<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
// Imported rather than globally resolved: Vue emits the component lookup at the TOP of the render
// function, before the v-if that guards it, so a globally-resolved RouterLink warns on every
// render in document mode (where no link is ever rendered) and in any test without a router.
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import api from '../api/client'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Select from 'primevue/select'
import InputText from 'primevue/inputtext'
import DatePicker from 'primevue/datepicker'
import Button from 'primevue/button'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'
import { activityTypeLabel, mergeAuditRows, observedTypes, reconcileSelection } from '../utils/activityLog'
import { activityTargetLabel, resolveActivityLink } from '../utils/activityLink'
import {
  activityClassLabel,
  FILTERABLE_ACTIVITY_CLASSES,
  FILTERABLE_ACTIVITY_TYPES,
} from '../utils/activityClasses'

// Shared audit-log table (#177), extracted from DocumentViewActivity so the per-document
// Activity tab and the global /history view are the SAME component in two modes:
//
//   documentId set  -> document scope; client-side type filter over the ACCUMULATED rows
//                      (the behaviour #113/#139 shipped, preserved exactly)
//   documentId null -> global scope; SERVER-side filters (type/class/user/after_date), which
//                      the backend AND-composes into the caller's authorization scope
//
// Paging is the keyset cursor from #139 in both modes.

const props = withDefaults(
  defineProps<{
    // Which feed this table shows. Explicit rather than inferred from documentId, because a
    // document-scoped table must NOT fire its first request before its id is known, while the
    // global one has nothing to wait for — "documentId is still undefined" and "there is no
    // document" are different states that a null check alone cannot tell apart.
    scope?: 'document' | 'global'
    // Document scope id; only meaningful when scope === 'document'.
    documentId?: string | null
    // Offer the server-side filter bar. Only the global view uses it: a server filter changes the
    // REQUEST, so it is part of the request identity below.
    serverFilters?: boolean
    // Resolve targets to links. Independent of the class column below: the document tab wants
    // clickable File rows without an entity column (every row there is the same document).
    linkTargets?: boolean
    // Show the entity-class column. Only the global feed needs it — it mixes entity types.
    showEntityClass?: boolean
    // Gates the admin-only link destinations (see resolveActivityLink).
    isAdmin?: boolean
    emptyMessage?: string
  }>(),
  {
    scope: 'document',
    documentId: null,
    serverFilters: false,
    linkTargets: false,
    showEntityClass: false,
    isAdmin: false,
  },
)

const { t } = useI18n()

interface AuditEntry {
  // Stable unique row id (LOG_ID_C). Retained so "load older" can (a) form the keyset
  // cursor with create_date and (b) dedupe on append.
  id: string
  create_date: number
  username: string
  type: string
  class: string
  target: string
  // Serialized via JsonUtil.nullable (AuditLogResource): a legacy row with a null LOG_MESSAGE_C
  // arrives as JSON null, so the Action cell must fall back rather than render blank.
  message: string | null
}

// Page size for the first load and every "load older" fetch. Matches the backend default.
const PAGE_SIZE = 20

// --- server-side filters (global view only) ---------------------------------
// Held as a single object so the request identity below is one value to compare.
const ALL = '__all__'
const filterType = ref<string>(ALL)
const filterClass = ref<string>(ALL)
const filterAfter = ref<Date | null>(null)
// The username filter is a free-text field, so it is held as a DRAFT that is committed on change
// (blur or Enter) rather than bound straight to the request. Binding the input itself would make
// every keystroke a new request identity — a fetch per character, each one superseding the last.
const filterUserDraft = ref<string>('')
const filterUser = ref<string>('')

function commitUserFilter() {
  filterUser.value = filterUserDraft.value.trim()
}

// The filters that actually reach the server, as query params. Only ACTIVE ones appear, so a
// request with no filters carries exactly the params the document view has always sent.
const serverFilterParams = computed<Record<string, string | number>>(() => {
  if (!props.serverFilters) return {}
  const params: Record<string, string | number> = {}
  if (filterType.value !== ALL) params.type = filterType.value
  if (filterClass.value !== ALL) params.class = filterClass.value
  if (filterUser.value) params.user = filterUser.value
  if (filterAfter.value) {
    // Local start-of-day: the user picked a calendar date, not an instant.
    const day = new Date(filterAfter.value)
    day.setHours(0, 0, 0, 0)
    params.after_date = day.getTime()
  }
  return params
})

