<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import type { DataTableSortEvent, DataTableRowReorderEvent, DataTableRowDoubleClickEvent } from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import Checkbox from 'primevue/checkbox'
import { formatDate, formatFileSize } from '../utils/formatters'
import { displayName } from '../utils/fileName'
import FileActionMenu from './FileActionMenu.vue'
import AccessCountBadge from './AccessCountBadge.vue'

// Enriched, authenticated file LIST (the grid⇄list toggle's "list" mode). Owns the
// list-only affordances: quick filter, optional columns, transient sort, drag-handle
// reorder and inline rename. The list has NO inner scroll container and no windowing:
// it flows with the page exactly like the grid does, however many files it holds (#196).
// All *mutations* are delegated up (the parent owns the document + query cache); this
// component only owns presentation + local order/filter/sort/rename UI state. Every write
// affordance is gated on `writable` so a read-only viewer sees a browse-only table.
export interface FilePanelFile {
  id: string
  // Nullable: the backend serializes a file name as nullable, so legacy/inbox rows can arrive
  // without one. Every name read here guards against that.
  name: string | null
  mimetype: string
  size: number
  create_date: number
  // Nullable: the backend serializes a file's uploader (creator) as nullable, so a legacy file
  // can arrive without one. The uploader column renders it directly (an absent value shows blank).
  creator: string | null
  version: number
  rotation?: number
}

const props = defineProps<{
  files: FilePanelFile[]
  writable: boolean
  // The document these files belong to. The rows carry no document id of their own, so it is
  // threaded down for FileActionMenu's copy-link deep link (#192).
  documentId: string
  // The document's explicit cover file id (#174), or null when the cover is derived from order. The
  // matching row shows a cover badge and offers "remove as cover"; every other row offers "set as
  // cover".
  coverFileId?: string | null
  // #300 — the CALLING user's own access count per file id, or an empty map while the counts are
  // still loading. Passed in rather than fetched here: the document view owns the single query, so
  // an N-row table still costs zero extra requests.
  accessCounts?: Record<string, number>
}>()

const emit = defineEmits<{
  open: [file: FilePanelFile]
  rename: [fileId: string, name: string]
  delete: [file: FilePanelFile]
  versions: [file: FilePanelFile]
  reorder: [orderedIds: string[]]
  setCover: [file: FilePanelFile]
  clearCover: [file: FilePanelFile]
  move: [file: FilePanelFile]
}>()

const { t } = useI18n()

// Above this count the drag handle is withdrawn. It is NOT a rendering cap — every row
// always renders — it guards the reorder contract: `POST /file/reorder` needs the COMPLETE
// id order, and a drag over a very long list is both error-prone and easy to drop rows from.
const LARGE_LIST_THRESHOLD = 100

// Optional-column visibility. Icon + Name are always shown; Created + Size default on,
// Uploader default off (accepted decision). Persisted so a user's column choice sticks.
const COLUMNS_KEY = 'teedy_file_columns'
type ColumnKey = 'created' | 'size' | 'uploader'
const DEFAULT_COLUMNS: Record<ColumnKey, boolean> = { created: true, size: true, uploader: false }

function readColumns(): Record<ColumnKey, boolean> {
  try {
    const raw = localStorage.getItem(COLUMNS_KEY)
    if (!raw) return { ...DEFAULT_COLUMNS }
    const parsed = JSON.parse(raw) as Partial<Record<ColumnKey, unknown>>
    // Coerce defensively: a stale/tampered value must never drop the mandatory shape. A preference
    // saved while #300 briefly had an "accesses" column carries an extra key, which is simply ignored.
    return {
      created: typeof parsed.created === 'boolean' ? parsed.created : DEFAULT_COLUMNS.created,
      size: typeof parsed.size === 'boolean' ? parsed.size : DEFAULT_COLUMNS.size,
      uploader: typeof parsed.uploader === 'boolean' ? parsed.uploader : DEFAULT_COLUMNS.uploader,
    }
  } catch {
    return { ...DEFAULT_COLUMNS }
  }
}

