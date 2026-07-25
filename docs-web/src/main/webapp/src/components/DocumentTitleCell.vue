<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { type DocumentListItem } from '../api/document'

/**
 * The title cell of a document row (#194).
 *
 * DocumentTable renders TWO DataTables (selectable and single-select), each with its
 * own `field="title"` body slot. This component is that slot's whole content, so the
 * two branches cannot drift apart — the link semantics below have to hold on both.
 *
 * The title is a REAL link to the document view: it carries a genuine href, so the
 * browser's own affordances work (ctrl/cmd-click and middle-click open a new tab,
 * shift+right-click reaches "Copy link address", the status bar previews the target).
 * The list's interaction contract is unchanged, though — single click opens the
 * slide-over, double click opens the full view — so a PLAIN left click is intercepted
 * and re-emitted instead of navigating.
 */
const { t } = useI18n()

defineProps<{ document: DocumentListItem }>()

const emit = defineEmits<{
  open: [doc: DocumentListItem]
  openFull: [doc: DocumentListItem]
}>()

// Same guard as DocumentGallery's card link. Modifier / non-left clicks fall through
// untouched so the native href handles them; a plain left click is suppressed and
// re-emitted. The emit is EXPLICIT rather than left to bubbling because PrimeVue's
// DataTable.onRowClick bails out through isClickable() whenever the event target is
// an <a> (or a direct child of one) — the row would otherwise never see the click.
// No stopPropagation: the row still needs the event for its own bookkeeping.
function onOpenClick(event: MouseEvent, doc: DocumentListItem) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('open', doc)
}

// DataTable.onRowDblClick is gated by the SAME isClickable() check, so double-click
// to open the full view has to be re-emitted here too or it would silently die on
// the title (the one place users aim at).
function onOpenDblclick(event: MouseEvent, doc: DocumentListItem) {
  event.preventDefault()
  emit('openFull', doc)
}
</script>

<template>
  <router-link
    :to="{ name: 'document-view', params: { id: document.id } }"
    custom
    v-slot="{ href }"
  >
    <a
      class="doc-title"
      :href="href"
      @click="(e: MouseEvent) => onOpenClick(e, document)"
      @dblclick="(e: MouseEvent) => onOpenDblclick(e, document)"
      >{{ document.title }}</a
    >
  </router-link>
  <!-- "Awaiting your action" badge. active_route is target-scoped server-side: it is true only
       when the current route step targets the viewer, so it IS the "awaiting you" signal.
       It stays a SIBLING of the link — never a descendant — so the row has no nested
       interactive/tooltipped content inside the anchor. -->
  <span
    v-if="document.active_route"
    class="wf-awaiting"
    v-tooltip.top="document.current_step_name || t('ui.workflow.awaiting_you')"
  >
    <i class="pi pi-sitemap" aria-hidden="true" />{{ t('ui.workflow.awaiting_you') }}
  </span>
</template>

<style scoped>
.doc-title {
  font-weight: 500;
  /* The title is a link, but it must still READ as list text: the row's own hover
     highlight and pointer cursor are the open affordance. So the global `a` styling
     (primary color) AND the global `a:hover` (color shift + underline) are both
     neutralized — the cell is pixel-identical, in every state, to the plain <span>
     this replaced. :focus-visible is deliberately left to the UA ring so the link
     stays keyboard-discoverable. */
  color: inherit;
  text-decoration: none;
}
.doc-title:hover {
  color: inherit;
  text-decoration: none;
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
</style>
