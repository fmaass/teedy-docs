<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Button from 'primevue/button'

const router = useRouter()
const auth = useAuthStore()

const emit = defineEmits<{ toggleSidebar: [] }>()

function toggleDarkMode() {
  document.documentElement.classList.toggle('dark-mode')
}

async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <nav class="teedy-navbar">
    <button class="teedy-sidebar-toggle" @click="emit('toggleSidebar')" aria-label="Toggle sidebar">
      <i class="pi pi-bars" />
    </button>

    <router-link to="/" class="teedy-navbar-brand">teedy</router-link>

    <ul class="teedy-navbar-nav" v-if="!auth.isAnonymous">
      <li>
        <router-link :to="{ name: 'documents' }">
          <i class="pi pi-file" />
          Documents
        </router-link>
      </li>
      <li>
        <router-link :to="{ name: 'tags' }">
          <i class="pi pi-tags" />
          Tags
        </router-link>
      </li>
      <li>
        <router-link :to="{ name: 'user-groups' }">
          <i class="pi pi-users" />
          Users &amp; Groups
        </router-link>
      </li>
    </ul>

    <div class="teedy-navbar-spacer" />

    <div class="teedy-navbar-right" v-if="!auth.isAnonymous">
      <Button
        icon="pi pi-moon"
        text
        rounded
        size="small"
        severity="secondary"
        @click="toggleDarkMode"
        :style="{ color: 'var(--teedy-navbar-text)' }"
        aria-label="Toggle dark mode"
      />
      <router-link :to="{ name: 'settings-account' }" class="teedy-navbar-user">
        <i class="pi pi-user" />
        {{ auth.username }}
      </router-link>
      <router-link :to="{ name: 'settings-account' }" class="teedy-navbar-nav-link">
        <i class="pi pi-cog" style="color: var(--teedy-navbar-text); font-size: 0.875rem" />
      </router-link>
      <Button
        icon="pi pi-sign-out"
        text
        rounded
        size="small"
        severity="secondary"
        @click="handleLogout"
        :style="{ color: 'var(--teedy-navbar-text)' }"
        aria-label="Logout"
      />
    </div>
  </nav>
</template>

<style scoped>
.teedy-sidebar-toggle {
  display: none;
  background: none;
  border: none;
  color: var(--teedy-navbar-text);
  font-size: 1.125rem;
  cursor: pointer;
  padding: 0.5rem;
  margin-right: 0.5rem;
}

@media (max-width: 768px) {
  .teedy-sidebar-toggle {
    display: flex;
  }
}
</style>
