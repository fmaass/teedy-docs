<script setup lang="ts">
import { ref, computed, provide, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getDocument, deleteDocument, type DocumentDetail } from '../../api/document'
import { getFileUrl } from '../../api/file'
import Button from 'primevue/button'
import TabMenu from 'primevue/tabmenu'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import TagBadge from '../../components/TagBadge.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()
const route = useRoute()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const { data: doc, isLoading: loading, error } = useQuery({
  queryKey: computed(() => ['document', props.id]),
  queryFn: () => getDocument(props.id).then((r) => r.data),
})

provide('document', doc)

watch(error, (err) => {
  if (err) {
    toast.add({ severity: 'error', summary: 'Document not found', life: 3000 })
    router.push({ name: 'documents' })
  }
})

const tabs = [
  { label: 'Content', icon: 'pi pi-file', route: 'document-view-content' },
  { label: 'Permissions', icon: 'pi pi-lock', route: 'document-view-permissions' },
  { label: 'Activity', icon: 'pi pi-history', route: 'document-view-activity' },
]

const activeTab = ref(tabs.findIndex((t) => t.route === route.name) || 0)

watch(() => route.name, (name) => {
  const idx = tabs.findIndex((t) => t.route === name)
  if (idx >= 0) activeTab.value = idx
})

function onTabChange(e: any) {
  const tab = tabs[e.index]
  if (tab) router.push({ name: tab.route, params: { id: props.id } })
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function handleDelete() {
  confirm.require({
    message: 'Are you sure you want to delete this document?',
    header: 'Delete document',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        await deleteDocument(props.id)
        queryClient.invalidateQueries({ queryKey: ['documents'] })
        toast.add({ severity: 'success', summary: 'Document deleted', life: 2000 })
        router.push({ name: 'documents' })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to delete document', life: 3000 })
      }
    },
  })
}
</script>

<template>
  <div class="doc-view">
    <!-- Loading skeleton -->
    <div v-if="loading" class="doc-view-loading">
      <Skeleton width="60%" height="2rem" class="mb-2" />
      <Skeleton width="30%" height="1rem" class="mb-4" />
      <Skeleton height="20rem" />
    </div>

    <template v-else-if="doc">
      <!-- Header -->
      <header class="doc-header">
        <div class="doc-header-main">
          <h1>{{ doc.title }}</h1>
          <p class="doc-header-meta">
            {{ formatDate(doc.create_date) }}
            <span v-if="doc.creator"> · <strong>{{ doc.creator }}</strong></span>
            <span v-if="doc.file_count"> · {{ doc.file_count }} file{{ doc.file_count !== 1 ? 's' : '' }}</span>
          </p>
          <div v-if="doc.tags?.length" class="doc-header-tags">
            <TagBadge v-for="tag in doc.tags" :key="tag.id" :name="tag.name" :color="tag.color" />
          </div>
        </div>

        <!-- Action buttons: icon-only on small screens, labelled on large -->
        <div class="doc-header-actions">
          <a
            v-if="doc.file_id"
            :href="getFileUrl(doc.file_id)"
            target="_blank"
            class="p-button p-button-outlined p-button-secondary p-button-sm doc-action-btn"
            v-tooltip.bottom="'Download'"
          >
            <i class="pi pi-download" />
            <span class="action-label">Download</span>
          </a>
          <Button
            icon="pi pi-pencil"
            severity="secondary"
            outlined
            size="small"
            class="doc-action-btn"
            v-tooltip.bottom="'Edit'"
            @click="router.push({ name: 'document-edit', params: { id } })"
          >
            <template #icon><i class="pi pi-pencil" /></template>
            <span class="action-label">Edit</span>
          </Button>
          <Button
            icon="pi pi-trash"
            severity="danger"
            outlined
            size="small"
            class="doc-action-btn"
            v-tooltip.bottom="'Delete'"
            @click="handleDelete"
          >
            <template #icon><i class="pi pi-trash" /></template>
            <span class="action-label">Delete</span>
          </Button>
        </div>
      </header>

      <!-- Tabs -->
      <TabMenu :model="tabs" :activeIndex="activeTab" @tab-change="onTabChange" class="doc-tabs" />

      <!-- Tab content -->
      <div class="doc-tab-content">
        <router-view />
      </div>
    </template>
  </div>
</template>

<style scoped>
.doc-view {
  padding: 1.5rem;
  max-width: 960px;
}

.doc-view-loading {
  padding: 1rem 0;
}

.doc-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1.25rem;
  padding-bottom: 1.25rem;
  border-bottom: 1px solid #e5e7eb;
}

.doc-header-main {
  flex: 1;
  min-width: 0;
}

.doc-header-main h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.3;
}

.doc-header-meta {
  margin: 0.3rem 0 0;
  font-size: 0.8125rem;
  color: #6b7280;
}

.doc-header-tags {
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
  margin-top: 0.5rem;
}

.doc-header-actions {
  display: flex;
  gap: 0.375rem;
  flex-shrink: 0;
  align-items: center;
}

/* icon-only buttons — hide label below 640px */
.doc-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  text-decoration: none;
}

@media (max-width: 640px) {
  .action-label {
    display: none;
  }
  .doc-header {
    flex-direction: column;
  }
  .doc-header-actions {
    align-self: flex-end;
  }
}

.doc-tabs {
  margin-bottom: 1rem;
}

.doc-tab-content {
  min-height: 300px;
}
</style>
