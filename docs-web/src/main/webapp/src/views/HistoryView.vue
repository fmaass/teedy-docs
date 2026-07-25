<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import ActivityTable from '../components/ActivityTable.vue'

// Global activity history (#177) — the feature-parity restoration of the AngularJS app's
// account-wide audit feed, which the Vue rewrite ported only in its per-document form.
//
// Scope is decided ENTIRELY server-side: GET /auditlog with no `document` returns the caller's own
// rows, or every user's rows (minus Acl) for an admin. This view sends no scope hint of its own —
// `isAdmin` here only decides which target LINKS are worth offering (admin-only routes would just
// bounce a regular user), never which rows are requested.
//
// Order is newest-first, with no sort control: the keyset cursor the paging model is built on
// (create_date DESC, id DESC — #139) admits exactly one order. The filters below are what narrow
// the feed instead.

const { t } = useI18n()
const auth = useAuthStore()
</script>

<template>
  <div class="history-view">
    <h1 class="history-title">{{ t('ui.history.title') }}</h1>
    <ActivityTable
      scope="global"
      server-filters
      link-targets
      show-entity-class
      :is-admin="auth.isAdmin"
      :empty-message="t('ui.history.empty')"
    />
  </div>
</template>

<style scoped>
.history-view {
  padding: 1rem;
}

.history-title {
  font-size: 1.25rem;
  font-weight: 600;
  margin: 0 0 1rem;
}
</style>
