<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
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
import {
  FILTER_KEYS,
  serialize as serializeFilterQuery,
  parse as parseFilterQuery,
  equals as filterQueriesEqual,
} from '../utils/savedFilterQuery'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

// The "save" affordance is derived from the RAW route.query (NOT the tagFilter store's
// hasActiveFilters, which excludes `workflow`), so a workflow-only filter is saveable.
// The dimension list itself, and the verbatim-capture / order-insensitive-compare rules,
// live in utils/savedFilterQuery so capturing a filter and recognising an APPLIED one
// share one definition.
const hasSavableFilter = computed(() =>
  FILTER_KEYS.some((k) => {
    const v = route.query[k]
    return v !== undefined && v !== null && v !== ''
  }),
)

function currentQueryString(): string {
  return serializeFilterQuery(route.query)
}

const { data: filtersData } = useQuery({
  queryKey: ['savedFilters'],
  queryFn: () => listSavedFilters().then((r) => r.data.saved_filters),
})

const filters = computed<SavedFilterItem[]>(() => filtersData.value ?? [])

// #297: which stored filter is APPLIED right now. DERIVED state — the active filter is
// the stored one whose query selects the same documents as the current route. The
// comparison is order-insensitive, so a filter saved from a differently-ordered URL still
// matches the canonical one tagFilter.buildFilterQuery rewrites, and `favorites` is not a
// dimension, so toggling favourites cannot un-highlight the applied filter.
//
// An UNFILTERED route never has an active filter: without this guard, a stored filter
// with an empty query would match the bare document list and leave the toolbar
// permanently marked.
const activeFilter = computed<SavedFilterItem | null>(() => {
  if (!hasSavableFilter.value) return null
  const current = currentQueryString()
  return filters.value.find((f) => filterQueriesEqual(current, f.query)) ?? null
})

// #297 part 2: the applied filter's IDENTITY, persisted in the URL as `filter=<id>` by
// applyFilter and carried through every later criteria edit by the store's serializer
// (tagFilter.buildFilterQuery). Only a non-empty scalar counts — a hand-edited or
// duplicated key is no identity.
const routeFilterId = computed<string | null>(() => {
  const raw = route.query.filter
  return typeof raw === 'string' && raw !== '' ? raw : null
})

// The MODIFIED state: a filter was LOADED and its criteria have since been edited, so
// nothing matches exactly any more but the URL still says which filter this started as.
// Ranked strictly BELOW an exact match — comparison is the truth, an id is only memory —
// and gated on the same unfiltered guard, so clearing the criteria reads as plain even in
// the instant before the serializer drops the now-meaningless id. An id naming a filter
// that no longer exists (deleted in another tab) names nothing: also plain.
const modifiedFilter = computed<SavedFilterItem | null>(() => {
  if (activeFilter.value || !hasSavableFilter.value) return null
  const id = routeFilterId.value
  if (!id) return null
  return filters.value.find((f) => f.id === id) ?? null
})

// The URL identity must never CONTRADICT the derived truth: once the criteria exactly
// describe a stored filter, THAT filter is the active one, and a stale id left over from
// the filter it was edited away from is corrected to name it. Surgical (the current query
// with one key replaced), and only ever while an id is already present — an ad-hoc filter
// that merely happens to match a stored one was never loaded, and stamping an identity
// onto it would make a later edit claim the user had opened a saved filter.
watch(
  [activeFilter, () => route.query.filter],
  ([active, id]) => {
    if (!active || typeof id !== 'string' || id === '' || id === active.id) return
    router.replace({ name: 'documents', query: { ...route.query, filter: active.id } })
  },
  { immediate: true },
)

// The toolbar keeps the filter's NAME in both states; the modified one wraps it in a
// label that says so, so the state survives a greyscale screenshot and reaches a screen
// reader — it is never carried by colour alone.
const toolbarLabel = computed(() => {
  if (activeFilter.value) return activeFilter.value.name
  if (modifiedFilter.value) {
    return t('ui.saved_filters.modified_label', { name: modifiedFilter.value.name })
  }
  return t('ui.saved_filters.saved_label')
})

