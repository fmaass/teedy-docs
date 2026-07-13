import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '../../locale/en.json'

// The hub shows an optional muted version line sourced from the shared app-info
// query (#64, same key AdminNavPanel uses). Mock it so tests drive current_version
// without the network.
const appInfoValue = vi.hoisted(() => ({
  value: { current_version: '3.6.0' } as { current_version: string } | undefined,
}))
vi.mock('../../composables/useAppInfo', () => ({
  useAppInfo: () => ({ data: ref(appInfoValue.value) }),
}))

// isAdmin is driven per-test to exercise the admin gate.
const isAdminRef = vi.hoisted(() => ({ value: false }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: isAdminRef.value }),
}))

import SettingsHub from './SettingsHub.vue'

// Unit under test: the /settings landing hub (#64). It is a GROUPED, ANNOTATED list
// — the Personal section is always shown; the three admin groups (Access & Users /
// Content Model / System) only render for an admin. Each entry is a router-link to
// an existing leaf route, carrying its icon, title label, and a one-line description
// (the load-bearing feature). ROUTES are unchanged.

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

// A router-link stub that records its resolved route name so a test can assert the
// entry points at the correct existing route. `to` here is a {name} location object.
const RouterLinkStub = {
  props: ['to'],
  template: '<a class="hub-link" :data-route-name="to && to.name"><slot /></a>',
}

function mountHub(isAdmin = false) {
  isAdminRef.value = isAdmin
  return mount(SettingsHub, {
    global: {
      plugins: [i18n],
      stubs: { 'router-link': RouterLinkStub, RouterLink: RouterLinkStub },
    },
  })
}

// The full set of entries the hub renders for an admin, in group order, each with the
// route name it must link to and the exact en.json description it must show.
const ADMIN_GROUPS = [
  {
    heading: en.ui.nav.group_access,
    entries: [
      { name: 'settings-users', desc: en.ui.settings.hub.desc.users },
      { name: 'settings-groups', desc: en.ui.settings.hub.desc.groups },
      { name: 'settings-ldap', desc: en.ui.settings.hub.desc.ldap },
      { name: 'settings-oidc', desc: en.ui.settings.hub.desc.oidc },
    ],
  },
  {
    heading: en.ui.nav.group_content,
    entries: [
      { name: 'settings-metadata', desc: en.ui.settings.hub.desc.metadata },
      { name: 'settings-vocabulary', desc: en.ui.settings.hub.desc.vocabulary },
      { name: 'settings-tag-rules', desc: en.ui.settings.hub.desc.tag_rules },
      { name: 'settings-workflow', desc: en.ui.settings.hub.desc.workflow },
    ],
  },
  {
    heading: en.ui.nav.group_system,
    entries: [
      { name: 'settings-config', desc: en.ui.settings.hub.desc.config },
      { name: 'settings-inbox', desc: en.ui.settings.hub.desc.inbox },
      { name: 'settings-webhooks', desc: en.ui.settings.hub.desc.webhooks },
      { name: 'settings-stats', desc: en.ui.settings.hub.desc.stats },
      { name: 'settings-monitoring', desc: en.ui.settings.hub.desc.monitoring },
    ],
  },
]

const PERSONAL_ENTRIES = [
  { name: 'settings-account', desc: en.ui.settings.hub.desc.account },
  { name: 'settings-api-keys', desc: en.ui.settings.hub.desc.apikeys },
]

function linkRouteNames(wrapper: ReturnType<typeof mountHub>): string[] {
  return wrapper.findAll('.hub-link').map((l) => l.attributes('data-route-name') ?? '')
}

describe('SettingsHub — settings landing hub (#64)', () => {
  it('renders an H1 for the settings home', () => {
    const wrapper = mountHub(false)
    const h1 = wrapper.find('h1')
    expect(h1.exists()).toBe(true)
    expect(h1.text()).toBe(en.ui.settings.hub.heading)
  })

  it('a NON-admin sees only the Personal section (no admin groups)', () => {
    const wrapper = mountHub(false)
    // The two personal entries link to the account + api-keys routes.
    expect(linkRouteNames(wrapper)).toEqual(PERSONAL_ENTRIES.map((e) => e.name))
    // None of the admin group headers render.
    const h2s = wrapper.findAll('h2').map((h) => h.text())
    expect(h2s).not.toContain(en.ui.nav.group_access)
    expect(h2s).not.toContain(en.ui.nav.group_content)
    expect(h2s).not.toContain(en.ui.nav.group_system)
  })

  it('an admin sees Personal + all three admin groups in order', () => {
    const wrapper = mountHub(true)
    const h2s = wrapper.findAll('h2').map((h) => h.text())
    expect(h2s).toEqual([
      en.ui.nav.personal,
      en.ui.nav.group_access,
      en.ui.nav.group_content,
      en.ui.nav.group_system,
    ])
  })

  it('each entry links to the correct existing route name', () => {
    const wrapper = mountHub(true)
    const expected = [
      ...PERSONAL_ENTRIES.map((e) => e.name),
      ...ADMIN_GROUPS.flatMap((g) => g.entries.map((e) => e.name)),
    ]
    expect(linkRouteNames(wrapper)).toEqual(expected)
  })

  it('renders the one-line description for every entry (the load-bearing feature)', () => {
    const wrapper = mountHub(true)
    const text = wrapper.text()
    for (const entry of [...PERSONAL_ENTRIES, ...ADMIN_GROUPS.flatMap((g) => g.entries)]) {
      expect(text).toContain(entry.desc)
    }
  })

  it('a non-admin does NOT render any admin-only description', () => {
    const wrapper = mountHub(false)
    const text = wrapper.text()
    for (const entry of ADMIN_GROUPS.flatMap((g) => g.entries)) {
      expect(text).not.toContain(entry.desc)
    }
  })

  it('shows the running app version subtly when known, omits it otherwise', () => {
    appInfoValue.value = { current_version: '3.6.0' }
    expect(mountHub(false).find('.hub-version').text()).toContain('3.6.0')
    appInfoValue.value = undefined
    expect(mountHub(false).find('.hub-version').exists()).toBe(false)
    appInfoValue.value = { current_version: '3.6.0' }
  })
})
