<script setup lang="ts">
import { ref, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFileUrl } from '../api/file'
import { type DocumentListItem } from '../api/document'
import { formatDate } from '../utils/formatters'
import TagBadge from './TagBadge.vue'
import TagOverflow from './TagOverflow.vue'
import FavoriteStar from './FavoriteStar.vue'
import { useTagFilterStore } from '../stores/tagFilter'

const { t } = useI18n()
const tagFilter = useTagFilterStore()

// Gallery is browse/open-only (pinned decision): no multi-select, no selection
// affordance. It renders the SAME paginated result set the table does — the parent
// (DocumentList) owns the query, pagination, and filters; this is a pure render mode.
const props = defineProps<{
  documents: DocumentListItem[]
  loading?: boolean
}>()

const emit = defineEmits<{
  cardClick: [doc: DocumentListItem]
  cardDblclick: [doc: DocumentListItem]
  cardContextMenu: [event: Event, doc: DocumentListItem]
}>()

// Thumbnail load-error handling (#80). A gallery thumbnail is loaded lazily; under load the
// lazy load can fire a TRANSIENT error while the server raster is still settling (the raster
// request itself ultimately returns 200). The old handler added the file id to `failedThumbs`
// whose v-if removed the <img> from the DOM permanently, so a transient hiccup hid the thumbnail
// until a full page reload. Instead, recover with a bounded retry: on error, re-request the thumb
// a couple of times (a changed, cache-busted src) before giving up and falling back to the file
// icon for a genuinely dead thumb.
const MAX_THUMB_RETRIES = 2
const THUMB_RETRY_DELAY_MS = 400

// Thumbs that failed to load after exhausting the retries — the card shows a file icon rather
// than a broken-image glyph (mirrors DocumentTable's @error handling).
const failedThumbs = ref(new Set<string>())
// Per-file retry counter: doubles as a cache-busting token in the img src (so the browser truly
// re-fetches rather than reusing the errored cache entry) and as the retry bound.
const thumbRetries = ref(new Map<string, number>())

function thumbSrc(fileId: string, rotation?: number): string {
  const base = getFileUrl(fileId, 'thumb', undefined, rotation)
  const attempt = thumbRetries.value.get(fileId) ?? 0
  if (attempt === 0) return base
  return `${base}${base.includes('?') ? '&' : '?'}_thumbretry=${attempt}`
}

// Pending retry timers keyed by file id, so they can be cancelled on unmount or when the file's
// document leaves the list — a fired-after-teardown callback would mutate stale reactive state
// (leaked-timer hazard). Plain (non-reactive) map: it holds timer handles, not render state.
const pendingRetryTimers = new Map<string, number>()
let unmounted = false

function clearRetryTimer(fileId: string) {
  const id = pendingRetryTimers.get(fileId)
  if (id !== undefined) {
    clearTimeout(id)
    pendingRetryTimers.delete(fileId)
  }
}

function onThumbError(fileId: string) {
  const attempt = thumbRetries.value.get(fileId) ?? 0
  if (attempt < MAX_THUMB_RETRIES) {
    clearRetryTimer(fileId) // never stack two pending timers for the same thumb
    const id = window.setTimeout(() => {
      pendingRetryTimers.delete(fileId)
      // Guard: no-op if the component has been torn down (defensive — onUnmounted also clears the
      // timer, so this normally cannot fire post-teardown, but never mutate stale reactive state).
      if (unmounted) return
      // Bumping the attempt changes thumbSrc(), which re-fetches the <img>.
      thumbRetries.value = new Map(thumbRetries.value).set(fileId, attempt + 1)
    }, THUMB_RETRY_DELAY_MS)
    pendingRetryTimers.set(fileId, id)
  } else {
    // Retries exhausted — treat the thumb as genuinely dead and fall back to the icon.
    failedThumbs.value = new Set(failedThumbs.value).add(fileId)
  }
}

// When the document set changes, cancel any pending retry whose document is no longer rendered —
// its <img> is gone, so a late retry would only mutate reactive state for nothing.
watch(
  () => props.documents,
  (docs) => {
    const liveFileIds = new Set(
      docs.map((d) => d.file_id).filter((id): id is string => !!id),
    )
    for (const fileId of [...pendingRetryTimers.keys()]) {
      if (!liveFileIds.has(fileId)) clearRetryTimer(fileId)
    }
  },
)

onUnmounted(() => {
  unmounted = true
  for (const id of pendingRetryTimers.values()) clearTimeout(id)
  pendingRetryTimers.clear()
})

