<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  color: string
  removable?: boolean
}>()

const emit = defineEmits<{
  remove: []
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
  <span class="teedy-tag" :style="{ backgroundColor: color, color: textColor }">{{ name }}<i v-if="removable" class="pi pi-times tag-remove-icon" role="button" aria-label="Remove tag" @click.stop="emit('remove')" /></span>
</template>

<style scoped>
.tag-remove-icon {
  font-size: 0.625rem;
  margin-left: 0.25rem;
  opacity: 0.7;
  cursor: pointer;
  border-radius: 50%;
  transition: opacity 0.15s;
  color: inherit;
}
.tag-remove-icon:hover {
  opacity: 1;
}
</style>
