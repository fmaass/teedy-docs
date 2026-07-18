<script setup lang="ts">
import PdfPageOrganizer from './PdfPageOrganizer.vue'

// Single-source mount point for per-file EXTRA actions, shared by the grid tiles and
// the list rows. It is rendered inside FileActionMenu's writable-only `#extra` slot in
// BOTH views, so any control added here appears in both and inherits the writable gate —
// with no edits to FileListTable or the grid markup (Decision 4).
//
// >>> Phases 3/4 add their per-file controls HERE (and only here): <<<
//   #73  "Edit pages"         — PDF page operations (guard on file.mimetype === 'application/pdf')
//   #117 "Upload new version" — replace-with-new-version upload
// Each control owns its own applicability; `writable` is already enforced by the slot.
defineProps<{
  file: { id: string; name: string; mimetype: string }
  writable: boolean
}>()
</script>

<template>
  <!-- #73: PDF-only page organizer. The trigger + dialog live inside the component. -->
  <PdfPageOrganizer
    v-if="file.mimetype === 'application/pdf'"
    :file-id="file.id"
    :file-name="file.name"
  />
</template>
