<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { useTagFilterStore } from '../stores/tagFilter'
import { useResizablePanel } from '../composables/useResizablePanel'
import AppHeader from '../components/AppHeader.vue'
import DefaultPasswordBanner from '../components/DefaultPasswordBanner.vue'
import AdminNavPanel from '../components/AdminNavPanel.vue'
import TagTreePanel from '../components/TagTreePanel.vue'
import Button from 'primevue/button'
import Drawer from 'primevue/drawer'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const auth = useAuthStore()
const tf = useTagFilterStore()

const modeOptions = computed<Array<{ label: string; value: 'and' | 'or' }>>(() => [
  { label: t('ui.mode_and'), value: 'and' },
  { label: t('ui.mode_or'), value: 'or' },
])

const isMobile = ref(false)
const drawerOpen = ref(false)
let mql: MediaQueryList

const {
  width: panelWidth,
  startDrag: startPanelResize,
  onKeydown: onPanelResizeKey,
  reset: resetPanelWidth,
} = useResizablePanel()

function updateMobile(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) drawerOpen.value = false
}

onMounted(() => {
  mql = window.matchMedia('(max-width: 1024px)')
  isMobile.value = mql.matches
  mql.addEventListener('change', updateMobile)
})

onUnmounted(() => {
  mql?.removeEventListener('change', updateMobile)
})

const isAdminContext = computed(() =>
  route.path.startsWith('/settings') || route.path.startsWith('/tag'),
)

const settingsNavItems = computed(() => [
  { label: t('ui.account.title'), icon: 'pi pi-user', to: '/settings/account', name: 'settings-account' },
  { label: t('ui.apikeys.title'), icon: 'pi pi-key', to: '/settings/api-keys', name: 'settings-api-keys' },
])

const settingsAdminItems = computed(() => [
  { label: t('ui.config.title'), icon: 'pi pi-cog', to: '/settings/config', name: 'settings-config' },
  { label: t('ui.users.title'), icon: 'pi pi-users', to: '/settings/users', name: 'settings-users' },
  { label: t('ui.groups.title'), icon: 'pi pi-sitemap', to: '/settings/groups', name: 'settings-groups' },
  { label: t('ui.tag_rules.title'), icon: 'pi pi-bolt', to: '/settings/tag-rules', name: 'settings-tag-rules' },
  { label: t('ui.webhooks.title'), icon: 'pi pi-link', to: '/settings/webhooks', name: 'settings-webhooks' },
  { label: t('ui.ldap.title'), icon: 'pi pi-server', to: '/settings/ldap', name: 'settings-ldap' },
  { label: t('ui.metadata.title'), icon: 'pi pi-tags', to: '/settings/metadata', name: 'settings-metadata' },
  { label: t('ui.workflow_admin.title'), icon: 'pi pi-sitemap', to: '/settings/workflow', name: 'settings-workflow' },
  { label: t('ui.vocabulary.title'), icon: 'pi pi-list', to: '/settings/vocabulary', name: 'settings-vocabulary' },
  { label: t('ui.monitoring.title'), icon: 'pi pi-chart-line', to: '/settings/monitoring', name: 'settings-monitoring' },
  { label: t('ui.inbox.title'), icon: 'pi pi-inbox', to: '/settings/inbox', name: 'settings-inbox' },
])

const tagManageItems = computed(() => [
  { label: t('ui.tags_page.title'), icon: 'pi pi-tags', to: '/tag', name: 'tags' },
])

function handleDesktopTagSelect(tagId: string) {
  tf.toggleTag(tagId)
}

function handleMobileTagSelect(tagId: string) {
  tf.toggleTag(tagId)
  drawerOpen.value = false
}

</script>

