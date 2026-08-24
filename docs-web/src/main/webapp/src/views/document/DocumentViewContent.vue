<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { getFileUrl, deleteFile, renameFile, uploadFile, setRotation, reorderFiles, getFileList, moveFile } from '../../api/file'
import { partitionByNameConflict, type FileConflict, type ConflictAction } from '../../utils/fileConflicts'
import { shouldPoll, createProcessingPoller } from '../../utils/fileProcessing'
import { displayName } from '../../utils/fileName'
import {
  listDocuments,
  updateDocument,
  setDocumentCover,
  clearDocumentCover,
  buildRelationsParams,
  swapRelation,
  type DocumentListItem,
  type DocumentDetail,
} from '../../api/document'
import { queryKeys } from '../../api/queryKeys'
// pdf.js (~pulled in by PdfViewer) is heavy and only needed when a PDF file is
// actually displayed, so the viewer is loaded on demand into its own chunk.
const PdfViewer = defineAsyncComponent(() => import('../../components/PdfViewer.vue'))
import EmptyState from '../../components/EmptyState.vue'
import FileVersionsDialog from '../../components/FileVersionsDialog.vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import AutoComplete from 'primevue/autocomplete'
import Dialog from 'primevue/dialog'
import SelectButton from 'primevue/selectbutton'
import Select from 'primevue/select'
import FileUpload, { type FileUploadUploaderEvent } from 'primevue/fileupload'
import CameraCaptureButton from '../../components/CameraCaptureButton.vue'
import UploadProgressList from '../../components/UploadProgressList.vue'
import FileListTable from '../../components/FileListTable.vue'
import FileActionMenu, { type FileActionTarget } from '../../components/FileActionMenu.vue'
import FileExtraActions from '../../components/FileExtraActions.vue'
import FileConflictDialog from '../../components/FileConflictDialog.vue'
import FilePreviewDialog, { type PreviewFile } from '../../components/FilePreviewDialog.vue'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import { usePreviewQueue } from '../../composables/usePreviewQueue'
import { useAuthStore } from '../../stores/auth'
import { useRelationSortStore } from '../../stores/relationSort'
import { formatDate } from '../../utils/formatters'
import { sortFiles, type FileSortField, type FileSortDirection } from '../../utils/fileSort'
import {
  sortRelations,
  type RelationSortDirection,
  type RelationSortField,
} from '../../utils/relationSort'
import { injectDocument } from './documentKey'

const doc = injectDocument()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()
const authStore = useAuthStore()
const relationSortStore = useRelationSortStore()

// Grid⇄list toggle for the file view. Grid is the default; the choice is remembered
// per user (localStorage) so two accounts sharing a browser keep independent
// preferences. The stored value is validated on read so a stale/tampered entry can
// only ever resolve to a valid mode.
const FILE_VIEW_MODE_KEY = 'teedy_file_view_mode'
type FileViewMode = 'grid' | 'list'
function fileViewStorageKey() {
  return `${FILE_VIEW_MODE_KEY}:${authStore.username}`
}
const fileViewMode = ref<FileViewMode>(
  localStorage.getItem(`${FILE_VIEW_MODE_KEY}:${authStore.username}`) === 'list' ? 'list' : 'grid',
)
watch(fileViewMode, (v) => localStorage.setItem(fileViewStorageKey(), v))
const fileViewOptions = computed(() => [
  { label: t('ui.file_view.grid'), value: 'grid' as FileViewMode, icon: 'pi pi-th-large' },
  { label: t('ui.file_view.list'), value: 'list' as FileViewMode, icon: 'pi pi-list' },
])

const sanitizedDescription = computed(() => {
  if (!doc.value?.description) return ''
  return DOMPurify.sanitize(doc.value.description)
})

// Custom metadata fields that actually carry a value on this document.
const metadataFields = computed(() =>
  (doc.value?.metadata ?? []).filter((m) => m.value != null && m.value !== ''),
)

function formatMetadataValue(field: { type: string; value?: unknown }) {
  if (field.type === 'BOOLEAN') {
    return field.value ? t('yes') : t('no')
  }
  if (field.type === 'DATE') {
    return formatDate(Number(field.value))
  }
  return String(field.value)
}

// --- Related documents ---
// getDocument returns BOTH directions in `relations`: source=true is an outgoing relation
// this document owns (removable here); source=false is incoming, owned by the OTHER document
// (shown read-only — it must be removed from its source document's view).
//
// Both groups are projected through ONE order (#296). The reader picks it once and it follows
// them from document to document for the session (`useRelationSortStore`).
//
// Until they do, the arrays are handed through UNSORTED. The backend already orders by title —
// `RelationDao.getByDocumentId` ends in `order by d.DOC_TITLE_C` — but in the DATABASE's
// collation, and re-collating that in the browser on mount would silently overrule the server on
// exactly the case/accent pairs where the two disagree. A default that quietly reorders is not a
// default. The collator therefore runs only for an explicit choice.
function projectRelations(source: boolean) {
  const list = (doc.value?.relations ?? []).filter((r) => r.source === source)
  const sort = relationSortStore.sort
  return sort ? sortRelations(list, sort.field, sort.direction) : list
}
const outgoingRelations = computed(() => projectRelations(true))
const incomingRelations = computed(() => projectRelations(false))

// The control is offered only where it has something to reorder. The relations block renders
// for EVERY writable document — including one with no relations at all — so an unconditional
// control would put a dead affordance on that (screenshot-captured) surface. One relation per
// direction is the same case: nothing to reorder, whichever way it points.
const showRelationSort = computed(
  () => outgoingRelations.value.length > 1 || incomingRelations.value.length > 1,
)
// The neutral state is a real OPTION, not a placeholder on an empty model value: a placeholder
// is a one-way door (nothing to click to get back), and PrimeVue's Select renders a null-valued
// option as no-selection anyway. Same shape as the file grid's `manual` entry next door — hence a
// string sentinel in the control, mapped to the store's `null` here rather than bound to it.
const RELATION_SORT_SERVER = 'server'
// `field:direction`, the same key shape the file grid's sort uses — one option per criterion and
// way round, so the neutral entry and the orderings live in ONE flat list the Select can render.
type RelationSortKey = typeof RELATION_SORT_SERVER | `${RelationSortField}:${RelationSortDirection}`

const relationSortKey = computed<RelationSortKey>({
  get: () => {
    const sort = relationSortStore.sort
    // The assertion is the same one the grid's key does on the way back (`split(':') as [...]`):
    // TypeScript widens an interpolated string to `string`, and this is the single place the two
    // halves are joined.
    return sort ? (`${sort.field}:${sort.direction}` as RelationSortKey) : RELATION_SORT_SERVER
  },
  set: (key: RelationSortKey) => {
    if (key === RELATION_SORT_SERVER) {
      relationSortStore.sort = null
      return
    }
    const [field, direction] = key.split(':') as [RelationSortField, RelationSortDirection]
    relationSortStore.sort = { field, direction }
  },
})
// Creation date is the LINKED document's own (`relations[].create_date`, joined server-side), not
// the date this document was linked to it: the reporter reads these lists to find the oldest or
// newest of the documents on the other end.
const relationSortOptions = computed(() => [
  { value: RELATION_SORT_SERVER as RelationSortKey, label: t('ui.relations.sort_default') },
  { value: 'title:asc' as RelationSortKey, label: t('ui.relations.sort_title_asc') },
  { value: 'title:desc' as RelationSortKey, label: t('ui.relations.sort_title_desc') },
  { value: 'create_date:asc' as RelationSortKey, label: t('ui.relations.sort_created_asc') },
  { value: 'create_date:desc' as RelationSortKey, label: t('ui.relations.sort_created_desc') },
])

const relationSearchResults = ref<DocumentListItem[]>([])
const selectedRelationTarget = ref<DocumentListItem | null>(null)
const savingRelation = ref(false)

async function completeRelationSearch(event: { query: string }) {
  const query = event.query.trim()
  if (!query || !doc.value) {
    relationSearchResults.value = []
    return
  }
  try {
    const { data } = await listDocuments({ search: query, limit: 10 })
    // Exclude self and any document already related (either direction).
    const relatedIds = new Set((doc.value.relations ?? []).map((r) => r.id))
    relationSearchResults.value = data.documents.filter(
      (d) => d.id !== doc.value!.id && !relatedIds.has(d.id),
    )
  } catch {
    relationSearchResults.value = []
  }
}

