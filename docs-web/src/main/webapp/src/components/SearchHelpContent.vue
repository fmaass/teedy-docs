<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

// The body of the "Search help" popover, shared by every field that feeds the document search
// parser: the main search bar and the relation-link typeahead on the document Content tab, which
// both reach `DocumentSearchCriteriaUtil.parseSearchQuery` through `GET /document/list` (#309).
// One list, one set of locale keys — a second copy would drift the moment the backend gains an
// operator.
//
// Search operators actually parsed by the backend
// (DocumentSearchCriteriaUtil.parseSearchQuery). Tokens are literal; only the
// descriptions are translated.
const operators: { token: string; example: string; descKey: string }[] = [
  { token: 'tag:', example: 'tag:invoice', descKey: 'document.search_help.op_tag' },
  { token: '!tag:', example: '!tag:draft', descKey: 'document.search_help.op_nottag' },
  // "*" is the only wildcard the backend reads, and only on a tag term. The example
  // deliberately avoids spelling out a longer tag term verbatim so each row stays uniquely
  // identifiable by its example text.
  { token: 'tag:*', example: 'tag:inv*', descKey: 'document.search_help.op_tag_wildcard' },
  { token: 'after:', example: 'after:2024-01', descKey: 'document.search_help.op_after' },
  { token: 'before:', example: 'before:2024-12-31', descKey: 'document.search_help.op_before' },
  { token: 'uafter:', example: 'uafter:2024', descKey: 'document.search_help.op_uafter' },
  { token: 'ubefore:', example: 'ubefore:2024-06', descKey: 'document.search_help.op_ubefore' },
  { token: 'at:', example: 'at:2024-05-01', descKey: 'document.search_help.op_at' },
  { token: 'uat:', example: 'uat:2024-05', descKey: 'document.search_help.op_uat' },
  { token: 'by:', example: 'by:alice', descKey: 'document.search_help.op_by' },
  { token: 'lang:', example: 'lang:eng', descKey: 'document.search_help.op_lang' },
  { token: 'mime:', example: 'mime:application/pdf', descKey: 'document.search_help.op_mime' },
  { token: 'title:', example: 'title:report', descKey: 'document.search_help.op_title' },
  { token: 'shared:yes', example: 'shared:yes', descKey: 'document.search_help.op_shared' },
]
</script>

<template>
  <div class="search-help">
    <h4 class="search-help-title">{{ t('document.search_help.title') }}</h4>
    <p class="search-help-intro">{{ t('document.search_help.contents_hint') }}</p>
    <p class="search-help-intro">{{ t('document.search_help.operators_intro') }}</p>
    <table class="search-help-table">
      <tbody>
        <tr v-for="op in operators" :key="op.token">
          <td><code>{{ op.example }}</code></td>
          <td>{{ t(op.descKey) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.search-help {
  max-width: 30rem;
}

.search-help-title {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  font-weight: 600;
}

.search-help-intro {
  margin: 0 0 0.5rem;
  font-size: 0.8rem;
  color: var(--p-text-muted-color);
}

.search-help-table {
  border-collapse: collapse;
  font-size: 0.8rem;
}

.search-help-table td {
  padding: 0.15rem 0.5rem 0.15rem 0;
  vertical-align: top;
}

.search-help-table code {
  background: var(--p-surface-100);
  border-radius: 4px;
  padding: 0.05rem 0.35rem;
  font-size: 0.75rem;
  white-space: nowrap;
}

:global(.dark-mode) .search-help-table code {
  background: var(--p-surface-800);
}
</style>
