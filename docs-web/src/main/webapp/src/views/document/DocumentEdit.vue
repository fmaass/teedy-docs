<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { getDocument, createDocument, updateDocument } from '../../api/document'
import { uploadFile, deleteFile, getFileUrl } from '../../api/file'
import { useTagFilterStore } from '../../stores/tagFilter'
import { SUPPORTED_LANGUAGES } from '../../constants/languages'
import api from '../../api/client'
import { formatFileSize } from '../../composables/useFormatters'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import MultiSelect from 'primevue/multiselect'
import FileUpload, { type FileUploadSelectEvent, type FileUploadRemoveEvent } from 'primevue/fileupload'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const toast = useToast()
const confirm = useConfirm()
const tagFilter = useTagFilterStore()
const queryClient = useQueryClient()
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

interface AttachedFile {
  id: string
  name: string
  mimetype: string
  size: number
}

const loading = ref(false)
const showAdvanced = ref(false)
const existingFiles = ref<AttachedFile[]>([])
const pendingFiles = ref<File[]>([])
const loadedRelations = ref<Array<{ id: string }>>([])
const loadedMetadata = ref<Array<{ id: string; value?: unknown }>>([])

const fileUploadRef = ref()

const languages = SUPPORTED_LANGUAGES

const tagOptions = computed(() =>
  tagFilter.allTags.map((t) => ({ label: t.name, value: t.id })),
)

onMounted(async () => {
  if (isEdit.value && props.id) {
    try {
      const { data } = await getDocument(props.id, true)
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
      form.value.create_date = data.create_date ? new Date(data.create_date) : new Date()
      form.value.tags = data.tags?.map((t) => t.id) || []
      loadedRelations.value = data.relations ?? []
      loadedMetadata.value = data.metadata ?? []
      existingFiles.value = data.files || []
    } catch {
      toast.add({ severity: 'error', summary: 'Failed to load document', life: 3000 })
      router.back()
    }
  } else {
    try {
      const { data: appConfig } = await api.get('/app')
      if (appConfig.default_language) {
        form.value.language = appConfig.default_language
      }
    } catch { /* fall back to 'eng' */ }

    form.value.tags = [...tagFilter.selectedTagIds]
  }
})

function onFileSelect(event: FileUploadSelectEvent) {
  pendingFiles.value = [...event.files]
}

function onFileRemove(event: FileUploadRemoveEvent) {
  pendingFiles.value = pendingFiles.value.filter((f) => f !== event.file)
}

function onFileClear() {
  pendingFiles.value = []
}

function confirmDeleteExisting(file: AttachedFile) {
  confirm.require({
    message: `Remove "${file.name}" from this document?`,
    header: 'Remove file',
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: async () => {
      try {
        await deleteFile(file.id)
        existingFiles.value = existingFiles.value.filter((f) => f.id !== file.id)
        toast.add({ severity: 'success', summary: 'File removed', life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: 'Failed to remove file', life: 3000 })
      }
    },
  })
}

function buildDocParams() {
  const params = new URLSearchParams()
  const date = form.value.create_date ?? new Date()
  const fields: Record<string, string> = {
    title: form.value.title,
    description: form.value.description,
    language: form.value.language,
    create_date: String(date.getTime()),
  }
  if (form.value.subject) fields.subject = form.value.subject
  if (form.value.identifier) fields.identifier = form.value.identifier
  if (form.value.publisher) fields.publisher = form.value.publisher
  if (form.value.format) fields.format = form.value.format
  if (form.value.source) fields.source = form.value.source
  if (form.value.type) fields.type = form.value.type
  if (form.value.coverage) fields.coverage = form.value.coverage
  if (form.value.rights) fields.rights = form.value.rights
  Object.entries(fields).forEach(([k, v]) => params.append(k, v))
  form.value.tags.forEach((tagId) => params.append('tags', tagId))
  for (const r of loadedRelations.value) params.append('relations', r.id)
  for (const m of loadedMetadata.value) {
    params.append('metadata_id', m.id)
    params.append('metadata_value', m.value != null ? String(m.value) : '')
  }
  return params
}

