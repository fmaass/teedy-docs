<script setup lang="ts">
import { getFileUrl } from '../api/file'
import { type DocumentListItem } from '../api/document'
import { languageLabel } from '../constants/languages'
import { formatDate } from '../composables/useFormatters'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import TagBadge from './TagBadge.vue'

interface RowClickEvent {
  data: DocumentListItem
}

interface RowContextMenuEvent {
  data: DocumentListItem
  originalEvent: Event
}

defineProps<{
  documents: DocumentListItem[]
}>()

const emit = defineEmits<{
  rowClick: [doc: DocumentListItem]
  rowContextMenu: [event: Event, doc: DocumentListItem]
}>()
</script>

<template>
  <DataTable
    :value="documents"
    stripedRows
    :rowHover="true"
    class="doc-table"
    @row-click="(e: RowClickEvent) => emit('rowClick', e.data)"
    @row-contextmenu="(e: RowContextMenuEvent) => emit('rowContextMenu', e.originalEvent, e.data)"
    selectionMode="single"
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
    <Column field="title" header="Title" sortable>
      <template #body="{ data }">
        <span class="doc-title">{{ data.title }}</span>
      </template>
    </Column>
    <Column header="Tags" style="width: 200px">
      <template #body="{ data }">
        <div class="doc-tags" v-if="data.tags?.length">
          <TagBadge v-for="tag in data.tags.slice(0, 3)" :key="tag.id" :name="tag.name" :color="tag.color" />
          <span v-if="data.tags.length > 3" class="tag-overflow">+{{ data.tags.length - 3 }}</span>
        </div>
      </template>
    </Column>
    <Column header="Language" style="width: 100px">
      <template #body="{ data }">
        <span class="doc-lang">{{ languageLabel(data.language) }}</span>
      </template>
    </Column>
    <Column header="Files" style="width: 60px">
      <template #body="{ data }">
        <span class="doc-meta">{{ data.file_count }}</span>
      </template>
    </Column>
    <Column field="create_date" header="Created" style="width: 120px" sortable>
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

.doc-tags {
  display: flex;
  gap: 0.2rem;
  flex-wrap: wrap;
}

.tag-overflow {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  align-self: center;
}

.doc-lang,
.doc-meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
