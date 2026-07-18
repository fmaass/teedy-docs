<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import type { ConflictAction } from '../utils/fileConflicts'

// Presentational name-conflict prompt shown when a manual upload-bar drop collides with
// an existing file of the current document (#117.2). The orchestration (which files
// conflict, apply-to-all bookkeeping, the actual uploads) lives in DocumentViewContent;
// this component only presents ONE conflict and emits the user's choice. `remaining` is
// the number of conflicts still to decide (including this one) so the apply-to-all
// affordance appears only for a genuine multi-conflict batch.

defineProps<{
  fileName: string
  remaining: number
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  decide: [decision: { action: ConflictAction; applyToAll: boolean }]
}>()

const { t } = useI18n()

// Apply-to-all is a fresh, opt-in choice for each batch — reset it whenever the dialog
// re-opens for a new conflict so a stale tick can't silently carry over.
const applyToAll = ref(false)
watch(visible, (open) => {
  if (open) applyToAll.value = false
})

function decide(action: ConflictAction) {
  emit('decide', { action, applyToAll: applyToAll.value })
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :closable="false"
    :close-on-escape="false"
    :header="t('ui.conflict.title')"
    :style="{ width: '34rem' }"
    :breakpoints="{ '640px': '95vw' }"
  >
    <p class="conflict-message">{{ t('ui.conflict.message', { name: fileName }) }}</p>

    <div v-if="remaining > 1" class="conflict-apply-all">
      <Checkbox v-model="applyToAll" :binary="true" inputId="conflict-apply-all" />
      <label for="conflict-apply-all">{{ t('ui.conflict.apply_to_all', { count: remaining }) }}</label>
    </div>

    <template #footer>
      <Button :label="t('cancel')" text severity="secondary" @click="decide('cancel')" />
      <Button :label="t('ui.conflict.keep_both')" text @click="decide('keep-both')" />
      <Button :label="t('ui.conflict.new_version')" autofocus @click="decide('version')" />
    </template>
  </Dialog>
</template>

<style scoped>
.conflict-message {
  margin: 0;
  line-height: 1.5;
  color: var(--p-text-color);
}
.conflict-apply-all {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
  font-size: 0.875rem;
}
</style>
