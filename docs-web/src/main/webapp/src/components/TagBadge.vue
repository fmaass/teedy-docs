<script setup lang="ts">
defineProps<{
  name: string
  color: string
  removable?: boolean
}>()

const emit = defineEmits<{
  remove: []
}>()
</script>

<template>
  <span class="teedy-tag" :style="{ backgroundColor: color }">{{ name }}<i v-if="removable" class="pi pi-times tag-remove-icon" role="button" aria-label="Remove tag" @click.stop="emit('remove')" /></span>
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