const hasActiveFilters = computed(() => Object.keys(serverFilterParams.value).length > 0)

function clearFilters() {
  filterType.value = ALL
  filterClass.value = ALL
  filterUserDraft.value = ''
  filterUser.value = ''
  filterAfter.value = null
}

// IDENTITY of the request stream: the scope PLUS every active server filter. #139 bound an
// in-flight "load older" to the document it was issued for; with server-side filters the
// document alone is no longer enough — changing a filter starts a DIFFERENT stream, and a late
// response from the previous one must be discarded just as a previous document's was. This
// value is both the query key and the staleness token for loadOlder.
const requestIdentity = computed(() =>
  JSON.stringify({ document: props.documentId ?? null, ...serverFilterParams.value }),
)

function requestParams(cursor?: { before_date: number; before_id: string }) {
  const params: Record<string, unknown> = {}
  if (props.documentId) params.document = props.documentId
  params.limit = PAGE_SIZE
  Object.assign(params, serverFilterParams.value)
  if (cursor) Object.assign(params, cursor)
  return params
}

interface AuditPage {
  logs: AuditEntry[]
  hasMore: boolean
}

// First page: newest PAGE_SIZE rows, no cursor. The response carries has_more so the view
// knows whether an older page exists before the user asks for it.
const { data, isLoading: loading, isError, refetch } = useQuery({
  queryKey: computed(() => ['auditlog', requestIdentity.value]),
  queryFn: () =>
    api
      .get('/auditlog', { params: requestParams() })
      .then((r) => ({ logs: (r.data.logs || []) as AuditEntry[], hasMore: !!r.data.has_more } as AuditPage)),
  // The document view must not fire before its document is known; the global view has no such
  // dependency and is always enabled.
  enabled: computed(() => props.scope === 'global' || !!props.documentId),
})

// The accumulated, displayed set. Seeded from the first page and grown by "load older"
// (never replaced), so every loaded page stays visible and the client-side type filter
// operates across ALL of them. A DataTable rebound per page would instead throw away the
// earlier pages (and the filter would only ever see the last one).
const rows = ref<AuditEntry[]>([])
const hasMore = ref(false)
const loadingMore = ref(false)

// Reseed whenever the first page (re)loads — a document switch, a server-filter change, or a
// refetch past staleTime. This resets the accumulation so a new stream never shows the
// previous one's rows.
watch(
  data,
  (page) => {
    rows.value = page ? page.logs.slice() : []
    hasMore.value = page ? page.hasMore : false
    // A new stream reseeds the accumulation; drop any in-flight "load older" state so a late
    // response for the previous one cannot leave the spinner stuck (see loadOlder).
    loadingMore.value = false
  },
  { immediate: true },
)

// Client-side event-type filter (document view only). Options are the DISTINCT types observed
// in the ACCUMULATED rows (never a fixed enum) — a type that never occurred for this document is
// never offered. The selection is purely local: it does NOT enter the query key and adds no
// /auditlog params. The global view filters server-side instead, so this bar is hidden there.
const selectedType = ref<string | null>(null)

const selectedOption = computed<string>({
  get: () => selectedType.value ?? ALL,
  set: (value) => {
    selectedType.value = value === ALL ? null : value
  },
})

const observed = computed(() => observedTypes(rows.value))

