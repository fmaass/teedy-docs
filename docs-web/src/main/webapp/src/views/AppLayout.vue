<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useTagFilterStore } from '../stores/tagFilter'
import AppActionBar from '../components/AppActionBar.vue'
import Tree from 'primevue/tree'
import Button from 'primevue/button'
import SelectButton from 'primevue/selectbutton'
import Drawer from 'primevue/drawer'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const tf = useTagFilterStore()

const modeOptions = [
  { label: 'AND', value: 'and' },
  { label: 'OR', value: 'or' },
]

const isMobile = ref(false)
const drawerOpen = ref(false)
let mql: MediaQueryList

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

const settingsNavItems = [
  { label: 'Account', icon: 'pi pi-user', to: '/settings/account', name: 'settings-account' },
  { label: 'API Keys', icon: 'pi pi-key', to: '/settings/api-keys', name: 'settings-api-keys' },
]

const settingsAdminItems = [
  { label: 'Configuration', icon: 'pi pi-cog', to: '/settings/config', name: 'settings-config' },
  { label: 'Users', icon: 'pi pi-users', to: '/settings/users', name: 'settings-users' },
  { label: 'Tag rules', icon: 'pi pi-bolt', to: '/settings/tag-rules', name: 'settings-tag-rules' },
  { label: 'Webhooks', icon: 'pi pi-link', to: '/settings/webhooks', name: 'settings-webhooks' },
]

const tagManageItems = [
  { label: 'All tags', icon: 'pi pi-tags', to: '/tag', name: 'tags' },
]

function isNavActive(name: string) {
  return route.name === name
}
</script>

