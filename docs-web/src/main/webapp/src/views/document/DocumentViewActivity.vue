<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery } from '@tanstack/vue-query'
import api from '../../api/client'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Select from 'primevue/select'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import { injectDocument } from './documentKey'
import { activityTypeLabel, observedTypes, reconcileSelection } from '../../utils/activityLog'

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

const rows = computed<AuditEntry[]>(() => logs.value ?? [])

// Client-side event-type filter. Options are the DISTINCT types observed in the
// loaded rows (never a fixed enum) — a type that never occurred for this document is
// never offered. The selection is purely local: it does NOT enter the query key and
// adds no /auditlog params.
//
// `selectedType` keeps null = "all rows" semantics for the filtering and reconcile
// logic. The dropdown additionally offers an explicit "All types" entry so a keyboard
// user can return to the unfiltered state without a mouse — its sentinel value bridges
// to that same null state (a real string value avoids PrimeVue's null-option pitfall
// and stays fully keyboard-selectable).
const ALL_TYPES = '__all__'
const selectedType = ref<string | null>(null)

const selectedOption = computed<string>({
  get: () => selectedType.value ?? ALL_TYPES,
  set: (value) => {
    selectedType.value = value === ALL_TYPES ? null : value
  },
})

const observed = computed(() => observedTypes(rows.value))

const typeOptions = computed(() => [
  { value: ALL_TYPES, label: t('ui.activity.filter_all_types') },
  ...observed.value.map((type) => ({ value: type, label: activityTypeLabel(type, t) })),
])

// A stale selection (its type no longer present after a document switch / refetch)
// auto-clears so the table can never render false-empty behind a dead filter.
watch(observed, (types) => {
  selectedType.value = reconcileSelection(selectedType.value, types)
})

const visibleRows = computed(() =>
  selectedType.value ? rows.value.filter((row) => row.type === selectedType.value) : rows.value,
)

function formatDate(ts: number) {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div>
    <div v-if="observed.length" class="activity-toolbar">
      <Select
        v-model="selectedOption"
        :options="typeOptions"
        optionLabel="label"
        optionValue="value"
        :aria-label="t('ui.activity.filter_label')"
        size="small"
        class="activity-type-filter"
      />
    </div>
    <DataTable :value="visibleRows" :loading="loading" size="small" stripedRows>
      <Column :header="t('ui.date')" style="width: 180px">
        <template #body="{ data }">
          <span class="activity-date">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column field="username" :header="t('ui.user')" style="width: 120px" />
      <Column :header="t('ui.activity.type')" style="width: 140px">
        <template #body="{ data }">
          <span class="activity-type">{{ activityTypeLabel(data.type, t) }}</span>
        </template>
      </Column>
      <Column field="message" :header="t('ui.action')" />
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-history" :message="t('ui.no_activity')" />
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.activity-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}

.activity-type-filter {
  min-width: 12rem;
}
</style>