// The accessible name keeps the control's PURPOSE and appends the active (or modified)
// filter's name. Replacing it with the bare filter name would hide what the button does
// from a screen reader, and would make the toolbar button collide with the popover row
// that applies the same filter (both e2e lookups and any by-name automation would then be
// ambiguous).
const toolbarAriaLabel = computed(() => {
  if (activeFilter.value) return `${t('ui.saved_filters.saved_label')}: ${activeFilter.value.name}`
  if (modifiedFilter.value) {
    return `${t('ui.saved_filters.saved_label')}: ${t('ui.saved_filters.modified_label', {
      name: modifiedFilter.value.name,
    })}`
  }
  return t('ui.saved_filters.saved_label')
})

const toolbarStateClass = computed(() => {
  if (activeFilter.value) return 'saved-filters-active'
  if (modifiedFilter.value) return 'saved-filters-modified'
  return undefined
})

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
  // The applied filter's identity rides in the URL alongside its criteria, so it survives
  // a reload and the back button, and the store's serializer carries it through every
  // later criteria edit — which is what turns an edit into the MODIFIED state instead of
  // a silent drop to plain. Applying another filter replaces the id; clearing the
  // criteria drops it (the serializer emits it only alongside a real dimension).
  router.push({
    name: 'documents',
    query: { ...parseFilterQuery(filter.query), filter: filter.id },
  })
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
      :label="toolbarLabel"
      text
      size="small"
      severity="secondary"
      :class="toolbarStateClass"
      :aria-current="activeFilter ? 'true' : undefined"
      :aria-label="toolbarAriaLabel"
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
          <li
            v-for="filter in visibleFilters"
            :key="filter.id"
            class="saved-filters-item"
            :class="{
              active: filter.id === activeFilter?.id,
              modified: filter.id === modifiedFilter?.id,
            }"
          >
            <Button
              :label="filter.name"
              text
              size="small"
              class="saved-filters-apply"
              :aria-current="filter.id === activeFilter?.id ? 'true' : undefined"
              :aria-label="
                filter.id === modifiedFilter?.id
                  ? t('ui.saved_filters.modified_label', { name: filter.name })
                  : undefined
              "
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

/* #297: the APPLIED saved filter — the toolbar button carries its name, and the row that
   applies it is marked in the list. Same treatment as the admin nav's current link
   (AdminNavPanel `.admin-nav-link.active`), built from theme tokens so a custom
   main_color follows it. Nothing here renders while no stored filter matches the route,
   which is why the captured document-list/gallery surfaces are untouched. */
.saved-filters-active {
  color: var(--p-primary-color);
  font-weight: 600;
}

.saved-filters-item.active {
  background: color-mix(in srgb, var(--p-primary-color) 15%, transparent);
  border-radius: 4px;
}

.saved-filters-item.active .saved-filters-apply {
  color: var(--p-primary-color);
  font-weight: 600;
}

/* #297 part 2: the LOADED-then-EDITED filter. Deliberately not the active treatment — it
   must read as "this started as Invoices, but what you are looking at is no longer
   Invoices": a muted, italic label against the active state's full primary colour, and a
   faded inset marker instead of the solid fill. The marker is an inset shadow rather than
   a border so the row's contents do not shift relative to its unmarked siblings. Neither
   state is carried by colour alone — the label itself says "(modified)". */
.saved-filters-modified {
  /* A FADED primary: unmistakably about the same saved filter as the active state, but
     visibly not it — against the plain button's secondary grey it still reads as "a
     filter is loaded". Mixed toward the muted text colour rather than toward the
     background so it keeps its contrast in both themes. */
  color: color-mix(in srgb, var(--p-primary-color) 70%, var(--p-text-muted-color));
  font-style: italic;
}

.saved-filters-item.modified {
  background: color-mix(in srgb, var(--p-primary-color) 7%, transparent);
  border-radius: 4px;
  box-shadow: inset 2px 0 0 color-mix(in srgb, var(--p-primary-color) 55%, transparent);
}

.saved-filters-item.modified .saved-filters-apply {
  font-style: italic;
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
