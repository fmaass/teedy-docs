<script setup lang="ts">
import Button from 'primevue/button'

defineProps<{
  icon?: string
  message: string
  actionLabel?: string
}>()

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="teedy-empty">
    <i :class="icon || 'pi pi-inbox'" />
    <p>{{ message }}</p>
    <slot>
      <Button v-if="actionLabel" :label="actionLabel" outlined @click="emit('action')" />
    </slot>
  </div>
</template>
