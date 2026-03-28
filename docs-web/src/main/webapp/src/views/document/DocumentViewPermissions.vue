<script setup lang="ts">
import { inject, computed, ref, watch, type Ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { type DocumentDetail, type Acl, type InheritedAcl } from '../../api/document'
import { addAcl, deleteAcl, searchAclTargets, type AclTarget } from '../../api/acl'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import TagBadge from '../../components/TagBadge.vue'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const doc = inject<Ref<DocumentDetail | null>>('document')!
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const acls = computed<Acl[]>(() => doc.value?.acls ?? [])
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
const searchQuery = ref('')
const searchResults = ref<AclTarget[]>([])
const selectedTarget = ref<AclTarget | null>(null)
const selectedPerm = ref<'READ' | 'WRITE'>('READ')
const addingAcl = ref(false)
let searchTimeout: ReturnType<typeof setTimeout>

const permOptions = [
  { label: 'Can view', value: 'READ' },
  { label: 'Can edit', value: 'WRITE' },
]

watch(searchQuery, (val) => {
  clearTimeout(searchTimeout)
  if (!val.trim()) {
    searchResults.value = []
    selectedTarget.value = null
    return
  }
  searchTimeout = setTimeout(async () => {
    try {
      const { data } = await searchAclTargets(val)
      const users = (data.users ?? []).map((u) => ({ ...u, type: 'USER' as const }))
      const groups = (data.groups ?? []).map((g) => ({ ...g, type: 'GROUP' as const }))
      searchResults.value = [...users, ...groups]
    } catch {
      searchResults.value = []
    }
  }, 300)
})

async function handleAdd() {
  if (!selectedTarget.value || !doc.value) return
  addingAcl.value = true
  try {
    await addAcl(doc.value.id, selectedPerm.value, selectedTarget.value.name, selectedTarget.value.type)
    queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
    toast.add({ severity: 'success', summary: 'Permission added', life: 2000 })
    searchQuery.value = ''
    selectedTarget.value = null
    searchResults.value = []
    selectedPerm.value = 'READ'
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to add permission', life: 3000 })
  } finally {
    addingAcl.value = false
  }
}

function confirmRemove(acl: Acl) {
  confirm.require({
    message: `Remove ${acl.perm.toLowerCase()} permission for "${acl.name}"?`,
    header: 'Remove permission',
    icon: 'pi pi-lock',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!doc.value) return
      try {
        await deleteAcl(doc.value.id, acl.perm, acl.id)
        queryClient.invalidateQueries({ queryKey: ['document', doc.value.id] })
        toast.add({ severity: 'success', summary: 'Permission removed', life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to remove permission', life: 3000 })
      }
    },
  })
}

function permLabel(perm: string) {
  return perm === 'WRITE' ? 'Can edit' : 'Can view'
}
function typeIcon(type: string) {
  return type === 'GROUP' ? 'pi pi-users' : 'pi pi-user'
}
</script>

<template>
  <div v-if="doc" class="permissions-view">

    <!-- Direct permissions -->
    <section class="perm-section">
      <h3>Direct permissions</h3>
      <p class="section-hint">Users and groups with explicit access to this document.</p>

      <div v-if="acls.length" class="acl-list">
        <div v-for="acl in acls" :key="acl.id + acl.perm" class="acl-row">
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
            v-tooltip="'Remove'"
          />
        </div>
      </div>
      <p v-else class="no-acl">No direct permissions set. Only the owner has access.</p>

      <!-- Add permission form -->
      <div v-if="doc.writable" class="add-acl-form">
        <h4>Add permission</h4>
        <div class="add-acl-row">
          <div class="add-acl-search">
            <InputText
              v-model="searchQuery"
              placeholder="Search user or group…"
              size="small"
              class="w-full"
            />
            <div v-if="searchResults.length" class="search-dropdown">
              <button
                v-for="result in searchResults"
                :key="result.id"
                class="search-result"
                :class="{ selected: selectedTarget?.id === result.id }"
                @click="selectedTarget = result; searchQuery = result.name; searchResults = []"
              >
                <i :class="typeIcon(result.type)" />
                {{ result.name }}
                <span class="result-type">{{ result.type }}</span>
              </button>
            </div>
          </div>
          <Select
            v-model="selectedPerm"
            :options="permOptions"
            optionLabel="label"
            optionValue="value"
            size="small"
            style="width: 130px"
          />
          <Button
            label="Add"
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
      <h3>Inherited from tags</h3>
      <p class="section-hint">These permissions are inherited from the tags applied to this document and cannot be changed here.</p>

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
  border: 1px solid var(--p-content-border-color);
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
  background: #dbeafe;
  color: #1d4ed8;
}
.badge-write {
  background: #fef3c7;
  color: #92400e;
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

.add-acl-search {
  position: relative;
  flex: 1;
}

.search-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  z-index: 20;
  background: white;
  border: 1px solid var(--p-content-border-color);
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  margin-top: 2px;
}

.search-result {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.5rem 0.75rem;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.875rem;
  text-align: left;
}
.search-result:hover,
.search-result.selected {
  background: var(--p-content-hover-background);
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
</style>
