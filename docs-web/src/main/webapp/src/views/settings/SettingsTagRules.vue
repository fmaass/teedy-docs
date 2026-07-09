<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import api from '../../api/client'
import { listTags } from '../../api/tag'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

interface Rule {
  id: string
  tag_id: string
  rule_type: string
  pattern: string
  order: number
  enabled: boolean
}

interface ApiError {
  response?: {
    data?: {
      message?: string
    }
  }
}

const showDialog = ref(false)
const editId = ref<string | null>(null)

const form = ref({
  tag_id: '',
  rule_type: 'TITLE_REGEX',
  pattern: '',
  order: 0,
  enabled: true,
})

const ruleTypes = computed(() => [
  { label: t('ui.tag_rules.type_title_regex'), value: 'TITLE_REGEX' },
  { label: t('ui.tag_rules.type_filename_regex'), value: 'FILENAME_REGEX' },
  { label: t('ui.tag_rules.type_content_regex'), value: 'CONTENT_REGEX' },
])

const { data: rules, isLoading: loading, isError, refetch } = useQuery({
  queryKey: ['tagmatchrules'],
  queryFn: () => api.get('/tagmatchrule').then((r) => r.data.rules as Rule[]),
})

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

const { mutate: saveRule } = useMutation({
  mutationFn: (vars: { editId: string | null; params: URLSearchParams }) => {
    if (vars.editId) {
      return api.post(`/tagmatchrule/${vars.editId}`, vars.params)
    }
    return api.put('/tagmatchrule', vars.params)
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tagmatchrules'] })
    showDialog.value = false
    toast.add({ severity: 'success', summary: t('ui.tag_rules.rule_saved'), life: 2000 })
  },
  onError: (error: unknown) => {
    const message = (error as ApiError).response?.data?.message || t('ui.tag_rules.failed_save')
    toast.add({ severity: 'error', summary: message, life: 3000 })
  },
})

const { mutate: deleteRule } = useMutation({
  mutationFn: (id: string) => api.delete(`/tagmatchrule/${id}`),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tagmatchrules'] })
    toast.add({ severity: 'success', summary: t('ui.tag_rules.rule_deleted'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.tag_rules.failed_delete'), life: 3000 })
  },
})

function openCreate() {
  editId.value = null
  form.value = { tag_id: '', rule_type: 'TITLE_REGEX', pattern: '', order: 0, enabled: true }
  showDialog.value = true
}

function openEdit(rule: Rule) {
  editId.value = rule.id
  form.value = { ...rule }
  showDialog.value = true
}

function handleSave() {
  const params = new URLSearchParams()
  params.set('tag_id', form.value.tag_id)
  params.set('rule_type', form.value.rule_type)
  params.set('pattern', form.value.pattern)
  params.set('order', String(form.value.order))
  params.set('enabled', String(form.value.enabled))
  saveRule({ editId: editId.value, params })
}

function handleDelete(rule: Rule) {
  confirmDanger({
    message: t('ui.tag_rules.delete_confirm'),
    header: t('ui.tag_rules.delete_title'),
    accept: () => deleteRule(rule.id),
  })
}

function getTagName(tagId: string) {
  return tags.value?.find((t) => t.id === tagId)?.name ?? tagId
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-4">
      <h2 style="margin: 0">{{ t('ui.tag_rules.title') }}</h2>
      <Button :label="t('ui.tag_rules.add_rule')" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>

    <p class="text-sm text-muted mb-4">
      {{ t('ui.tag_rules.description') }}
    </p>

    <DataTable :value="rules ?? []" :loading="loading" size="small" stripedRows>
      <Column :header="t('ui.tag_rules.tag')" style="width: 150px">
        <template #body="{ data }">{{ getTagName(data.tag_id) }}</template>
      </Column>
      <Column field="rule_type" :header="t('ui.tag_rules.type')" style="width: 140px" />
      <Column field="pattern" :header="t('ui.tag_rules.pattern')" />
      <Column field="order" :header="t('ui.tag_rules.order')" style="width: 70px" />
      <Column :header="t('enabled')" style="width: 80px">
        <template #body="{ data }">
          <i :class="data.enabled ? 'pi pi-check-circle' : 'pi pi-times-circle'" :style="{ color: data.enabled ? 'var(--teedy-enabled-color)' : 'var(--teedy-danger)' }" />
        </template>
      </Column>
      <Column header="" style="width: 100px">
        <template #body="{ data }">
          <Button icon="pi pi-pencil" text rounded size="small" @click="openEdit(data)" :aria-label="t('ui.tag_rules.edit_rule')" />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="handleDelete(data)" :aria-label="t('ui.tag_rules.delete_title')" />
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-bolt" :message="t('ui.tag_rules.no_rules')" />
      </template>
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="editId ? t('ui.tag_rules.edit_rule') : t('ui.tag_rules.new_rule')" modal style="width: 480px">
      <div class="form-field">
        <label for="rule-tag">{{ t('ui.tag_rules.tag') }}</label>
        <Select
          v-model="form.tag_id"
          inputId="rule-tag"
          :options="tags ?? []"
          optionLabel="name"
          optionValue="id"
          :placeholder="t('ui.tag_rules.select_tag')"
          class="w-full"
        />
      </div>
      <div class="form-field">
        <label for="rule-type">{{ t('ui.tag_rules.type') }}</label>
        <Select v-model="form.rule_type" inputId="rule-type" :options="ruleTypes" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <div class="form-field">
        <label for="rule-pattern">{{ t('ui.tag_rules.pattern') }}</label>
        <InputText id="rule-pattern" v-model="form.pattern" class="w-full" :placeholder="t('ui.tag_rules.regex_placeholder')" />
      </div>
      <div class="form-field">
        <label for="rule-order">{{ t('ui.tag_rules.execution_order') }}</label>
        <InputNumber v-model="form.order" inputId="rule-order" :min="0" class="w-full" />
      </div>
      <div class="form-field">
        <label class="flex items-center gap-2">
          <ToggleSwitch v-model="form.enabled" />
          {{ t('enabled') }}
        </label>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showDialog = false" />
        <Button :label="editId ? t('save') : t('create')" icon="pi pi-check" @click="handleSave" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.form-field {
  margin-bottom: 1rem;
}
.form-field > label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-color);
}
</style>
