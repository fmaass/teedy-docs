<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { getAppStats, STATS_WINDOWS, type StatsWindow, type StatsSeriesPoint } from '../../api/app'
import { formatStorage } from '../../utils/formatters'
import Chart from 'primevue/chart'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import SelectButton from 'primevue/selectbutton'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t, locale } = useI18n()

// Manual-refetch window selector (no auto-refresh): the query key includes the window, so
// switching windows refetches once; the Refresh button re-runs the current window.
const windowOptions = computed(() =>
  STATS_WINDOWS.map((days) => ({ label: t('ui.stats.window_days', { days }), value: days })),
)
const selectedWindow = ref<StatsWindow>(7)

const { data, isLoading, isFetching, isError, refetch } = useQuery({
  queryKey: computed(() => ['app-stats', selectedWindow.value]),
  queryFn: () => getAppStats(selectedWindow.value),
  placeholderData: keepPreviousData,
})

const totals = computed(() => data.value?.totals)
const perUser = computed(() => data.value?.storage.per_user ?? [])
const globalStorage = computed(() => data.value?.storage.global ?? 0)

const totalCards = computed(() => {
  const value = totals.value
  if (!value) return []
  return [
    { key: 'documents', icon: 'pi pi-file', label: t('ui.stats.total_documents'), value: value.documents },
    { key: 'files', icon: 'pi pi-copy', label: t('ui.stats.total_files'), value: value.files },
    { key: 'users', icon: 'pi pi-users', label: t('ui.stats.total_users'), value: value.users },
    { key: 'tags', icon: 'pi pi-tags', label: t('ui.stats.total_tags'), value: value.tags },
    { key: 'favorites', icon: 'pi pi-star', label: t('ui.stats.total_favorites'), value: value.favorites },
  ]
})

// Read live CSS custom properties so the charts follow the active (light/dark) theme.
function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

// Localised short day label for the X axis (the series dates are UTC yyyy-MM-dd).
function labelForDate(point: StatsSeriesPoint): string {
  const [y, m, d] = point.date.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(locale.value, {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  })
}

const documentsChartData = computed(() => {
  const series = data.value?.series.documents_created ?? []
  const primary = cssVar('--p-primary-color', '#2aabd2')
  return {
    labels: series.map(labelForDate),
    datasets: [
      {
        label: t('ui.stats.chart_documents_created'),
        data: series.map((p) => p.count),
        borderColor: primary,
        backgroundColor: primary,
        tension: 0.3,
        fill: false,
      },
    ],
  }
})

const activityChartData = computed(() => {
  const series = data.value?.series.activity ?? []
  const primary = cssVar('--p-primary-color', '#2aabd2')
  return {
    labels: series.map(labelForDate),
    datasets: [
      {
        label: t('ui.stats.chart_activity'),
        data: series.map((p) => p.count),
        backgroundColor: primary,
        borderColor: primary,
      },
    ],
  }
})

const chartOptions = computed(() => {
  const text = cssVar('--p-text-muted-color', '#6b7280')
  const grid = cssVar('--p-content-border-color', 'rgba(0,0,0,0.1)')
  return {
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: { ticks: { color: text }, grid: { color: grid } },
      y: { beginAtZero: true, ticks: { color: text, precision: 0 }, grid: { color: grid } },
    },
  }
})

// A user's used-fraction of quota, clamped to [0,1]; 0 when quota is unset (0).
function quotaFraction(current: number, quota: number): number {
  if (quota <= 0) return 0
  return Math.min(1, current / quota)
}

const hasData = computed(() => !!data.value)

async function onRefresh() {
  await refetch()
}
</script>

