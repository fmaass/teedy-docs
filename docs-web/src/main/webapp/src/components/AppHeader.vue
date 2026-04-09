<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Button from 'primevue/button'

defineProps<{ isMobile?: boolean }>()
const emit = defineEmits<{ toggleDrawer: [] }>()

const router = useRouter()
const auth = useAuthStore()

function toggleDarkMode() {
  const isDark = document.documentElement.classList.toggle('dark-mode')
  localStorage.setItem('teedy-dark-mode', isDark ? 'true' : 'false')
}

async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="action-bar" v-if="!auth.isAnonymous">
    <Button
      v-if="isMobile"
      icon="pi pi-bars"
      text
      rounded
      size="small"
      @click="emit('toggleDrawer')"
      aria-label="Menu"
    />

    <div class="action-spacer" />

    <div class="action-items">
      <Button
        icon="pi pi-trash"
        text
        rounded
        size="small"
        @click="router.push({ name: 'document-trash' })"
        aria-label="Trash"
        v-tooltip.bottom="'Trash'"
      />
      <Button
        icon="pi pi-moon"
        text
        rounded
        size="small"
        @click="toggleDarkMode"
        aria-label="Toggle dark mode"
        v-tooltip.bottom="'Dark mode'"
      />
      <span class="user-name">{{ auth.username }}</span>
      <Button
        icon="pi pi-sign-out"
        text
        rounded
        size="small"
        @click="handleLogout"
        aria-label="Logout"
        v-tooltip.bottom="'Logout'"
      />
    </div>
  </header>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color);
  background: var(--p-content-background);
  min-height: 40px;
  flex-shrink: 0;
}

.action-spacer {
  flex: 1;
}

.action-items {
  display: flex;
  align-items: center;
  gap: 0.125rem;
}

.user-name {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  padding: 0 0.375rem;
}
</style>
