<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { listUsers, createUser, updateUser, deleteUser, disableUserTotp, type UserListItem } from '../../api/user'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Password from 'primevue/password'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import { formatDate, formatStorage } from '../../utils/formatters'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const { data: usersData, isLoading: loading, isError, refetch } = useQuery({
  queryKey: ['users'],
  queryFn: () => listUsers().then((r) => r.data.users),
})

const users = computed(() => usersData.value ?? [])

// Add user dialog
const showAddDialog = ref(false)
const addForm = ref({ username: '', password: '', email: '', storage_quota: 1000000000 })
const addLoading = ref(false)

function openAddDialog() {
  addForm.value = { username: '', password: '', email: '', storage_quota: 1000000000 }
  showAddDialog.value = true
}

async function handleAdd() {
  if (!addForm.value.username || !addForm.value.password || !addForm.value.email) {
    toast.add({ severity: 'warn', summary: t('ui.users.all_fields_required'), life: 2000 })
    return
  }
  addLoading.value = true
  try {
    await createUser(addForm.value.username, addForm.value.password, addForm.value.email, addForm.value.storage_quota)
    queryClient.invalidateQueries({ queryKey: ['users'] })
    showAddDialog.value = false
    toast.add({ severity: 'success', summary: t('ui.users.user_created'), life: 2000 })
  } catch (error: unknown) {
    const msg = getCreateUserErrorMessage(error)
    toast.add({ severity: 'error', summary: msg, life: 3000 })
  } finally {
    addLoading.value = false
  }
}

// Edit user dialog
const showEditDialog = ref(false)
const editTarget = ref<UserListItem | null>(null)
const editForm = ref({ email: '', password: '', storage_quota: 0 })
const editLoading = ref(false)

function openEditDialog(user: UserListItem) {
  editTarget.value = user
  // Pre-fill the quota from the user's current storage_quota so a save without a change
  // is a no-op on the stored value.
  editForm.value = { email: user.email, password: '', storage_quota: user.storage_quota }
  showEditDialog.value = true
}

