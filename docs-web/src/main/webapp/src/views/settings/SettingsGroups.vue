<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  listGroups,
  getGroup,
  createGroup,
  updateGroup,
  deleteGroup,
  addGroupMember,
  removeGroupMember,
  type GroupListItem,
  type GroupDetail,
} from '../../api/group'
import { listUsers } from '../../api/user'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const { data: groupsData, isLoading: loading, isError, refetch } = useQuery({
  queryKey: ['groups'],
  queryFn: () => listGroups().then((r) => r.data.groups),
})

const groups = computed(() => groupsData.value ?? [])

const { data: usersData } = useQuery({
  queryKey: ['users'],
  queryFn: () => listUsers().then((r) => r.data.users),
})

const usernameOptions = computed(() => (usersData.value ?? []).map((u) => u.username))

// Add group dialog
const showAddDialog = ref(false)
const addForm = ref({ name: '', parent: '' })
const addLoading = ref(false)

const parentOptions = computed(() => groups.value.map((g) => g.name))

function openAddDialog() {
  addForm.value = { name: '', parent: '' }
  showAddDialog.value = true
}

async function handleAdd() {
  if (!addForm.value.name) {
    toast.add({ severity: 'warn', summary: t('ui.groups.name_required'), life: 2000 })
    return
  }
  addLoading.value = true
  try {
    await createGroup(addForm.value.name, addForm.value.parent || undefined)
    queryClient.invalidateQueries({ queryKey: ['groups'] })
    showAddDialog.value = false
    toast.add({ severity: 'success', summary: t('ui.groups.group_created'), life: 2000 })
  } catch (error: unknown) {
    toast.add({ severity: 'error', summary: getMutationError(error, t('ui.groups.failed_create')), life: 3000 })
  } finally {
    addLoading.value = false
  }
}

// Edit (rename) group dialog
const showEditDialog = ref(false)
const editTarget = ref<GroupListItem | null>(null)
const editForm = ref({ name: '', parent: '' })
const editLoading = ref(false)

function openEditDialog(group: GroupListItem) {
  editTarget.value = group
  editForm.value = { name: group.name, parent: group.parent ?? '' }
  showEditDialog.value = true
}

const editParentOptions = computed(() =>
  groups.value.map((g) => g.name).filter((n) => n !== editTarget.value?.name),
)

async function handleEdit() {
  if (!editTarget.value) return
  if (!editForm.value.name) {
    toast.add({ severity: 'warn', summary: t('ui.groups.name_required'), life: 2000 })
    return
  }
  editLoading.value = true
  try {
    await updateGroup(editTarget.value.name, editForm.value.name, editForm.value.parent || undefined)
    queryClient.invalidateQueries({ queryKey: ['groups'] })
    showEditDialog.value = false
    toast.add({ severity: 'success', summary: t('ui.groups.group_updated'), life: 2000 })
  } catch (error: unknown) {
    toast.add({ severity: 'error', summary: getMutationError(error, t('ui.groups.failed_update')), life: 3000 })
  } finally {
    editLoading.value = false
  }
}

function confirmDelete(group: GroupListItem) {
  confirmDanger({
    message: t('ui.groups.delete_confirm', { name: group.name }),
    header: t('ui.groups.delete_group'),
    accept: async () => {
      try {
        await deleteGroup(group.name)
        queryClient.invalidateQueries({ queryKey: ['groups'] })
        toast.add({ severity: 'success', summary: t('ui.groups.group_deleted'), life: 2000 })
      } catch (error: unknown) {
        toast.add({ severity: 'error', summary: getMutationError(error, t('ui.groups.failed_delete')), life: 3000 })
      }
    },
  })
}

// Manage members dialog
const showMembersDialog = ref(false)
const membersTarget = ref<GroupListItem | null>(null)
const memberDetail = ref<GroupDetail | null>(null)
const membersLoading = ref(false)
const memberToAdd = ref<string | null>(null)
const addMemberLoading = ref(false)

const availableUsers = computed(() =>
  usernameOptions.value.filter((u) => !(memberDetail.value?.members ?? []).includes(u)),
)

async function openMembersDialog(group: GroupListItem) {
  membersTarget.value = group
  memberDetail.value = null
  memberToAdd.value = null
  showMembersDialog.value = true
  await loadMembers()
}

async function loadMembers() {
  if (!membersTarget.value) return
  membersLoading.value = true
  try {
    const res = await getGroup(membersTarget.value.name)
    memberDetail.value = res.data
  } catch {
    toast.add({ severity: 'error', summary: t('ui.groups.failed_load_members'), life: 3000 })
  } finally {
    membersLoading.value = false
  }
}

async function handleAddMember() {
  if (!membersTarget.value || !memberToAdd.value) return
  addMemberLoading.value = true
  try {
    await addGroupMember(membersTarget.value.name, memberToAdd.value)
    memberToAdd.value = null
    await loadMembers()
    toast.add({ severity: 'success', summary: t('ui.groups.member_added'), life: 2000 })
  } catch (error: unknown) {
    toast.add({ severity: 'error', summary: getMutationError(error, t('ui.groups.failed_add_member')), life: 3000 })
  } finally {
    addMemberLoading.value = false
  }
}

async function handleRemoveMember(username: string) {
  if (!membersTarget.value) return
  try {
    await removeGroupMember(membersTarget.value.name, username)
    await loadMembers()
    toast.add({ severity: 'success', summary: t('ui.groups.member_removed'), life: 2000 })
  } catch (error: unknown) {
    toast.add({ severity: 'error', summary: getMutationError(error, t('ui.groups.failed_remove_member')), life: 3000 })
  }
}

