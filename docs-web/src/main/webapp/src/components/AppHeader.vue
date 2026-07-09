<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import Button from 'primevue/button'
import AboutDialog from './AboutDialog.vue'

defineProps<{ isMobile?: boolean }>()
const emit = defineEmits<{ toggleDrawer: [] }>()

const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

const aboutVisible = ref(false)

function toggleDarkMode() {
  const isDark = document.documentElement.classList.toggle('dark-mode')
  localStorage.setItem('teedy-dark-mode', isDark ? 'true' : 'false')
}

async function handleLogout() {
  const logoutUrl = await auth.logout()
  if (logoutUrl) {
    // RP-initiated logout: hand off to the IdP end_session_endpoint so the SSO
    // session is terminated too (it redirects back to us afterwards).
    window.location.href = logoutUrl
    return
  }
  // Land on the local login form, not straight back into an SSO auto-redirect loop.
  router.push({ name: 'login', query: { local: '1' } })
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
      :aria-label="t('ui.menu')"
    />

    <div class="action-spacer" />

    <div class="action-items">
      <Button
        icon="pi pi-trash"
        text
        rounded
        size="small"
        @click="router.push({ name: 'document-trash' })"
        :aria-label="t('ui.trash')"
        v-tooltip.bottom="t('ui.trash')"
      />
      <Button
        icon="pi pi-moon"
        text
        rounded
        size="small"
        @click="toggleDarkMode"
        :aria-label="t('ui.dark_mode')"
        v-tooltip.bottom="t('ui.dark_mode')"
      />
      <Button
        icon="pi pi-info-circle"
        text
        rounded
        size="small"
        @click="aboutVisible = true"
        :aria-label="t('ui.about.title')"
        v-tooltip.bottom="t('ui.about.title')"
      />
      <span class="user-name">{{ auth.username }}</span>
      <Button
        icon="pi pi-sign-out"
        text
        rounded
        size="small"
        @click="handleLogout"
        :aria-label="t('index.logout')"
        v-tooltip.bottom="t('index.logout')"
      />
    </div>

    <AboutDialog v-model:visible="aboutVisible" />
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
