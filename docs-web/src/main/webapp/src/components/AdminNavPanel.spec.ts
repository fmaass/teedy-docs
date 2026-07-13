import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../locale/en.json'

// The panel reads the running version from the shared app-info query (#62). Mock it
// so a test can drive current_version without the network.
const appInfoValue = vi.hoisted(() => ({ value: { current_version: '3.5.2' } as { current_version: string } | undefined }))
vi.mock('../composables/useAppInfo', () => ({
  useAppInfo: () => ({ data: ref(appInfoValue.value) }),
}))

import AdminNavPanel from './AdminNavPanel.vue'

// Unit under test: the settings admin nav (#61) renders the 13 admin items grouped
// into THREE labelled sections (Access & Users / Content Model / System) with the
// correct membership, plus the renamed personal header ("Personal"), and the running
// app version pinned at the BOTTOM (#62). ROUTES are unchanged — this asserts the
// presentation regroup + version label only.

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

// Mirrors AppLayout's settingsAdminGroups data so the spec pins the exact grouping
// AND membership the component must render.
const settingsAdminGroups = [
  {
    label: en.ui.nav.group_access,
    items: [
      { label: 'Users', icon: 'pi pi-users', to: '/settings/users', name: 'settings-users' },
      { label: 'Groups', icon: 'pi pi-sitemap', to: '/settings/groups', name: 'settings-groups' },
      { label: 'LDAP', icon: 'pi pi-server', to: '/settings/ldap', name: 'settings-ldap' },
      { label: 'OIDC', icon: 'pi pi-id-card', to: '/settings/oidc', name: 'settings-oidc' },
    ],
  },
  {
    label: en.ui.nav.group_content,
    items: [
      { label: 'Metadata', icon: 'pi pi-tags', to: '/settings/metadata', name: 'settings-metadata' },
      { label: 'Vocabulary', icon: 'pi pi-list', to: '/settings/vocabulary', name: 'settings-vocabulary' },
      { label: 'Tag rules', icon: 'pi pi-bolt', to: '/settings/tag-rules', name: 'settings-tag-rules' },
      { label: 'Workflow', icon: 'pi pi-sitemap', to: '/settings/workflow', name: 'settings-workflow' },
    ],
  },
  {
    label: en.ui.nav.group_system,
    items: [
      { label: 'Config', icon: 'pi pi-cog', to: '/settings/config', name: 'settings-config' },
      { label: 'Inbox', icon: 'pi pi-inbox', to: '/settings/inbox', name: 'settings-inbox' },
      { label: 'Webhooks', icon: 'pi pi-link', to: '/settings/webhooks', name: 'settings-webhooks' },
      { label: 'Stats', icon: 'pi pi-chart-bar', to: '/settings/stats', name: 'settings-stats' },
      { label: 'Monitoring', icon: 'pi pi-chart-line', to: '/settings/monitoring', name: 'settings-monitoring' },
    ],
  },
]

const settingsNavItems = [
  { label: 'Account', icon: 'pi pi-user', to: '/settings/account', name: 'settings-account' },
  { label: 'API keys', icon: 'pi pi-key', to: '/settings/api-keys', name: 'settings-api-keys' },
]

function mountPanel(isAdmin = true) {
  return mount(AdminNavPanel, {
    props: {
      mode: 'settings' as const,
      isAdmin,
      currentRouteName: 'settings-account',
      settingsNavItems,
      settingsAdminGroups,
      tagManageItems: [],
    },
    global: {
      plugins: [i18n],
      stubs: {
        'router-link': { template: '<a :href="to"><slot /></a>', props: ['to'] },
      },
    },
  })
}

describe('AdminNavPanel — settings nav regroup (#61)', () => {
  it('renames the personal section header to "Personal"', () => {
    const sections = mountPanel().findAll('.admin-nav-section').map((s) => s.text())
    expect(sections).toContain('Personal')
    expect(sections).not.toContain('Settings')
  })

  it('renders exactly the three admin group headers in order', () => {
    const sections = mountPanel().findAll('.admin-nav-section').map((s) => s.text())
    // Personal + the three admin groups, in render order.
    expect(sections).toEqual([
      'Personal',
      'Access & Users',
      'Content Model',
      'System',
    ])
  })

  it('places each admin item under its correct group section', () => {
    const wrapper = mountPanel()
    // Walk the flat DOM order: section header, then its links until the next header.
    const nodes = wrapper.findAll('.admin-nav-section, .admin-nav-link')
    const grouped: Record<string, string[]> = {}
    let current = ''
    for (const n of nodes) {
      if (n.classes().includes('admin-nav-section')) {
        current = n.text()
        grouped[current] = []
      } else {
        grouped[current].push(n.text())
      }
    }
    expect(grouped['Access & Users']).toEqual(['Users', 'Groups', 'LDAP', 'OIDC'])
    expect(grouped['Content Model']).toEqual(['Metadata', 'Vocabulary', 'Tag rules', 'Workflow'])
    expect(grouped['System']).toEqual(['Config', 'Inbox', 'Webhooks', 'Stats', 'Monitoring'])
  })

  it('renders no admin group sections for a non-admin', () => {
    const sections = mountPanel(false).findAll('.admin-nav-section').map((s) => s.text())
    expect(sections).toEqual(['Personal'])
  })

  it('pins the running app version at the BOTTOM of the panel (#62)', () => {
    appInfoValue.value = { current_version: '3.5.2' }
    const wrapper = mountPanel()
    const label = wrapper.find('.admin-nav-version')
    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('v3.5.2')
    // It is the LAST child of the nav container (bottom of the panel).
    const nav = wrapper.get('.admin-nav')
    expect(nav.element.lastElementChild).toBe(label.element)
  })

  it('renders the version for a non-admin too (the label is not admin-gated)', () => {
    appInfoValue.value = { current_version: '3.6.0' }
    const wrapper = mountPanel(false)
    expect(wrapper.find('.admin-nav-version').text()).toBe('v3.6.0')
  })

  it('omits the version label when the app version is not yet known', () => {
    appInfoValue.value = undefined
    const wrapper = mountPanel()
    expect(wrapper.find('.admin-nav-version').exists()).toBe(false)
    appInfoValue.value = { current_version: '3.5.2' }
  })
})
