<script setup lang="ts">
import { ref, provide } from 'vue'
import AppNavbar from '../components/AppNavbar.vue'
import AppFooter from '../components/AppFooter.vue'

const sidebarOpen = ref(false)

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function closeSidebar() {
  sidebarOpen.value = false
}

provide('sidebarOpen', sidebarOpen)
provide('closeSidebar', closeSidebar)
</script>

<template>
  <div class="teedy-app">
    <AppNavbar @toggle-sidebar="toggleSidebar" />
    <div class="teedy-app-body">
      <router-view />
    </div>
    <AppFooter />
  </div>
</template>

<style scoped>
.teedy-app {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.teedy-app-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
</style>
