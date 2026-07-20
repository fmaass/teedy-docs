<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { getDocument, createDocument, updateDocument, importEml } from '../../api/document'
import { uploadFile, deleteFile } from '../../api/file'
import { listMetadata, type MetadataDefinition } from '../../api/metadata'
import { getVocabulary } from '../../api/vocabulary'
import { buildMetadataParams, shouldResetMetadata, type MetadataValue } from '../../utils/metadataSerialize'
import { shouldResetTags } from '../../utils/tagsReset'
import { useTagFilterStore } from '../../stores/tagFilter'
import { SUPPORTED_LANGUAGES } from '../../constants/languages'
import { getAppInfo } from '../../api/app'
import { queryKeys } from '../../api/queryKeys'
import { formatFileSize } from '../../utils/formatters'
import { displayName } from '../../utils/fileName'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import InputNumber from 'primevue/inputnumber'
import ToggleSwitch from 'primevue/toggleswitch'
import MultiSelect from 'primevue/multiselect'
import FileUpload, { type FileUploadSelectEvent, type FileUploadRemoveEvent } from 'primevue/fileupload'
import Button from 'primevue/button'
import Card from 'primevue/card'
import CameraCaptureButton from '../../components/CameraCaptureButton.vue'
import UploadProgressList from '../../components/UploadProgressList.vue'
import TagBadge from '../../components/TagBadge.vue'
import RichDescriptionEditor from '../../components/RichDescriptionEditor.vue'
import FilePreviewDialog, { type PreviewFile } from '../../components/FilePreviewDialog.vue'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const tagFilter = useTagFilterStore()
const queryClient = useQueryClient()
const { t } = useI18n()
const isEdit = computed(() => !!props.id)

function defaultForm() {
  return {
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
  }
}

const form = ref(defaultForm())

interface AttachedFile {
  id: string
  // Serialized nullable by the backend (JsonUtil.nullable): a legacy/inbox file can have no name.
  name: string | null
  mimetype: string
  size: number
}

const loading = ref(false)
const showAdvanced = ref(false)
const existingFiles = ref<AttachedFile[]>([])
const pendingFiles = ref<File[]>([])

// Safe in-app preview (#144): clicking an existing file's name opens the shared preview
// dialog instead of navigating to the original file URL, which the backend serves as an
// attachment (opening it would just trigger a download).
const previewVisible = ref(false)
const previewFile = ref<PreviewFile | null>(null)
function openPreview(file: AttachedFile) {
  previewFile.value = { id: file.id, name: file.name, mimetype: file.mimetype }
  previewVisible.value = true
}
// CREATE-mode retry guard: once createDocument() succeeds we remember the new id, so
// a subsequent Save (after a file-upload failure) updates that document instead of
// creating a second one. uploadedCount tracks how many of the CURRENT pendingFiles
// queue already uploaded, so a retry resumes at the first file that failed rather
// than re-uploading the ones that already landed. Both reset once a save fully
// succeeds (navigation away) or the file queue is replaced.
const createdId = ref<string | null>(null)
const uploadedCount = ref(0)
// Per-file upload progress (0..100), keyed by array index into pendingFiles,
// populated only while an upload is in flight so the ProgressBar shows real bytes.
const uploadProgress = ref<Record<number, number>>({})
const uploadingFiles = ref(false)
const loadedRelations = ref<Array<{ id: string }>>([])

// Custom metadata: field definitions (admin-configured) and per-field values keyed
// by definition id. Values are typed for their input; serialized on save.
const metadataDefinitions = ref<MetadataDefinition[]>([])
const metadataValues = ref<Record<string, MetadataValue>>({})
// Ids of BOOLEAN fields that carry an explicit value — either already set on the
// document or toggled by the user. Unset booleans stay out of this set so they are
// omitted from the save rather than coerced to an explicit "false".
const metadataSetIds = ref<Set<string>>(new Set())
// Snapshot at load: did this document have ANY set custom-metadata value? Captured
// once at hydration (edit mode) and NOT mutated by later user edits, so a save that
// emits zero metadata params can tell a genuine clear-the-last-value ("send
// metadata_reset=true") apart from a document that simply never had metadata.
const metadataHadValuesAtLoad = ref(false)

