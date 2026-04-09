<script setup lang="ts">
import { ref, computed } from 'vue'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { listApiKeys, createApiKey, deleteApiKey, type ApiKeyItem } from '../../api/apikey'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'

const toast = useToast()
const queryClient = useQueryClient()

const { data: keysData, isLoading } = useQuery({
  queryKey: ['apikeys'],
  queryFn: () => listApiKeys().then((r) => r.data.api_keys),
})

const keys = computed(() => keysData.value ?? [])

const showCreateDialog = ref(false)
const newKeyName = ref('')
const createdKey = ref('')
const showKeyDialog = ref(false)

const deleteConfirmId = ref('')
const deleteConfirmName = ref('')
const showDeleteDialog = ref(false)

const createMutation = useMutation({
  mutationFn: (name: string) => createApiKey(name),
  onSuccess: (res) => {
    createdKey.value = res.data.key
    showCreateDialog.value = false
    showKeyDialog.value = true
    newKeyName.value = ''
    queryClient.invalidateQueries({ queryKey: ['apikeys'] })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to create API key', life: 3000 })
  },
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteApiKey(id),
  onSuccess: () => {
    showDeleteDialog.value = false
    queryClient.invalidateQueries({ queryKey: ['apikeys'] })
    toast.add({ severity: 'success', summary: 'API key deleted', life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to delete API key', life: 3000 })
  },
})

function doCreate() {
  if (newKeyName.value.trim()) {
    createMutation.mutate(newKeyName.value.trim())
  }
}

function confirmDelete(key: ApiKeyItem) {
  deleteConfirmId.value = key.id
  deleteConfirmName.value = key.name
  showDeleteDialog.value = true
}

function doDelete() {
  deleteMutation.mutate(deleteConfirmId.value)
}

function copyKey() {
  navigator.clipboard.writeText(createdKey.value)
  toast.add({ severity: 'success', summary: 'Copied to clipboard', life: 2000 })
}

function formatDate(ts?: number) {
  if (!ts) return 'Never'
  return new Date(ts).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<template>
  <div class="apikeys-settings">
    <div class="section-header">
      <div>
        <h2>API Keys</h2>
        <p class="section-desc">Create keys to access the Teedy API programmatically.</p>
      </div>
      <Button label="Create key" icon="pi pi-plus" size="small" @click="showCreateDialog = true" />
    </div>

    <DataTable v-if="keys.length" :value="keys" stripedRows :loading="isLoading" class="keys-table">
      <Column field="name" header="Name">
        <template #body="{ data }">
          <span class="key-name">{{ data.name }}</span>
        </template>
      </Column>
      <Column header="Key" style="width: 160px">
        <template #body="{ data }">
          <code class="key-prefix">{{ data.prefix }}...</code>
        </template>
      </Column>
      <Column header="Last used" style="width: 180px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.last_used_date) }}</span>
        </template>
      </Column>
      <Column header="Created" style="width: 180px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 60px">
        <template #body="{ data }">
          <Button icon="pi pi-trash" text severity="danger" size="small" @click="confirmDelete(data)" aria-label="Delete API key" />
        </template>
      </Column>
    </DataTable>

    <div v-else-if="!isLoading" class="empty-state">
      <i class="pi pi-key" />
      <p>No API keys yet</p>
    </div>

    <!-- Create dialog -->
    <Dialog v-model:visible="showCreateDialog" header="Create API key" :modal="true" :style="{ width: '400px' }">
      <div class="form-field">
        <label for="key-name">Name</label>
        <InputText id="key-name" v-model="newKeyName" placeholder="e.g. CI/CD pipeline" class="w-full" @keyup.enter="doCreate" />
      </div>
      <template #footer>
        <Button label="Cancel" text @click="showCreateDialog = false" />
        <Button label="Create" :disabled="!newKeyName.trim()" :loading="createMutation.isPending.value" @click="doCreate" />
      </template>
    </Dialog>

    <!-- Key display dialog (shown once) -->
    <Dialog v-model:visible="showKeyDialog" header="API key created" :modal="true" :closable="false" :style="{ width: '520px' }">
      <div class="key-display">
        <p class="key-warning">Copy this key now. It will not be shown again.</p>
        <div class="key-value-row">
          <code class="key-value">{{ createdKey }}</code>
          <Button icon="pi pi-copy" text size="small" @click="copyKey" aria-label="Copy to clipboard" />
        </div>
      </div>
      <template #footer>
        <Button label="Done" @click="showKeyDialog = false" />
      </template>
    </Dialog>

    <!-- Delete confirmation -->
    <Dialog v-model:visible="showDeleteDialog" header="Delete API key" :modal="true" :style="{ width: '400px' }">
      <p>Delete "{{ deleteConfirmName }}"? Any integrations using this key will stop working.</p>
      <template #footer>
        <Button label="Cancel" text @click="showDeleteDialog = false" />
        <Button label="Delete" severity="danger" :loading="deleteMutation.isPending.value" @click="doDelete" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.apikeys-settings {
  max-width: 700px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.25rem;
}
.section-header h2 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}
.section-desc {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.key-name {
  font-weight: 500;
}

.key-prefix {
  font-family: monospace;
  font-size: 0.8125rem;
  background: var(--p-content-hover-background);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
}

.meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 3rem 1rem;
  color: var(--p-text-muted-color);
}
.empty-state i {
  font-size: 2.5rem;
  margin-bottom: 0.75rem;
}
.empty-state p {
  margin: 0;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.form-field label {
  font-size: 0.8125rem;
  font-weight: 500;
}
.w-full {
  width: 100%;
}

.key-display {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.key-warning {
  margin: 0;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--teedy-warning-text);
}
.key-value-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: var(--p-content-hover-background);
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
}
.key-value {
  font-family: monospace;
  font-size: 0.8125rem;
  word-break: break-all;
  flex: 1;
}
</style>
