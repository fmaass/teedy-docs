<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery } from '@tanstack/vue-query'
import api from '../../api/client'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import { injectDocument } from './documentKey'

const { t } = useI18n()
const doc = injectDocument()

interface AuditEntry {
  create_date: number
  username: string
  type: string
  message: string
}

const docId = computed(() => doc.value?.id)

const { data: logs, isLoading: loading, isError, refetch } = useQuery({
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
      <Column :header="t('ui.date')" style="width: 180px">
        <template #body="{ data }">
          <span class="text-xs">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column field="username" :header="t('ui.user')" style="width: 120px" />
      <Column field="message" :header="t('ui.action')" />
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-history" :message="t('ui.no_activity')" />
      </template>
    </DataTable>
  </div>
</template>
