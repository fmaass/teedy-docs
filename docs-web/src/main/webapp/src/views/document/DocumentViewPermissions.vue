<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import { type Acl, type InheritedAcl } from '../../api/document'
import { addAcl, deleteAcl, searchAclTargets, type AclTarget } from '../../api/acl'
import { createShare, deleteShare, buildShareUrl } from '../../api/share'
import Button from 'primevue/button'
import AutoComplete from 'primevue/autocomplete'
import Select from 'primevue/select'
import InputText from 'primevue/inputtext'
import TagBadge from '../../components/TagBadge.vue'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import { injectDocument } from './documentKey'

const { t } = useI18n()
const doc = injectDocument()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const acls = computed<Acl[]>(() => doc.value?.acls ?? [])
// User/group ACLs only — SHARE ACLs are surfaced in the Share links section.
const directAcls = computed<Acl[]>(() => acls.value.filter((a) => a.type !== 'SHARE'))
const inheritedAcls = computed<InheritedAcl[]>(() => doc.value?.inherited_acls ?? [])

// Group inherited ACLs by source tag
const inheritedBySource = computed(() => {
  const map = new Map<string, { id: string; name: string; color: string; acls: InheritedAcl[] }>()
  for (const acl of inheritedAcls.value) {
    if (!map.has(acl.source_id)) {
      map.set(acl.source_id, { id: acl.source_id, name: acl.source_name, color: acl.source_color, acls: [] })
    }
    map.get(acl.source_id)!.acls.push(acl)
  }
  return [...map.values()]
})

// Add ACL form
const searchResults = ref<AclTarget[]>([])
const selectedTarget = ref<AclTarget | null>(null)
const selectedPerm = ref<'READ' | 'WRITE'>('READ')
const addingAcl = ref(false)

const permOptions = computed(() => [
  { label: t('ui.permissions.can_view'), value: 'READ' },
  { label: t('ui.permissions.can_edit'), value: 'WRITE' },
])

async function completeAclTargetSearch(event: { query: string }) {
  const query = event.query.trim()
  if (!query) {
    searchResults.value = []
    return
  }
  try {
    const { data } = await searchAclTargets(query)
    const users = (data.users ?? []).map((u) => ({ ...u, type: 'USER' as const }))
    const groups = (data.groups ?? []).map((g) => ({ ...g, type: 'GROUP' as const }))
    searchResults.value = [...users, ...groups]
  } catch {
    searchResults.value = []
  }
}

async function handleAdd() {
  if (!selectedTarget.value || !doc.value) return
  addingAcl.value = true
  try {
    await addAcl(doc.value.id, selectedPerm.value, selectedTarget.value.name, selectedTarget.value.type)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
    toast.add({ severity: 'success', summary: t('ui.permissions.added'), life: 2000 })
    selectedTarget.value = null
    searchResults.value = []
    selectedPerm.value = 'READ'
  } catch {
    toast.add({ severity: 'error', summary: t('ui.permissions.failed_add'), life: 3000 })
  } finally {
    addingAcl.value = false
  }
}

