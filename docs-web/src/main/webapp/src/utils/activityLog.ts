// Activity-log (audit) display helpers for the document Activity tab (#113).
//
// The server returns audit rows whose `type` is a docs-core AuditLogType constant
// (com.sismics.docs.core.constant.AuditLogType): CREATE / UPDATE / DELETE /
// AUTHENTICATION. The UI renders a localized label per row and offers a client-side
// event-type filter. These helpers are pure so the label mapping and the stale-
// selection reconciliation can be unit-tested without mounting the view.

// Static, quoted-literal key map: each i18n key appears verbatim so the i18n
// unused-key scan (scripts/check-i18n-parity.mjs) resolves it without a dynamic
// prefix exemption. AUTHENTICATION is kept for completeness even though a
// document-scoped auditlog query never returns it.
export const ACTIVITY_TYPE_LABEL_KEYS: Record<string, string> = {
  CREATE: 'ui.activity.type_create',
  UPDATE: 'ui.activity.type_update',
  DELETE: 'ui.activity.type_delete',
  AUTHENTICATION: 'ui.activity.type_authentication',
}

// Localized label for an audit row's type. Known types resolve through the catalog;
// an unknown/unmapped type value falls back to its raw string so a future backend
// type can never render blank.
export function activityTypeLabel(type: string, t: (key: string) => string): string {
  const key = ACTIVITY_TYPE_LABEL_KEYS[type]
  return key ? t(key) : type
}

// Distinct type values present in the loaded rows, in first-seen order. This is the
// source of the filter's option set — the options are what was OBSERVED, never a
// fixed enum, so a type that never occurred for this document is never offered.
export function observedTypes(rows: { type: string }[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const row of rows) {
    const type = row?.type
    if (type && !seen.has(type)) {
      seen.add(type)
      out.push(type)
    }
  }
  return out
}

// Reconcile a filter selection against the currently observed type set. A selection
// survives only while its type is still present; when the loaded rows change
// (document switch / refetch) and no longer contain it, the selection is cleared so
// the table can never render false-empty behind a stale filter.
export function reconcileSelection(selected: string | null, observed: string[]): string | null {
  if (selected == null) return null
  return observed.includes(selected) ? selected : null
}

// Append a newly-fetched older page to the accumulated audit rows (#139 "load older").
// Rows already present by `id` are dropped — belt-and-suspenders against a boundary row
// that two overlapping keyset pages could return twice, so the client stays correct even
// if the server's cursor is imperfect. Order is preserved: existing rows first (newest →
// oldest), then the not-yet-seen incoming rows in arrival order. Pure, so the append/dedupe
// contract is unit-tested without mounting the view.
export function mergeAuditRows<T extends { id: string }>(existing: T[], incoming: T[]): T[] {
  const seen = new Set(existing.map((row) => row.id))
  const merged = existing.slice()
  for (const row of incoming) {
    if (row && row.id && !seen.has(row.id)) {
      seen.add(row.id)
      merged.push(row)
    }
  }
  return merged
}
