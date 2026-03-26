<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, createTag } from '../../api/tag'
import Tree from 'primevue/tree'
import InputText from 'primevue/inputtext'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const router = useRouter()
const toast = useToast()
const queryClient = useQueryClient()

const newTagName = ref('')
const newTagColor = ref('2aabd2')

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const tagTreeNodes = computed(() => {
  const allTags = tags.value ?? []
  const rootTags = allTags.filter((t) => !t.parent)
  function buildNode(tag: typeof allTags[0]): any {
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

const { mutate: addTag } = useMutation({
  mutationFn: () => createTag(newTagName.value.trim(), '#' + newTagColor.value),
  onSuccess: () => {
    newTagName.value = ''
    queryClient.invalidateQueries({ queryKey: ['tags'] })
    toast.add({ severity: 'success', summary: 'Tag created', life: 2000 })
  },
  onError: (e: any) => {
    toast.add({ severity: 'error', summary: e.response?.data?.message || 'Failed to create tag', life: 3000 })
  },
})

function handleAddTag() {
  if (!newTagName.value.trim()) return
  addTag()
}

function selectTag(node: any) {
  router.push({ name: 'tag-edit', params: { id: node.key } })
}
</script>

<template>
  <div class="teedy-page">
    <aside class="teedy-sidebar">
      <div class="p-4">
        <!-- Add tag form -->
        <div class="flex gap-2 mb-4">
          <ColorPicker v-model="newTagColor" />
          <InputText
            v-model="newTagName"
            placeholder="New tag"
            class="flex-1"
            size="small"
            @keydown.enter="handleAddTag"
          />
          <Button label="Add" icon="pi pi-plus" size="small" @click="handleAddTag" />
        </div>

        <!-- Tag tree -->
        <Tree
          :value="tagTreeNodes"
          selectionMode="single"
          @node-select="selectTag"
        >
          <template #default="{ node }">
            <span class="tag-node">
              <span class="tag-dot" :style="{ background: node.data.color }" />
              {{ node.label }}
            </span>
          </template>
        </Tree>
      </div>
    </aside>
    <main class="teedy-content p-4">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.tag-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
}
.tag-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
