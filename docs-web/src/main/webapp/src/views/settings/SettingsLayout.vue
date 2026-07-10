<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

// Wide admin table views (users, groups, tag-rules, webhooks, metadata, workflow,
// monitoring) opt into the full content width via `meta.wideSettings` on their route.
// The 800px readability cap stays for the text-form views (account, config, LDAP)
// that benefit from a narrow measure.
const isWide = computed(() => route.meta.wideSettings === true)
</script>

<template>
  <div class="settings-content" :class="{ 'settings-content--wide': isWide }">
    <router-view />
  </div>
</template>

<style scoped>
.settings-content {
  padding: 1.5rem;
  max-width: 800px;
}

/* Wide table views (6-column admin DataTables) need the full width so their action
   column is not clipped off-screen behind a horizontal scrollbar. */
.settings-content--wide {
  max-width: none;
}
</style>
