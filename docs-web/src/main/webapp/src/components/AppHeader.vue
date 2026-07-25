<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import api from '../api/client'
import Button from 'primevue/button'
import AboutDialog from './AboutDialog.vue'

defineProps<{ isMobile?: boolean }>()
const emit = defineEmits<{ toggleDrawer: [] }>()

const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

const aboutVisible = ref(false)

async function toggleDarkMode() {
  const isDark = document.documentElement.classList.toggle('dark-mode')
  localStorage.setItem('teedy-dark-mode', isDark ? 'true' : 'false')

  // #147: persist the preference server-side so a fresh device / new login seeds it. Only for an
  // AUTHENTICATED user — an anonymous session has no account to store it on, so it stays localStorage
  // only. Best-effort, mirroring the locale write: form-encoded, and a server failure never blocks the
  // local toggle (the class + localStorage already applied); we only warn. No coalescer / retry — a
  // rapid-toggle stale server value only affects a fresh device's initial seed.
  if (!auth.isAnonymous) {
    try {
      await api.post('/user', new URLSearchParams({ dark_mode: String(isDark) }))
    } catch (e) {
      console.warn('Failed to persist the dark-mode preference to your account', e)
    }
  }
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
      <!-- Icon-only, like every other header action: the German label ("Aktivitätsverlauf") is
           long enough that a labelled 5th control would eat into the narrow mobile bar, which the
           German-overflow gate (visual.spec.ts) asserts against. -->
      <Button
        icon="pi pi-history"
        text
        rounded
        size="small"
        @click="router.push({ name: 'history' })"
        :aria-label="t('ui.history.title')"
        v-tooltip.bottom="t('ui.history.title')"
      />
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

/* `min-width: 0` is required on the CONTAINER as well as on the username below: a flex item
   defaults to `min-width: auto`, so without it `.action-items` refuses to shrink below the
   combined intrinsic width of its children and overflows the bar instead — and the username
   inside it never gets the chance to truncate. */
.action-items {
  display: flex;
  align-items: center;
  gap: 0.125rem;
  min-width: 0;
}

/* Header icon buttons already get an intrinsic square size from PrimeVue
   (`button.icon.only.width`), but in the narrow mobile bar the default
   `flex-shrink: 1` let the flex row squeeze them below it, so the left icons
   crowded/collapsed (#67). Pinning `flex-shrink: 0` makes each icon hold its
   token width as a stable, tappable target. */
.action-bar :deep(.p-button.p-button-icon-only) {
  flex-shrink: 0;
}

/* The username is the ONLY elastic item in the action row: every icon button is pinned
   `flex-shrink: 0` (#67). Without `min-width: 0` a flex item refuses to shrink below its
   content width (the `min-width: auto` default), so a long username overflowed the narrow
   mobile bar and painted OVER the Logout button — which then swallowed its clicks
   (caught adding the 5th header control in #177; a 24-char username made Logout
   unclickable at 393px). Truncating with an ellipsis keeps every control reachable. */
.user-name {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  padding: 0 0.375rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
