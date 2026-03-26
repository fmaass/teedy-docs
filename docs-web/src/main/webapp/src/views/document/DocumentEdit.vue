<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getDocument, createDocument, updateDocument } from '../../api/document'
import { useTagStore } from '../../stores/tags'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import MultiSelect from 'primevue/multiselect'
import Button from 'primevue/button'
import FileUpload from 'primevue/fileupload'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const toast = useToast()
const tagStore = useTagStore()
const isEdit = computed(() => !!props.id)

const form = ref({
  title: '',
  description: '',
  subject: '',
  identifier: '',
  publisher: '',
  format: '',
  source: '',
  type: '',
  coverage: '',
  rights: '',
  language: 'eng',
  create_date: new Date(),
  tags: [] as string[],
})

const loading = ref(false)
const showAdvanced = ref(false)

const languages = [
  { label: 'English', value: 'eng' },
  { label: 'French', value: 'fra' },
  { label: 'German', value: 'deu' },
  { label: 'Spanish', value: 'spa' },
  { label: 'Italian', value: 'ita' },
  { label: 'Portuguese', value: 'por' },
  { label: 'Chinese (Simplified)', value: 'zho' },
  { label: 'Japanese', value: 'jpn' },
  { label: 'Korean', value: 'kor' },
  { label: 'Arabic', value: 'ara' },
  { label: 'Russian', value: 'rus' },
  { label: 'Polish', value: 'pol' },
]

const tagOptions = computed(() =>
  tagStore.tags.map((t) => ({ label: t.name, value: t.id }))
)

onMounted(async () => {
  tagStore.fetchTags()
  if (isEdit.value && props.id) {
    const { data } = await getDocument(props.id, false)
    form.value.title = data.title || ''
    form.value.description = data.description || ''
    form.value.subject = data.subject || ''
    form.value.identifier = data.identifier || ''
    form.value.publisher = data.publisher || ''
    form.value.format = data.format || ''
    form.value.source = data.source || ''
    form.value.type = data.type || ''
    form.value.coverage = data.coverage || ''
    form.value.rights = data.rights || ''
    form.value.language = data.language || 'eng'
    form.value.create_date = new Date(data.create_date)
    form.value.tags = data.tags?.map((t) => t.id) || []
  }
})

async function handleSubmit() {
  if (!form.value.title.trim()) {
    toast.add({ severity: 'warn', summary: 'Title is required', life: 2000 })
    return
  }

  loading.value = true
  try {
    const data: Record<string, string> = {
      title: form.value.title,
      description: form.value.description,
      language: form.value.language,
      create_date: String(form.value.create_date.getTime()),
    }

    if (form.value.subject) data.subject = form.value.subject
    if (form.value.identifier) data.identifier = form.value.identifier
    if (form.value.publisher) data.publisher = form.value.publisher
    if (form.value.format) data.format = form.value.format
    if (form.value.source) data.source = form.value.source
    if (form.value.type) data.type = form.value.type
    if (form.value.coverage) data.coverage = form.value.coverage
    if (form.value.rights) data.rights = form.value.rights

    const params = new URLSearchParams()
    Object.entries(data).forEach(([k, v]) => params.append(k, v))
    form.value.tags.forEach((tagId) => params.append('tags', tagId))

    let resultId: string
    if (isEdit.value && props.id) {
      await updateDocument(props.id, data)
      resultId = props.id
      toast.add({ severity: 'success', summary: 'Document updated', life: 2000 })
    } else {
      const { data: result } = await createDocument(data)
      resultId = result.id
      toast.add({ severity: 'success', summary: 'Document created', life: 2000 })
    }
    router.push({ name: 'document-view', params: { id: resultId } })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to save document', life: 3000 })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="doc-edit">
    <header class="doc-edit-header">
      <h1>{{ isEdit ? 'Edit document' : 'New document' }}</h1>
      <div class="doc-edit-actions">
        <Button label="Cancel" severity="secondary" text @click="router.back()" />
        <Button label="Save" icon="pi pi-check" :loading="loading" @click="handleSubmit" />
      </div>
    </header>

    <form @submit.prevent="handleSubmit" class="doc-edit-form teedy-card p-4">
      <!-- Primary fields -->
      <div class="form-field">
        <label for="edit-title">Title *</label>
        <InputText id="edit-title" v-model="form.title" class="w-full" autofocus />
      </div>

      <div class="form-field">
        <label for="edit-desc">Description</label>
        <Textarea id="edit-desc" v-model="form.description" rows="4" class="w-full" autoResize />
      </div>

      <div class="form-row">
        <div class="form-field">
          <label for="edit-date">Creation date</label>
          <DatePicker id="edit-date" v-model="form.create_date" dateFormat="yy-mm-dd" class="w-full" />
        </div>
        <div class="form-field">
          <label for="edit-lang">Language</label>
          <Select
            id="edit-lang"
            v-model="form.language"
            :options="languages"
            optionLabel="label"
            optionValue="value"
            class="w-full"
          />
        </div>
      </div>

      <div class="form-field">
        <label for="edit-tags">Tags</label>
        <MultiSelect
          id="edit-tags"
          v-model="form.tags"
          :options="tagOptions"
          optionLabel="label"
          optionValue="value"
          placeholder="Select tags"
          class="w-full"
          display="chip"
        />
      </div>

      <!-- Advanced metadata (collapsible) -->
      <button type="button" class="advanced-toggle" @click="showAdvanced = !showAdvanced">
        <i :class="showAdvanced ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" />
        Additional metadata
      </button>

      <div v-if="showAdvanced" class="advanced-fields">
        <div class="form-row">
          <div class="form-field">
            <label>Subject</label>
            <InputText v-model="form.subject" class="w-full" />
          </div>
          <div class="form-field">
            <label>Identifier</label>
            <InputText v-model="form.identifier" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label>Publisher</label>
            <InputText v-model="form.publisher" class="w-full" />
          </div>
          <div class="form-field">
            <label>Format</label>
            <InputText v-model="form.format" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label>Source</label>
            <InputText v-model="form.source" class="w-full" />
          </div>
          <div class="form-field">
            <label>Type</label>
            <InputText v-model="form.type" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label>Coverage</label>
            <InputText v-model="form.coverage" class="w-full" />
          </div>
          <div class="form-field">
            <label>Rights</label>
            <InputText v-model="form.rights" class="w-full" />
          </div>
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.doc-edit {
  padding: 1.5rem;
  max-width: 720px;
}

.doc-edit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.25rem;
}
.doc-edit-header h1 {
  margin: 0;
  font-size: 1.375rem;
  font-weight: 600;
}
.doc-edit-actions {
  display: flex;
  gap: 0.5rem;
}

.doc-edit-form {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.form-field {
  margin-bottom: 1.125rem;
}
.form-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #374151;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

.advanced-toggle {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--teedy-brand);
  padding: 0.5rem 0;
  margin-bottom: 0.75rem;
}
.advanced-toggle:hover {
  text-decoration: underline;
}

.advanced-fields {
  border-top: 1px solid #e5e7eb;
  padding-top: 1rem;
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
