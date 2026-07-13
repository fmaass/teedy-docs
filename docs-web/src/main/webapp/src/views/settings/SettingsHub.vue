<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import { useAppInfo } from '../../composables/useAppInfo'

// #64: the /settings landing hub — a GROUPED, ANNOTATED list (NOT a card grid, no
// search, no widgets, no recents). The Personal section is always shown; the three
// admin groups render only for an admin. Each entry carries its nav icon, the
// existing title label, and a NEW one-line description — the description is what
// makes the hub worth more than the left nav. ROUTES are unchanged: every entry is a
// router-link to an existing leaf route.
//
// Icons + title/group label keys are REUSED verbatim from the authoritative nav model
// (AppLayout.vue settingsNavItems/settingsAdminGroups). Only the descKey strings are
// new (ui.settings.hub.desc.*). Both label and desc keys are written as literals so
// the i18n unused-key scan resolves them.

const { t } = useI18n()
const auth = useAuthStore()

interface HubEntry {
  icon: string
  titleKey: string
  descKey: string
  name: string
}

interface HubSection {
  headingKey: string
  entries: HubEntry[]
}

const personalSection: HubSection = {
  headingKey: 'ui.nav.personal',
  entries: [
    { icon: 'pi pi-user', titleKey: 'ui.account.title', descKey: 'ui.settings.hub.desc.account', name: 'settings-account' },
    { icon: 'pi pi-key', titleKey: 'ui.apikeys.title', descKey: 'ui.settings.hub.desc.apikeys', name: 'settings-api-keys' },
  ],
}

const adminSections: HubSection[] = [
  {
    headingKey: 'ui.nav.group_access',
    entries: [
      { icon: 'pi pi-users', titleKey: 'ui.users.title', descKey: 'ui.settings.hub.desc.users', name: 'settings-users' },
      { icon: 'pi pi-sitemap', titleKey: 'ui.groups.title', descKey: 'ui.settings.hub.desc.groups', name: 'settings-groups' },
      { icon: 'pi pi-server', titleKey: 'ui.ldap.title', descKey: 'ui.settings.hub.desc.ldap', name: 'settings-ldap' },
      { icon: 'pi pi-id-card', titleKey: 'ui.oidc.title', descKey: 'ui.settings.hub.desc.oidc', name: 'settings-oidc' },
    ],
  },
  {
    headingKey: 'ui.nav.group_content',
    entries: [
      { icon: 'pi pi-tags', titleKey: 'ui.metadata.title', descKey: 'ui.settings.hub.desc.metadata', name: 'settings-metadata' },
      { icon: 'pi pi-list', titleKey: 'ui.vocabulary.title', descKey: 'ui.settings.hub.desc.vocabulary', name: 'settings-vocabulary' },
      { icon: 'pi pi-bolt', titleKey: 'ui.tag_rules.title', descKey: 'ui.settings.hub.desc.tag_rules', name: 'settings-tag-rules' },
      { icon: 'pi pi-sitemap', titleKey: 'ui.workflow_admin.title', descKey: 'ui.settings.hub.desc.workflow', name: 'settings-workflow' },
    ],
  },
  {
    headingKey: 'ui.nav.group_system',
    entries: [
      { icon: 'pi pi-cog', titleKey: 'ui.config.title', descKey: 'ui.settings.hub.desc.config', name: 'settings-config' },
      { icon: 'pi pi-inbox', titleKey: 'ui.inbox.title', descKey: 'ui.settings.hub.desc.inbox', name: 'settings-inbox' },
      { icon: 'pi pi-link', titleKey: 'ui.webhooks.title', descKey: 'ui.settings.hub.desc.webhooks', name: 'settings-webhooks' },
      { icon: 'pi pi-chart-bar', titleKey: 'ui.stats.title', descKey: 'ui.settings.hub.desc.stats', name: 'settings-stats' },
      { icon: 'pi pi-chart-line', titleKey: 'ui.monitoring.title', descKey: 'ui.settings.hub.desc.monitoring', name: 'settings-monitoring' },
    ],
  },
]

// The Personal section is always rendered; the admin groups are gated on isAdmin.
const sections = computed<HubSection[]>(() =>
  auth.isAdmin ? [personalSection, ...adminSections] : [personalSection],
)

// #64 nice-to-have: a subtle version line at the top, from the SHARED app-info query
// (same cached key AdminNavPanel/AboutDialog use — no new fetch).
const { data: appInfo } = useAppInfo()
const version = computed(() => appInfo.value?.current_version ?? null)
</script>

<template>
  <div class="settings-hub">
    <header class="hub-head">
      <h1>{{ t('ui.settings.hub.heading') }}</h1>
      <p class="hub-intro">{{ t('ui.settings.hub.intro') }}</p>
      <p v-if="version" class="hub-version">{{ `v${version}` }}</p>
    </header>

    <section v-for="section in sections" :key="section.headingKey" class="hub-section">
      <h2>{{ t(section.headingKey) }}</h2>
      <nav :aria-label="t(section.headingKey)">
        <ul class="hub-list">
          <li v-for="entry in section.entries" :key="entry.name">
            <router-link :to="{ name: entry.name }" class="hub-entry">
              <i :class="entry.icon" class="hub-entry-icon" aria-hidden="true" />
              <span class="hub-entry-text">
                <span class="hub-entry-title">{{ t(entry.titleKey) }}</span>
                <span class="hub-entry-desc">{{ t(entry.descKey) }}</span>
              </span>
            </router-link>
          </li>
        </ul>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.settings-hub {
  display: flex;
  flex-direction: column;
  gap: 1.75rem;
}

.hub-head h1 {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--p-text-color);
  margin: 0;
}

.hub-intro {
  margin: 0.375rem 0 0;
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}

.hub-version {
  margin: 0.25rem 0 0;
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
}

.hub-section h2 {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
  margin: 0 0 0.5rem;
}

.hub-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.hub-entry {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 6px;
  text-decoration: none;
  color: var(--p-text-color);
  background: var(--p-content-background);
  transition: background 0.12s, border-color 0.12s;
}

.hub-entry:hover {
  background: var(--p-content-hover-background);
  border-color: var(--p-primary-color);
  text-decoration: none;
}

.hub-entry-icon {
  font-size: 1rem;
  width: 1.25rem;
  text-align: center;
  color: var(--p-primary-color);
  margin-top: 0.125rem;
}

.hub-entry-text {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 0;
}

.hub-entry-title {
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--p-text-color);
}

.hub-entry-desc {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
