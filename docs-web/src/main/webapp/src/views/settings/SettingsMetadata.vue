<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import {
  listMetadata,
  createMetadata,
  updateMetadata,
  deleteMetadata,
  METADATA_TYPES,
  type MetadataDefinition,
  type MetadataType,
} from '../../api/metadata'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const { data: metadataData, isLoading, isError, refetch } = useQuery({
  queryKey: ['metadata'],
  queryFn: () => listMetadata().then((r) => r.data.metadata),
})

const fields = computed(() => metadataData.value ?? [])

const typeOptions = METADATA_TYPES.map((type) => ({
  label: t(`ui.metadata.type_${type.toLowerCase()}`),
  value: type,
}))

function typeLabel(type: string) {
  return t(`ui.metadata.type_${type.toLowerCase()}`)
}

// Add dialog
const showAddDialog = ref(false)
const newName = ref('')
const newType = ref<MetadataType>(METADATA_TYPES[0])

const addMutation = useMutation({
  mutationFn: () => createMetadata(newName.value.trim(), newType.value),
  onSuccess: () => {
    showAddDialog.value = false
    newName.value = ''
    newType.value = METADATA_TYPES[0]
    queryClient.invalidateQueries({ queryKey: ['metadata'] })
    toast.add({ severity: 'success', summary: t('ui.metadata.field_added'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.metadata.failed_add'), life: 3000 })
  },
})

function doAdd() {
  if (!newName.value.trim()) return
  addMutation.mutate()
}

// Inline rename
const renamingId = ref<string | null>(null)
const renameValue = ref('')

function startRename(field: MetadataDefinition) {
  renamingId.value = field.id
  renameValue.value = field.name
}

function cancelRename() {
  renamingId.value = null
  renameValue.value = ''
}

const renameMutation = useMutation({
  mutationFn: (vars: { id: string; name: string }) => updateMetadata(vars.id, vars.name),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['metadata'] })
    toast.add({ severity: 'success', summary: t('ui.metadata.field_updated'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.metadata.failed_update'), life: 3000 })
  },
  onSettled: () => cancelRename(),
})

function commitRename(id: string) {
  const name = renameValue.value.trim()
  if (!name) return cancelRename()
  renameMutation.mutate({ id, name })
}

// Delete
const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteMetadata(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['metadata'] })
    toast.add({ severity: 'success', summary: t('ui.metadata.field_deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.metadata.failed_delete'), life: 3000 })
  },
})

function confirmDelete(field: MetadataDefinition) {
  confirm.require({
    message: t('ui.metadata.delete_confirm', { name: field.name }),
    header: t('ui.metadata.delete_title'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: () => deleteMutation.mutate(field.id),
  })
}
</script>

<template>
  <div class="metadata-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.metadata.title') }}</h2>
        <p class="section-desc">{{ t('ui.metadata.description') }}</p>
      </div>
      <Button :label="t('ui.metadata.add_field')" icon="pi pi-plus" size="small" @click="showAddDialog = true" />
    </div>

    <DataTable :value="fields" stripedRows :loading="isLoading" class="metadata-table">
      <Column :header="t('ui.metadata.name')">
        <template #body="{ data }">
          <template v-if="renamingId === data.id">
            <InputText
              v-model="renameValue"
              size="small"
              class="rename-input"
              @keyup.enter="commitRename(data.id)"
              @keyup.escape="cancelRename"
              autofocus
            />
          </template>
          <span v-else class="field-name">{{ data.name }}</span>
        </template>
      </Column>
      <Column :header="t('ui.metadata.type')" style="width: 140px">
        <template #body="{ data }">
          <Tag :value="typeLabel(data.type)" severity="secondary" />
        </template>
      </Column>
      <Column header="" style="width: 96px">
        <template #body="{ data }">
          <div class="row-actions">
            <template v-if="renamingId === data.id">
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitRename(data.id)" :aria-label="t('ui.confirm_rename')" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelRename" :aria-label="t('ui.cancel_rename')" />
            </template>
            <template v-else>
              <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" @click="startRename(data)" :aria-label="t('rename')" />
              <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="confirmDelete(data)" :aria-label="t('ui.metadata.delete_title')" />
            </template>
          </div>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-tags" :message="t('ui.metadata.no_fields')" />
      </template>
    </DataTable>

    <!-- Add dialog -->
    <Dialog v-model:visible="showAddDialog" :header="t('ui.metadata.add_title')" :modal="true" :style="{ width: '480px' }">
      <div class="form-fields">
        <div class="form-field">
          <label for="metadata-name">{{ t('ui.metadata.name') }}</label>
          <InputText id="metadata-name" v-model="newName" maxlength="50" class="w-full" :placeholder="t('ui.metadata.name_placeholder')" @keyup.enter="doAdd" autofocus />
        </div>
        <div class="form-field">
          <label for="metadata-type">{{ t('ui.metadata.type') }}</label>
          <Select id="metadata-type" v-model="newType" :options="typeOptions" optionLabel="label" optionValue="value" class="w-full" />
          <small class="field-hint">{{ t('ui.metadata.type_immutable_hint') }}</small>
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showAddDialog = false" />
        <Button :label="t('add')" :disabled="!newName.trim()" :loading="addMutation.isPending.value" @click="doAdd" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.metadata-settings {
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

.field-name {
  font-size: 0.875rem;
}
.rename-input {
  width: 100%;
  font-size: 0.875rem;
}

.row-actions {
  display: flex;
  gap: 0.125rem;
}

.form-fields {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
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
.field-hint {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}
.w-full {
  width: 100%;
}
</style>
