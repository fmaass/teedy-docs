<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { type Tag } from '../api/tag'
import type { FacetTreeNodeData, OverflowNodeData } from '../composables/useCoOccurrenceTree'
import Tree from 'primevue/tree'
import SelectButton from 'primevue/selectbutton'

const { t } = useI18n()

interface TagTreeNode {
  key: string
  label: string
  // Tree/facet nodes carry a tag; facet trees additionally emit a terminal
  // overflow node whose data is the discriminated overflow shape.
  data: FacetTreeNodeData
  children: TagTreeNode[]
}

/** Discriminate the overflow node without extending the "__" key heuristic. */
function isOverflowNode(node: { data?: unknown }): node is { data: OverflowNodeData } {
  return !!node.data && (node.data as { kind?: string }).kind === 'overflow'
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

const viewModeOptions = computed(() => [
  { label: t('ui.tree_view'), value: 'tree' },
  { label: t('ui.facets_view'), value: 'facets' },
])

function nodeTagId(key: string): string {
  const idx = key.indexOf('__')
  return idx >= 0 ? key.slice(idx + 2) : key
}

// Sum a tree-mode node's own document count plus all descendants' counts,
// treating an unused tag (absent from tagCounts) as 0. Used so a parent whose
// documents live only on nested tags still shows a rolled-up total (#66).
function subtreeCount(node: any): number {
  let total = props.tagCounts[node.key] ?? 0
  for (const child of node.children ?? []) total += subtreeCount(child)
  return total
}

function getNodeCount(node: any): number | undefined {
  if (node.key?.includes('__')) {
    return node.data?.coCount
  }
  // Parent (tree-mode) node: display the rolled-up subtree total so a parent of
  // used-but-nested tags is never blank (#66). Suppress a 0 badge (whole subtree
  // unused) to preserve the "unused tags show no number" behaviour.
  if (node.children?.length) {
    const total = subtreeCount(node)
    return total > 0 ? total : undefined
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
        <!-- Terminal "…and K more" overflow node (#12): NON-interactive. No
             button role, no tabindex, no aria-pressed, no activation handlers —
             its key is not a real tag id and must never reach tagFilter. -->
        <div v-if="isOverflowNode(node)" class="tag-tree-node tag-overflow">
          <span class="tag-name tag-overflow-label">
            {{ t('ui.facets_overflow', { count: (node.data as OverflowNodeData).hiddenCount }) }}
          </span>
        </div>
        <div
          v-else
          class="tag-tree-node"
          :class="{
            'tag-active': selectedTagIds.has(nodeTagId(node.key)),
            'tag-excluded': excludedTagIds.has(nodeTagId(node.key)),
            'tag-dimmed': !selectedTagIds.has(nodeTagId(node.key)) && !excludedTagIds.has(nodeTagId(node.key)) && selectedTagIds.size > 0 && !((getNodeCount(node) ?? 0) > 0),
          }"
          role="button"
          tabindex="0"
          :aria-pressed="selectedTagIds.has(nodeTagId(node.key))"
          @click.stop="emit('selectTag', node.key)"
          @keydown.enter.stop="emit('selectTag', node.key)"
          @keydown.space.prevent="emit('selectTag', node.key)"
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
      <span class="meta-text">{{ t('document.no_tags') }}</span>
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
  background: color-mix(in srgb, var(--teedy-danger) 10%, transparent);
  text-decoration: line-through;
  opacity: 0.7;
}

.tag-tree-node.tag-dimmed { opacity: 0.4; }

.tag-tree-node.tag-overflow {
  cursor: default;
  font-style: italic;
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
}

.tag-tree-node.tag-overflow:hover { background: transparent; }

.tag-overflow-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-icon { font-size: 0.75rem; flex-shrink: 0; }
.state-icon.include { color: var(--p-primary-color); }
.state-icon.exclude { color: var(--teedy-danger); }

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
