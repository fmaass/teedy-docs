<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery } from '@tanstack/vue-query'
import api from '../../api/client'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Select from 'primevue/select'
import Button from 'primevue/button'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import { injectDocument } from './documentKey'
import { activityTypeLabel, mergeAuditRows, observedTypes, reconcileSelection } from '../../utils/activityLog'

const { t } = useI18n()
const doc = injectDocument()

interface AuditEntry {
  // Stable unique row id (LOG_ID_C). Retained so "load older" can (a) form the keyset
  // cursor with create_date and (b) dedupe on append.
  id: string
  create_date: number
  username: string
  type: string
  // Serialized via JsonUtil.nullable (AuditLogResource): a legacy row with a null LOG_MESSAGE_C
  // arrives as JSON null, so the Action cell must fall back rather than render blank.
  message: string | null
}

// Page size for the first load and every "load older" fetch. Matches the backend default.
const PAGE_SIZE = 20

const docId = computed(() => doc.value?.id)

interface AuditPage {
  logs: AuditEntry[]
  hasMore: boolean
}

// First page: newest PAGE_SIZE rows, no cursor. The response carries has_more so the view
// knows whether an older page exists before the user asks for it.
const { data, isLoading: loading, isError, refetch } = useQuery({
  queryKey: computed(() => ['auditlog', docId.value]),
  queryFn: () =>
    api
      .get('/auditlog', { params: { document: docId.value, limit: PAGE_SIZE } })
      .then((r) => ({ logs: (r.data.logs || []) as AuditEntry[], hasMore: !!r.data.has_more } as AuditPage)),
  enabled: computed(() => !!docId.value),
})

// The accumulated, displayed set. Seeded from the first page and grown by "load older"
// (never replaced), so every loaded page stays visible and the client-side type filter
// operates across ALL of them. A DataTable rebound per page would instead throw away the
// earlier pages (and the filter would only ever see the last one).
const rows = ref<AuditEntry[]>([])
const hasMore = ref(false)
const loadingMore = ref(false)

// Reseed whenever the first page (re)loads — a document switch or a refetch past staleTime.
// This resets the accumulation so a new document never shows the previous one's rows.
watch(
  data,
  (page) => {
    rows.value = page ? page.logs.slice() : []
    hasMore.value = page ? page.hasMore : false
    // A document switch reseeds the accumulation; drop any in-flight "load older" state so a late
    // response for the previous document cannot leave the spinner stuck (see loadOlder).
    loadingMore.value = false
  },
  { immediate: true },
)

// Client-side event-type filter. Options are the DISTINCT types observed in the ACCUMULATED
// rows (never a fixed enum) — a type that never occurred for this document is never offered.
// The selection is purely local: it does NOT enter the query key and adds no /auditlog params.
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

// Fetch the next older page using the oldest ACCUMULATED row (unfiltered) as the keyset
// cursor, then APPEND (deduped by id). Paging the raw stream — not the filtered view —
// keeps "load older" independent of the client-side type filter.
async function loadOlder() {
  if (loadingMore.value || !rows.value.length) return
  const oldest = rows.value[rows.value.length - 1]
  // Bind this fetch to the document it was issued for. If the user switches documents while it is
  // in flight, its response must be discarded — otherwise the previous document's older rows would
  // append to (and its has_more overwrite) the newly selected document (#139).
  const requestedDocId = docId.value
  loadingMore.value = true
  try {
    const r = await api.get('/auditlog', {
      params: {
        document: requestedDocId,
        limit: PAGE_SIZE,
        before_date: oldest.create_date,
        before_id: oldest.id,
      },
    })
    if (docId.value !== requestedDocId) return
    rows.value = mergeAuditRows(rows.value, (r.data.logs || []) as AuditEntry[])
    hasMore.value = !!r.data.has_more
  } finally {
    if (docId.value === requestedDocId) loadingMore.value = false
  }
}

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
      <Column field="message" :header="t('ui.action')">
        <template #body="{ data }">
          <!-- A legacy audit row can have a null (or empty) message; show a neutral placeholder
               instead of a blank Action cell. Not the file displayName helper — an audit message
               is not a file name. -->
          <span class="activity-message">{{ data.message || '—' }}</span>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-history" :message="t('ui.no_activity')" />
      </template>
    </DataTable>
    <div v-if="hasMore" class="activity-load-more">
      <Button
        class="activity-load-older"
        :label="t('ui.activity.load_older')"
        icon="pi pi-history"
        size="small"
        text
        :loading="loadingMore"
        @click="loadOlder"
      />
    </div>
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

.activity-load-more {
  display: flex;
  justify-content: center;
  margin-top: 0.75rem;
}
</style>
