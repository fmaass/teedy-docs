<script setup lang="ts">
import PdfPageOrganizer from './PdfPageOrganizer.vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useVersionUpload } from '../composables/useVersionUpload'

// Single-source mount point for per-file EXTRA actions, shared by the grid tiles and
// the list rows. It is rendered inside FileActionMenu's writable-only `#extra` slot in
// BOTH views, so any control added here appears in both and inherits the writable gate —
// with no edits to FileListTable or the grid markup.
//
// >>> Per-file controls live HERE (and only here): <<<
//   #73  "Edit pages"         — PDF page operations (guard on file.mimetype === 'application/pdf')
//   #117 "Upload new version" — replace-with-new-version upload
// Each control owns its own applicability; `writable` is already enforced by the slot.
const props = defineProps<{
  file: { id: string; name: string | null; mimetype: string }
  writable: boolean
}>()

// --- #117.1 "Upload new version" ---------------------------------------------------
// A self-contained per-file action: pick a replacement file and post it through the
// shared version pipeline (previousFileId = THIS file's id) so it becomes v(n+1) of the
// same chain rather than a second attachment. useVersionUpload owns the network wiring
// (also used by the versions dialog).
const { t } = useI18n()
const { input: versionInput, uploading: uploadingVersion, pick: pickNewVersion, onPicked } =
  useVersionUpload()

function onVersionPicked(event: Event) {
  return onPicked(event, props.file.id)
}
</script>

<template>
  <!-- #73: PDF-only page organizer. The trigger + dialog live inside the component. -->
  <PdfPageOrganizer
    v-if="file.mimetype === 'application/pdf'"
    :file-id="file.id"
    :file-name="file.name"
  />
  <!-- #117.1 -->
  <Button
    icon="pi pi-upload"
    text
    rounded
    size="small"
    severity="secondary"
    :loading="uploadingVersion"
    @click="pickNewVersion"
    v-tooltip="t('ui.versions.upload_new')"
    :aria-label="t('ui.versions.upload_new')"
  />
  <input
    ref="versionInput"
    type="file"
    class="upload-version-input"
    hidden
    @change="onVersionPicked"
  />
</template>
