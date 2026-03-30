<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()

const items = [
  { label: 'Account', icon: 'pi pi-user', to: '/settings/account', name: 'settings-account' },
  { label: 'API Keys', icon: 'pi pi-key', to: '/settings/api-keys', name: 'settings-api-keys' },
]

const adminItems = [
  { label: 'Configuration', icon: 'pi pi-cog', to: '/settings/config', name: 'settings-config' },
  { label: 'Users', icon: 'pi pi-users', to: '/settings/users', name: 'settings-users' },
  { label: 'Tag rules', icon: 'pi pi-bolt', to: '/settings/tag-rules', name: 'settings-tag-rules' },
  { label: 'Webhooks', icon: 'pi pi-link', to: '/settings/webhooks', name: 'settings-webhooks' },
]
</script>

<template>
  <div class="settings-page">
    <div class="settings-header">
      <h1>Settings</h1>
    </div>
    <div class="settings-body">
      <nav class="settings-nav">
        <router-link
          v-for="item in items"
          :key="item.name"
          :to="item.to"
          class="nav-link"
          :class="{ active: route.name === item.name }"
        >
          <i :class="item.icon" />
          {{ item.label }}
        </router-link>
        <template v-if="auth.isAdmin">
          <div class="nav-divider" />
          <span class="nav-section">Administration</span>
          <router-link
            v-for="item in adminItems"
            :key="item.name"
            :to="item.to"
            class="nav-link"
            :class="{ active: route.name === item.name }"
          >
            <i :class="item.icon" />
            {{ item.label }}
          </router-link>
        </template>
      </nav>
      <div class="settings-content">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  padding: 1.5rem;
  max-width: 1000px;
}

.settings-header h1 {
  margin: 0 0 1.25rem;
  font-size: 1.5rem;
  font-weight: 600;
}

.settings-body {
  display: flex;
  gap: 1.5rem;
}

.settings-nav {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}
.nav-link:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
  text-decoration: none;
}
.nav-link.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  color: var(--p-primary-color);
  font-weight: 600;
}

.nav-divider {
  height: 1px;
  background: var(--p-content-border-color);
  margin: 0.5rem 0;
}

.nav-section {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--p-text-muted-color);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 0.25rem 0.75rem;
}

.settings-content {
  flex: 1;
  min-width: 0;
}

@media (max-width: 768px) {
  .settings-body {
    flex-direction: column;
  }
  .settings-nav {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
    gap: 0.25rem;
  }
  .nav-divider, .nav-section {
    display: none;
  }
}
</style>
