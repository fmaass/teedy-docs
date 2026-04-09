<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, updateTag, deleteTag } from '../../api/tag'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ id: string }>()
const router = useRouter()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const name = ref('')
const color = ref('2aabd2')
const parent = ref<string | null>(null)

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const parentOptions = computed(() => [
  { label: '(none — root level)', value: null },
  ...(tags.value ?? [])
    .filter((t) => t.id !== props.id)
    .map((t) => ({ label: t.name, value: t.id })),
])

function loadFromCache() {
  const tag = tags.value?.find((t) => t.id === props.id)
  if (tag) {
    name.value = tag.name
    color.value = tag.color.replace('#', '')
    parent.value = tag.parent
  }
}

watch([tags, () => props.id], loadFromCache, { immediate: true })

const { mutate: save, isPending: loading } = useMutation({
  mutationFn: () => updateTag(props.id, name.value, '#' + color.value, parent.value ?? undefined),
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
  <div class="tag-edit-page">
    <div class="page-header">
      <h1>Edit tag</h1>
      <router-link :to="{ name: 'tags' }" class="back-link">
        <i class="pi pi-arrow-left" /> Back to tags
      </router-link>
    </div>

    <Card style="max-width: 480px">
      <template #content>
        <div class="form-field">
          <label>Name</label>
          <InputText v-model="name" class="w-full" />
        </div>
        <div class="form-field">
          <label>Color</label>
          <div class="color-row">
            <ColorPicker v-model="color" />
            <span class="color-preview" :style="{ background: '#' + color }">{{ name || 'Preview' }}</span>
          </div>
        </div>
        <div class="form-field">
          <label>Parent tag</label>
          <Select
            v-model="parent"
            :options="parentOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
            showClear
            placeholder="No parent (root level)"
          />
        </div>
        <div class="flex gap-2 mt-4">
          <Button label="Save" icon="pi pi-check" :loading="loading" @click="save()" />
          <Button label="Delete" icon="pi pi-trash" severity="danger" outlined @click="handleDelete" />
        </div>
      </template>
    </Card>
  </div>
</template>

<style scoped>
.tag-edit-page {
  padding: 1.5rem;
  max-width: 600px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.25rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}

.back-link {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
}
.back-link:hover {
  color: var(--p-primary-color);
  text-decoration: none;
}

.form-field {
  margin-bottom: 1rem;
}
.form-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-color);
}

.color-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.color-preview {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.75rem;
  border-radius: 4px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--teedy-tag-text);
}
</style>
