<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, keepPreviousData, useQueryClient } from '@tanstack/vue-query'
import { listDocuments, getDocument, type DocumentListItem } from '../../api/document'
import { useTagFilterStore } from '../../stores/tagFilter'
import { useDocumentTags } from '../../composables/useDocumentTags'
import ContextMenu from 'primevue/contextmenu'
import type { MenuItem } from 'primevue/menuitem'
import type { DataTablePageEvent, DataTableSortEvent } from 'primevue/datatable'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import DocumentSearchBar from '../../components/DocumentSearchBar.vue'
import TagFilterChips from '../../components/TagFilterChips.vue'
import ToggleButton from 'primevue/togglebutton'
import DocumentTable from '../../components/DocumentTable.vue'
import DocumentSlideOver from '../../components/DocumentSlideOver.vue'
import BulkActionBar from '../../components/BulkActionBar.vue'
import { updateDocument, deleteDocument } from '../../api/document'
import {
  runBulk,
  buildAddTagParams,
  buildLanguageParams,
  type BulkResult,
} from '../../utils/bulkOps'
import { useClampedOffset } from '../../composables/useClampedOffset'
import { queryKeys, tagCountKeys } from '../../api/queryKeys'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const tf = useTagFilterStore()
const queryClient = useQueryClient()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const { addTag, removeTag } = useDocumentTags()

// --- Pagination & sort state ---

const PAGE_SIZE = 20
const pageOffset = ref(0)
const sortField = ref<string>('create_date')
const sortOrder = ref<number>(-1)

// "Assigned to me" workflow filter — sent as the typed search[searchworkflow]=me param, NOT folded
// into the free-text search. Restricts the list to documents whose current step targets the viewer.
//
// The toggle lives in component state (not the tagFilter store), but it MUST round-trip through the
// URL like every other filter dimension: the returnTo query carries `workflow=me`, and the documents
// route re-hydrates it on BOTH entry paths — an in-app Back (push(returnTo) changes route.query) and
// a cold URL load. Hydration is an immediate route-query watcher; only the scalar string "me"
// activates (arrays/empty/unknown are inactive), matching the store's canonicalization contract.
const workflowMe = ref(false)

watch(
  () => route.query.workflow,
  (v) => {
    workflowMe.value = v === 'me'
    // Canonicalize INVALID values away on entry: only the scalar "me" is valid.
    // Any other PRESENT value (unknown scalar, empty string, array, bare ?workflow)
    // must be rewritten out of the URL here — the toggle watcher below early-returns
    // for it (off == not-'me') and the store's rewrite only fires on tag/text/mode
    // changes, so without this replace an invalid value would sit in the URL
    // indefinitely.
    //
    // The replace is SURGICAL: the current route query minus the workflow key —
    // NEVER a rebuild from tf.buildFilterQuery(). On a cold load the tag store
    // defers tag-ID hydration until the tags request settles, so the serializer
    // is still empty at this moment; rebuilding from it would drop valid raw
    // params (?tags=a&workflow=them would lose tags=a). The store's own
    // hydration/canonicalization machinery handles the other dimensions on its
    // own schedule.
    if (v !== undefined && v !== 'me') {
      const rest = { ...route.query }
      delete rest.workflow
      router.replace({ name: 'documents', query: rest })
    }
  },
  { immediate: true },
)

// A user toggle drives the canonical URL: replace with the store's full query
// (which now preserves/omits `workflow=me` per the current route) plus this
// toggle's desired state. The guard keeps this from re-firing on hydration —
// only write when the URL doesn't already reflect the toggle.
watch(workflowMe, (on) => {
  const active = route.query.workflow === 'me'
  if (on === active) return
  const query = { ...tf.buildFilterQuery() }
  if (on) query.workflow = 'me'
  else delete query.workflow
  router.replace({ name: 'documents', query })
})

const SORT_FIELD_MAP: Record<string, number> = { title: 1, create_date: 3 }

watch([() => tf.combinedSearch, () => tf.tagMode, workflowMe], () => {
  pageOffset.value = 0
})

