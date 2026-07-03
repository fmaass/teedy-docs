<script setup lang="ts">
import { computed, provide, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getDocument, deleteDocument, type DocumentDetail } from '../../api/document'
import { getFileUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import Button from 'primevue/button'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
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
const { t } = useI18n()

const returnTo = computed(() => (history.state?.returnTo as string) || null)
const filterLabel = computed(() => (history.state?.filterLabel as string) || null)

function goBack() {
  if (returnTo.value) {
    router.push(returnTo.value)
  } else {
    router.push({ name: 'documents' })
  }
}

const { data: doc, isLoading: loading, error } = useQuery({
  queryKey: computed(() => ['document', props.id]),
  queryFn: () => getDocument(props.id).then((r) => r.data),
})

provide('document', doc)

watch(error, (err) => {
  if (err) {
    toast.add({ severity: 'error', summary: t('ui.document_not_found'), life: 3000 })
    router.push({ name: 'documents' })
  }
})

const tabs = computed(() => [
  { label: t('ui.files'), icon: 'pi pi-file', route: 'document-view-content' },
  { label: t('document.view.content.content'), icon: 'pi pi-align-left', route: 'document-view-text' },
  { label: t('document.view.permissions.permissions'), icon: 'pi pi-lock', route: 'document-view-permissions' },
  { label: t('document.view.activity.activity'), icon: 'pi pi-history', route: 'document-view-activity' },
])

const activeTab = computed(() => {
  const name = route.name as string
  return tabs.value.find((tb) => tb.route === name)?.route ?? tabs.value[0].route
})

function onTabChange(value: string | number) {
  const tab = tabs.value.find((tb) => tb.route === value)
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
    message: t('ui.delete_document_confirm'),
    header: t('ui.delete_document'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: async () => {
      try {
        await deleteDocument(props.id)
        queryClient.invalidateQueries({ queryKey: ['documents'] })
        queryClient.invalidateQueries({ queryKey: ['trash'] })
        toast.add({ severity: 'success', summary: t('ui.document_deleted'), life: 2000 })
        router.push({ name: 'documents' })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.failed_delete_document'), life: 3000 })
      }
    },
  })
}
</script>

<template>
  <div class="doc-view">
    <!-- Back bar -->
    <div class="back-bar">
      <Button text size="small" class="back-link" @click="goBack">
        <i class="pi pi-arrow-left" aria-hidden="true" />
        <span>{{ t('ui.documents') }}</span>
      </Button>
      <span v-if="filterLabel" class="back-filter">· {{ filterLabel }}</span>
    </div>

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
            <span v-if="doc.language" class="lang-badge">{{ languageLabel(doc.language) }}</span>
            <span v-if="doc.file_count"> · {{ t('ui.n_files', doc.file_count) }}</span>
          </p>
          <div v-if="doc.tags?.length" class="doc-header-tags">
            <TagBadge v-for="tag in doc.tags" :key="tag.id" :name="tag.name" :color="tag.color" />
          </div>
        </div>

        <div class="doc-header-actions">
          <Button
            v-if="doc.file_id"
            :as="'a'"
            :href="getFileUrl(doc.file_id)"
            target="_blank"
            icon="pi pi-download"
            :label="t('download')"
            severity="secondary"
            outlined
            size="small"
          />
          <Button
            icon="pi pi-pencil"
            :label="t('edit')"
            severity="secondary"
            outlined
            size="small"
            @click="router.push({ name: 'document-edit', params: { id } })"
          />
          <Button
            icon="pi pi-trash"
            :label="t('delete')"
            severity="danger"
            outlined
            size="small"
            @click="handleDelete"
          />
        </div>
      </header>

      <!-- Tabs -->
      <Tabs :value="activeTab" @update:value="onTabChange" class="doc-tabs">
        <TabList>
          <Tab v-for="tab in tabs" :key="tab.route" :value="tab.route">
            <i :class="tab.icon" style="margin-right: 0.375rem" aria-hidden="true" />{{ tab.label }}
          </Tab>
        </TabList>
      </Tabs>

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

.back-bar {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin-bottom: 1rem;
  font-size: 0.8125rem;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  background: none;
  border: none;
  color: var(--p-primary-color);
  font-size: 0.8125rem;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  margin: -0.25rem -0.5rem;
  border-radius: 4px;
  transition: background 0.12s;
}
.back-link:hover {
  background: var(--p-content-hover-background);
}
.back-link i {
  font-size: 0.75rem;
}

.back-filter {
  color: var(--p-text-muted-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  border-bottom: 1px solid var(--p-content-border-color);
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
  color: var(--p-text-muted-color);
}

.lang-badge {
  display: inline-block;
  margin-left: 0.375rem;
  padding: 0.05rem 0.4rem;
  font-size: 0.6875rem;
  font-weight: 600;
  border-radius: 999px;
  background: var(--teedy-neutral-bg);
  color: var(--teedy-neutral-text);
  vertical-align: baseline;
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
  white-space: nowrap;
}

@media (max-width: 640px) {
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
