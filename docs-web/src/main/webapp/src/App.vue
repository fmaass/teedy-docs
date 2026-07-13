<script setup lang="ts">
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import { useThemeBranding } from './composables/useThemeBranding'

// Keep the browser tab title + favicon in sync with the server-side theme name.
// Mounted at the app root so it applies on every route, including login.
useThemeBranding()
</script>

<template>
  <Toast position="top-right" />
  <ConfirmDialog />
  <router-view />
</template>
