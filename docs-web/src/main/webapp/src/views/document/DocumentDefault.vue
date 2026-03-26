<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import FileUpload from 'primevue/fileupload'
import EmptyState from '../../components/EmptyState.vue'

const router = useRouter()
const isDragging = ref(false)

function onUpload(event: any) {
  // File upload would create a new document and attach files
  // For now, redirect to add document
  router.push({ name: 'document-add' })
}
</script>

<template>
  <div class="default-view">
    <!-- Upload zone -->
    <section class="upload-section teedy-card">
      <h3>Quick upload</h3>
      <div
        class="upload-zone"
        :class="{ 'drag-active': isDragging }"
        @dragenter="isDragging = true"
        @dragleave="isDragging = false"
        @drop="isDragging = false"
      >
        <FileUpload
          mode="basic"
          :auto="true"
          :multiple="true"
          chooseLabel="Choose files or drag & drop"
          chooseIcon="pi pi-upload"
          @uploader="onUpload"
          :customUpload="true"
          class="w-full"
        />
      </div>
    </section>

    <!-- Getting started -->
    <section class="teedy-card p-4 mt-4">
      <h3 style="margin-top: 0">Getting started</h3>
      <p class="text-sm text-muted">
        Select a document from the sidebar, or create a new one to get started.
        You can search for documents using the search bar, or filter by tags.
      </p>
      <p class="text-sm text-muted">
        <strong>Search tips:</strong> Use <code>tag:name</code> to filter by tag,
        <code>after:2024</code> and <code>before:2025</code> for date ranges,
        <code>by:username</code> for author, or <code>lang:eng</code> for language.
      </p>
    </section>
  </div>
</template>

<style scoped>
.default-view {
  padding: 1.5rem;
  max-width: 800px;
}

.upload-section {
  padding: 1.5rem;
}
.upload-section h3 {
  margin: 0 0 1rem;
  font-size: 1.125rem;
  font-weight: 600;
}

.upload-zone {
  border: 2px dashed #d1d5db;
  border-radius: 8px;
  padding: 2rem;
  text-align: center;
  transition: border-color 0.15s, background 0.15s;
}
.upload-zone.drag-active {
  border-color: var(--teedy-brand);
  background: #e8f4f8;
}

code {
  background: #f3f4f6;
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-size: 0.8125rem;
}
</style>
