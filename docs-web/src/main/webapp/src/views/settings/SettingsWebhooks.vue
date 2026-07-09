<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listWebhooks, createWebhook, deleteWebhook, WEBHOOK_EVENTS, type WebhookItem } from '../../api/webhook'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const { data: webhooksData, isLoading, isError, refetch } = useQuery({
  queryKey: ['webhooks'],
  queryFn: () => listWebhooks().then((r) => r.data.webhooks),
})

const webhooks = computed(() => webhooksData.value ?? [])

const showAddDialog = ref(false)
const newEvent = ref(WEBHOOK_EVENTS[0])
const newUrl = ref('')

const eventOptions = WEBHOOK_EVENTS.map((e) => ({ label: e.replace(/_/g, ' '), value: e }))

const urlError = ref('')

const addMutation = useMutation({
  mutationFn: () => createWebhook(newEvent.value, newUrl.value),
  onSuccess: () => {
    showAddDialog.value = false
    newUrl.value = ''
    queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    toast.add({ severity: 'success', summary: t('ui.webhooks.webhook_added'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.webhooks.failed_add'), life: 3000 })
  },
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteWebhook(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    toast.add({ severity: 'success', summary: t('ui.webhooks.webhook_deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.webhooks.failed_delete'), life: 3000 })
  },
})

function isValidUrl(str: string): boolean {
  try {
    const url = new URL(str)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function doAdd() {
  urlError.value = ''
  const url = newUrl.value.trim()
  if (!url) return
  if (!isValidUrl(url)) {
    urlError.value = t('ui.webhooks.url_invalid')
    return
  }
  addMutation.mutate()
}

function confirmDelete(webhook: WebhookItem) {
  confirmDanger({
    message: t('ui.webhooks.delete_confirm', { event: formatEvent(webhook.event), url: webhook.url }),
    header: t('ui.webhooks.delete_title'),
    accept: () => deleteMutation.mutate(webhook.id),
  })
}

function formatEvent(event: string) {
  return event.replace(/_/g, ' ')
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <div class="webhooks-settings">
    <div class="section-header">
      <div>
        <h2>{{ t('ui.webhooks.title') }}</h2>
        <p class="section-desc">
          {{ t('ui.webhooks.description') }}
          {{ t('ui.webhooks.payload_hint') }} <code>{"event": "EVENT_NAME", "id": "entity_id"}</code>.
        </p>
      </div>
      <Button :label="t('ui.webhooks.add_webhook')" icon="pi pi-plus" size="small" @click="showAddDialog = true" />
    </div>

    <DataTable :value="webhooks" stripedRows :loading="isLoading" class="webhooks-table">
      <Column :header="t('ui.webhooks.event')" style="width: 220px">
        <template #body="{ data }">
          <code class="event-badge">{{ formatEvent(data.event) }}</code>
        </template>
      </Column>
      <Column field="url" :header="t('ui.webhooks.url')">
        <template #body="{ data }">
          <span class="webhook-url">{{ data.url }}</span>
        </template>
      </Column>
      <Column :header="t('ui.webhooks.created')" style="width: 130px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 60px">
        <template #body="{ data }">
          <Button icon="pi pi-trash" text severity="danger" size="small" @click="confirmDelete(data)" :aria-label="t('ui.webhooks.delete_title')" />
        </template>
      </Column>
      <template #empty>
        <ErrorState v-if="isError" @retry="refetch()" />
        <EmptyState v-else icon="pi pi-link" :message="t('ui.webhooks.no_webhooks')" />
      </template>
    </DataTable>

    <!-- Add dialog -->
    <Dialog v-model:visible="showAddDialog" :header="t('ui.webhooks.add_title')" :modal="true" :style="{ width: '480px' }">
      <div class="form-fields">
        <div class="form-field">
          <label for="webhook-event">{{ t('ui.webhooks.event') }}</label>
          <Select v-model="newEvent" inputId="webhook-event" :options="eventOptions" optionLabel="label" optionValue="value" class="w-full" />
        </div>
        <div class="form-field">
          <label for="webhook-url">{{ t('ui.webhooks.url') }}</label>
          <InputText id="webhook-url" v-model="newUrl" :placeholder="t('ui.webhooks.url_placeholder')" class="w-full" :invalid="!!urlError" @keyup.enter="doAdd" />
          <small v-if="urlError" class="url-error">{{ urlError }}</small>
        </div>
      </div>
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showAddDialog = false" />
        <Button :label="t('add')" :disabled="!newUrl.trim()" :loading="addMutation.isPending.value" @click="doAdd" />
      </template>
    </Dialog>

  </div>
</template>

<style scoped>
.webhooks-settings {
  max-width: 700px;
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
.section-desc code {
  font-size: 0.75rem;
  background: var(--p-content-hover-background);
  padding: 0.0625rem 0.25rem;
  border-radius: 3px;
}

.event-badge {
  font-family: monospace;
  font-size: 0.75rem;
  background: var(--p-content-hover-background);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  text-transform: lowercase;
}

.webhook-url {
  font-size: 0.8125rem;
  word-break: break-all;
}

.meta {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

.form-fields {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
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
.w-full {
  width: 100%;
}
.url-error {
  color: var(--p-red-500);
  font-size: 0.75rem;
}
</style>