<template>
  <div class="app-layout" v-if="!auth.isAnonymous">
    <AppActionBar @toggle-drawer="drawerOpen = !drawerOpen" :isMobile="isMobile" />

    <div class="app-body">
      <!-- Desktop left panel -->
      <aside v-if="!isMobile" class="left-panel">
        <!-- Brand + add document -->
        <div class="panel-brand-row">
          <router-link to="/document" class="panel-brand">teedy</router-link>
          <Button
            icon="pi pi-plus"
            size="small"
            rounded
            @click="router.push({ name: 'document-add' })"
            aria-label="Add document"
            v-tooltip.right="'Add document'"
          />
        </div>

        <!-- Contextual middle -->
        <div class="panel-middle">
          <!-- Documents context: tag tree -->
          <template v-if="!isAdminContext">
            <div class="panel-controls">
              <SelectButton
                v-model="tf.tagMode"
                :options="modeOptions"
                optionLabel="label"
                optionValue="value"
                :allowEmpty="false"
                class="mode-toggle-sm"
              />
              <button
                class="untagged-toggle"
                :class="{ active: tf.showUntagged }"
                @click="tf.showUntagged = !tf.showUntagged"
                title="Show untagged documents"
              >
                <i class="pi pi-circle" />
                <span>Untagged</span>
              </button>
            </div>
            <div class="panel-tree">
              <Tree
                :value="tf.tagTreeNodes"
                :expandedKeys="tf.expandedKeys"
                class="tag-tree"
              >
                <template #default="{ node }">
                  <div
                    class="tag-tree-node"
                    :class="{
                      'tag-active': tf.selectedTagIds.has(node.key),
                      'tag-excluded': tf.excludedTagIds.has(node.key),
                      'tag-dimmed': !tf.selectedTagIds.has(node.key) && !tf.excludedTagIds.has(node.key) && tf.selectedTagIds.size > 0 && !(tf.tagCounts[node.key] > 0),
                    }"
                    @click.stop="tf.toggleTag(node.key)"
                  >
                    <i v-if="tf.selectedTagIds.has(node.key)" class="pi pi-check-circle state-icon include" />
                    <i v-else-if="tf.excludedTagIds.has(node.key)" class="pi pi-minus-circle state-icon exclude" />
                    <span class="tag-dot" :style="{ background: node.data.color }" />
                    <span class="tag-name">{{ node.label }}</span>
                    <span class="tag-count" v-if="tf.tagCounts[node.key] !== undefined">
                      {{ tf.tagCounts[node.key] }}
                    </span>
                  </div>
                </template>
              </Tree>
              <div v-if="!tf.tagTreeNodes.length" class="tag-empty">
                <span class="meta-text">No tags yet</span>
              </div>
            </div>
          </template>

          <!-- Admin context: settings/tag nav -->
          <template v-else>
            <div class="admin-nav">
              <button class="back-to-docs" @click="tf.navigateToDocuments()">
                <i class="pi pi-arrow-left" />
                <span>Back to documents</span>
              </button>

              <template v-if="route.path.startsWith('/tag')">
                <div class="admin-nav-section">Tags</div>
                <router-link
                  v-for="item in tagManageItems"
                  :key="item.name"
                  :to="item.to"
                  class="admin-nav-link"
                  :class="{ active: isNavActive(item.name) }"
                >
                  <i :class="item.icon" />
                  <span>{{ item.label }}</span>
                </router-link>
              </template>

              <template v-if="route.path.startsWith('/settings')">
                <div class="admin-nav-section">Settings</div>
                <router-link
                  v-for="item in settingsNavItems"
                  :key="item.name"
                  :to="item.to"
                  class="admin-nav-link"
                  :class="{ active: isNavActive(item.name) }"
                >
                  <i :class="item.icon" />
                  <span>{{ item.label }}</span>
                </router-link>
                <template v-if="auth.isAdmin">
                  <div class="admin-nav-section">Administration</div>
                  <router-link
                    v-for="item in settingsAdminItems"
                    :key="item.name"
                    :to="item.to"
                    class="admin-nav-link"
                    :class="{ active: isNavActive(item.name) }"
                  >
                    <i :class="item.icon" />
                    <span>{{ item.label }}</span>
                  </router-link>
                </template>
              </template>
            </div>
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
            <span>Manage tags</span>
          </router-link>
          <router-link
            to="/settings"
            class="footer-link"
            :class="{ active: route.path.startsWith('/settings') }"
          >
            <i class="pi pi-cog" />
            <span>Settings</span>
          </router-link>
        </div>
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
          <div class="panel-controls" v-if="!isAdminContext">
            <SelectButton
              v-model="tf.tagMode"
              :options="modeOptions"
              optionLabel="label"
              optionValue="value"
              :allowEmpty="false"
              class="mode-toggle-sm"
            />
            <button
              class="untagged-toggle"
              :class="{ active: tf.showUntagged }"
              @click="tf.showUntagged = !tf.showUntagged"
            >
              <i class="pi pi-circle" />
              <span>Untagged</span>
            </button>
          </div>
          <div class="panel-tree" v-if="!isAdminContext">
            <Tree :value="tf.tagTreeNodes" :expandedKeys="tf.expandedKeys" class="tag-tree">
              <template #default="{ node }">
                <div
                  class="tag-tree-node"
                  :class="{
                    'tag-active': tf.selectedTagIds.has(node.key),
                    'tag-excluded': tf.excludedTagIds.has(node.key),
                    'tag-dimmed': !tf.selectedTagIds.has(node.key) && !tf.excludedTagIds.has(node.key) && tf.selectedTagIds.size > 0 && !(tf.tagCounts[node.key] > 0),
                  }"
                  @click.stop="() => { tf.toggleTag(node.key); drawerOpen = false }"
                >
                  <i v-if="tf.selectedTagIds.has(node.key)" class="pi pi-check-circle state-icon include" />
                  <i v-else-if="tf.excludedTagIds.has(node.key)" class="pi pi-minus-circle state-icon exclude" />
                  <span class="tag-dot" :style="{ background: node.data.color }" />
                  <span class="tag-name">{{ node.label }}</span>
                  <span class="tag-count" v-if="tf.tagCounts[node.key] !== undefined">
                    {{ tf.tagCounts[node.key] }}
                  </span>
                </div>
              </template>
            </Tree>
          </div>
          <div class="admin-nav" v-if="isAdminContext">
            <button class="back-to-docs" @click="tf.navigateToDocuments(); drawerOpen = false">
              <i class="pi pi-arrow-left" />
              <span>Back to documents</span>
            </button>
          </div>
          <div class="panel-footer">
            <router-link to="/tag" class="footer-link" @click="drawerOpen = false">
              <i class="pi pi-tags" /><span>Manage tags</span>
            </router-link>
            <router-link to="/settings" class="footer-link" @click="drawerOpen = false">
              <i class="pi pi-cog" /><span>Settings</span>
            </router-link>
          </div>
        </div>
      </Drawer>

      <!-- Main content -->
      <div class="app-content">
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
  width: 250px;
  min-width: 250px;
  border-right: 1px solid var(--p-content-border-color);
  display: flex;
  flex-direction: column;
  background: var(--p-content-background);
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

