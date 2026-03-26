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
      path: '/',
      component: () => import('../views/AppLayout.vue'),
      children: [
        {
          path: '',
          name: 'documents',
          component: () => import('../views/document/DocumentList.vue'),
        },
        {
          path: 'document/add',
          name: 'document-add',
          component: () => import('../views/document/DocumentEdit.vue'),
        },
        {
          path: 'document/:id',
          name: 'document-view',
          component: () => import('../views/document/DocumentView.vue'),
          props: true,
        },
        {
          path: 'document/:id/edit',
          name: 'document-edit',
          component: () => import('../views/document/DocumentEdit.vue'),
          props: true,
        },
        {
          path: 'tag',
          name: 'tags',
          component: () => import('../views/tag/TagList.vue'),
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('../views/settings/Settings.vue'),
          children: [
            {
              path: '',
              name: 'settings-account',
              component: () => import('../views/settings/SettingsAccount.vue'),
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
