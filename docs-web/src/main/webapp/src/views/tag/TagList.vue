<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, createTag, type Tag } from '../../api/tag'
import Tree from 'primevue/tree'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const router = useRouter()
const toast = useToast()
const queryClient = useQueryClient()

const newTagName = ref('')
const newTagColor = ref('2aabd2')
const newTagParent = ref<string | null>(null)

const { data: tags, isLoading, isError, refetch } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const tagList = computed(() => tags.value ?? [])

interface TagTreeNode {
  key: string
  label: string
  data: Tag
  children: TagTreeNode[]
}

interface ApiError {
  response?: {
    data?: {
      message?: string
    }
  }
}

const tagTreeNodes = computed(() => {
  const allTags = tagList.value
  const rootTags = allTags.filter((tag) => !tag.parent)
  function buildNode(tag: Tag): TagTreeNode {
    const children = allTags.filter((child) => child.parent === tag.id)
    return {
      key: tag.id,
      label: tag.name,
      data: tag,
      children: children.map(buildNode),
    }
  }
  return rootTags.map(buildNode)
})

const parentOptions = computed(() => [
  { label: t('ui.tags_page.none_root'), value: null },
  ...tagList.value.map((tag) => ({ label: tag.name, value: tag.id })),
])

const { mutate: addTag } = useMutation({
  mutationFn: () => createTag(newTagName.value.trim(), '#' + newTagColor.value, newTagParent.value ?? undefined),
  onSuccess: () => {
    newTagName.value = ''
    newTagParent.value = null
    queryClient.invalidateQueries({ queryKey: ['tags'] })
    toast.add({ severity: 'success', summary: t('ui.tags_page.tag_created'), life: 2000 })
  },
  onError: (error: unknown) => {
    const message = (error as ApiError).response?.data?.message || t('ui.tags_page.failed_create_tag')
    toast.add({ severity: 'error', summary: message, life: 3000 })
  },
})

function handleAddTag() {
  if (!newTagName.value.trim()) return
  addTag()
}

function selectTag(node: { key: string }) {
  router.push({ name: 'tag-edit', params: { id: node.key } })
}
</script>

<template>
  <div class="tag-list-page">
    <div class="page-header">
      <h1>{{ t('ui.tags_page.title') }}</h1>
      <p class="page-subtitle">{{ t('ui.tags_page.subtitle') }}</p>
    </div>

    <!-- Create tag -->
    <Card class="mb-4" style="max-width: 520px">
      <template #content>
        <h3 class="section-title">{{ t('ui.tags_page.create_tag') }}</h3>
        <div class="create-row">
          <ColorPicker v-model="newTagColor" />
          <InputText
            v-model="newTagName"
            :placeholder="t('ui.tags_page.tag_name_placeholder')"
            class="flex-1"
            @keydown.enter="handleAddTag"
          />
        </div>
        <div class="create-row mt-3">
          <Select
            v-model="newTagParent"
            :options="parentOptions"
            optionLabel="label"
            optionValue="value"
            :placeholder="t('ui.tags_page.parent_placeholder')"
            class="flex-1"
            showClear
          />
          <Button :label="t('create')" icon="pi pi-plus" @click="handleAddTag" />
        </div>
      </template>
    </Card>

    <!-- Tag tree -->
    <Card>
      <template #content>
        <div v-if="isLoading" class="text-muted text-sm">{{ t('ui.tags_page.loading_tags') }}</div>
        <Tree
          v-else-if="tagTreeNodes.length"
          :value="tagTreeNodes"
          selectionMode="single"
          @node-select="selectTag"
          class="tag-tree"
        >
          <template #default="{ node }">
            <span class="tag-node">
              <span class="tag-dot" :style="{ background: node.data.color }" />
              <span class="tag-label">{{ node.label }}</span>
            </span>
          </template>
        </Tree>
        <ErrorState v-else-if="isError" @retry="refetch()" />
        <div v-else class="empty-state">
          <i class="pi pi-tags" />
          <p>{{ t('ui.tags_page.no_tags') }}</p>
        </div>
      </template>
    </Card>
  </div>
</template>

<style scoped>
.tag-list-page {
  padding: 1.5rem;
  max-width: 700px;
}

.page-header {
  margin-bottom: 1.25rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}
.page-subtitle {
  margin: 0.2rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.section-title {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
}

.create-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
/* The submit button must hold its label width; the flex-1 Select beside it absorbs
   the horizontal squeeze. Without this the button shrinks below its content on a
   narrow row and clips its label (e.g. German "Erstellen" -> "Erste"). The Select
   needs min-width:0 to shrink below its intrinsic content width (a flex item defaults
   to min-width:auto) so the row itself never overflows the narrow viewport. */
.create-row :deep(.p-button) {
  flex-shrink: 0;
}
.create-row :deep(.p-select) {
  min-width: 0;
}

.tag-tree :deep(.p-tree) {
  border: none;
  padding: 0;
  background: transparent;
}

.tag-node {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.125rem 0;
  cursor: pointer;
}

.tag-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1px solid var(--p-content-border-color);
}

.tag-label {
  font-size: 0.875rem;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2rem;
  color: var(--p-text-muted-color);
}
.empty-state i {
  font-size: 2.5rem;
  margin-bottom: 0.75rem;
}
.empty-state p {
  margin: 0;
}
</style>
