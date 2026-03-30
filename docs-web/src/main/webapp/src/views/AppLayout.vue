<script setup lang="ts">
import AppActionBar from '../components/AppActionBar.vue'
</script>

<template>
  <div class="app-layout">
    <AppActionBar />
    <div class="app-content">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.app-content {
  flex: 1;
  overflow-y: auto;
}
</style>
