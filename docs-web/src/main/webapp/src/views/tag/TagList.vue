<script setup lang="ts">
import { ref, computed, onBeforeUnmount, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import {
  listTags,
  createTag,
  getTagStats,
  getTagMaintenance,
  deleteTagSubtree,
  deleteUnusedTags,
  type Tag,
  type TagMaintenanceItem,
  type TagDeletionReport,
} from '../../api/tag'
import { queryKeys, tagCountKeys } from '../../api/queryKeys'
import Tree from 'primevue/tree'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import ContextMenu from 'primevue/contextmenu'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
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
// The key is the app-wide `queryKeys.tagStats()`: only the shared one is in
// `tagCountKeys`, the list a document tag add/remove/bulk edit invalidates, so the counts
// on this page follow tagging done elsewhere in the session. The tag EDIT page reads that
// same entry, which makes this page's fetch the one that fills it — so
// `refetchOnMount: 'always'` is load-bearing, not a nicety: this is the cleanup screen,
// opening it must show the counts as they are now, and a shared entry left to go
// staleTime-old is exactly what showed a freshly-tagged tag as empty on the edit page
// (tags.spec.ts, #281).
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

// ---------------------------------------------------------------------------------------------
// #298 parts 1 and 2 — removing unused tags.
//
// Only a FULLY unused subtree may go: the tag itself carries no document and neither does any
// descendant. Nothing still on a document is ever deleted, and nothing is un-assigned to make a
// tag deletable ("as long as tags are sticking to any doc, do not delete them generally" — the
// reporter). An unused chain of parents above a USED deep child therefore keeps its structure.
//
// The verdict is the SERVER's, read whole from /tag/maintenance and never recomputed here: it
// depends on tags this account may not even see (a hidden used child under a visible parent), so
// a client-side roll-up of the counts shown in the tree would offer deletes the server refuses —
// and, worse, would read an unreachable branch as empty. A missing verdict therefore disables
// every delete rather than defaulting to "unused".
// ---------------------------------------------------------------------------------------------
const { data: maintenance } = useQuery({
  queryKey: queryKeys.tagMaintenance(),
  queryFn: () => getTagMaintenance().then((r) => r.data.tags),
  staleTime: 60_000,
  // Same reason as the counts above: this is the cleanup screen, so opening it must ask again
  // rather than answer from whatever the session cached.
  refetchOnMount: 'always',
})

const maintenanceById = computed(() => {
  const map = new Map<string, TagMaintenanceItem>()
  for (const item of maintenance.value ?? []) map.set(item.id, item)
  return map
})

function isDeletable(tagId: string): boolean {
  return maintenanceById.value.get(tagId)?.deletable ?? false
}

/** The reason a node's delete is disabled, as the tooltip and the context menu state it. */
function deleteHint(tagId: string): string {
  const item = maintenanceById.value.get(tagId)
  if (!item) return t('ui.tags_page.blocked_unknown')
  if (item.deletable) return t('ui.tags_page.delete_unused_tag')
  switch (item.reason) {
    case 'documents':
      return t('ui.tags_page.blocked_documents', { count: item.subtreeDocuments })
    // The count beside the node is ACTIVE documents only, so a tag held by the trash shows a 0.
    // Quoting that count here would send the user looking through a list the document is not in.
    case 'trash':
      return t('ui.tags_page.blocked_trash')
    case 'rule':
      return t('ui.tags_page.blocked_rule')
    case 'other':
      return t('ui.tags_page.blocked_other')
    default:
      return t('ui.tags_page.blocked_unknown')
  }
}

/** Descendants of a tag in the visible tree — how many tags a subtree delete takes along. */
function descendantCount(tagId: string): number {
  const children = new Map<string, string[]>()
  for (const tag of tagList.value) {
    if (tag.parent) {
      const list = children.get(tag.parent)
      if (list) list.push(tag.id)
      else children.set(tag.parent, [tag.id])
    }
  }
  let count = 0
  const queue = [tagId]
  while (queue.length) {
    for (const childId of children.get(queue.pop()!) ?? []) {
      count++
      queue.push(childId)
    }
  }
  return count
}

function invalidateAfterTagRemoval() {
  queryClient.invalidateQueries({ queryKey: queryKeys.tags() })
  for (const key of tagCountKeys) {
    queryClient.invalidateQueries({ queryKey: key })
  }
}

function reportDeletion(report: TagDeletionReport) {
  toast.add({
    severity: 'success',
    summary: t('ui.tags_page.deleted_report', {
      count: report.tags.length,
      names: report.tags.map((tag) => tag.name).join(', '),
    }),
    life: 4000,
  })
  // The server re-checks each tag immediately before removing it and keeps one that became used in
  // the meantime. Saying so is the honest half of the report: without it a kept tag is
  // indistinguishable from one that was never in the run.
  if (report.blocked.length) {
    toast.add({
      severity: 'warn',
      summary: t('ui.tags_page.deleted_skipped', {
        names: report.blocked.map((tag) => tag.name).join(', '),
      }),
      life: 6000,
    })
  }
}

function reportDeletionFailure() {
  toast.add({ severity: 'error', summary: t('ui.tag_edit.failed_delete'), life: 3000 })
}

/**
 * Asks before deleting, always — the confirm names the tag and, when the branch has children,
 * how many tags go with it, so nobody removes a whole subtree thinking they clicked one tag.
 */
function requestDelete(tagId: string) {
  if (!isDeletable(tagId)) return
  const item = maintenanceById.value.get(tagId)!
  const descendants = descendantCount(tagId)
  confirmDanger({
    header: t('ui.tags_page.delete_unused_tag'),
    message: descendants
      ? t('ui.tags_page.delete_subtree_confirm', { name: item.name, count: descendants + 1 })
      : t('ui.tags_page.delete_tag_confirm', { name: item.name }),
    accept: () =>
      deleteTagSubtree(tagId)
        .then((response) => {
          invalidateAfterTagRemoval()
          reportDeletion(response.data)
        })
        .catch(reportDeletionFailure),
  })
}

// Right-click on a node, the affordance the reporter asked for. The menu carries the same
// verdict as the row button, and states the reason as a second, permanently disabled entry —
// a greyed-out command with no explanation is what made the old per-document menu confusing.
const nodeMenu = useTemplateRef<InstanceType<typeof ContextMenu>>('nodeMenu')
const menuTagId = ref<string | null>(null)

const nodeMenuItems = computed(() => {
  const tagId = menuTagId.value
  if (!tagId) return []
  const deletable = isDeletable(tagId)
  const items = [
    {
      label: t('ui.tags_page.delete_unused_tag'),
      icon: 'pi pi-trash',
      disabled: !deletable,
      command: () => requestDelete(tagId),
    },
  ]
  if (!deletable) {
    items.push({
      label: deleteHint(tagId),
      icon: 'pi pi-info-circle',
      disabled: true,
      command: () => {},
    })
  }
  return items
})

// PrimeVue's own outside-click dismissal does not close THIS menu. MEASURED on primevue 4.5.5
// (e2e probe, 2026-08-25): with the menu open, a capture-phase document listener sees the click
// on an element outside the panel — `menuInDom=true, menuContainsTarget=false` — and the panel
// stays open regardless; only Escape or picking an item closes it. A context menu that survives a
// click elsewhere on the page sits over the tree it is meant to act on, so the page binds its own
// dismissal.
//
// It is bound HERE, when the menu is opened, and deliberately not on the component's `show`
// event: `show` is emitted from the very same after-enter hook that binds the component's own
// (ineffective) outside-click listener, so a dismissal hung off it would inherit whatever keeps
// that one from working. A right-click emits no `click`, so the listener cannot see the event
// that opened the menu and dismiss it immediately.
function openNodeMenu(event: MouseEvent, tagId: string) {
  menuTagId.value = tagId
  nodeMenu.value?.show(event)
  document.addEventListener('click', dismissNodeMenu, true)
}

function dismissNodeMenu(event: MouseEvent) {
  const panel = document.querySelector('.p-contextmenu')
  if (panel && panel.contains(event.target as Node)) return
  unbindNodeMenuDismiss()
  nodeMenu.value?.hide()
}

// Also called from the menu's `hide` event, so picking an item (which PrimeVue closes the menu
// for) leaves no listener behind. Re-binding is idempotent, so a `hide` that never arrives costs
// nothing — the next outside click unbinds anyway.
function unbindNodeMenuDismiss() {
  document.removeEventListener('click', dismissNodeMenu, true)
}

// The menu can still be open when the route changes, and a listener left on `document` would
// outlive the page that owns it.
onBeforeUnmount(unbindNodeMenuDismiss)

// The instance-wide cleanup. Two deliberate steps: opening the dialog PREVIEWS what would go
// and deletes nothing, and the confirm inside it deletes and then reports exactly what went.
const cleanupVisible = ref(false)
const cleanupResult = ref<TagDeletionReport | null>(null)

const cleanupRoots = computed(() => (maintenance.value ?? []).filter((item) => item.root))
const cleanupTotal = computed(() => (maintenance.value ?? []).filter((item) => item.deletable).length)

function openCleanup() {
  cleanupResult.value = null
  cleanupVisible.value = true
}

const { mutate: runCleanup, isPending: cleanupPending } = useMutation({
  mutationFn: () => deleteUnusedTags().then((r) => r.data),
  onSuccess: (report) => {
    cleanupResult.value = report
    invalidateAfterTagRemoval()
    reportDeletion(report)
  },
  onError: reportDeletionFailure,
})
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
        <div class="tree-toolbar">
          <Button
            class="tag-cleanup-btn"
            :label="t('ui.tags_page.cleanup_unused')"
            icon="pi pi-eraser"
            severity="secondary"
            outlined
            size="small"
            @click="openCleanup"
          />
        </div>
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
            <span class="tag-node" @contextmenu.prevent.stop="openNodeMenu($event, node.key)">
              <span class="tag-dot" :style="{ background: node.data.color }" />
              <span class="tag-label">{{ node.label }}</span>
              <span v-if="countsLoaded" class="tag-count">{{ docCount(node.key) }}</span>
              <!-- The row affordance beside the right-click menu: a context menu has no touch
                   equivalent, so on a phone this button is the ONLY way to reach the action. -->
              <Button
                class="tag-delete-btn"
                icon="pi pi-trash"
                text
                rounded
                severity="danger"
                size="small"
                :disabled="!isDeletable(node.key)"
                :title="deleteHint(node.key)"
                :aria-label="deleteHint(node.key)"
                @click.stop="requestDelete(node.key)"
              />
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

    <ContextMenu ref="nodeMenu" :model="nodeMenuItems" @hide="unbindNodeMenuDismiss" />

    <!-- Unused-tag cleanup: preview first, delete only on the dialog's own confirm, then report. -->
    <Dialog
      v-model:visible="cleanupVisible"
      modal
      :header="t('ui.tags_page.cleanup_header')"
      class="tag-cleanup-dialog"
      :style="{ width: '34rem', maxWidth: '95vw' }"
    >
      <template v-if="cleanupResult">
        <div class="cleanup-result">
          <p class="cleanup-result-summary">
            {{ t('ui.tags_page.cleanup_result', { count: cleanupResult.tags.length }) }}
          </p>
          <ul class="cleanup-list">
            <li v-for="tag in cleanupResult.tags" :key="tag.id" class="cleanup-result-row">{{ tag.path }}</li>
          </ul>
          <p v-if="cleanupResult.blocked.length" class="cleanup-skipped">
            {{ t('ui.tags_page.deleted_skipped', {
              names: cleanupResult.blocked.map((tag) => tag.name).join(', '),
            }) }}
          </p>
        </div>
      </template>
      <template v-else>
        <p class="cleanup-intro">{{ t('ui.tags_page.cleanup_intro') }}</p>
        <div v-if="!cleanupRoots.length" class="cleanup-empty">{{ t('ui.tags_page.cleanup_none') }}</div>
        <ul v-else class="cleanup-list">
          <li v-for="root in cleanupRoots" :key="root.id" class="cleanup-row">
            <span class="cleanup-path">{{ root.path }}</span>
            <span v-if="descendantCount(root.id)" class="cleanup-extra">
              {{ t('ui.tags_page.cleanup_subtree_size', { count: descendantCount(root.id) }) }}
            </span>
          </li>
        </ul>
      </template>
      <template #footer>
        <Button
          class="cleanup-close-btn"
          :label="cleanupResult ? t('close') : t('cancel')"
          severity="secondary"
          outlined
          @click="cleanupVisible = false"
        />
        <Button
          v-if="!cleanupResult"
          class="cleanup-confirm-btn"
          :label="t('ui.tags_page.cleanup_confirm', { count: cleanupTotal })"
          icon="pi pi-trash"
          severity="danger"
          :disabled="!cleanupTotal || cleanupPending"
          :loading="cleanupPending"
          @click="runCleanup()"
        />
      </template>
    </Dialog>
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

.tree-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}

/* The row action sits at the trailing edge of the node and keeps its size whatever the label
   does; a shrunk icon button is a mis-click hazard on a destructive action. */
.tag-delete-btn {
  flex-shrink: 0;
  margin-left: 0.125rem;
}
.tag-node :deep(.p-button.tag-delete-btn) {
  width: 1.75rem;
  height: 1.75rem;
}

.cleanup-intro {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.cleanup-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 18rem;
  overflow-y: auto;
}

.cleanup-row,
.cleanup-result-row {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  padding: 0.3125rem 0;
  border-bottom: 1px solid var(--p-content-border-color);
  font-size: 0.875rem;
}
.cleanup-row:last-child,
.cleanup-result-row:last-child {
  border-bottom: none;
}

.cleanup-extra {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

/* The kept-tags line is a warning, not a failure: the sweep did run. */
.cleanup-skipped {
  margin: 0.75rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-orange-500, var(--p-text-muted-color));
}

.cleanup-empty,
.cleanup-result-summary {
  margin: 0 0 0.5rem;
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
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
