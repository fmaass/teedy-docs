/**
 * A latest-version active file already attached to the current document — the only
 * kind of row a manual upload can collide with by name. `id` is that file's id, used
 * as the version base (previousFileId) when the user chooses "add as new version".
 */
export interface ExistingFile {
  id: string
  name: string
}

/** A dropped file whose name collides with an existing document file. */
export interface FileConflict {
  file: File
  existing: ExistingFile
}

export interface ConflictPartition {
  conflicts: FileConflict[]
  fresh: File[]
}

/**
 * The user's choice for a single name conflict: replace as a new version, keep both
 * as separate files, or cancel this file's upload.
 */
export type ConflictAction = 'version' | 'keep-both' | 'cancel'

/**
 * Split just-dropped upload-bar files by whether their name collides
 * (case-insensitively) with an existing active file of THIS document. `existing`
 * is the document's latest-version active files (doc.files); cross-document files
 * are never considered (Decision: match this document only). A collision is a
 * conflict carrying the existing file's id so the caller can offer "add as new
 * version" (previousFileId = existing.id); everything else is fresh and uploads
 * unchanged. Drop order is preserved within each bucket.
 */
export function partitionByNameConflict(
  dropped: File[],
  existing: ExistingFile[],
): ConflictPartition {
  // First existing file wins for a duplicated name — a document rarely holds two
  // active files with the same name, but if it does the earliest-listed row is the
  // deterministic version base.
  const byLowerName = new Map<string, ExistingFile>()
  for (const f of existing) {
    const key = f.name.toLowerCase()
    if (!byLowerName.has(key)) byLowerName.set(key, f)
  }

  const conflicts: FileConflict[] = []
  const fresh: File[] = []
  for (const file of dropped) {
    const match = byLowerName.get(file.name.toLowerCase())
    if (match) conflicts.push({ file, existing: match })
    else fresh.push(file)
  }
  return { conflicts, fresh }
}
