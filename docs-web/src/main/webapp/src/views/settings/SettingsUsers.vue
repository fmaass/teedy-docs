<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listUsers, createUser, updateUser, deleteUser, type UserListItem } from '../../api/user'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const toast = useToast()
const confirm = useConfirm()

const users = ref<UserListItem[]>([])
const loading = ref(false)

async function loadUsers() {
  loading.value = true
  try {
    const { data } = await listUsers()
    users.value = data.users
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to load users', life: 3000 })
  } finally {
    loading.value = false
  }
}

onMounted(loadUsers)

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
    toast.add({ severity: 'warn', summary: 'All fields are required', life: 2000 })
    return
  }
  addLoading.value = true
  try {
    await createUser(addForm.value.username, addForm.value.password, addForm.value.email, addForm.value.storage_quota)
    await loadUsers()
    showAddDialog.value = false
    toast.add({ severity: 'success', summary: 'User created', life: 2000 })
  } catch (e: any) {
    const msg = e.response?.data?.type === 'AlreadyExistingUsername' ? 'Username already in use' : 'Failed to create user'
    toast.add({ severity: 'error', summary: msg, life: 3000 })
  } finally {
    addLoading.value = false
  }
}

// Edit user dialog
const showEditDialog = ref(false)
const editTarget = ref<UserListItem | null>(null)
const editForm = ref({ email: '', password: '' })
const editLoading = ref(false)

function openEditDialog(user: UserListItem) {
  editTarget.value = user
  editForm.value = { email: user.email, password: '' }
  showEditDialog.value = true
}

async function handleEdit() {
  if (!editTarget.value) return
  editLoading.value = true
  try {
    const data: { email?: string; password?: string } = { email: editForm.value.email }
    if (editForm.value.password) data.password = editForm.value.password
    await updateUser(editTarget.value.username, data)
    await loadUsers()
    showEditDialog.value = false
    toast.add({ severity: 'success', summary: 'User updated', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to update user', life: 3000 })
  } finally {
    editLoading.value = false
  }
}

function confirmDelete(user: UserListItem) {
  confirm.require({
    message: `Delete user "${user.username}"? This cannot be undone.`,
    header: 'Delete user',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        await deleteUser(user.username)
        await loadUsers()
        toast.add({ severity: 'success', summary: 'User deleted', life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to delete user', life: 3000 })
      }
    },
  })
}

function formatStorage(bytes: number) {
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + ' GB'
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(0) + ' MB'
  return (bytes / 1024).toFixed(0) + ' KB'
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString()
}
</script>

<template>
  <div>
    <div class="users-header">
      <h2>Users</h2>
      <Button label="Add user" icon="pi pi-plus" size="small" @click="openAddDialog" />
    </div>

    <div v-if="loading" class="p-4 text-center text-muted">Loading users…</div>

    <div v-else-if="users.length" class="users-table teedy-card">
      <div class="user-row user-row-head">
        <span>Username</span>
        <span>Email</span>
        <span>Storage</span>
        <span>Created</span>
        <span></span>
      </div>
      <div
        v-for="user in users"
        :key="user.username"
        class="user-row"
        :class="{ disabled: user.disabled }"
      >
        <span class="user-name">
          <i class="pi pi-user" />
          {{ user.username }}
          <span v-if="user.disabled" class="badge-disabled">disabled</span>
          <span v-if="user.totp_enabled" class="badge-totp" v-tooltip="'2FA enabled'">2FA</span>
        </span>
        <span class="user-email">{{ user.email }}</span>
        <span class="user-storage">
          {{ formatStorage(user.storage_current) }} / {{ formatStorage(user.storage_quota) }}
        </span>
        <span class="user-date">{{ formatDate(user.create_date) }}</span>
        <span class="user-actions">
          <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" @click="openEditDialog(user)" v-tooltip="'Edit'" />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="confirmDelete(user)" v-tooltip="'Delete'" />
        </span>
      </div>
    </div>

    <p v-else class="text-sm text-muted mt-4">No users found.</p>

    <!-- Add user dialog -->
    <Dialog v-model:visible="showAddDialog" header="Add user" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label>Username *</label>
          <InputText v-model="addForm.username" class="w-full" autofocus />
        </div>
        <div class="form-field">
          <label>Email *</label>
          <InputText v-model="addForm.email" type="email" class="w-full" />
        </div>
        <div class="form-field">
          <label>Password *</label>
          <Password v-model="addForm.password" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="showAddDialog = false" />
        <Button label="Create" icon="pi pi-check" :loading="addLoading" @click="handleAdd" />
      </template>
    </Dialog>

    <!-- Edit user dialog -->
    <Dialog v-model:visible="showEditDialog" :header="`Edit ${editTarget?.username}`" :style="{ width: '400px' }" modal>
      <div class="dialog-form">
        <div class="form-field">
          <label>Email</label>
          <InputText v-model="editForm.email" type="email" class="w-full" />
        </div>
        <div class="form-field">
          <label>New password <span class="text-muted">(leave blank to keep current)</span></label>
          <Password v-model="editForm.password" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="showEditDialog = false" />
        <Button label="Save" icon="pi pi-check" :loading="editLoading" @click="handleEdit" />
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

.users-table {
  overflow: hidden;
}

.user-row {
  display: grid;
  grid-template-columns: 1.5fr 2fr 1.2fr 1fr 80px;
  align-items: center;
  gap: 0.75rem;
  padding: 0.625rem 1rem;
  border-bottom: 1px solid #f3f4f6;
  font-size: 0.875rem;
}
.user-row:last-child {
  border-bottom: none;
}
.user-row-head {
  font-size: 0.75rem;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  background: #f9fafb;
}
.user-row.disabled {
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
  background: #fee2e2;
  color: #991b1b;
  padding: 0.1rem 0.375rem;
  border-radius: 999px;
  font-weight: 600;
  text-transform: uppercase;
}
.badge-totp {
  font-size: 0.625rem;
  background: #d1fae5;
  color: #065f46;
  padding: 0.1rem 0.375rem;
  border-radius: 999px;
  font-weight: 600;
}

.user-email,
.user-storage,
.user-date {
  color: #6b7280;
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
  color: #374151;
}

@media (max-width: 700px) {
  .user-row {
    grid-template-columns: 1fr 1fr 80px;
  }
  .user-storage,
  .user-date {
    display: none;
  }
}
</style>
