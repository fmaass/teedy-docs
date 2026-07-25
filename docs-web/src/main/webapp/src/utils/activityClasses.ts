// Entity-class vocabulary for the global history view (#177).
//
// LOG_CLASSENTITY_C is written by TWO kinds of caller, so the vocabulary here mirrors
// AuditLogResource.ALLOWED_CLASSES (the server rejects anything else with a 400) rather than the
// `Loggable` model alone:
//   1. AuditLogUtil.create -> `loggable.getClass().getSimpleName()`, i.e. every Loggable entity;
//   2. DIRECT writers that call setEntityClass("<literal>") themselves — DocumentResource
//      ("Export"), SecurityFilter ("User" AUTHENTICATION rows), PrincipalDeletionUtil ("Acl").
// Deriving this list from the Loggable model alone is a false oracle: it omitted `Export`, whose
// rows the history view displayed while the filter rejected them. Two backend tests enforce both
// halves (a model scan and a source scan for setEntityClass literals), so this list has an
// upstream source of truth rather than being invented here.
//
// `Acl` is deliberately ABSENT from the filter choices: every user- and admin-scoped audit query
// excludes Acl rows by construction (the authorization predicate), so offering it would advertise a
// filter that can only ever return an empty page in this view. It still gets a label, because a
// DOCUMENT-scoped table can legitimately show Acl rows.

// Static, quoted-literal key map: each i18n key appears verbatim so the i18n unused-key scan
// (scripts/check-i18n-parity.mjs) resolves it without a dynamic prefix exemption.
export const ACTIVITY_CLASS_LABEL_KEYS: Record<string, string> = {
  Acl: 'ui.history.class_acl',
  Comment: 'ui.history.class_comment',
  Document: 'ui.history.class_document',
  Export: 'ui.history.class_export',
  File: 'ui.history.class_file',
  Group: 'ui.history.class_group',
  Metadata: 'ui.history.class_metadata',
  Route: 'ui.history.class_route',
  RouteModel: 'ui.history.class_route_model',
  Tag: 'ui.history.class_tag',
  User: 'ui.history.class_user',
  Webhook: 'ui.history.class_webhook',
}

// The values the class filter offers, in display order. Acl excluded — see above.
export const FILTERABLE_ACTIVITY_CLASSES = [
  'Document',
  'File',
  'Comment',
  'Tag',
  'User',
  'Group',
  'Metadata',
  'Route',
  'RouteModel',
  'Webhook',
  // Not a Loggable entity — DocumentResource writes these rows directly for a data export.
  'Export',
]

// The values the type filter offers: the full com.sismics.docs.core.constant.AuditLogType enum.
// Unlike the document view's client-side filter (whose options are the types OBSERVED in the
// loaded rows), a server-side filter must offer every value the server accepts — the whole point
// is to reach rows that are not on the current page.
export const FILTERABLE_ACTIVITY_TYPES = ['CREATE', 'UPDATE', 'DELETE', 'AUTHENTICATION']

// Localized label for an audit row's entity class. An unknown/unmapped class falls back to its
// raw name so a future backend loggable can never render blank.
export function activityClassLabel(entityClass: string, t: (key: string) => string): string {
  const key = ACTIVITY_CLASS_LABEL_KEYS[entityClass]
  return key ? t(key) : entityClass
}