const { data: documentsData, isLoading, isError, refetch } = useQuery({
  queryKey: computed(() => [...queryKeys.documents(), {
    search: tf.combinedSearch,
    tagMode: tf.tagMode,
    workflowMe: workflowMe.value,
    offset: pageOffset.value,
    sortField: sortField.value,
    sortOrder: sortOrder.value,
  }]),
  queryFn: () =>
    listDocuments({
      limit: PAGE_SIZE,
      offset: pageOffset.value,
      sort_column: SORT_FIELD_MAP[sortField.value] ?? 3,
      asc: sortOrder.value === 1,
      search: tf.combinedSearch || undefined,
      'search[tagMode]': tf.selectedTagIds.size > 1 ? tf.tagMode : undefined,
      'search[searchworkflow]': workflowMe.value ? 'me' : undefined,
    }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => documentsData.value?.documents ?? [])
const totalCount = computed(() => documentsData.value?.total ?? 0)

// Bulk-deleting the last item of a page > 1 refetches with a now-stale offset and
// the server returns zero rows while total is still positive — a false-empty page
// with no paginator to escape. Clamp back to the last valid page when that happens.
useClampedOffset(documentsData, isLoading, pageOffset, PAGE_SIZE)

function onPage(event: DataTablePageEvent) {
  pageOffset.value = event.first
}

function onSort(event: DataTableSortEvent) {
  sortField.value = (event.sortField as string) ?? 'create_date'
  sortOrder.value = event.sortOrder ?? -1
  pageOffset.value = 0
}

// --- Quick tagging context menu ---

const contextMenuDoc = ref<DocumentListItem | null>(null)
const contextMenu = ref()

function onDocContextMenu(event: Event, doc: DocumentListItem) {
  if (!(event instanceof MouseEvent)) return
  event.preventDefault()
  contextMenuDoc.value = doc
  contextMenu.value?.show(event)
}

async function quickAddTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  await addTag(doc.id, tagId)
  contextMenu.value?.hide()
}

async function quickRemoveTag(tagId: string) {
  const doc = contextMenuDoc.value
  if (!doc) return
  await removeTag(doc.id, tagId)
  contextMenu.value?.hide()
}

// --- Slide-over panel ---

const slideOverOpen = ref(false)
const slideOverDocId = ref<string | null>(null)

const { data: slideOverDoc, isLoading: slideOverLoading, error: slideOverError } = useQuery({
  queryKey: computed(() => queryKeys.document(slideOverDocId.value!)),
  queryFn: () => getDocument(slideOverDocId.value!).then((r) => r.data),
  enabled: computed(() => !!slideOverDocId.value && slideOverOpen.value),
})

watch(slideOverError, (err) => {
  if (err) {
    slideOverOpen.value = false
    toast.add({ severity: 'error', summary: t('ui.failed_to_load', { item: t('ui.item_document') }), life: 3000 })
  }
})

function openSlideOver(doc: DocumentListItem) {
  slideOverDocId.value = doc.id
  slideOverOpen.value = true
}

const availableTagsForSlideOver = computed(() => {
  if (!slideOverDoc.value) return []
  const docTagIds = new Set(slideOverDoc.value.tags?.map((t) => t.id) ?? [])
  return tf.allTags.filter((t) => !docTagIds.has(t.id))
})

async function addTagToSlideOver(tagId: string) {
  if (!slideOverDoc.value || !tagId) return
  // addTag invalidates ['document', id]; the slide-over query refetches the
  // authoritative doc — no manual setQueryData with a client-built copy.
  await addTag(slideOverDoc.value.id, tagId, slideOverDoc.value)
}

async function removeTagFromSlideOver(tagId: string) {
  if (!slideOverDoc.value || !tagId) return
  await removeTag(slideOverDoc.value.id, tagId, slideOverDoc.value)
}

function buildFilterLabel(): string {
  const parts: string[] = []
  for (const tag of tf.selectedTags) parts.push(tag.name)
  if (tf.debouncedText.trim()) parts.push(`"${tf.debouncedText.trim()}"`)
  return parts.join(' · ')
}

// History state so the document view's in-app Back returns to the active filtered
// list (returnTo) and shows the filter context (filterLabel). Shared by both the
// slide-over "open full view" and the row double-click paths so double-click never
// drops the filter context.
function buildDocumentViewState() {
  // Merge the component-owned workflow key onto the store's canonical query so the
  // document view's Back returns to the list with the "Assigned to me" filter still
  // active — the store serializer preserves it too, but merging here keeps returnTo
  // correct even if the route query lags the toggle by a tick.
  const query = { ...tf.buildFilterQuery(), ...(workflowMe.value ? { workflow: 'me' } : {}) }
  return {
    returnTo: router.resolve({ name: 'documents', query }).fullPath,
    filterLabel: buildFilterLabel() || undefined,
  }
}

function openFullView() {
  if (slideOverDoc.value) {
    router.push({
      name: 'document-view',
      params: { id: slideOverDoc.value.id },
      state: buildDocumentViewState(),
    })
  }
}

// Single- vs double-click: a native double-click fires two row-clicks first, so a
// naive single-click handler would flash the slide-over open before the dblclick
// navigation. Debounce the slide-over open and cancel it if a dblclick lands within
// the window — a clean double-click opens the full view with no stale slide-over.
const CLICK_DEBOUNCE_MS = 250
let pendingSingleClick: ReturnType<typeof setTimeout> | null = null

function openDocument(doc: DocumentListItem) {
  if (pendingSingleClick) clearTimeout(pendingSingleClick)
  pendingSingleClick = setTimeout(() => {
    pendingSingleClick = null
    openSlideOver(doc)
  }, CLICK_DEBOUNCE_MS)
}

function openDocumentFull(doc: DocumentListItem) {
  // Cancel any pending slide-over from the two preceding single-clicks.
  if (pendingSingleClick) {
    clearTimeout(pendingSingleClick)
    pendingSingleClick = null
  }
  // A slow double-click can land AFTER the 250 ms single-click timer already
  // fired and opened the slide-over. Cancelling the (now-null) timer is not
  // enough — close the slide-over so navigation never strands it open.
  slideOverOpen.value = false
  slideOverDocId.value = null
  // Carry the SAME returnTo/filterLabel state as the slide-over path so the
  // document view's Back returns to the active filtered list.
  router.push({
    name: 'document-view',
    params: { id: doc.id },
    state: buildDocumentViewState(),
  })
}

// No dangling debounce timer should survive teardown (navigation, filter change).
onBeforeUnmount(() => {
  if (pendingSingleClick) {
    clearTimeout(pendingSingleClick)
    pendingSingleClick = null
  }
})

const contextMenuItems = computed(() => {
  const doc = contextMenuDoc.value
  if (!doc) return [] as MenuItem[]
  const currentTagIds = new Set((doc.tags ?? []).map((t) => t.id))
  const addItems: MenuItem[] = tf.allTags
    .filter((t) => !currentTagIds.has(t.id))
    .map((tag) => ({
      label: tag.name,
      icon: 'pi pi-plus',
      command: () => quickAddTag(tag.id),
    }))
  const removeItems: MenuItem[] = (doc.tags ?? []).map((tag) => ({
    label: tag.name,
    icon: 'pi pi-minus',
    command: () => quickRemoveTag(tag.id),
  }))

  const items: MenuItem[] = []
  if (addItems.length) items.push({ label: t('ui.context_add_tag'), items: addItems })
  if (removeItems.length) {
    if (items.length) items.push({ separator: true })
    items.push({ label: t('ui.context_remove_tag'), items: removeItems })
  }
  return items
})

// --- Bulk operations ---
//
// Teedy exposes no bulk document endpoint, so each bulk action fans out over the
// existing single-document endpoints (see src/utils/bulkOps.ts). We run the
// operation over the current selection, drive a progress bar, and report a
// per-document success/failure summary — ACL/permission failures surface as
// individual failures rather than aborting the batch.

const selectedDocs = ref<DocumentListItem[]>([])
const bulkProgress = ref<[number, number] | null>(null)

// Documents dropping out of the current page (paging, refetch) must not linger in
// the selection — reconcile against what is actually rendered.
watch(documents, (docs) => {
  if (!selectedDocs.value.length) return
  const visibleIds = new Set(docs.map((d) => d.id))
  selectedDocs.value = selectedDocs.value.filter((d) => visibleIds.has(d.id))
})

function clearSelection() {
  selectedDocs.value = []
}

function summariseBulk(result: BulkResult) {
  const succeeded = result.succeeded.length
  const failed = result.failed.length
  if (failed === 0) {
    toast.add({
      severity: 'success',
      summary: t('ui.bulk.done'),
      detail: t('ui.bulk.summary_ok', { count: succeeded }),
      life: 3000,
    })
  } else {
    toast.add({
      severity: succeeded ? 'warn' : 'error',
      summary: t('ui.bulk.done'),
      detail: t('ui.bulk.summary_partial', { ok: succeeded, failed }),
      life: 6000,
    })
  }
}

async function runBulkOp(op: (doc: DocumentListItem) => Promise<unknown>) {
  const docs = [...selectedDocs.value]
  if (!docs.length || bulkProgress.value) return
  bulkProgress.value = [0, docs.length]
  try {
    const result = await runBulk(docs, op, (done, total) => {
      bulkProgress.value = [done, total]
    })
    summariseBulk(result)
    clearSelection()
    queryClient.invalidateQueries({ queryKey: queryKeys.documents() })
    // Bulk tag/language/delete changes which tags sit on which docs — stale the
    // sidebar/facet counts too.
    for (const key of tagCountKeys) queryClient.invalidateQueries({ queryKey: key })
  } finally {
    bulkProgress.value = null
  }
}

function bulkAddTag(tagId: string) {
  runBulkOp((doc) => updateDocument(doc.id, buildAddTagParams(doc, tagId)))
}

function bulkSetLanguage(language: string) {
  runBulkOp((doc) => updateDocument(doc.id, buildLanguageParams(doc, language)))
}

function bulkDelete() {
  const count = selectedDocs.value.length
  if (!count) return
  confirmDanger({
    message: t('ui.bulk.delete_confirm', { count }),
    header: t('ui.bulk.delete'),
    accept: () => runBulkOp((doc) => deleteDocument(doc.id)),
  })
}
</script>

<template>
  <div class="doc-list-page">
    <!-- Address bar -->
    <div class="address-bar">
      <DocumentSearchBar
        v-model="tf.searchText"
        :has-active-filters="tf.hasActiveFilters"
        :total-count="totalCount"
        @clear="tf.clearFilters()"
      />

      <TagFilterChips
        :selected-tags="tf.selectedTags"
        :excluded-tags="tf.excludedTags"
        :related-tags="tf.relatedTags"
        @remove-tag="tf.removeTag($event)"
        @toggle-tag="tf.toggleTag($event)"
      />

      <div class="wf-filter-row">
        <ToggleButton
          v-model="workflowMe"
          :onLabel="t('ui.workflow.assigned_to_me')"
          :offLabel="t('ui.workflow.assigned_to_me')"
          onIcon="pi pi-sitemap"
          offIcon="pi pi-sitemap"
          size="small"
        />
      </div>
    </div>

    <!-- Bulk action toolbar -->
    <div v-if="selectedDocs.length" class="bulk-bar-wrap">
      <BulkActionBar
        :count="selectedDocs.length"
        :tags="tf.allTags"
        :progress="bulkProgress"
        @add-tag="bulkAddTag"
        @set-language="bulkSetLanguage"
        @delete="bulkDelete"
        @clear="clearSelection"
      />
    </div>

    <!-- Document list -->
    <div class="doc-area">
      <DocumentTable
        v-if="documents.length || isLoading"
        v-model:selection="selectedDocs"
        selectable
        :documents="documents"
        :totalRecords="totalCount"
        :rows="PAGE_SIZE"
        :first="pageOffset"
        :loading="isLoading"
        :sortField="sortField"
        :sortOrder="sortOrder"
        @row-click="openDocument"
        @row-dblclick="openDocumentFull"
        @row-context-menu="onDocContextMenu"
        @page="onPage"
        @sort="onSort"
      />

      <ErrorState v-else-if="isError" @retry="refetch()" />

      <EmptyState
        v-else
        icon="pi pi-inbox"
        :message="tf.hasActiveFilters ? t('ui.no_documents_match') : t('ui.no_documents_yet')"
        :action-label="tf.hasActiveFilters ? undefined : t('ui.add_first_document')"
        @action="router.push({ name: 'document-add' })"
      />
    </div>

    <ContextMenu ref="contextMenu" :model="contextMenuItems" />

    <DocumentSlideOver
      v-model:visible="slideOverOpen"
      :loading="slideOverLoading"
      :document="slideOverDoc ?? null"
      :available-tags="availableTagsForSlideOver"
      @add-tag="addTagToSlideOver"
      @remove-tag="removeTagFromSlideOver"
      @open-full-view="openFullView"
      @edit-document="(id: string) => router.push({ name: 'document-edit', params: { id } })"
    />
  </div>
</template>

<style scoped>
.doc-list-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* --- Address bar --- */

.address-bar {
  padding: 0.75rem 1.5rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  flex-shrink: 0;
}

/* --- Workflow filter --- */

.wf-filter-row {
  display: flex;
  gap: 0.5rem;
}

/* --- Bulk action toolbar --- */

.bulk-bar-wrap {
  padding: 0 1.5rem;
  flex-shrink: 0;
}

/* --- Document area --- */

.doc-area {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1.5rem 1.5rem;
}

@media (max-width: 1024px) {
  .address-bar { padding: 0.75rem 1rem 0; }
  .bulk-bar-wrap { padding: 0 1rem; }
  .doc-area { padding: 0.75rem 1rem 1rem; }
}
</style>
