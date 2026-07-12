<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery } from '@tanstack/vue-query'
import DOMPurify from 'dompurify'
import { getDocument } from '../api/document'
import { getFileUrl } from '../api/file'
import { formatFileSize } from '../utils/formatters'
import Skeleton from 'primevue/skeleton'
import ErrorState from '../components/ErrorState.vue'

// Public, unauthenticated read-only view of a shared document. Reachable via the
// share link built by buildShareUrl(): #/share/:documentId/:shareId. Every
// backend read threads ?share=<shareId> so the anonymous principal passes the
// document's SHARE ACL.
const props = defineProps<{ documentId: string; shareId: string }>()
const { t } = useI18n()

const { data: doc, isLoading: loading, error, refetch } = useQuery({
  queryKey: computed(() => ['share', props.documentId, props.shareId]),
  queryFn: () => getDocument(props.documentId, true, props.shareId).then((r) => r.data),
  retry: false,
})

const sanitizedDescription = computed(() =>
  doc.value?.description ? DOMPurify.sanitize(doc.value.description) : '',
)

function fileUrl(fileId: string, size?: 'web', rotation?: number) {
  return getFileUrl(fileId, size, props.shareId, rotation)
}

function isImage(mime: string) {
  return mime.startsWith('image/')
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })
}
</script>

<template>
  <div class="share-page">
    <div class="share-card">
      <div v-if="loading" class="share-loading">
        <Skeleton width="60%" height="2rem" class="mb-2" />
        <Skeleton width="30%" height="1rem" class="mb-4" />
        <Skeleton height="16rem" />
      </div>

      <ErrorState v-else-if="error || !doc" :message="t('ui.share.view.not_found')" @retry="refetch()" />

      <template v-else>
        <header class="share-header">
          <h1>{{ doc.title }}</h1>
          <p class="share-meta">
            {{ formatDate(doc.create_date) }}
            <span v-if="doc.file_count"> · {{ t('ui.n_files', doc.file_count) }}</span>
          </p>
        </header>

        <div v-if="sanitizedDescription" class="share-description" v-html="sanitizedDescription" />

        <div v-if="doc.files?.length" class="share-files">
          <template v-for="file in doc.files" :key="file.id">
            <div v-if="isImage(file.mimetype)" class="share-file-card">
              <img :src="fileUrl(file.id, 'web', file.rotation)" :alt="file.name" loading="lazy" />
              <div class="share-file-label">{{ file.name }}</div>
            </div>
          </template>

          <div class="share-file-table">
            <a
              v-for="file in doc.files"
              :key="file.id"
              :href="fileUrl(file.id)"
              target="_blank"
              rel="noopener"
              class="share-file-row"
            >
              <i class="pi pi-download share-file-icon" aria-hidden="true" />
              <span class="share-file-name">{{ file.name }}</span>
              <span class="share-file-size">{{ formatFileSize(file.size) }}</span>
            </a>
          </div>
        </div>

        <p class="share-footer">{{ t('ui.share.view.footer') }}</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.share-page {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  padding: 2rem 1rem;
  background: var(--p-content-hover-background, #f4f4f5);
}

.share-card {
  width: 100%;
  max-width: 820px;
  background: var(--p-content-background);
  border: 1px solid var(--p-content-border-color);
  border-radius: 12px;
  padding: 2rem;
}

.share-loading {
  padding: 1rem 0;
}

.share-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.3;
}

.share-meta {
  margin: 0.3rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.share-description {
  margin: 1.5rem 0;
  line-height: 1.6;
  color: var(--p-text-color);
}

.share-files {
  margin-top: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.share-file-card {
  overflow: hidden;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
}
.share-file-card img {
  width: 100%;
  display: block;
  max-height: 480px;
  object-fit: contain;
  background: var(--p-content-hover-background);
}
.share-file-label {
  padding: 0.375rem 0.625rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  border-top: 1px solid var(--p-content-border-color);
}

.share-file-table {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
}
.share-file-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
  text-decoration: none;
  color: var(--p-text-color);
}
.share-file-row:last-child {
  border-bottom: none;
}
.share-file-row:hover {
  background: var(--p-content-hover-background);
}
.share-file-icon {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  flex-shrink: 0;
}
.share-file-name {
  flex: 1;
  font-size: 0.875rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.share-file-size {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
}

.share-footer {
  margin: 1.5rem 0 0;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  text-align: center;
}
</style>
