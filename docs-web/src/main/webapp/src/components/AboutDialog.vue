<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import { getAppInfo } from '../api/app'

const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()

const version = ref<string | null>(null)

// Curated "What's new in 3.0.0" highlights. Each entry is an i18n key so the
// bullets translate; the list is intentionally short and accurate to
// RELEASE-NOTES-3.0.0.md (retirements, security hardening, new UIs).
const highlightKeys = [
  'ui.about.highlights.retirements',
  'ui.about.highlights.security',
  'ui.about.highlights.header_auth',
  'ui.about.highlights.new_ui',
] as const

// The What's-New bullets are hand-curated for a specific release, so the heading
// is pinned to that release — NOT the live server version, which drifts ahead of
// the bullets on every patch. The v{version} brand badge keeps the live version.
const HIGHLIGHTS_VERSION = '3.0.0'

const releasesUrl = 'https://github.com/fmaass/teedy-docs/releases'

async function loadVersion() {
  try {
    const info = await getAppInfo()
    version.value = info.current_version
  } catch {
    // Non-critical: the dialog still renders its What's-new content without a version.
    version.value = null
  }
}

// Fetch the running version the first time the dialog is opened.
watch(visible, (open) => {
  if (open && version.value === null) loadVersion()
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('ui.about.title')"
    :style="{ width: '34rem' }"
    :breakpoints="{ '640px': '95vw' }"
  >
    <div class="about-body">
      <div class="about-brand">
        <span class="about-name">teedy</span>
        <span v-if="version" class="about-version">{{ `v${version}` }}</span>
      </div>

      <section class="about-section">
        <h3 class="about-heading">{{ t('ui.about.whats_new_title', { version: HIGHLIGHTS_VERSION }) }}</h3>
        <ul class="about-highlights">
          <li v-for="key in highlightKeys" :key="key">{{ t(key) }}</li>
        </ul>
      </section>
    </div>

    <template #footer>
      <a :href="releasesUrl" target="_blank" rel="noopener" class="about-releases-link">
        <Button
          :label="t('ui.about.view_releases')"
          icon="pi pi-external-link"
          text
          size="small"
          severity="secondary"
        />
      </a>
      <Button :label="t('close')" text @click="visible = false" />
    </template>
  </Dialog>
</template>

<style scoped>
.about-body {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.about-brand {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
}
.about-name {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--p-primary-color);
  letter-spacing: -0.02em;
}
.about-version {
  font-size: 0.9375rem;
  color: var(--p-text-muted-color);
  font-variant-numeric: tabular-nums;
}
.about-heading {
  margin: 0 0 0.5rem;
  font-size: 0.9375rem;
  font-weight: 600;
}
.about-highlights {
  margin: 0;
  padding-left: 1.1rem;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.about-highlights li {
  font-size: 0.875rem;
  color: var(--p-text-color);
  line-height: 1.4;
}
.about-releases-link {
  text-decoration: none;
  margin-right: auto;
}
</style>