const columns = ref<Record<ColumnKey, boolean>>(readColumns())
watch(columns, (v) => localStorage.setItem(COLUMNS_KEY, JSON.stringify(v)), { deep: true })

const colPopover = ref<InstanceType<typeof Popover> | null>(null)
function toggleColumns(event: Event) {
  colPopover.value?.toggle(event)
}

// Quick filter — client-side name/mimetype contains-match.
const filterText = ref('')

// Transient sort: clicking a header sorts the display for this session only; it is
// never persisted (only an explicit drag persists order). removableSort lets a third
// click clear it, returning to the saved custom order.
const sortField = ref<string | undefined>(undefined)
const sortOrder = ref<number | undefined>(undefined)

function onSort(event: DataTableSortEvent) {
  sortField.value = (event.sortField as string) || undefined
  sortOrder.value = event.sortOrder ?? undefined
  // A transient sort supersedes the custom-order view; drop any stale reorder-failure.
  reorderFailed.value = false
}

// Local working copy of the order. Seeded from (and re-seeded on every refetch of)
// props.files, which the backend returns in the persisted `order`. A drag mutates it
// optimistically and emits the new id order; the parent persists + refetches, which
// re-seeds this to the authoritative order (so a reorder survives reload). A successful
// re-seed also clears any prior reorder-failure state — the list is showing the
// authoritative order again.
const orderedFiles = ref<FilePanelFile[]>([])

// The order in effect BEFORE the last optimistic drag, kept so a failed persist can be
// rolled back locally and deterministically — never left showing an unsaved order under
// the "saved" indicator, even if the parent's refetch also fails.
let orderBeforeDrag: FilePanelFile[] = []
const reorderFailed = ref(false)
// Serialize reorders: only ONE may be in flight. While a persist is pending the drag is
// disabled, so a second drag can never overwrite the single pre-drag snapshot (which
// would let a late failure roll back to the wrong order). Set on drag, cleared when the
// parent confirms/rolls back the persist (or on any re-seed).
const reorderPending = ref(false)

watch(
  () => props.files,
  (f) => {
    orderedFiles.value = [...f]
    reorderFailed.value = false
    reorderPending.value = false
  },
  { immediate: true },
)

const filteredFiles = computed(() => {
  const q = filterText.value.trim().toLowerCase()
  if (!q) return orderedFiles.value
  return orderedFiles.value.filter(
    // A null-name file has no name to match: it survives an empty filter (handled above) but a
    // non-empty query can only match it on mimetype, never on the missing name.
    (f) => (f.name ?? '').toLowerCase().includes(q) || f.mimetype.toLowerCase().includes(q),
  )
})

const largeList = computed(() => filteredFiles.value.length > LARGE_LIST_THRESHOLD)

// Drag reorder is only meaningful over the full, unfiltered, unsorted list — reordering a
// filtered/sorted subset is ambiguous, and the backend needs the complete id order. Also
// inherently write-gated.
const reorderEnabled = computed(
  () =>
    props.writable &&
    !filterText.value &&
    !sortField.value &&
    !largeList.value &&
    !reorderPending.value,
)

function onRowReorder(event: DataTableRowReorderEvent) {
  // One reorder in flight at a time — ignore a second while pending so the single
  // pre-drag snapshot is never overwritten (the handle is also disabled meanwhile).
  if (reorderPending.value) return
  // Snapshot the pre-drag order BEFORE applying the optimistic one, so a persist failure
  // can be reverted to exactly the last saved sequence.
  orderBeforeDrag = [...orderedFiles.value]
  reorderFailed.value = false
  reorderPending.value = true
  const next = event.value as FilePanelFile[]
  orderedFiles.value = next
  emit(
    'reorder',
    next.map((f) => f.id),
  )
}

// Called by the parent when POST /file/reorder resolves. On success the optimistic order
// already equals the persisted one, so we just release the in-flight lock.
function confirmReorder() {
  reorderPending.value = false
}

