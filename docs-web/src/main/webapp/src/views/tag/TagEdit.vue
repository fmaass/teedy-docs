<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, updateTag, deleteTag } from '../../api/tag'
import InputText from 'primevue/inputtext'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ id: string }>()
const router = useRouter()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const name = ref('')
const color = ref('2aabd2')

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

function loadFromCache() {
  const tag = tags.value?.find((t) => t.id === props.id)
  if (tag) {
    name.value = tag.name
    color.value = tag.color.replace('#', '')
  }
}

watch([tags, () => props.id], loadFromCache, { immediate: true })

const { mutate: save, isPending: loading } = useMutation({
  mutationFn: () => updateTag(props.id, name.value, '#' + color.value),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tags'] })
    toast.add({ severity: 'success', summary: 'Tag updated', life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to update tag', life: 3000 })
  },
})

function handleDelete() {
  confirm.require({
    message: `Delete tag "${name.value}"? Documents will not be deleted.`,
    header: 'Delete tag',
    icon: 'pi pi-trash',
    acceptClass: 'p-button-danger',
    accept: () => {
      deleteTag(props.id).then(() => {
        queryClient.invalidateQueries({ queryKey: ['tags'] })
        toast.add({ severity: 'success', summary: 'Tag deleted', life: 2000 })
        router.push({ name: 'tags' })
      })
    },
  })
}
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
      <Button label="Save" icon="pi pi-check" :loading="loading" @click="save()" />
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
