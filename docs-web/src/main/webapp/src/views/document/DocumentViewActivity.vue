<script setup lang="ts">
import { computed } from 'vue'
import ActivityTable from '../../components/ActivityTable.vue'
import { injectDocument } from './documentKey'

// The per-document Activity tab (#113, keyset paging #139). The table itself is shared with the
// global history view (#177) — this view only supplies the scope, and keeps the client-side type
// filter over the accumulated rows.
//
// `link-targets` is on so a File row opens its parent document's Files tab (#195) — the reporter's
// ask alongside the label fix. No entity-class column: every row here already belongs to the one
// document the user is looking at, so the column would carry no information.

const doc = injectDocument()
const docId = computed(() => doc.value?.id ?? null)
</script>

<template>
  <!-- `doc-activity-view` is this tab's readiness root (e2e ROUTE_ROOT.documentActivity). It falls
       through to ActivityTable's single root element, so it marks the mounted tab without adding a
       wrapper. It is needed because the tab outlet's own DataTable is NOT activity-specific: the
       Content tab renders FileListTable, also a DataTable, so a selector keyed on the table would
       still match the PREVIOUS tab during a transition. -->
  <ActivityTable scope="document" :document-id="docId" link-targets class="doc-activity-view" />
</template>
