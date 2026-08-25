<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getDocument, deleteDocument, duplicateDocument, type DocumentDetail } from '../../api/document'
import { getFileUrl, getDocumentZipUrl } from '../../api/file'
import { languageLabel } from '../../constants/languages'
import Button from 'primevue/button'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import TagBadge from '../../components/TagBadge.vue'
import FavoriteStar from '../../components/FavoriteStar.vue'
import AccessCountBadge from '../../components/AccessCountBadge.vue'
import { useTagFilterStore } from '../../stores/tagFilter'
import { useAccessCounts } from '../../composables/useAccessCounts'
import { DocumentKey } from './documentKey'
import { AccessCountsKey } from './accessCountsKey'

const props = defineProps<{ id: string }>()
const router = useRouter()
const route = useRoute()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()
const { t } = useI18n()
const tagFilter = useTagFilterStore()

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

provide(DocumentKey, doc)

// The caller's OWN access counts (#300). Gated on the document query having resolved: serving that
// document IS the access being counted, so an unordered parallel request would render the count
// from before this open.
const { data: accessCounts } = useAccessCounts(
  computed(() => props.id),
  computed(() => !!doc.value),
)
provide(AccessCountsKey, accessCounts)

// Header Download target: a multi-file document downloads a ZIP of ALL its files
// (GET /file/zip); a single-file document keeps the direct file download; a document with
// no file offers nothing.
const downloadHref = computed(() => {
  const d = doc.value
  if (!d) return undefined
  if (d.file_count > 1) return getDocumentZipUrl(d.id)
  return d.file_id ? getFileUrl(d.file_id) : undefined
})

// #206 — the header identifies the document with its cover thumbnail, the same raster the
// list and gallery rows already show: `file_id` is the SERVED cover pointer the backend
// reconciles (the explicit cover if one is set, else the first file, else null —
// DocumentDao.reconcileServingCover), and `file_rotation` varies the URL so a rotated cover
// isn't served from the long-lived raster cache.
//
// The no-file state is handled HERE, client-side, and cannot be delegated to the server's
// placeholder raster: that branch lives behind a file lookup (FileResource's findFile), so a
// document with no files has no id to ask about and never reaches it. A failed thumb (a
// raster still being generated, a deleted file) degrades to the same placeholder rather than
// leaving a broken-image glyph — mirroring the list/gallery consumers.
//
// The cover can change under an in-flight raster request — a cover swap, a file delete, a
// rotation, or a cycle back to a cover already shown — and the replaced <img> can still fire a
// LATE `error` from its detached node. The GUARD against that is ELEMENT IDENTITY: only the
// element currently mounted here may record a failure, and a detached node can never be that
// element, whatever url it carries. Weaker discriminators all leak: a shared boolean lets any
// stale error hide the live cover; the url alone fails an A→B→A cycle, where the first A
// element's late error names a url that is live again by the time it arrives.
//
// The handler must NOT read `doc.file_id` either — Vue caches inline event handlers, so one
// shared closure serves every render and would read whatever the cover is at CALL time.
const thumbEl = ref<HTMLImageElement | null>(null)
const failedThumbUrl = ref<string | null>(null)
function onThumbError(event: Event) {
  // A stale/detached element is not the live one: its failure is about a cover that is no
  // longer on screen and must not change what is.
  if (event.target !== thumbEl.value) return
  const src = (event.target as HTMLImageElement).getAttribute('src')
  if (src) failedThumbUrl.value = src
}

// The url the cover WOULD be served from, with no failure gating — the discriminator for
// "this is a different raster than the one that failed".
const candidateThumbUrl = computed(() => {
  const d = doc.value
  if (!d?.file_id) return null
  return getFileUrl(d.file_id, 'thumb', undefined, d.file_rotation)
})

// A recorded failure must never OUTLIVE the url it was about. While the placeholder shows,
// no <img> is mounted, so nothing on screen can retry that raster by itself — only a url
// change puts an element back. Keying this reset on the candidate url (not on file_id, which
// a same-file rotation never changes) is what makes rotating away and back a real retry
// instead of a permanent placeholder for the rest of the view's life. It only ever CLEARS the
// record; recording stays gated by element identity above, so no stale event can slip in.
watch(candidateThumbUrl, () => {
  failedThumbUrl.value = null
})

