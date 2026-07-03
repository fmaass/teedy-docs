<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'

withDefaults(
  defineProps<{
    icon?: string
    message?: string
  }>(),
  {},
)

const emit = defineEmits<{ retry: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="teedy-error" role="alert">
    <i :class="icon || 'pi pi-exclamation-triangle'" aria-hidden="true" />
    <p>{{ message || t('ui.load_error') }}</p>
    <Button :label="t('ui.retry')" icon="pi pi-refresh" outlined @click="emit('retry')" />
  </div>
</template>

<style scoped>
.teedy-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1.5rem;
  text-align: center;
  color: var(--p-text-muted-color);
}

.teedy-error i {
  font-size: 2.5rem;
  margin-bottom: 0.75rem;
  color: var(--p-red-400);
}

.teedy-error p {
  margin: 0 0 1rem;
  font-size: 0.9375rem;
}
</style>
