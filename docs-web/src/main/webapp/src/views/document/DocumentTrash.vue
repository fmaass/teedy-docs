<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient, useMutation, keepPreviousData } from '@tanstack/vue-query'
import { listTrash, restoreDocument, permanentDeleteDocument, emptyTrash, type TrashItem } from '../../api/document'
import DataTable, { type DataTablePageEvent } from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import { daysUntilPurge, DEFAULT_RETENTION_DAYS } from '../../utils/trashRetention'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

// --- Pagination (mirrors DocumentList: lazy PrimeVue paginator, server limit/offset) ---
const PAGE_SIZE = 20
const pageOffset = ref(0)

const { data: trashData, isLoading, isError, refetch } = useQuery({
  queryKey: computed(() => ['trash', { offset: pageOffset.value }]),
  queryFn: () => listTrash({ limit: PAGE_SIZE, offset: pageOffset.value }).then((r) => r.data),
  placeholderData: keepPreviousData,
})

const documents = computed(() => trashData.value?.documents ?? [])
const totalCount = computed(() => trashData.value?.total ?? 0)

function onPage(event: DataTablePageEvent) {
  pageOffset.value = event.first
}

// Retention window is not exposed by the API (see utils/trashRetention.ts); we display
// a countdown against the backend's default window.
const retentionDays = DEFAULT_RETENTION_DAYS

function purgeCountdown(deleteDate: number) {
  return daysUntilPurge(deleteDate, retentionDays)
}

const restoreMutation = useMutation({
  mutationFn: (id: string) => restoreDocument(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    queryClient.invalidateQueries({ queryKey: ['documents'] })
    toast.add({ severity: 'success', summary: t('ui.document_restored'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.failed_restore'), life: 3000 })
  },
})

const permanentDeleteMutation = useMutation({
  mutationFn: (id: string) => permanentDeleteDocument(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    toast.add({ severity: 'success', summary: t('ui.document_deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.failed_delete_permanently'), life: 3000 })
  },
})

const emptyTrashMutation = useMutation({
  mutationFn: () => emptyTrash(),
  onSuccess: (res) => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    toast.add({ severity: 'success', summary: t('ui.documents_deleted', { count: res.data.deleted_count }), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.failed_empty_trash'), life: 3000 })
  },
})

function doRestore(doc: TrashItem) {
  restoreMutation.mutate(doc.id)
}

function confirmPermanentDelete(doc: TrashItem) {
  confirm.require({
    message: t('ui.permanently_delete_confirm', { title: doc.title }),
    header: t('ui.permanently_delete'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: () => permanentDeleteMutation.mutate(doc.id),
  })
}

function confirmEmptyTrash() {
  confirm.require({
    message: t('ui.empty_trash_confirm'),
    header: t('ui.empty_trash'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: () => emptyTrashMutation.mutate(),
  })
}

function formatDeletedAt(ts: number) {
  return new Date(ts).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

</script>

<template>
  <div class="trash-page">
    <div class="page-header">
      <div>
        <h1>{{ t('ui.trash') }}</h1>
        <p class="page-subtitle" v-if="totalCount">
          {{ t('ui.trash_count', { count: totalCount }) }}
        </p>
      </div>
      <Button
        v-if="documents.length"
        :label="t('ui.empty_trash')"
        icon="pi pi-trash"
        severity="danger"
        outlined
        @click="confirmEmptyTrash"
        :loading="emptyTrashMutation.isPending.value"
      />
    </div>

    <div v-if="isLoading" class="loading-area">
      <Skeleton v-for="i in 5" :key="i" height="3rem" class="mb-2" />
    </div>

    <DataTable
      v-else-if="documents.length"
      :value="documents"
      :totalRecords="totalCount"
      :rows="PAGE_SIZE"
      :first="pageOffset"
      lazy
      paginator
      stripedRows
      :rowHover="true"
      dataKey="id"
      class="trash-table"
      @page="onPage"
    >
      <Column field="title" :header="t('document.title')">
        <template #body="{ data }">
          <span class="doc-title">{{ data.title }}</span>
        </template>
      </Column>
      <Column :header="t('ui.trash_deleted')" style="width: 180px">
        <template #body="{ data }">
          <span class="doc-meta">{{ formatDeletedAt(data.delete_date) }}</span>
        </template>
      </Column>
      <Column :header="t('ui.trash_purges_in')" style="width: 160px">
        <template #body="{ data }">
          <Tag
            :severity="purgeCountdown(data.delete_date) <= 3 ? 'warn' : 'secondary'"
            :value="purgeCountdown(data.delete_date) === 0
              ? t('ui.trash_purges_soon')
              : t('ui.trash_purges_in_days', purgeCountdown(data.delete_date))"
          />
        </template>
      </Column>
      <Column :header="t('ui.actions')" style="width: 200px">
        <template #body="{ data }">
          <div class="action-buttons">
            <Button
              icon="pi pi-replay"
              :label="t('ui.restore')"
              text
              size="small"
              @click="doRestore(data)"
              :loading="restoreMutation.isPending.value && restoreMutation.variables.value === data.id"
            />
            <Button
              icon="pi pi-times"
              :label="t('delete')"
              text
              size="small"
              severity="danger"
              @click="confirmPermanentDelete(data)"
              :loading="permanentDeleteMutation.isPending.value && permanentDeleteMutation.variables.value === data.id"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <ErrorState v-else-if="isError" @retry="refetch()" />

    <EmptyState v-else icon="pi pi-trash" :message="t('ui.trash_empty')" />
  </div>
</template>

<style scoped>
.trash-page {
  padding: 1.5rem;
  max-width: 1100px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}
.page-subtitle {
  margin: 0.2rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.loading-area {
  padding: 1rem 0;
}

.doc-title {
  font-weight: 500;
}

.doc-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.action-buttons {
  display: flex;
  gap: 0.25rem;
}

@media (max-width: 768px) {
  .trash-page {
    padding: 1rem;
  }
}
</style>
