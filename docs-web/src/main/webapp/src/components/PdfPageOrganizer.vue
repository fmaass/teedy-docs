<script setup lang="ts">
import { ref, shallowRef, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from 'primevue/usetoast'
import { useQueryClient } from '@tanstack/vue-query'
import type { PDFDocumentProxy } from 'pdfjs-dist'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'
import PdfPageThumbnail from './PdfPageThumbnail.vue'
import { getFileUrl, getFileVersions, applyPageOperations, type PageManifest } from '../api/file'
import { createGeneration } from '../utils/staleGuard'

// The #73 PDF page organizer, mounted per PDF file row from FileExtraActions (writable-only).
// It renders every page of the CURRENT pdf client-side (pdf.js — no server rasters), lets the
// user reorder (drag OR keyboard move buttons), rotate and delete pages, then saves the result
// as a NEW file version by posting the Phase-1 v1 manifest with the expected base version. The
// original is preserved as a prior version; a stale base, an over-ceiling/signed/encrypted/empty
// result, or a saturated concurrency limit are surfaced distinctly and keep the dialog open.

const props = defineProps<{
  fileId: string
  fileName?: string
}>()

const { t } = useI18n()
const toast = useToast()
const queryClient = useQueryClient()

// pdf.js is a large dependency AND touches DOMMatrix at module load, so it is imported
// lazily the first time the organizer actually opens: this keeps it out of the initial
// bundle and out of every consumer that merely imports this component (e.g. the file panel
// under test) until a PDF is genuinely being edited.
let pdfjsModule: typeof import('pdfjs-dist') | null = null
let workerConfigured = false
async function loadPdfjs() {
  if (!pdfjsModule) pdfjsModule = await import('pdfjs-dist')
  if (!workerConfigured) {
    pdfjsModule.GlobalWorkerOptions.workerSrc = new URL(
      'pdfjs-dist/build/pdf.worker.min.mjs',
      import.meta.url,
    ).toString()
    workerConfigured = true
  }
  return pdfjsModule
}

interface PageItem {
  // Stable identity for the keyed v-for so a reorder MOVES the card (keeping its rendered
  // canvas) rather than re-rendering it. Never sent to the backend.
  key: string
  // 0-based index of this page in the current pdf (the manifest `source`).
  source: number
  // ABSOLUTE clockwise orientation (0/90/180/270), seeded from the page's intrinsic rotation and
  // adjusted by the rotate controls. This exact value is previewed AND posted, so what the user
  // sees is what the backend's absolute setRotation produces.
  rotate: number
}

let keySeq = 0
const nextKey = () => `p${keySeq++}`

const open = ref(false)
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const errorMessage = ref<string | null>(null)
const baseVersion = ref<number | null>(null)
const pages = ref<PageItem[]>([])
// Snapshot of the order+rotation at load, for the dirty check and Reset.
const initialOrder = ref<Array<{ source: number; rotate: number }>>([])
// The loaded document, shared by every thumbnail. shallowRef: a PDFDocumentProxy is a large
// non-plain object that must not be made deeply reactive.
const pdfDoc = shallowRef<PDFDocumentProxy | null>(null)
const listRef = ref<HTMLElement>()
let dragIndex: number | null = null

const gen = createGeneration()

function destroyDoc() {
  if (pdfDoc.value) {
    pdfDoc.value.destroy()
    pdfDoc.value = null
  }
}

async function load() {
  const myGen = gen.next()
  loading.value = true
  loadError.value = false
  errorMessage.value = null
  pages.value = []
  destroyDoc()
  try {
    // Base version and page bytes both come from THIS file id, so they are always consistent
    // (a new version is a new id): the version read here is the optimistic-concurrency base.
    const versions = await getFileVersions(props.fileId)
    if (!gen.isCurrent(myGen)) return
    const current = versions.find((v) => v.id === props.fileId)
    if (!current) {
      loadError.value = true
      loading.value = false
      return
    }
    baseVersion.value = current.version

    const pdfjs = await loadPdfjs()
    if (!gen.isCurrent(myGen)) return
    const doc = await pdfjs.getDocument(getFileUrl(props.fileId)).promise
    if (!gen.isCurrent(myGen)) {
      doc.destroy()
      return
    }
    // Seed each page's rotation from its INTRINSIC pdf.js orientation (an absolute angle) so the
    // organizer works in one absolute space end to end: the preview, the dirty check and the posted
    // manifest all use this value. getPage only parses the page dict (no rasterization), so reading
    // them up front is cheap relative to the lazy thumbnail render.
    const proxies = await Promise.all(
      Array.from({ length: doc.numPages }, (_, i) => doc.getPage(i + 1)),
    )
    if (!gen.isCurrent(myGen)) {
      doc.destroy()
      return
    }
    pdfDoc.value = doc
    const items: PageItem[] = proxies.map((page, i) => ({
      key: nextKey(),
      source: i,
      rotate: ((page.rotate % 360) + 360) % 360,
    }))
    pages.value = items
    initialOrder.value = items.map((p) => ({ source: p.source, rotate: p.rotate }))
    loading.value = false
  } catch {
    if (!gen.isCurrent(myGen)) return
    loadError.value = true
    loading.value = false
  }
}

watch(open, (isOpen) => {
  if (isOpen) load()
  else {
    // Free the document and abandon any in-flight load when the dialog closes.
    gen.next()
    destroyDoc()
    pages.value = []
    errorMessage.value = null
  }
})

onBeforeUnmount(() => {
  gen.next()
  destroyDoc()
})

const dirty = computed(() => {
  const init = initialOrder.value
  if (pages.value.length !== init.length) return true
  return pages.value.some((p, i) => p.source !== init[i].source || p.rotate !== init[i].rotate)
})

const canSave = computed(
  () => !loading.value && !loadError.value && !saving.value && pages.value.length > 0 && dirty.value,
)

// --- Reorder / rotate / delete ------------------------------------------------
function move(from: number, to: number) {
  if (to < 0 || to >= pages.value.length || from === to) return
  const next = pages.value.slice()
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  pages.value = next
}

// After a keyboard-driven move the card is a NEW DOM position; refocus its move control so a
// keyboard user can keep nudging the same page (falling back to the opposite direction when the
// page has reached an end and the preferred button is now disabled).
async function focusMoveButton(key: string, preferred: 'backward' | 'forward') {
  await nextTick()
  const container = listRef.value
  if (!container) return
  const card = container.querySelector<HTMLElement>(`[data-page-key="${key}"]`)
  if (!card) return
  const primary = card.querySelector<HTMLButtonElement>(`[data-dir="${preferred}"]`)
  const other = preferred === 'backward' ? 'forward' : 'backward'
  const secondary = card.querySelector<HTMLButtonElement>(`[data-dir="${other}"]`)
  const target = primary && !primary.disabled ? primary : secondary && !secondary.disabled ? secondary : null
  target?.focus()
}

function moveBackward(index: number) {
  const key = pages.value[index].key
  move(index, index - 1)
  focusMoveButton(key, 'backward')
}

function moveForward(index: number) {
  const key = pages.value[index].key
  move(index, index + 1)
  focusMoveButton(key, 'forward')
}

function rotateLeft(index: number) {
  pages.value[index].rotate = (pages.value[index].rotate + 270) % 360
}

function rotateRight(index: number) {
  pages.value[index].rotate = (pages.value[index].rotate + 90) % 360
}

function removePage(index: number) {
  pages.value = pages.value.filter((_, i) => i !== index)
}

function reset() {
  pages.value = initialOrder.value.map((p) => ({ key: nextKey(), source: p.source, rotate: p.rotate }))
  errorMessage.value = null
}

// --- Native drag-to-reorder (mouse/touch pointer) -----------------------------
// Dragging is locked while a save is in flight (alongside the per-page controls) so the
// arrangement cannot change under the request that will produce the success toast.
function onDragStart(index: number) {
  if (saving.value) return
  dragIndex = index
}
function onDragOver(event: DragEvent) {
  if (saving.value) return
  // Allowing the drop is what enables the reorder; without preventDefault the browser rejects it.
  event.preventDefault()
}
function onDrop(index: number) {
  if (saving.value) return
  if (dragIndex !== null) move(dragIndex, index)
  dragIndex = null
}
function onDragEnd() {
  dragIndex = null
}

// --- Save ---------------------------------------------------------------------
function messageForError(err: unknown): string {
  const e = err as { response?: { status?: number; data?: { type?: string } } }
  const status = e?.response?.status
  const type = e?.response?.data?.type
  // Status wins for the transient/conflict classes so the message is right even if the type
  // token ever changes; the 400 sub-types below carry the specific reason.
  if (status === 429 || type === 'TooManyRequests') return t('ui.pdf_organizer.error.busy')
  if (status === 409 || type === 'VersionConflict') return t('ui.pdf_organizer.error.stale')
  switch (type) {
    case 'BaseVersionMismatch':
    case 'PreviousVersionMismatch':
      return t('ui.pdf_organizer.error.stale')
    case 'TooManyPages':
      return t('ui.pdf_organizer.error.too_many_pages')
    case 'SourceTooLarge':
      return t('ui.pdf_organizer.error.too_large')
    case 'SignedSource':
      return t('ui.pdf_organizer.error.signed')
    case 'EncryptedSource':
      return t('ui.pdf_organizer.error.encrypted')
    case 'EmptyResult':
      return t('ui.pdf_organizer.error.empty')
    default:
      // InvalidPdf / OutputInvalid / InvalidManifest / a 500 FileError (which includes an
      // over-quota save on this endpoint) / a network error all land here.
      return t('ui.pdf_organizer.error.generic')
  }
}

async function save() {
  if (!canSave.value || baseVersion.value === null) return
  saving.value = true
  errorMessage.value = null
  const manifest: PageManifest = {
    version: 1,
    baseVersion: baseVersion.value,
    // Post the ABSOLUTE orientation for every page (the backend sets it absolutely). Sending it
    // unconditionally — rather than omitting 0 — keeps "what you saw is what is saved" true even
    // for a page whose intrinsic rotation the user rotated back to upright.
    pages: pages.value.map((p) => ({ source: p.source, rotate: p.rotate })),
  }
  try {
    await applyPageOperations(props.fileId, manifest)
    // Refresh the open document so the file panel shows the new version immediately (the file
    // gets a new id on save). Prefix match refreshes whichever document detail is active.
    queryClient.invalidateQueries({ queryKey: ['document'] })
    toast.add({ severity: 'success', summary: t('ui.pdf_organizer.saved'), life: 2000 })
    open.value = false
  } catch (err) {
    errorMessage.value = messageForError(err)
  } finally {
    saving.value = false
  }
}

const header = computed(() =>
  props.fileName
    ? t('ui.pdf_organizer.title_named', { name: props.fileName })
    : t('ui.pdf_organizer.title'),
)
</script>

<template>
  <Button
    icon="pi pi-file-edit"
    text
    rounded
    size="small"
    severity="secondary"
    class="pdf-organizer-trigger"
    @click="open = true"
    v-tooltip="t('ui.pdf_organizer.edit_pages')"
    :aria-label="t('ui.pdf_organizer.edit_pages')"
  />

  <Dialog
    v-model:visible="open"
    modal
    :header="header"
    :style="{ width: '56rem' }"
    :breakpoints="{ '960px': '95vw' }"
    class="pdf-organizer-dialog"
  >
    <div v-if="loading" class="pdf-organizer-loading" role="status" :aria-label="t('ui.pdf_organizer.loading')">
      <ProgressSpinner style="width: 2.5rem; height: 2.5rem" strokeWidth="4" />
      <span>{{ t('ui.pdf_organizer.loading') }}</span>
    </div>

    <ErrorState v-else-if="loadError" :message="t('ui.pdf_organizer.load_failed')" @retry="load" />

    <template v-else>
      <p class="pdf-organizer-hint">{{ t('ui.pdf_organizer.drag_hint') }}</p>

      <Message
        v-if="errorMessage"
        severity="error"
        :closable="false"
        class="pdf-organizer-error"
        data-test="organizer-error"
        >{{ errorMessage }}</Message
      >

      <EmptyState
        v-if="pages.length === 0"
        icon="pi pi-file"
        :message="t('ui.pdf_organizer.empty_hint')"
      />

      <div v-else ref="listRef" class="pdf-organizer-grid" data-test="organizer-grid">
        <div
          v-for="(page, index) in pages"
          :key="page.key"
          class="pdf-page-slot"
          :data-page-key="page.key"
          :draggable="!saving"
          @dragstart="onDragStart(index)"
          @dragover="onDragOver"
          @drop="onDrop(index)"
          @dragend="onDragEnd"
        >
          <PdfPageThumbnail
            v-if="pdfDoc"
            :pdf-doc="pdfDoc"
            :source="page.source"
            :rotate="page.rotate"
            :position="index + 1"
            :can-move-backward="index > 0"
            :can-move-forward="index < pages.length - 1"
            :disabled="saving"
            @move-backward="moveBackward(index)"
            @move-forward="moveForward(index)"
            @rotate-left="rotateLeft(index)"
            @rotate-right="rotateRight(index)"
            @remove="removePage(index)"
          />
        </div>
      </div>
    </template>

    <template #footer>
      <Button
        :label="t('ui.pdf_organizer.reset')"
        text
        severity="secondary"
        :disabled="saving || !dirty"
        @click="reset"
      />
      <Button :label="t('cancel')" text severity="secondary" :disabled="saving" @click="open = false" />
      <Button
        :label="saving ? t('ui.pdf_organizer.saving') : t('ui.pdf_organizer.save')"
        icon="pi pi-save"
        :loading="saving"
        :disabled="!canSave"
        data-test="organizer-save"
        @click="save"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.pdf-organizer-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 2rem;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}
.pdf-organizer-hint {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.pdf-organizer-error {
  margin-bottom: 0.75rem;
}
.pdf-organizer-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(11rem, 100%), 1fr));
  gap: 0.75rem;
}
.pdf-page-slot {
  /* The whole slot is the drag source/target; the card fills it. */
  display: flex;
}
.pdf-page-slot > * {
  width: 100%;
}
</style>
