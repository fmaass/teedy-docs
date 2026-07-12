<script setup lang="ts">
import { computed } from 'vue'
import Editor from 'primevue/editor'
import 'quill/dist/quill.snow.css'

// The rich description editor: a PrimeVue Editor (Quill 2) restricted to the exact format
// set the server-side DescriptionSanitizer allows. The restriction is enforced on THREE
// axes, because a toolbar restriction alone does not stop pasted markup:
//   1. a custom toolbar (the #toolbar slot below) exposes only the allowed controls;
//   2. the `formats` prop constrains Quill's content model, so any format outside the
//      list — including one arrived-at via paste — is dropped from the document;
//   3. the server sanitizer is the authoritative backstop (defense in depth).
// Keep ALLOWED_FORMATS in lockstep with DescriptionSanitizer's allowlist and the
// checked-in Quill fixtures (docs-core/src/test/resources/description-fixtures).
const ALLOWED_FORMATS = [
  'header',
  'bold',
  'italic',
  'underline',
  'strike',
  'list',
  'link',
  'blockquote',
  'code-block',
]

const props = defineProps<{
  modelValue: string
  id?: string
  ariaLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const value = computed({
  get: () => props.modelValue,
  set: (v: string) => emit('update:modelValue', v ?? ''),
})
</script>

<template>
  <div :id="id" class="rich-description-editor-wrap">
  <Editor
    v-model="value"
    :formats="ALLOWED_FORMATS"
    :aria-label="ariaLabel"
    editorStyle="min-height: 8rem"
    class="rich-description-editor"
  >
    <template #toolbar>
      <span class="ql-formats">
        <select class="ql-header" :aria-label="ariaLabel">
          <option value="1"></option>
          <option value="2"></option>
          <option value="3"></option>
          <option selected></option>
        </select>
      </span>
      <span class="ql-formats">
        <button class="ql-bold" type="button"></button>
        <button class="ql-italic" type="button"></button>
        <button class="ql-underline" type="button"></button>
        <button class="ql-strike" type="button"></button>
      </span>
      <span class="ql-formats">
        <button class="ql-list" value="ordered" type="button"></button>
        <button class="ql-list" value="bullet" type="button"></button>
      </span>
      <span class="ql-formats">
        <button class="ql-blockquote" type="button"></button>
        <button class="ql-code-block" type="button"></button>
        <button class="ql-link" type="button"></button>
      </span>
      <span class="ql-formats">
        <button class="ql-clean" type="button"></button>
      </span>
    </template>
  </Editor>
  </div>
</template>

<style scoped>
.rich-description-editor :deep(.ql-editor) {
  font-size: 0.95rem;
  color: var(--p-text-color);
}

/* The Quill "snow" theme hardcodes light-theme colours (#444 icons, #fff
   popovers). Map the toolbar and its overlays onto the app's semantic tokens
   so the editor stays legible in dark mode. */
:global(.dark-mode) .rich-description-editor :deep(.ql-toolbar.ql-snow),
:global(.dark-mode) .rich-description-editor :deep(.ql-container.ql-snow) {
  border-color: var(--p-content-border-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-stroke) {
  stroke: var(--p-text-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-fill) {
  fill: var(--p-text-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-picker) {
  color: var(--p-text-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-picker-options) {
  background-color: var(--p-content-background);
  border-color: var(--p-content-border-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow.ql-toolbar button:hover .ql-stroke),
:global(.dark-mode) .rich-description-editor :deep(.ql-snow.ql-toolbar button.ql-active .ql-stroke) {
  stroke: var(--p-primary-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow.ql-toolbar button:hover .ql-fill),
:global(.dark-mode) .rich-description-editor :deep(.ql-snow.ql-toolbar button.ql-active .ql-fill) {
  fill: var(--p-primary-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-tooltip) {
  background-color: var(--p-content-background);
  border-color: var(--p-content-border-color);
  color: var(--p-text-color);
  box-shadow: none;
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-tooltip input[type='text']) {
  background-color: var(--p-content-background);
  border-color: var(--p-content-border-color);
  color: var(--p-text-color);
}
:global(.dark-mode) .rich-description-editor :deep(.ql-snow .ql-editor blockquote) {
  border-left-color: var(--p-content-border-color);
}
</style>
