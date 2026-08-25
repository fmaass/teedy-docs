import api from './client'

// Access counters over AccessResource (@Path("/access")), issue #300.
//
// The server records an ACCESS EVENT whenever it serves a document or a file to an identified
// user; the counters below are aggregations over those events, never a stored tally. Nothing in
// this module records anything — the SPA must not be able to declare "count this", or the same
// event stream could not later serve as an access history.
//
// Two visibilities, enforced server-side:
//   * getDocumentAccessCounts  — the CALLING user's own numbers. There is no user parameter, so
//     no call from this client can ask for anybody else's.
//   * getAccessStats           — administrator-only aggregate: global totals and the most-used
//     documents with their per-user breakdown. A non-admin gets a 403 from the server; hiding
//     the screen is never the defence.

/** One file's access count for the calling user. */
export interface FileAccessCount {
  id: string
  count: number
}

export interface DocumentAccessCounts {
  /** How many times the CALLING user opened this document. */
  count: number
  /** The calling user's own count per file of the document; every file is present, zero included. */
  files: FileAccessCount[]
}

/** One user's share of a document's accesses (administrator view only). */
export interface AccessUserCount {
  username: string
  count: number
}

/** One row of the administrator's most-accessed-documents ranking. */
export interface DocumentAccessStats {
  id: string
  title: string
  /** Accesses by every user. */
  total: number
  users: AccessUserCount[]
}

export interface AccessStats {
  total_document_accesses: number
  total_file_accesses: number
  /** Most-accessed documents, restricted to those the calling administrator may read. */
  documents: DocumentAccessStats[]
}

/** The calling user's own access counts for a document and each of its files. */
export function getDocumentAccessCounts(documentId: string) {
  return api.get<DocumentAccessCounts>(`/access/document/${documentId}`)
}

/** Aggregate access statistics. Administrator only — the server refuses anyone else with a 403. */
export function getAccessStats(limit?: number) {
  return api.get<AccessStats>('/access/stats', { params: limit === undefined ? undefined : { limit } })
}
