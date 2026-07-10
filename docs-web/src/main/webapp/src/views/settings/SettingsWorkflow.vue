<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import {
  listRouteModels,
  getRouteModel,
  createRouteModel,
  updateRouteModel,
  deleteRouteModel,
  routeModelKeys,
  type RouteModelListItem,
} from '../../api/routeModel'
import {
  serializeSteps,
  parseSteps,
  newStep,
  type StepModel,
} from '../../utils/routeModelSteps'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import WorkflowStepEditor from '../../components/WorkflowStepEditor.vue'
import AclEditor from '../../components/AclEditor.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const { data: models, isLoading, isError, refetch } = useQuery({
  queryKey: routeModelKeys.all(),
  queryFn: () => listRouteModels().then((r) => r.data.routemodels),
})

const rows = computed(() => models.value ?? [])

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString()
}

// --- Editor dialog (create + edit share the same form) ---
const showEditor = ref(false)
const editingId = ref<string | null>(null)
const formName = ref('')
const formSteps = ref<StepModel[]>([])
const formAcls = ref<{ perm: 'READ' | 'WRITE'; id: string; name: string | null; type: 'USER' | 'GROUP' }[]>([])
const formWritable = ref(true)
const stepError = ref('')

const dialogHeader = computed(() =>
  editingId.value ? t('ui.workflow_admin.edit_title') : t('ui.workflow_admin.create_title'),
)

function openCreate() {
  editingId.value = null
  formName.value = ''
  formSteps.value = [newStep()]
  formAcls.value = []
  formWritable.value = true
  stepError.value = ''
  showEditor.value = true
}

async function openEdit(model: RouteModelListItem) {
  editingId.value = model.id
  formName.value = model.name
  formSteps.value = []
  formAcls.value = []
  stepError.value = ''
  showEditor.value = true
  const { data } = await getRouteModel(model.id)
  formName.value = data.name
  formSteps.value = parseSteps(data.steps)
  formAcls.value = data.acls
  formWritable.value = data.writable
}

// Re-fetch the ACL list of the model currently open in the editor (after an ACL change).
async function refetchAcls() {
  if (!editingId.value) return
  const { data } = await getRouteModel(editingId.value)
  formAcls.value = data.acls
  formWritable.value = data.writable
}

function validateForm(): string {
  if (!formName.value.trim()) return t('ui.workflow_admin.error_name_required')
  if (formSteps.value.length === 0) return t('ui.workflow_admin.error_no_steps')
  for (const step of formSteps.value) {
    if (!step.name.trim()) return t('ui.workflow_admin.error_step_name')
    if (!step.target.name.trim()) return t('ui.workflow_admin.error_step_target')
  }
  return ''
}

const saveMutation = useMutation({
  mutationFn: async () => {
    const steps = serializeSteps(formSteps.value)
    if (editingId.value) {
      await updateRouteModel(editingId.value, formName.value.trim(), steps)
    } else {
      await createRouteModel(formName.value.trim(), steps)
    }
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: routeModelKeys.all() })
    toast.add({ severity: 'success', summary: t('ui.workflow_admin.saved'), life: 2000 })
    showEditor.value = false
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.workflow_admin.failed_save'), life: 3000 })
  },
})

function doSave() {
  const err = validateForm()
  stepError.value = err
  if (err) return
  saveMutation.mutate()
}

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteRouteModel(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: routeModelKeys.all() })
    toast.add({ severity: 'success', summary: t('ui.workflow_admin.deleted'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.workflow_admin.failed_delete'), life: 3000 })
  },
})

function confirmDelete(model: RouteModelListItem) {
  confirmDanger({
    message: t('ui.workflow_admin.delete_confirm', { name: model.name }),
    header: t('ui.workflow_admin.delete_title'),
    accept: () => deleteMutation.mutate(model.id),
  })
}

// When editing switches to create, drop the stale editingId for the ACL panel gate.
watch(showEditor, (open) => {
  if (!open) editingId.value = null
})
</script>

<template>
  <div class="workflow-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.workflow_admin.title') }}</h2>
        <p class="section-desc">{{ t('ui.workflow_admin.description') }}</p>
      </div>
      <Button :label="t('ui.workflow_admin.create')" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>

    <DataTable :value="rows" stripedRows :loading="isLoading" class="workflow-table">
      <Column :header="t('ui.workflow_admin.name')">
        <template #body="{ data }">
          <span class="model-name">{{ data.name }}</span>
          <Tag
            v-if="data.incomplete"
            :value="t('ui.workflow_admin.incomplete')"
            severity="warn"
            v-tooltip.top="t('ui.workflow_admin.incomplete_tooltip')"
            class="incomplete-badge"
          />
        </template>
      </Column>
      <Column :header="t('ui.workflow_admin.created')" style="width: 160px">
        <template #body="{ data }">
          <span class="model-date">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 96px">
        <template #body="{ data }">
          <div class="row-actions">
            <Button icon="pi pi-pencil" text rounded size="small" severity="secondary" :aria-label="t('ui.workflow_admin.edit_title')" @click="openEdit(data)" />
            <Button icon="pi pi-trash" text rounded size="small" severity="danger" :aria-label="t('ui.workflow_admin.delete_title')" @click="confirmDelete(data)" />
          </div>
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-sitemap" :message="t('ui.workflow_admin.no_models')" />
      </template>
    </DataTable>

    <Dialog v-model:visible="showEditor" :header="dialogHeader" :modal="true" :style="{ width: '760px' }" :breakpoints="{ '960px': '95vw' }">
      <div class="editor-body">
        <div class="form-field">
          <label for="wf-name">{{ t('ui.workflow_admin.name') }}</label>
          <InputText id="wf-name" v-model="formName" maxlength="50" class="w-full" :placeholder="t('ui.workflow_admin.name_placeholder')" />
        </div>

        <div class="editor-section">
          <h3>{{ t('ui.workflow_admin.steps') }}</h3>
          <WorkflowStepEditor v-model="formSteps" />
        </div>

        <div v-if="editingId" class="editor-section">
          <h3>{{ t('ui.workflow_admin.sharing') }}</h3>
          <AclEditor :source-id="editingId" :acls="formAcls" :writable="formWritable" @changed="refetchAcls" />
        </div>

        <small v-if="stepError" class="field-error">{{ stepError }}</small>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showEditor = false" />
        <Button :label="t('save')" icon="pi pi-check" :loading="saveMutation.isPending.value" @click="doSave" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.workflow-settings {
  max-width: 760px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.25rem;
}
.section-header h2 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}
.section-desc {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.model-name {
  font-size: 0.875rem;
}
.incomplete-badge {
  margin-left: 0.5rem;
}
.model-date {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.row-actions {
  display: flex;
  gap: 0.125rem;
}
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.editor-section h3 {
  margin: 0 0 0.75rem;
  font-size: 0.9375rem;
  font-weight: 600;
}
.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.form-field label {
  font-size: 0.8125rem;
  font-weight: 500;
}
.field-error {
  color: var(--p-red-500);
  font-size: 0.8125rem;
}
.w-full {
  width: 100%;
}
</style>