// Vocabulary option cache keyed by vocabulary name. Each entry is the list of
// selectable values for that vocabulary, loaded on demand and shared by the Dublin
// Core trio selects (type/coverage/rights) and any VOCABULARY custom-metadata field.
const vocabularyOptions = ref<Record<string, string[]>>({})

function loadVocabulary(name: string) {
  if (!name || vocabularyOptions.value[name] !== undefined) return
  // Mark as loading (empty list) so we do not fire the request twice.
  vocabularyOptions.value[name] = []
  getVocabulary(name)
    .then(({ data }) => {
      vocabularyOptions.value[name] = (data.entries ?? []).map((e) => e.value)
    })
    .catch(() => {
      // Leave an empty option list on failure; the Select simply shows no options.
    })
}

function vocabularyOptionsFor(name?: string): string[] {
  if (!name) return []
  return vocabularyOptions.value[name] ?? []
}

// Load the vocabularies referenced by the current metadata definitions (VOCABULARY
// fields) plus the Dublin Core trio (type/coverage/rights, which are always selects).
function loadMetadataVocabularies() {
  loadVocabulary('type')
  loadVocabulary('coverage')
  loadVocabulary('rights')
  for (const def of metadataDefinitions.value) {
    if (def.type === 'VOCABULARY' && def.vocabulary) {
      loadVocabulary(def.vocabulary)
    }
  }
}

const fileUploadRef = ref()

const languages = SUPPORTED_LANGUAGES

const tagOptions = computed(() =>
  tagFilter.allTags.map((t) => ({ label: t.name, value: t.id, color: t.color })),
)

// The MultiSelect chip slot's removeCallback closes over the selected value itself and its
// runtime only reads event.stopPropagation() — the second `item` param in PrimeVue's typed
// signature is unused at runtime. We pass a synthetic Event so the TagBadge remove button
// (whose own remove emit carries no event) can deselect the chip.
function removeTagChip(removeCallback: (event: Event, item?: unknown) => void) {
  removeCallback(new Event('remove'))
}

// Resolve a selected tag id to a display chip. A tag known in the tag map renders coloured
// with its real name; an UNKNOWN id (a selection not in the loaded tag list, or a gap
// before the map populates) still renders a VISIBLE, REMOVABLE fallback chip — a neutral
// grey chip labelled with the raw id — so a selected tag is never invisible/unremovable.
const UNKNOWN_TAG_COLOR = '#9e9e9e'
function tagChip(tagId: string): { name: string; color: string } {
  const tag = tagFilter.tagMap.get(tagId)
  return tag ? { name: tag.name, color: tag.color } : { name: tagId, color: UNKNOWN_TAG_COLOR }
}

// Generation counter guarding initFromRoute against out-of-order async completion:
// on rapid add -> edit / edit -> edit navigation, a stale getDocument(oldId) (or
// /app, listMetadata) response can resolve AFTER the reset for the new target and
// repopulate the form for the wrong document. Each init run captures its generation
// at entry and re-checks after every await; a mismatch means a newer reset/init has
// superseded it, so it stops without mutating state.
let initGen = 0

