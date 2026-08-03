import { describe, it, expect } from 'vitest'
import router, { resolveNavGuard } from './index'

function metaFor(name: string): { public?: boolean; requiresAdmin?: boolean } {
  const record = router.getRoutes().find((r) => r.name === name)
  if (!record) throw new Error(`route ${name} not found`)
  return record.meta
}

describe('resolveNavGuard', () => {
  const admin = { isAnonymous: false, isAdmin: true }
  const nonAdmin = { isAnonymous: false, isAdmin: false }
  const anon = { isAnonymous: true, isAdmin: false }

  it('allows an authenticated user through a normal route', () => {
    expect(resolveNavGuard({}, nonAdmin)).toBeNull()
  })

  it('redirects an anonymous user to login on a protected route', () => {
    expect(resolveNavGuard({}, anon)).toEqual({ name: 'login' })
  })

  it('allows an anonymous user onto a public route', () => {
    expect(resolveNavGuard({ public: true }, anon)).toBeNull()
  })

  it('bounces a non-admin off an admin-only route to documents', () => {
    expect(resolveNavGuard({ requiresAdmin: true }, nonAdmin)).toEqual({ name: 'documents' })
  })

  it('allows an admin onto an admin-only route', () => {
    expect(resolveNavGuard({ requiresAdmin: true }, admin)).toBeNull()
  })

  it('sends an anonymous user hitting an admin route to login (auth check wins)', () => {
    expect(resolveNavGuard({ requiresAdmin: true }, anon)).toEqual({ name: 'login' })
  })

  // #245: when /api/user could not be reached, the store reports serverUnavailable and — deliberately
  // — NOT anonymous, because an unreachable server says nothing about the session. Without its own
  // branch here, "not anonymous" would mount the protected view and fire its own doomed API calls.
  describe('server-unavailable state', () => {
    const unavailable = { isAnonymous: false, isAdmin: false, serverUnavailable: true }

    it('routes a protected view to login so the outage surface is what renders', () => {
      expect(resolveNavGuard({}, unavailable)).toEqual({ name: 'login' })
    })

    it('routes an admin-only view to login too — not to documents', () => {
      expect(resolveNavGuard({ requiresAdmin: true }, unavailable)).toEqual({ name: 'login' })
    })

    it('leaves public routes alone (they render their own errors, and login IS the surface)', () => {
      expect(resolveNavGuard({ public: true }, unavailable)).toBeNull()
    })

    it('does not disturb the ordinary paths when the server is reachable', () => {
      expect(resolveNavGuard({}, { ...nonAdmin, serverUnavailable: false })).toBeNull()
      expect(resolveNavGuard({}, { ...anon, serverUnavailable: false })).toEqual({ name: 'login' })
    })
  })

  // The full admin-settings route set (mirrors AppLayout's settingsAdminItems).
  const ADMIN_ROUTES = [
    'settings-config',
    'settings-users',
    'settings-groups',
    'settings-tag-rules',
    'settings-webhooks',
    'settings-ldap',
    'settings-metadata',
    'settings-workflow',
    'settings-vocabulary',
    'settings-monitoring',
    'settings-inbox',
  ] as const

  // User-level routes (settingsNavItems) — reachable by any logged-in user.
  const USER_ROUTES = ['settings-account', 'settings-api-keys'] as const

  // These drive the ACTUAL route meta from the router config through the guard,
  // so a route missing its requiresAdmin meta (regression) fails here.
  describe.each(ADMIN_ROUTES)('admin route %s', (name) => {
    it('is tagged requiresAdmin in the router config', () => {
      expect(metaFor(name).requiresAdmin).toBe(true)
    })
    it('bounces a non-admin to documents', () => {
      expect(resolveNavGuard(metaFor(name), nonAdmin)).toEqual({ name: 'documents' })
    })
    it('lets an admin through', () => {
      expect(resolveNavGuard(metaFor(name), admin)).toBeNull()
    })
  })

  describe.each(USER_ROUTES)('user-level route %s', (name) => {
    it('is NOT tagged requiresAdmin', () => {
      expect(metaFor(name).requiresAdmin).toBeUndefined()
    })
    it('stays reachable by a non-admin', () => {
      expect(resolveNavGuard(metaFor(name), nonAdmin)).toBeNull()
    })
  })
})
