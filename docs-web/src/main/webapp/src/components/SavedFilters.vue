<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import Button from 'primevue/button'
import Popover from 'primevue/popover'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../composables/useConfirmDanger'
import {
  listSavedFilters,
  createSavedFilter,
  deleteSavedFilter,
  type SavedFilterItem,
} from '../api/savedfilter'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

// The filter dimensions the documents route carries. The "save" affordance is
// derived from the RAW route.query (NOT the tagFilter store's hasActiveFilters,
// which excludes `workflow`), so a workflow-only filter is saveable.
const FILTER_KEYS = ['tags', 'exclude', 'mode', 'search', 'workflow'] as const

const hasSavableFilter = computed(() =>
  FILTER_KEYS.some((k) => {
    const v = route.query[k]
    return v !== undefined && v !== null && v !== ''
  }),
)

// Serialize the CURRENT route.query VERBATIM into the saved query string for the
// filter dimensions: every value the URL actually carries is preserved exactly —
// including empty values and (were the URL ever malformed) repeated keys, which
// are appended as-is and left to the backend contract to reject. Only non-filter
// keys are dropped. The URL is the source of truth; no normalization here.
function currentQueryString(): string {
  const params = new URLSearchParams()
  for (const k of FILTER_KEYS) {
    const raw = route.query[k]
    if (raw === undefined) continue
    for (const v of Array.isArray(raw) ? raw : [raw]) {
      params.append(k, v ?? '')
    }
  }
  return params.toString()
}

// Parse a stored query string back into a vue-router LocationQuery. Applying flows
// through the existing initFromUrl() via router.push — no new hydration path.
function parseQueryString(query: string): LocationQuery {
  const out: LocationQuery = {}
  new URLSearchParams(query).forEach((v, k) => {
    out[k] = v
  })
  return out
}

const { data: filtersData } = useQuery({
  queryKey: ['savedFilters'],
  queryFn: () => listSavedFilters().then((r) => r.data.saved_filters),
})

const filters = computed<SavedFilterItem[]>(() => filtersData.value ?? [])

// --- Dropdown ---

const dropdown = ref<InstanceType<typeof Popover> | null>(null)
function toggleDropdown(event: Event) {
  dropdown.value?.toggle(event)
}

function applyFilter(filter: SavedFilterItem) {
  dropdown.value?.hide()
  router.push({ name: 'documents', query: parseQueryString(filter.query) })
}

// --- Save dialog ---

const showSaveDialog = ref(false)
const newName = ref('')
const nameError = ref('')

function openSaveDialog() {
  newName.value = ''
  nameError.value = ''
  showSaveDialog.value = true
}

const saveMutation = useMutation({
  mutationFn: () => createSavedFilter(newName.value.trim(), currentQueryString()),
  onSuccess: () => {
    showSaveDialog.value = false
    queryClient.invalidateQueries({ queryKey: ['savedFilters'] })
    toast.add({ severity: 'success', summary: t('ui.saved_filters.saved'), life: 3000 })
  },
  onError: () => {
    nameError.value = t('ui.saved_filters.save_failed')
  },
})

function doSave() {
  nameError.value = ''
  const name = newName.value.trim()
  if (!name) {
    nameError.value = t('ui.saved_filters.name_required')
    return
  }
  if (filters.value.some((f) => f.name.toLowerCase() === name.toLowerCase())) {
    nameError.value = t('ui.saved_filters.name_exists')
    return
  }
  saveMutation.mutate()
}

// --- Delete ---

const deleteMutation = useMutation({
  mutationFn: (id: string) => deleteSavedFilter(id),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['savedFilters'] })
    toast.add({ severity: 'success', summary: t('ui.saved_filters.deleted'), life: 3000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.saved_filters.delete_failed'), life: 3000 })
  },
})

function confirmDelete(filter: SavedFilterItem) {
  confirmDanger({
    message: t('ui.saved_filters.delete_confirm', { name: filter.name }),
    header: t('ui.saved_filters.delete_title'),
    accept: () => deleteMutation.mutate(filter.id),
  })
}
</script>

<template>
  <div class="saved-filters">
    <Button
      icon="pi pi-bookmark"
      :label="t('ui.saved_filters.saved_label')"
      text
      size="small"
      severity="secondary"
      :aria-label="t('ui.saved_filters.saved_label')"
      @click="toggleDropdown"
    />
    <Button
      v-if="hasSavableFilter"
      icon="pi pi-bookmark-fill"
      :label="t('ui.saved_filters.save_current')"
      text
      size="small"
      severity="secondary"
      @click="openSaveDialog"
    />

    <Popover ref="dropdown">
      <div class="saved-filters-list">
        <p v-if="!filters.length" class="saved-filters-empty">
          {{ t('ui.saved_filters.empty') }}
        </p>
        <ul v-else class="saved-filters-items">
          <li v-for="filter in filters" :key="filter.id" class="saved-filters-item">
            <Button
              :label="filter.name"
              text
              size="small"
              class="saved-filters-apply"
              @click="applyFilter(filter)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('ui.saved_filters.delete_title')"
              @click="confirmDelete(filter)"
            />
          </li>
        </ul>
      </div>
    </Popover>

    <Dialog
      v-model:visible="showSaveDialog"
      modal
      :header="t('ui.saved_filters.save_current')"
      :style="{ width: '24rem' }"
    >
      <div class="save-dialog-body">
        <label for="saved-filter-name" class="save-dialog-label">
          {{ t('ui.saved_filters.name_label') }}
        </label>
        <InputText
          id="saved-filter-name"
          v-model="newName"
          autofocus
          :maxlength="100"
          class="save-dialog-input"
          @keyup.enter="doSave"
        />
        <small v-if="nameError" class="save-dialog-error">{{ nameError }}</small>
      </div>
      <template #footer>
        <Button :label="t('cancel')" text severity="secondary" @click="showSaveDialog = false" />
        <Button
          :label="t('save')"
          :loading="saveMutation.isPending.value"
          @click="doSave"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.saved-filters {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.saved-filters-list {
  min-width: 14rem;
  max-width: 20rem;
}

.saved-filters-empty {
  margin: 0;
  padding: 0.25rem 0.5rem;
  font-size: 0.8rem;
  color: var(--p-text-muted-color);
}

.saved-filters-items {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 20rem;
  overflow-y: auto;
}

.saved-filters-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.saved-filters-apply {
  flex: 1;
  justify-content: flex-start;
  text-align: left;
}

.save-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.save-dialog-label {
  font-size: 0.85rem;
  font-weight: 500;
}

.save-dialog-input {
  width: 100%;
}

.save-dialog-error {
  color: var(--p-red-500);
  font-size: 0.8rem;
}
</style>