// Submit the FULL surviving outgoing id list (buildRelationsParams sends title + language,
// which the backend requires, and relations_reset=true when the list is empty). Only outgoing
// relations are reconciled by the backend — incoming ones are untouched.
async function saveOutgoing(outgoingIds: string[]) {
  if (!doc.value) return
  const sourceId = doc.value.id
  // Delta of affected TARGETS, captured before the refetch replaces doc.value: an added
  // or removed target's own detail (its incoming list) changes with this mutation, so a
  // cached target view would render stale relations on in-app navigation if only the
  // source were invalidated.
  const prevIds = new Set(outgoingRelations.value.map((r) => r.id))
  const nextIds = new Set(outgoingIds)
  const affectedTargetIds = [
    ...outgoingIds.filter((id) => !prevIds.has(id)),
    ...[...prevIds].filter((id) => !nextIds.has(id)),
  ]
  savingRelation.value = true
  try {
    await updateDocument(
      sourceId,
      buildRelationsParams(doc.value.title, doc.value.language, outgoingIds),
    )
    queryClient.invalidateQueries({ queryKey: ['document', sourceId] })
    for (const id of affectedTargetIds) {
      queryClient.invalidateQueries({ queryKey: ['document', id] })
    }
    toast.add({ severity: 'success', summary: t('ui.relations.saved'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.relations.failed_save'), life: 3000 })
  } finally {
    savingRelation.value = false
  }
}

async function handleAddRelation() {
  if (!selectedRelationTarget.value) return
  const ids = [...outgoingRelations.value.map((r) => r.id), selectedRelationTarget.value.id]
  await saveOutgoing(ids)
  selectedRelationTarget.value = null
  relationSearchResults.value = []
}

/**
 * Reverse a relation's direction (#191). The row is passed in its displayed orientation: an
 * outgoing row reads this document -> the related one, an incoming row the other way round, and
 * the endpoint always takes the pair in its CURRENT orientation. Both documents' details change
 * (the link leaves one outgoing list and joins the other's), so both are invalidated.
 *
 * WRITE on the counterpart document is unknown to the client — `doc.writable` describes only this
 * document — so the server is the sole authority and a refusal surfaces as an error toast.
 */
async function handleSwapRelation(relation: { id: string; title: string; source: boolean }) {
  if (!doc.value?.writable) return
  const documentId = doc.value.id
  const fromId = relation.source ? documentId : relation.id
  const toId = relation.source ? relation.id : documentId
  savingRelation.value = true
  try {
    await swapRelation(fromId, toId)
    queryClient.invalidateQueries({ queryKey: queryKeys.document(documentId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.document(relation.id) })
    toast.add({ severity: 'success', summary: t('ui.relations.swapped'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.relations.failed_swap'), life: 3000 })
  } finally {
    savingRelation.value = false
  }
}

function confirmRemoveRelation(relation: { id: string; title: string }) {
  confirmDanger({
    message: t('ui.relations.remove_confirm', { title: relation.title }),
    header: t('ui.relations.remove'),
    icon: 'pi pi-link',
    accept: async () => {
      const ids = outgoingRelations.value.map((r) => r.id).filter((id) => id !== relation.id)
      await saveOutgoing(ids)
    },
  })
}

const versionsDialogVisible = ref(false)
const versionsFileId = ref<string | null>(null)
const versionsFileName = ref('')

function showVersions(file: { id: string; name: string | null }) {
  versionsFileId.value = file.id
  versionsFileName.value = displayName(file.name, t)
  versionsDialogVisible.value = true
}
const uploading = ref(false)
const uploadProgress = ref<Record<number, number>>({})
const uploadingNames = ref<string[]>([])
const fileUploadRef = ref()
// Whole-batch guard: true for the ENTIRE add-files flow — the actual upload AND the
// (interactive) conflict resolution in between, during which `uploading` is briefly
// false. It disables every add-file affordance and rejects a second batch, so a drop
// arriving mid-resolution can never overwrite the single conflict resolver and strand
// the first batch's undecided files.
const busy = ref(false)

// One upload job: a file, and — when the user chose "add as new version" for a name
// conflict — the id of the file it supersedes (previousFileId → v(n+1)).
interface UploadJob {
  file: File
  previousFileId?: string
}

// --- Name-conflict prompt (#117.2) -------------------------------------------------
// A manual upload-bar drop whose name matches an existing active file of THIS document
// is intercepted so the user can choose add-as-new-version / keep-both / cancel. The
// dialog presents one conflict at a time; `askConflict` resolves when the user clicks.
const conflictDialogVisible = ref(false)
const conflictFileName = ref('')
const conflictRemaining = ref(0)
let conflictResolver: ((decision: { action: ConflictAction; applyToAll: boolean }) => void) | null =
  null

function askConflict(
  fileName: string,
  remaining: number,
): Promise<{ action: ConflictAction; applyToAll: boolean }> {
  conflictFileName.value = fileName
  conflictRemaining.value = remaining
  conflictDialogVisible.value = true
  return new Promise((resolve) => {
    conflictResolver = resolve
  })
}

function onConflictDecision(decision: { action: ConflictAction; applyToAll: boolean }) {
  conflictDialogVisible.value = false
  const resolve = conflictResolver
  conflictResolver = null
  resolve?.(decision)
}

// Turn the conflicting drops into upload jobs by asking the user per conflict, honouring
// an apply-to-all choice for the rest of the batch. A cancelled conflict is dropped.
async function resolveConflicts(conflicts: FileConflict[]): Promise<UploadJob[]> {
  const jobs: UploadJob[] = []
  let bulkAction: ConflictAction | null = null
  for (let i = 0; i < conflicts.length; i++) {
    const conflict = conflicts[i]
    let action = bulkAction
    if (!action) {
      const decision = await askConflict(conflict.file.name, conflicts.length - i)
      action = decision.action
      if (decision.applyToAll) bulkAction = decision.action
    }
    if (action === 'version') jobs.push({ file: conflict.file, previousFileId: conflict.existing.id })
    else if (action === 'keep-both') jobs.push({ file: conflict.file })
    // 'cancel' → skip this file entirely.
  }
  return jobs
}

// Upload a batch of jobs sequentially with per-file progress. A stale-base 409 (the
// version chain moved under an "add as new version" job) surfaces the reload path.
async function runUploads(documentId: string, jobs: UploadJob[]) {
  if (!jobs.length) return
  uploading.value = true
  uploadProgress.value = {}
  uploadingNames.value = jobs.map((j) => j.file.name)
  try {
    // #119: the backend flags a content-identical upload (a renamed duplicate, or an identical new
    // version it collapsed) with duplicateKind='content' + duplicateOfId. Surface ONE non-blocking,
    // purely informational hint per batch — no action is taken server-side. Absent (feature off) it never fires.
    let duplicateHint: { name: string } | null = null
    for (let i = 0; i < jobs.length; i++) {
      uploadProgress.value[i] = 0
      const res = await uploadFile(
        documentId,
        jobs[i].file,
        (pct) => {
          uploadProgress.value[i] = pct
        },
        jobs[i].previousFileId,
      )
      uploadProgress.value[i] = 100
      const data = (res as { data?: { duplicateKind?: string; duplicateOfId?: string } } | undefined)?.data
      if (data?.duplicateKind === 'content' && !duplicateHint) {
        const existing = (doc.value?.files ?? []).find((f) => f.id === data.duplicateOfId)
        duplicateHint = { name: existing?.name ?? jobs[i].file.name }
      }
    }
    toast.add({ severity: 'success', summary: t('ui.files_uploaded'), life: 2000 })
    if (duplicateHint) {
      toast.add({
        severity: 'info',
        summary: t('ui.duplicate_content_hint', { name: duplicateHint.name }),
        life: 6000,
      })
    }
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status
    const staleBase = status === 409
    toast.add({
      severity: 'error',
      summary: staleBase ? t('ui.versions.stale_base') : t('ui.upload_failed'),
      life: staleBase ? 4000 : 3000,
    })
  } finally {
    uploading.value = false
    uploadProgress.value = {}
    uploadingNames.value = []
    fileUploadRef.value?.clear()
    // Invalidate unconditionally: a mid-batch failure still uploaded earlier files,
    // and skipping the refetch would leave them invisible (users re-upload dupes).
    // Detached (not awaited) so the reconciliation never holds `busy` — the add-file
    // affordances and the conflict prompt must not wait on a background pointer write.
    schedulePointerSettle(documentId)
  }
}

// How many times an upload-complete invalidation is repeated while the document still
// reports files but no served-file pointer, and how long to wait between attempts.
const POINTER_SETTLE_ATTEMPTS = 3
const POINTER_SETTLE_DELAY_MS = 500

// Lifecycle token for the pointer reconciliation, owned by this component instance.
// Every scheduled settle captures the generation it was started with and abandons itself
// as soon as the live value moves on, which makes the loop both SINGLE-FLIGHT and
// cancellable with one primitive.
let pointerSettleGeneration = 0

/**
 * Start a pointer reconciliation, superseding any settle still in flight.
 *
 * SINGLE-FLIGHT WITH RESTART, not coalesce: `runUploads` fires once per batch (the
 * conflict path runs a second batch after the fresh one) and a new drop can start once
 * `busy` clears, so overlapping settles are reachable. Two live settlers would invalidate
 * the same exact key concurrently and cancel each other's refetch (vue-query's default
 * `cancelRefetch: true`), so at most one may run. Restart rather than coalesce because the
 * later wave is the one whose files still need a pointer: a settle already running for an
 * earlier batch can exit on ITS observation (e.g. the fresh batch's pointer landed) and
 * would leave a later batch — the one that actually added the first file — unreconciled.
 */
function schedulePointerSettle(documentId: string) {
  pointerSettleGeneration += 1
  void settleServingPointer(documentId, pointerSettleGeneration)
}

/**
 * Refetch the document until its served-file pointer catches up with the upload (#199).
 *
 * `file_id` is NOT written by PUT /file: DocumentUpdatedAsyncEvent →
 * DocumentUpdatedAsyncListener fills DOC_IDFILE_C after the request returns, so a single
 * invalidation has no happens-after relation to that write. Losing that race caches a
 * null pointer as fresh for the query's staleTime, and every consumer of the pointer (the
 * header Download link, list/gallery thumbnails) stays empty until something else
 * refetches. Re-invalidate a bounded number of times while the refetched document says it
 * HAS files but still carries no pointer.
 *
 * Gated on `file_count > 0`: after a total upload failure a null pointer is the truth, and
 * looping on it would refetch three times for nothing. Scope is the first-file /
 * null-pointer case only — a version replace (non-null → non-null pointer) is not covered
 * here and is tracked separately (see useVersionUpload).
 *
 * Only ever entered through `schedulePointerSettle`, which owns the generation token. Each
 * step re-checks that token after every await, so a superseding schedule or an unmount
 * stops the loop at its next checkpoint: nothing is invalidated, and the last armed timer
 * fires into a stale generation and returns without touching anything.
 */
async function settleServingPointer(documentId: string, generation: number) {
  // exact: ['document', id] is a leaf key; a prefix match would also invalidate unrelated
  // ['document', id, …] queries a future feature may add.
  const key = ['document', documentId]
  const isCurrent = () => generation === pointerSettleGeneration
  for (let attempt = 0; attempt < POINTER_SETTLE_ATTEMPTS; attempt++) {
    if (attempt > 0) {
      await new Promise((resolve) => setTimeout(resolve, POINTER_SETTLE_DELAY_MS))
    }
    if (!isCurrent()) return
    // Awaited: invalidateQueries resolves once the active refetch has settled, so the
    // cache read below sees the response this attempt asked for, not the previous one.
    await queryClient.invalidateQueries({ queryKey: key, exact: true })
    // The await above can span an unmount or a newer batch; re-check before deciding
    // whether another round is warranted off data that is no longer this loop's concern.
    if (!isCurrent()) return
    const fresh = queryClient.getQueryData<DocumentDetail>(key)
    // No cached document (navigated away, or nothing observing it) — nothing to settle.
    if (!fresh) return
    if (!(fresh.file_count > 0 && !fresh.file_id)) return
  }
}

async function uploadAll(files: File[]) {
  // Reject a second batch while one is in flight (upload OR conflict resolution): the
  // conflict resolver is a single slot, so a concurrent batch would clobber it.
  if (!doc.value || !files.length || busy.value) return
  // Snapshot the id and existing-file names up front: the injected ref can be cleared
  // or refetched while the (possibly interactive) batch is in flight, but the version
  // bases and the target document must stay fixed to the drop moment.
  const documentId = doc.value.id
  const existing = (doc.value.files ?? []).map((f) => ({ id: f.id, name: f.name }))
  const { conflicts, fresh } = partitionByNameConflict(files, existing)

  busy.value = true
  try {
    // Non-conflicting files upload straight away — no prompt.
    await runUploads(documentId, fresh.map((f) => ({ file: f })))

    // Then resolve each name conflict with the user and upload the chosen jobs.
    if (conflicts.length) {
      const jobs = await resolveConflicts(conflicts)
      await runUploads(documentId, jobs)
    }
  } finally {
    busy.value = false
  }
}

async function handleUpload(event: FileUploadUploaderEvent) {
  const files = Array.isArray(event.files) ? event.files : [event.files]
  await uploadAll(files as File[])
}

// Camera capture: photos upload IMMEDIATELY via the same real PUT /api/file path,
// BYPASSING the name-conflict prompt. That interception (#117.2) is scoped to the
// manual upload bar; a camera capture keeps its prior add-a-new-file behavior even when
// a same-named file already exists.
async function onCameraCapture(captured: File[]) {
  if (!doc.value || !captured.length || busy.value) return
  const documentId = doc.value.id
  busy.value = true
  try {
    await runUploads(documentId, captured.map((f) => ({ file: f })))
  } finally {
    busy.value = false
  }
}

// Persisted, non-destructive image rotation, per file. The server bakes the rotation into the
// served `_web` raster, so we do NOT apply any CSS transform to the image (that would double-rotate).
// The stored rotation drives only (i) the absolute value computed on a rotate click and (ii) the
// `?v=<rotation>` cache-bust key on the image URL. In-flight rotations are tracked so the URL and
// the button stay disabled until the query refetch replaces `doc.value` with the persisted value.
const pendingRotation = ref<Record<string, number>>({})
const rotating = ref<Record<string, boolean>>({})

// The rotation to render/cache-bust with: the optimistic in-flight value if present, else the
// persisted server value on the file.
function effectiveRotation(file: { id: string; rotation?: number }): number {
  return pendingRotation.value[file.id] ?? file.rotation ?? 0
}

async function persistRotation(file: { id: string; rotation?: number }, next: number) {
  const normalized = ((next % 360) + 360) % 360
  pendingRotation.value[file.id] = normalized
  rotating.value[file.id] = true
  try {
    await setRotation(file.id, normalized)
    // Invalidate BOTH the document detail (this view's files[].rotation + cache-bust) AND the
    // documents list (gallery/table/slide-over rows carry file_rotation and a cache-busted thumb
    // URL) so every consumer picks up the new rotation, not just this page.
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] }),
      queryClient.invalidateQueries({ queryKey: queryKeys.documents() }),
    ])
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_rotate_file'), life: 3000 })
  } finally {
    // Drop the optimistic value: the refetched doc now carries the persisted rotation, and the
    // cache-bust key must follow the authoritative value from here on.
    delete pendingRotation.value[file.id]
    rotating.value[file.id] = false
  }
}

function rotateImageLeft(file: { id: string; rotation?: number }) {
  void persistRotation(file, effectiveRotation(file) + 270)
}

function rotateImageRight(file: { id: string; rotation?: number }) {
  void persistRotation(file, effectiveRotation(file) + 90)
}

function isImage(mime: string) {
  return mime.startsWith('image/')
}

function fileIcon(mime: string) {
  if (mime.startsWith('image/')) return 'pi pi-image'
  if (mime === 'application/pdf') return 'pi pi-file-pdf'
  return 'pi pi-file'
}

// The safe in-app preview (#144). The list's double-click / icon, and the grid's generic
// card, all route here. They deliberately do NOT open the original file URL: the backend
// serves it as an attachment under a locked-down CSP (a stored-XSS control), so opening
// it only triggers a download. The dialog renders a derived, safe representation per type
// and keeps the original URL behind a single explicit Download control.
const previewVisible = ref(false)
const previewFile = ref<PreviewFile | null>(null)
function openPreview(file: PreviewFile) {
  previewFile.value = { id: file.id, name: file.name, mimetype: file.mimetype, rotation: file.rotation }
  previewVisible.value = true
}

// --- File deep link (#192) ---------------------------------------------------------
// `?file=<id>` on this route and the preview dialog are two views of ONE piece of state,
// kept in sync in both directions:
//
//   URL → preview  a known id opens the preview on that file as soon as the document's
//                  files have resolved; an unknown/stale id (deleted, or moved to another
//                  document) opens nothing, is cleared out of the URL and warns once.
//   preview → URL  opening writes the key, closing removes it — always by REPLACE, never
//                  push, exactly like the DocumentList filter params: a preview is a view
//                  of the current page, not a history entry of its own.
//
// The two directions feed each other (a replace re-fires the route watcher, which would
// re-open the preview, which would replace again), so BOTH writers are guarded by a
// value-equality check against the state the other side already holds. `syncFileParam` is
// the only place the URL is written, and it returns early when the URL already says what
// the app shows; `hydrateFromRoute` returns early when the preview already shows what the
// URL says.
//
// The query is edited SURGICALLY ({...route.query} minus/plus the one key) rather than
// rebuilt, so any other param on the route — present or future — survives untouched.

// The id whose "no such file" warning has already been shown. The clearing replace is
// asynchronous, so a document refetch can re-run the resolution while the dead id is still
// in the URL; without this the same dead link would toast on every refetch.
let warnedMissingId: string | null = null

// The `file` value this component last reconciled with, which distinguishes "the URL never
// carried a param" from "the URL carried one and it was taken away". Only the latter closes
// a preview. The same asynchrony is why: a refetch landing between the user opening the
// preview and the replace arriving would otherwise see no param yet and close the dialog
// the user had just opened.
let appliedFileParam: string | null = null

// The scalar `file` param, or undefined when absent OR malformed. Repeated params arrive as
// an array (?file=a&file=b) and name no single file, so they are inactive — and, like
// DocumentList's invalid filter values, canonicalized out of the URL rather than left to sit.
const fileParam = computed(() => {
  const raw = route.query.file
  return typeof raw === 'string' && raw ? raw : undefined
})

// Sentinel for a param that IS present but names no single file (repeated key, or bare
// `?file=`). It can never equal a target id, so such a value always gets rewritten instead
// of being mistaken for "absent" by the equality guard below and left in the URL forever.
const MALFORMED_FILE_PARAM = Symbol('malformed file param')

function currentFileParam(): string | undefined | typeof MALFORMED_FILE_PARAM {
  const raw = route.query.file
  if (raw === undefined) return undefined
  return typeof raw === 'string' && raw ? raw : MALFORMED_FILE_PARAM
}

// The target of a replace that has been issued but is not yet visible in `route`. Two
// distinct writers can ask for the SAME replacement in one tick — the missing-file branch
// clears the param and, by closing the stale preview, makes the preview watcher ask to
// clear it too — and a refetch arriving before the navigation settles re-runs the whole
// resolution against a URL that still holds the old value. Both would re-issue an identical
// replace. `undefined` means nothing is in flight; `null` is a real target ("remove the
// key"), which is why absence cannot be spelled as null here.
let pendingFileParam: string | null | undefined = undefined

function syncFileParam(fileId: string | null) {
  const current = currentFileParam()
  const target = fileId ?? undefined
  // Loop guard AND no-op guard: the URL already carries exactly this value, and nothing
  // this component asked for is outstanding any more.
  if (target === current) {
    pendingFileParam = undefined
    return
  }
  // The identical write is already on its way — asking twice changes nothing.
  if (pendingFileParam !== undefined && (pendingFileParam ?? undefined) === target) return
  pendingFileParam = fileId
  const query = { ...route.query }
  if (fileId) query.file = fileId
  else delete query.file
  router.replace({ name: 'document-view-content', params: route.params, query })
}

function hydrateFromRoute() {
  // A replace this component issued has landed — nothing is outstanding any more.
  if (pendingFileParam !== undefined && currentFileParam() === (pendingFileParam ?? undefined)) {
    pendingFileParam = undefined
  }
  // An unresolved document is not a missing file: wait for the detail query before judging
  // the id. `files` is always present on a loaded document.
  if (!doc.value) return
  const raw = route.query.file
  const id = fileParam.value
  if (!id) {
    // Malformed-but-present (array / empty) values are canonicalized away…
    if (raw !== undefined) syncFileParam(null)
    // …and a param this component had ALREADY applied, now gone (Back, or an in-app
    // navigation that dropped it), closes the preview it had opened. Writing the URL back
    // here is what the equality guard in syncFileParam prevents.
    if (appliedFileParam !== null && previewVisible.value) {
      previewVisible.value = false
      previewFile.value = null
    }
    appliedFileParam = null
    return
  }
  const file = (doc.value.files ?? []).find((f) => f.id === id)
  if (!file) {
    // The file the link named is gone (deleted, or moved to another document). The URL is
    // authoritative, so ANY preview closes here — not just one of this id. A navigation to
    // a dead link while file A is on screen must not leave A standing: the user would go on
    // reading A while the address bar names, and then disclaims, something else.
    if (previewVisible.value) {
      previewVisible.value = false
      previewFile.value = null
    }
    appliedFileParam = null
    syncFileParam(null)
    if (warnedMissingId !== id) {
      warnedMissingId = id
      toast.add({ severity: 'warn', summary: t('ui.file_view.link_not_found'), life: 4000 })
    }
    return
  }
  appliedFileParam = id
  // Already showing it — this is the arm of the guard that stops open→replace→open.
  if (previewVisible.value && previewFile.value?.id === id) return
  openPreview(file)
}

// Re-resolve on BOTH inputs: the param itself (cold load, in-app Back, a second deep link)
// and the document's file set (the cold-load case where the id arrives before the files,
// and a refetch that removes the previewed file).
watch(
  [() => route.query.file, () => doc.value?.id, () => (doc.value?.files ?? []).map((f) => f.id).join(',')],
  () => hydrateFromRoute(),
  { immediate: true },
)

// The other direction. Collapsing "which file is on screen" into one value keeps open,
// close and re-target on a single writer.
watch(
  () => (previewVisible.value ? (previewFile.value?.id ?? null) : null),
  (id) => syncFileParam(id),
)

// Commit an inline rename requested by the grid tile or the list. Both edit surfaces
// funnel through here — the single write boundary — so a read-only document (or a mid-
// edit permission flip to read-only) can never issue a rename, whatever opened the editor.
async function renameFileTo(fileId: string, name: string) {
  if (!doc.value?.writable) return
  const trimmed = name.trim()
  if (!trimmed) return
  try {
    await renameFile(fileId, trimmed)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
    toast.add({ severity: 'success', summary: t('ui.file_renamed'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_rename_file'), life: 3000 })
  }
}

// Grid-tile inline rename. The grid uses a compact per-card editor (the list has its own
// in-cell editor); both funnel through renameFileTo for the real mutation.
const gridRenamingId = ref<string | null>(null)
const gridRenameValue = ref('')
function startGridRename(file: { id: string; name: string | null }) {
  if (!doc.value?.writable) return
  gridRenamingId.value = file.id
  // Empty-seed a null-name file so it is named from scratch and commit's trim() never sees null.
  gridRenameValue.value = file.name ?? ''
}
function cancelGridRename() {
  gridRenamingId.value = null
  gridRenameValue.value = ''
}
function commitGridRename(fileId: string) {
  if (gridRenamingId.value !== fileId) return
  // Guard the commit too: a permission refetch to read-only WHILE the editor is open
  // must not let Enter/blur fire the write.
  if (!doc.value?.writable) return cancelGridRename()
  const name = gridRenameValue.value.trim()
  const original = doc.value?.files?.find((f) => f.id === fileId)?.name
  if (name && name !== original) void renameFileTo(fileId, name)
  cancelGridRename()
}

// --- Grid drag reorder (#211) -------------------------------------------------------
// The list reorders through PrimeVue DataTable's `rowReorder` (FileListTable:299), which has
// no equivalent in a CSS grid, so the grid reorders with native HTML5 drag-and-drop — the same
// primitive PdfPageOrganizer:224 uses — off a dedicated per-tile handle. What IS shared with
// the list is the persistence contract, and only that: one POST /file/reorder carrying the
// COMPLETE id order, an optimistic local order, and a deterministic rollback on failure.
//
// The optimistic order has to live here rather than in FileListTable, because in grid mode
// that component is not mounted at all: `fileListRef` is null, so the confirm/rollback calls
// below would land on nothing and a rejected persist would leave the grid showing an order
// the server refused.
type DocFile = NonNullable<DocumentDetail['files']>[number]

// Above this count the handles are withdrawn, exactly as in the list (FileListTable:65).
// It is not a rendering cap — every tile always renders — it guards the reorder contract:
// the endpoint needs the complete id order, and a drag across hundreds of tiles is
// error-prone and easy to drop files from.
const GRID_REORDER_LIMIT = 100

// Working copy of the grid's order, re-seeded from every refetch (the backend returns the
// files in their persisted `order`), so a saved reorder survives a reload and a rejected one
// is replaced by the authoritative order as soon as the refetch lands.
const gridOrderedFiles = ref<DocFile[]>([])
// The order in effect BEFORE the last optimistic drag: a failed persist rolls back to exactly
// this sequence, independently of whether the refetch that follows also succeeds.
let gridOrderBeforeDrag: DocFile[] = []
// Serialize reorders: only ONE may be in flight. While a persist is pending the handles are
// withdrawn, so a second drag can never overwrite the single pre-drag snapshot (which would
// let a late failure roll back to the wrong order). ONLY confirm/rollback clears it — a
// refetch must not, or a sibling mutation's refresh would re-open the door mid-POST.
const gridReorderPending = ref(false)
// The tile a drag started on. Only an ARMED tile sets it (see onGridCardMouseDown), which is
// what keeps every other control on a tile an ordinary control: a drag that did not begin on a
// handle (an image's native drag, a text selection, a file dragged in from the desktop) leaves
// this null, and every drop below is then inert.
const gridDragIndex = ref<number | null>(null)
// The tile the pointer is currently over, for the drop-target outline.
const gridDragOverId = ref<string | null>(null)

// A file-set refresh that arrived while the grid's order was FROZEN. It is remembered rather
// than applied, and reconciled once the freeze lifts. Any sibling mutation — a rename, a
// rotation, the upload poll, another tab — invalidates the document query, so such a refresh can
// land at any instant.
let gridPendingRefresh: DocFile[] | null = null

// The order is frozen for as long as anything is holding an index into it:
//   * an ACTIVE DRAG (dragstart → drop/dragend) holds `gridDragIndex`, a position in THIS
//     sequence. Re-seed underneath it and that index silently names a different file: the drop
//     would move — and POST — the wrong one.
//   * a PENDING PERSIST holds the optimistic order the in-flight request is about to confirm.
// Outside both, a refresh is authoritative and applied at once.
const gridOrderFrozen = () => gridDragIndex.value !== null || gridReorderPending.value

watch(
  () => doc.value?.files,
  (files) => {
    const next = [...(files ?? [])]
    if (gridOrderFrozen()) {
      gridPendingRefresh = next
      return
    }
    gridOrderedFiles.value = next
  },
  { immediate: true },
)

// Apply a held-back refresh once nothing holds the order any more. A drag that ended without a
// persist (cancelled, dropped on its own tile, dropped outside) unfreezes here; a drag that DID
// persist stays frozen and is reconciled by confirm/rollback instead.
function releaseGridRefresh() {
  if (gridOrderFrozen()) return
  const fresh = gridPendingRefresh
  gridPendingRefresh = null
  if (fresh) gridOrderedFiles.value = fresh
}

// --- Grid transient sort (#211) -------------------------------------------------------
// The list view has a transient column sort (FileListTable:103); the grid had none, so the same
// files could not be ordered the same way in the two views. This is that missing half, and it is
// deliberately the SAME KIND of thing: view-only, never persisted, never near POST /file/reorder,
// and cleared back to the manual order by an explicit choice.
//
// It is a PROJECTION, not a second order: `gridOrderedFiles` — the optimistic manual order the
// whole reorder contract above is built on — is read, cloned and sorted, never written. Every
// freeze/refresh/confirm/rollback path therefore keeps working on the manual order exactly as
// before, and the projection simply re-derives afterwards. With no sort active the projection IS
// the manual array (same reference), so the tile indices the drag handlers take stay indices into
// `gridOrderedFiles`.
const GRID_SORT_MANUAL = 'manual'
type GridSortKey = typeof GRID_SORT_MANUAL | `${FileSortField}:${FileSortDirection}`

// The criteria the LIST offers as sortable columns (FileListTable's `sortable` Columns) minus
// Uploader, which no tile displays — the grid card shows a name, a size-free preview and a date,
// so an uploader sort would reorder tiles by something invisible on them.
const gridSortKey = ref<GridSortKey>(GRID_SORT_MANUAL)
const gridSortOptions = computed(() => [
  { value: GRID_SORT_MANUAL as GridSortKey, label: t('ui.file_view.sort_manual') },
  { value: 'name:asc' as GridSortKey, label: t('ui.file_view.sort_name_asc') },
  { value: 'name:desc' as GridSortKey, label: t('ui.file_view.sort_name_desc') },
  { value: 'create_date:asc' as GridSortKey, label: t('ui.file_view.sort_date_asc') },
  { value: 'create_date:desc' as GridSortKey, label: t('ui.file_view.sort_date_desc') },
  { value: 'size:asc' as GridSortKey, label: t('ui.file_view.sort_size_asc') },
  { value: 'size:desc' as GridSortKey, label: t('ui.file_view.sort_size_desc') },
])

const gridSort = computed<{ field: FileSortField; direction: FileSortDirection } | null>(() => {
  if (gridSortKey.value === GRID_SORT_MANUAL) return null
  const [field, direction] = gridSortKey.value.split(':') as [FileSortField, FileSortDirection]
  return { field, direction }
})

// Returning `gridOrderedFiles.value` UNWRAPPED (not a copy) in the manual case is load-bearing,
// not an optimisation: the drag handlers index into the manual order, so the rendered list has to
// be that very array whenever a drag is possible at all.
const gridDisplayFiles = computed(() =>
  gridSort.value
    ? sortFiles(gridOrderedFiles.value, gridSort.value.field, gridSort.value.direction)
    : gridOrderedFiles.value,
)

// Eligibility parity with the list (FileListTable:157): a writable document, the COMPLETE
// unfiltered/unsorted order, under the size threshold, and no persist in flight. The sort clause
// is the grid's analogue of the list's `!sortField` — a drop into a sorted projection has no
// meaningful target index, and the endpoint needs the complete MANUAL order.
const gridReorderEnabled = computed(
  () =>
    !!doc.value?.writable &&
    !gridSort.value &&
    gridOrderedFiles.value.length <= GRID_REORDER_LIMIT &&
    !gridReorderPending.value,
)


// A drag ends with a pointer release over a tile, which the browser may follow with a click on
// whatever sits under it — the preview button, a rotation control, the action menu. Exactly ONE
// such click is swallowed: within this window AND only inside the card that was dragged, which
// is the card the drop leaves under the pointer. A click anywhere else in the grid is the
// user's own and must go through, even in the same millisecond.
const GRID_DROP_CLICK_SUPPRESS_MS = 300
let gridDropAt = 0
// The card element the current drag started from, and the one the last drop landed. Held as
// elements, not ids, so the check is a plain DOM containment test against the click target.
let gridDragCard: HTMLElement | null = null
let gridDroppedCard: HTMLElement | null = null

// A tile becomes a drag source ONLY for a gesture that began on its handle, and stops being one
// the moment any other mousedown lands on it. This is the same arming trick PrimeVue's DataTable
// uses for the list's row handle (`onRowMouseDown`, datatable/index.mjs:5580) — and it is not a
// stylistic choice: a nested `draggable` handle inside the tile starts no drag at all (measured
// against the running app — dragging such a handle fires no dragstart, while an armed card fires
// the full dragstart/dragenter/dragover chain). It is set imperatively, like PrimeVue's, because
// a reactive attribute would not be in the DOM before the browser decides whether to drag.
// The currently armed card, and the document-level release that always finds it. A press can
// end anywhere — off the tile, off the grid, outside the window — and in those cases neither the
// card's mouseup nor its mouseleave-with-no-button ever fires, so a card-scoped listener alone
// leaves the tile armed. The document listener exists ONLY while something is armed.
let gridArmedCard: HTMLElement | null = null

function disarmGridCard() {
  if (gridArmedCard) gridArmedCard.draggable = false
  gridArmedCard = null
  document.removeEventListener('mouseup', onGridDocumentMouseUp)
}

function onGridDocumentMouseUp() {
  // A real drag never gets here: the browser ends it with dragend and delivers no mouseup.
  if (gridDragIndex.value !== null) return
  disarmGridCard()
}

function onGridCardMouseDown(event: MouseEvent) {
  const card = event.currentTarget as HTMLElement
  const origin = event.target as HTMLElement | null
  const arm = !!(gridReorderEnabled.value && origin?.closest('.file-card-drag-handle'))
  // Every press re-evaluates the arming, so whatever was armed before is released first.
  disarmGridCard()
  card.draggable = arm
  if (!arm) return
  gridArmedCard = card
  document.addEventListener('mouseup', onGridDocumentMouseUp)
}

// A press on the handle that produced no drag (a plain click) must not leave the card armed.
//
// Only ever with NO button held. A mouseleave WITH the button down is the pointer being carried
// off the tile by the very gesture that is about to become a drag, and Chromium dispatches that
// boundary crossing BEFORE it turns the move into a dragstart: disarming there cancels every
// drag at its first pixel. Measured — an unguarded mouseleave disarm made the e2e grid reorder
// fail on both viewports (no drop, no persist) while every other file-panel spec stayed green.
// `buttons` is 0 on mouseup too, so the one guard covers both events.
function onGridCardDisarm(event: MouseEvent) {
  if (event.buttons !== 0 || gridDragIndex.value !== null) return
  const card = event.currentTarget as HTMLElement | null
  if (card) card.draggable = false
  disarmGridCard()
}

function onGridDragStart(index: number, event: DragEvent) {
  // An unarmed tile is not draggable, so the browser fires no dragstart on it at all; asserting
  // it here as well is what makes "the handle is the only drag origin" a checkable invariant
  // rather than a property of the arming code alone.
  const card = event.currentTarget as HTMLElement | null
  if (!gridReorderEnabled.value || !card?.draggable) return
  gridDragIndex.value = index
  gridDragCard = card
  // Firefox starts no drag at all unless the dataTransfer carries something. The payload is
  // never read back — the index above is the source of truth.
  if (event.dataTransfer) {
    event.dataTransfer.setData('text/plain', gridOrderedFiles.value[index]?.id ?? '')
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onGridDragOver(fileId: string, event: DragEvent) {
  // Only a tile drag may be dropped here. Without preventDefault the browser rejects the drop
  // outright; WITH it unconditionally, a tile would also start accepting the OS file drags
  // that belong to the upload dropzone.
  if (gridDragIndex.value === null) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  if (gridDragOverId.value !== fileId) gridDragOverId.value = fileId
}

function onGridDragLeave(fileId: string) {
  if (gridDragOverId.value === fileId) gridDragOverId.value = null
}

function onGridDrop(index: number) {
  const from = gridDragIndex.value
  gridDragIndex.value = null
  gridDragOverId.value = null
  if (from === null) {
    // Not a tile drag (nothing was armed), so nothing was frozen by it either.
    releaseGridRefresh()
    return
  }
  // Any completed drag arms the click suppressor — including one that changes nothing, which
  // still ends over a tile control. It is armed for the DRAGGED card, which the reorder leaves
  // sitting under the pointer; every other tile stays clickable.
  gridDropAt = Date.now()
  gridDroppedCard = gridDragCard
  if (gridReorderEnabled.value && from !== index) {
    const next = [...gridOrderedFiles.value]
    const [moved] = next.splice(from, 1)
    next.splice(index, 0, moved)
    // Snapshot BEFORE applying the optimistic order, so a rejected persist reverts to exactly
    // the last sequence the server acknowledged.
    gridOrderBeforeDrag = [...gridOrderedFiles.value]
    gridReorderPending.value = true
    gridOrderedFiles.value = next
    void onReorderFiles(
      next.map((f) => f.id),
      'grid',
    )
  }
  // A persist takes the freeze over (confirm/rollback reconciles); a drop that persisted
  // nothing has to lift it here, or a refresh held back mid-drag would never be applied.
  releaseGridRefresh()
}

function onGridDragEnd(event: DragEvent) {
  gridDragIndex.value = null
  gridDragOverId.value = null
  gridDragCard = null
  // Disarm: the tile stops being a drag source until a handle mousedown arms it again.
  const card = event.currentTarget as HTMLElement | null
  if (card) card.draggable = false
  disarmGridCard()
  // A drag that ended without a drop (cancelled with Escape, released outside the grid) leaves
  // the order frozen and a refresh possibly held back — lift it.
  releaseGridRefresh()
}

function onGridClickCapture(event: MouseEvent) {
  const dropped = gridDroppedCard
  if (!dropped) return
  if (Date.now() - gridDropAt >= GRID_DROP_CLICK_SUPPRESS_MS) {
    gridDroppedCard = null
    return
  }
  // Scope: only the card that was just dropped. A click on ANY other tile in the same window is
  // the user's own — swallowing it would eat a preview, a rotation or an action-menu press on a
  // file the drag never touched.
  const target = event.target as Node | null
  if (!target || !dropped.contains(target)) return
  // One click only: disarm before swallowing, so a drop can never eat two.
  gridDroppedCard = null
  gridDropAt = 0
  event.stopPropagation()
  event.preventDefault()
}

// Called when POST /file/reorder resolves: the optimistic order already equals the persisted
// one, so this releases the in-flight lock and reconciles any refresh that landed meanwhile
// (the post-persist refetch re-seeds authoritatively shortly after).
function confirmGridReorder() {
  gridReorderPending.value = false
  const fresh = gridPendingRefresh
  gridPendingRefresh = null
  if (!fresh) return
  // That refresh was computed BEFORE the server applied this reorder: its file SET is newer,
  // its ORDER is stale. So reconcile rather than replace — keep the sequence the server just
  // acknowledged for the files that survive, and append whatever the refresh brought (which is
  // where the backend appends a new file anyway). Replacing wholesale would bounce every tile
  // back to the pre-drag order until the post-persist refetch lands.
  const byId = new Map(fresh.map((f) => [f.id, f]))
  const kept = gridOrderedFiles.value.flatMap((f) => {
    const current = byId.get(f.id)
    return current ? [current] : []
  })
  const known = new Set(gridOrderedFiles.value.map((f) => f.id))
  gridOrderedFiles.value = [...kept, ...fresh.filter((f) => !known.has(f.id))]
}

// Called when POST /file/reorder rejects: restore the last order the server acknowledged and
// release the lock, regardless of whether the refetch that follows succeeds.
function rollbackGridReorder() {
  const fresh = gridPendingRefresh
  gridPendingRefresh = null
  // A refresh that landed mid-flight is a NEWER baseline than the pre-drag snapshot — that
  // snapshot can name files the refresh deleted, and miss ones it added — and it carries the
  // pre-reorder order, which is exactly what a rollback wants. Prefer it when there is one.
  gridOrderedFiles.value = fresh ?? [...gridOrderBeforeDrag]
  gridReorderPending.value = false
}

// Reference to the list so a failed reorder can be rolled back deterministically at the
// component that owns the optimistic order (present only while the list view is mounted).
const fileListRef = ref<InstanceType<typeof FileListTable> | null>(null)

// Which view raised the reorder — the two own their optimistic order in different places
// (the list inside FileListTable, the grid in this component, because FileListTable is not
// mounted in grid mode), so confirm/rollback has to be routed rather than broadcast.
type ReorderSource = 'list' | 'grid'

// Persist an explicit drag reorder (the only order-persisting action) via the existing
// reorder endpoint. On success the refetch re-seeds the raising view from the authoritative
// order (so it survives reload); on failure that view rolls its optimistic order back to the
// last saved sequence — never a false "saved".
async function onReorderFiles(orderedIds: string[], source: ReorderSource = 'list') {
  const documentId = doc.value?.id
  if (!documentId) {
    // Nothing to persist against. The grid holds its in-flight lock until confirm/rollback, so
    // it has to be released here too — otherwise the handles never come back.
    if (source === 'grid') rollbackGridReorder()
    return
  }
  try {
    await reorderFiles(documentId, orderedIds)
    toast.add({ severity: 'success', summary: t('ui.file_view.reorder_saved'), life: 2000 })
    // Release the in-flight lock so the drag re-enables promptly (before the refetch
    // settles); the optimistic order already equals the persisted one.
    if (source === 'grid') confirmGridReorder()
    else fileListRef.value?.confirmReorder()
    queryClient.invalidateQueries({ queryKey: ['document', documentId] })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.file_view.reorder_failed'), life: 3000 })
    // Deterministic local rollback independent of the refetch (which may also fail).
    if (source === 'grid') rollbackGridReorder()
    else fileListRef.value?.rollbackReorder()
    queryClient.invalidateQueries({ queryKey: ['document', documentId] })
  }
}

function confirmDelete(file: { id: string; name: string | null }) {
  confirmDanger({
    message: t('ui.remove_file_confirm', { name: displayName(file.name, t) }),
    header: t('ui.remove_file'),
    accept: async () => {
      try {
        await deleteFile(file.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
        toast.add({ severity: 'success', summary: t('ui.file_removed'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.failed_remove_file'), life: 3000 })
      }
    },
  })
}

// Invalidate BOTH the document detail (this view's file_id_cover + served file_id) AND the documents
// list (gallery/table/slide-over rows render the served file_id thumbnail), because setting or
// clearing the cover changes which file the thumbnail resolves to.
async function invalidateAfterCoverChange() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] }),
    queryClient.invalidateQueries({ queryKey: queryKeys.documents() }),
  ])
}

async function setCoverFor(file: { id: string }) {
  const documentId = doc.value?.id
  if (!documentId) return
  try {
    await setDocumentCover(documentId, file.id)
    await invalidateAfterCoverChange()
    toast.add({ severity: 'success', summary: t('ui.cover_set'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_set_cover'), life: 3000 })
  }
}

async function clearCoverFor() {
  const documentId = doc.value?.id
  if (!documentId) return
  try {
    await clearDocumentCover(documentId)
    await invalidateAfterCoverChange()
    toast.add({ severity: 'success', summary: t('ui.cover_cleared'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_set_cover'), life: 3000 })
  }
}

// "Move to document…": a search-driven picker over documents. The rows carry no writable flag — the
// server is the sole authority, so a target the caller cannot write to comes back as a 403 surfaced
// through the error toast (no client-side pre-filter). Both the source and the destination document's
// cached views change, so both are invalidated alongside the document list.
const moveDialogVisible = ref(false)
const fileToMove = ref<FileActionTarget | null>(null)
const moveSearchResults = ref<DocumentListItem[]>([])
const moveTarget = ref<DocumentListItem | null>(null)
const movingFile = ref(false)

// Generation counter for the picker's searches. A response may only publish when it is the newest
// search OF THE CURRENT PICKER SESSION; any other write is harmful, because the AutoComplete
// re-opens its overlay whenever its suggestions change while a search is pending. So a stale write
// re-shows a dropdown — for the previous query, or for a session the user already dismissed and
// reopened — and an out-of-order write replaces the live query's results with an older query's.
// Opening the picker starts a new generation, and every search (including one whose query was
// cleared) bumps it, so any request still in flight from before is superseded.
let moveSearchSeq = 0

function openMoveDialog(file: FileActionTarget) {
  fileToMove.value = file
  moveTarget.value = null
  moveSearchResults.value = []
  moveSearchSeq++
  moveDialogVisible.value = true
}

async function completeMoveSearch(event: { query: string }) {
  const seq = ++moveSearchSeq
  const isCurrent = () => seq === moveSearchSeq && moveDialogVisible.value
  const query = event.query.trim()
  if (!query || !doc.value) {
    moveSearchResults.value = []
    return
  }
  try {
    const { data } = await listDocuments({ search: query, limit: 10 })
    if (!isCurrent()) return
    // Exclude the current document — moving to the same document is rejected by the backend.
    moveSearchResults.value = data.documents.filter((d) => d.id !== doc.value!.id)
  } catch {
    if (!isCurrent()) return
    moveSearchResults.value = []
  }
}

async function confirmMove() {
  const sourceId = doc.value?.id
  const targetId = moveTarget.value?.id
  const fileId = fileToMove.value?.id
  if (!sourceId || !targetId || !fileId) return
  movingFile.value = true
  try {
    await moveFile(fileId, targetId)
    // Dismiss the modal as soon as the SERVER has confirmed the move — the user's action is
    // complete at that point. Holding a full-screen modal mask open across the cache refresh
    // (as this handler used to) makes dismissal hostage to three extra round trips: a slow or
    // failing refetch leaves the app pointer-blocked after a move that already succeeded.
    // Every sibling mutation here (rename, remove, reorder, relations) already invalidates
    // without gating its UI on the refetch.
    moveDialogVisible.value = false
    toast.add({ severity: 'success', summary: t('ui.file_moved'), life: 2000 })
    queryClient.invalidateQueries({ queryKey: queryKeys.document(sourceId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.document(targetId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.documents() })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_move_file'), life: 3000 })
  } finally {
    movingFile.value = false
  }
}

const previewQueue = usePreviewQueue()
const previewObjectUrls = ref<Record<string, string>>({})
const previewCardRefs = ref<Record<string, HTMLElement>>({})
let observer: IntersectionObserver | null = null

function revokeAllObjectUrls() {
  for (const url of Object.values(previewObjectUrls.value)) {
    URL.revokeObjectURL(url)
  }
  previewObjectUrls.value = {}
}

function setPreviewCardRef(fileId: string, el: HTMLElement | null) {
  if (el) {
    previewCardRefs.value[fileId] = el
    observer?.observe(el)
  }
}

function loadPreview(fileId: string, rotation: number | undefined, priority: number) {
  previewQueue
    .enqueue(fileId, 'web', priority, undefined, rotation)
    .then((blob) => {
      if (!blob) return
      // Replace any blob URL already held for this file — the processing-time
      // placeholder the data endpoint served (HTTP 200 for a not-yet-generated
      // raster) when re-enqueued after processing finishes — revoking the stale
      // one first so a re-enqueue never leaks an object URL.
      const prev = previewObjectUrls.value[fileId]
      if (prev) URL.revokeObjectURL(prev)
      previewObjectUrls.value[fileId] = URL.createObjectURL(blob)
    })
}

function setupObserver() {
  observer?.disconnect()
  if (typeof IntersectionObserver === 'undefined') {
    observer = null
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const fileId = (entry.target as HTMLElement).dataset.fileId
        if (!fileId) continue
        if (entry.isIntersecting) {
          previewQueue.reprioritize(fileId, 0)
        }
      }
    },
    { rootMargin: '200px' },
  )
}

function loadAllImagePreviews() {
  previewQueue.cancel()
  revokeAllObjectUrls()
  setupObserver()

  const files = doc.value?.files ?? []
  for (const file of files) {
    if (!file.mimetype.startsWith('image/')) continue
    loadPreview(file.id, effectiveRotation(file), 1)
  }
}

// --- Processing poll -------------------------------------------------------------
// A freshly uploaded (or reprocessed) file has its web/thumb rasters generated
// asynchronously. Until they exist the data endpoint answers with a bundled
// placeholder image at HTTP 200, so the first preview fetch caches the placeholder
// blob and nothing would ever refresh it. While any file is still processing we
// poll /file/list, and when a file flips processing -> done we re-enqueue that
// file's preview so the real raster replaces the cached placeholder. Non-image
// files carry no queued raster preview, so a flip only re-enqueues images.
const processingByFile = new Map<string, boolean>()

const poller = createProcessingPoller(async (isDisposed) => {
  const documentId = doc.value?.id
  if (!documentId) return false

  let items
  try {
    items = await getFileList(documentId)
  } catch {
    // Transient failure — keep polling while local state still says processing.
    return [...processingByFile.values()].some(Boolean)
  }

  // The await above may have resolved after unmount; bail before re-enqueuing.
  if (isDisposed()) return false

  const fileById = new Map((doc.value?.files ?? []).map((f) => [f.id, f]))
  for (const item of items) {
    const wasProcessing = processingByFile.get(item.id) === true
    const nowProcessing = item.processing === true
    processingByFile.set(item.id, nowProcessing)
    if (wasProcessing && !nowProcessing) {
      const file = fileById.get(item.id)
      if (file && isImage(file.mimetype)) {
        // Foreground priority — the user is looking at this document now.
        loadPreview(item.id, effectiveRotation(file), 0)
      }
    }
  }

  return shouldPoll(items)
})

// Seed the per-file processing state from the current document detail (its files
// carry the same live `processing` flag) and start polling if anything is still
// processing. Runs on every file-set change so a new upload re-seeds and re-arms.
function syncProcessing() {
  const files = doc.value?.files ?? []
  processingByFile.clear()
  for (const file of files) {
    processingByFile.set(file.id, (file as { processing?: boolean }).processing === true)
  }
  poller.ensurePolling([...processingByFile.values()].some(Boolean))
}

watch(
  () => doc.value?.id,
  () => {
    nextTick(() => {
      loadAllImagePreviews()
      syncProcessing()
    })
  },
  { immediate: true },
)

// The key is SORTED, so it describes the file SET and each file's rotation — the two things
// that decide which previews have to be (re)fetched — and not their order. Unsorted, a
// reorder (#211) changed the key, and the reload below cancels the queue and revokes every
// object URL: every image in the grid would blank out and refetch after each drag, for an
// order the previews do not depend on.
watch(
  () => doc.value?.files?.map((f) => `${f.id}:${effectiveRotation(f)}`).sort().join(','),
  (next, prev) => {
    if (next !== prev)
      nextTick(() => {
        loadAllImagePreviews()
        syncProcessing()
      })
  },
)

onUnmounted(() => {
  // The armed-card release lives on `document`, so it outlives this component unless removed.
  disarmGridCard()
  poller.dispose()
  previewQueue.cancel()
  revokeAllObjectUrls()
  observer?.disconnect()
  observer = null
  // Retire any in-flight pointer reconciliation: its remaining steps see a stale
  // generation and return without invalidating (an upload started seconds before a
  // navigation would otherwise keep refetching a document nobody is looking at).
  pointerSettleGeneration += 1
})

</script>

<template>
  <div v-if="doc" class="doc-content-view">
    <!-- Description -->
    <div v-if="doc.description" class="doc-description" v-html="sanitizedDescription" />

    <!-- Custom metadata -->
    <div v-if="metadataFields.length" class="doc-metadata">
      <h3 class="doc-metadata-heading">{{ t('ui.metadata.custom_fields') }}</h3>
      <dl class="metadata-list">
        <template v-for="field in metadataFields" :key="field.id">
          <dt class="metadata-name">{{ field.name }}</dt>
          <dd class="metadata-value">{{ formatMetadataValue(field) }}</dd>
        </template>
      </dl>
    </div>

    <!-- Related documents -->
    <div
      v-if="outgoingRelations.length || incomingRelations.length || doc.writable"
      class="doc-relations"
    >
      <div class="doc-relations-header">
        <h3 class="doc-relations-heading">{{ t('ui.relations.title') }}</h3>
        <!-- One control for BOTH groups: the two lists are two halves of one set of linked
             documents, and offering a sort per direction would be two ways to say one thing. -->
        <Select
          v-if="showRelationSort"
          v-model="relationSortKey"
          :options="relationSortOptions"
          optionLabel="label"
          optionValue="value"
          size="small"
          class="relation-sort-select"
          data-testid="relation-sort"
          :aria-label="t('ui.relations.sort_label')"
        />
      </div>

      <!-- Outgoing: this document links to these (removable). -->
      <div v-if="outgoingRelations.length" class="relation-group">
        <p class="relation-group-label">{{ t('ui.relations.links_to') }}</p>
        <div class="relation-list">
          <div v-for="relation in outgoingRelations" :key="relation.id" class="relation-row">
            <i class="pi pi-arrow-right relation-dir-icon" aria-hidden="true" />
            <router-link
              :to="{ name: 'document-view-content', params: { id: relation.id } }"
              class="relation-link"
            >
              {{ relation.title }}
            </router-link>
            <Button
              v-if="doc.writable"
              icon="pi pi-arrow-right-arrow-left"
              text
              rounded
              size="small"
              :loading="savingRelation"
              @click="handleSwapRelation(relation)"
              v-tooltip="t('ui.relations.swap')"
              :aria-label="t('ui.relations.swap')"
            />
            <Button
              v-if="doc.writable"
              icon="pi pi-times"
              text
              rounded
              size="small"
              severity="danger"
              :loading="savingRelation"
              @click="confirmRemoveRelation(relation)"
              v-tooltip="t('ui.relations.remove')"
              :aria-label="t('ui.relations.remove')"
            />
          </div>
        </div>
      </div>

      <!-- Incoming: other documents link here. The relation is owned by the source document, so it
           still has no remove control — but it CAN be reversed from here (#191), which brings it
           onto this document's outgoing list. -->
      <div v-if="incomingRelations.length" class="relation-group">
        <p class="relation-group-label">{{ t('ui.relations.linked_from') }}</p>
        <div class="relation-list">
          <div v-for="relation in incomingRelations" :key="relation.id" class="relation-row">
            <i class="pi pi-arrow-left relation-dir-icon" aria-hidden="true" />
            <router-link
              :to="{ name: 'document-view-content', params: { id: relation.id } }"
              class="relation-link"
              v-tooltip="t('ui.relations.remove_from_source', { title: relation.title })"
            >
              {{ relation.title }}
            </router-link>
            <Button
              v-if="doc.writable"
              icon="pi pi-arrow-right-arrow-left"
              text
              rounded
              size="small"
              :loading="savingRelation"
              @click="handleSwapRelation(relation)"
              v-tooltip="t('ui.relations.swap')"
              :aria-label="t('ui.relations.swap')"
            />
          </div>
        </div>
      </div>

      <!-- Add an outgoing relation. Writable-only. -->
      <div v-if="doc.writable" class="relation-add">
        <AutoComplete
          v-model="selectedRelationTarget"
          :suggestions="relationSearchResults"
          optionLabel="title"
          forceSelection
          size="small"
          class="relation-add-autocomplete"
          :placeholder="t('ui.relations.search_placeholder')"
          @complete="completeRelationSearch"
        >
          <template #option="{ option }">
            <div class="relation-search-result">
              <i class="pi pi-file" aria-hidden="true" />
              <span>{{ option.title }}</span>
            </div>
          </template>
        </AutoComplete>
        <Button
          :label="t('add')"
          icon="pi pi-plus"
          size="small"
          :disabled="!selectedRelationTarget"
          :loading="savingRelation"
          @click="handleAddRelation"
        />
      </div>
    </div>

    <!-- File view: one section with a grid⇄list toggle (grid default, per-user). -->
    <div v-if="doc.files?.length" class="file-panel">
      <div class="file-panel-header">
        <h3>{{ t('ui.files_count', { count: doc.files.length }) }}</h3>
        <!-- Grid-only transient sort (#211). The LIST carries its own sort in its column
             headers, so offering a second control for it here would be two ways to set one
             thing; the grid has no headers to click, hence a compact Select. It is a control
             rather than a gesture, so it is the sort affordance that works on a phone, where
             the drag handle is the awkward one. -->
        <Select
          v-if="fileViewMode === 'grid' && doc.files.length > 1"
          v-model="gridSortKey"
          :options="gridSortOptions"
          optionLabel="label"
          optionValue="value"
          size="small"
          class="grid-sort-select"
          data-testid="grid-sort"
          :aria-label="t('ui.file_view.sort_label')"
        />
        <SelectButton
          :model-value="fileViewMode"
          :options="fileViewOptions"
          optionLabel="label"
          optionValue="value"
          dataKey="value"
          :allowEmpty="false"
          :aria-label="t('ui.file_view.toggle_label')"
          class="file-view-toggle"
          @update:model-value="(v: FileViewMode) => { if (v) fileViewMode = v }"
        >
          <template #option="{ option }">
            <i :class="option.icon" aria-hidden="true" />
            <span class="file-view-label">{{ option.label }}</span>
          </template>
        </SelectButton>
      </div>

      <!-- GRID: rich previews. Images keep their persisted-rotation controls and the
           PDF viewer is unchanged (preview DOM untouched); every other type gets an icon
           card with an open link so nothing is hidden in the default view. Each tile also
           carries the shared FileActionMenu, so the per-file action menu (and the
           #file-extra mount point) is present in BOTH views.

           Tiles render `gridDisplayFiles` — the local optimistic order (#211), projected
           through the transient sort when one is active — not `doc.files` directly, so a drag
           reorders immediately and a rejected persist can be rolled back here. With no sort the
           projection IS the manual array, which is what keeps `index` below a position in the
           order the drop persists. The click listener is on the CONTAINER and in the CAPTURE phase
           because it has to swallow the post-drop click before the tile control under the
           pointer (preview, rotation, action menu) ever sees it. -->
      <div
        v-if="fileViewMode === 'grid'"
        class="file-preview-grid"
        @click.capture="onGridClickCapture"
      >
        <template v-for="(file, index) in gridDisplayFiles" :key="file.id">
          <div
            v-if="isImage(file.mimetype)"
            class="file-preview-card"
            :class="{ 'file-card-drag-over': gridDragOverId === file.id }"
            :data-file-id="file.id"
            :ref="(el: any) => setPreviewCardRef(file.id, el as HTMLElement | null)"
            @mousedown="onGridCardMouseDown"
            @mouseup="onGridCardDisarm"
            @mouseleave="onGridCardDisarm"
            @dragstart="onGridDragStart(index, $event)"
            @dragover="onGridDragOver(file.id, $event)"
            @dragleave="onGridDragLeave(file.id)"
            @drop.prevent="onGridDrop(index)"
            @dragend="onGridDragEnd"
          >
            <!-- The image and its rotation controls share ONE fixed-height media band (#283) so the
                 filename + action row below sit at the SAME offset as the PDF and generic cards.
                 Before, the controls added their own row on top of the stage, dropping an image
                 card's title/buttons below a neighbouring PDF or icon card's. -->
            <div class="image-preview-media">
              <!-- The stage IS the open control (#235), exactly as the generic card's icon stage
                   is: a real <button> routing to the same `openPreview`, so the picture a user
                   clicks behaves like the icon they can already click — and the keyboard reaches
                   it too. It wraps ONLY the stage, never the rotation controls below, so those
                   stay ordinary buttons rather than nested-in-a-button.
                   `draggable="false"` is load-bearing, not defensive: an <img> is a native drag
                   source, so a press that travelled even a few pixels started an image drag and
                   the browser delivered NO click at all — the same swallow the gallery card was
                   fixed for in 126ea8e8. -->
              <button
                type="button"
                class="image-preview-stage media-open"
                :aria-label="t('ui.file_view.open_file', { name: displayName(file.name, t) })"
                @click="openPreview(file)"
              >
                <img
                  v-if="previewObjectUrls[file.id]"
                  :src="previewObjectUrls[file.id]"
                  :alt="displayName(file.name, t)"
                  class="rotatable-image"
                  draggable="false"
                />
                <i v-else class="pi pi-spin pi-spinner preview-loading-spinner" aria-hidden="true" />
              </button>
              <div v-if="doc.writable" class="image-preview-controls">
                <Button
                  icon="pi pi-replay"
                  text
                  rounded
                  size="small"
                  severity="secondary"
                  :disabled="rotating[file.id]"
                  @click="rotateImageLeft(file)"
                  :aria-label="t('ui.rotate_left')"
                />
                <Button
                  icon="pi pi-refresh"
                  text
                  rounded
                  size="small"
                  severity="secondary"
                  :disabled="rotating[file.id]"
                  @click="rotateImageRight(file)"
                  :aria-label="t('ui.rotate_right')"
                />
              </div>
            </div>
            <div class="file-preview-label" :title="displayName(file.name, t)">{{ displayName(file.name, t) }}</div>
            <div class="file-card-actions">
              <!-- The ONLY drag origin on a tile (#211): a mousedown here is what arms the CARD
                   as a drag source (onGridCardMouseDown), so everything else on the card stays
                   an ordinary control. Withdrawn while this tile is being renamed so the editor
                   keeps the row to itself. -->
              <span
                v-if="gridReorderEnabled && gridRenamingId !== file.id"
                class="file-card-drag-handle"
                :title="t('ui.file_view.reorder_handle')"
              >
                <i class="pi pi-bars" aria-hidden="true" />
              </span>
              <InputText
                v-if="gridRenamingId === file.id"
                v-model="gridRenameValue"
                class="grid-rename-input"
                size="small"
                autofocus
                @keyup.enter="commitGridRename(file.id)"
                @keyup.escape="cancelGridRename"
                @blur="commitGridRename(file.id)"
              />
              <FileActionMenu
                v-else
                :file="file"
                :writable="doc.writable"
                :document-id="doc.id"
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
                @preview="openPreview"
                @rename="startGridRename"
                @delete="confirmDelete"
                @set-cover="setCoverFor"
                @clear-cover="clearCoverFor"
                @move="openMoveDialog"
              >
                <template #extra="s">
                  <slot name="file-extra" v-bind="s"><FileExtraActions v-bind="s" /></slot>
                </template>
              </FileActionMenu>
            </div>
          </div>
          <div
            v-else-if="file.mimetype === 'application/pdf'"
            class="file-preview-card"
            :class="{ 'file-card-drag-over': gridDragOverId === file.id }"
            @mousedown="onGridCardMouseDown"
            @mouseup="onGridCardDisarm"
            @mouseleave="onGridCardDisarm"
            @dragstart="onGridDragStart(index, $event)"
            @dragover="onGridDragOver(file.id, $event)"
            @dragleave="onGridDragLeave(file.id)"
            @drop.prevent="onGridDrop(index)"
            @dragend="onGridDragEnd"
          >
            <!-- `downloadable=false`: the tile's own action menu now carries the explicit
                 Download (#178), so the viewer's built-in one would be a second, unlabelled
                 control on the same card — the duplicate #181 removed from the dialog.
                 The viewer is boxed to the shared media height (#283) so the filename + action
                 row aligns with the image and generic cards; the page scrolls inside the box and
                 the page-nav stays pinned at its foot (styles below). -->
            <div class="pdf-preview-media">
              <!-- `openable` (#235): the PAGE AREA opens the preview, the image stage and the
                   generic icon stage do. It is a viewer prop rather than a click handler on this
                   wrapper because the page area and the nav bar are siblings INSIDE the viewer —
                   binding it there is what makes "page-nav and rotation are never hijacked" a
                   structural fact instead of a target test this file would have to keep right. -->
              <PdfViewer
                :src="getFileUrl(file.id)"
                :initial-rotation="file.rotation ?? 0"
                :persistable="doc.writable"
                :downloadable="false"
                openable
                :open-label="t('ui.file_view.open_file', { name: displayName(file.name, t) })"
                @rotate="(deg: number) => persistRotation(file, deg)"
                @open="openPreview(file)"
              />
            </div>
            <div class="file-preview-label" :title="displayName(file.name, t)">{{ displayName(file.name, t) }}</div>
            <div class="file-card-actions">
              <span
                v-if="gridReorderEnabled && gridRenamingId !== file.id"
                class="file-card-drag-handle"
                :title="t('ui.file_view.reorder_handle')"
              >
                <i class="pi pi-bars" aria-hidden="true" />
              </span>
              <InputText
                v-if="gridRenamingId === file.id"
                v-model="gridRenameValue"
                class="grid-rename-input"
                size="small"
                autofocus
                @keyup.enter="commitGridRename(file.id)"
                @keyup.escape="cancelGridRename"
                @blur="commitGridRename(file.id)"
              />
              <FileActionMenu
                v-else
                :file="file"
                :writable="doc.writable"
                :document-id="doc.id"
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
                @preview="openPreview"
                @rename="startGridRename"
                @delete="confirmDelete"
                @set-cover="setCoverFor"
                @clear-cover="clearCoverFor"
                @move="openMoveDialog"
              >
                <template #extra="s">
                  <slot name="file-extra" v-bind="s"><FileExtraActions v-bind="s" /></slot>
                </template>
              </FileActionMenu>
            </div>
          </div>
          <div
            v-else
            class="file-preview-card file-preview-generic"
            :class="{ 'file-card-drag-over': gridDragOverId === file.id }"
            @mousedown="onGridCardMouseDown"
            @mouseup="onGridCardDisarm"
            @mouseleave="onGridCardDisarm"
            @dragstart="onGridDragStart(index, $event)"
            @dragover="onGridDragOver(file.id, $event)"
            @dragleave="onGridDragLeave(file.id)"
            @drop.prevent="onGridDrop(index)"
            @dragend="onGridDragEnd"
          >
            <!-- The icon stage AND the filename label are one keyboard-focusable button
                 that opens the in-app preview — NOT a link to the original file URL, which
                 the backend serves as a download (#144). The action buttons below are
                 separate, non-navigating targets. -->
            <button
              type="button"
              class="generic-open"
              :aria-label="t('ui.file_view.open_file', { name: displayName(file.name, t) })"
              @click="openPreview(file)"
            >
              <div class="generic-preview-stage">
                <i :class="fileIcon(file.mimetype)" aria-hidden="true" />
              </div>
              <div class="file-preview-label" :title="displayName(file.name, t)">{{ displayName(file.name, t) }}</div>
            </button>
            <div class="file-card-actions">
              <span
                v-if="gridReorderEnabled && gridRenamingId !== file.id"
                class="file-card-drag-handle"
                :title="t('ui.file_view.reorder_handle')"
              >
                <i class="pi pi-bars" aria-hidden="true" />
              </span>
              <InputText
                v-if="gridRenamingId === file.id"
                v-model="gridRenameValue"
                class="grid-rename-input"
                size="small"
                autofocus
                @keyup.enter="commitGridRename(file.id)"
                @keyup.escape="cancelGridRename"
                @blur="commitGridRename(file.id)"
              />
              <FileActionMenu
                v-else
                :file="file"
                :writable="doc.writable"
                :document-id="doc.id"
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
                @preview="openPreview"
                @rename="startGridRename"
                @delete="confirmDelete"
                @set-cover="setCoverFor"
                @clear-cover="clearCoverFor"
                @move="openMoveDialog"
              >
                <template #extra="s">
                  <slot name="file-extra" v-bind="s"><FileExtraActions v-bind="s" /></slot>
                </template>
              </FileActionMenu>
            </div>
          </div>
        </template>
      </div>

      <!-- LIST: enriched DataTable (optional columns, quick filter, inline rename,
           drag-handle reorder). Like the grid it flows with the page — it has no inner
           scroll container and no windowing at any length (#196). The same #file-extra
           mount point is forwarded into each row's action menu. -->
      <FileListTable
        v-else
        ref="fileListRef"
        :files="doc.files"
        :writable="doc.writable"
        :document-id="doc.id"
        :cover-file-id="doc.file_id_cover"
        @open="openPreview"
        @rename="renameFileTo"
        @delete="confirmDelete"
        @versions="showVersions"
        @reorder="onReorderFiles"
        @set-cover="setCoverFor"
        @clear-cover="clearCoverFor"
        @move="openMoveDialog"
      >
        <template #file-extra="s">
          <slot name="file-extra" v-bind="s"><FileExtraActions v-bind="s" /></slot>
        </template>
      </FileListTable>
    </div>

    <!-- Upload + camera: write-only. A read-only viewer (share ACL / READ grant) must
         see no add-file affordance. -->
    <template v-if="doc.writable">
      <!-- Upload zone -->
      <FileUpload
        ref="fileUploadRef"
        mode="advanced"
        :chooseLabel="t('ui.choose')"
        multiple
        customUpload
        auto
        :showUploadButton="false"
        :showCancelButton="false"
        :disabled="busy"
        @uploader="handleUpload"
        class="view-file-upload"
      >
        <template #empty>
          <div class="file-upload-empty">
            <i class="pi pi-cloud-upload" aria-hidden="true" />
            <span v-if="uploading">{{ t('ui.uploading') }}</span>
            <span v-else>{{ t('ui.drag_or_choose_upload') }}</span>
          </div>
        </template>
      </FileUpload>

      <!-- Camera capture: opens the device camera on mobile; photos upload at once. -->
      <CameraCaptureButton :disabled="busy" @capture="onCameraCapture" />

      <!-- Real per-file upload progress. -->
      <UploadProgressList v-if="uploading" :names="uploadingNames" :progress="uploadProgress" />
    </template>

    <EmptyState
      v-if="!doc.files?.length"
      icon="pi pi-file"
      :message="t('ui.no_files')"
      :action-label="doc.writable ? t('ui.edit_to_add_files') : undefined"
      @action="$router.push({ name: 'document-edit', params: { id: doc.id } })"
    />

    <FileVersionsDialog
      v-model:visible="versionsDialogVisible"
      :file-id="versionsFileId"
      :file-name="versionsFileName"
      :writable="doc.writable"
    />

    <!-- Upload-bar name-conflict prompt (#117.2). -->
    <FileConflictDialog
      v-model:visible="conflictDialogVisible"
      :file-name="conflictFileName"
      :remaining="conflictRemaining"
      @decide="onConflictDecision"
    />

    <!-- Safe in-app file preview (#144). -->
    <FilePreviewDialog v-model:visible="previewVisible" :file="previewFile" />

    <!-- Move a file to another document (#175). The picker searches all documents; the server enforces
         WRITE on the destination, so no client-side pre-filter narrows the results. -->
    <Dialog
      v-model:visible="moveDialogVisible"
      modal
      :header="t('ui.move_file')"
      :style="{ width: '30rem' }"
    >
      <AutoComplete
        v-model="moveTarget"
        :suggestions="moveSearchResults"
        optionLabel="title"
        forceSelection
        fluid
        size="small"
        :placeholder="t('ui.move_search_placeholder')"
        @complete="completeMoveSearch"
      >
        <template #option="{ option }">
          <div class="relation-search-result">
            <i class="pi pi-file" aria-hidden="true" />
            <span>{{ option.title }}</span>
          </div>
        </template>
      </AutoComplete>
      <template #footer>
        <Button :label="t('cancel')" text @click="moveDialogVisible = false" />
        <Button
          :label="t('ui.move_confirm')"
          icon="pi pi-arrow-right"
          :disabled="!moveTarget"
          :loading="movingFile"
          @click="confirmMove"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.doc-description {
  margin: 0 0 1.5rem;
  color: var(--p-text-color);
  line-height: 1.6;
}
/* Read-only description prose: sanitized to plain <ol>/<ul>; pin native markers
   and indent so lists show numbers / bullets exactly once, never clipped (#70). */
.doc-description :deep(ol) { list-style: decimal outside; padding-left: 1.5em; }
.doc-description :deep(ul) { list-style: disc outside; padding-left: 1.5em; }

.doc-metadata {
  margin: 0 0 1.5rem;
}
.doc-metadata-heading {
  margin: 0 0 0.625rem;
  font-size: 1rem;
  font-weight: 600;
}
.metadata-list {
  display: grid;
  grid-template-columns: minmax(120px, max-content) 1fr;
  gap: 0.375rem 1rem;
  margin: 0;
}
.metadata-name {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-muted-color);
}
.metadata-value {
  font-size: 0.875rem;
  color: var(--p-text-color);
  margin: 0;
  word-break: break-word;
}

.doc-relations {
  margin: 0 0 1.5rem;
}
/* Mirrors .file-panel-header: heading left, compact control right, wrapping onto its own line
   on a narrow viewport rather than squeezing the title. With no control rendered the row is the
   heading alone, so its 0.625rem bottom margin is kept HERE and the flex row adds nothing. */
.doc-relations-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}
.doc-relations-heading {
  margin: 0 auto 0.625rem 0;
  font-size: 1rem;
  font-weight: 600;
}
.relation-sort-select {
  margin-bottom: 0.625rem;
  max-width: 13rem;
}
.relation-group {
  margin-bottom: 0.75rem;
}
.relation-group-label {
  margin: 0 0 0.375rem;
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--p-text-muted-color);
  text-transform: uppercase;
  letter-spacing: 0.02em;
}
.relation-list {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
}
.relation-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
}
.relation-row:last-child {
  border-bottom: none;
}
.relation-dir-icon {
  color: var(--p-text-muted-color);
  font-size: 0.8rem;
  flex-shrink: 0;
}
.relation-link {
  flex: 1;
  min-width: 0;
  font-size: 0.875rem;
  color: var(--p-text-color);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.relation-link:hover {
  color: var(--teedy-brand);
  text-decoration: underline;
}
.relation-add {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
  margin-top: 0.5rem;
}
.relation-add-autocomplete {
  flex: 1;
}
.relation-search-result {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  font-size: 0.875rem;
}

.file-preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
  /* One media height shared by EVERY card type's preview (image band, PDF box, generic icon
     stage), so the filename + action row lands at the same offset on every card and adjacent
     cards line up regardless of file type (#283). */
  --file-card-media-height: 400px;
}

.file-preview-card {
  overflow: hidden;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
}
/* Every card type opens now (#235), so the primary-border hover the GENERIC card has carried
   since #144 belongs to all three: a card that opens has to read as one, and a grid of mixed
   file types must not answer the pointer in two different colours depending on the type. */
.file-preview-card:hover {
  border-color: var(--p-primary-color);
}
/* The image card's media band: the stage AND the rotation controls together fill one
   shared-height box (#283). The controls therefore no longer stack ON TOP of the stage and push
   the filename down — they sit inside the band, so an image card's title + actions align with the
   PDF and generic cards beside it. */
.image-preview-media {
  height: var(--file-card-media-height);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* Rotation stage: fills the band and centers the image. The image itself is physically rotated
   server-side (the served _web raster), so the stage only needs to fit-contain it — no CSS
   transform, no sideways sizing. */
.image-preview-stage {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--p-content-hover-background);
  /* The stage is a <button> since #235. Every declaration below strips UA button chrome that
     would otherwise reach the media band's geometry and break the shared card height (#283). */
  width: 100%;
  min-width: 0;
  padding: 0;
  border: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.rotatable-image {
  display: block;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.image-preview-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  padding: 0.25rem;
  border-top: 1px solid var(--p-content-border-color);
  background: var(--p-content-background);
}

.file-preview-label {
  padding: 0.375rem 0.625rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  border-top: 1px solid var(--p-content-border-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-panel {
  margin-top: 1rem;
}
/* The header carries the heading plus TWO controls in grid mode (the transient sort and the
   grid⇄list toggle, #211). `space-between` alone would strand the sort in the middle of the
   row, so the heading claims the slack instead and the controls stay a pair on the right —
   and they wrap onto their own line rather than squeezing on a phone. */
.file-panel-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.file-panel-header h3 {
  margin: 0 auto 0 0;
  font-size: 1rem;
  font-weight: 600;
}
.grid-sort-select {
  max-width: 13rem;
}
.file-view-label {
  margin-left: 0.35rem;
}

/* Generic (non-image, non-PDF) grid card: a large type icon + the file name. The stage
   is an open link; the action menu (with rename/delete/versions) sits below. */
.file-preview-generic {
  display: flex;
  flex-direction: column;
}
.generic-open {
  display: block;
  width: 100%;
  text-align: inherit;
  text-decoration: none;
  color: inherit;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  font: inherit;
}
.generic-preview-stage {
  height: var(--file-card-media-height);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--p-content-hover-background);
  color: var(--p-text-muted-color);
  font-size: 3rem;
}

/* The grid PDF preview is boxed to the SAME shared media height (#283) so its filename + action
   row aligns with the image and generic cards. The viewer becomes a column INSIDE the box: the
   page column scrolls within the media area while the page-nav bar stays pinned at its foot. This
   :deep is scoped to the grid card's viewer only — the fullscreen preview is a SEPARATE PdfViewer
   instance (FilePreviewDialog, continuous mode) and is untouched. */
.pdf-preview-media {
  height: var(--file-card-media-height);
  display: flex;
  overflow: hidden;
}
.pdf-preview-media :deep(.pdf-viewer) {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.pdf-preview-media :deep(.pdf-canvas-container) {
  flex: 1;
  min-height: 0;
  overflow: auto;
  /* Top-align the width-fitted page so a page taller than the box scrolls from its top edge
     (centering a taller-than-container flex item hides its top behind an unreachable scroll). */
  align-items: flex-start;
}

/* Per-tile action row: hosts the shared FileActionMenu (or the grid inline-rename
   editor). Sits under the label so it never overlaps the preview/rotation controls. */
.file-card-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.25rem;
  padding: 0.125rem 0.375rem;
  border-top: 1px solid var(--p-content-border-color);
  min-height: 2.25rem;
}
/* The cluster WRAPS inside the card, at every viewport (#192).
   A grid tile is `minmax(280px, 1fr)` inside a 960px-capped page, so a tile is ~336px wide
   on a 360px phone and no wider on a large screen — while the worst-case writable PDF
   cluster is TEN 36px controls. They cannot share one line, and because this is a flex row
   the consequence is not an overflow some other rule would catch: the buttons SHRINK.
   Measured against a deliberately unwrapped build at 360px: 30px per control instead of 36,
   so every control in the grid becomes 17% smaller than the identical control in the list,
   and the squeeze deepens with each control added until it clips outright.
   Wrapping has to be set on `.file-action-menu` itself, not on this row: the menu is ONE
   inline-flex child here, so a `flex-wrap` on the row alone would never break the icons.
   The card has no fixed height, so it simply grows by a line.
   e2e/file-list-geometry.spec.ts measures the painted control WIDTH at 360px and 393px. */
.file-card-actions :deep(.file-action-menu) {
  flex-wrap: wrap;
  justify-content: flex-end;
  row-gap: 0.125rem;
  min-width: 0;
}
/* Drag handle (#211). It sits at the LEFT end of the action row (`margin-right: auto`
   against the row's flex-end justification) so the action cluster keeps the right end it has
   always had, and it is a plain span rather than a button: it is a pointer-only affordance
   (native HTML5 drag), exactly like the list's PrimeVue reorder handle, and a focusable
   control that no key can operate would be worse than none. It is deliberately NOT counted
   by e2e/file-list-geometry.spec.ts, which measures the `button, a` controls of the cluster. */
.file-card-drag-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  align-self: stretch;
  margin-right: auto;
  padding: 0 0.25rem;
  min-width: 1.5rem;
  color: var(--p-text-muted-color);
  cursor: grab;
}
.file-card-drag-handle:active {
  cursor: grabbing;
}
/* The drop target under the pointer. Inset outline rather than a border: the card clips its
   overflow and has a fixed-height preview stage, so a border would shift the whole tile. */
.file-card-drag-over {
  outline: 2px dashed var(--p-primary-color);
  outline-offset: -2px;
}

.grid-rename-input {
  width: 100%;
  font-size: 0.8125rem;
}

.view-file-upload {
  margin-top: 1rem;
}

.file-upload-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.75rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.file-upload-empty i {
  font-size: 1.25rem;
}
.preview-loading-spinner {
  font-size: 2rem;
  color: var(--p-text-muted-color);
}
</style>
