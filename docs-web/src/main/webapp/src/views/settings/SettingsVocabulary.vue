<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import {
  listVocabularyNames,
  getVocabulary,
  createVocabularyEntry,
  updateVocabularyEntry,
  deleteVocabularyEntry,
  getVocabularyUsage,
  type VocabularyEntry,
} from '../../api/vocabulary'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

// Vocabulary names (admin-only list endpoint) and the currently selected one.
const { data: namesData, isLoading: namesLoading, isError: namesError, refetch: refetchNames } = useQuery({
  queryKey: ['vocabulary-names'],
  queryFn: () => listVocabularyNames().then((r) => r.data.names),
})

const names = computed(() => namesData.value ?? [])
const selectedName = ref<string | null>(null)

// Default the selection to the first vocabulary once the list resolves.
watch(names, (list) => {
  if (selectedName.value === null && list.length > 0) {
    selectedName.value = list[0]
  }
})

// Entries of the selected vocabulary.
const {
  data: entriesData,
  isLoading: entriesLoading,
  isError: entriesError,
  refetch: refetchEntries,
} = useQuery({
  queryKey: computed(() => ['vocabulary-entries', selectedName.value]),
  queryFn: () => getVocabulary(selectedName.value as string).then((r) => r.data.entries),
  enabled: computed(() => selectedName.value !== null),
})

const entries = computed<VocabularyEntry[]>(() => entriesData.value ?? [])

function invalidateEntries() {
  queryClient.invalidateQueries({ queryKey: ['vocabulary-entries', selectedName.value] })
  queryClient.invalidateQueries({ queryKey: ['vocabulary-names'] })
}

// Add entry dialog
const showAddDialog = ref(false)
const addValue = ref('')

const nextOrder = computed(() =>
  entries.value.reduce((max, e) => Math.max(max, e.order), -1) + 1,
)