// Load (edit) or seed (create) the form for the current route target. Extracted from
// onMounted because vue-router REUSES this component instance across document-add and
// document-edit routes (same component, no keep-alive) — onMounted alone would leave
// the previous target's state in place.
async function initFromRoute() {
  const gen = ++initGen
  if (isEdit.value && props.id) {
    try {
      const { data } = await getDocument(props.id, true)
      if (gen !== initGen) return
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
      // OUTGOING relations only: POST /document/:id reconciles `relations` as this
      // document's outgoing set, so re-sending an incoming (source=false) relation
      // would mint a spurious reverse relation on every save.
      loadedRelations.value = (data.relations ?? []).filter((r) => r.source)
      // The document detail returns every defined field merged with this document's
      // value (value omitted when unset), so it doubles as the definition list.
      metadataDefinitions.value = (data.metadata ?? []).map((m) => ({
        id: m.id,
        name: m.name,
        type: m.type as MetadataDefinition['type'],
        vocabulary: m.vocabulary,
      }))
      loadMetadataVocabularies()
      hydrateMetadataValues(data.metadata ?? [])
      existingFiles.value = data.files || []
    } catch {
      // A stale run's failure must not toast or navigate away from the NEW target.
      if (gen !== initGen) return
      toast.add({ severity: 'error', summary: t('ui.failed_save_document'), life: 3000 })
      router.back()
    }
  } else {
    try {
      const appConfig = await queryClient.fetchQuery({ queryKey: queryKeys.app(), queryFn: () => getAppInfo() })
      if (gen !== initGen) return
      if (appConfig.default_language) {
        form.value.language = appConfig.default_language
      }
    } catch { /* fall back to 'eng' */ }
    if (gen !== initGen) return

    // New document: load field definitions so the user can fill them in.
    try {
      const { data } = await listMetadata()
      if (gen !== initGen) return
      metadataDefinitions.value = data.metadata
      loadMetadataVocabularies()
      hydrateMetadataValues(data.metadata)
    } catch { /* no custom fields available */ }
    if (gen !== initGen) return

    form.value.tags = [...tagFilter.selectedTagIds]
  }
}

// Drop everything tied to the PREVIOUS route target. Critically this clears the
// create-retry state (createdId / uploadedCount): a half-created document from an
// earlier document-add visit must never be silently updated when this reused
// instance serves a fresh create (or inherited into an edit). The pending file
// queue and loaded document state belong to the old target too.
function resetForRouteChange() {
  // Invalidate any in-flight initFromRoute immediately — even before the next init
  // run bumps the generation again.
  initGen++
  form.value = defaultForm()
  createdId.value = null
  uploadedCount.value = 0
  pendingFiles.value = []
  uploadProgress.value = {}
  uploadingFiles.value = false
  fileUploadRef.value?.clear()
  existingFiles.value = []
  loadedRelations.value = []
  metadataDefinitions.value = []
  metadataValues.value = {}
  metadataSetIds.value = new Set()
  metadataHadValuesAtLoad.value = false
}

onMounted(initFromRoute)

