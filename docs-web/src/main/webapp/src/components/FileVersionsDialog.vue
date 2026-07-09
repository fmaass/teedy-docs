<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'
import { getFileVersions, getFileUrl, type FileVersion } from '../api/file'
import { formatDate } from '../composables/useFormatters'
import { createGeneration } from '../utils/staleGuard'

const props = defineProps<{
  fileId: string | null
  fileName?: string
}>()

const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()

const versions = ref<FileVersion[]>([])
const loading = ref(false)
const error = ref(false)

// Generation guard against out-of-order async completion. The caller
// (DocumentViewContent) reuses ONE dialog instance, so opening file A then quickly
// file B fires load twice; A's slower response must not overwrite B's versions under
// B's title. Each run claims a generation at entry and re-checks after the await — a
// mismatch means a newer load has superseded it, so the stale run stops without
// mutating state.
const gen = createGeneration()

async function load(id: string) {
  const myGen = gen.next()
  loading.value = true
  error.value = false
  try {
    // Newest version first for a natural history reading order.
    const list = await getFileVersions(id)
    if (!gen.isCurrent(myGen)) return
    versions.value = [...list].sort((a, b) => b.version - a.version)
  } catch {
    if (!gen.isCurrent(myGen)) return
    error.value = true
    versions.value = []
  } finally {
    // Only the newest run owns the loading flag; a superseded run must leave it
    // set so the current in-flight load still shows its spinner.
    if (gen.isCurrent(myGen)) loading.value = false
  }
}

// Load whenever the dialog opens for a file id.
watch(
  () => [visible.value, props.fileId] as const,
  ([open, id]) => {
    if (open && id) load(id)
  },
  { immediate: true },
)

function versionUrl(version: FileVersion) {
  return getFileUrl(version.id)
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="fileName ? t('ui.versions.title_named', { name: fileName }) : t('ui.versions.title')"
    :style="{ width: '40rem' }"
    :breakpoints="{ '640px': '95vw' }"
  >
    <div v-if="loading" class="versions-loading">
      <ProgressSpinner style="width: 2.5rem; height: 2.5rem" strokeWidth="4" />
    </div>

    <ErrorState v-else-if="error" :message="t('ui.versions.load_failed')" @retry="fileId && load(fileId)" />

    <EmptyState
      v-else-if="!versions.length"
      icon="pi pi-history"
      :message="t('ui.versions.empty')"
    />

    <template v-else>
      <p class="versions-hint">{{ t('ui.versions.read_only_hint') }}</p>
      <DataTable :value="versions" dataKey="id" size="small" class="versions-table">
        <Column :header="t('ui.versions.version')" style="width: 6rem">
          <template #body="{ data }">
            <span class="version-number">{{ t('ui.versions.version_number', { n: data.version + 1 }) }}</span>
          </template>
        </Column>
        <Column :header="t('ui.versions.date')">
          <template #body="{ data }">{{ formatDate(data.create_date) }}</template>
        </Column>
        <Column :header="t('ui.versions.type')">
          <template #body="{ data }">
            <span class="version-mime">{{ data.mimetype }}</span>
          </template>
        </Column>
        <Column header="" style="width: 5rem">
          <template #body="{ data }">
            <a :href="versionUrl(data)" target="_blank" rel="noopener">
              <Button
                icon="pi pi-download"
                text
                rounded
                size="small"
                severity="secondary"
                v-tooltip="t('ui.versions.view')"
                :aria-label="t('ui.versions.view')"
              />
            </a>
          </template>
        </Column>
      </DataTable>
    </template>

    <template #footer>
      <Button :label="t('close')" text @click="visible = false" />
    </template>
  </Dialog>
</template>

<style scoped>
.versions-loading {
  display: flex;
  justify-content: center;
  padding: 2rem 0;
}
.versions-hint {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.version-number {
  font-weight: 600;
  font-size: 0.875rem;
}
.version-mime {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
