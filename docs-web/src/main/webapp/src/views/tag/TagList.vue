<script setup lang="ts">
import { ref, computed } from 'vue'
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

const router = useRouter()
const toast = useToast()
const queryClient = useQueryClient()

const newTagName = ref('')
const newTagColor = ref('2aabd2')
const newTagParent = ref<string | null>(null)

const { data: tags, isLoading } = useQuery({
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
  const rootTags = allTags.filter((t) => !t.parent)
  function buildNode(tag: Tag): TagTreeNode {
    const children = allTags.filter((t) => t.parent === tag.id)
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
  { label: '(none — root level)', value: null },
  ...tagList.value.map((t) => ({ label: t.name, value: t.id })),
])

const { mutate: addTag } = useMutation({
  mutationFn: () => createTag(newTagName.value.trim(), '#' + newTagColor.value, newTagParent.value ?? undefined),
  onSuccess: () => {
    newTagName.value = ''
    newTagParent.value = null
    queryClient.invalidateQueries({ queryKey: ['tags'] })
    toast.add({ severity: 'success', summary: 'Tag created', life: 2000 })
  },
  onError: (error: unknown) => {
    const message = (error as ApiError).response?.data?.message || 'Failed to create tag'
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
      <h1>Tags</h1>
      <p class="page-subtitle">Organize documents with tags. Click a tag to edit its name, color, or parent.</p>
    </div>

    <!-- Create tag -->
    <Card class="mb-4" style="max-width: 520px">
      <template #content>
        <h3 class="section-title">Create tag</h3>
        <div class="create-row">
          <ColorPicker v-model="newTagColor" />
          <InputText
            v-model="newTagName"
            placeholder="Tag name"
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
            placeholder="Parent tag (optional)"
            class="flex-1"
            showClear
          />
          <Button label="Create" icon="pi pi-plus" @click="handleAddTag" />
        </div>
      </template>
    </Card>

    <!-- Tag tree -->
    <Card>
      <template #content>
        <div v-if="isLoading" class="text-muted text-sm">Loading tags...</div>
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
        <div v-else class="empty-state">
          <i class="pi pi-tags" />
          <p>No tags yet. Create one above.</p>
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
