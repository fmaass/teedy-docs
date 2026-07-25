// Audit-row -> route resolution for the activity views (#177).
//
// An audit row carries only `class` (the loggable's simple class name), `target`
// (LOG_IDENTITY_C — the entity's own id) and `message` (whatever that entity's
// `toMessage()` returned). Neither field says where the entity LIVES in the SPA, so the
// mapping below is per-class and grounded in two sources:
//
//   1. the entity's `toMessage()` in docs-core (what `message` actually holds), and
//   2. the AngularJS `directive.auditlog.html` this view is a port of (recovered at commit
//      6a69d0e3), which is the only prior art for which field addresses which target.
//
// Two rules from that prior art are load-bearing and non-obvious:
//   - Comment and Route put the DOCUMENT id in `message` (not in `target`), so they link
//     through `message`; Document and File put their own id in `target`.
//   - File's message is a fixed 36-char document-id PREFIX followed by the file name, and the
//     prefix is 36 SPACES when the file has no document. Splitting at 36 is therefore the only
//     way to recover either half.
//
// A class with no user-reachable destination resolves to null and renders as plain text —
// inventing a target would be worse than not linking. In particular EVERY `User` row is
// unlinkable: `SecurityFilter` writes AUTHENTICATION rows with entityId = the internal user id,
// there is no per-user route in the SPA, and the row's message is a username, not an id.

import type { RouteLocationRaw } from 'vue-router'

export interface ActivityLinkRow {
  class: string
  target: string
  message: string | null
}

// Length of the document-id prefix File.toMessage() prepends (a UUID, or 36 spaces when the
// file is not attached to a document). docs-core File.java:344-347.
const FILE_DOC_ID_LENGTH = 36

// Routes that carry `meta.requiresAdmin`. Linking a non-admin at one only bounces them to the
// document list, so they are offered to admins only and render as plain text otherwise.
const ADMIN_ROUTES: Record<string, string> = {
  Group: 'settings-groups',
  Metadata: 'settings-metadata',
  RouteModel: 'settings-workflow',
  Webhook: 'settings-webhooks',
}

function documentRoute(id: string | null | undefined, name: string): RouteLocationRaw | null {
  const trimmed = (id ?? '').trim()
  return trimmed ? { name, params: { id: trimmed } } : null
}

// The document id a File row points at, or null when the file has no document (the prefix is
// blank) or the message is missing entirely.
export function fileDocumentId(message: string | null): string | null {
  if (!message) return null
  const prefix = message.slice(0, FILE_DOC_ID_LENGTH).trim()
  return prefix.length > 0 ? prefix : null
}

// The file NAME half of a File row's message, or null when the message carries only the
// document-id prefix (a 36-char message = prefix and nothing else).
export function fileName(message: string | null): string | null {
  if (!message || message.length <= FILE_DOC_ID_LENGTH) return null
  const name = message.slice(FILE_DOC_ID_LENGTH)
  return name.length > 0 ? name : null
}

/**
 * Where this audit row's entity can be opened, or null when it has no user-reachable target.
 */
export function resolveActivityLink(
  row: ActivityLinkRow,
  options: { isAdmin?: boolean } = {},
): RouteLocationRaw | null {
  switch (row.class) {
    // The document's own id is in `target`.
    case 'Document':
      return documentRoute(row.target, 'document-view')
    // The parent document's id is the message's 36-char prefix. The SPA has no per-file route
    // (unlike the AngularJS app), so the deepest reachable target is the document's Files tab.
    case 'File':
      return documentRoute(fileDocumentId(row.message), 'document-view-content')
    // Comment.toMessage() and Route.toMessage() both return the DOCUMENT id.
    case 'Comment':
    case 'Route':
      return documentRoute(row.message, 'document-view')
    // Tags have no per-tag view; the tag list is the reachable destination.
    case 'Tag':
      return { name: 'tags' }
    // Never linkable: no per-user route exists, and AUTHENTICATION rows point at an internal id.
    case 'User':
    // Acl rows are excluded from every user/admin-scoped query; a document-scoped one has no
    // per-ACL destination either.
    case 'Acl':
      return null
    default: {
      const route = ADMIN_ROUTES[row.class]
      return route && options.isAdmin ? { name: route } : null
    }
  }
}

/**
 * The text to show for an audit row's target. Falls back through the row's own fields so a
 * legacy row with a null message never renders blank; `openLabel` is the caller's localized
 * "Open" string, used when the message carries an id rather than anything human-readable.
 */
export function activityTargetLabel(row: ActivityLinkRow, openLabel: string): string {
  switch (row.class) {
    // The message IS a document id — showing it would be noise.
    case 'Comment':
    case 'Route':
      return openLabel
    case 'File':
      return fileName(row.message) ?? openLabel
    default:
      return row.message || '—'
  }
}
