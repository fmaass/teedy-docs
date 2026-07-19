import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from 'primevue/usetoast'
import { useQueryClient } from '@tanstack/vue-query'
import { uploadFile } from '../api/file'
import { injectDocument } from '../views/document/documentKey'

/**
 * Shared "upload a new version of a file" wiring, used by BOTH the per-file action
 * (FileExtraActions) and the versions dialog (FileVersionsDialog) so the two surfaces
 * post identically. It owns a hidden <input type="file"> (bind the returned `input`
 * ref) triggered by `pick()`; on selection it posts through the version pipeline with
 * `previousFileId` = the file being replaced, so the upload becomes v(n+1) of the same
 * chain. A stale-base 409 (the chain moved under it) surfaces the reload path; success
 * invalidates the document query and runs the optional `onUploaded` hook (e.g. reload
 * the versions list). The document id comes from the DocumentView provide scope both
 * callers always render inside.
 */
export function useVersionUpload(onUploaded?: () => void) {
  const { t } = useI18n()
  const toast = useToast()
  const queryClient = useQueryClient()
  const doc = injectDocument()

  const input = ref<HTMLInputElement | null>(null)
  const uploading = ref(false)

  function pick() {
    input.value?.click()
  }

  async function onPicked(event: Event, previousFileId: string) {
    const el = event.target as HTMLInputElement
    const chosen = el.files?.[0]
    // Clear the input so re-picking the SAME file still fires change next time.
    el.value = ''
    if (!chosen || !doc.value) return
    const documentId = doc.value.id
    uploading.value = true
    try {
      await uploadFile(documentId, chosen, undefined, previousFileId)
      await queryClient.invalidateQueries({ queryKey: ['document', documentId] })
      toast.add({ severity: 'success', summary: t('ui.versions.uploaded_new'), life: 2000 })
      onUploaded?.()
    } catch (e) {
      // 409 = the base was already replaced (lost the version-chain compare-and-swap);
      // the view is stale, so tell the user to reload rather than silently retry.
      const status = (e as { response?: { status?: number } })?.response?.status
      toast.add({
        severity: 'error',
        summary: status === 409 ? t('ui.versions.stale_base') : t('ui.versions.upload_new_failed'),
        life: 4000,
      })
    } finally {
      uploading.value = false
    }
  }

  return { input, uploading, pick, onPicked }
}
