<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { getLogs, LOG_LEVELS } from '../../api/app'
import DataTable, { type DataTablePageEvent } from 'primevue/datatable'
import Column from 'primevue/column'
import Select from 'primevue/select'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()

const PAGE_SIZE = 25
const pageOffset = ref(0)

// The three server-side filters. `level` is a MINIMUM level (empty = all levels);
// tag/message are free-text substring filters applied server-side.
const level = ref<string | undefined>(undefined)
const tag = ref('')
const message = ref('')

// `applied` is the snapshot the query actually runs on. The filter inputs stay
// uncommitted until the user hits Apply (or Enter), so typing does not fire a
// request per keystroke. Applying also resets to the first page.
const applied = ref<{ level?: string; tag?: string; message?: string }>({})

const levelOptions = LOG_LEVELS.map((l) => ({ label: l, value: l }))

const { data, isLoading, isFetching, isError, refetch } = useQuery({
  queryKey: computed(() => ['app-logs', { ...applied.value, offset: pageOffset.value }]),
  queryFn: () =>
    getLogs({
      level: applied.value.level,
      tag: applied.value.tag,
      message: applied.value.message,
      limit: PAGE_SIZE,
      offset: pageOffset.value,
    }),
  placeholderData: keepPreviousData,
})

const logs = computed(() => data.value?.logs ?? [])
const totalCount = computed(() => data.value?.total ?? 0)

function applyFilters() {
  applied.value = {
    level: level.value || undefined,
    tag: tag.value.trim() || undefined,
    message: message.value.trim() || undefined,
  }
  pageOffset.value = 0
}

function clearFilters() {
  level.value = undefined
  tag.value = ''
  message.value = ''
  applied.value = {}
  pageOffset.value = 0
}

function onPage(event: DataTablePageEvent) {
  pageOffset.value = event.first
}

async function onRefresh() {
  try {
    await refetch()
  } catch {
    toast.add({ severity: 'error', summary: t('ui.monitoring.refresh_failed'), life: 3000 })
  }
}

// Log-level → PrimeVue Tag severity, so the level column reads at a glance.
function levelSeverity(lvl: string): 'danger' | 'warn' | 'info' | 'secondary' {
  switch (lvl) {
    case 'FATAL':
    case 'ERROR':
      return 'danger'
    case 'WARN':
      return 'warn'
    case 'INFO':
      return 'info'
    default:
      return 'secondary'
  }
}

function formatTimestamp(ts: number): string {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="monitoring-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.monitoring.title') }}</h2>
        <p class="section-desc">{{ t('ui.monitoring.description') }}</p>
      </div>
      <Button
        :label="t('ui.monitoring.refresh')"
        icon="pi pi-refresh"
        size="small"
        :loading="isFetching"
        @click="onRefresh"
      />
    </div>

    <!-- Filters -->
    <div class="log-filters">
      <div class="filter-field">
        <label for="log-level">{{ t('ui.monitoring.level') }}</label>
        <Select
          v-model="level"
          inputId="log-level"
          :options="levelOptions"
          optionLabel="label"
          optionValue="value"
          :placeholder="t('ui.monitoring.level_all')"
          showClear
          class="w-full"
        />
      </div>
      <div class="filter-field">
        <label for="log-tag">{{ t('ui.monitoring.tag') }}</label>
        <InputText
          id="log-tag"
          v-model="tag"
          :placeholder="t('ui.monitoring.tag_placeholder')"
          class="w-full"
          @keyup.enter="applyFilters"
        />
      </div>
      <div class="filter-field">
        <label for="log-message">{{ t('ui.monitoring.message') }}</label>
        <InputText
          id="log-message"
          v-model="message"
          :placeholder="t('ui.monitoring.message_placeholder')"
          class="w-full"
          @keyup.enter="applyFilters"
        />
      </div>
      <div class="filter-actions">
        <Button :label="t('ui.monitoring.apply')" icon="pi pi-filter" size="small" @click="applyFilters" />
        <Button :label="t('ui.monitoring.clear')" severity="secondary" text size="small" @click="clearFilters" />
      </div>
    </div>

    <p class="log-total">{{ t('ui.monitoring.total_count', { count: totalCount }) }}</p>

    <DataTable
      :value="logs"
      stripedRows
      :loading="isLoading"
      lazy
      paginator
      :rows="PAGE_SIZE"
      :first="pageOffset"
      :totalRecords="totalCount"
      class="log-table"
      @page="onPage"
    >
      <Column :header="t('ui.monitoring.time')" style="width: 180px">
        <template #body="{ data: row }">
          <span class="log-time">{{ formatTimestamp(row.date) }}</span>
        </template>
      </Column>
      <Column :header="t('ui.monitoring.level')" style="width: 90px">
        <template #body="{ data: row }">
          <Tag :value="row.level" :severity="levelSeverity(row.level)" />
        </template>
      </Column>
      <Column :header="t('ui.monitoring.tag')" style="width: 200px">
        <template #body="{ data: row }">
          <code class="log-tag">{{ row.tag }}</code>
        </template>
      </Column>
      <Column :header="t('ui.monitoring.message')">
        <template #body="{ data: row }">
          <span class="log-message">{{ row.message }}</span>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-list" :message="t('ui.monitoring.no_logs')" />
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.monitoring-settings {
  max-width: 960px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.25rem;
}
.section-header h2 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}
.section-desc {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.log-filters {
  display: grid;
  grid-template-columns: 160px 1fr 1fr auto;
  gap: 0.75rem;
  align-items: end;
  margin-bottom: 1rem;
}
.filter-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.filter-field label {
  font-size: 0.8125rem;
  font-weight: 500;
}
.filter-actions {
  display: flex;
  gap: 0.5rem;
}
.w-full {
  width: 100%;
}

.log-total {
  margin: 0 0 0.5rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.log-time {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  white-space: nowrap;
}
.log-tag {
  font-family: monospace;
  font-size: 0.75rem;
  word-break: break-all;
}
.log-message {
  font-size: 0.8125rem;
  word-break: break-word;
}

@media (max-width: 720px) {
  .log-filters {
    grid-template-columns: 1fr;
  }
  .filter-actions {
    justify-content: flex-end;
  }
}
</style>
