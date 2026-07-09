<script setup lang="ts">
import ProgressBar from 'primevue/progressbar'

// Shared per-file upload progress list: one named row with a real-bytes progress
// bar per file in flight. Both DocumentEdit and DocumentViewContent render this
// while an upload batch runs; the parent owns the queue and the progress record.

defineProps<{
  /** File names in upload order; index i pairs with progress[i]. */
  names: string[]
  /** Per-file percentage (0..100), keyed by index into `names`. */
  progress: Record<number, number>
}>()
</script>

<template>
  <div class="upload-progress-list">
    <div v-for="(name, i) in names" :key="i" class="upload-progress-item">
      <span class="upload-progress-name">{{ name }}</span>
      <ProgressBar :value="progress[i] ?? 0" class="upload-progress-bar" />
    </div>
  </div>
</template>

<style scoped>
.upload-progress-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.upload-progress-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.upload-progress-name {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-progress-bar {
  height: 0.75rem;
}
</style>