// Called by the parent when POST /file/reorder rejects: restore the pre-drag order
// locally, flip the indicator to the not-saved state, and release the lock — regardless
// of whether the parent's refetch succeeds.
function rollbackReorder() {
  orderedFiles.value = [...orderBeforeDrag]
  reorderFailed.value = true
  reorderPending.value = false
}

function onRowDblclick(event: DataTableRowDoubleClickEvent) {
  emit('open', event.data as FilePanelFile)
}

function fileIcon(mime: string) {
  if (mime.startsWith('image/')) return 'pi pi-image'
  if (mime === 'application/pdf') return 'pi pi-file-pdf'
  return 'pi pi-file'
}

// --- Inline rename (double-click name cell + F2 + the pencil in the action menu) ---
const renamingId = ref<string | null>(null)
const renameValue = ref('')

function startRename(file: FilePanelFile) {
  if (!props.writable) return
  renamingId.value = file.id
  // Seed the editor with an empty string for a null-name file so the user names it from scratch
  // (and the trim() on commit never sees a null).
  renameValue.value = file.name ?? ''
}

function cancelRename() {
  renamingId.value = null
  renameValue.value = ''
}

function commitRename(fileId: string) {
  if (renamingId.value !== fileId) return
  const name = renameValue.value.trim()
  const original = orderedFiles.value.find((f) => f.id === fileId)?.name
  if (!name || name === original) return cancelRename()
  emit('rename', fileId, name)
  cancelRename()
}

function onNameKeydown(event: KeyboardEvent, file: FilePanelFile) {
  if (event.key === 'F2') {
    event.preventDefault()
    startRename(file)
  }
}

defineExpose({ columns, reorderEnabled, reorderFailed, reorderPending, confirmReorder, rollbackReorder })
</script>

