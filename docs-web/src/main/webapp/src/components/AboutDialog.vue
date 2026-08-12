<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import { useAppInfo } from '../composables/useAppInfo'
import { useBrand } from '../composables/useThemeBranding'
import { HIGHLIGHT_KEYS, headingVersion } from './aboutHighlights'

const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()

// The brand line used to be a hardcoded "teedy" — the third such literal in the app, so an
// instance renamed in Branding still introduced itself by the product name here. Same shared
// query and same fallback as the panel brand.
const { brandName } = useBrand()

// Live running version from the shared app-info query (v{version} brand badge).
const { data: appInfo } = useAppInfo()
const version = computed(() => appInfo.value?.current_version ?? null)

// The "What's new in {version}" heading DISPLAYS the running app's MAJOR.MINOR,
// derived from the live version — so a 3.5.x app always reads "What's new in 3.5"
// and can never show a patch that mismatches the badge above it. The curated
// bullets stay tied to the current minor (aboutHighlights); only the display
// format is derived here.
const whatsNewVersion = computed(() => headingVersion(version.value))

// Curated "What's new" highlights live in a shared module (single source of
// truth, unit-tested — BL-019).
const highlightKeys = HIGHLIGHT_KEYS

const releasesUrl = 'https://github.com/fmaass/teedy-docs/releases'

// Build commit the running server was built from (#275): a short, monospace SHA beside the version,
// linked to the exact GitHub commit for triage. ABSENT on an unstamped/local build (the server omits
// commit_id when it is "unknown"), so About then shows only the version. The full SHA is the link
// title; the visible text is the 7-character short form.
const commitId = computed(() => appInfo.value?.commit_id ?? null)
const shortCommit = computed(() => (commitId.value ? commitId.value.slice(0, 7) : null))
const commitUrl = computed(() =>
  commitId.value ? `https://github.com/fmaass/teedy-docs/commit/${commitId.value}` : null,
)
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
        <span class="about-name">{{ brandName }}</span>
        <span v-if="version" class="about-version">{{ `v${version}` }}</span>
        <a
          v-if="shortCommit"
          :href="commitUrl!"
          :title="commitId!"
          target="_blank"
          rel="noopener"
          class="about-commit"
        >{{ shortCommit }}</a>
      </div>

      <section class="about-section">
        <h3 class="about-heading">{{ t('ui.about.whats_new_title', { version: whatsNewVersion }) }}</h3>
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
.about-commit {
  font-size: 0.8125rem;
  color: var(--teedy-brand);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  text-decoration: none;
}
.about-commit:hover {
  text-decoration: underline;
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
