<script setup lang="ts">
import { ref, computed } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import api from '../../api/client'
import { listTags } from '../../api/tag'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import InputSwitch from 'primevue/inputswitch'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

interface Rule {
  id: string
  tag_id: string
  rule_type: string
  pattern: string
  order: number
  enabled: boolean
}

const showDialog = ref(false)
const editId = ref<string | null>(null)

const form = ref({
  tag_id: '',
  rule_type: 'TITLE_REGEX',
  pattern: '',
  order: '0',
  enabled: true,
})

const ruleTypes = [
  { label: 'Title regex', value: 'TITLE_REGEX' },
  { label: 'Filename regex', value: 'FILENAME_REGEX' },
  { label: 'Content regex', value: 'CONTENT_REGEX' },
]

const { data: rules, isLoading: loading } = useQuery({
  queryKey: ['tagmatchrules'],
  queryFn: () => api.get('/tagmatchrule').then((r) => r.data.rules as Rule[]),
})

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const { mutate: saveRule } = useMutation({
  mutationFn: (vars: { editId: string | null; params: URLSearchParams }) => {
    if (vars.editId) {
      return api.post(`/tagmatchrule/${vars.editId}`, vars.params)
    }
    return api.put('/tagmatchrule', vars.params)
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tagmatchrules'] })
    showDialog.value = false
    toast.add({ severity: 'success', summary: 'Rule saved', life: 2000 })
  },
  onError: (e: any) => {
    toast.add({ severity: 'error', summary: e.response?.data?.message || 'Failed to save rule', life: 3000 })
  },
})

const { mutate: deleteRule } = useMutation({
  mutationFn: (id: string) => api.delete(`/tagmatchrule/${id}`),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tagmatchrules'] })
    toast.add({ severity: 'success', summary: 'Rule deleted', life: 2000 })
  },
})

function openCreate() {
  editId.value = null
  form.value = { tag_id: '', rule_type: 'TITLE_REGEX', pattern: '', order: '0', enabled: true }
  showDialog.value = true
}

function openEdit(rule: Rule) {
  editId.value = rule.id
  form.value = { ...rule, order: String(rule.order) }
  showDialog.value = true
}

function handleSave() {
  const params = new URLSearchParams()
  params.set('tag_id', form.value.tag_id)
  params.set('rule_type', form.value.rule_type)
  params.set('pattern', form.value.pattern)
  params.set('order', form.value.order)
  params.set('enabled', String(form.value.enabled))
  saveRule({ editId: editId.value, params })
}

function handleDelete(rule: Rule) {
  confirm.require({
    message: `Delete this auto-tagging rule?`,
    header: 'Delete rule',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: () => deleteRule(rule.id),
  })
}

function getTagName(tagId: string) {
  return tags.value?.find((t) => t.id === tagId)?.name ?? tagId
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-4">
      <h2 style="margin: 0">Auto-tagging rules</h2>
      <Button label="Add rule" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>

    <p class="text-sm text-muted mb-4">
      Auto-tagging rules automatically apply tags to documents when their title, filename,
      or extracted content matches a regex pattern.
    </p>

    <DataTable :value="rules ?? []" :loading="loading" size="small" stripedRows>
      <Column header="Tag" style="width: 150px">
        <template #body="{ data }">{{ getTagName(data.tag_id) }}</template>
      </Column>
      <Column field="rule_type" header="Type" style="width: 140px" />
      <Column field="pattern" header="Pattern" />
      <Column field="order" header="Order" style="width: 70px" />
      <Column header="Enabled" style="width: 80px">
        <template #body="{ data }">
          <i :class="data.enabled ? 'pi pi-check-circle' : 'pi pi-times-circle'" :style="{ color: data.enabled ? '#22c55e' : '#ef4444' }" />
        </template>
      </Column>
      <Column header="" style="width: 100px">
        <template #body="{ data }">
          <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(data)" />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="handleDelete(data)" />
        </template>
      </Column>
      <template #empty>
        <div class="teedy-empty">
          <i class="pi pi-bolt" />
          <p>No auto-tagging rules defined yet</p>
        </div>
      </template>
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="editId ? 'Edit rule' : 'New rule'" modal style="width: 480px">
      <div class="form-field">
        <label>Tag</label>
        <Select
          v-model="form.tag_id"
          :options="tags ?? []"
          optionLabel="name"
          optionValue="id"
          placeholder="Select a tag"
          class="w-full"
        />
      </div>
      <div class="form-field">
        <label>Rule type</label>
        <Select v-model="form.rule_type" :options="ruleTypes" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <div class="form-field">
        <label>Regex pattern</label>
        <InputText v-model="form.pattern" class="w-full" placeholder="e.g. invoice.*\d{4}" />
      </div>
      <div class="form-field">
        <label>Execution order</label>
        <InputText v-model="form.order" type="number" class="w-full" />
      </div>
      <div class="form-field">
        <label class="flex items-center gap-2">
          <InputSwitch v-model="form.enabled" />
          Enabled
        </label>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="showDialog = false" />
        <Button :label="editId ? 'Save' : 'Create'" icon="pi pi-check" @click="handleSave" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.form-field {
  margin-bottom: 1rem;
}
.form-field > label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #374151;
}
</style>
