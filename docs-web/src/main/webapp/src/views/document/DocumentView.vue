<script setup lang="ts">
import { ref, onMounted, provide, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getDocument, deleteDocument, type DocumentDetail } from '../../api/document'
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

const doc = ref<DocumentDetail | null>(null)
const loading = ref(true)

provide('document', doc)

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
        toast.add({ severity: 'success', summary: 'Document deleted', life: 2000 })
        router.push({ name: 'documents' })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to delete document', life: 3000 })
      }
    },
  })
}

async function loadDocument() {
  loading.value = true
  try {
    const { data } = await getDocument(props.id)
    doc.value = data
  } catch {
    toast.add({ severity: 'error', summary: 'Document not found', life: 3000 })
    router.push({ name: 'documents' })
  } finally {
    loading.value = false
  }
}

onMounted(loadDocument)
watch(() => props.id, loadDocument)
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
            <span v-if="doc.creator"> by <strong>{{ doc.creator }}</strong></span>
          </p>
          <div v-if="doc.tags?.length" class="doc-header-tags">
            <TagBadge v-for="tag in doc.tags" :key="tag.id" :name="tag.name" :color="tag.color" />
          </div>
        </div>
        <div class="doc-header-actions">
          <Button
            icon="pi pi-pencil"
            label="Edit"
            severity="secondary"
            outlined
            size="small"
            @click="router.push({ name: 'document-edit', params: { id } })"
          />
          <Button
            icon="pi pi-trash"
            label="Delete"
            severity="danger"
            outlined
            size="small"
            @click="handleDelete"
          />
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
}

.doc-header-main h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.3;
}

.doc-header-meta {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
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
  gap: 0.5rem;
  flex-shrink: 0;
}

.doc-tabs {
  margin-bottom: 1rem;
}

.doc-tab-content {
  min-height: 300px;
}

@media (max-width: 640px) {
  .doc-header {
    flex-direction: column;
  }
}
</style>