<template>
  <div class="app-layout" v-if="!auth.isAnonymous">
    <AppHeader @toggle-drawer="drawerOpen = !drawerOpen" :isMobile="isMobile" />

    <div class="app-body">
      <!-- Desktop left panel -->
      <aside
        v-if="!isMobile"
        class="left-panel"
        :style="{ width: panelWidth + 'px', minWidth: panelWidth + 'px' }"
      >
        <!-- Brand + add document -->
        <div class="panel-brand-row">
          <router-link to="/document" class="panel-brand">teedy</router-link>
          <Button
            icon="pi pi-plus"
            size="small"
            rounded
            @click="router.push({ name: 'document-add' })"
            :aria-label="t('ui.add_document')"
            v-tooltip.right="t('ui.add_document')"
          />
        </div>

        <!-- Contextual middle -->
        <div class="panel-middle">
          <!-- Documents context: tag tree -->
          <template v-if="!isAdminContext">
            <TagTreePanel
              :tag-mode="tf.tagMode"
              :mode-options="modeOptions"
              :tag-tree-nodes="tf.activeTreeNodes"
              :expanded-keys="tf.activeExpandedKeys"
              :selected-tag-ids="tf.selectedTagIds"
              :excluded-tag-ids="tf.excludedTagIds"
              :tag-counts="tf.tagCounts"
              :view-mode="tf.viewMode"
              @update:tag-mode="tf.tagMode = $event"
              @update:view-mode="tf.viewMode = $event"
              @select-tag="handleDesktopTagSelect"
            />
          </template>

          <!-- Admin context: settings/tag nav -->
          <template v-else>
            <AdminNavPanel
              :mode="route.path.startsWith('/tag') ? 'tag' : 'settings'"
              :is-admin="auth.isAdmin"
              :current-route-name="route.name"
              :settings-nav-items="settingsNavItems"
              :settings-admin-items="settingsAdminItems"
              :tag-manage-items="tagManageItems"
              @back="tf.navigateToDocuments()"
            />
          </template>
        </div>

        <!-- Footer nav -->
        <div class="panel-footer">
          <router-link
            to="/tag"
            class="footer-link"
            :class="{ active: route.path.startsWith('/tag') }"
          >
            <i class="pi pi-tags" />
            <span>{{ t('ui.manage_tags') }}</span>
          </router-link>
          <router-link
            to="/settings"
            class="footer-link"
            :class="{ active: route.path.startsWith('/settings') }"
          >
            <i class="pi pi-cog" />
            <span>{{ t('ui.nav.settings') }}</span>
          </router-link>
        </div>

        <!-- Resize handle (desktop only) -->
        <div
          class="panel-resizer"
          role="separator"
          aria-orientation="vertical"
          tabindex="0"
          :aria-label="t('ui.resize_panel')"
          :aria-valuenow="panelWidth"
          aria-valuemin="200"
          aria-valuemax="480"
          @pointerdown="startPanelResize"
          @keydown="onPanelResizeKey"
          @dblclick="resetPanelWidth"
        />
      </aside>

      <!-- Mobile drawer -->
      <Drawer
        v-if="isMobile"
        v-model:visible="drawerOpen"
        position="left"
        :showCloseIcon="true"
        class="mobile-panel-drawer"
      >
        <template #header>
          <router-link to="/document" class="panel-brand" @click="drawerOpen = false">teedy</router-link>
        </template>
        <div class="mobile-panel-body">
          <TagTreePanel
            v-if="!isAdminContext"
            :tag-mode="tf.tagMode"
            :mode-options="modeOptions"
            :tag-tree-nodes="tf.activeTreeNodes"
            :expanded-keys="tf.activeExpandedKeys"
            :selected-tag-ids="tf.selectedTagIds"
            :excluded-tag-ids="tf.excludedTagIds"
            :tag-counts="tf.tagCounts"
            :view-mode="tf.viewMode"
            @update:tag-mode="tf.tagMode = $event"
            @update:view-mode="tf.viewMode = $event"
            @select-tag="handleMobileTagSelect"
          />
          <AdminNavPanel
            v-if="isAdminContext"
            :mode="route.path.startsWith('/tag') ? 'tag' : 'settings'"
            :is-admin="auth.isAdmin"
            :current-route-name="route.name"
            :settings-nav-items="settingsNavItems"
            :settings-admin-items="settingsAdminItems"
            :tag-manage-items="tagManageItems"
            @back="tf.navigateToDocuments(); drawerOpen = false"
            @navigate="drawerOpen = false"
          />
          <div class="panel-footer">
            <router-link to="/tag" class="footer-link" @click="drawerOpen = false">
              <i class="pi pi-tags" /><span>{{ t('ui.manage_tags') }}</span>
            </router-link>
            <router-link to="/settings" class="footer-link" @click="drawerOpen = false">
              <i class="pi pi-cog" /><span>{{ t('ui.nav.settings') }}</span>
            </router-link>
          </div>
        </div>
      </Drawer>

      <!-- Main content -->
      <div class="app-content">
        <DefaultPasswordBanner />
        <router-view />
      </div>
    </div>
  </div>

  <!-- Unauthenticated: no left panel -->
  <div v-else>
    <router-view />
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.app-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

/* --- Left panel --- */

.left-panel {
  position: relative;
  width: 250px;
  min-width: 250px;
  flex-shrink: 0;
  border-right: 1px solid var(--p-content-border-color);
  display: flex;
  flex-direction: column;
  background: var(--p-content-background);
}

/* --- Resize handle --- */

.panel-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 5;
  background: transparent;
  transition: background 0.12s;
  touch-action: none;
}
.panel-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  left: 2px;
  width: 2px;
  height: 100%;
  background: transparent;
  transition: background 0.12s;
}
.panel-resizer:hover::after,
.panel-resizer:focus-visible::after {
  background: var(--p-primary-color);
}
.panel-resizer:focus-visible {
  outline: none;
}

.panel-brand-row {
  padding: 0.75rem 1rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.panel-brand {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--p-primary-color);
  letter-spacing: -0.02em;
  text-decoration: none;
}
.panel-brand:hover {
  text-decoration: none;
  opacity: 0.85;
}

.panel-middle {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* --- Footer nav --- */

.panel-footer {
  border-top: 1px solid var(--p-content-border-color);
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  flex-shrink: 0;
}

.footer-link {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.5rem;
  border-radius: 4px;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
  transition: background 0.12s, color 0.12s;
}
.footer-link:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
  text-decoration: none;
}
.footer-link.active {
  color: var(--p-primary-color);
  font-weight: 600;
}
.footer-link i {
  font-size: 0.875rem;
  width: 1.125rem;
  text-align: center;
}

/* --- Main content --- */

.app-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
}

/* --- Mobile --- */

.mobile-panel-drawer :deep(.p-drawer) {
  width: 280px;
}

.mobile-panel-body {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.mobile-panel-body .panel-footer {
  margin-top: auto;
}
</style>
