<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useTagStore } from '../stores/tags'
import { onMounted } from 'vue'
import Button from 'primevue/button'
import Menubar from 'primevue/menubar'

const router = useRouter()
const auth = useAuthStore()
const tagStore = useTagStore()

onMounted(() => {
  tagStore.fetchTags()
})

async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="app-layout">
    <header class="app-header">
      <Menubar>
        <template #start>
          <router-link to="/" class="app-brand">Teedy</router-link>
        </template>
        <template #end>
          <div class="header-actions">
            <Button
              v-if="auth.isAdmin"
              icon="pi pi-cog"
              severity="secondary"
              text
              rounded
              @click="router.push({ name: 'settings-account' })"
            />
            <span class="username">{{ auth.username }}</span>
            <Button
              icon="pi pi-sign-out"
              severity="secondary"
              text
              rounded
              @click="handleLogout"
            />
          </div>
        </template>
      </Menubar>
    </header>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  background: var(--p-surface-50);
}
.app-brand {
  font-size: 1.25rem;
  font-weight: 700;
  text-decoration: none;
  color: var(--p-primary-color);
  margin-right: 1rem;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.username {
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
}
.app-main {
  max-width: 1400px;
  margin: 0 auto;
  padding: 1.5rem;
}
</style>
