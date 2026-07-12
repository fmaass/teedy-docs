<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { getFileUrl, deleteFile, renameFile, uploadFile, setRotation } from '../../api/file'
import {
  listDocuments,
  updateDocument,
  buildRelationsParams,
  type DocumentListItem,
} from '../../api/document'
import { queryKeys } from '../../api/queryKeys'
import PdfViewer from '../../components/PdfViewer.vue'
import EmptyState from '../../components/EmptyState.vue'
import FileVersionsDialog from '../../components/FileVersionsDialog.vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import AutoComplete from 'primevue/autocomplete'
import FileUpload, { type FileUploadUploaderEvent } from 'primevue/fileupload'
import CameraCaptureButton from '../../components/CameraCaptureButton.vue'
import UploadProgressList from '../../components/UploadProgressList.vue'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import { formatDate, formatFileSize } from '../../utils/formatters'
import { injectDocument } from './documentKey'

const doc = injectDocument()
const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

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

const renamingId = ref<string | null>(null)
const renameValue = ref('')

const versionsDialogVisible = ref(false)
const versionsFileId = ref<string | null>(null)
const versionsFileName = ref('')

function showVersions(file: { id: string; name: string }) {
  versionsFileId.value = file.id
  versionsFileName.value = file.name
  versionsDialogVisible.value = true
}
const uploading = ref(false)
const uploadProgress = ref<Record<number, number>>({})
const uploadingNames = ref<string[]>([])
const fileUploadRef = ref()

