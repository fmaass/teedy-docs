<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { listTrash, restoreDocument, permanentDeleteDocument, emptyTrash, type TrashItem } from '../../api/document'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import EmptyState from '../../components/EmptyState.vue'

const toast = useToast()
const queryClient = useQueryClient()

const { data: trashData, isLoading } = useQuery({
  queryKey: ['trash'],
  queryFn: () => listTrash({ limit: 200 }).then((r) => r.data),
})

const documents = computed(() => trashData.value?.documents ?? [])
const totalCount = computed(() => trashData.value?.total ?? 0)

const confirmDialog = ref(false)
const confirmAction = ref<'permanent' | 'empty'>('permanent')
const confirmDocId = ref('')
const confirmDocTitle = ref('')

const restoreMutation = useMutation({
  mutationFn: (id: string) => restoreDocument(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    queryClient.invalidateQueries({ queryKey: ['documents'] })
    toast.add({ severity: 'success', summary: 'Document restored', life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to restore document', life: 3000 })
  },
})

const permanentDeleteMutation = useMutation({
  mutationFn: (id: string) => permanentDeleteDocument(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    toast.add({ severity: 'success', summary: 'Document permanently deleted', life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to delete document', life: 3000 })
  },
})

const emptyTrashMutation = useMutation({
  mutationFn: () => emptyTrash(),
  onSuccess: (res) => {
    queryClient.invalidateQueries({ queryKey: ['trash'] })
    toast.add({ severity: 'success', summary: `${res.data.deleted_count} document(s) permanently deleted`, life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to empty trash', life: 3000 })
  },
})

function doRestore(doc: TrashItem) {
  restoreMutation.mutate(doc.id)
}

function confirmPermanentDelete(doc: TrashItem) {
  confirmAction.value = 'permanent'
  confirmDocId.value = doc.id
  confirmDocTitle.value = doc.title
  confirmDialog.value = true
}

function confirmEmptyTrash() {
  confirmAction.value = 'empty'
  confirmDialog.value = true
}

function executeConfirmed() {
  confirmDialog.value = false
  if (confirmAction.value === 'permanent') {
    permanentDeleteMutation.mutate(confirmDocId.value)
  } else {
    emptyTrashMutation.mutate()
  }
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
        <h1>Trash</h1>
        <p class="page-subtitle" v-if="totalCount">
          {{ totalCount }} document{{ totalCount !== 1 ? 's' : '' }} in trash
        </p>
      </div>
      <Button
        v-if="documents.length"
        label="Empty trash"
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
      stripedRows
      :rowHover="true"
      class="trash-table"
    >
      <Column field="title" header="Title" sortable>
        <template #body="{ data }">
          <span class="doc-title">{{ data.title }}</span>
        </template>
      </Column>
      <Column header="Deleted" style="width: 180px" sortable sortField="delete_date">
        <template #body="{ data }">
          <span class="doc-meta">{{ formatDeletedAt(data.delete_date) }}</span>
        </template>
      </Column>
      <Column header="Actions" style="width: 200px">
        <template #body="{ data }">
          <div class="action-buttons">
            <Button
              icon="pi pi-replay"
              label="Restore"
              text
              size="small"
              @click="doRestore(data)"
              :loading="restoreMutation.isPending.value"
            />
            <Button
              icon="pi pi-times"
              label="Delete"
              text
              size="small"
              severity="danger"
              @click="confirmPermanentDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <EmptyState v-else icon="pi pi-trash" message="Trash is empty" />

    <Dialog
      v-model:visible="confirmDialog"
      :header="confirmAction === 'empty' ? 'Empty trash' : 'Permanently delete'"
      :modal="true"
      :style="{ width: '400px' }"
    >
      <p v-if="confirmAction === 'empty'">
        This will permanently delete all documents in the trash. This action cannot be undone.
      </p>
      <p v-else>
        Permanently delete "{{ confirmDocTitle }}"? This action cannot be undone.
      </p>
      <template #footer>
        <Button label="Cancel" text @click="confirmDialog = false" />
        <Button label="Delete permanently" severity="danger" @click="executeConfirmed" />
      </template>
    </Dialog>
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
