<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Button from 'primevue/button'

const router = useRouter()
const auth = useAuthStore()

const emit = defineEmits<{ toggleSidebar: [] }>()

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
    <button class="mobile-toggle" @click="emit('toggleSidebar')" aria-label="Toggle sidebar">
      <i class="pi pi-bars" />
    </button>

    <div class="action-spacer" />

    <div class="action-items">
      <Button
        icon="pi pi-moon"
        text
        rounded
        size="small"
        @click="toggleDarkMode"
        aria-label="Toggle dark mode"
      />
      <span class="user-name">{{ auth.username }}</span>
      <Button
        icon="pi pi-sign-out"
        text
        rounded
        size="small"
        @click="handleLogout"
        aria-label="Logout"
      />
    </div>
  </header>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color);
  background: var(--p-content-background);
  min-height: 48px;
}

.mobile-toggle {
  display: none;
  background: none;
  border: none;
  color: var(--p-text-color);
  font-size: 1.125rem;
  cursor: pointer;
  padding: 0.375rem;
}

.action-spacer {
  flex: 1;
}

.action-items {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.user-name {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  padding: 0 0.375rem;
}

@media (max-width: 768px) {
  .mobile-toggle {
    display: flex;
  }
}
</style>
