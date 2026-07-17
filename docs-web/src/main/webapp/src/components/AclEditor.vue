<script setup lang="ts">
/**
 * Focused, reusable DIRECT-ACL editor for any ACL source (route models, and any
 * future source with a {perm, id, name, type} ACL list + PUT/DELETE /acl endpoints).
 *
 * Deliberately NOT DocumentViewPermissions.vue: that view injects a document, folds in
 * inherited ACLs, and manages share links. This component handles only the direct ACLs
 * the caller passes in, via the generic /api/acl endpoints (PUT to add, DELETE to remove).
 * The parent owns the data (passes `acls` + `writable`) and refetches on `@changed`.
 */
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { addAcl, deleteAcl, searchAclTargets, type AclTarget, type AclEntry } from '../api/acl'
import AutoComplete from 'primevue/autocomplete'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../composables/useConfirmDanger'

// Kept as an alias (not a re-declaration) so existing consumers importing `DirectAcl`
// keep working while the shape lives in one place — api/acl.ts.
export type DirectAcl = AclEntry

const props = defineProps<{
  sourceId: string
  acls: DirectAcl[]
  writable: boolean
  /**
   * Optional per-entry immutability predicate. A truthy result removes the row's delete
   * button (a mandatory grant, e.g. a tag owner's base ACL or the sole remaining owner) and
   * shows a lock marker instead; return a string to override the marker's label for that row
   * (e.g. a "sole owner" reason vs the default owner label). Defaults to "nothing is
   * immutable" so existing consumers are unchanged.
   */
  immutable?: (acl: DirectAcl) => boolean | string
  /**
   * Optional gate run before a grant is added. Return false (or a promise resolving false)
   * to cancel the add — used by the tag editor to surface the READ grant-disclosure
   * confirmation. Omitted by consumers that need no confirmation.
   */
  beforeAdd?: (perm: 'READ' | 'WRITE', target: AclTarget) => boolean | Promise<boolean>
}>()

const emit = defineEmits<{ changed: [] }>()

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()

const searchResults = ref<AclTarget[]>([])
const selectedTarget = ref<AclTarget | null>(null)
const selectedPerm = ref<'READ' | 'WRITE'>('READ')
const adding = ref(false)

const permOptions = computed(() => [
  { label: t('ui.acl_editor.can_view'), value: 'READ' },
  { label: t('ui.acl_editor.can_edit'), value: 'WRITE' },
])

function permLabel(perm: string) {
  return perm === 'WRITE' ? t('ui.acl_editor.can_edit') : t('ui.acl_editor.can_view')
}

// Resolve a row's immutability to its lock-marker label, or null when the row is mutable.
// A string result is used verbatim; a bare `true` falls back to the default owner label.
function immutableLabel(acl: DirectAcl): string | null {
  const result = props.immutable ? props.immutable(acl) : false
  if (!result) return null
  return typeof result === 'string' ? result : t('ui.acl_editor.owner_locked')
}

function isImmutable(acl: DirectAcl): boolean {
  return immutableLabel(acl) !== null
}

async function completeSearch(event: { query: string }) {
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
  if (!selectedTarget.value) return
  if (props.beforeAdd) {
    const proceed = await props.beforeAdd(selectedPerm.value, selectedTarget.value)
    if (!proceed) return
  }
  adding.value = true
  try {
    await addAcl(props.sourceId, selectedPerm.value, selectedTarget.value.name, selectedTarget.value.type)
    toast.add({ severity: 'success', summary: t('ui.acl_editor.added'), life: 2000 })
    selectedTarget.value = null
    searchResults.value = []
    selectedPerm.value = 'READ'
    emit('changed')
  } catch {
    toast.add({ severity: 'error', summary: t('ui.acl_editor.failed_add'), life: 3000 })
  } finally {
    adding.value = false
  }
}

function confirmRemove(acl: DirectAcl) {
  confirmDanger({
    message: t('ui.acl_editor.remove_confirm', { perm: permLabel(acl.perm), name: acl.name ?? '' }),
    header: t('ui.acl_editor.remove'),
    icon: 'pi pi-lock',
    accept: async () => {
      try {
        await deleteAcl(props.sourceId, acl.perm, acl.id)
        toast.add({ severity: 'success', summary: t('ui.acl_editor.removed'), life: 2000 })
        emit('changed')
      } catch {
        toast.add({ severity: 'error', summary: t('ui.acl_editor.failed_remove'), life: 3000 })
      }
    },
  })
}
</script>

<template>
  <div class="acl-editor">
    <ul class="acl-list">
      <li v-for="acl in acls" :key="`${acl.perm}-${acl.id}`" class="acl-row">
        <span class="acl-target">
          <i :class="acl.type === 'GROUP' ? 'pi pi-users' : 'pi pi-user'" />
          {{ acl.name }}
        </span>
        <Tag :value="permLabel(acl.perm)" severity="secondary" />
        <Button
          v-if="writable && !isImmutable(acl)"
          icon="pi pi-times"
          text
          rounded
          size="small"
          severity="danger"
          :aria-label="t('ui.acl_editor.remove')"
          @click="confirmRemove(acl)"
        />
        <i
          v-else-if="isImmutable(acl)"
          class="pi pi-lock acl-immutable"
          v-tooltip.top="immutableLabel(acl) || ''"
          :aria-label="immutableLabel(acl) || ''"
        />
      </li>
      <li v-if="acls.length === 0" class="acl-empty">{{ t('ui.acl_editor.none') }}</li>
    </ul>

    <div v-if="writable" class="acl-add">
      <AutoComplete
        v-model="selectedTarget"
        :suggestions="searchResults"
        optionLabel="name"
        :placeholder="t('ui.acl_editor.search_placeholder')"
        class="acl-add-target"
        @complete="completeSearch"
      >
        <template #option="{ option }">
          <span class="acl-option">
            <i :class="option.type === 'GROUP' ? 'pi pi-users' : 'pi pi-user'" />
            {{ option.name }}
          </span>
        </template>
      </AutoComplete>
      <Select v-model="selectedPerm" :options="permOptions" optionLabel="label" optionValue="value" class="acl-add-perm" />
      <Button
        :label="t('ui.acl_editor.add')"
        icon="pi pi-plus"
        size="small"
        :disabled="!selectedTarget"
        :loading="adding"
        @click="handleAdd"
      />
    </div>
  </div>
</template>

<style scoped>
.acl-list {
  list-style: none;
  margin: 0 0 0.75rem;
  padding: 0;
}
.acl-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0;
  border-bottom: 1px solid var(--p-content-border-color);
}
.acl-target {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  font-size: 0.875rem;
}
.acl-empty {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  padding: 0.375rem 0;
}
.acl-immutable {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  padding: 0 0.5rem;
}
.acl-add {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.5rem;
  flex-wrap: wrap;
}
.acl-add-target {
  flex: 1;
  min-width: 12rem;
}
.acl-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
