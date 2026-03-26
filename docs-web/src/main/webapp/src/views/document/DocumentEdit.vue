<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDocument, createDocument, updateDocument } from '../../api/document'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const toast = useToast()
const isEdit = !!props.id

const title = ref('')
const description = ref('')
const language = ref('eng')
const subject = ref('')
const loading = ref(false)

onMounted(async () => {
  if (isEdit && props.id) {
    const { data } = await getDocument(props.id, false)
    title.value = data.title || ''
    description.value = data.description || ''
    language.value = data.language || 'eng'
    subject.value = data.subject || ''
  }
})

async function handleSubmit() {
  loading.value = true
  try {
    const data: Record<string, string> = {
      title: title.value,
      description: description.value,
      language: language.value,
      subject: subject.value,
    }
    if (isEdit && props.id) {
      await updateDocument(props.id, data)
      toast.add({ severity: 'success', summary: 'Document updated', life: 2000 })
      router.push({ name: 'document-view', params: { id: props.id } })
    } else {
      data.create_date = String(Date.now())
      const { data: result } = await createDocument(data)
      toast.add({ severity: 'success', summary: 'Document created', life: 2000 })
      router.push({ name: 'document-view', params: { id: result.id } })
    }
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to save document', life: 3000 })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="document-edit">
    <div class="edit-header">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h2>{{ isEdit ? 'Edit Document' : 'New Document' }}</h2>
    </div>
    <form @submit.prevent="handleSubmit" class="edit-form">
      <div class="field">
        <label for="title">Title *</label>
        <InputText id="title" v-model="title" :fluid="true" required />
      </div>
      <div class="field">
        <label for="description">Description</label>
        <Textarea id="description" v-model="description" rows="4" :fluid="true" />
      </div>
      <div class="field">
        <label for="subject">Subject</label>
        <InputText id="subject" v-model="subject" :fluid="true" />
      </div>
      <div class="field">
        <label for="language">Language</label>
        <InputText id="language" v-model="language" :fluid="true" />
      </div>
      <div class="form-actions">
        <Button label="Cancel" severity="secondary" outlined @click="router.back()" />
        <Button type="submit" :label="isEdit ? 'Save' : 'Create'" :loading="loading" />
      </div>
    </form>
  </div>
</template>

<style scoped>
.document-edit {
  max-width: 700px;
}
.edit-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
}
.edit-header h2 {
  margin: 0;
}
.edit-form .field {
  margin-bottom: 1.25rem;
}
.edit-form .field label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
}
.form-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  margin-top: 1.5rem;
}
</style>
