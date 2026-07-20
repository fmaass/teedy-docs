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

// Enriched, authenticated file LIST (the grid⇄list toggle's "list" mode). Owns the
// list-only affordances: quick filter, optional columns, transient sort, drag-handle
// reorder, inline rename, and virtualization for long lists. All *mutations* are
// delegated up (the parent owns the document + query cache); this component only owns
// presentation + local order/filter/sort/rename UI state. Every write affordance is
// gated on `writable` so a read-only viewer sees a browse-only table.
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
}>()

const emit = defineEmits<{
  open: [file: FilePanelFile]
  rename: [fileId: string, name: string]
  delete: [file: FilePanelFile]
  versions: [file: FilePanelFile]
  reorder: [orderedIds: string[]]
}>()

const { t } = useI18n()

// Above this count the list virtual-scrolls (grid windowing is deferred; the list is
// the surface that can realistically hold hundreds of rows).
const VIRTUAL_THRESHOLD = 100

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
    // Coerce defensively: a stale/tampered value must never drop the mandatory shape.
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

const virtualize = computed(() => filteredFiles.value.length > VIRTUAL_THRESHOLD)

// Drag reorder is only meaningful over the full, unfiltered, unsorted, non-virtualized
// list — reordering a filtered/sorted subset or a virtual window is ambiguous, and the
// backend needs the complete id order. Also inherently write-gated.
const reorderEnabled = computed(
  () =>
    props.writable &&
    !filterText.value &&
    !sortField.value &&
    !virtualize.value &&
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

defineExpose({ columns, reorderEnabled, virtualize, reorderFailed, reorderPending, confirmReorder, rollbackReorder })
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
          <div class="file-column-option">
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
      :scrollable="virtualize"
      :scrollHeight="virtualize ? '480px' : undefined"
      :virtualScrollerOptions="virtualize ? { itemSize: 46 } : undefined"
      class="file-data-table"
      @row-reorder="onRowReorder"
      @row-dblclick="onRowDblclick"
      @sort="onSort"
    >
      <Column v-if="reorderEnabled" rowReorder headerStyle="width: 3rem" :reorderableColumn="false" />

      <Column headerStyle="width: 3rem">
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

      <Column field="name" :header="t('ui.file_view.col_name')" sortable>
        <template #body="{ data }">
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
          <span
            v-else
            class="file-name-text"
            tabindex="0"
            @dblclick.stop="startRename(data)"
            @keydown="onNameKeydown($event, data)"
          >{{ displayName(data.name, t) }}</span>
        </template>
      </Column>

      <Column v-if="columns.created" field="create_date" :header="t('ui.file_view.col_created')" sortable headerStyle="width: 10rem">
        <template #body="{ data }">
          <span class="file-meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>

      <Column v-if="columns.size" field="size" :header="t('ui.file_view.col_size')" sortable headerStyle="width: 7rem">
        <template #body="{ data }">
          <span class="file-meta">{{ formatFileSize(data.size) }}</span>
        </template>
      </Column>

      <Column v-if="columns.uploader" field="creator" :header="t('ui.file_view.col_uploader')" sortable headerStyle="width: 10rem">
        <template #body="{ data }">
          <span class="file-meta">{{ data.creator }}</span>
        </template>
      </Column>

      <Column headerStyle="width: 8rem" bodyStyle="text-align: right">
        <template #body="{ data }">
          <FileActionMenu
            :file="data"
            :writable="writable"
            @versions="emit('versions', data)"
            @rename="startRename(data)"
            @delete="emit('delete', data)"
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

.file-name-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: default;
  outline-offset: 2px;
}

.rename-input {
  width: 100%;
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
</style>