.panel-controls {
  padding: 0 0.75rem 0.5rem;
  display: flex;
  gap: 0.375rem;
  flex-shrink: 0;
}

.mode-toggle-sm :deep(.p-selectbutton) {
  height: 1.75rem;
}
.mode-toggle-sm :deep(.p-togglebutton) {
  padding: 0.125rem 0.5rem;
  font-size: 0.6875rem;
  font-weight: 600;
}

.untagged-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 4px;
  background: none;
  cursor: pointer;
  font-size: 0.6875rem;
  font-family: inherit;
  font-weight: 500;
  color: var(--p-text-muted-color);
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}
.untagged-toggle:hover {
  border-color: var(--p-primary-color);
  color: var(--p-text-color);
}
.untagged-toggle.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  border-color: var(--p-primary-color);
  color: var(--p-primary-color);
}
.untagged-toggle i { font-size: 0.625rem; }

.panel-tree {
  flex: 1;
  overflow-y: auto;
  padding: 0 0.25rem 0.5rem;
}

.tag-tree :deep(.p-tree) {
  border: none;
  padding: 0;
  background: transparent;
}
.tag-tree :deep(.p-tree-node-content) {
  padding: 0.125rem 0;
}

.tag-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  transition: background 0.12s;
  width: 100%;
}
.tag-tree-node:hover { background: var(--p-content-hover-background); }
.tag-tree-node.tag-active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  font-weight: 600;
}
.tag-tree-node.tag-excluded {
  background: color-mix(in srgb, var(--p-red-500, #ef4444) 10%, transparent);
  text-decoration: line-through;
  opacity: 0.7;
}
.tag-tree-node.tag-dimmed { opacity: 0.4; }

.state-icon { font-size: 0.75rem; flex-shrink: 0; }
.state-icon.include { color: var(--p-primary-color); }
.state-icon.exclude { color: var(--p-red-500, #ef4444); }

.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.tag-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-count {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  padding: 0.0625rem 0.375rem;
  border-radius: 10px;
  min-width: 1.25rem;
  text-align: center;
  flex-shrink: 0;
}
.tag-empty {
  padding: 1rem;
  text-align: center;
}
.meta-text {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

/* --- Admin nav --- */

.admin-nav {
  padding: 0 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.back-to-docs {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.5rem;
  background: none;
  border: none;
  font-size: 0.8125rem;
  font-family: inherit;
  font-weight: 500;
  color: var(--p-primary-color);
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.12s;
  margin-bottom: 0.5rem;
}
.back-to-docs:hover { background: var(--p-content-hover-background); }
.back-to-docs i { font-size: 0.75rem; }

.admin-nav-section {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--p-text-muted-color);
  padding: 0.5rem 0.5rem 0.25rem;
}

.admin-nav-link {
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
.admin-nav-link:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
  text-decoration: none;
}
.admin-nav-link.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  color: var(--p-primary-color);
  font-weight: 600;
}
.admin-nav-link i {
  font-size: 0.875rem;
  width: 1.125rem;
  text-align: center;
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
  width: 280px !important;
}

.mobile-panel-body {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.mobile-panel-body .panel-tree {
  flex: 1;
}
.mobile-panel-body .panel-footer {
  margin-top: auto;
}
</style>
