<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import { useToast } from 'primevue/usetoast'
import Button from 'primevue/button'
import { addFavorite, removeFavorite } from '../api/favorite'
import { queryKeys } from '../api/queryKeys'

// A single star toggle used in the document list rows and the document view header.
// It owns the optimistic flip and the TanStack invalidation of BOTH the document list
// and the affected document-detail keys, so every place a document is rendered
// re-reads the authoritative favorite state after a toggle.
const props = defineProps<{
  documentId: string
  favorite: boolean
  /** Larger control for the document-view header (default is a compact list-row star). */
  large?: boolean
}>()

const { t } = useI18n()
const toast = useToast()
const queryClient = useQueryClient()

// Local optimistic mirror of the prop; reconciled whenever the prop changes (a refetch
// or a parent-driven update replaces the optimistic guess with the server truth).
const starred = ref(props.favorite)
watch(() => props.favorite, (v) => { starred.value = v })

const pending = ref(false)

async function toggle(event: Event) {
  // Prevent the surrounding row-click (open document) from firing on the star cell.
  event.stopPropagation()
  if (pending.value) return

  const next = !starred.value
  starred.value = next // optimistic
  pending.value = true
  try {
    if (next) {
      await addFavorite(props.documentId)
    } else {
      await removeFavorite(props.documentId)
    }
    // Invalidate BOTH the list (favorite flag + the favorites=me filtered view) and this
    // document's detail key, so a slide-over/detail open reflects the new state.
    queryClient.invalidateQueries({ queryKey: queryKeys.documents() })
    queryClient.invalidateQueries({ queryKey: queryKeys.document(props.documentId) })
  } catch {
    starred.value = !next // roll back the optimistic flip
    toast.add({ severity: 'error', summary: t('ui.favorite.toggle_failed'), life: 3000 })
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <Button
    text
    rounded
    :size="large ? undefined : 'small'"
    class="favorite-star"
    :class="{ 'is-on': starred }"
    :icon="starred ? 'pi pi-star-fill' : 'pi pi-star'"
    :aria-pressed="starred"
    :aria-label="starred ? t('ui.favorite.remove') : t('ui.favorite.add')"
    v-tooltip.top="starred ? t('ui.favorite.remove') : t('ui.favorite.add')"
    @click="toggle"
    @dblclick.stop
  />
</template>

<style scoped>
.favorite-star {
  color: var(--p-text-muted-color);
}
.favorite-star.is-on {
  color: var(--teedy-warning-text, #d4a017);
}
</style>
