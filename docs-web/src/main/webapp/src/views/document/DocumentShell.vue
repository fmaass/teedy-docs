<script setup lang="ts">
import { inject, type Ref } from 'vue'
import DocumentSidebar from './DocumentSidebar.vue'

const sidebarOpen = inject<Ref<boolean>>('sidebarOpen')
const closeSidebar = inject<() => void>('closeSidebar')
</script>

<template>
  <div class="teedy-page">
    <aside class="teedy-sidebar" :class="{ open: sidebarOpen }">
      <DocumentSidebar @navigate="closeSidebar?.()" />
    </aside>
    <div class="teedy-sidebar-overlay" v-if="sidebarOpen" @click="closeSidebar?.()" />
    <main class="teedy-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.teedy-sidebar-overlay {
  display: none;
}

@media (max-width: 768px) {
  .teedy-sidebar-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.3);
    z-index: 899;
  }
}
</style>
