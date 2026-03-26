<script setup lang="ts">
</script>

<template>
  <div class="teedy-page">
    <aside class="teedy-sidebar">
      <div class="p-4">
        <h3 class="text-sm font-semibold text-muted mb-2" style="text-transform: uppercase; letter-spacing: 0.04em">Groups</h3>
        <p class="text-sm text-muted">Group management coming soon</p>
        <h3 class="text-sm font-semibold text-muted mb-2 mt-4" style="text-transform: uppercase; letter-spacing: 0.04em">Users</h3>
        <p class="text-sm text-muted">User directory coming soon</p>
      </div>
    </aside>
    <main class="teedy-content p-4">
      <router-view />
    </main>
  </div>
</template>
