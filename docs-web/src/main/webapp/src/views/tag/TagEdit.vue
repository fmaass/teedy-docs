<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTagStore } from '../../stores/tags'
import { updateTag, deleteTag } from '../../api/tag'
import InputText from 'primevue/inputtext'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ id: string }>()
const router = useRouter()
const tagStore = useTagStore()
const toast = useToast()
const confirm = useConfirm()

const name = ref('')
const color = ref('2aabd2')
const loading = ref(false)

function loadTag() {
  const tag = tagStore.tags.find((t) => t.id === props.id)
  if (tag) {
    name.value = tag.name
    color.value = tag.color.replace('#', '')
  }
}

async function handleSave() {
  loading.value = true
  try {
    await updateTag(props.id, name.value, '#' + color.value)
    await tagStore.fetchTags()
    toast.add({ severity: 'success', summary: 'Tag updated', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to update tag', life: 3000 })
  } finally {
    loading.value = false
  }
}

function handleDelete() {
  confirm.require({
    message: `Delete tag "${name.value}"? Documents will not be deleted.`,
    header: 'Delete tag',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: async () => {
      await deleteTag(props.id)
      await tagStore.fetchTags()
      toast.add({ severity: 'success', summary: 'Tag deleted', life: 2000 })
      router.push({ name: 'tags' })
    },
  })
}

onMounted(loadTag)
watch(() => props.id, loadTag)
</script>

<template>
  <div style="max-width: 480px">
    <h2>Edit tag</h2>
    <div class="form-field">
      <label>Name</label>
      <InputText v-model="name" class="w-full" />
    </div>
    <div class="form-field">
      <label>Color</label>
      <ColorPicker v-model="color" />
    </div>
    <div class="flex gap-2 mt-4">
      <Button label="Save" icon="pi pi-check" :loading="loading" @click="handleSave" />
      <Button label="Delete" icon="pi pi-trash" severity="danger" outlined @click="handleDelete" />
    </div>
  </div>
</template>

<style scoped>
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
</style>