<template>
  <div class="file-list-section">
    <div class="file-list-toolbar">
      <InputText
        v-model="filterText"
        class="file-filter-input"
        size="small"
        :placeholder="t('ui.file_view.filter_placeholder')"
        :aria-label="t('ui.file_view.filter_aria')"
      />

      <span
        v-if="orderedFiles.length > 1"
        class="file-order-indicator"
        :class="{ transient: !!sortField, failed: reorderFailed && !sortField }"
        v-tooltip="sortField ? t('ui.file_view.order_sorted_hint') : reorderFailed ? t('ui.file_view.order_failed_hint') : t('ui.file_view.order_custom_hint')"
      >
        <i
          :class="sortField ? 'pi pi-sort-alt' : reorderFailed ? 'pi pi-exclamation-triangle' : 'pi pi-bars'"
          aria-hidden="true"
        />
        {{ sortField ? t('ui.file_view.order_sorted') : reorderFailed ? t('ui.file_view.order_failed') : t('ui.file_view.order_custom') }}
      </span>

      <!-- The chooser is hidden on narrow viewports, where every metadata column is
           collapsed anyway (see the responsive block below) and it would offer choices
           that cannot take effect. -->
      <Button
        class="file-columns-btn"
        icon="pi pi-sliders-h"
        text
        size="small"
        severity="secondary"
        :label="t('ui.file_view.columns')"
        :aria-label="t('ui.file_view.columns')"
        @click="toggleColumns"
      />
      <Popover ref="colPopover">
        <div class="file-columns-panel">
          <div class="file-column-option">
            <Checkbox v-model="columns.created" binary inputId="file-col-created" />
            <label for="file-col-created">{{ t('ui.file_view.col_created') }}</label>
          </div>
          <div class="file-column-option">
            <Checkbox v-model="columns.size" binary inputId="file-col-size" />
            <label for="file-col-size">{{ t('ui.file_view.col_size') }}</label>
          </div>
          <div class="file-column-option file-column-option-uploader">
            <Checkbox v-model="columns.uploader" binary inputId="file-col-uploader" />
            <label for="file-col-uploader">{{ t('ui.file_view.col_uploader') }}</label>
          </div>
        </div>
      </Popover>
    </div>

    <DataTable
      :value="filteredFiles"
      dataKey="id"
      :sortField="sortField"
      :sortOrder="sortOrder"
      removableSort
      stripedRows
      :rowHover="true"
      :reorderableRows="reorderEnabled"
      class="file-data-table"
      :class="{ 'has-uploader': columns.uploader }"
      @row-reorder="onRowReorder"
      @row-dblclick="onRowDblclick"
      @sort="onSort"
    >
      <Column
        v-if="reorderEnabled"
        rowReorder
        headerClass="file-col-handle"
        bodyClass="file-col-handle"
        :reorderableColumn="false"
      />

      <Column headerClass="file-col-icon" bodyClass="file-col-icon">
        <template #body="{ data }">
          <!-- The icon opens the in-app preview (emits `open`), it does NOT link to the
               original attachment URL — that URL is served as a download, so linking to
               it here would trigger a browser download instead of showing the file (#144). -->
          <button
            type="button"
            class="file-open-link"
            :aria-label="t('ui.file_view.open_file', { name: displayName(data.name, t) })"
            @click="emit('open', data)"
            @dblclick.stop
          >
            <i :class="fileIcon(data.mimetype)" aria-hidden="true" />
          </button>
        </template>
      </Column>

      <Column
        field="name"
        :header="t('ui.file_view.col_name')"
        sortable
        headerClass="file-col-name"
        bodyClass="file-col-name"
      >
        <template #body="{ data }">
          <!-- One flex line: the name shrinks (and ellipsizes) so the cover badge and the
               action cluster keep their room instead of being pushed off-screen (#170). -->
          <div class="file-name-cell">
            <InputText
              v-if="renamingId === data.id"
              v-model="renameValue"
              class="rename-input"
              size="small"
              autofocus
              @keyup.enter="commitRename(data.id)"
              @keyup.escape="cancelRename"
              @blur="commitRename(data.id)"
            />
            <!-- The cell ellipsizes, so the full name is only recoverable on hover (#207). -->
            <span
              v-else
              class="file-name-text"
              tabindex="0"
              :title="displayName(data.name, t)"
              @dblclick.stop="startRename(data)"
              @keydown="onNameKeydown($event, data)"
            >{{ displayName(data.name, t) }}</span>
            <!-- #300: the CALLING user's own count for this file, folded into the NAME cell
                 rather than given a column of its own. A column added ~5.5rem of fixed width and
                 pushed the table past its container at the #170 breakpoints; the name column is
                 the flexible one, so an inline badge here costs the table no width at all. It is
                 a SIBLING of .file-name-text, never a child: that span's textContent is the file
                 name, which nullname.spec and file-panel.spec read. It is a <span>, so it is not
                 one of the `button, a` controls the #170 cluster contract counts (and it is not
                 in the action cell either). -->
            <AccessCountBadge
              class="file-access-badge"
              :count="accessCounts?.[data.id]"
              kind="file"
            />
            <span
              v-if="coverFileId && data.id === coverFileId"
              class="cover-badge"
              :aria-label="t('ui.cover_badge')"
            >
              <i class="pi pi-image" aria-hidden="true" />
              {{ t('ui.cover_badge') }}
            </span>
          </div>
        </template>
      </Column>

      <Column
        v-if="columns.created"
        field="create_date"
        :header="t('ui.file_view.col_created')"
        sortable
        headerClass="file-col-created"
        bodyClass="file-col-created"
      >
        <template #body="{ data }">
          <span class="file-meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>

      <Column
        v-if="columns.size"
        field="size"
        :header="t('ui.file_view.col_size')"
        sortable
        headerClass="file-col-size"
        bodyClass="file-col-size"
      >
        <template #body="{ data }">
          <span class="file-meta">{{ formatFileSize(data.size) }}</span>
        </template>
      </Column>

      <Column
        v-if="columns.uploader"
        field="creator"
        :header="t('ui.file_view.col_uploader')"
        sortable
        headerClass="file-col-uploader"
        bodyClass="file-col-uploader"
      >
        <template #body="{ data }">
          <span class="file-meta">{{ data.creator }}</span>
        </template>
      </Column>

      <Column headerClass="file-col-actions" bodyClass="file-col-actions">
        <template #body="{ data }">
          <FileActionMenu
            :file="data"
            :writable="writable"
            :document-id="documentId"
            :is-cover="!!coverFileId && data.id === coverFileId"
            @versions="emit('versions', data)"
            @preview="emit('open', data)"
            @rename="startRename(data)"
            @delete="emit('delete', data)"
            @set-cover="emit('setCover', data)"
            @clear-cover="emit('clearCover', data)"
            @move="emit('move', data)"
          >
            <!-- Forward the parent's per-file extra actions into the (writable-gated)
                 action menu, so #73/#117 mount in ONE place and light up here too. -->
            <template #extra="s">
              <slot name="file-extra" v-bind="s" />
            </template>
          </FileActionMenu>
        </template>
      </Column>
    </DataTable>

    <p v-if="filterText && filteredFiles.length === 0" class="file-no-matches">
      {{ t('ui.file_view.no_matches') }}
    </p>
  </div>
</template>

<style scoped>
.file-list-section {
  margin-top: 0.5rem;
}

.cover-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  flex: 0 0 auto;
  padding: 0.05rem 0.4rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  color: var(--p-primary-contrast-color, #fff);
  background: var(--p-primary-color, #3b82f6);
  vertical-align: middle;
}

.cover-badge .pi {
  font-size: 0.7rem;
}

.file-list-toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
  flex-wrap: wrap;
}

.file-filter-input {
  flex: 1 1 12rem;
  min-width: 0;
  max-width: 20rem;
}

.file-order-indicator {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.1rem 0.5rem;
  font-size: 0.75rem;
  border-radius: 999px;
  background: var(--p-content-hover-background);
  color: var(--p-text-muted-color);
  white-space: nowrap;
}
.file-order-indicator.transient {
  background: var(--teedy-warning-bg);
  color: var(--teedy-warning-text);
}
.file-order-indicator.failed {
  background: var(--p-red-100, #fee2e2);
  color: var(--p-red-700, #b91c1c);
}
.file-order-indicator i {
  font-size: 0.7rem;
}

.file-columns-btn {
  margin-left: auto;
}

.file-columns-panel {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  min-width: 10rem;
}
.file-column-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.file-column-option label {
  font-size: 0.875rem;
  cursor: pointer;
}

.file-open-link {
  color: var(--p-text-muted-color);
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  font: inherit;
}
.file-open-link:hover {
  color: var(--teedy-brand);
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  min-width: 0;
}

.file-name-text {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: default;
  outline-offset: 2px;
}

.rename-input {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 0.875rem;
}

.file-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.file-no-matches {
  margin: 0.5rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

/* ---------------------------------------------------------------------------
   Row geometry (#170, #192). A writable PDF row carries TEN controls (the shared
   FileActionMenu's nine plus the PDF page organizer). Measured in the running app,
   each is 36px wide, so the cluster is ~378px of icons — wider than the entire
   action area of a 360px phone, where 128px is left once the 8rem name floor and
   the handle/icon columns are paid for. A single-line cluster is therefore
   geometrically impossible on a phone, and the row is banded by measurement:

     >= 1024px  Uploader may be enabled (the widest optional column). The page content
                is capped at 960px by `.doc-view`, so this band has NO more room than
                any other — a wider window buys nothing. Uploader + the ten-icon
                cluster therefore does not fit on one line, and the cluster wraps
                (see below) instead of the column being taken away.
     >=  960px  the ten-icon cluster sits on ONE line — including at 960-1023px with the
                Uploader PREFERENCE stored but the column hidden, which is the boundary
                case the geometry gate pins
     900-959px  the cluster takes two lines; the row is otherwise unchanged
     <=  899px  the tightest metadata columns; the cluster wraps three per line
     <=  639px  Created + Size collapse too, and the column chooser goes with them
                (it would otherwise offer choices that cannot take effect)
     <=  479px  the tightest cells — this is the band 360px and 393px land in, and
                it satisfies F2's "all metadata columns collapse below 480px"

   The WRAP itself is not banded — `.file-action-menu` may wrap at every width. What is
   banded is the actions column's min-width FLOOR: 24.75rem (the whole cluster on one
   line) from 960px up, back down to 12.5rem from 1024px up when the Uploader column is
   RENDERING, and 8rem/7.5rem on phones. Expressing it as a floor rather than as a
   breakpoint on the wrap is what lets the row absorb whatever combination of optional
   columns and controls it is handed — the Uploader case in particular turns on a user
   preference, which no media query can see. The preference outlives the width it was set
   at, which is why its override is pinned to the band where the column is actually
   painted (1024px+), not to the band where the one-line floor applies (960px+).

   NO control is ever hidden or moved behind an overlay: at every width every action
   of every row stays visible, unclipped and clickable — the cluster gets taller
   instead of narrower. e2e/file-list-geometry.spec.ts is the standing gate; it
   measures the WORST-CASE row (writable PDF) at 360px, 393px and desktop, the same
   row with the Uploader column enabled across the 1024–1440 band, and the grid
   card's own action row.
   --------------------------------------------------------------------------- */

/* The name column absorbs the row's slack and ellipsizes. `max-width: 0` stops the
   file name from driving the column's preferred width (an auto-layout table otherwise
   sizes this column to the longest name and pushes the action cluster off-screen —
   that IS #170); `width: 100%` makes it the column that receives the leftover space;
   `min-width` is the 8rem readability floor the redesign guarantees. */
.file-data-table :deep(td.file-col-name) {
  width: 100%;
  max-width: 0;
  min-width: 8rem;
}

.file-data-table :deep(.file-col-handle),
.file-data-table :deep(.file-col-icon) {
  width: 3rem;
}

.file-data-table :deep(.file-col-created),
.file-data-table :deep(.file-col-uploader) {
  width: 10rem;
}

.file-data-table :deep(.file-col-size) {
  width: 7rem;
}

/* #300 — the access badge shares the name cell, so it must never be the thing that grows it:
   it keeps its intrinsic width and the name span (min-width: 0) absorbs the squeeze by
   ellipsizing, exactly as it already does for the cover badge. */
.file-data-table :deep(.file-access-badge) {
  flex: 0 0 auto;
}

/* The cluster is right-aligned, so the cell's default 1rem gutters are dead space — and
   `.doc-view` caps the content at 960px, which is where a row carrying the Uploader column
   AND the (then nine-icon) cluster ran out of room. Halving the gutters is what made that
   combination fit; the tenth control (#192) needs another ~38px that the cap cannot supply,
   which is what the wrap below is for.

   `min-width` is the floor the auto-layout table may not squeeze past: without it the
   wrapping cluster's min-content width is ONE icon, and the table would happily collapse
   the column to a single vertical stack whenever another column wanted room. 12.5rem holds
   five icons per line, so the worst case degrades to two tidy lines rather than ten. */
.file-data-table :deep(td.file-col-actions),
.file-data-table :deep(th.file-col-actions) {
  text-align: right;
  padding-left: 0.5rem;
  padding-right: 0.5rem;
  min-width: 12.5rem;
}

/* Wrapping is unconditional (#192). The cluster grows DOWNWARDS when its column is tight
   and stays on one line when it is not — no control is ever hidden, and no breakpoint has
   to predict which combination of optional columns is active. Set on the menu itself, not
   the cell: the menu is one inline-flex box, so a rule on the cell alone would never break
   the icons. Rows have no fixed height, so the row simply gets taller. */
.file-data-table :deep(td.file-col-actions .file-action-menu) {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  row-gap: 0.125rem;
}

/* ONE LINE where the row can afford one. The name column is `width: 100%` precisely so it
   absorbs the row's slack and ellipsizes (#170) — which means that the moment the cluster
   became wrappable, the name column claimed everything and the cluster collapsed onto its
   12.5rem floor even on a wide desktop (measured: three lines at 1280px). The floor is
   therefore raised to the cluster's full one-line width in the band where that provably
   fits: 24.75rem = ten 36px controls + nine 2px gaps + the halved 0.5rem gutters.

   960px is where it fits, measured against the 960px content cap: 912px of content less
   the handle (3rem), icon (3rem), Created (10rem), Size (7rem) and the 8rem name floor
   leaves 20px of slack over the 396px cluster. Between 900 and 959px the base floor
   applies and the cluster takes two lines — no control is lost either way. Nothing changes
   above 1008px: `.doc-view` caps the content at 960px, so a wider window is not a roomier
   row. */
@media (min-width: 960px) {
  .file-data-table :deep(td.file-col-actions),
  .file-data-table :deep(th.file-col-actions) {
    min-width: 24.75rem;
  }
}

/* …except with the Uploader column actually SHOWING. It costs another 10rem, which the
   content cap cannot supply at any viewport width — so that row wraps instead, and the
   column survives. This is the one state a breakpoint could not express on its own: it
   turns on a stored user preference.

   `has-uploader` reflects that PREFERENCE, and the preference outlives the width it was set
   at — the column itself is force-hidden below 1024px. So the override must be scoped to
   the same band the column renders in: between 960 and 1023px a user who once enabled
   Uploader sees no Uploader column, and must therefore keep the full one-line floor. Scoped
   to `min-width: 960px` instead, the persisted preference would silently wrap those rows
   for a column that is not on screen. */
@media (min-width: 1024px) {
  .file-data-table.has-uploader :deep(td.file-col-actions),
  .file-data-table.has-uploader :deep(th.file-col-actions) {
    min-width: 12.5rem;
  }
}

@media (max-width: 1023px) {
  .file-data-table :deep(.file-col-uploader) {
    display: none;
  }
  .file-column-option-uploader {
    display: none;
  }
}

@media (max-width: 899px) {
  .file-data-table :deep(th),
  .file-data-table :deep(td) {
    padding: 0.5rem 0.375rem;
  }
  .file-data-table :deep(.file-col-handle),
  .file-data-table :deep(.file-col-icon) {
    width: 2.5rem;
  }
  .file-data-table :deep(.file-col-created) {
    width: 7rem;
  }
  .file-data-table :deep(.file-col-size) {
    width: 5rem;
  }
  /* Three 36px icons per line (8rem = 128px, less this band's 12px of cell padding,
     holds 3 × 36 + 2 × 2 = 112). It OVERRIDES the wide band's 12.5rem floor — the wrap
     itself is unconditional and lives in the base rule above. The padding is restated
     because the wide band's cell rule outranks the element-level one above on
     specificity, media query or not. */
  .file-data-table :deep(td.file-col-actions),
  .file-data-table :deep(th.file-col-actions) {
    min-width: 8rem;
    padding-left: 0.375rem;
    padding-right: 0.375rem;
  }
}

@media (max-width: 639px) {
  .file-data-table :deep(.file-col-created),
  .file-data-table :deep(.file-col-size) {
    display: none;
  }
  .file-columns-btn {
    display: none;
  }
}

@media (max-width: 479px) {
  .file-data-table :deep(th),
  .file-data-table :deep(td) {
    padding: 0.5rem 0.125rem;
  }
  .file-data-table :deep(.file-col-handle),
  .file-data-table :deep(.file-col-icon) {
    width: 2rem;
  }
  /* Same three icons per line, with only 4px of cell padding to pay for. Keeping it at
     7.5rem leaves the name column above its floor at 360px (measured: 176px there). */
  .file-data-table :deep(td.file-col-actions),
  .file-data-table :deep(th.file-col-actions) {
    min-width: 7.5rem;
    padding-left: 0.125rem;
    padding-right: 0.125rem;
  }
}
</style>
