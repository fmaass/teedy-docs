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
  updateSavedFilter,
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

// --- List presentation: name search + direction toggle ---

const searchTerm = ref('')
const sortAsc = ref(true)

// The server orders by name under the DB's BINARY collation, which puts every
// upper-case name before every lower-case one ("Zebra" before "apple"). Sorting is
// therefore redone here with localeCompare, on a COPY: `filters` is backed by the
// vue-query cache, and Array.prototype.sort reorders IN PLACE — sorting it directly
// would mutate cached data shared with every other consumer of ['savedFilters'].
// The search is case-insensitive substring matching on the name.
const visibleFilters = computed<SavedFilterItem[]>(() => {
  const needle = searchTerm.value.trim().toLowerCase()
  const matched = needle
    ? filters.value.filter((f) => f.name.toLowerCase().includes(needle))
    : filters.value
  return [...matched].sort((a, b) =>
    sortAsc.value ? a.name.localeCompare(b.name) : b.name.localeCompare(a.name),
  )
})

function toggleSort() {
  sortAsc.value = !sortAsc.value
}

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

// Overwrite: saving under a name that already exists REPLACES that filter's stored
// query instead of dead-ending on the duplicate guard. It reuses the same
// case-insensitive match the guard performs — a save is only ever a create or a
// confirmed replace, never a silent second filter with a colliding name.
const replaceMutation = useMutation({
  mutationFn: (vars: { id: string; name: string }) =>
    updateSavedFilter(vars.id, vars.name, currentQueryString()),
  onSuccess: () => {
    showSaveDialog.value = false
    queryClient.invalidateQueries({ queryKey: ['savedFilters'] })
    toast.add({ severity: 'success', summary: t('ui.saved_filters.replaced'), life: 3000 })
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
  const existing = filters.value.find((f) => f.name.toLowerCase() === name.toLowerCase())
  if (existing) {
    confirmDanger({
      header: t('ui.saved_filters.replace_title'),
      message: t('ui.saved_filters.replace_confirm', { name: existing.name }),
      icon: 'pi pi-bookmark-fill',
      accept: () => replaceMutation.mutate({ id: existing.id, name }),
    })
    return
  }
  saveMutation.mutate()
}

// --- Rename ---

const showRenameDialog = ref(false)
const renameTarget = ref<SavedFilterItem | null>(null)
const renameName = ref('')
const renameError = ref('')

function openRenameDialog(filter: SavedFilterItem) {
  dropdown.value?.hide()
  renameTarget.value = filter
  renameName.value = filter.name
  renameError.value = ''
  showRenameDialog.value = true
}

const renameMutation = useMutation({
  mutationFn: (vars: { id: string; name: string; query: string }) =>
    updateSavedFilter(vars.id, vars.name, vars.query),
  onSuccess: () => {
    showRenameDialog.value = false
    queryClient.invalidateQueries({ queryKey: ['savedFilters'] })
    toast.add({ severity: 'success', summary: t('ui.saved_filters.renamed'), life: 3000 })
  },
  onError: () => {
    renameError.value = t('ui.saved_filters.rename_failed')
  },
})

function doRename() {
  renameError.value = ''
  const target = renameTarget.value
  if (!target) {
    return
  }
  const name = renameName.value.trim()
  if (!name) {
    renameError.value = t('ui.saved_filters.name_required')
    return
  }
  // Same duplicate guard as the save path, EXCLUDING the filter being renamed so a
  // no-op or case-only rename is not rejected against itself.
  if (filters.value.some((f) => f.id !== target.id && f.name.toLowerCase() === name.toLowerCase())) {
    renameError.value = t('ui.saved_filters.name_exists')
    return
  }
  // A rename carries the STORED query over verbatim — renaming from an unrelated
  // view must never silently re-capture the current route into the filter.
  renameMutation.mutate({ id: target.id, name, query: target.query })
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
        <div v-if="filters.length" class="saved-filters-toolbar">
          <InputText
            id="saved-filter-search"
            v-model="searchTerm"
            size="small"
            class="saved-filters-search"
            :placeholder="t('ui.saved_filters.search_placeholder')"
            :aria-label="t('ui.saved_filters.search_placeholder')"
          />
          <Button
            :icon="sortAsc ? 'pi pi-sort-alpha-down' : 'pi pi-sort-alpha-up'"
            text
            rounded
            size="small"
            severity="secondary"
            :aria-label="
              sortAsc ? t('ui.saved_filters.sort_descending') : t('ui.saved_filters.sort_ascending')
            "
            @click="toggleSort"
          />
        </div>
        <p v-if="!filters.length" class="saved-filters-empty">
          {{ t('ui.saved_filters.empty') }}
        </p>
        <p v-else-if="!visibleFilters.length" class="saved-filters-empty">
          {{ t('ui.saved_filters.no_matches') }}
        </p>
        <ul v-else class="saved-filters-items">
          <li v-for="filter in visibleFilters" :key="filter.id" class="saved-filters-item">
            <Button
              :label="filter.name"
              text
              size="small"
              class="saved-filters-apply"
              @click="applyFilter(filter)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              severity="secondary"
              :aria-label="t('ui.saved_filters.rename_button', { name: filter.name })"
              @click="openRenameDialog(filter)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('ui.saved_filters.delete_button', { name: filter.name })"
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
          :loading="saveMutation.isPending.value || replaceMutation.isPending.value"
          @click="doSave"
        />
      </template>
    </Dialog>

    <Dialog
      v-model:visible="showRenameDialog"
      modal
      :header="t('ui.saved_filters.rename_title')"
      :style="{ width: '24rem' }"
    >
      <div class="save-dialog-body">
        <label for="saved-filter-rename-name" class="save-dialog-label">
          {{ t('ui.saved_filters.name_label') }}
        </label>
        <InputText
          id="saved-filter-rename-name"
          v-model="renameName"
          autofocus
          :maxlength="100"
          class="save-dialog-input"
          @keyup.enter="doRename"
        />
        <small v-if="renameError" class="save-dialog-error">{{ renameError }}</small>
      </div>
      <template #footer>
        <Button :label="t('cancel')" text severity="secondary" @click="showRenameDialog = false" />
        <Button
          :label="t('rename')"
          :loading="renameMutation.isPending.value"
          @click="doRename"
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
  /* When a savable filter is active this group renders two labelled buttons ("Saved
     filters" + "Save current"). The parent filter row pins its children at their natural
     width so they wrap as whole units (#67), which would leave this two-button group
     rigid and, at a very narrow width with long (e.g. German) labels, spilling past the
     row. Capping it at the row width and letting its own buttons wrap keeps it on-screen
     — the buttons stack instead of the group overflowing. */
  flex-wrap: wrap;
  max-width: 100%;
}

.saved-filters-list {
  min-width: 14rem;
  max-width: 20rem;
}

.saved-filters-toolbar {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0 0.25rem 0.35rem;
}

.saved-filters-search {
  flex: 1;
  min-width: 0;
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
