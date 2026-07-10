import { createRouter, createWebHashHistory, type RouteLocationRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'

// Pure navigation-guard decision, extracted so it can be unit-tested without a
// router harness. Returns the redirect target, or null to allow navigation.
export function resolveNavGuard(
  meta: { public?: boolean; requiresAdmin?: boolean },
  auth: { isAnonymous: boolean; isAdmin: boolean },
): RouteLocationRaw | null {
  if (!meta.public && auth.isAnonymous) {
    return { name: 'login' }
  }
  // A non-admin reaching an admin-only route by direct URL is bounced to the
  // documents list rather than mounting the view (and firing its admin-gated
  // API call). Nav-hiding alone does not stop deep links.
  if (meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'documents' }
  }
  return null
}

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/password-reset/:key',
      name: 'password-reset',
      component: () => import('../views/PasswordReset.vue'),
      props: (route) => ({ resetKey: route.params.key }),
      meta: { public: true },
    },
    {
      // Public share-by-URL view — reachable by logged-out visitors.
      path: '/share/:documentId/:shareId',
      name: 'share-view',
      component: () => import('../views/ShareView.vue'),
      props: true,
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../views/AppLayout.vue'),
      children: [
        {
          path: '',
          redirect: { name: 'documents' },
        },
        // Documents
        {
          path: 'document',
          name: 'documents',
          component: () => import('../views/document/DocumentList.vue'),
        },
        // Legacy browse route redirects to documents (merged in v2.6)
        {
          path: 'browse',
          redirect: { name: 'documents' },
        },
        {
          path: 'document/trash',
          name: 'document-trash',
          component: () => import('../views/document/DocumentTrash.vue'),
        },
        {
          path: 'document/add',
          name: 'document-add',
          component: () => import('../views/document/DocumentEdit.vue'),
        },
        {
          path: 'document/edit/:id',
          name: 'document-edit',
          component: () => import('../views/document/DocumentEdit.vue'),
          props: true,
        },
        {
          path: 'document/view/:id',
          name: 'document-view',
          component: () => import('../views/document/DocumentView.vue'),
          props: true,
          redirect: (to) => ({ name: 'document-view-content', params: to.params }),
          children: [
            {
              path: 'content',
              name: 'document-view-content',
              component: () => import('../views/document/DocumentViewContent.vue'),
            },
            {
              path: 'text',
              name: 'document-view-text',
              component: () => import('../views/document/DocumentViewText.vue'),
            },
            {
              path: 'permissions',
              name: 'document-view-permissions',
              component: () => import('../views/document/DocumentViewPermissions.vue'),
            },
            {
              path: 'workflow',
              name: 'document-view-workflow',
              component: () => import('../views/document/DocumentViewWorkflow.vue'),
            },
            {
              path: 'activity',
              name: 'document-view-activity',
              component: () => import('../views/document/DocumentViewActivity.vue'),
            },
            {
              path: 'comments',
              name: 'document-view-comments',
              component: () => import('../views/document/DocumentViewComments.vue'),
            },
          ],
        },
        // Tags
        {
          path: 'tag',
          name: 'tags',
          component: () => import('../views/tag/TagList.vue'),
        },
        {
          path: 'tag/:id',
          name: 'tag-edit',
          component: () => import('../views/tag/TagEdit.vue'),
          props: true,
        },
        // Legacy user route redirects to settings (merged in v2.6)
        {
          path: 'user',
          redirect: { name: 'settings-users' },
        },
        // Settings
        {
          path: 'settings',
          component: () => import('../views/settings/SettingsLayout.vue'),
          redirect: { name: 'settings-account' },
          children: [
            {
              path: 'account',
              name: 'settings-account',
              component: () => import('../views/settings/SettingsAccount.vue'),
            },
            {
              path: 'api-keys',
              name: 'settings-api-keys',
              component: () => import('../views/settings/SettingsApiKeys.vue'),
            },
            {
              path: 'config',
              name: 'settings-config',
              component: () => import('../views/settings/SettingsConfig.vue'),
              meta: { requiresAdmin: true },
            },
            {
              path: 'users',
              name: 'settings-users',
              component: () => import('../views/settings/SettingsUsers.vue'),
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'groups',
              name: 'settings-groups',
              component: () => import('../views/settings/SettingsGroups.vue'),
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'tag-rules',
              name: 'settings-tag-rules',
              component: () => import('../views/settings/SettingsTagRules.vue'),
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'webhooks',
              name: 'settings-webhooks',
              component: () => import('../views/settings/SettingsWebhooks.vue'),
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'ldap',
              name: 'settings-ldap',
              component: () => import('../views/settings/SettingsLdap.vue'),
              // Admin-only: SettingsLdap fires the admin-gated GET /app/config_ldap
              // on mount. The nav item is already admin-hidden, but a direct URL
              // hit would otherwise mount it (the backend 403 the only defence).
              meta: { requiresAdmin: true },
            },
            {
              path: 'metadata',
              name: 'settings-metadata',
              component: () => import('../views/settings/SettingsMetadata.vue'),
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'workflow',
              name: 'settings-workflow',
              component: () => import('../views/settings/SettingsWorkflow.vue'),
              // Admin-only: SettingsWorkflow fires the admin-gated route-model
              // endpoints. The nav item is already admin-hidden; this stops a
              // direct-URL mount by a non-admin.
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'vocabulary',
              name: 'settings-vocabulary',
              component: () => import('../views/settings/SettingsVocabulary.vue'),
              // Admin-only: SettingsVocabulary fires the admin-gated GET /vocabulary
              // (name list) on mount. Nav is already admin-hidden; this stops a
              // direct-URL mount from firing that admin call as a non-admin.
              meta: { requiresAdmin: true },
            },
            {
              path: 'monitoring',
              name: 'settings-monitoring',
              component: () => import('../views/settings/SettingsMonitoring.vue'),
              // Admin-only: SettingsMonitoring fires the admin-gated GET /app/log on
              // mount. Nav is already admin-hidden; this stops a direct-URL mount.
              meta: { requiresAdmin: true, wideSettings: true },
            },
            {
              path: 'inbox',
              name: 'settings-inbox',
              component: () => import('../views/settings/SettingsInbox.vue'),
              // Admin-only: SettingsInbox fires the admin-gated GET /app/config_inbox
              // on mount. The nav item is already admin-hidden, but a direct URL hit
              // would otherwise mount it (the backend 403 the only defence).
              meta: { requiresAdmin: true },
            },
          ],
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    await auth.fetchCurrentUser()
  }
  return resolveNavGuard(to.meta, auth) ?? undefined
})

export default router
