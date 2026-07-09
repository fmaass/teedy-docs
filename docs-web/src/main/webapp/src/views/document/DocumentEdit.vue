<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { getDocument, createDocument, updateDocument } from '../../api/document'
import { uploadFile, deleteFile, getFileUrl } from '../../api/file'
import { listMetadata, type MetadataDefinition } from '../../api/metadata'
import { buildMetadataParams, type MetadataValue } from '../../composables/metadataSerialize'
import { useTagFilterStore } from '../../stores/tagFilter'
import { SUPPORTED_LANGUAGES } from '../../constants/languages'
import api from '../../api/client'
import { formatFileSize } from '../../composables/useFormatters'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import InputNumber from 'primevue/inputnumber'
import ToggleSwitch from 'primevue/toggleswitch'
import MultiSelect from 'primevue/multiselect'
import FileUpload, { type FileUploadSelectEvent, type FileUploadRemoveEvent } from 'primevue/fileupload'
import Button from 'primevue/button'
import Card from 'primevue/card'
import ProgressBar from 'primevue/progressbar'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const toast = useToast()
const confirm = useConfirm()
const tagFilter = useTagFilterStore()
const queryClient = useQueryClient()
const { t } = useI18n()
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
// Per-file upload progress (0..100), keyed by array index into pendingFiles,
// populated only while an upload is in flight so the ProgressBar shows real bytes.
const uploadProgress = ref<Record<number, number>>({})
const uploadingFiles = ref(false)
const loadedRelations = ref<Array<{ id: string }>>([])
const cameraInputRef = ref<HTMLInputElement | null>(null)

// Custom metadata: field definitions (admin-configured) and per-field values keyed
// by definition id. Values are typed for their input; serialized on save.
const metadataDefinitions = ref<MetadataDefinition[]>([])
const metadataValues = ref<Record<string, MetadataValue>>({})
// Ids of BOOLEAN fields that carry an explicit value — either already set on the
// document or toggled by the user. Unset booleans stay out of this set so they are
// omitted from the save rather than coerced to an explicit "false".
const metadataSetIds = ref<Set<string>>(new Set())

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
      // The document detail returns every defined field merged with this document's
      // value (value omitted when unset), so it doubles as the definition list.
      metadataDefinitions.value = (data.metadata ?? []).map((m) => ({
        id: m.id,
        name: m.name,
        type: m.type as MetadataDefinition['type'],
      }))
      hydrateMetadataValues(data.metadata ?? [])
      existingFiles.value = data.files || []
    } catch {
      toast.add({ severity: 'error', summary: t('ui.failed_save_document'), life: 3000 })
      router.back()
    }
  } else {
    try {
      const { data: appConfig } = await api.get('/app')
      if (appConfig.default_language) {
        form.value.language = appConfig.default_language
      }
    } catch { /* fall back to 'eng' */ }

    // New document: load field definitions so the user can fill them in.
    try {
      const { data } = await listMetadata()
      metadataDefinitions.value = data.metadata
      hydrateMetadataValues(data.metadata)
    } catch { /* no custom fields available */ }

    form.value.tags = [...tagFilter.selectedTagIds]
  }
})

// Seed the value map from a definition/value list, coercing each backend value to
// the shape its input expects (DATE epoch ms -> Date, BOOLEAN -> bool, else raw).
// A field with a value on the document is recorded as "set"; an unset boolean is
// left null (and NOT recorded) so it stays out of the save until the user toggles it.
function hydrateMetadataValues(list: Array<{ id: string; type: string; value?: unknown }>) {
  const values: Record<string, MetadataValue> = {}
  const setIds = new Set<string>()
  for (const m of list) {
    if (m.value == null) {
      values[m.id] = null
    } else {
      setIds.add(m.id)
      if (m.type === 'DATE') {
        values[m.id] = new Date(Number(m.value))
      } else if (m.type === 'BOOLEAN') {
        values[m.id] = Boolean(m.value)
      } else {
        values[m.id] = m.value as MetadataValue
      }
    }
  }
  metadataValues.value = values
  metadataSetIds.value = setIds
}

// A user toggling a boolean makes it explicitly set (so false is submitted, not omitted).
function onBooleanToggle(id: string) {
  metadataSetIds.value.add(id)
}

function onFileSelect(event: FileUploadSelectEvent) {
  pendingFiles.value = [...event.files]
}

function onFileRemove(event: FileUploadRemoveEvent) {
  pendingFiles.value = pendingFiles.value.filter((f) => f !== event.file)
}

function onFileClear() {
  pendingFiles.value = []
}

// Camera capture: a separate hidden <input capture> so mobile browsers open the
// camera directly. Captured photos are appended to the same pendingFiles queue,
// so they upload on save exactly like picked files.
function openCamera() {
  cameraInputRef.value?.click()
}