const addMutation = useMutation({
  mutationFn: () =>
    createVocabularyEntry(selectedName.value as string, addValue.value.trim(), nextOrder.value),
  onSuccess: () => {
    showAddDialog.value = false
    addValue.value = ''
    invalidateEntries()
    toast.add({ severity: 'success', summary: t('ui.vocabulary.entry_added'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.vocabulary.failed_add'), life: 3000 })
  },
})

function doAdd() {
  if (!selectedName.value || !addValue.value.trim()) return
  addMutation.mutate()
}

// Create a NEW vocabulary namespace. A vocabulary exists only as long as it has at
// least one entry, so creating one means adding its first entry under a brand-new name.
// The name must match the backend pattern ^[a-z0-9-]+$.
const VOCABULARY_NAME_RE = /^[a-z0-9-]+$/
const showNewVocabularyDialog = ref(false)
const newVocabularyName = ref('')
const newVocabularyValue = ref('')

const newVocabularyNameValid = computed(
  () => VOCABULARY_NAME_RE.test(newVocabularyName.value.trim()) && newVocabularyName.value.trim().length <= 50,
)
const newVocabularyExists = computed(() => names.value.includes(newVocabularyName.value.trim()))

function openNewVocabularyDialog() {
  newVocabularyName.value = ''
  newVocabularyValue.value = ''
  showNewVocabularyDialog.value = true
}

const newVocabularyMutation = useMutation({
  mutationFn: () =>
    createVocabularyEntry(newVocabularyName.value.trim(), newVocabularyValue.value.trim(), 0),
  onSuccess: () => {
    const created = newVocabularyName.value.trim()
    showNewVocabularyDialog.value = false
    queryClient.invalidateQueries({ queryKey: ['vocabulary-names'] })
    // Focus the freshly created vocabulary.
    selectedName.value = created
    toast.add({ severity: 'success', summary: t('ui.vocabulary.vocabulary_created'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.vocabulary.failed_create'), life: 3000 })
  },
})

function doCreateVocabulary() {
  if (!newVocabularyNameValid.value || newVocabularyExists.value || !newVocabularyValue.value.trim()) {
    return
  }
  newVocabularyMutation.mutate()
}

// Inline edit of an entry value
const editingId = ref<string | null>(null)
const editValue = ref('')
const editOriginalValue = ref('')

function startEdit(entry: VocabularyEntry) {
  editingId.value = entry.id
  editValue.value = entry.value
  editOriginalValue.value = entry.value
}

function cancelEdit() {
  editingId.value = null
  editValue.value = ''
  editOriginalValue.value = ''
}

const editMutation = useMutation({
  mutationFn: (vars: { id: string; value: string }) =>
    updateVocabularyEntry(vars.id, { value: vars.value }),
  onSuccess: () => {
    invalidateEntries()
    toast.add({ severity: 'success', summary: t('ui.vocabulary.entry_updated'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.vocabulary.failed_update'), life: 3000 })
  },
  onSettled: () => cancelEdit(),
})

// Best-effort reference count for a warning snapshot. On failure return 0 so the
// destructive action is never blocked by a transient usage-lookup error.
async function fetchUsageCount(id: string): Promise<number> {
  try {
    const res = await getVocabularyUsage(id)
    return res.data.document_count
  } catch {
    return 0
  }
}

async function commitEdit(id: string) {
  const value = editValue.value.trim()
  if (!value) return cancelEdit()

  // No value change: nothing references anything new — commit directly.
  if (value === editOriginalValue.value) {
    editMutation.mutate({ id, value })
    return
  }

  // A value change may strand existing document metadata values pointing at the old
  // value. Warn with a reference-count snapshot before committing.
  const count = await fetchUsageCount(id)
  if (count > 0) {
    confirmDanger({
      header: t('ui.vocabulary.rename_title'),
      message: t('ui.vocabulary.rename_referenced_confirm', count, {
        named: { count, value: editOriginalValue.value },
      }),
      icon: 'pi pi-exclamation-triangle',
      accept: () => editMutation.mutate({ id, value }),
      reject: () => cancelEdit(),
    })
  } else {
    editMutation.mutate({ id, value })
  }
}

// Reorder: swap the order values of the entry with its neighbour.
const reorderMutation = useMutation({
  mutationFn: (vars: { a: VocabularyEntry; b: VocabularyEntry }) =>
    Promise.all([
      updateVocabularyEntry(vars.a.id, { order: vars.b.order }),
      updateVocabularyEntry(vars.b.id, { order: vars.a.order }),
    ]),
  onSuccess: () => invalidateEntries(),
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.vocabulary.failed_update'), life: 3000 })
  },
})

function moveUp(index: number) {
  if (index <= 0) return
  reorderMutation.mutate({ a: entries.value[index], b: entries.value[index - 1] })
}

function moveDown(index: number) {
  if (index >= entries.value.length - 1) return
  reorderMutation.mutate({ a: entries.value[index], b: entries.value[index + 1] })
}

// Delete
const deleteMutation = useMutation({
  mutationFn: (vars: { id: string; wasLast: boolean }) => deleteVocabularyEntry(vars.id),
  onSuccess: (_data, vars) => {
    // A vocabulary with no entries ceases to exist (the backend has no empty-namespace
    // concept). Deleting the last entry therefore removes the whole vocabulary: drop the
    // now-dangling selection so the picker re-defaults to another vocabulary.
    if (vars.wasLast) {
      selectedName.value = null
    }
    invalidateEntries()
    toast.add({ severity: 'success', summary: t('ui.vocabulary.entry_deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.vocabulary.failed_delete'), life: 3000 })
  },
})

async function confirmDelete(entry: VocabularyEntry) {
  const wasLast = entries.value.length === 1

  // A referenced entry strands the document metadata values pointing at it — surface
  // a reference-count snapshot so the admin deletes with eyes open.
  const count = await fetchUsageCount(entry.id)
  let message: string
  if (count > 0) {
    message = t('ui.vocabulary.delete_referenced_confirm', count, {
      named: { count, value: entry.value },
    })
  } else if (wasLast) {
    // Deleting the last entry removes the whole vocabulary — warn explicitly.
    message = t('ui.vocabulary.delete_last_confirm', { name: selectedName.value ?? '' })
  } else {
    message = t('ui.vocabulary.delete_confirm', { value: entry.value })
  }

  confirmDanger({
    message,
    header: t('ui.vocabulary.delete_title'),
    accept: () => deleteMutation.mutate({ id: entry.id, wasLast }),
  })
}
</script>

<template>
  <div class="vocabulary-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.vocabulary.title') }}</h2>
        <p class="section-desc">{{ t('ui.vocabulary.description') }}</p>
      </div>
      <div class="header-actions">
        <Button
          :label="t('ui.vocabulary.new_vocabulary')"
          icon="pi pi-folder-plus"
          size="small"
          severity="secondary"
          @click="openNewVocabularyDialog"
        />
        <Button
          :label="t('ui.vocabulary.add_entry')"
          icon="pi pi-plus"
          size="small"
          :disabled="!selectedName"
          @click="showAddDialog = true"
        />
      </div>
    </div>

    <div class="form-field vocabulary-picker">
      <label for="vocabulary-name">{{ t('ui.vocabulary.name') }}</label>
      <Select
        id="vocabulary-name"
        v-model="selectedName"
        :options="names"
        :loading="namesLoading"
        filter
        :placeholder="t('ui.vocabulary.select_placeholder')"
        class="w-full"
      />
      <ErrorState v-if="namesError" @retry="refetchNames()" />
    </div>

    <DataTable :value="entries" stripedRows :loading="entriesLoading" class="vocabulary-table">
      <Column :header="t('ui.vocabulary.value')">
        <template #body="{ data }">
          <template v-if="editingId === data.id">
            <InputText
              v-model="editValue"
              size="small"
              class="edit-input"
              @keyup.enter="commitEdit(data.id)"
              @keyup.escape="cancelEdit"
              autofocus
            />
          </template>
          <span v-else class="entry-value">{{ data.value }}</span>
        </template>
      </Column>
      <Column :header="t('ui.vocabulary.order')" style="width: 120px">
        <template #body="{ index }">
          <div class="row-actions">
            <Button
              icon="pi pi-arrow-up"
              text
              rounded
              size="small"
              severity="secondary"
              :disabled="index === 0"
              @click="moveUp(index)"
              :aria-label="t('ui.vocabulary.move_up')"
            />
            <Button
              icon="pi pi-arrow-down"
              text
              rounded
              size="small"
              severity="secondary"
              :disabled="index === entries.length - 1"
              @click="moveDown(index)"
              :aria-label="t('ui.vocabulary.move_down')"
            />
          </div>
        </template>
      </Column>
      <Column header="" style="width: 96px">
        <template #body="{ data }">
          <div class="row-actions">
            <template v-if="editingId === data.id">
              <Button icon="pi pi-check" text rounded size="small" severity="success" @click="commitEdit(data.id)" :aria-label="t('ui.confirm_rename')" />
              <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="cancelEdit" :aria-label="t('ui.cancel_rename')" />
            </template>
            <template v-else>
              <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" @click="startEdit(data)" :aria-label="t('rename')" />
              <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="confirmDelete(data)" :aria-label="t('ui.vocabulary.delete_title')" />
            </template>
          </div>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="entriesError" @retry="refetchEntries()" />
        <EmptyState v-else icon="pi pi-list" :message="t('ui.vocabulary.no_entries')" />
      </template>
    </DataTable>

    <!-- Add dialog -->
    <Dialog v-model:visible="showAddDialog" :header="t('ui.vocabulary.add_title')" :modal="true" :style="{ width: '480px' }">
      <div class="form-fields">
        <div class="form-field">
          <label for="vocabulary-value">{{ t('ui.vocabulary.value') }}</label>
          <InputText id="vocabulary-value" v-model="addValue" maxlength="500" class="w-full" :placeholder="t('ui.vocabulary.value_placeholder')" @keyup.enter="doAdd" autofocus />
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showAddDialog = false" />
        <Button :label="t('add')" :disabled="!addValue.trim()" :loading="addMutation.isPending.value" @click="doAdd" />
      </template>
    </Dialog>

    <!-- New vocabulary dialog -->
    <Dialog v-model:visible="showNewVocabularyDialog" :header="t('ui.vocabulary.new_title')" :modal="true" :style="{ width: '480px' }">
      <div class="form-fields">
        <div class="form-field">
          <label for="new-vocabulary-name">{{ t('ui.vocabulary.name') }}</label>
          <InputText id="new-vocabulary-name" v-model="newVocabularyName" maxlength="50" class="w-full" :placeholder="t('ui.vocabulary.name_placeholder')" autofocus />
          <small v-if="newVocabularyName.trim() && !newVocabularyNameValid" class="field-error">{{ t('ui.vocabulary.name_invalid') }}</small>
          <small v-else-if="newVocabularyExists" class="field-error">{{ t('ui.vocabulary.name_taken') }}</small>
          <small v-else class="field-hint">{{ t('ui.vocabulary.name_hint') }}</small>
        </div>
        <div class="form-field">
          <label for="new-vocabulary-value">{{ t('ui.vocabulary.first_value') }}</label>
          <InputText id="new-vocabulary-value" v-model="newVocabularyValue" maxlength="500" class="w-full" :placeholder="t('ui.vocabulary.value_placeholder')" @keyup.enter="doCreateVocabulary" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showNewVocabularyDialog = false" />
        <Button
          :label="t('create')"
          :disabled="!newVocabularyNameValid || newVocabularyExists || !newVocabularyValue.trim()"
          :loading="newVocabularyMutation.isPending.value"
          @click="doCreateVocabulary"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.vocabulary-settings {
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

.vocabulary-picker {
  margin-bottom: 1.25rem;
}

.entry-value {
  font-size: 0.875rem;
}
.edit-input {
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
.field-error {
  font-size: 0.75rem;
  color: var(--p-red-500);
}
.header-actions {
  display: flex;
  gap: 0.5rem;
}
.w-full {
  width: 100%;
}
</style>
