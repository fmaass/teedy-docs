<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useTagStore } from '../../stores/tags'
import { createTag, updateTag, deleteTag } from '../../api/tag'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import ColorPicker from 'primevue/colorpicker'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'

const tagStore = useTagStore()
const toast = useToast()

const showDialog = ref(false)
const editId = ref<string | null>(null)
const name = ref('')
const color = ref('4caf50')

function openCreate() {
  editId.value = null
  name.value = ''
  color.value = '4caf50'
  showDialog.value = true
}

function openEdit(tag: any) {
  editId.value = tag.id
  name.value = tag.name
  color.value = tag.color.replace('#', '')
  showDialog.value = true
}

async function handleSave() {
  try {
    if (editId.value) {
      await updateTag(editId.value, name.value, '#' + color.value)
    } else {
      await createTag(name.value, '#' + color.value)
    }
    showDialog.value = false
    await tagStore.fetchTags()
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to save tag', life: 3000 })
  }
}

async function handleDelete(id: string) {
  if (!confirm('Delete this tag?')) return
  try {
    await deleteTag(id)
    await tagStore.fetchTags()
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to delete tag', life: 3000 })
  }
}

onMounted(() => tagStore.fetchTags())
</script>

<template>
  <div>
    <div class="list-header">
      <h2>Tags</h2>
      <Button icon="pi pi-plus" label="New Tag" @click="openCreate" />
    </div>

    <DataTable :value="tagStore.tags" dataKey="id" stripedRows>
      <Column header="Color" style="width: 60px">
        <template #body="{ data }">
          <span class="tag-dot" :style="{ background: data.color }" />
        </template>
      </Column>
      <Column field="name" header="Name" />
      <Column header="Actions" style="width: 140px">
        <template #body="{ data }">
          <Button icon="pi pi-pencil" text rounded @click="openEdit(data)" />
          <Button icon="pi pi-trash" text rounded severity="danger" @click="handleDelete(data.id)" />
        </template>
      </Column>
      <template #empty>
        <div style="text-align: center; padding: 2rem; color: var(--p-text-muted-color)">No tags found.</div>
      </template>
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="editId ? 'Edit Tag' : 'New Tag'" modal style="width: 400px">
      <div class="field">
        <label>Name</label>
        <InputText v-model="name" :fluid="true" />
      </div>
      <div class="field">
        <label>Color</label>
        <ColorPicker v-model="color" />
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
  margin-bottom: 1.5rem;
}
.list-header h2 { margin: 0; }
.tag-dot {
  display: inline-block;
  width: 16px;
  height: 16px;
  border-radius: 50%;
}
.field {
  margin-bottom: 1rem;
}
.field label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
}
</style>
