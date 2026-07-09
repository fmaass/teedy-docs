<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'

// Shared "Take photo" affordance: a hidden <input capture> plus the button that
// triggers it. On mobile the capture attribute opens the device camera directly;
// captured photos are emitted to the parent, which decides whether to queue them
// (DocumentEdit) or upload immediately (DocumentViewContent). This component owns
// only the UI/CSS — never the upload policy.

defineProps<{ disabled?: boolean }>()

const emit = defineEmits<{ capture: [files: File[]] }>()

const { t } = useI18n()
const inputRef = ref<HTMLInputElement | null>(null)

function open() {
  inputRef.value?.click()
}

function onChange(event: Event) {
  const input = event.target as HTMLInputElement
  const captured = input.files ? Array.from(input.files) : []
  // Reset so re-capturing the same-named photo fires change again.
  input.value = ''
  if (captured.length) emit('capture', captured)
}
</script>

<template>
  <div class="camera-capture">
    <Button
      type="button"
      icon="pi pi-camera"
      :label="t('ui.take_photo')"
      severity="secondary"
      outlined
      class="camera-btn"
      :disabled="disabled"
      @click="open"
    />
    <input
      ref="inputRef"
      type="file"
      accept="image/*"
      capture="environment"
      class="camera-input"
      @change="onChange"
    />
  </div>
</template>

<style scoped>
.camera-capture {
  margin-top: 0.75rem;
}
.camera-input {
  display: none;
}
.camera-btn {
  width: 100%;
}

@media (min-width: 601px) {
  .camera-btn {
    width: auto;
  }
}
</style>