async function handleEdit() {
  if (!editTarget.value) return
  editLoading.value = true
  try {
    const data: { email?: string; password?: string; storage_quota?: number } = {
      email: editForm.value.email,
      storage_quota: editForm.value.storage_quota,
    }
    if (editForm.value.password) data.password = editForm.value.password
    await updateUser(editTarget.value.username, data)
    queryClient.invalidateQueries({ queryKey: ['users'] })
    showEditDialog.value = false
    toast.add({ severity: 'success', summary: t('ui.users.user_updated'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.users.failed_update'), life: 3000 })
  } finally {
    editLoading.value = false
  }
}

function confirmDelete(user: UserListItem) {
  confirmDanger({
    message: t('ui.users.delete_confirm', { username: user.username }),
    header: t('ui.users.delete_user'),
    accept: async () => {
      try {
        await deleteUser(user.username)
        queryClient.invalidateQueries({ queryKey: ['users'] })
        toast.add({ severity: 'success', summary: t('ui.users.user_deleted'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.users.failed_delete'), life: 3000 })
      }
    },
  })
}

// Disable or re-enable an account. Disabling is a soft, per-request block that
// preserves the user's documents (unlike delete); re-enabling resurrects old
// sessions and API keys, so it is not treated as a destructive action.
function toggleDisabled(user: UserListItem) {
  if (user.disabled) {
    void setDisabled(user, false)
    return
  }
  confirmDanger({
    message: t('ui.users.disable_user_message', { username: user.username }),
    header: t('ui.users.disable_user_title'),
    icon: 'pi pi-ban',
    accept: () => setDisabled(user, true),
  })
}

async function setDisabled(user: UserListItem, disabled: boolean) {
  try {
    await updateUser(user.username, { disabled })
    queryClient.invalidateQueries({ queryKey: ['users'] })
    toast.add({
      severity: 'success',
      summary: disabled ? t('ui.users.user_disabled') : t('ui.users.user_enabled'),
      life: 2000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: disabled ? t('ui.users.failed_disable_user') : t('ui.users.failed_enable_user'),
      life: 3000,
    })
  }
}

function confirmDisableTotp(user: UserListItem) {
  confirmDanger({
    message: t('ui.users.disable_totp_message'),
    header: t('ui.users.disable_totp_title'),
    icon: 'pi pi-shield',
    accept: async () => {
      try {
        await disableUserTotp(user.username)
        queryClient.invalidateQueries({ queryKey: ['users'] })
        toast.add({ severity: 'success', summary: t('ui.users.totp_disabled'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.users.failed_disable_totp'), life: 3000 })
      }
    },
  })
}

function getCreateUserErrorMessage(error: unknown): string {
  const maybeType = (error as { response?: { data?: { type?: string } } })?.response?.data?.type
  return maybeType === 'AlreadyExistingUsername' ? t('ui.users.username_taken') : t('ui.users.failed_create')
}

function userRowClass(data: UserListItem): string {
  return data.disabled ? 'row-disabled' : ''
}

// The backend force-ignores disabling the guest user or any ADMIN user
// (UserResource.update). Hide the toggle for those rows so it never presents a
// false-success affordance (a toast with no state change).
const GUEST_USERNAME = 'guest'
function canToggleDisabled(data: UserListItem): boolean {
  return data.username !== GUEST_USERNAME && !data.admin
}

</script>

<template>
  <div>
    <div class="users-header">
      <h2>{{ t('ui.users.title') }}</h2>
      <Button :label="t('ui.users.add_user')" icon="pi pi-plus" size="small" @click="openAddDialog" />
    </div>

    <p class="users-hint"><i class="pi pi-info-circle" aria-hidden="true" /> {{ t('ui.users.disable_hint') }}</p>

    <DataTable
      :value="users"
      :loading="loading"
      :rowClass="userRowClass"
      stripedRows
      class="users-table"
      size="small"
    >
      <Column :header="t('ui.users.username')">
        <template #body="{ data }">
          <span class="user-name">
            <i class="pi pi-user" aria-hidden="true" />
            {{ data.username }}
            <span v-if="data.disabled" class="badge-disabled">{{ t('disabled') }}</span>
            <span v-if="data.totp_enabled" class="badge-totp" v-tooltip="t('ui.users.totp_enabled')">2FA</span>
          </span>
        </template>
      </Column>
      <Column field="email" :header="t('ui.users.email')">
        <template #body="{ data }">
          <span class="user-email">{{ data.email }}</span>
        </template>
      </Column>
      <Column :header="t('ui.users.storage')">
        <template #body="{ data }">
          <span class="user-storage">
            {{ formatStorage(data.storage_current) }} / {{ formatStorage(data.storage_quota) }}
          </span>
        </template>
      </Column>
      <Column :header="t('ui.users.created')">
        <template #body="{ data }">
          <span class="user-date">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 160px">
        <template #body="{ data }">
          <span class="user-actions">
            <Button v-if="data.totp_enabled" icon="pi pi-shield" text rounded size="small" severity="warn" @click="confirmDisableTotp(data)" v-tooltip="t('ui.users.disable_totp_btn')" :aria-label="t('ui.users.disable_totp_btn')" />
            <Button
              v-if="canToggleDisabled(data)"
              :icon="data.disabled ? 'pi pi-check-circle' : 'pi pi-ban'"
              text rounded size="small"
              :severity="data.disabled ? 'success' : 'warn'"
              @click="toggleDisabled(data)"
              v-tooltip="data.disabled ? t('ui.users.enable_user_btn') : t('ui.users.disable_user_btn')"
              :aria-label="data.disabled ? t('ui.users.enable_user_btn') : t('ui.users.disable_user_btn')"
            />
            <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" @click="openEditDialog(data)" v-tooltip="t('edit')" :aria-label="t('edit')" />
            <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="confirmDelete(data)" v-tooltip="t('delete')" :aria-label="t('delete')" />
          </span>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-users" :message="t('ui.users.no_users')" />
      </template>
    </DataTable>

    <!-- Add user dialog -->
    <Dialog v-model:visible="showAddDialog" :header="t('ui.users.add_user')" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label for="add-user-name">{{ t('ui.users.username') }} *</label>
          <InputText id="add-user-name" v-model="addForm.username" class="w-full" autofocus />
        </div>
        <div class="form-field">
          <label for="add-user-email">{{ t('ui.users.email') }} *</label>
          <InputText id="add-user-email" v-model="addForm.email" type="email" class="w-full" />
        </div>
        <div class="form-field">
          <label for="add-user-pass">{{ t('ui.users.password') }} *</label>
          <Password v-model="addForm.password" inputId="add-user-pass" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'new-password' }" inputClass="w-full" class="w-full" />
        </div>
        <div class="form-field">
          <label for="add-user-quota">{{ t('ui.users.storage_quota') }}</label>
          <InputNumber id="add-user-quota" v-model="addForm.storage_quota" :useGrouping="true" suffix=" B" class="w-full" :min="0" />
          <small class="field-hint">{{ t('ui.users.storage_quota_hint', { human: formatStorage(addForm.storage_quota) }) }}</small>
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showAddDialog = false" />
        <Button :label="t('create')" icon="pi pi-check" :loading="addLoading" @click="handleAdd" />
      </template>
    </Dialog>

    <!-- Edit user dialog -->
    <Dialog v-model:visible="showEditDialog" :header="t('ui.users.edit_user', { username: editTarget?.username })" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label for="edit-user-email">{{ t('ui.users.email') }}</label>
          <InputText id="edit-user-email" v-model="editForm.email" type="email" class="w-full" />
        </div>
        <div class="form-field">
          <label for="edit-user-pass">{{ t('ui.account.new_password') }} <span class="text-muted">({{ t('ui.users.new_password_hint') }})</span></label>
          <Password v-model="editForm.password" inputId="edit-user-pass" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'new-password' }" inputClass="w-full" class="w-full" />
        </div>
        <div class="form-field">
          <label for="edit-user-quota">{{ t('ui.users.storage_quota') }}</label>
          <InputNumber id="edit-user-quota" v-model="editForm.storage_quota" :useGrouping="true" suffix=" B" class="w-full" :min="0" />
          <small class="field-hint">{{ t('ui.users.storage_quota_hint', { human: formatStorage(editForm.storage_quota) }) }}</small>
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showEditDialog = false" />
        <Button :label="t('save')" icon="pi pi-check" :loading="editLoading" @click="handleEdit" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.users-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.25rem;
}
.users-header h2 {
  margin: 0;
}

.users-hint {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  margin: 0 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.users-table {
  max-width: 100%;
}

:deep(.row-disabled) {
  opacity: 0.6;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-weight: 500;
}

.badge-disabled {
  font-size: 0.625rem;
  background: var(--teedy-danger-bg);
  color: var(--teedy-danger-text);
  padding: 0.1rem 0.375rem;
  border-radius: 999px;
  font-weight: 600;
  text-transform: uppercase;
}
.badge-totp {
  font-size: 0.625rem;
  background: var(--teedy-success-bg);
  color: var(--teedy-success-text);
  padding: 0.1rem 0.375rem;
  border-radius: 999px;
  font-weight: 600;
}

.user-email,
.user-storage,
.user-date {
  color: var(--p-text-muted-color);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-actions {
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

</style>
