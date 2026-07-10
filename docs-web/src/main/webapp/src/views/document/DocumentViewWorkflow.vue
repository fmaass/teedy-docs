<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getRoutes, startRoute, cancelRoute, routeKeys, type Route, type RouteStep, type RouteStepTransition } from '../../api/route'
import { listRouteModels, routeModelKeys, type RouteModelListItem } from '../../api/routeModel'
import { stepRender, transitionSeverity, routeStatusSeverity, canStartRoute, startableModels, timeAgo } from '../../utils/routeHistory'
import { useRouteActions } from '../../composables/useRouteActions'
import { injectDocument } from './documentKey'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'

const { t } = useI18n()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()
const doc = injectDocument()

const documentId = computed(() => doc.value?.id ?? '')

// --- Route history (GET /route) ---
const { data: routesData } = useQuery({
  queryKey: computed(() => routeKeys.all(documentId.value)),
  queryFn: () => getRoutes(documentId.value).then((r) => r.data),
  enabled: computed(() => !!documentId.value),
})
const routes = computed<Route[]>(() => routesData.value?.routes ?? [])

// The current step comes from the doc-detail payload (route_step), which also tells us if the
// caller may act now (transitionable). GET /route supplies the full history.
const currentStep = computed(() => doc.value?.route_step ?? null)
const hasActiveRoute = computed(() => !!currentStep.value)

// A route is cancellable only while ACTIVE and the doc is writable (never on a terminal route).
function canCancel(route: Route): boolean {
  return route.status === 'ACTIVE' && !!doc.value?.writable
}

// --- Startable models (READ-ACL filtered list; complete ones only, per B4) ---
const { data: modelsData } = useQuery({
  queryKey: routeModelKeys.all(),
  queryFn: () => listRouteModels().then((r) => r.data),
})
const models = computed<RouteModelListItem[]>(() => modelsData.value?.routemodels ?? [])
const startable = computed(() => startableModels(models.value))
const showStart = computed(() =>
  canStartRoute(!!doc.value?.writable, hasActiveRoute.value, models.value),
)

const selectedModelId = ref<string | null>(null)
const starting = ref(false)

async function handleStart() {
  if (!selectedModelId.value || !documentId.value) return
  starting.value = true
  try {
    await startRoute(documentId.value, selectedModelId.value)
    await refetchAll()
    selectedModelId.value = null
    toast.add({ severity: 'success', summary: t('ui.workflow.started'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.workflow.failed_start'), life: 3000 })
  } finally {
    starting.value = false
  }
}

// --- ACT controls (validate / approve / reject) ---
const { pending, isDisabled, act } = useRouteActions(documentId.value, currentStep)
const comment = ref('')

async function refetchAll() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: routeKeys.all(documentId.value) }),
    queryClient.invalidateQueries({ queryKey: ['document', documentId.value] }),
  ])
}

async function submit(transition: RouteStepTransition) {
  const outcome = await act(transition, comment.value.trim() || undefined)
  switch (outcome.kind) {
    case 'advanced':
    case 'completed':
      comment.value = ''
      await refetchAll()
      toast.add({ severity: 'success', summary: t('ui.workflow.acted'), life: 2000 })
      break
    case 'access-ended':
      // The caller lost read access as a side effect: tell them explicitly and leave, rather than
      // refetching the document into the generic not-found flow.
      toast.add({ severity: 'info', summary: t('ui.workflow.access_ended'), life: 4000 })
      router.push({ name: 'documents' })
      break
    case 'step-changed':
      // The route advanced under the user: refresh so they see the real current step, and inform.
      await refetchAll()
      comment.value = ''
      toast.add({ severity: 'warn', summary: t('ui.workflow.step_changed'), life: 4000 })
      break
    case 'error':
      toast.add({ severity: 'error', summary: t('ui.workflow.failed_action'), life: 3000 })
      break
  }
}

function confirmReject() {
  confirmDanger({
    message: t('ui.workflow.reject_confirm'),
    header: t('ui.workflow.reject'),
    icon: 'pi pi-times-circle',
    accept: () => submit('REJECTED'),
  })
}