const typeOptions = computed(() => [
  { value: ALL, label: t('ui.activity.filter_all_types') },
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

// Server-filter option sets are FIXED enums (unlike the client filter's observed set): the
// server can only be asked for values it knows, and a value absent from the current page is
// exactly what the user needs to filter TO.
const serverTypeOptions = computed(() => [
  { value: ALL, label: t('ui.activity.filter_all_types') },
  ...FILTERABLE_ACTIVITY_TYPES.map((type) => ({ value: type, label: activityTypeLabel(type, t) })),
])

const serverClassOptions = computed(() => [
  { value: ALL, label: t('ui.history.filter_all_classes') },
  ...FILTERABLE_ACTIVITY_CLASSES.map((cls) => ({ value: cls, label: activityClassLabel(cls, t) })),
])

// Fetch the next older page using the oldest ACCUMULATED row (unfiltered) as the keyset
// cursor, then APPEND (deduped by id). Paging the raw stream — not the client-filtered view —
// keeps "load older" independent of the client-side type filter.
async function loadOlder() {
  if (loadingMore.value || !rows.value.length) return
  const oldest = rows.value[rows.value.length - 1]
  // Bind this fetch to the STREAM it was issued for — the scope plus the active server filters.
  // If either changes while it is in flight, its response must be discarded: otherwise the
  // previous stream's older rows would append to (and its has_more overwrite) the new one (#139,
  // generalized for #177's server-side filters).
  const issuedFor = requestIdentity.value
  loadingMore.value = true
  try {
    const r = await api.get('/auditlog', {
      params: requestParams({ before_date: oldest.create_date, before_id: oldest.id }),
    })
    if (requestIdentity.value !== issuedFor) return
    rows.value = mergeAuditRows(rows.value, (r.data.logs || []) as AuditEntry[])
    hasMore.value = !!r.data.has_more
  } finally {
    if (requestIdentity.value === issuedFor) loadingMore.value = false
  }
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleString()
}

function targetLink(row: AuditEntry) {
  return resolveActivityLink(row, { isAdmin: props.isAdmin })
}

function targetLabel(row: AuditEntry) {
  return activityTargetLabel(row, t('ui.history.open'))
}
</script>

<template>
  <div>
    <div v-if="serverFilters" class="activity-filters">
      <Select
        v-model="filterType"
        :options="serverTypeOptions"
        optionLabel="label"
        optionValue="value"
        :aria-label="t('ui.activity.filter_label')"
        size="small"
        class="activity-filter-field history-filter-type"
      />
      <Select
        v-model="filterClass"
        :options="serverClassOptions"
        optionLabel="label"
        optionValue="value"
        :aria-label="t('ui.history.filter_class')"
        size="small"
        class="activity-filter-field history-filter-class"
      />
      <InputText
        v-model="filterUserDraft"
        :placeholder="t('ui.history.filter_user')"
        :aria-label="t('ui.history.filter_user')"
        size="small"
        class="activity-filter-field history-filter-user"
        @change="commitUserFilter"
        @keyup.enter="commitUserFilter"
      />
      <DatePicker
        v-model="filterAfter"
        showIcon
        iconDisplay="input"
        dateFormat="yy-mm-dd"
        :placeholder="t('ui.history.filter_after')"
        :aria-label="t('ui.history.filter_after')"
        size="small"
        class="activity-filter-field history-filter-after"
      />
      <Button
        v-if="hasActiveFilters"
        class="history-clear-filters"
        :label="t('ui.history.clear_filters')"
        icon="pi pi-filter-slash"
        size="small"
        text
        @click="clearFilters"
      />
    </div>

    <div v-if="!serverFilters && observed.length" class="activity-toolbar">
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
      <Column v-if="showEntityClass" :header="t('ui.history.entity')" style="width: 140px">
        <template #body="{ data }">
          <span class="activity-class">{{ activityClassLabel(data.class, t) }}</span>
        </template>
      </Column>
      <Column field="message" :header="t('ui.action')">
        <template #body="{ data }">
          <!-- The LABEL is unconditional (#195). A raw audit message is not display text: a File
               row's message is the 36-char parent-document id CONCATENATED with the file name, and
               a Comment/Route row's message is a bare document id. Rendering it verbatim leaked
               "<uuid>Sachspende.xml" into the document Activity tab. activityTargetLabel is a
               no-op for the classes whose message IS human-readable (Document, Tag, Acl…), and it
               keeps the neutral placeholder for a legacy row with a null message. Only the LINK is
               a per-mode choice. -->
          <RouterLink v-if="linkTargets && targetLink(data)" class="activity-target-link" :to="targetLink(data)!">
            {{ targetLabel(data) }}
          </RouterLink>
          <span v-else class="activity-message">{{ targetLabel(data) }}</span>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-history" :message="emptyMessage || t('ui.no_activity')" />
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

.activity-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

/* Each filter holds a readable width but may wrap to the next line on a narrow
   viewport rather than squeezing its neighbours (the mobile bar lesson from #67). */
.activity-filter-field {
  flex: 1 1 11rem;
  min-width: 11rem;
  max-width: 16rem;
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
