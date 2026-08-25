<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * The calling user's OWN access count for one document or file (#300).
 *
 * It never shows anybody else's number: the endpoint behind it is scoped to the caller, and this
 * component has no way to render a name. `count` is undefined while the counts are still loading,
 * and the badge renders nothing then rather than flashing a wrong zero.
 */
const props = defineProps<{
  count: number | undefined
  /** Which wording to use — a document was "opened", a file was "accessed". */
  kind: 'document' | 'file'
}>()

const { t } = useI18n()

const label = computed(() =>
  t(props.kind === 'document' ? 'ui.access.personal_document' : 'ui.access.personal_file', props.count ?? 0),
)
</script>

<template>
  <span v-if="count !== undefined" class="access-count" :title="label" :aria-label="label">
    <i class="pi pi-eye" aria-hidden="true" />
    <span class="access-count-value">{{ count }}</span>
  </span>
</template>

<style scoped>
.access-count {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  color: var(--p-text-muted-color);
  font-size: 0.75rem;
  white-space: nowrap;
}

.access-count i {
  font-size: 0.6875rem;
}

.access-count-value {
  font-variant-numeric: tabular-nums;
}
</style>
