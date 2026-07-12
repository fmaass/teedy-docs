<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFileUrl } from '../api/file'
import { type DocumentListItem } from '../api/document'
import { languageLabel } from '../constants/languages'
import { formatDate } from '../utils/formatters'
import DataTable from 'primevue/datatable'
import type { DataTableSortEvent, DataTableRowClickEvent, DataTableRowSelectEvent } from 'primevue/datatable'
import Column from 'primevue/column'
import TagBadge from './TagBadge.vue'
import TagOverflow from './TagOverflow.vue'
import FavoriteStar from './FavoriteStar.vue'
import { useTagFilterStore } from '../stores/tagFilter'

const { t } = useI18n()
const tagFilter = useTagFilterStore()

interface RowContextMenuEvent {
  data: DocumentListItem
  originalEvent: Event
}

defineProps<{
  documents: DocumentListItem[]
  totalRecords: number
  rows: number
  first: number
  loading?: boolean
  sortField?: string
  sortOrder?: number
  /** When true, show a checkbox column for multi-selection (bound via v-model:selection). */
  selectable?: boolean
}>()

const emit = defineEmits<{
  rowClick: [doc: DocumentListItem]
  rowDblclick: [doc: DocumentListItem]
  rowContextMenu: [event: Event, doc: DocumentListItem]
  sort: [event: DataTableSortEvent]
}>()

/** Multi-selection model, only active when `selectable` is set. */
const selection = defineModel<DocumentListItem[]>('selection', { default: () => [] })

const selectedRow = ref<DocumentListItem | null>(null)

function onRowSelect(event: DataTableRowSelectEvent) {
  emit('rowClick', event.data as DocumentListItem)
}
</script>