async function handleSubmit() {
  if (!form.value.title.trim()) {
    toast.add({ severity: 'warn', summary: 'Title is required', life: 2000 })
    return
  }

  loading.value = true
  try {
    const params = buildDocParams()
    let resultId: string

    if (isEdit.value && props.id) {
      await updateDocument(props.id, params)
      resultId = props.id
    } else {
      const { data: result } = await createDocument(params)
      resultId = result.id
    }

    for (const file of pendingFiles.value) {
      await uploadFile(resultId, file)
    }
    pendingFiles.value = []
    fileUploadRef.value?.clear()

    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    await queryClient.invalidateQueries({ queryKey: ['document', resultId] })
    toast.add({ severity: 'success', summary: isEdit.value ? 'Document updated' : 'Document created', life: 2000 })
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

    <Card><template #content><form @submit.prevent="handleSubmit" class="doc-edit-form">
      <!-- Primary fields -->
      <div class="form-field">
        <label for="edit-title">Title *</label>
        <InputText id="edit-title" v-model="form.title" class="w-full" autofocus />
      </div>

      <div class="form-field">
        <label for="edit-desc">Description <span class="label-hint">(HTML supported)</span></label>
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
      <Button
        type="button"
        :icon="showAdvanced ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
        label="Additional metadata"
        text
        size="small"
        class="advanced-toggle"
        @click="showAdvanced = !showAdvanced"
      />

      <div v-if="showAdvanced" class="advanced-fields">
        <div class="form-row">
          <div class="form-field">
            <label for="edit-subject">Subject</label>
            <InputText id="edit-subject" v-model="form.subject" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-identifier">Identifier</label>
            <InputText id="edit-identifier" v-model="form.identifier" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-publisher">Publisher</label>
            <InputText id="edit-publisher" v-model="form.publisher" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-format">Format</label>
            <InputText id="edit-format" v-model="form.format" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-source">Source</label>
            <InputText id="edit-source" v-model="form.source" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-type">Type</label>
            <InputText id="edit-type" v-model="form.type" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-coverage">Coverage</label>
            <InputText id="edit-coverage" v-model="form.coverage" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-rights">Rights</label>
            <InputText id="edit-rights" v-model="form.rights" class="w-full" />
          </div>
        </div>
      </div>
    </form></template></Card>

    <!-- Files section -->
    <Card class="mt-3"><template #content><div class="doc-edit-files">
      <h3 class="files-heading">Files</h3>

      <!-- Existing files (edit mode) -->
      <div v-if="existingFiles.length" class="existing-files">
        <div v-for="file in existingFiles" :key="file.id" class="file-row">
          <i :class="file.mimetype.startsWith('image/') ? 'pi pi-image' : file.mimetype === 'application/pdf' ? 'pi pi-file-pdf' : 'pi pi-file'" class="file-icon" />
          <a :href="getFileUrl(file.id)" target="_blank" class="file-name">{{ file.name }}</a>
          <span class="file-size">{{ formatFileSize(file.size) }}</span>
          <Button
            icon="pi pi-times"
            text
            rounded
            severity="danger"
            size="small"
            @click="confirmDeleteExisting(file)"
            aria-label="Remove file"
          />
        </div>
      </div>

      <!-- PrimeVue FileUpload (deferred — files upload on save) -->
      <FileUpload
        ref="fileUploadRef"
        mode="advanced"
        multiple
        customUpload
        :showUploadButton="false"
        :showCancelButton="false"
        @select="onFileSelect"
        @remove="onFileRemove"
        @clear="onFileClear"
      >
        <template #empty>
          <div class="file-upload-empty">
            <i class="pi pi-cloud-upload" />
            <span>Drag files here or click Choose to add</span>
          </div>
        </template>
      </FileUpload>

      <p v-if="pendingFiles.length" class="upload-hint">
        {{ pendingFiles.length }} file{{ pendingFiles.length > 1 ? 's' : '' }} will be uploaded on save.
      </p>
    </div></template></Card>
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
  color: var(--p-text-color);
}
.label-hint {
  font-weight: 400;
  color: var(--p-text-muted-color);
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

.advanced-toggle {
  color: var(--teedy-brand);
  margin-bottom: 0.75rem;
}

.advanced-fields {
  border-top: 1px solid var(--p-content-border-color);
  padding-top: 1rem;
}

/* Files section */
.doc-edit-files {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.files-heading {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0;
  border-bottom: 1px solid var(--p-content-border-color);
}
.file-row:last-of-type {
  border-bottom: none;
}
.file-row.pending {
  opacity: 0.7;
}

.file-icon {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
  flex-shrink: 0;
}

.file-name {
  flex: 1;
  font-size: 0.875rem;
  color: var(--p-text-color);
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
a.file-name:hover {
  text-decoration: underline;
  color: var(--teedy-brand);
}

.file-size {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  flex-shrink: 0;
}

.file-upload-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.375rem;
  padding: 0.75rem 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.file-upload-empty i {
  font-size: 1.5rem;
}

.upload-hint {
  margin: 0.25rem 0 0;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
