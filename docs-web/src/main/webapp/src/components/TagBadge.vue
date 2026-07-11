<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  name: string
  color: string
  removable?: boolean
  /**
   * Opt-in: render the chip as an interactive button that filters by this tag.
   * Default (false) keeps the chip an inert, non-interactive span so the existing
   * render sites are untouched. Mutually additive with `removable` is not intended
   * — a chip is either a clickable filter action or a removable label.
   */
  clickable?: boolean
}>()

const emit = defineEmits<{
  remove: []
  select: []
}>()

const textColor = computed(() => {
  const hex = props.color.replace('#', '')
  if (hex.length < 6) return '#ffffff'
  const r = parseInt(hex.slice(0, 2), 16)
  const g = parseInt(hex.slice(2, 4), 16)
  const b = parseInt(hex.slice(4, 6), 16)
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
  return luminance > 0.6 ? '#1e1e1e' : '#ffffff'
})
</script>

<template>
  <button
    v-if="clickable"
    type="button"
    class="teedy-tag tag-clickable"
    :style="{ backgroundColor: color, color: textColor }"
    :aria-label="t('tag.filter_by_tag', { name })"
    @click="emit('select')"
  >{{ name }}</button>
  <span v-else class="teedy-tag" :style="{ backgroundColor: color, color: textColor }">{{ name }}<button v-if="removable" type="button" class="tag-remove-btn" :aria-label="t('tag.remove_tag', { name })" @click.stop="emit('remove')"><i class="pi pi-times" /></button></span>
</template>

<style scoped>
/* Clickable chip: strip the native button chrome so it keeps the .teedy-tag
   pill look, then add the interactive affordances (pointer cursor + a subtle
   hover/focus lift). The color/contrast is inherited from .teedy-tag + the
   inline background — this rule must not touch it. */
.tag-clickable {
  border: none;
  font-family: inherit;
  cursor: pointer;
  transition: filter 0.12s, box-shadow 0.12s;
}
.tag-clickable:hover {
  filter: brightness(1.08);
}
.tag-clickable:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--p-primary-color);
}

.tag-remove-btn {
  all: unset;
  font-size: 0.625rem;
  margin-left: 0.25rem;
  opacity: 0.7;
  cursor: pointer;
  border-radius: 50%;
  transition: opacity 0.15s;
  color: inherit;
  display: inline-flex;
  align-items: center;
}
.tag-remove-btn:hover,
.tag-remove-btn:focus-visible {
  opacity: 1;
}
</style>
