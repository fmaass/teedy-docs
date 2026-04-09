<script setup lang="ts">
import { computed, inject, type Ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { type DocumentDetail } from '../../api/document'
import api from '../../api/client'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import EmptyState from '../../components/EmptyState.vue'

const doc = inject<Ref<DocumentDetail | null>>('document')!

interface AuditEntry {
  create_date: number
  username: string
  type: string
  message: string
}

const docId = computed(() => doc.value?.id)

const { data: logs, isLoading: loading } = useQuery({
  queryKey: computed(() => ['auditlog', docId.value]),
  queryFn: () => api.get('/auditlog', { params: { document: docId.value } }).then((r) => (r.data.logs || []) as AuditEntry[]),
  enabled: computed(() => !!docId.value),
})

function formatDate(ts: number) {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div>
    <DataTable :value="logs ?? []" :loading="loading" size="small" stripedRows>
      <Column header="Date" style="width: 180px">
        <template #body="{ data }">
          <span class="text-xs">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column field="username" header="User" style="width: 120px" />
      <Column field="message" header="Action" />
      <template #empty>
        <EmptyState icon="pi pi-history" message="No activity recorded" />
      </template>
    </DataTable>
  </div>
</template>
