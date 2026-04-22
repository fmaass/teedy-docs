<script setup lang="ts">
import { type Tag } from '../api/tag'
import Tree from 'primevue/tree'
import SelectButton from 'primevue/selectbutton'

interface TagTreeNode {
  key: string
  label: string
  data: Tag
  children: TagTreeNode[]
}

interface ModeOption {
  label: string
  value: 'and' | 'or'
}

const props = defineProps<{
  tagMode: 'and' | 'or'
  modeOptions: ModeOption[]
  tagTreeNodes: TagTreeNode[]
  expandedKeys: Record<string, boolean>
  selectedTagIds: Set<string>
  excludedTagIds: Set<string>
  tagCounts: Record<string, number>
  viewMode?: 'tree' | 'facets'
}>()

const emit = defineEmits<{
  'update:tagMode': [value: 'and' | 'or']
  'update:viewMode': [value: 'tree' | 'facets']
  selectTag: [tagId: string]
}>()

const viewModeOptions = [
  { label: 'Tree', value: 'tree' },
  { label: 'Facets', value: 'facets' },
]

function nodeTagId(key: string): string {
  const idx = key.indexOf('__')
  return idx >= 0 ? key.slice(idx + 2) : key
}

function getNodeCount(node: any): number | undefined {
  if (node.key?.includes('__')) {
    return node.data?.coCount
  }
  return props.tagCounts[node.key]
}
</script>

<template>
  <div class="panel-controls">
    <SelectButton
      v-if="viewMode !== undefined"
      :model-value="viewMode"
      :options="viewModeOptions"
      optionLabel="label"
      optionValue="value"
      :allowEmpty="false"
      class="mode-toggle-sm"
      @update:model-value="(v: string) => emit('update:viewMode', v as 'tree' | 'facets')"
    />
    <SelectButton
      :model-value="tagMode"
      :options="modeOptions"
      optionLabel="label"
      optionValue="value"
      :allowEmpty="false"
      class="mode-toggle-sm"
      @update:model-value="(value) => emit('update:tagMode', value as 'and' | 'or')"
    />
  </div>
  <div class="panel-tree">
    <Tree
      :value="tagTreeNodes"
      :expandedKeys="expandedKeys"
      class="tag-tree"
    >
      <template #default="{ node }">
        <div
          class="tag-tree-node"
          :class="{
            'tag-active': selectedTagIds.has(nodeTagId(node.key)),
            'tag-excluded': excludedTagIds.has(nodeTagId(node.key)),
            'tag-dimmed': !selectedTagIds.has(nodeTagId(node.key)) && !excludedTagIds.has(nodeTagId(node.key)) && selectedTagIds.size > 0 && !((getNodeCount(node) ?? 0) > 0),
          }"
          @click.stop="emit('selectTag', node.key)"
        >
          <i v-if="selectedTagIds.has(nodeTagId(node.key))" class="pi pi-check-circle state-icon include" />
          <i v-else-if="excludedTagIds.has(nodeTagId(node.key))" class="pi pi-minus-circle state-icon exclude" />
          <span class="tag-dot" :style="{ background: node.data.color }" />
          <span class="tag-name">{{ node.label }}</span>
          <span class="tag-count" v-if="getNodeCount(node) != null">
            {{ getNodeCount(node) }}
          </span>
        </div>
      </template>
    </Tree>
    <div v-if="!tagTreeNodes.length" class="tag-empty">
      <span class="meta-text">No tags yet</span>
    </div>
  </div>
</template>

<style scoped>
.panel-controls {
  padding: 0 0.75rem 0.5rem;
  display: flex;
  gap: 0.375rem;
  flex-shrink: 0;
}

.mode-toggle-sm :deep(.p-selectbutton) {
  height: 1.75rem;
}

.mode-toggle-sm :deep(.p-togglebutton) {
  padding: 0.125rem 0.5rem;
  font-size: 0.6875rem;
  font-weight: 600;
}


.panel-tree {
  flex: 1;
  overflow-y: auto;
  padding: 0 0.25rem 0.5rem;
}

.tag-tree :deep(.p-tree) {
  border: none;
  padding: 0;
  background: transparent;
}

.tag-tree :deep(.p-tree-node-content) {
  padding: 0.125rem 0;
}

.tag-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  transition: background 0.12s;
  width: 100%;
}

.tag-tree-node:hover { background: var(--p-content-hover-background); }

.tag-tree-node.tag-active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  font-weight: 600;
}

.tag-tree-node.tag-excluded {
  background: color-mix(in srgb, var(--teedy-disabled-color) 10%, transparent);
  text-decoration: line-through;
  opacity: 0.7;
}

.tag-tree-node.tag-dimmed { opacity: 0.4; }

.state-icon { font-size: 0.75rem; flex-shrink: 0; }
.state-icon.include { color: var(--p-primary-color); }
.state-icon.exclude { color: var(--teedy-disabled-color); }

.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tag-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-count {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  padding: 0.0625rem 0.375rem;
  border-radius: 10px;
  min-width: 1.25rem;
  text-align: center;
  flex-shrink: 0;
}

.tag-empty {
  padding: 1rem;
  text-align: center;
}

.meta-text {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
