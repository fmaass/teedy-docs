import { watch, unref, type Ref, type MaybeRefOrGetter } from 'vue'
import { clampOffset } from '../utils/pagination'

function resolvePageSize(pageSize: MaybeRefOrGetter<number>): number {
  return typeof pageSize === 'function' ? pageSize() : unref(pageSize)
}

/**
 * Shape a paginated query result must expose for the stale-offset clamp: the
 * rows returned for the current offset and the total across all pages.
 */
export interface ClampablePage {
  documents: unknown[]
  total: number
}

/**
 * Re-clamp a paginator offset whenever a query refetch leaves the view stranded
 * on a now-empty page.
 *
 * Bulk-deleting (or emptying) the last item of a page > 1 refetches with a
 * now-stale offset and the server returns zero rows while total is still
 * positive — a false-empty page with no paginator to escape. This watch clamps
 * the offset back to the last valid page when that happens. It is gated on
 * `isLoading` so the placeholder/keep-previous phase (rows still present) and the
 * genuine-empty case (total 0) both stay untouched. Only a genuinely-stranded
 * offset moves (see clampOffset), so a mid-page delete is a no-op.
 *
 * @param data        The `useQuery` data ref (`{ documents, total }`, may be undefined).
 * @param isLoading   The `useQuery` isLoading ref — clamp is skipped while true.
 * @param pageOffset  The paginator offset ref; mutated in place when it must change.
 * @param pageSize    Items per page (> 0) — a plain number, ref, or getter so the
 *                    clamp tracks a user-selectable page size (#52).
 */
export function useClampedOffset(
  data: Ref<ClampablePage | undefined>,
  isLoading: Ref<boolean>,
  pageOffset: Ref<number>,
  pageSize: MaybeRefOrGetter<number>,
): void {
  watch(data, () => {
    if (isLoading.value) return
    const visibleCount = data.value?.documents.length ?? 0
    const totalCount = data.value?.total ?? 0
    const next = clampOffset(pageOffset.value, visibleCount, totalCount, resolvePageSize(pageSize))
    if (next !== pageOffset.value) pageOffset.value = next
  })
}