function getMutationError(error: unknown, fallback: string): string {
  const type = (error as { response?: { data?: { type?: string } } })?.response?.data?.type
  if (type === 'GroupAlreadyExists') return t('ui.groups.name_taken')
  if (type === 'ParentGroupNotFound') return t('ui.groups.parent_not_found')
  return fallback
}
</script>

<template>
  <div>
    <div class="groups-header">
      <h2>{{ t('ui.groups.title') }}</h2>
      <Button :label="t('ui.groups.add_group')" icon="pi pi-plus" size="small" @click="openAddDialog" />
    </div>

    <DataTable
      :value="groups"
      :loading="loading"
      stripedRows
      class="groups-table"
      size="small"
    >
      <Column :header="t('ui.groups.name')">
        <template #body="{ data }">
          <span class="group-name">
            <i class="pi pi-users" aria-hidden="true" />
            {{ data.name }}
          </span>
        </template>
      </Column>
      <Column :header="t('ui.groups.parent')">
        <template #body="{ data }">
          <span class="group-parent">{{ data.parent || '—' }}</span>
        </template>
      </Column>
      <Column header="" style="width: 148px">
        <template #body="{ data }">
          <span class="group-actions">
            <Button icon="pi pi-user-edit" text rounded size="small" severity="secondary" @click="openMembersDialog(data)" v-tooltip="t('ui.groups.manage_members')" :aria-label="t('ui.groups.manage_members')" />
            <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" @click="openEditDialog(data)" v-tooltip="t('edit')" :aria-label="t('edit')" />
            <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="confirmDelete(data)" v-tooltip="t('delete')" :aria-label="t('delete')" />
          </span>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-users" :message="t('ui.groups.no_groups')" />
      </template>
    </DataTable>

    <!-- Add group dialog -->
    <Dialog v-model:visible="showAddDialog" :header="t('ui.groups.add_group')" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label for="add-group-name">{{ t('ui.groups.name') }} *</label>
          <InputText id="add-group-name" v-model="addForm.name" class="w-full" autofocus />
          <small class="form-hint">{{ t('ui.groups.name_hint') }}</small>
        </div>
        <div class="form-field">
          <label for="add-group-parent">{{ t('ui.groups.parent') }}</label>
          <Select inputId="add-group-parent" v-model="addForm.parent" :options="parentOptions" :placeholder="t('ui.groups.no_parent')" showClear class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showAddDialog = false" />
        <Button :label="t('create')" icon="pi pi-check" :loading="addLoading" @click="handleAdd" />
      </template>
    </Dialog>

    <!-- Edit group dialog -->
    <Dialog v-model:visible="showEditDialog" :header="t('ui.groups.edit_group', { name: editTarget?.name })" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label for="edit-group-name">{{ t('ui.groups.name') }} *</label>
          <InputText id="edit-group-name" v-model="editForm.name" class="w-full" autofocus />
          <small class="form-hint">{{ t('ui.groups.name_hint') }}</small>
        </div>
        <div class="form-field">
          <label for="edit-group-parent">{{ t('ui.groups.parent') }}</label>
          <Select inputId="edit-group-parent" v-model="editForm.parent" :options="editParentOptions" :placeholder="t('ui.groups.no_parent')" showClear class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showEditDialog = false" />
        <Button :label="t('save')" icon="pi pi-check" :loading="editLoading" @click="handleEdit" />
      </template>
    </Dialog>

    <!-- Manage members dialog -->
    <Dialog v-model:visible="showMembersDialog" :header="t('ui.groups.members_of', { name: membersTarget?.name })" :style="{ width: '440px' }" modal>
      <div class="members-add">
        <Select v-model="memberToAdd" :options="availableUsers" :placeholder="t('ui.groups.select_user')" filter class="w-full" :aria-label="t('ui.groups.select_user')" />
        <Button :label="t('ui.groups.add_member')" icon="pi pi-plus" size="small" :loading="addMemberLoading" :disabled="!memberToAdd" @click="handleAddMember" />
      </div>

      <div v-if="membersLoading" class="members-loading">{{ t('loading') }}</div>
      <ul v-else-if="(memberDetail?.members?.length ?? 0) > 0" class="members-list">
        <li v-for="username in memberDetail?.members" :key="username" class="member-row">
          <span class="member-name"><i class="pi pi-user" aria-hidden="true" /> {{ username }}</span>
          <Button icon="pi pi-times" text rounded size="small" severity="danger" @click="handleRemoveMember(username)" v-tooltip="t('ui.groups.remove_member')" :aria-label="t('ui.groups.remove_member')" />
        </li>
      </ul>
      <div v-else class="members-empty">{{ t('ui.groups.no_members') }}</div>

      <template #footer>
        <Button :label="t('close')" severity="secondary" text @click="showMembersDialog = false" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.groups-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.25rem;
}
.groups-header h2 {
  margin: 0;
}

.groups-table {
  max-width: 100%;
}

.group-name {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-weight: 500;
}

.group-parent {
  color: var(--p-text-muted-color);
}

.group-actions {
  display: flex;
  gap: 0.125rem;
  justify-content: flex-end;
}

.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding-top: 0.5rem;
}
.form-field {
  margin-bottom: 1rem;
}
.form-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-color);
}
.form-hint {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.members-add {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  padding: 0.5rem 0 1rem;
}
.members-add :deep(.p-select) {
  flex: 1;
}

.members-loading,
.members-empty {
  color: var(--p-text-muted-color);
  font-size: 0.8125rem;
  padding: 0.5rem 0;
}

.members-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--p-content-border-color);
}
.member-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.375rem 0;
  border-bottom: 1px solid var(--p-content-border-color);
}
.member-name {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
}
</style>
