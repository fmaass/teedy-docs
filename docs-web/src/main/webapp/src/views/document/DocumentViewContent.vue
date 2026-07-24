<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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
  type DocumentListItem,
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
import { formatDate } from '../../utils/formatters'
import { injectDocument } from './documentKey'

const doc = injectDocument()
const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()
const authStore = useAuthStore()

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
const outgoingRelations = computed(() =>
  (doc.value?.relations ?? []).filter((r) => r.source),
)
const incomingRelations = computed(() =>
  (doc.value?.relations ?? []).filter((r) => !r.source),
)

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
    // Invalidate unconditionally: a mid-batch failure still uploaded earlier files,
    // and skipping the refetch would leave them invisible (users re-upload dupes).
    queryClient.invalidateQueries({ queryKey: ['document', documentId] })
    uploading.value = false
    uploadProgress.value = {}
    uploadingNames.value = []
    fileUploadRef.value?.clear()
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

// Reference to the list so a failed reorder can be rolled back deterministically at the
// component that owns the optimistic order (present only while the list view is mounted).
const fileListRef = ref<InstanceType<typeof FileListTable> | null>(null)

// Persist an explicit drag reorder (the only order-persisting action) via the existing
// reorder endpoint. On success the refetch re-seeds the list from the authoritative
// order (so it survives reload); on failure the list rolls back its optimistic order to
// the last saved sequence and shows the not-saved indicator — never a false "saved".
async function onReorderFiles(orderedIds: string[]) {
  const documentId = doc.value?.id
  if (!documentId) return
  try {
    await reorderFiles(documentId, orderedIds)
    toast.add({ severity: 'success', summary: t('ui.file_view.reorder_saved'), life: 2000 })
    // Release the list's in-flight lock so the drag re-enables promptly (before the
    // refetch settles); the optimistic order already equals the persisted one.
    fileListRef.value?.confirmReorder()
    queryClient.invalidateQueries({ queryKey: ['document', documentId] })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.file_view.reorder_failed'), life: 3000 })
    // Deterministic local rollback independent of the refetch (which may also fail).
    fileListRef.value?.rollbackReorder()
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

function openMoveDialog(file: FileActionTarget) {
  fileToMove.value = file
  moveTarget.value = null
  moveSearchResults.value = []
  moveDialogVisible.value = true
}

async function completeMoveSearch(event: { query: string }) {
  const query = event.query.trim()
  if (!query || !doc.value) {
    moveSearchResults.value = []
    return
  }
  try {
    const { data } = await listDocuments({ search: query, limit: 10 })
    // Exclude the current document — moving to the same document is rejected by the backend.
    moveSearchResults.value = data.documents.filter((d) => d.id !== doc.value!.id)
  } catch {
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
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.document(sourceId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.document(targetId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.documents() }),
    ])
    moveDialogVisible.value = false
    toast.add({ severity: 'success', summary: t('ui.file_moved'), life: 2000 })
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

watch(
  () => doc.value?.files?.map((f) => `${f.id}:${effectiveRotation(f)}`).join(','),
  (next, prev) => {
    if (next !== prev)
      nextTick(() => {
        loadAllImagePreviews()
        syncProcessing()
      })
  },
)

onUnmounted(() => {
  poller.dispose()
  previewQueue.cancel()
  revokeAllObjectUrls()
  observer?.disconnect()
  observer = null
})

</script>

<template>
  <div v-if="doc">
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
      <h3 class="doc-relations-heading">{{ t('ui.relations.title') }}</h3>

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

      <!-- Incoming: other documents link here. Read-only — no remove control; the relation
           is owned by the source document and must be removed from there. -->
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
           #file-extra mount point) is present in BOTH views. -->
      <div v-if="fileViewMode === 'grid'" class="file-preview-grid">
        <template v-for="file in doc.files" :key="file.id">
          <div
            v-if="isImage(file.mimetype)"
            class="file-preview-card"
            :data-file-id="file.id"
            :ref="(el: any) => setPreviewCardRef(file.id, el as HTMLElement | null)"
          >
            <div class="image-preview-stage">
              <img
                v-if="previewObjectUrls[file.id]"
                :src="previewObjectUrls[file.id]"
                :alt="displayName(file.name, t)"
                class="rotatable-image"
              />
              <i v-else class="pi pi-spin pi-spinner preview-loading-spinner" aria-hidden="true" />
            </div>
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
            <div class="file-preview-label">{{ displayName(file.name, t) }}</div>
            <div class="file-card-actions">
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
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
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
          <div v-else-if="file.mimetype === 'application/pdf'" class="file-preview-card">
            <PdfViewer
              :src="getFileUrl(file.id)"
              :initial-rotation="file.rotation ?? 0"
              :persistable="doc.writable"
              @rotate="(deg: number) => persistRotation(file, deg)"
            />
            <div class="file-preview-label">{{ displayName(file.name, t) }}</div>
            <div class="file-card-actions">
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
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
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
          <div v-else class="file-preview-card file-preview-generic">
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
              <div class="file-preview-label">{{ displayName(file.name, t) }}</div>
            </button>
            <div class="file-card-actions">
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
                :is-cover="doc.file_id_cover === file.id"
                @versions="showVersions"
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
           drag-handle reorder, list virtualization). The same #file-extra mount point is
           forwarded into each row's action menu. -->
      <FileListTable
        v-else
        ref="fileListRef"
        :files="doc.files"
        :writable="doc.writable"
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
.doc-relations-heading {
  margin: 0 0 0.625rem;
  font-size: 1rem;
  font-weight: 600;
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
}

.file-preview-card {
  overflow: hidden;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
}
/* Rotation stage: a fixed-height box that centers the image. The image itself is
   physically rotated server-side (the served _web raster), so the stage only needs
   to fit-contain it — no CSS transform, no sideways sizing. */
.image-preview-stage {
  height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--p-content-hover-background);
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
.file-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.file-panel-header h3 {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
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
  height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--p-content-hover-background);
  color: var(--p-text-muted-color);
  font-size: 3rem;
}
.file-preview-generic:hover {
  border-color: var(--p-primary-color);
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
