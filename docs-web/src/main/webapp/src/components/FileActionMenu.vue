<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'

// Reusable per-file action surface shared by the file list/grid. Version history is
// always offered (read-only history); rename and delete are write actions, so they —
// and any caller-injected `extra` actions (#73 "Edit pages", #117 "Upload new version")
// — render only when the document is writable. This is the single place the read-only
// gate for per-file mutations lives, so every consumer inherits it.
export interface FileActionTarget {
  id: string
  // Nullable to match the file panel's model — a file can be served without a name.
  name: string | null
  mimetype: string
}

defineProps<{
  file: FileActionTarget
  writable: boolean
}>()

const emit = defineEmits<{
  versions: [file: FileActionTarget]
  rename: [file: FileActionTarget]
  delete: [file: FileActionTarget]
}>()

const { t } = useI18n()
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
    <!-- Write actions: gated on `writable` so a read-only viewer sees history only. -->
    <template v-if="writable">
      <slot name="extra" :file="file" :writable="writable" />
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