<template>
  <DataTable
    v-if="selectable"
    :value="documents"
    :totalRecords="totalRecords"
    :rows="rows"
    :first="first"
    :loading="loading"
    :sortField="sortField"
    :sortOrder="sortOrder"
    v-model:selection="selection"
    :metaKeySelection="false"
    lazy
    stripedRows
    :rowHover="true"
    dataKey="id"
    class="doc-table"
    @row-click="(e: DataTableRowClickEvent) => emit('rowClick', e.data as DocumentListItem)"
    @row-dblclick="(e: DataTableRowClickEvent) => emit('rowDblclick', e.data as DocumentListItem)"
    @row-contextmenu="(e: RowContextMenuEvent) => emit('rowContextMenu', e.originalEvent, e.data)"
    @sort="(e: DataTableSortEvent) => emit('sort', e)"
  >
    <Column selectionMode="multiple" style="width: 44px" :exportable="false" />
    <Column header="" style="width: 44px">
      <template #body="{ data }">
        <div class="doc-thumb">
          <img
            v-if="data.file_id"
            :src="getFileUrl(data.file_id, 'thumb')"
            alt=""
            loading="lazy"
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          />
          <i v-else class="pi pi-file" />
        </div>
      </template>
    </Column>
    <Column header="" style="width: 44px" :exportable="false" class="star-cell">
      <template #body="{ data }">
        <FavoriteStar :document-id="data.id" :favorite="!!data.favorite" @click.stop />
      </template>
    </Column>
    <Column field="title" :header="t('document.title')" sortable>
      <template #body="{ data }">
        <span class="doc-title">{{ data.title }}</span>
        <!-- "Awaiting your action" badge. active_route is target-scoped server-side: it is true only
             when the current route step targets the viewer, so it IS the "awaiting you" signal. -->
        <span
          v-if="data.active_route"
          class="wf-awaiting"
          v-tooltip.top="data.current_step_name || t('ui.workflow.awaiting_you')"
        >
          <i class="pi pi-sitemap" aria-hidden="true" />{{ t('ui.workflow.awaiting_you') }}
        </span>
      </template>
    </Column>
    <Column :header="t('document.tags')" style="width: 200px">
      <template #body="{ data }">
        <div class="doc-tags" v-if="data.tags?.length">
          <TagBadge
            v-for="tag in data.tags.slice(0, 3)"
            :key="tag.id"
            :name="tag.name"
            :color="tag.color"
            clickable
            @select="tagFilter.selectTag(tag.id)"
            @click.stop
            @dblclick.stop
          />
          <TagOverflow v-if="data.tags.length > 3" :tags="data.tags.slice(3)" />
        </div>
      </template>
    </Column>
    <Column :header="t('document.language')" style="width: 100px">
      <template #body="{ data }">
        <span class="doc-lang">{{ languageLabel(data.language) }}</span>
      </template>
    </Column>
    <Column :header="t('ui.files')" style="width: 60px">
      <template #body="{ data }">
        <span class="doc-meta">{{ data.file_count }}</span>
      </template>
    </Column>
    <Column field="create_date" :header="t('document.creation_date')" style="width: 120px" sortable>
      <template #body="{ data }">
        <span class="doc-meta">{{ formatDate(data.create_date) }}</span>
      </template>
    </Column>
  </DataTable>

  <DataTable
    v-else
    :value="documents"
    :totalRecords="totalRecords"
    :rows="rows"
    :first="first"
    :loading="loading"
    :sortField="sortField"
    :sortOrder="sortOrder"
    v-model:selection="selectedRow"
    selectionMode="single"
    :metaKeySelection="false"
    lazy
    stripedRows
    :rowHover="true"
    dataKey="id"
    class="doc-table"
    @row-click="(e: DataTableRowClickEvent) => emit('rowClick', e.data as DocumentListItem)"
    @row-dblclick="(e: DataTableRowClickEvent) => emit('rowDblclick', e.data as DocumentListItem)"
    @row-select="onRowSelect"
    @row-contextmenu="(e: RowContextMenuEvent) => emit('rowContextMenu', e.originalEvent, e.data)"
    @sort="(e: DataTableSortEvent) => emit('sort', e)"
  >
    <Column header="" style="width: 44px">
      <template #body="{ data }">
        <div class="doc-thumb">
          <img
            v-if="data.file_id"
            :src="getFileUrl(data.file_id, 'thumb')"
            alt=""
            loading="lazy"
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          />
          <i v-else class="pi pi-file" />
        </div>
      </template>
    </Column>
    <Column header="" style="width: 44px" :exportable="false" class="star-cell">
      <template #body="{ data }">
        <FavoriteStar :document-id="data.id" :favorite="!!data.favorite" @click.stop />
      </template>
    </Column>
    <Column field="title" :header="t('document.title')" sortable>
      <template #body="{ data }">
        <span class="doc-title">{{ data.title }}</span>
        <!-- "Awaiting your action" badge. active_route is target-scoped server-side: it is true only
             when the current route step targets the viewer, so it IS the "awaiting you" signal. -->
        <span
          v-if="data.active_route"
          class="wf-awaiting"
          v-tooltip.top="data.current_step_name || t('ui.workflow.awaiting_you')"
        >
          <i class="pi pi-sitemap" aria-hidden="true" />{{ t('ui.workflow.awaiting_you') }}
        </span>
      </template>
    </Column>
    <Column :header="t('document.tags')" style="width: 200px">
      <template #body="{ data }">
        <div class="doc-tags" v-if="data.tags?.length">
          <TagBadge
            v-for="tag in data.tags.slice(0, 3)"
            :key="tag.id"
            :name="tag.name"
            :color="tag.color"
            clickable
            @select="tagFilter.selectTag(tag.id)"
            @click.stop
            @dblclick.stop
          />
          <TagOverflow v-if="data.tags.length > 3" :tags="data.tags.slice(3)" />
        </div>
      </template>
    </Column>
    <Column :header="t('document.language')" style="width: 100px">
      <template #body="{ data }">
        <span class="doc-lang">{{ languageLabel(data.language) }}</span>
      </template>
    </Column>
    <Column :header="t('ui.files')" style="width: 60px">
      <template #body="{ data }">
        <span class="doc-meta">{{ data.file_count }}</span>
      </template>
    </Column>
    <Column field="create_date" :header="t('document.creation_date')" style="width: 120px" sortable>
      <template #body="{ data }">
        <span class="doc-meta">{{ formatDate(data.create_date) }}</span>
      </template>
    </Column>
  </DataTable>
</template>

<style scoped>
.doc-table {
  cursor: pointer;
}

.doc-thumb {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--p-content-hover-background);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}

.doc-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.doc-title {
  font-weight: 500;
}

.wf-awaiting {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  margin-left: 0.5rem;
  padding: 0.05rem 0.4rem;
  font-size: 0.6875rem;
  font-weight: 600;
  border-radius: 999px;
  background: var(--teedy-warning-bg);
  color: var(--teedy-warning-text);
  vertical-align: baseline;
}
.wf-awaiting i {
  font-size: 0.625rem;
}

.doc-tags {
  display: flex;
  gap: 0.2rem;
  flex-wrap: wrap;
}

.doc-lang,
.doc-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
