<script setup lang="ts">
/**
 * The tag-reduction run over the current document-list selection (#293).
 *
 * Two passes over one endpoint, and the order is the safety property the reporter asked for
 * ("some preview/dry-run would be good, to not destroy"): mounting PREVIEWS — a dry run that
 * modifies nothing — and only the confirm button below runs it for real. Both passes send document
 * IDs alone; the server derives what is redundant each time, so what this screen previews is never
 * replayed as a removal list, and the report shown afterwards is what actually went.
 *
 * The component is mounted on demand (the list renders it under a `v-if`), so the preview runs
 * once per opening with no watcher, and the document list carries no dialog DOM at all while
 * nothing is selected.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import ProgressSpinner from 'primevue/progressspinner'
import { reduceDocumentTags, type TagReductionReport } from '../api/tag'

const { t } = useI18n()

const props = defineProps<{
  /** The current selection: the run's scope, and the only source of document titles. */
  documents: { id: string; title: string }[]
}>()

const emit = defineEmits<{
  close: []
  /** Emitted after a real run, so the list can refetch what changed. */
  reduced: [report: TagReductionReport]
}>()

const visible = ref(true)
const loading = ref(true)
const running = ref(false)
const failed = ref(false)
/** The dry run's answer while previewing; replaced by the real run's answer afterwards. */
const report = ref<TagReductionReport | null>(null)
/** True once a real run has come back — the dialog then reports instead of offering. */
const done = ref(false)

/**
 * The document's own title. The server answers with IDs only: it never sends titles back, so this
 * endpoint cannot be turned into a title oracle for documents the caller cannot read. The titles
 * come from the selection the dialog was opened with, which is where they already are.
 */
function titleOf(documentId: string): string {
  return props.documents.find((document) => document.id === documentId)?.title ?? documentId
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const { data } = await reduceDocumentTags(props.documents.map((document) => document.id), true)
    report.value = data
  } catch {
    failed.value = true
    report.value = null
  } finally {
    loading.value = false
  }
}

async function run() {
  running.value = true
  failed.value = false
  try {
    const { data } = await reduceDocumentTags(props.documents.map((document) => document.id), false)
    report.value = data
    done.value = true
    emit('reduced', data)
  } catch {
    failed.value = true
  } finally {
    running.value = false
  }
}

function close() {
  visible.value = false
  emit('close')
}

onMounted(load)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('ui.tag_reduction.header')"
    class="tag-reduction-dialog"
    :style="{ width: '34rem', maxWidth: '95vw' }"
    @update:visible="(value: boolean) => { if (!value) emit('close') }"
  >
    <div v-if="loading" class="reduction-loading">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
      <span>{{ t('ui.tag_reduction.loading') }}</span>
    </div>

    <template v-else>
      <p v-if="failed" class="reduction-error">{{ t('ui.tag_reduction.failed') }}</p>

      <!-- The rule, spelled out before anything is confirmed: it is transitive, and that is
           exactly the part nobody can infer from the button's label. -->
      <p v-if="!done" class="reduction-intro">{{ t('ui.tag_reduction.intro') }}</p>

      <template v-if="report">
        <p v-if="done" class="reduction-result">
          {{
            report.count
              ? t('ui.tag_reduction.result', {
                  tags: report.count,
                  documents: report.documents.length,
                })
              : t('ui.tag_reduction.result_none')
          }}
        </p>
        <p v-else-if="report.count" class="reduction-summary">
          {{ t('ui.tag_reduction.summary', { tags: report.count, documents: report.documents.length }) }}
        </p>
        <p v-else class="reduction-none">{{ t('ui.tag_reduction.none') }}</p>

        <ul v-if="report.documents.length" class="reduction-list">
          <li v-for="document in report.documents" :key="document.id" class="reduction-doc">
            <span class="reduction-doc-title">{{ titleOf(document.id) }}</span>
            <span class="reduction-doc-tags">
              <span v-for="tag in document.tags" :key="tag.id" class="reduction-tag">{{ tag.path }}</span>
            </span>
          </li>
        </ul>

        <p v-if="report.skipped.length" class="reduction-skipped">
          {{ t('ui.tag_reduction.skipped', { count: report.skipped.length }) }}
        </p>
      </template>
    </template>

    <template #footer>
      <Button
        class="reduction-close-btn"
        :label="done ? t('close') : t('cancel')"
        severity="secondary"
        text
        @click="close"
      />
      <Button
        v-if="!done"
        class="reduction-confirm-btn"
        :label="t('ui.tag_reduction.confirm', { count: report?.count ?? 0 })"
        severity="danger"
        :disabled="loading || running || !report?.count"
        :loading="running"
        @click="run"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.reduction-loading {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.reduction-intro,
.reduction-summary,
.reduction-result,
.reduction-none,
.reduction-skipped,
.reduction-error {
  margin: 0 0 0.75rem;
}

.reduction-intro,
.reduction-skipped {
  color: var(--p-text-muted-color);
  font-size: 0.875rem;
}

.reduction-error {
  color: var(--p-red-500);
}

.reduction-list {
  margin: 0 0 0.75rem;
  padding: 0;
  list-style: none;
  max-height: 20rem;
  overflow-y: auto;
}

.reduction-doc {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  padding: 0.375rem 0;
  border-bottom: 1px solid var(--p-content-border-color);
}

.reduction-doc-title {
  font-weight: 600;
  font-size: 0.875rem;
}

.reduction-doc-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.reduction-tag {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  background: var(--p-content-hover-background);
  border-radius: var(--p-content-border-radius);
  padding: 0.0625rem 0.375rem;
}
</style>
