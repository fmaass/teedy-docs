<script setup lang="ts">
interface NavItem {
  label: string
  icon: string
  to: string
  name: string
}

const props = defineProps<{
  mode: 'settings' | 'tag'
  isAdmin: boolean
  currentRouteName?: string | symbol | null
  settingsNavItems: NavItem[]
  settingsAdminItems: NavItem[]
  tagManageItems: NavItem[]
}>()

const emit = defineEmits<{
  back: []
  navigate: []
}>()

function isNavActive(name: string) {
  return props.currentRouteName === name
}
</script>

<template>
  <div class="admin-nav">
    <button class="back-to-docs" @click="emit('back')">
      <i class="pi pi-arrow-left" />
      <span>Back to documents</span>
    </button>

    <template v-if="mode === 'tag'">
      <div class="admin-nav-section">Tags</div>
      <router-link
        v-for="item in tagManageItems"
        :key="item.name"
        :to="item.to"
        class="admin-nav-link"
        :class="{ active: isNavActive(item.name) }"
        @click="emit('navigate')"
      >
        <i :class="item.icon" />
        <span>{{ item.label }}</span>
      </router-link>
    </template>

    <template v-else>
      <div class="admin-nav-section">Settings</div>
      <router-link
        v-for="item in settingsNavItems"
        :key="item.name"
        :to="item.to"
        class="admin-nav-link"
        :class="{ active: isNavActive(item.name) }"
        @click="emit('navigate')"
      >
        <i :class="item.icon" />
        <span>{{ item.label }}</span>
      </router-link>
      <template v-if="isAdmin">
        <div class="admin-nav-section">Administration</div>
        <router-link
          v-for="item in settingsAdminItems"
          :key="item.name"
          :to="item.to"
          class="admin-nav-link"
          :class="{ active: isNavActive(item.name) }"
          @click="emit('navigate')"
        >
          <i :class="item.icon" />
          <span>{{ item.label }}</span>
        </router-link>
      </template>
    </template>
  </div>
</template>

<style scoped>
.admin-nav {
  padding: 0 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.back-to-docs {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.5rem;
  background: none;
  border: none;
  font-size: 0.8125rem;
  font-family: inherit;
  font-weight: 500;
  color: var(--p-primary-color);
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.12s;
  margin-bottom: 0.5rem;
}

.back-to-docs:hover {
  background: var(--p-content-hover-background);
}

.back-to-docs i {
  font-size: 0.75rem;
}

.admin-nav-section {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
  padding: 0.5rem 0.5rem 0.25rem;
}

.admin-nav-link {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.5rem;
  border-radius: 4px;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
  transition: background 0.12s, color 0.12s;
}

.admin-nav-link:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
  text-decoration: none;
}

.admin-nav-link.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  color: var(--p-primary-color);
  font-weight: 600;
}

.admin-nav-link i {
  font-size: 0.875rem;
  width: 1.125rem;
  text-align: center;
}
</style>
