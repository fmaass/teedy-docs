<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../../api/client'
import { useTagStore } from '../../stores/tags'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import InputSwitch from 'primevue/inputswitch'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'

const tagStore = useTagStore()
const toast = useToast()

interface Rule {
  id: string
  tag_id: string
  rule_type: string
  pattern: string
  order: number
  enabled: boolean
}

const rules = ref<Rule[]>([])
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
  { label: 'Title Regex', value: 'TITLE_REGEX' },
  { label: 'Filename Regex', value: 'FILENAME_REGEX' },
  { label: 'Content Regex', value: 'CONTENT_REGEX' },
]

async function loadRules() {
  const { data } = await api.get('/tagmatchrule')
  rules.value = data.rules
}

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

async function handleSave() {
  try {
    const params = new URLSearchParams()
    params.set('tag_id', form.value.tag_id)
    params.set('rule_type', form.value.rule_type)
    params.set('pattern', form.value.pattern)
    params.set('order', form.value.order)
    params.set('enabled', String(form.value.enabled))

    if (editId.value) {
      await api.post(`/tagmatchrule/${editId.value}`, params)
    } else {
      await api.put('/tagmatchrule', params)
    }
    showDialog.value = false
    await loadRules()
  } catch (e: any) {
    toast.add({ severity: 'error', summary: e.response?.data?.message || 'Failed to save rule', life: 3000 })
  }
}

async function handleDelete(id: string) {
  if (!confirm('Delete this rule?')) return
  await api.delete(`/tagmatchrule/${id}`)
  await loadRules()
}

function getTagName(tagId: string) {
  return tagStore.tags.find((t) => t.id === tagId)?.name ?? tagId
}

onMounted(() => {
  loadRules()
  tagStore.fetchTags()
})
</script>

<template>
  <div>
    <div class="list-header">
      <h3>Auto-Tagging Rules</h3>
      <Button icon="pi pi-plus" label="New Rule" @click="openCreate" />
    </div>

    <DataTable :value="rules" dataKey="id" stripedRows>
      <Column header="Tag">
        <template #body="{ data }">{{ getTagName(data.tag_id) }}</template>
      </Column>
      <Column field="rule_type" header="Type" />
      <Column field="pattern" header="Pattern" />
      <Column field="order" header="Order" style="width: 80px" />
      <Column header="Enabled" style="width: 80px">
        <template #body="{ data }">
          <i :class="data.enabled ? 'pi pi-check-circle' : 'pi pi-times-circle'" />
        </template>
      </Column>
      <Column header="Actions" style="width: 140px">
        <template #body="{ data }">
          <Button icon="pi pi-pencil" text rounded @click="openEdit(data)" />
          <Button icon="pi pi-trash" text rounded severity="danger" @click="handleDelete(data.id)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="editId ? 'Edit Rule' : 'New Rule'" modal style="width: 500px">
      <div class="field">
        <label>Tag</label>
        <Select
          v-model="form.tag_id"
          :options="tagStore.tags"
          optionLabel="name"
          optionValue="id"
          placeholder="Select a tag"
          :fluid="true"
        />
      </div>
      <div class="field">
        <label>Rule Type</label>
        <Select
          v-model="form.rule_type"
          :options="ruleTypes"
          optionLabel="label"
          optionValue="value"
          :fluid="true"
        />
      </div>
      <div class="field">
        <label>Regex Pattern</label>
        <InputText v-model="form.pattern" :fluid="true" />
      </div>
      <div class="field">
        <label>Order</label>
        <InputText v-model="form.order" type="number" :fluid="true" />
      </div>
      <div class="field-switch">
        <InputSwitch v-model="form.enabled" />
        <label>Enabled</label>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" outlined @click="showDialog = false" />
        <Button :label="editId ? 'Save' : 'Create'" @click="handleSave" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.list-header h3 { margin: 0; }
.field {
  margin-bottom: 1rem;
}
.field label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
}
.field-switch {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
</style>
