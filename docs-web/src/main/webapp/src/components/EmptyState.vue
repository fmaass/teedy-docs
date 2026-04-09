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

<style scoped>
.teedy-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1.5rem;
  text-align: center;
  color: var(--teedy-text-muted);
}

.teedy-empty i {
  font-size: 2.5rem;
  margin-bottom: 0.75rem;
}

.teedy-empty p {
  margin: 0 0 1rem;
  font-size: 0.9375rem;
}
</style>
