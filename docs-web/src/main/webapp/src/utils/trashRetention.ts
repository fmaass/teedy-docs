// Trash retention countdown helpers (D4 / R-043).
//
// The backend purges trashed documents after DOCS_TRASH_RETENTION_DAYS (default 30,
// see TrashPurgeService.DEFAULT_RETENTION_DAYS). That value is NOT exposed by any
// REST endpoint (/api/app does not carry it), so the frontend cannot read the real
// configured window. We display a countdown against this default; if an operator
// overrides DOCS_TRASH_RETENTION_DAYS server-side, the displayed count is an estimate.
// Keep DEFAULT_RETENTION_DAYS in sync with the backend default.
export const DEFAULT_RETENTION_DAYS = 30

const MS_PER_DAY = 24 * 60 * 60 * 1000

/**
 * Whole days remaining until a trashed document is automatically purged.
 *
 * Pure function: given the document's delete date (epoch ms), the retention window
 * in days, and the current instant, returns the number of days until purge, clamped
 * to a minimum of 0 (an item at or past its purge instant reports 0 — "due").
 *
 * @param deleteDate  Epoch milliseconds when the document was trashed.
 * @param retentionDays  Retention window in days.
 * @param now  Current instant in epoch milliseconds (injectable for testability).
 */
export function daysUntilPurge(
  deleteDate: number,
  retentionDays: number = DEFAULT_RETENTION_DAYS,
  now: number = Date.now(),
): number {
  const purgeAt = deleteDate + retentionDays * MS_PER_DAY
  const remainingMs = purgeAt - now
  if (remainingMs <= 0) return 0
  return Math.ceil(remainingMs / MS_PER_DAY)
}
