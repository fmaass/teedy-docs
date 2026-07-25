<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { buildFileLink, getFileUrl } from '../api/file'
import { displayName } from '../utils/fileName'

// Reusable per-file action surface shared by the file list/grid. Preview, copy-link,
// download and version history are READ actions and are always offered; rename and delete
// are write actions, so they — and any caller-injected `extra` actions (#73 "Edit pages",
// #117 "Upload new version") — render only when the document is writable. This is the
// single place the read-only gate for per-file mutations lives, so every consumer inherits it.
//
// Preview is emitted rather than handled here: each consumer already owns the in-app
// preview dialog (#144), and routing through the parent keeps ONE preview path per view.
// Download, by contrast, is a plain anchor at the ORIGINAL file URL — the backend serves
// that URL as an attachment under a locked-down CSP, so it can only ever download, never
// render. That is why it is labelled Download and never "open".
//
// Copy link (#192) IS handled here, because it has no per-consumer behaviour to route: it
// writes a deep link to this exact file to the clipboard and reports the outcome. It sits
// above the writable gate deliberately — handing a colleague the precise file is a read
// action, and the link grants nothing (the recipient still needs their own READ on the
// document).
export interface FileActionTarget {
  id: string
  // Nullable to match the file panel's model — a file can be served without a name.
  name: string | null
  mimetype: string
}

const props = defineProps<{
  file: FileActionTarget
  writable: boolean
  // The document this file belongs to. Required: the copy-link action is always offered, so
  // there is no state in which the menu can render without knowing where the file lives.
  // The file rows themselves carry no document id, so every consumer must thread it down.
  documentId: string
  // True when this file is the document's explicit cover: the "set as cover" action is hidden and a
  // "remove as cover" action is offered instead. Defaults to false so consumers that do not manage a
  // cover keep their current behaviour.
  isCover?: boolean
}>()

const emit = defineEmits<{
  versions: [file: FileActionTarget]
  preview: [file: FileActionTarget]
  rename: [file: FileActionTarget]
  delete: [file: FileActionTarget]
  setCover: [file: FileActionTarget]
  clearCover: [file: FileActionTarget]
  move: [file: FileActionTarget]
}>()

const { t } = useI18n()
const toast = useToast()

// Routed through displayName so a null-named file gets the localized fallback instead of
// an accessible name reading "Open " — file.name is nullable on the wire.
const previewLabel = computed(() =>
  t('ui.file_view.open_file', { name: displayName(props.file.name, t) }),
)

// Same try/catch+toast shape as DocumentViewPermissions' share-link copy. The try covers
// the PROPERTY ACCESS as well as the write: on a plain-http origin (not a secure context)
// `navigator.clipboard` is undefined and reading `.writeText` off it throws synchronously,
// which must land in the same error toast rather than escaping as an unhandled rejection.
async function copyLink() {
  try {
    await navigator.clipboard.writeText(buildFileLink(props.documentId, props.file.id))
    toast.add({ severity: 'success', summary: t('ui.file_view.link_copied'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.file_view.link_copy_failed'), life: 3000 })
  }
}
</script>

<template>
  <div class="file-action-menu">
    <Button
      icon="pi pi-history"
      text
      rounded
      size="small"
      severity="secondary"
      @click="emit('versions', file)"
      v-tooltip="t('ui.versions.title')"
      :aria-label="t('ui.versions.title')"
    />
    <Button
      icon="pi pi-eye"
      text
      rounded
      size="small"
      severity="secondary"
      @click="emit('preview', file)"
      v-tooltip="previewLabel"
      :aria-label="previewLabel"
    />
    <!-- #192: a READ action, between preview and download and ABOVE the writable gate. -->
    <Button
      icon="pi pi-link"
      text
      rounded
      size="small"
      severity="secondary"
      @click="copyLink"
      v-tooltip="t('ui.file_view.copy_link')"
      :aria-label="t('ui.file_view.copy_link')"
    />
    <Button
      as="a"
      icon="pi pi-download"
      text
      rounded
      size="small"
      severity="secondary"
      class="file-action-download"
      :href="getFileUrl(file.id)"
      :download="file.name ?? ''"
      v-tooltip="t('download')"
      :aria-label="t('download')"
    />
    <!-- Write actions: gated on `writable` so a read-only viewer sees the read actions only. -->
    <template v-if="writable">
      <slot name="extra" :file="file" :writable="writable" />
      <Button
        v-if="!isCover"
        icon="pi pi-image"
        text
        rounded
        size="small"
        severity="secondary"
        @click="emit('setCover', file)"
        v-tooltip="t('ui.set_as_cover')"
        :aria-label="t('ui.set_as_cover')"
      />
      <Button
        v-else
        icon="pi pi-image"
        text
        rounded
        size="small"
        severity="contrast"
        @click="emit('clearCover', file)"
        v-tooltip="t('ui.remove_as_cover')"
        :aria-label="t('ui.remove_as_cover')"
      />
      <Button
        icon="pi pi-arrow-right"
        text
        rounded
        size="small"
        severity="secondary"
        @click="emit('move', file)"
        v-tooltip="t('ui.move_file')"
        :aria-label="t('ui.move_file')"
      />
      <Button
        icon="pi pi-pencil"
        text
        rounded
        size="small"
        severity="secondary"
        @click="emit('rename', file)"
        v-tooltip="t('rename')"
        :aria-label="t('rename')"
      />
      <Button
        icon="pi pi-trash"
        text
        rounded
        size="small"
        severity="danger"
        @click="emit('delete', file)"
        v-tooltip="t('ui.remove_file')"
        :aria-label="t('ui.remove_file')"
      />
    </template>
  </div>
</template>

<style scoped>
.file-action-menu {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
}
</style>
