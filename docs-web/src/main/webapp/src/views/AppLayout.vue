<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Drawer from 'primevue/drawer'
import AppSidebar from '../components/AppSidebar.vue'
import AppActionBar from '../components/AppActionBar.vue'

const sidebarOpen = ref(false)
const isMobile = ref(false)
let mql: MediaQueryList

function updateMobile(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) sidebarOpen.value = false
}

onMounted(() => {
  mql = window.matchMedia('(max-width: 768px)')
  isMobile.value = mql.matches
  mql.addEventListener('change', updateMobile)
})

onUnmounted(() => {
  mql?.removeEventListener('change', updateMobile)
})
</script>

<template>
  <div class="app-layout">
    <!-- Desktop sidebar -->
    <AppSidebar v-if="!isMobile" />

    <!-- Mobile sidebar drawer -->
    <Drawer
      v-if="isMobile"
      v-model:visible="sidebarOpen"
      position="left"
      :showCloseIcon="false"
      class="sidebar-drawer"
    >
      <AppSidebar @navigate="sidebarOpen = false" />
    </Drawer>

    <!-- Right column: action bar + content -->
    <div class="app-main">
      <AppActionBar @toggle-sidebar="sidebarOpen = !sidebarOpen" />
      <div class="app-content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  min-height: 100vh;
}

.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.app-content {
  flex: 1;
  overflow-y: auto;
}

.sidebar-drawer :deep(.p-drawer-content) {
  padding: 0;
}
.sidebar-drawer :deep(.p-drawer) {
  width: 220px !important;
}
</style>