// Same component instance, different target (add -> edit, edit -> add, or another
// document id): reset, then re-initialize for the new target.
watch(() => props.id, () => {
  resetForRouteChange()
  initFromRoute()
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
  metadataHadValuesAtLoad.value = setIds.size > 0
}

// A user toggling a boolean makes it explicitly set (so false is submitted, not omitted).
function onBooleanToggle(id: string) {
  metadataSetIds.value.add(id)
}

function onFileSelect(event: FileUploadSelectEvent) {
  pendingFiles.value = [...event.files]
  // The queue changed — any prior partial-upload progress no longer maps to it.
  uploadedCount.value = 0
}

function onFileRemove(event: FileUploadRemoveEvent) {
  pendingFiles.value = pendingFiles.value.filter((f) => f !== event.file)
  uploadedCount.value = 0
}

function onFileClear() {
  pendingFiles.value = []
  uploadedCount.value = 0
}

// Camera capture: photos from CameraCaptureButton are appended to the same
// pendingFiles queue, so they upload on save exactly like picked files.
function onCameraCapture(captured: File[]) {
  if (captured.length) {
    pendingFiles.value = [...pendingFiles.value, ...captured]
    uploadedCount.value = 0
  }
}

function confirmDeleteExisting(file: AttachedFile) {
  confirmDanger({
    message: t('ui.remove_file_confirm', { name: displayName(file.name, t) }),
    header: t('ui.remove_file'),
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
  // The backend preserves tags on an omitted `tags` param, so clearing the last
  // tag on an edit is otherwise a silent no-op. Send the clear-all sentinel on a
  // genuine clear (mirrors the metadata_reset path below and bulkOps — BL-025).
  if (shouldResetTags(isEdit.value, form.value.tags.length)) {
    params.append('tags_reset', 'true')
  }
  for (const r of loadedRelations.value) params.append('relations', r.id)
  // The backend preserves relations on an omitted `relations` param, so an edit-save
  // with zero surviving outgoing relations must send the clear-all sentinel (mirrors
  // tags_reset above; a create has nothing to reset). Harmless no-op when the document
  // never had outgoing relations.
  if (isEdit.value && loadedRelations.value.length === 0) {
    params.append('relations_reset', 'true')
  }
  // Only submit fields the user actually set — an unset numeric/date field sent as a
  // blank pair makes the backend reject the whole save; an unset boolean must not
  // silently become "false".
  const metaParams = buildMetadataParams(metadataDefinitions.value, metadataValues.value, metadataSetIds.value)
  for (const meta of metaParams) {
    params.append('metadata_id', meta.id)
    params.append('metadata_value', meta.value)
  }
  // The backend preserves metadata on an omitted set, so clearing the last set value
  // is otherwise a silent no-op. Send the clear-all sentinel ONLY on a genuine clear
  // (the document HAD values at load and now emits zero params).
  if (shouldResetMetadata(metadataHadValuesAtLoad.value, metaParams)) {
    params.append('metadata_reset', 'true')
  }
  return params
}

async function handleSubmit() {
  if (!form.value.title.trim()) {
    toast.add({ severity: 'warn', summary: t('ui.title_required'), life: 2000 })
    return
  }

  loading.value = true
  // True once the document itself is persisted this attempt (created or updated), so
  // a later file-upload failure reports "saved, files failed" rather than "not saved"
  // — and never re-creates the document on retry.
  let documentPersisted = false
  try {
    const params = buildDocParams()
    let resultId: string

    if (isEdit.value && props.id) {
      await updateDocument(props.id, params)
      resultId = props.id
    } else if (createdId.value) {
      // A prior attempt already created this document (then a file upload failed):
      // update it instead of creating a duplicate.
      await updateDocument(createdId.value, params)
      resultId = createdId.value
    } else {
      const { data: result } = await createDocument(params)
      resultId = result.id
      createdId.value = resultId
    }
    documentPersisted = true

    if (pendingFiles.value.length) {
      uploadingFiles.value = true
      uploadProgress.value = {}
      const files = pendingFiles.value
      // Resume at the first file that has not yet uploaded — files that succeeded on
      // a previous attempt are not sent again.
      for (let i = uploadedCount.value; i < files.length; i++) {
        uploadProgress.value[i] = 0
        await uploadFile(resultId, files[i], (pct) => {
          uploadProgress.value[i] = pct
        })
        uploadProgress.value[i] = 100
        uploadedCount.value = i + 1
      }
      uploadingFiles.value = false
    }
    pendingFiles.value = []
    uploadProgress.value = {}
    uploadedCount.value = 0
    createdId.value = null
    fileUploadRef.value?.clear()

    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    await queryClient.invalidateQueries({ queryKey: ['document', resultId] })
    toast.add({ severity: 'success', summary: isEdit.value ? t('ui.document_updated') : t('ui.document_created'), life: 2000 })
    router.push({ name: 'document-view', params: { id: resultId } })
  } catch {
    // Distinguish the two failure modes: the document was saved but a file upload
    // failed (retry uploads only the remaining files — no duplicate document), versus
    // the save itself failed (nothing persisted). Stay on the form either way.
    toast.add({
      severity: 'error',
      summary: documentPersisted ? t('ui.document_saved_files_failed') : t('ui.failed_save_document'),
      life: 4000,
    })
  } finally {
    loading.value = false
    uploadingFiles.value = false
  }
}

// --- .eml import (create mode only) ---
// The EML endpoint creates a whole document from the email itself (title, body, and
// attachments), so it is a one-shot import rather than a file added to THIS form's
// document. The user picks an .eml file; we PUT it to /document/eml and navigate to
// the created document. Kept separate from the normal file queue, whose files attach
// to the document built from the form fields above.
const emlInputRef = ref<HTMLInputElement | null>(null)
const importingEml = ref(false)

function triggerEmlPicker() {
  emlInputRef.value?.click()
}

async function onEmlSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // Reset the input so re-picking the same file fires change again.
  input.value = ''
  if (!file) return

  importingEml.value = true
  try {
    const { data } = await importEml(file)
    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    toast.add({ severity: 'success', summary: t('ui.eml_imported'), life: 2000 })
    router.push({ name: 'document-view', params: { id: data.id } })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.eml_import_failed'), life: 4000 })
  } finally {
    importingEml.value = false
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
        <RichDescriptionEditor id="edit-desc" v-model="form.description" :aria-label="t('ui.description')" />
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
          class="w-full tag-multiselect"
          display="chip"
          filter
        >
          <!-- Colour the selected chips from the tag map (the slot's `value` is the tag
               id, since optionValue is the id). An id missing from the map still gets a
               visible, removable fallback chip. Chips wrap instead of clipping. -->
          <template #chip="{ value, removeCallback }">
            <TagBadge
              :name="tagChip(value).name"
              :color="tagChip(value).color"
              removable
              @remove="removeTagChip(removeCallback)"
            />
          </template>
        </MultiSelect>
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
            <Select
              id="edit-type"
              v-model="form.type"
              :options="vocabularyOptionsFor('type')"
              showClear
              filter
              :placeholder="t('ui.vocabulary.select_placeholder')"
              class="w-full"
            />
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label for="edit-coverage">{{ t('document.coverage') }}</label>
            <Select
              id="edit-coverage"
              v-model="form.coverage"
              :options="vocabularyOptionsFor('coverage')"
              showClear
              filter
              :placeholder="t('ui.vocabulary.select_placeholder')"
              class="w-full"
            />
          </div>
          <div class="form-field">
            <label for="edit-rights">{{ t('document.rights') }}</label>
            <Select
              id="edit-rights"
              v-model="form.rights"
              :options="vocabularyOptionsFor('rights')"
              showClear
              filter
              :placeholder="t('ui.vocabulary.select_placeholder')"
              class="w-full"
            />
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
          <Select
            v-else-if="def.type === 'VOCABULARY'"
            :inputId="`meta-${def.id}`"
            v-model="(metadataValues[def.id] as string)"
            :options="vocabularyOptionsFor(def.vocabulary)"
            showClear
            filter
            :placeholder="t('ui.vocabulary.select_placeholder')"
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
          <button type="button" class="file-name" @click="openPreview(file)">{{ displayName(file.name, t) }}</button>
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
        :chooseLabel="t('ui.choose')"
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

      <!-- Camera capture: opens the device camera on mobile; photos queue for save. -->
      <CameraCaptureButton @capture="onCameraCapture" />

      <!-- .eml import (create only): builds a whole document from an email file via
           the dedicated endpoint, then navigates to it. Separate from the file queue. -->
      <div v-if="!isEdit" class="eml-import">
        <input
          ref="emlInputRef"
          type="file"
          accept=".eml,message/rfc822"
          class="eml-input"
          @change="onEmlSelected"
        />
        <Button
          type="button"
          :label="t('ui.import_eml')"
          icon="pi pi-envelope"
          severity="secondary"
          outlined
          size="small"
          :loading="importingEml"
          @click="triggerEmlPicker"
        />
        <span class="eml-hint">{{ t('ui.import_eml_hint') }}</span>
      </div>

      <!-- Real per-file upload progress while saving. -->
      <UploadProgressList
        v-if="uploadingFiles"
        :names="pendingFiles.map((f) => f.name)"
        :progress="uploadProgress"
      />

      <p v-else-if="pendingFiles.length" class="upload-hint">
        {{ t('ui.files_upload_hint', { count: pendingFiles.length }) }}
      </p>
    </div></template></Card>

    <!-- Safe in-app file preview (#144). -->
    <FilePreviewDialog v-model:visible="previewVisible" :file="previewFile" />
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

/* Tag picker: let the coloured TagBadge chips wrap onto multiple rows instead of
   clipping in a single overflow line (#23). */
.tag-multiselect :deep(.p-multiselect-label) {
  flex-wrap: wrap;
  gap: 0.25rem;
  white-space: normal;
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
  min-width: 0;
  font-size: 0.875rem;
  color: var(--p-text-color);
  text-decoration: none;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  font-family: inherit;
}
.file-name:hover {
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

.eml-import {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  flex-wrap: wrap;
}
.eml-input {
  display: none;
}
.eml-hint {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

/* Camera capture: hide the native input; the styled Button triggers it. */
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
</style>
