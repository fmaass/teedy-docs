<script setup lang="ts">
// R-042 / RR-42: First-run security banner. Shown app-wide to an admin while the
// admin account still holds the built-in default password (auth.hasDefaultPassword,
// derived from GET /api/user's is_default_password). It is intentionally persistent
// (non-dismissible): the only way to clear it is to change the password, which the
// backend signal reflects on the next fetch. CTA routes to account settings, where
// the change-password form lives.
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import Message from 'primevue/message'
import Button from 'primevue/button'

const { t } = useI18n()
const auth = useAuthStore()
</script>

<template>
  <Message
    v-if="auth.hasDefaultPassword"
    severity="warn"
    :closable="false"
    icon="pi pi-exclamation-triangle"
    class="default-password-banner"
  >
    <div class="banner-body">
      <div class="banner-text">
        <strong>{{ t('ui.default_password_banner.title') }}</strong>
        <span>{{ t('ui.default_password_banner.message') }}</span>
      </div>
      <Button
        as="router-link"
        :to="{ name: 'settings-account' }"
        :label="t('ui.default_password_banner.cta')"
        icon="pi pi-key"
        size="small"
        severity="warn"
      />
    </div>
  </Message>
</template>

<style scoped>
.default-password-banner {
  margin: 0.75rem 1rem 0;
}
.banner-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  width: 100%;
}
.banner-text {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
</style>
