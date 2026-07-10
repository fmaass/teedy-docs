<script setup lang="ts">
/**
 * Ordered step editor for a route model (workflow template). Each step has a name,
 * a type (VALIDATE / APPROVE), a target (user/group typeahead via /api/acl/target/search),
 * and per-transition action panels. The transition SET is implied by the step type and
 * auto-managed (VALIDATE → VALIDATED; APPROVE → APPROVED + REJECTED) so the serialized
 * blob always satisfies the backend's strict validation.
 *
 * v-model is the StepModel[] array; reordering and add/remove are handled here.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  type StepModel,
  type StepAction,
  type TransitionName,
  transitionNamesFor,
  newStep,
} from '../utils/routeModelSteps'
import { searchAclTargets, type AclTarget } from '../api/acl'
import { listTags, type Tag as TagType } from '../api/tag'
import { useQuery } from '@tanstack/vue-query'
import AutoComplete from 'primevue/autocomplete'
import Select from 'primevue/select'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { ref } from 'vue'

const model = defineModel<StepModel[]>({ required: true })

const { t } = useI18n()

const typeOptions = computed(() => [
  { label: t('ui.workflow_admin.type_validate'), value: 'VALIDATE' },
  { label: t('ui.workflow_admin.type_approve'), value: 'APPROVE' },
])

const targetTypeOptions = computed(() => [
  { label: t('ui.workflow_admin.target_user'), value: 'USER' },
  { label: t('ui.workflow_admin.target_group'), value: 'GROUP' },
])

const actionTypeOptions = computed(() => [
  { label: t('ui.workflow_admin.action_add_tag'), value: 'ADD_TAG' },
  { label: t('ui.workflow_admin.action_remove_tag'), value: 'REMOVE_TAG' },
  { label: t('ui.workflow_admin.action_process_files'), value: 'PROCESS_FILES' },
])

// Tags for the ADD_TAG/REMOVE_TAG picker (value = tag id, matching the backend's `tag` key).
const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
})
const tagOptions = computed(() =>
  (tags.value ?? []).map((tag: TagType) => ({ label: tag.name, value: tag.id })),
)

// AutoComplete needs its own suggestions ref; keyed per step index so parallel edits don't clash.
const targetSuggestions = ref<Record<number, AclTarget[]>>({})

async function completeTargetSearch(index: number, event: { query: string }) {
  const query = event.query.trim()
  if (!query) {
    targetSuggestions.value[index] = []
    return
  }
  try {
    const { data } = await searchAclTargets(query)
    const users = (data.users ?? []).map((u) => ({ ...u, type: 'USER' as const }))
    const groups = (data.groups ?? []).map((g) => ({ ...g, type: 'GROUP' as const }))
    targetSuggestions.value[index] = [...users, ...groups]
  } catch {
    targetSuggestions.value[index] = []
  }
}

// AutoComplete v-model over the target: we bind an object so the label renders, but only
// the name+type are persisted into the step model.
function onTargetSelect(step: StepModel, target: AclTarget | string) {
  if (typeof target === 'string') {
    step.target.name = target
  } else {
    step.target.name = target.name
    step.target.type = target.type
  }
}

function addStep() {
  model.value = [...model.value, newStep()]
}

function removeStep(index: number) {
  model.value = model.value.filter((_, i) => i !== index)
}

function moveUp(index: number) {
  if (index === 0) return
  const next = [...model.value]
  ;[next[index - 1], next[index]] = [next[index], next[index - 1]]
  model.value = next
}

function moveDown(index: number) {
  if (index === model.value.length - 1) return
  const next = [...model.value]
  ;[next[index + 1], next[index]] = [next[index], next[index + 1]]
  model.value = next
}

function transitionsOf(step: StepModel): TransitionName[] {
  return transitionNamesFor(step.type)
}

function transitionLabel(name: TransitionName): string {
  return t(`ui.workflow_admin.transition_${name.toLowerCase()}`)
}

function actionsFor(step: StepModel, transition: TransitionName): StepAction[] {
  if (!step.actions[transition]) step.actions[transition] = []
  return step.actions[transition] as StepAction[]
}

function addAction(step: StepModel, transition: TransitionName) {
  actionsFor(step, transition).push({ type: 'ADD_TAG', tag: '' })
}

function removeAction(step: StepModel, transition: TransitionName, index: number) {
  actionsFor(step, transition).splice(index, 1)
}
</script>

<template>
  <div class="step-editor">
    <div v-for="(step, index) in model" :key="index" class="step-card">
      <div class="step-head">
        <span class="step-index">{{ index + 1 }}</span>
        <InputText
          v-model="step.name"
          class="step-name"
          maxlength="200"
          :placeholder="t('ui.workflow_admin.step_name_placeholder')"
        />
        <div class="step-move">
          <Button icon="pi pi-arrow-up" text rounded size="small" severity="secondary" :disabled="index === 0" :aria-label="t('ui.workflow_admin.move_up')" @click="moveUp(index)" />
          <Button icon="pi pi-arrow-down" text rounded size="small" severity="secondary" :disabled="index === model.length - 1" :aria-label="t('ui.workflow_admin.move_down')" @click="moveDown(index)" />
          <Button icon="pi pi-trash" text rounded size="small" severity="danger" :aria-label="t('ui.workflow_admin.remove_step')" @click="removeStep(index)" />
        </div>
      </div>

      <div class="step-fields">
        <div class="step-field">
          <label>{{ t('ui.workflow_admin.step_type') }}</label>
          <Select v-model="step.type" :options="typeOptions" optionLabel="label" optionValue="value" class="w-full" />
        </div>
        <div class="step-field">
          <label>{{ t('ui.workflow_admin.target_type') }}</label>
          <Select v-model="step.target.type" :options="targetTypeOptions" optionLabel="label" optionValue="value" class="w-full" />
        </div>
        <div class="step-field step-field-target">
          <label>{{ t('ui.workflow_admin.target') }}</label>
          <AutoComplete
            :modelValue="step.target.name"
            :suggestions="targetSuggestions[index] ?? []"
            optionLabel="name"
            :placeholder="t('ui.workflow_admin.target_placeholder')"
            class="w-full"
            @complete="completeTargetSearch(index, $event)"
            @item-select="onTargetSelect(step, $event.value)"
            @update:modelValue="onTargetSelect(step, $event)"
          >
            <template #option="{ option }">
              <span class="target-option">
                <i :class="option.type === 'GROUP' ? 'pi pi-users' : 'pi pi-user'" />
                {{ option.name }}
              </span>
            </template>
          </AutoComplete>
        </div>
      </div>

      <div class="transitions">
        <div v-for="tr in transitionsOf(step)" :key="tr" class="transition-panel">
          <div class="transition-head">{{ transitionLabel(tr) }}</div>
          <div v-for="(action, ai) in actionsFor(step, tr)" :key="ai" class="action-row">
            <Select
              v-model="action.type"
              :options="actionTypeOptions"
              optionLabel="label"
              optionValue="value"
              class="action-type"
            />
            <Select
              v-if="action.type === 'ADD_TAG' || action.type === 'REMOVE_TAG'"
              v-model="action.tag"
              :options="tagOptions"
              optionLabel="label"
              optionValue="value"
              :placeholder="t('ui.workflow_admin.select_tag')"
              filter
              class="action-tag"
            />
            <Button icon="pi pi-times" text rounded size="small" severity="danger" :aria-label="t('ui.workflow_admin.remove_action')" @click="removeAction(step, tr, ai)" />
          </div>
          <Button :label="t('ui.workflow_admin.add_action')" icon="pi pi-plus" text size="small" @click="addAction(step, tr)" />
        </div>
      </div>
    </div>

    <Button :label="t('ui.workflow_admin.add_step')" icon="pi pi-plus" size="small" severity="secondary" outlined @click="addStep" />
  </div>
</template>

<style scoped>
.step-editor {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.step-card {
  border: 1px solid var(--p-content-border-color);
  border-radius: 6px;
  padding: 0.75rem;
  background: var(--p-content-background);
}
.step-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 50%;
  background: var(--p-primary-color);
  color: var(--p-primary-contrast-color);
  font-size: 0.75rem;
  font-weight: 600;
  flex-shrink: 0;
}
.step-name {
  flex: 1;
}
.step-move {
  display: flex;
  gap: 0.125rem;
}
.step-fields {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.step-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.step-field label {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--p-text-muted-color);
}
.transitions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.transition-panel {
  border-left: 3px solid var(--p-primary-color);
  padding: 0.5rem 0 0.5rem 0.75rem;
}
.transition-head {
  font-size: 0.8125rem;
  font-weight: 600;
  margin-bottom: 0.375rem;
}
.action-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.375rem;
}
.action-type {
  width: 180px;
}
.action-tag {
  flex: 1;
}
.target-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.w-full {
  width: 100%;
}
</style>
