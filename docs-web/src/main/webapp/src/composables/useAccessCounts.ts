import { computed, type Ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { getDocumentAccessCounts, type DocumentAccessCounts } from '../api/access'
import { queryKeys } from '../api/queryKeys'

/**
 * The calling user's own access counts for one document and its files (#300).
 *
 * Both consumers — the document header (the document's own count) and the file panel (one count
 * per file) — go through this ONE key, so opening a document costs a single extra request no
 * matter how many files it has.
 *
 * `enabled` is what keeps the number honest. Opening the document IS the access: the server records
 * it while serving `GET /document/:id`, so a counts request fired in parallel with that one can
 * observe the state from BEFORE the open and render N-1. Gating on the document query having
 * resolved orders the two, so the number on screen always includes the open the user just made.
 *
 * @param documentId The document whose counts to read
 * @param enabled Gate — pass "the document query has resolved"
 */
export function useAccessCounts(documentId: Ref<string>, enabled: Ref<boolean>) {
  return useQuery<DocumentAccessCounts>({
    queryKey: computed(() => queryKeys.accessCounts(documentId.value)),
    queryFn: () => getDocumentAccessCounts(documentId.value).then((r) => r.data),
    enabled,
  })
}

/**
 * Index a counts payload by file id, so a file row can read its own number in O(1).
 *
 * @param counts The payload, or undefined while it is still loading
 * @return File id to count; empty while loading
 */
export function fileAccessCountMap(counts: DocumentAccessCounts | undefined): Record<string, number> {
  const map: Record<string, number> = {}
  for (const file of counts?.files ?? []) {
    map[file.id] = file.count
  }
  return map
}
