import { describe, it, expect } from 'vitest'
import {
  activityTargetLabel,
  fileDocumentId,
  fileName,
  resolveActivityLink,
} from './activityLink'

// The link resolver is the part of the global history view (#177) that can silently point a user
// at the wrong entity, so every class the backend can emit gets a case here — including the ones
// that must NOT resolve.

const DOC_ID = '11111111-2222-4333-8444-555555555555'
const FILE_ID = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BLANK_PREFIX = ' '.repeat(36)

describe('resolveActivityLink', () => {
  it('sends a Document row to the document view via its own target id', () => {
    expect(resolveActivityLink({ class: 'Document', target: DOC_ID, message: 'My title' })).toEqual({
      name: 'document-view',
      params: { id: DOC_ID },
    })
  })

  it('sends a File row to its PARENT document, read from the 36-char message prefix', () => {
    // File.toMessage() = <36-char document id><file name>.
    const row = { class: 'File', target: FILE_ID, message: `${DOC_ID}invoice.pdf` }
    expect(resolveActivityLink(row)).toEqual({
      name: 'document-view-content',
      params: { id: DOC_ID },
    })
    // The name half is what the user sees — not the id prefix.
    expect(activityTargetLabel(row, 'Open')).toBe('invoice.pdf')
  })

  it('does not link a File whose document-id prefix is blank (36 spaces = no document)', () => {
    const row = { class: 'File', target: FILE_ID, message: `${BLANK_PREFIX}orphan.pdf` }
    expect(fileDocumentId(row.message)).toBeNull()
    expect(resolveActivityLink(row)).toBeNull()
    // The name is still shown, just not as a link.
    expect(activityTargetLabel(row, 'Open')).toBe('orphan.pdf')
  })

  it('falls back to the Open label for a File message that is only the id prefix', () => {
    const row = { class: 'File', target: FILE_ID, message: DOC_ID }
    expect(fileName(row.message)).toBeNull()
    expect(activityTargetLabel(row, 'Open')).toBe('Open')
    // The prefix is a real document id, so the link still resolves.
    expect(resolveActivityLink(row)).toEqual({ name: 'document-view-content', params: { id: DOC_ID } })
  })

  it('does not link a File row with no message at all', () => {
    expect(resolveActivityLink({ class: 'File', target: FILE_ID, message: null })).toBeNull()
  })

  it('sends Comment and Route rows to the document named in their MESSAGE, not their target', () => {
    for (const cls of ['Comment', 'Route']) {
      // target is the comment/route id; the document id lives in message.
      const row = { class: cls, target: 'not-a-document-id', message: DOC_ID }
      expect(resolveActivityLink(row), cls).toEqual({ name: 'document-view', params: { id: DOC_ID } })
      expect(activityTargetLabel(row, 'Open'), cls).toBe('Open')
    }
  })

  it('does not link a Comment/Route row whose message is missing', () => {
    expect(resolveActivityLink({ class: 'Comment', target: 'x', message: null })).toBeNull()
    expect(resolveActivityLink({ class: 'Route', target: 'x', message: '   ' })).toBeNull()
  })

  it('sends a Tag row to the tag list and shows the tag name', () => {
    const row = { class: 'Tag', target: 'tag-id', message: 'Invoices' }
    expect(resolveActivityLink(row)).toEqual({ name: 'tags' })
    expect(activityTargetLabel(row, 'Open')).toBe('Invoices')
  })

  it('NEVER links a User row, for an admin or anyone else', () => {
    // SecurityFilter writes AUTHENTICATION rows with entityId = the internal user id, and the SPA
    // has no per-user route — so neither field addresses anything.
    for (const type of ['User']) {
      expect(resolveActivityLink({ class: type, target: 'use-id', message: 'alice' })).toBeNull()
      expect(resolveActivityLink({ class: type, target: 'use-id', message: 'alice' }, { isAdmin: true })).toBeNull()
    }
  })

  it('never links an Acl row', () => {
    expect(resolveActivityLink({ class: 'Acl', target: 'acl-id', message: 'READ granted to bob' })).toBeNull()
    expect(resolveActivityLink({ class: 'Acl', target: 'acl-id', message: 'x' }, { isAdmin: true })).toBeNull()
  })

  it('offers admin-only destinations to admins only', () => {
    const cases: Record<string, string> = {
      Group: 'settings-groups',
      Metadata: 'settings-metadata',
      RouteModel: 'settings-workflow',
      Webhook: 'settings-webhooks',
    }
    for (const [cls, name] of Object.entries(cases)) {
      const row = { class: cls, target: 'id', message: 'thing' }
      // Those routes carry meta.requiresAdmin — a non-admin would only be bounced to /document.
      expect(resolveActivityLink(row), `${cls} non-admin`).toBeNull()
      expect(resolveActivityLink(row, { isAdmin: true }), `${cls} admin`).toEqual({ name })
    }
  })

  it('does not link an unknown future class', () => {
    expect(resolveActivityLink({ class: 'Something', target: 'x', message: 'y' }, { isAdmin: true })).toBeNull()
  })

  it('shows a placeholder rather than a blank cell for a null message', () => {
    expect(activityTargetLabel({ class: 'Document', target: DOC_ID, message: null }, 'Open')).toBe('—')
  })
})