function confirmRemove(acl: Acl) {
  confirmDanger({
    message: t('ui.permissions.remove_confirm', { perm: acl.perm.toLowerCase(), name: acl.name }),
    header: t('ui.permissions.remove'),
    icon: 'pi pi-lock',
    accept: async () => {
      if (!doc.value) return
      try {
        await deleteAcl(doc.value.id, acl.perm, acl.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
        toast.add({ severity: 'success', summary: t('ui.permissions.removed'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.permissions.failed_remove'), life: 3000 })
      }
    },
  })
}

function permLabel(perm: string) {
  return perm === 'WRITE' ? t('ui.permissions.can_edit') : t('ui.permissions.can_view')
}
function typeIcon(type: string) {
  return type === 'GROUP' ? 'pi pi-users' : 'pi pi-user'
}

// --- Share-by-URL ---
// Active shares are the SHARE-typed ACLs on this document; the ACL id is the
// share token used in the public link. The share URL embeds this token, so the
// entire share section (list, URLs, copy, create, revoke) is rendered only when
// doc.writable — a read-only reader must not be able to read/redistribute it.
const shares = computed(() =>
  acls.value
    .filter((a) => a.type === 'SHARE')
    .map((a) => ({ id: a.id, name: a.name, url: buildShareUrl(doc.value!.id, a.id) })),
)

const shareName = ref('')
const creatingShare = ref(false)

async function handleCreateShare() {
  if (!doc.value) return
  creatingShare.value = true
  try {
    await createShare(doc.value.id, shareName.value)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
    toast.add({ severity: 'success', summary: t('ui.share.created'), life: 2000 })
    shareName.value = ''
  } catch {
    toast.add({ severity: 'error', summary: t('ui.share.failed_create'), life: 3000 })
  } finally {
    creatingShare.value = false
  }
}

async function copyShareUrl(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    toast.add({ severity: 'success', summary: t('ui.share.copied'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.share.copy_failed'), life: 3000 })
  }
}

function confirmRevokeShare(share: { id: string; name: string | null }) {
  confirmDanger({
    message: t('ui.share.revoke_confirm', { name: share.name || t('ui.share.unnamed') }),
    header: t('ui.share.revoke'),
    icon: 'pi pi-link',
    accept: async () => {
      if (!doc.value) return
      try {
        await deleteShare(share.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
        toast.add({ severity: 'success', summary: t('ui.share.revoked'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.share.failed_revoke'), life: 3000 })
      }
    },
  })
}
</script>

<template>
  <div v-if="doc" class="permissions-view">

    <!-- Direct permissions -->
    <section class="perm-section">
      <h3>{{ t('ui.permissions.direct') }}</h3>
      <p class="section-hint">{{ t('ui.permissions.direct_hint') }}</p>

      <div v-if="directAcls.length" class="acl-list">
        <div v-for="acl in directAcls" :key="acl.id + acl.perm" class="acl-row">
          <i :class="typeIcon(acl.type)" class="acl-icon" />
          <span class="acl-name">{{ acl.name }}</span>
          <span class="acl-badge" :class="acl.perm === 'WRITE' ? 'badge-write' : 'badge-read'">
            {{ permLabel(acl.perm) }}
          </span>
          <Button
            v-if="doc.writable"
            icon="pi pi-times"
            text
            rounded
            size="small"
            severity="danger"
            @click="confirmRemove(acl)"
            v-tooltip="t('ui.permissions.remove')"
            :aria-label="t('ui.permissions.remove')"
          />
        </div>
      </div>
      <p v-else class="no-acl">{{ t('ui.permissions.no_direct') }}</p>
    </section>

    <!-- Share by URL. Gated ENTIRELY behind doc.writable: a read-only reader must
         never see share tokens/URLs (they double as the anonymous access token),
         otherwise they could redistribute access beyond what they may grant. -->
    <section v-if="doc.writable" class="perm-section">
      <h3>{{ t('ui.share.title') }}</h3>
      <p class="section-hint">{{ t('ui.share.hint') }}</p>

      <div v-if="shares.length" class="share-list">
        <div v-for="share in shares" :key="share.id" class="share-row">
          <i class="pi pi-link share-icon" aria-hidden="true" />
          <div class="share-main">
            <span class="share-name">{{ share.name || t('ui.share.unnamed') }}</span>
            <span class="share-url">{{ share.url }}</span>
          </div>
          <Button
            icon="pi pi-copy"
            text
            rounded
            size="small"
            severity="secondary"
            @click="copyShareUrl(share.url)"
            v-tooltip="t('ui.share.copy')"
            :aria-label="t('ui.share.copy')"
          />
          <Button
            icon="pi pi-times"
            text
            rounded
            size="small"
            severity="danger"
            @click="confirmRevokeShare(share)"
            v-tooltip="t('ui.share.revoke')"
            :aria-label="t('ui.share.revoke')"
          />
        </div>
      </div>
      <p v-else class="no-acl">{{ t('ui.share.none') }}</p>

      <!-- Create share form -->
      <div class="add-acl-form">
        <h4>{{ t('ui.share.create') }}</h4>
        <div class="add-acl-row">
          <InputText
            v-model="shareName"
            size="small"
            class="add-acl-autocomplete"
            :placeholder="t('ui.share.name_placeholder')"
            @keyup.enter="handleCreateShare"
          />
          <Button
            :label="t('ui.share.create_button')"
            icon="pi pi-link"
            size="small"
            :loading="creatingShare"
            @click="handleCreateShare"
          />
        </div>
      </div>

      <!-- Add permission form -->
      <div v-if="doc.writable" class="add-acl-form">
        <h4>{{ t('ui.permissions.add') }}</h4>
        <div class="add-acl-row">
          <AutoComplete
            v-model="selectedTarget"
            :suggestions="searchResults"
            optionLabel="name"
            forceSelection
            dropdown
            size="small"
            class="add-acl-autocomplete"
            :placeholder="t('ui.permissions.search_placeholder')"
            @complete="completeAclTargetSearch"
          >
            <template #option="{ option }">
              <div class="search-result">
                <i :class="typeIcon(option.type)" />
                <span>{{ option.name }}</span>
                <span class="result-type">{{ option.type }}</span>
              </div>
            </template>
          </AutoComplete>
          <Select
            v-model="selectedPerm"
            :options="permOptions"
            optionLabel="label"
            optionValue="value"
            size="small"
            style="width: 130px"
          />
          <Button
            :label="t('add')"
            icon="pi pi-plus"
            size="small"
            :disabled="!selectedTarget"
            :loading="addingAcl"
            @click="handleAdd"
          />
        </div>
      </div>
    </section>

    <!-- Inherited permissions from tags -->
    <section v-if="inheritedBySource.length" class="perm-section">
      <h3>{{ t('ui.permissions.inherited') }}</h3>
      <p class="section-hint">{{ t('ui.permissions.inherited_hint') }}</p>

      <div v-for="source in inheritedBySource" :key="source.id" class="inherited-group">
        <div class="inherited-source">
          <TagBadge :name="source.name" :color="source.color" />
        </div>
        <div class="acl-list inherited">
          <div v-for="acl in source.acls" :key="acl.id + acl.perm" class="acl-row">
            <i :class="typeIcon(acl.type)" class="acl-icon" />
            <span class="acl-name">{{ acl.name }}</span>
            <span class="acl-badge" :class="acl.perm === 'WRITE' ? 'badge-write' : 'badge-read'">
              {{ permLabel(acl.perm) }}
            </span>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<style scoped>
.permissions-view {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.perm-section h3 {
  margin: 0 0 0.25rem;
  font-size: 0.9375rem;
  font-weight: 600;
}

.section-hint {
  margin: 0 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.acl-list {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 1.25rem;
}
.acl-list.inherited {
  margin-bottom: 0.5rem;
}

.acl-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
}
.acl-row:last-child {
  border-bottom: none;
}

.acl-icon {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  flex-shrink: 0;
}

.acl-name {
  flex: 1;
  font-size: 0.875rem;
}

.acl-badge {
  font-size: 0.6875rem;
  font-weight: 600;
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
  flex-shrink: 0;
}
.badge-read {
  background: var(--teedy-info-bg);
  color: var(--teedy-info-text);
}
.badge-write {
  background: var(--teedy-warning-bg);
  color: var(--teedy-warning-text);
}

.no-acl {
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
  margin: 0 0 1.25rem;
}

.add-acl-form h4 {
  margin: 0 0 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
}

.add-acl-row {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
}

.add-acl-autocomplete {
  flex: 1;
}

.search-result {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  font-size: 0.875rem;
}

.result-type {
  margin-left: auto;
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  text-transform: uppercase;
}

.inherited-group {
  margin-bottom: 1rem;
}

.inherited-source {
  margin-bottom: 0.375rem;
}

.share-list {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 1.25rem;
}

.share-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--p-content-border-color);
}
.share-row:last-child {
  border-bottom: none;
}

.share-icon {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  flex-shrink: 0;
}

.share-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.share-name {
  font-size: 0.875rem;
  font-weight: 500;
}

.share-url {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono, monospace);
}
</style>
