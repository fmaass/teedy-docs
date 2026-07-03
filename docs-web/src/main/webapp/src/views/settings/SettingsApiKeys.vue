<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient, useMutation } from '@tanstack/vue-query'
import { listApiKeys, createApiKey, deleteApiKey, type ApiKeyItem } from '../../api/apikey'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const { data: keysData, isLoading, isError, refetch } = useQuery({
  queryKey: ['apikeys'],
  queryFn: () => listApiKeys().then((r) => r.data.api_keys),
})

const keys = computed(() => keysData.value ?? [])

const showCreateDialog = ref(false)
const newKeyName = ref('')
const createdKey = ref('')
const showKeyDialog = ref(false)


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
    toast.add({ severity: 'error', summary: t('ui.apikeys.failed_create'), life: 3000 })
  },
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteApiKey(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['apikeys'] })
    toast.add({ severity: 'success', summary: t('ui.apikeys.key_deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.apikeys.failed_delete'), life: 3000 })
  },
})

function doCreate() {
  if (newKeyName.value.trim()) {
    createMutation.mutate(newKeyName.value.trim())
  }
}

function confirmDelete(key: ApiKeyItem) {
  confirm.require({
    message: t('ui.apikeys.delete_confirm', { name: key.name }),
    header: t('ui.apikeys.delete_title'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: () => deleteMutation.mutate(key.id),
  })
}

function copyKey() {
  navigator.clipboard.writeText(createdKey.value)
  toast.add({ severity: 'success', summary: t('ui.apikeys.copied'), life: 2000 })
}

function formatDate(ts?: number) {
  if (!ts) return t('ui.apikeys.never')
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
        <h2>{{ t('ui.apikeys.title') }}</h2>
        <p class="section-desc">{{ t('ui.apikeys.description') }}</p>
      </div>
      <Button :label="t('ui.apikeys.create_key')" icon="pi pi-plus" size="small" @click="showCreateDialog = true" />
    </div>

    <DataTable :value="keys" stripedRows :loading="isLoading" class="keys-table">
      <Column field="name" :header="t('ui.apikeys.name')">
        <template #body="{ data }">
          <span class="key-name">{{ data.name }}</span>
        </template>
      </Column>
      <Column :header="t('ui.apikeys.key')" style="width: 160px">
        <template #body="{ data }">
          <code class="key-prefix">{{ data.prefix }}...</code>
        </template>
      </Column>
      <Column :header="t('ui.apikeys.last_used')" style="width: 180px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.last_used_date) }}</span>
        </template>
      </Column>
      <Column :header="t('ui.apikeys.created')" style="width: 180px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 60px">
        <template #body="{ data }">
          <Button icon="pi pi-trash" text severity="danger" size="small" @click="confirmDelete(data)" :aria-label="t('ui.apikeys.delete_title')" />
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-key" :message="t('ui.apikeys.no_keys')" />
      </template>
    </DataTable>

    <!-- Create dialog -->
    <Dialog v-model:visible="showCreateDialog" :header="t('ui.apikeys.create_title')" :modal="true" :style="{ width: '400px' }">
      <div class="form-field">
        <label for="key-name">{{ t('ui.apikeys.name') }}</label>
        <InputText id="key-name" v-model="newKeyName" :placeholder="t('ui.apikeys.name_placeholder')" class="w-full" @keyup.enter="doCreate" />
      </div>
      <template #footer>
        <Button :label="t('cancel')" text @click="showCreateDialog = false" />
        <Button :label="t('create')" :disabled="!newKeyName.trim()" :loading="createMutation.isPending.value" @click="doCreate" />
      </template>
    </Dialog>

    <!-- Key display dialog (shown once) -->
    <Dialog v-model:visible="showKeyDialog" :header="t('ui.apikeys.key_created_title')" :modal="true" :closable="false" :style="{ width: '520px' }">
      <div class="key-display">
        <p class="key-warning">{{ t('ui.apikeys.copy_warning') }}</p>
        <div class="key-value-row">
          <code class="key-value">{{ createdKey }}</code>
          <Button icon="pi pi-copy" text size="small" @click="copyKey" :aria-label="t('ui.copy_to_clipboard')" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('ui.apikeys.done')" @click="showKeyDialog = false" />
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
