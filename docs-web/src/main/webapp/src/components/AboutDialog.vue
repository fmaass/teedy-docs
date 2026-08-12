<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from 'primevue/usetoast'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import { useAppInfo } from '../composables/useAppInfo'
import { useBrand } from '../composables/useThemeBranding'
import { useAuthStore } from '../stores/auth'
import { getAppDiagnostics, type AppDiagnostics } from '../api/app'
import { HIGHLIGHT_KEYS, headingVersion } from './aboutHighlights'
import { buildDiagnosticsBlock, buildReportUrl } from './aboutDiagnostics'

const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()
const toast = useToast()

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

// #275 (report-a-bug / diagnostics): admin-only. The server-stack fingerprint (Jetty/Java/OS) is
// admin-restricted (GET /app is anonymous, so it is NOT there), so the whole affordance renders only
// for an admin and the extra fetch never runs for a regular user.
const auth = useAuthStore()
const isAdmin = computed(() => auth.isAdmin)

// The admin-only stack fields, fetched lazily from GET /app/diagnostics. Cached behind a single
// in-flight promise so the dialog-open prefetch and a fast button click share one request and every
// awaiter sees the completed result. A failed fetch leaves this null; the block then degrades those
// fields to "unknown" rather than blocking the affordance.
const diagnostics = ref<AppDiagnostics | null>(null)
let diagnosticsPromise: Promise<void> | null = null
function ensureDiagnostics(): Promise<void> {
  if (!diagnosticsPromise) {
    diagnosticsPromise = getAppDiagnostics()
      .then((d) => {
        diagnostics.value = d
      })
      .catch(() => {
        // Leave diagnostics null: the block reports the six fields as "unknown".
      })
  }
  return diagnosticsPromise
}

// Prefetch when an admin opens the dialog so a subsequent click acts on loaded data. immediate so a
// dialog mounted already-open (the app's usage) prefetches too.
watch(
  visible,
  (open) => {
    if (open && isAdmin.value) {
      void ensureDiagnostics()
    }
  },
  { immediate: true },
)

async function copyDiagnostics() {
  // Build from the ALREADY-loaded diagnostics (the dialog-open prefetch populates it). Awaiting the
  // fetch here would break the click's transient user activation and let the browser silently block
  // the clipboard write; a rare click-before-prefetch degrades the stack fields to "unknown", which
  // is strictly better than a blocked write. The writeText CALL is synchronous inside the click task —
  // only its promise is awaited — so activation is preserved.
  const block = buildDiagnosticsBlock(appInfo.value, diagnostics.value)
  // Same try/catch+toast shape as FileActionMenu's copyLink: on a plain-http (non-secure) origin
  // `navigator.clipboard` is undefined and reading `.writeText` throws synchronously, which must land
  // in the error toast rather than escape as an unhandled rejection.
  try {
    await navigator.clipboard.writeText(block)
    toast.add({ severity: 'success', summary: t('ui.about.diagnostics_copied'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.about.diagnostics_copy_failed'), life: 3000 })
  }
}

function reportBug() {
  // Synchronous so window.open runs inside the click's transient user activation — an await first
  // would let the browser treat the popup as unrequested and block it. Built from the prefetched
  // diagnostics; a click before the prefetch resolves degrades the stack fields to "unknown".
  const block = buildDiagnosticsBlock(appInfo.value, diagnostics.value)
  window.open(buildReportUrl(block), '_blank', 'noopener')
}
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

      <section v-if="isAdmin" class="about-admin-actions">
        <Button
          :label="t('ui.about.report_bug')"
          icon="pi pi-external-link"
          size="small"
          severity="secondary"
          class="about-report-bug"
          @click="reportBug"
        />
        <Button
          :label="t('ui.about.copy_diagnostics')"
          icon="pi pi-copy"
          size="small"
          severity="secondary"
          outlined
          class="about-copy-diagnostics"
          @click="copyDiagnostics"
        />
      </section>

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
.about-admin-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