async function uploadAll(files: File[]) {
  if (!doc.value || !files.length) return
  // Snapshot the id: the injected ref can be cleared/replaced while a batch is
  // in flight, and the finally block must still target the original document.
  const documentId = doc.value.id
  uploading.value = true
  uploadProgress.value = {}
  uploadingNames.value = files.map((f) => f.name)
  try {
    for (let i = 0; i < files.length; i++) {
      uploadProgress.value[i] = 0
      await uploadFile(documentId, files[i], (pct) => {
        uploadProgress.value[i] = pct
      })
      uploadProgress.value[i] = 100
    }
    toast.add({ severity: 'success', summary: t('ui.files_uploaded'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.upload_failed'), life: 3000 })
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

async function handleUpload(event: FileUploadUploaderEvent) {
  const files = Array.isArray(event.files) ? event.files : [event.files]
  await uploadAll(files as File[])
}

// Camera capture: photos from CameraCaptureButton upload immediately via the same
// real PUT /api/file path.
async function onCameraCapture(captured: File[]) {
  await uploadAll(captured)
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

function startRename(file: { id: string; name: string }) {
  renamingId.value = file.id
  renameValue.value = file.name
}

function cancelRename() {
  renamingId.value = null
  renameValue.value = ''
}

async function commitRename(fileId: string) {
  const name = renameValue.value.trim()
  if (!name) return cancelRename()
  try {
    await renameFile(fileId, name)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value?.id] })
    toast.add({ severity: 'success', summary: t('ui.file_renamed'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_rename_file'), life: 3000 })
  } finally {
    cancelRename()
  }
}

function confirmDelete(file: { id: string; name: string }) {
  confirmDanger({
    message: t('ui.remove_file_confirm', { name: file.name }),
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

    <!-- File previews -->
    <div v-if="doc.files?.length" class="file-preview-grid">
      <template v-for="file in doc.files" :key="file.id">
        <div v-if="isImage(file.mimetype)" class="file-preview-card">
          <div class="image-preview-stage">
            <!-- No CSS transform: the served _web raster is already physically rotated by the
                 server. The rotation only cache-busts the URL so the fresh raster loads. -->
            <img
              :src="getFileUrl(file.id, 'web', undefined, effectiveRotation(file))"
              :alt="file.name"
              loading="lazy"
              class="rotatable-image"
            />
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
          <div class="file-preview-label">{{ file.name }}</div>
        </div>
        <div v-else-if="file.mimetype === 'application/pdf'" class="file-preview-card">
          <PdfViewer
            :src="getFileUrl(file.id)"
            :initial-rotation="file.rotation ?? 0"
            :persistable="doc.writable"
            @rotate="(deg: number) => persistRotation(file, deg)"
          />
          <div class="file-preview-label">{{ file.name }}</div>
        </div>
      </template>
    </div>

    <!-- File list -->
    <div v-if="doc.files?.length" class="file-list-section">
      <h3>{{ t('ui.files_count', { count: doc.files.length }) }}</h3>
      <div class="file-table">
        <div v-for="file in doc.files" :key="file.id" class="file-row">
          <i :class="fileIcon(file.mimetype)" class="file-type-icon" />

          <!-- Name: either link or rename input -->
          <div class="file-name-cell">
            <template v-if="renamingId === file.id">
              <InputText
                v-model="renameValue"
                size="small"
                class="rename-input"
                @keyup.enter="commitRename(file.id)"
                @keyup.escape="cancelRename"
                autofocus
              />
            </template>
            <a v-else :href="getFileUrl(file.id)" target="_blank" class="file-link">
              {{ file.name }}
            </a>
          </div>

          <span class="file-mime">{{ file.mimetype }}</span>
          <span class="file-size">{{ formatFileSize(file.size) }}</span>

          <div class="file-actions">
            <template v-if="renamingId === file.id">
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitRename(file.id)" :aria-label="t('ui.confirm_rename')" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelRename" :aria-label="t('ui.cancel_rename')" />
            </template>
            <template v-else>
              <Button
                icon="pi pi-history"
                text
                rounded
                size="small"
                severity="secondary"
                @click="showVersions(file)"
                v-tooltip="t('ui.versions.title')"
                :aria-label="t('ui.versions.title')"
              />
              <Button
                icon="pi pi-pencil"
                text
                rounded
                size="small"
                severity="secondary"
                @click="startRename(file)"
                v-tooltip="t('rename')"
                :aria-label="t('rename')"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click="confirmDelete(file)"
                v-tooltip="t('ui.remove_file')"
                :aria-label="t('ui.remove_file')"
              />
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Upload zone -->
    <FileUpload
      ref="fileUploadRef"
      mode="advanced"
      multiple
      customUpload
      auto
      :showUploadButton="false"
      :showCancelButton="false"
      :disabled="uploading"
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
    <CameraCaptureButton :disabled="uploading" @capture="onCameraCapture" />

    <!-- Real per-file upload progress. -->
    <UploadProgressList v-if="uploading" :names="uploadingNames" :progress="uploadProgress" />

    <EmptyState
      v-if="!doc.files?.length"
      icon="pi pi-file"
      :message="t('ui.no_files')"
      :action-label="t('ui.edit_to_add_files')"
      @action="$router.push({ name: 'document-edit', params: { id: doc.id } })"
    />

    <FileVersionsDialog
      v-model:visible="versionsDialogVisible"
      :file-id="versionsFileId"
      :file-name="versionsFileName"
    />
  </div>
</template>

<style scoped>
.doc-description {
  margin: 0 0 1.5rem;
  color: var(--p-text-color);
  line-height: 1.6;
}

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

.file-list-section {
  margin-top: 1rem;
}
.file-list-section h3 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
}

.file-table {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
  transition: background 0.1s;
}
.file-row:last-child {
  border-bottom: none;
}
.file-row:hover {
  background: var(--p-content-hover-background);
}

.file-type-icon {
  color: var(--p-text-muted-color);
  font-size: 0.9rem;
  flex-shrink: 0;
}

.file-name-cell {
  flex: 1;
  min-width: 0;
}

.file-link {
  font-size: 0.875rem;
  color: var(--p-text-color);
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
}
.file-link:hover {
  color: var(--teedy-brand);
  text-decoration: underline;
}

.rename-input {
  width: 100%;
  font-size: 0.875rem;
}

.file-mime {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
  width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
  width: 70px;
  text-align: right;
}

.file-actions {
  display: flex;
  gap: 0.125rem;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}
.file-row:hover .file-actions,
.file-row:focus-within .file-actions {
  opacity: 1;
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

@media (max-width: 600px) {
  .file-mime {
    display: none;
  }
}
</style>
