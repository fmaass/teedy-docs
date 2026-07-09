// Trash retention countdown helpers (D4 / R-043).
//
// The backend purges trashed documents after DOCS_TRASH_RETENTION_DAYS (default 30,
// see TrashPurgeService.DEFAULT_RETENTION_DAYS). /api/app now surfaces the configured
// window as `trash_retention_days`, which DocumentTrash.vue passes into the countdown.
//
// Contract for the `retentionDays` argument:
//   - ABSENT (undefined): older server that omits the field -> fall back to
//     DEFAULT_RETENTION_DAYS and count down normally.
//   - <= 0 (zero or negative): auto-purge is DISABLED (matches
//     TrashPurgeService.purgeExpiredTrash, which returns immediately for <= 0).
//     No purge will ever happen, so daysUntilPurge returns null — callers must
//     render "auto-purge off", NOT a "due for purge" countdown.
//   - > 0: normal retention window; count down as usual.
// DEFAULT_RETENTION_DAYS is only the ABSENT fallback; keep it in sync with the
// backend default. A present 0/negative value means disabled, never the default.
export const DEFAULT_RETENTION_DAYS = 30

const MS_PER_DAY = 24 * 60 * 60 * 1000

/**
 * Whole days remaining until a trashed document is automatically purged, or null
 * when auto-purge is disabled.
 *
 * Pure function: given the document's delete date (epoch ms), the retention window
 * in days, and the current instant, returns the number of days until purge, clamped
 * to a minimum of 0 (an item at or past its purge instant reports 0 — "due").
 * Returns null when retentionDays <= 0 (auto-purge disabled; no purge will occur).
 *
 * @param deleteDate  Epoch milliseconds when the document was trashed.
 * @param retentionDays  Retention window in days; <= 0 means auto-purge disabled.
 * @param now  Current instant in epoch milliseconds (injectable for testability).
 */
export function daysUntilPurge(
  deleteDate: number,
  retentionDays: number = DEFAULT_RETENTION_DAYS,
  now: number = Date.now(),
): number | null {
  if (retentionDays <= 0) return null
  const purgeAt = deleteDate + retentionDays * MS_PER_DAY
  const remainingMs = purgeAt - now
  if (remainingMs <= 0) return 0
  return Math.ceil(remainingMs / MS_PER_DAY)
}