<template>
  <div class="stats-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.stats.title') }}</h2>
        <p class="section-desc">{{ t('ui.stats.description') }}</p>
      </div>
      <div class="header-actions">
        <SelectButton
          v-model="selectedWindow"
          :options="windowOptions"
          optionLabel="label"
          optionValue="value"
          :allowEmpty="false"
          :aria-label="t('ui.stats.window_label')"
        />
        <Button
          :label="t('ui.stats.refresh')"
          icon="pi pi-refresh"
          size="small"
          :loading="isFetching"
          @click="onRefresh"
        />
      </div>
    </div>

    <ErrorState v-if="isError && !hasData" @retry="refetch()" />

    <div v-else-if="isLoading && !hasData" class="stats-loading">
      <ProgressSpinner style="width: 48px; height: 48px" strokeWidth="4" />
    </div>

    <template v-else-if="hasData">
      <!-- Totals -->
      <div class="totals-grid">
        <div v-for="card in totalCards" :key="card.key" class="total-card">
          <i :class="card.icon" class="total-icon" />
          <div class="total-body">
            <span class="total-value">{{ card.value.toLocaleString(locale) }}</span>
            <span class="total-label">{{ card.label }}</span>
          </div>
        </div>
      </div>

      <!-- Charts -->
      <div class="charts-grid">
        <div class="chart-card">
          <h3 class="chart-title">{{ t('ui.stats.chart_documents_created') }}</h3>
          <div class="chart-canvas-wrap">
            <Chart type="line" :data="documentsChartData" :options="chartOptions" />
          </div>
        </div>
        <div class="chart-card">
          <h3 class="chart-title">{{ t('ui.stats.chart_activity') }}</h3>
          <div class="chart-canvas-wrap">
            <Chart type="bar" :data="activityChartData" :options="chartOptions" />
          </div>
        </div>
      </div>

      <!-- Storage -->
      <div class="storage-section">
        <div class="storage-header">
          <h3 class="chart-title">{{ t('ui.stats.storage_title') }}</h3>
          <span class="storage-global">{{ t('ui.stats.storage_global', { value: formatStorage(globalStorage) }) }}</span>
        </div>
        <DataTable v-if="perUser.length" :value="perUser" stripedRows class="storage-table">
          <Column :header="t('ui.stats.storage_user')">
            <template #body="{ data: row }">
              <span>{{ row.username }}</span>
            </template>
          </Column>
          <Column :header="t('ui.stats.storage_used')" style="width: 140px">
            <template #body="{ data: row }">
              <span>{{ formatStorage(row.storage_current) }}</span>
            </template>
          </Column>
          <Column :header="t('ui.stats.storage_quota')" style="width: 220px">
            <template #body="{ data: row }">
              <div class="quota-cell">
                <div class="quota-bar" aria-hidden="true">
                  <div
                    class="quota-fill"
                    :style="{ width: `${quotaFraction(row.storage_current, row.storage_quota) * 100}%` }"
                  />
                </div>
                <span class="quota-text">{{ formatStorage(row.storage_quota) }}</span>
              </div>
            </template>
          </Column>
        </DataTable>
        <EmptyState v-else icon="pi pi-database" :message="t('ui.stats.storage_empty')" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.stats-settings {
  max-width: 1100px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
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
.header-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

.stats-loading {
  display: flex;
  justify-content: center;
  padding: 3rem 0;
}

.totals-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
  margin-bottom: 1.75rem;
}
.total-card {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  padding: 1rem 1.125rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 8px);
  background: var(--p-content-background);
}
.total-icon {
  font-size: 1.5rem;
  color: var(--p-primary-color);
}
.total-body {
  display: flex;
  flex-direction: column;
}
.total-value {
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1.1;
}
.total-label {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 1.25rem;
  margin-bottom: 1.75rem;
}
.chart-card {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 8px);
  padding: 1rem 1.125rem;
  background: var(--p-content-background);
}
.chart-title {
  margin: 0 0 0.75rem;
  font-size: 0.9375rem;
  font-weight: 600;
}
.chart-canvas-wrap {
  position: relative;
  height: 240px;
}

.storage-section {
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 8px);
  padding: 1rem 1.125rem;
  background: var(--p-content-background);
}
.storage-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 1rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.storage-global {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.quota-cell {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}
.quota-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--p-content-border-color);
  overflow: hidden;
}
.quota-fill {
  height: 100%;
  background: var(--p-primary-color);
}
.quota-text {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  white-space: nowrap;
}

@media (max-width: 640px) {
  .section-header {
    flex-direction: column;
  }
}
</style>
