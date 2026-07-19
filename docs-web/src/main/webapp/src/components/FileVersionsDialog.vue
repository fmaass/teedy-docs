<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'
import { getFileVersions, getFileUrl, type FileVersion } from '../api/file'
import { formatDate } from '../utils/formatters'
import { createGeneration } from '../utils/staleGuard'
import { useVersionUpload } from '../composables/useVersionUpload'

const props = defineProps<{
  fileId: string | null
  fileName?: string
  // Writable gate: the upload-new-version footer action is offered only when the
  // document is writable (a read-only viewer still sees the read-only history).
  writable?: boolean
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

// #117.1 — "Upload new version" from the versions dialog (same wiring as the per-file
// action). The base is the CURRENT chain head (versions are sorted newest-first, so
// versions[0] is the latest), which keeps repeat uploads in one open dialog correct
// after the list refreshes. On success the version list reloads to show v(n+1).
const { input: versionInput, uploading: uploadingVersion, pick: pickNewVersion, onPicked } =
  useVersionUpload(() => {
    if (props.fileId) load(props.fileId)
  })

function onVersionPicked(event: Event) {
  const base = versions.value[0]?.id
  if (base) return onPicked(event, base)
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
    <div v-if="loading" class="versions-loading" role="status" :aria-label="t('ui.versions.loading')">
      <Skeleton v-for="n in 3" :key="n" height="2.25rem" class="version-row-skeleton" />
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
      <div class="versions-footer">
        <template v-if="writable && !loading && versions.length">
          <Button
            :label="t('ui.versions.upload_new')"
            icon="pi pi-upload"
            text
            :loading="uploadingVersion"
            @click="pickNewVersion"
          />
          <input
            ref="versionInput"
            type="file"
            class="upload-version-input"
            hidden
            @change="onVersionPicked"
          />
        </template>
        <Button :label="t('close')" text severity="secondary" @click="visible = false" />
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.versions-loading {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.5rem 0;
}
.version-row-skeleton {
  width: 100%;
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
.versions-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
}
</style>
