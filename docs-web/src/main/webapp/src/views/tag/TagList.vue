<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, createTag, getTagStats, type Tag } from '../../api/tag'
import { queryKeys } from '../../api/queryKeys'
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

// #298: tag management is where tags get cleaned up, so every node carries its own
// document count. ONE query for the whole tree — the endpoint already returns the
// full tagId -> count map, so a per-node request would be N round-trips for data
// that arrives in one. Deliberately unlike the sidebar facet panel (TagTreePanel),
// which rolls counts up the subtree and hides zeroes: here the count is the tag's
// OWN documents, and a zero is the answer being looked for (an unused tag), not
// noise. A tag absent from the map carries no documents, hence 0.
//
// The key is the app-wide `queryKeys.tagStats()`, NOT TagEdit's private `['tag-stats']`:
// only the shared one is in `tagCountKeys`, the list a document tag add/remove/bulk edit
// invalidates, so the counts on this page follow tagging done elsewhere in the session.
// Sharing TagEdit's key instead would ALSO have made this page's fetch the one that fills
// that cache entry, freezing TagEdit's own count at whatever was true when the tag list was
// opened — which is exactly how it broke tags.spec.ts:118 (#281) before this key choice.
// `refetchOnMount: 'always'` because this is the cleanup screen: opening it must show the
// counts as they are now, not a value up to the shared staleTime old.
const { data: tagStats, isSuccess: tagStatsLoaded } = useQuery({
  queryKey: queryKeys.tagStats(),
  queryFn: () => getTagStats().then((r) => r.data.stats),
  staleTime: 60_000,
  refetchOnMount: 'always',
})

// A count is only shown once the stats actually arrived. On this screen a 0 is an
// instruction ("nothing uses this tag, delete it"), so printing the `?? 0` fallback
// while the request is in flight — or after it FAILED — would invite deleting tags
// that hold documents. No data, no number. Once loaded, a tag missing from the map
// genuinely carries no documents and its 0 is real.
const countsLoaded = computed(() => tagStatsLoaded.value && !!tagStats.value)

function docCount(tagId: string): number {
  return tagStats.value?.[tagId] ?? 0
}

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

const expandedKeys = ref<Record<string, boolean>>({})

// The tree filter must REVEAL matches nested under collapsed parents (#279):
// PrimeVue's Tree filter winnows the rendered nodes to matches and their
// ancestors, but leaves expansion entirely to expandedKeys — without help, a
// matching child would stay invisible inside its still-collapsed parent. So
// while a query is active every parent node is force-expanded (the filtered
// tree contains only matching branches, so this reveals exactly the matches),
// and the user's own expansion state is restored once the query is cleared.
// The handler listens for the native `input` event — which falls through to the
// Tree's root element, and the filter box is the only input the Tree renders —
// rather than the component's `filter` event, which fires on keyup only and
// would miss a mouse-driven paste or cut.
let preFilterExpandedKeys: Record<string, boolean> | null = null

const allParentKeys = computed(() => {
  const keys: Record<string, boolean> = {}
  for (const tag of tagList.value) {
    if (tag.parent) keys[tag.parent] = true
  }
  return keys
})

function onTreeFilterInput(event: Event) {
  const input = event.target as HTMLInputElement | null
  if (!input || typeof input.value !== 'string') return
  if (input.value.trim().length > 0) {
    preFilterExpandedKeys ??= { ...expandedKeys.value }
    expandedKeys.value = { ...allParentKeys.value }
  } else if (preFilterExpandedKeys) {
    expandedKeys.value = preFilterExpandedKeys
    preFilterExpandedKeys = null
  }
}

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
        <!-- filterMode "lenient" (the default, stated deliberately — #279): a query
             matching a nested tag keeps its ancestor chain visible, and a query
             matching a parent keeps the parent's whole subtree. "strict" would prune
             every non-matching child of a matched parent — hiding exactly the
             sub-tags being looked for under a remembered branch name. -->
        <Tree
          v-else-if="tagTreeNodes.length"
          :value="tagTreeNodes"
          v-model:expandedKeys="expandedKeys"
          selectionMode="single"
          filter
          filterMode="lenient"
          :filterPlaceholder="t('ui.tags_page.filter_placeholder')"
          @node-select="selectTag"
          @input="onTreeFilterInput"
          class="tag-tree"
        >
          <template #default="{ node }">
            <span class="tag-node">
              <span class="tag-dot" :style="{ background: node.data.color }" />
              <span class="tag-label">{{ node.label }}</span>
              <span v-if="countsLoaded" class="tag-count">{{ docCount(node.key) }}</span>
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

/* Mirrors the facet panel's count badge (TagTreePanel .tag-count) so the same
   number reads the same way in both trees. */
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
