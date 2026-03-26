<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import Menu from 'primevue/menu'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const menuItems = computed(() => {
  const personal = [
    { label: 'User account', icon: 'pi pi-user', command: () => router.push({ name: 'settings-account' }), class: route.name === 'settings-account' ? 'active-item' : '' },
  ]
  const admin = auth.isAdmin ? [
    { separator: true },
    { label: 'General settings', items: [
      { label: 'Configuration', icon: 'pi pi-cog', command: () => router.push({ name: 'settings-config' }), class: route.name === 'settings-config' ? 'active-item' : '' },
      { label: 'Users', icon: 'pi pi-users', command: () => router.push({ name: 'settings-users' }), class: route.name === 'settings-users' ? 'active-item' : '' },
      { label: 'Tag rules', icon: 'pi pi-bolt', command: () => router.push({ name: 'settings-tag-rules' }), class: route.name === 'settings-tag-rules' ? 'active-item' : '' },
    ]},
  ] : []
  return [
    { label: 'Personal settings', items: personal },
    ...admin,
  ]
})
</script>

<template>
  <div class="settings-layout">
    <aside class="settings-nav">
      <Menu :model="menuItems" class="settings-menu" />
    </aside>
    <main class="settings-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.settings-layout {
  display: flex;
  gap: 1.5rem;
  padding: 1.5rem;
  max-width: 1100px;
}

.settings-nav {
  width: 240px;
  flex-shrink: 0;
}

.settings-menu {
  width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.settings-content {
  flex: 1;
  min-width: 0;
}

:deep(.active-item) {
  font-weight: 600;
  color: var(--teedy-brand) !important;
}

@media (max-width: 768px) {
  .settings-layout {
    flex-direction: column;
  }
  .settings-nav {
    width: 100%;
  }
}
</style>
