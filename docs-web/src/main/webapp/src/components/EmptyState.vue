<script setup lang="ts">
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
      <button v-if="actionLabel" class="p-button p-button-outlined" @click="emit('action')">
        {{ actionLabel }}
      </button>
    </slot>
  </div>
</template>