function confirmCancel(route: Route) {
  confirmDanger({
    message: t('ui.workflow.cancel_confirm'),
    header: t('ui.workflow.cancel'),
    icon: 'pi pi-ban',
    accept: async () => {
      try {
        // Cancel targets the active route on the document (the backend resolves it by documentId).
        void route
        await cancelRoute(documentId.value)
        await refetchAll()
        toast.add({ severity: 'success', summary: t('ui.workflow.cancelled'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.workflow.failed_cancel'), life: 3000 })
      }
    },
  })
}

// --- Presentational helpers ---
function statusLabel(status: Route['status']): string {
  return t(`ui.workflow.status_${status.toLowerCase()}`)
}

function transitionLabel(transition: NonNullable<RouteStep['transition']>): string {
  return t(`ui.workflow.transition_${transition.toLowerCase()}`)
}

function timeAgoLabel(ts: number | null): string {
  const ago = timeAgo(ts)
  if (!ago) return ''
  if (ago.unit === 'now') return t('ui.workflow.time_now')
  return t(`ui.workflow.time_${ago.unit}`, { count: ago.count })
}
</script>

<template>
  <div v-if="doc" class="workflow-view">
    <!-- Current step + ACT controls (only when there is an active route) -->
    <section v-if="currentStep" class="wf-section">
      <h3>{{ t('ui.workflow.current_step') }}</h3>
      <div class="wf-current">
        <div class="wf-current-main">
          <span class="wf-step-name">{{ currentStep.name }}</span>
          <span v-if="currentStep.target?.name" class="wf-assignee">
            <i class="pi" :class="currentStep.target.type === 'GROUP' ? 'pi-users' : 'pi-user'" aria-hidden="true" />
            {{ currentStep.target.name }}
          </span>
        </div>

        <!-- ACT controls: shown only when the current user may act (transitionable). Always submit the
             displayed step id (inside useRouteActions); disabled while a request is pending. -->
        <div v-if="currentStep.transitionable" class="wf-act">
          <Textarea
            v-model="comment"
            :placeholder="t('ui.workflow.comment_placeholder')"
            rows="2"
            autoResize
            class="wf-comment"
            :disabled="pending"
          />
          <div class="wf-act-buttons">
            <Button
              v-if="currentStep.type === 'VALIDATE'"
              :label="t('ui.workflow.validate')"
              icon="pi pi-check"
              size="small"
              :loading="pending"
              :disabled="isDisabled()"
              @click="submit('VALIDATED')"
            />
            <Button
              v-if="currentStep.type === 'APPROVE'"
              :label="t('ui.workflow.approve')"
              icon="pi pi-check"
              size="small"
              severity="success"
              :loading="pending"
              :disabled="isDisabled()"
              @click="submit('APPROVED')"
            />
            <Button
              v-if="currentStep.type === 'APPROVE'"
              :label="t('ui.workflow.reject')"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              :disabled="isDisabled()"
              @click="confirmReject"
            />
          </div>
        </div>
      </div>
    </section>

    <!-- Start a workflow (writable + no active route + >=1 complete model) -->
    <section v-if="showStart" class="wf-section">
      <h3>{{ t('ui.workflow.start') }}</h3>
      <div class="wf-start">
        <Select
          v-model="selectedModelId"
          :options="startable"
          optionLabel="name"
          optionValue="id"
          :placeholder="t('ui.workflow.select_model')"
          size="small"
          class="wf-start-select"
        />
        <Button
          :label="t('ui.workflow.start_button')"
          icon="pi pi-play"
          size="small"
          :disabled="!selectedModelId"
          :loading="starting"
          @click="handleStart"
        />
      </div>
    </section>

    <p v-else-if="!currentStep && !routes.length" class="wf-empty">{{ t('ui.workflow.no_active_route') }}</p>

    <!-- History: every route on the document (active + terminal) -->
    <section v-if="routes.length" class="wf-section">
      <h3>{{ t('ui.workflow.history') }}</h3>
      <div v-for="route in routes" :key="route.id" class="wf-route">
        <div class="wf-route-head">
          <span class="wf-route-name">{{ route.name }}</span>
          <Tag :severity="routeStatusSeverity(route.status)" :value="statusLabel(route.status)" />
          <span v-if="route.initiator_username" class="wf-route-initiator">
            {{ t('ui.workflow.initiated_by', { name: route.initiator_username }) }}
          </span>
          <span class="wf-route-time">{{ timeAgoLabel(route.create_date) }}</span>
          <Button
            v-if="canCancel(route)"
            :label="t('ui.workflow.cancel')"
            icon="pi pi-ban"
            size="small"
            severity="danger"
            text
            @click="confirmCancel(route)"
          />
        </div>
        <ol class="wf-steps">
          <li v-for="step in route.steps" :key="step.id" class="wf-step">
            <span class="wf-step-title">{{ step.name }}</span>
            <span v-if="step.target?.name" class="wf-step-target">
              <i class="pi" :class="step.target.type === 'GROUP' ? 'pi-users' : 'pi-user'" aria-hidden="true" />
              {{ step.target.name }}
            </span>
            <!-- Transition rendering (3 cases from stepRender): pending / system-ended / acted verb -->
            <template v-if="stepRender(step).kind === 'pending'">
              <Tag severity="info" :value="t('ui.workflow.pending')" />
            </template>
            <template v-else-if="stepRender(step).kind === 'system'">
              <Tag severity="secondary" :value="t('ui.workflow.system_ended')" />
            </template>
            <template v-else>
              <Tag :severity="transitionSeverity(step.transition)" :value="transitionLabel(step.transition!)" />
              <span v-if="step.validator_username" class="wf-step-by">
                {{ t('ui.workflow.validator') }} {{ step.validator_username }}
              </span>
            </template>
            <span v-if="step.end_date" class="wf-step-time">{{ timeAgoLabel(step.end_date) }}</span>
            <p v-if="step.comment" class="wf-step-comment">{{ step.comment }}</p>
          </li>
        </ol>
      </div>
    </section>

    <p v-else class="wf-empty">{{ t('ui.workflow.no_history') }}</p>

    <!-- Explicit surface if the caller has no startable model and no history/active route at all -->
    <Message v-if="!currentStep && !showStart && !routes.length && !doc.writable" severity="secondary" :closable="false">
      {{ t('ui.workflow.no_startable_models') }}
    </Message>
  </div>
</template>

<style scoped>
.workflow-view {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.wf-section h3 {
  margin: 0 0 0.75rem;
  font-size: 0.9375rem;
  font-weight: 600;
}

.wf-current {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  padding: 0.875rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.wf-current-main {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.wf-step-name {
  font-weight: 600;
  font-size: 0.9375rem;
}

.wf-assignee,
.wf-step-target {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.wf-act {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.wf-comment {
  width: 100%;
  font-size: 0.875rem;
}

.wf-act-buttons {
  display: flex;
  gap: 0.5rem;
}

.wf-start {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.wf-start-select {
  min-width: 260px;
}

.wf-route {
  border: 1px solid var(--p-content-border-color);
  border-radius: 8px;
  padding: 0.875rem 1rem;
  margin-bottom: 1rem;
}

.wf-route-head {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  flex-wrap: wrap;
  margin-bottom: 0.75rem;
}

.wf-route-name {
  font-weight: 600;
  font-size: 0.9375rem;
}

.wf-route-initiator,
.wf-route-time,
.wf-step-time,
.wf-step-by {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.wf-route-time {
  margin-left: auto;
}

.wf-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.wf-step {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  padding: 0.5rem 0.75rem;
  border-radius: 6px;
  background: var(--p-content-hover-background);
}

.wf-step-title {
  font-weight: 500;
  font-size: 0.875rem;
}

.wf-step-comment {
  flex-basis: 100%;
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--p-text-color);
  white-space: pre-wrap;
}

.wf-empty {
  font-size: 0.875rem;
  color: var(--p-text-muted-color);
  margin: 0;
}
</style>
