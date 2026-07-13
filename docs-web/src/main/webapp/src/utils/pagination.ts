// Pagination offset clamping (P4).
//
// Deleting (or bulk-deleting) the last item on a page > 1 makes the server return
// zero rows for the now-stale offset, even though totalCount is still positive.
// The paginator then vanishes (no rows to render) leaving the user stranded on a
// false-empty page while the header still shows the real count.
//
// clampOffset computes where the offset SHOULD be after such a refetch: the offset
// of the last page that still holds at least one item. Views call it when a
// non-loading refetch returns zero items while totalCount > 0 and offset > 0, and
// only move the offset when it actually changes (so mid-page deletes are a no-op).

// User-selectable page sizes for the document list (#52). The default matches the
// historical fixed PAGE_SIZE (20) so existing behaviour is preserved when no choice
// has been persisted.
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const
export const DEFAULT_PAGE_SIZE = 20

/**
 * Clamp an arbitrary (possibly persisted or user-supplied) page size to the nearest
 * allowed option. A value below the smallest option maps to the smallest; above the
 * largest maps to the largest; an exact allowed value passes through; anything
 * in-between (or non-finite) resolves to the default. Pure function.
 *
 * @param value  The candidate page size (may be NaN, negative, or out of range).
 */
export function clampPageSize(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_PAGE_SIZE
  const options = PAGE_SIZE_OPTIONS
  if (value <= options[0]) return options[0]
  if (value >= options[options.length - 1]) return options[options.length - 1]
  return (options as readonly number[]).includes(value) ? value : DEFAULT_PAGE_SIZE
}

/**
 * Last valid page offset for a given total count and page size.
 *
 * Pure function. Returns the offset (a multiple of pageSize) of the last page that
 * contains at least one item. With totalCount 0 or pageSize <= 0 it returns 0.
 *
 * @param totalCount  Total number of items across all pages (post-deletion count).
 * @param pageSize    Items per page (> 0).
 */
export function lastPageOffset(totalCount: number, pageSize: number): number {
  if (totalCount <= 0 || pageSize <= 0) return 0
  return Math.floor((totalCount - 1) / pageSize) * pageSize
}

/**
 * Clamp a paginator offset after a refetch that returned `visibleCount` items.
 *
 * Returns the offset the view should adopt. When the current offset still has items
 * (visibleCount > 0), or there is nothing to page (totalCount 0), or we are already
 * on the first page (offset 0), the offset is returned unchanged. Only a
 * genuinely-stranded page (offset > 0, zero visible, but items still exist) is
 * clamped down to the last valid page offset.
 *
 * @param currentOffset  The offset that was just queried.
 * @param visibleCount   How many items the refetch returned for that offset.
 * @param totalCount     Total items reported by the same response.
 * @param pageSize       Items per page (> 0).
 */
export function clampOffset(
  currentOffset: number,
  visibleCount: number,
  totalCount: number,
  pageSize: number,
): number {
  if (visibleCount > 0) return currentOffset
  if (totalCount <= 0) return currentOffset
  if (currentOffset <= 0) return currentOffset
  return lastPageOffset(totalCount, pageSize)
}