const coverThumbUrl = computed(() => {
  const url = candidateThumbUrl.value
  // The no-file case is spelled out rather than left to `null === null`: it must render the
  // placeholder because there IS no raster, not because one failed.
  if (!url || url === failedThumbUrl.value) return null
  return url
})

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
  { label: t('ui.workflow.tab'), icon: 'pi pi-sitemap', route: 'document-view-workflow' },
  { label: t('document.view.activity.activity'), icon: 'pi pi-history', route: 'document-view-activity' },
  { label: t('document.view.comments'), icon: 'pi pi-comments', route: 'document-view-comments' },
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

async function handleDuplicate() {
  try {
    const { data } = await duplicateDocument(props.id)
    queryClient.invalidateQueries({ queryKey: ['documents'] })
    toast.add({ severity: 'success', summary: t('ui.document_duplicated'), life: 2000 })
    router.push({ name: 'document-view', params: { id: data.id } })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_duplicate_document'), life: 3000 })
  }
}

function handleDelete() {
  confirmDanger({
    message: t('ui.delete_document_confirm'),
    header: t('ui.delete_document'),
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
        <!-- Cover thumbnail (#206). Decorative: the title beside it is the document's
             accessible name, so an alt text here would only repeat it. -->
        <div class="doc-header-thumb">
          <img
            v-if="coverThumbUrl"
            ref="thumbEl"
            :key="coverThumbUrl ?? 'placeholder'"
            :src="coverThumbUrl"
            alt=""
            @error="onThumbError"
          />
          <i v-else class="pi pi-file" aria-hidden="true" />
        </div>

        <div class="doc-header-main">
          <h1>{{ doc.title }}</h1>
          <p class="doc-header-meta">
            {{ formatDate(doc.create_date) }}
            <span v-if="doc.creator"> · <strong>{{ doc.creator }}</strong></span>
            <span v-if="doc.language" class="lang-badge">{{ languageLabel(doc.language) }}</span>
            <span v-if="doc.file_count"> · {{ t('ui.n_files', doc.file_count) }}</span>
            <!-- #300: the caller's OWN open count. Deliberately here and not on the document LIST
                 row or the slide-over: those are captured visual-regression surfaces. -->
            <span v-if="accessCounts" class="doc-header-access">
              · <AccessCountBadge :count="accessCounts.count" kind="document" />
            </span>
          </p>
          <div v-if="doc.tags?.length" class="doc-header-tags">
            <TagBadge
              v-for="tag in doc.tags"
              :key="tag.id"
              :name="tag.name"
              :color="tag.color"
              clickable
              @select="tagFilter.selectTag(tag.id)"
            />
          </div>
        </div>

        <div class="doc-header-actions">
          <FavoriteStar :document-id="doc.id" :favorite="!!doc.favorite" large />
          <Button
            v-if="downloadHref"
            :as="'a'"
            :href="downloadHref"
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
            icon="pi pi-copy"
            :label="t('duplicate')"
            severity="secondary"
            outlined
            size="small"
            @click="handleDuplicate"
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

/* #206 — same square-tile treatment as the list row's `.doc-thumb`, at header scale: the
   raster fills the tile (cropping rather than letterboxing), and the placeholder icon is
   centred in the same box so the header's geometry does not shift between the two states. */
.doc-header-thumb {
  flex: 0 0 auto;
  width: 4rem;
  height: 4rem;
  border-radius: 6px;
  overflow: hidden;
  background: var(--p-content-hover-background);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--p-text-muted-color);
  font-size: 1.25rem;
}

.doc-header-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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

.doc-header-access {
  display: inline-flex;
  align-items: baseline;
  gap: 0.25rem;
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
  /* 1.5rem of page gutter is 48px of a 360px phone — a sixth of the width. The file
     list's row geometry (#170) needs that space for the name column and the action
     cluster, and every other tab reads better with it too. */
  .doc-view {
    padding: 1rem 0.75rem;
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
