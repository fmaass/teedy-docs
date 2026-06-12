<script setup lang="ts">
import { ref, computed } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listWebhooks, createWebhook, deleteWebhook, WEBHOOK_EVENTS, type WebhookItem } from '../../api/webhook'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import EmptyState from '../../components/EmptyState.vue'

const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const { data: webhooksData, isLoading } = useQuery({
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
    toast.add({ severity: 'success', summary: 'Webhook added', life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to add webhook', life: 3000 })
  },
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteWebhook(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    toast.add({ severity: 'success', summary: 'Webhook deleted', life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to delete webhook', life: 3000 })
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
    urlError.value = 'Enter a valid HTTP(S) URL'
    return
  }
  addMutation.mutate()
}

function confirmDelete(webhook: WebhookItem) {
  confirm.require({
    message: `Delete webhook for "${formatEvent(webhook.event)}" to ${webhook.url}?`,
    header: 'Delete webhook',
    icon: 'pi pi-trash',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
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
        <h2>Webhooks</h2>
        <p class="section-desc">
          Receive HTTP POST notifications when document lifecycle events occur.
          The payload is <code>{"event": "EVENT_NAME", "id": "entity_id"}</code>.
        </p>
      </div>
      <Button label="Add webhook" icon="pi pi-plus" size="small" @click="showAddDialog = true" />
    </div>

    <DataTable :value="webhooks" stripedRows :loading="isLoading" class="webhooks-table">
      <Column header="Event" style="width: 220px">
        <template #body="{ data }">
          <code class="event-badge">{{ formatEvent(data.event) }}</code>
        </template>
      </Column>
      <Column field="url" header="URL">
        <template #body="{ data }">
          <span class="webhook-url">{{ data.url }}</span>
        </template>
      </Column>
      <Column header="Created" style="width: 130px">
        <template #body="{ data }">
          <span class="meta">{{ formatDate(data.create_date) }}</span>
        </template>
      </Column>
      <Column header="" style="width: 60px">
        <template #body="{ data }">
          <Button icon="pi pi-trash" text severity="danger" size="small" @click="confirmDelete(data)" aria-label="Delete webhook" />
        </template>
      </Column>
      <template #empty>
        <EmptyState icon="pi pi-link" message="No webhooks configured" />
      </template>
    </DataTable>

    <!-- Add dialog -->
    <Dialog v-model:visible="showAddDialog" header="Add webhook" :modal="true" :style="{ width: '480px' }">
      <div class="form-fields">
        <div class="form-field">
          <label for="webhook-event">Event</label>
          <Select v-model="newEvent" inputId="webhook-event" :options="eventOptions" optionLabel="label" optionValue="value" class="w-full" />
        </div>
        <div class="form-field">
          <label for="webhook-url">URL</label>
          <InputText id="webhook-url" v-model="newUrl" placeholder="https://example.com/webhook" class="w-full" :invalid="!!urlError" @keyup.enter="doAdd" />
          <small v-if="urlError" class="url-error">{{ urlError }}</small>
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="showAddDialog = false" />
        <Button label="Add" :disabled="!newUrl.trim()" :loading="addMutation.isPending.value" @click="doAdd" />
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