// The open region is a real link to the document view (valid link semantics, a
// genuine href, keyboard-focusable, accessible name = title). We render it via
// RouterLink's `custom` slot so we own the click handling: the list's interaction
// contract is single-click → slide-over, double-click → full view, so a PLAIN left
// click is intercepted (preventDefault) and re-emitted as cardClick/cardDblclick.
// Modifier / non-left clicks (ctrl/cmd/middle → open in new tab) fall through to the
// native href so the link stays useful.
function onOpenClick(event: MouseEvent, doc: DocumentListItem) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('cardClick', doc)
}

function onOpenDblclick(event: MouseEvent, doc: DocumentListItem) {
  event.preventDefault()
  emit('cardDblclick', doc)
}
</script>

<template>
  <div class="doc-gallery" :aria-busy="loading">
    <article
      v-for="doc in documents"
      :key="doc.id"
      class="doc-card"
      @contextmenu="(e: MouseEvent) => emit('cardContextMenu', e, doc)"
    >
      <!-- Open/navigate action: covers the thumbnail + title + date region ONLY.
           The star and tag controls below are SIBLINGS of this link, never
           descendants — so the card has NO nested interactive elements. -->
      <router-link
        :to="{ name: 'document-view', params: { id: doc.id } }"
        custom
        v-slot="{ href }"
      >
        <a
          class="card-open"
          :href="href"
          :aria-label="doc.title"
          @click="(e: MouseEvent) => onOpenClick(e, doc)"
          @dblclick="(e: MouseEvent) => onOpenDblclick(e, doc)"
        >
          <span class="card-thumb">
            <img
              v-if="doc.file_id && !failedThumbs.has(doc.file_id)"
              :src="thumbSrc(doc.file_id, doc.file_rotation)"
              alt=""
              loading="lazy"
              @error="onThumbError(doc.file_id!)"
            />
            <i v-else class="pi pi-file" aria-hidden="true" />
          </span>

          <span class="card-body">
            <span class="card-title">{{ doc.title }}</span>
            <span
              v-if="doc.active_route"
              class="wf-awaiting"
              v-tooltip.top="doc.current_step_name || t('ui.workflow.awaiting_you')"
            >
              <i class="pi pi-sitemap" aria-hidden="true" />{{ t('ui.workflow.awaiting_you') }}
            </span>
            <span class="card-date">{{ formatDate(doc.create_date) }}</span>
          </span>
        </a>
      </router-link>

      <!-- Star: a sibling of the open link, positioned over the card corner. -->
      <FavoriteStar
        class="card-star"
        :document-id="doc.id"
        :favorite="!!doc.favorite"
      />

      <!-- Tag controls: siblings of the open link (each TagBadge is its own button). -->
      <div class="card-tags" v-if="doc.tags?.length">
        <TagBadge
          v-for="tag in doc.tags.slice(0, 3)"
          :key="tag.id"
          :name="tag.name"
          :color="tag.color"
          clickable
          @select="tagFilter.selectTag(tag.id)"
        />
        <TagOverflow v-if="doc.tags.length > 3" :tags="doc.tags.slice(3)" />
      </div>
    </article>
  </div>
</template>

<style scoped>
.doc-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 1rem;
}

.doc-card {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--p-content-border-color);
  border-radius: var(--p-content-border-radius, 6px);
  background: var(--p-content-background);
  overflow: hidden;
  transition: border-color 0.12s, box-shadow 0.12s;
}
.doc-card:hover {
  border-color: var(--p-primary-color);
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.08);
}

/* The open link owns the thumbnail + title + date; it is the card's primary
   focusable control and carries the document title as its accessible name. */
.card-open {
  display: flex;
  flex-direction: column;
  text-align: left;
  color: inherit;
  text-decoration: none;
}
.card-open:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 2px var(--p-primary-color);
}

.card-thumb {
  position: relative;
  aspect-ratio: 4 / 3;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--p-content-hover-background);
  color: var(--p-text-muted-color);
  font-size: 2.5rem;
}
.card-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-star {
  position: absolute;
  top: 0.25rem;
  right: 0.25rem;
  background: var(--p-content-background);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 0.6rem 0.7rem;
}

.card-title {
  font-weight: 500;
  line-height: 1.3;
  /* Clamp to two lines so cards keep a stable height. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-date {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.card-tags {
  display: flex;
  gap: 0.2rem;
  flex-wrap: wrap;
  padding: 0 0.7rem 0.6rem;
}

.wf-awaiting {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  align-self: flex-start;
  padding: 0.05rem 0.4rem;
  font-size: 0.6875rem;
  font-weight: 600;
  border-radius: 999px;
  background: var(--teedy-warning-bg);
  color: var(--teedy-warning-text);
}
.wf-awaiting i {
  font-size: 0.625rem;
}
</style>