function onCameraCapture(event: Event) {
  const input = event.target as HTMLInputElement
  const captured = input.files ? Array.from(input.files) : []
  if (captured.length) {
    pendingFiles.value = [...pendingFiles.value, ...captured]
  }
  // Reset so re-capturing the same-named photo fires change again.
  input.value = ''
}

function confirmDeleteExisting(file: AttachedFile) {
  confirm.require({
    message: t('ui.remove_file_confirm', { name: file.name }),
    header: t('ui.remove_file'),
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: async () => {
      try {
        await deleteFile(file.id)
        existingFiles.value = existingFiles.value.filter((f) => f.id !== file.id)
        toast.add({ severity: 'success', summary: t('ui.file_removed'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.failed_remove_file'), life: 3000 })
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
  // Only submit fields the user actually set — an unset numeric/date field sent as a
  // blank pair makes the backend reject the whole save; an unset boolean must not
  // silently become "false".
  for (const meta of buildMetadataParams(metadataDefinitions.value, metadataValues.value, metadataSetIds.value)) {
    params.append('metadata_id', meta.id)
    params.append('metadata_value', meta.value)
  }
  return params
}

async function handleSubmit() {
  if (!form.value.title.trim()) {
    toast.add({ severity: 'warn', summary: t('ui.title_required'), life: 2000 })
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

    if (pendingFiles.value.length) {
      uploadingFiles.value = true
      uploadProgress.value = {}
      const files = pendingFiles.value
      for (let i = 0; i < files.length; i++) {
        uploadProgress.value[i] = 0
        await uploadFile(resultId, files[i], (pct) => {
          uploadProgress.value[i] = pct
        })
        uploadProgress.value[i] = 100
      }
      uploadingFiles.value = false
    }
    pendingFiles.value = []
    uploadProgress.value = {}
    fileUploadRef.value?.clear()

    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    await queryClient.invalidateQueries({ queryKey: ['document', resultId] })
    toast.add({ severity: 'success', summary: isEdit.value ? t('ui.document_updated') : t('ui.document_created'), life: 2000 })
    router.push({ name: 'document-view', params: { id: resultId } })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.failed_save_document'), life: 3000 })
  } finally {
    loading.value = false
    uploadingFiles.value = false
  }
}
</script>

<template>
  <div class="doc-edit">
    <header class="doc-edit-header">
      <h1>{{ isEdit ? t('ui.edit_document') : t('ui.new_document') }}</h1>
      <div class="doc-edit-actions">
        <Button :label="t('cancel')" severity="secondary" text @click="router.back()" />
        <Button :label="t('save')" icon="pi pi-check" :loading="loading" @click="handleSubmit" />
      </div>
    </header>

    <Card><template #content><form @submit.prevent="handleSubmit" class="doc-edit-form">
      <!-- Primary fields -->
      <div class="form-field">
        <label for="edit-title">{{ t('ui.title') }} *</label>
        <InputText id="edit-title" v-model="form.title" class="w-full" autofocus />
      </div>

      <div class="form-field">
        <label for="edit-desc">{{ t('ui.description') }} <span class="label-hint">({{ t('ui.description_hint') }})</span></label>
        <Textarea id="edit-desc" v-model="form.description" rows="4" class="w-full" autoResize />
      </div>

      <div class="form-row">
        <div class="form-field">
          <label for="edit-date">{{ t('ui.creation_date') }}</label>
          <DatePicker id="edit-date" v-model="form.create_date" dateFormat="yy-mm-dd" class="w-full" />
        </div>
        <div class="form-field">
          <label for="edit-lang">{{ t('document.language') }}</label>
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
        <label for="edit-tags">{{ t('document.tags') }}</label>
        <MultiSelect
          id="edit-tags"
          v-model="form.tags"
          :options="tagOptions"
          optionLabel="label"
          optionValue="value"
          :placeholder="t('document.tags')"
          class="w-full"
          display="chip"
        />
      </div>

      <!-- Advanced metadata (collapsible) -->
      <Button
        type="button"
        :icon="showAdvanced ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
        :label="t('ui.additional_metadata')"
        text
        size="small"
        class="advanced-toggle"
        @click="showAdvanced = !showAdvanced"
      />

      <div v-if="showAdvanced" class="advanced-fields">
        <div class="form-row">
          <div class="form-field">
            <label for="edit-subject">{{ t('document.subject') }}</label>
            <InputText id="edit-subject" v-model="form.subject" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-identifier">{{ t('document.identifier') }}</label>
            <InputText id="edit-identifier" v-model="form.identifier" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-publisher">{{ t('document.publisher') }}</label>
            <InputText id="edit-publisher" v-model="form.publisher" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-format">{{ t('document.format') }}</label>
            <InputText id="edit-format" v-model="form.format" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-source">{{ t('document.source') }}</label>
            <InputText id="edit-source" v-model="form.source" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-type">{{ t('document.type') }}</label>
            <InputText id="edit-type" v-model="form.type" class="w-full" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-coverage">{{ t('document.coverage') }}</label>
            <InputText id="edit-coverage" v-model="form.coverage" class="w-full" />
          </div>
          <div class="form-field">
            <label for="edit-rights">{{ t('document.rights') }}</label>
            <InputText id="edit-rights" v-model="form.rights" class="w-full" />
          </div>
        </div>
      </div>

      <!-- Custom metadata fields (admin-defined) -->
      <div v-if="metadataDefinitions.length" class="custom-metadata">
        <h3 class="custom-metadata-heading">{{ t('ui.metadata.custom_fields') }}</h3>
        <div v-for="def in metadataDefinitions" :key="def.id" class="form-field">
          <label :for="`meta-${def.id}`">{{ def.name }}</label>
          <ToggleSwitch
            v-if="def.type === 'BOOLEAN'"
            :inputId="`meta-${def.id}`"
            :modelValue="metadataValues[def.id] === true"
            @update:modelValue="(v: boolean) => { metadataValues[def.id] = v; onBooleanToggle(def.id) }"
          />
          <DatePicker
            v-else-if="def.type === 'DATE'"
            :inputId="`meta-${def.id}`"
            v-model="(metadataValues[def.id] as Date)"
            dateFormat="yy-mm-dd"
            showButtonBar
            class="w-full"
          />
          <InputNumber
            v-else-if="def.type === 'INTEGER'"
            :inputId="`meta-${def.id}`"
            v-model="(metadataValues[def.id] as number)"
            :useGrouping="false"
            class="w-full"
          />
          <InputNumber
            v-else-if="def.type === 'FLOAT'"
            :inputId="`meta-${def.id}`"
            v-model="(metadataValues[def.id] as number)"
            :useGrouping="false"
            :minFractionDigits="1"
            :maxFractionDigits="6"
            class="w-full"
          />
          <InputText
            v-else
            :id="`meta-${def.id}`"
            v-model="(metadataValues[def.id] as string)"
            class="w-full"
          />
        </div>
      </div>
    </form></template></Card>

    <!-- Files section -->
    <Card class="mt-3"><template #content><div class="doc-edit-files">
      <h3 class="files-heading">{{ t('ui.files') }}</h3>

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
            :aria-label="t('ui.remove_file')"
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
            <i class="pi pi-cloud-upload" aria-hidden="true" />
            <span>{{ t('ui.drag_or_choose') }}</span>
          </div>
        </template>
      </FileUpload>

      <!-- Camera capture: hidden input opens the device camera on mobile. -->
      <div class="camera-capture">
        <Button
          type="button"
          icon="pi pi-camera"
          :label="t('ui.take_photo')"
          severity="secondary"
          outlined
          class="camera-btn"
          @click="openCamera"
        />
        <input
          ref="cameraInputRef"
          type="file"
          accept="image/*"
          capture="environment"
          class="camera-input"
          @change="onCameraCapture"
        />
      </div>

      <!-- Real per-file upload progress while saving. -->
      <div v-if="uploadingFiles" class="upload-progress-list">
        <div v-for="(file, i) in pendingFiles" :key="i" class="upload-progress-item">
          <span class="upload-progress-name">{{ file.name }}</span>
          <ProgressBar :value="uploadProgress[i] ?? 0" class="upload-progress-bar" />
        </div>
      </div>

      <p v-else-if="pendingFiles.length" class="upload-hint">
        {{ t('ui.files_upload_hint', { count: pendingFiles.length }) }}
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

.custom-metadata {
  border-top: 1px solid var(--p-content-border-color);
  padding-top: 1rem;
  margin-top: 0.25rem;
}
.custom-metadata-heading {
  margin: 0 0 0.875rem;
  font-size: 0.9375rem;
  font-weight: 600;
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

/* Camera capture: hide the native input; the styled Button triggers it. */
.camera-capture {
  margin-top: 0.75rem;
}
.camera-input {
  display: none;
}
.camera-btn {
  width: 100%;
}

.upload-progress-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.upload-progress-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.upload-progress-name {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-progress-bar {
  height: 0.75rem;
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
  .doc-edit {
    padding: 1rem;
  }
  .doc-edit-header {
    flex-direction: column;
    align-items: stretch;
    gap: 0.75rem;
  }
  .doc-edit-actions {
    justify-content: flex-end;
  }
}

@media (min-width: 641px) {
  .camera-btn {
    width: auto;
  }
}
</style>
