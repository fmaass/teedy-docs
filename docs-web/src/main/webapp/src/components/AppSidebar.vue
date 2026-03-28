<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()

const emit = defineEmits<{ navigate: [] }>()

function isActive(prefix: string) {
  return route.path.startsWith('/' + prefix)
}

const navItems = [
  { label: 'Documents', icon: 'pi pi-file', to: '/document', prefix: 'document' },
  { label: 'Tags', icon: 'pi pi-tags', to: '/tag', prefix: 'tag' },
  { label: 'Users & Groups', icon: 'pi pi-users', to: '/user', prefix: 'user' },
]

const footerItems = [
  { label: 'Settings', icon: 'pi pi-cog', to: '/settings', prefix: 'settings' },
]
</script>

<template>
  <nav class="app-sidebar" v-if="!auth.isAnonymous">
    <div class="sidebar-header">
      <router-link to="/" class="sidebar-brand" @click="emit('navigate')">
        <span class="brand-text">teedy</span>
      </router-link>
    </div>

    <div class="sidebar-nav">
      <router-link
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.prefix) }"
        @click="emit('navigate')"
      >
        <i :class="item.icon" />
        <span>{{ item.label }}</span>
      </router-link>
    </div>

    <div class="sidebar-spacer" />

    <div class="sidebar-footer">
      <router-link
        v-for="item in footerItems"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.prefix) }"
        @click="emit('navigate')"
      >
        <i :class="item.icon" />
        <span>{{ item.label }}</span>
      </router-link>
      <div class="sidebar-version">v2.4.0</div>
    </div>
  </nav>
</template>

<style scoped>
.app-sidebar {
  width: 220px;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  background: var(--p-content-background);
  border-right: 1px solid var(--p-content-border-color);
  height: 100vh;
  position: sticky;
  top: 0;
}

.sidebar-header {
  padding: 1.25rem 1rem;
}

.sidebar-brand {
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.brand-text {
  font-size: 1.375rem;
  font-weight: 700;
  color: var(--p-primary-color);
  letter-spacing: -0.02em;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: 0 0.75rem;
  gap: 0.125rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--p-content-border-radius, 6px);
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--p-text-muted-color);
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}
.nav-item:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
  text-decoration: none;
}
.nav-item.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  color: var(--p-primary-color);
  font-weight: 600;
}
.nav-item i {
  font-size: 1rem;
  width: 1.25rem;
  text-align: center;
}

.sidebar-spacer {
  flex: 1;
}

.sidebar-footer {
  display: flex;
  flex-direction: column;
  padding: 0 0.75rem 0.75rem;
  gap: 0.125rem;
}

.sidebar-version {
  padding: 0.5rem 0.75rem;
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  opacity: 0.6;
}
</style>
