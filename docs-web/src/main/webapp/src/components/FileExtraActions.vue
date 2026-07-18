<script setup lang="ts">
// Single-source mount point for per-file EXTRA actions, shared by the grid tiles and
// the list rows. It is rendered inside FileActionMenu's writable-only `#extra` slot in
// BOTH views, so any control added here appears in both and inherits the writable gate —
// with no edits to FileListTable or the grid markup (Decision 4).
//
// >>> Phases 3/4 add their per-file controls HERE (and only here): <<<
//   #73  "Edit pages"         — PDF page operations (guard on file.mimetype === 'application/pdf')
//   #117 "Upload new version" — replace-with-new-version upload
// Add <Button>s that emit up / open a dialog; each control owns its own applicability.
// No extra actions exist yet, so this renders nothing.
defineProps<{
  file: { id: string; name: string; mimetype: string }
  writable: boolean
}>()
</script>

<template>
  <!-- empty until #73 / #117 land -->
</template>
