import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

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
              path: 'activity',
              name: 'document-view-activity',
              component: () => import('../views/document/DocumentViewActivity.vue'),
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
            },
            {
              path: 'users',
              name: 'settings-users',
              component: () => import('../views/settings/SettingsUsers.vue'),
            },
            {
              path: 'tag-rules',
              name: 'settings-tag-rules',
              component: () => import('../views/settings/SettingsTagRules.vue'),
            },
            {
              path: 'webhooks',
              name: 'settings-webhooks',
              component: () => import('../views/settings/SettingsWebhooks.vue'),
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
  if (!to.meta.public && auth.isAnonymous) {
    return { name: 'login' }
  }
})

export default router
