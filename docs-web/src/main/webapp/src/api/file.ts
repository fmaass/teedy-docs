import api from './client'

/**
 * One historical revision of a file, as returned by GET /file/:id/versions.
 * The backend returns every row sharing the file's version_id (or the single
 * current row when the file has no version_id). Note: unlike /file/list, this
 * endpoint does NOT include a size field.
 */
export interface FileVersion {
  id: string
  name: string | null
  version: number
  mimetype: string
  create_date: number
}

/**
 * Map an axios upload-progress event to an integer percentage 0..100.
 * When the total size is unknown (some browsers/streams omit it) the fraction
 * cannot be computed, so we report 0 and let the caller show an indeterminate
 * state instead of a misleading number.
 */
export function toPercent(loaded: number, total?: number): number {
  if (!total || total <= 0) return 0
  const pct = Math.round((loaded / total) * 100)
  if (pct < 0) return 0
  if (pct > 100) return 100
  return pct
}

/**
 * One entry of GET /file/list. The `processing` flag reflects the backend's
 * in-memory processing set (FileUtil.isProcessingFile) — true while OCR /
 * content extraction is still running for that file, false once it clears.
 * This is the real signal used to drive the processing indicator; it is fresh
 * on every list call because the backend recomputes it per request.
 */
export interface FileListItem {
  id: string
  processing: boolean
  name: string | null
  version: number
  mimetype: string
  document_id: string | null
  create_date: number
  size: number
  // Baked clockwise rotation (0/90/180/270); drives the ?v= cache-bust on web/thumb URLs.
  rotation?: number
}

/**
 * Upload a file to a document via PUT /api/file (multipart/form-data).
 * `onProgress` receives an integer 0..100 as the browser streams the body up,
 * so callers can render a real per-file progress bar. The percentage is derived
 * from axios's native onUploadProgress (loaded/total), not simulated.
 *
 * When `previousFileId` is the id of the document's current latest file, the
 * backend supersedes that file and this upload becomes v(n+1) of the same version
 * chain (FileResource.add). Omit it for a plain new-file upload. A stale base (the
 * file was already replaced) comes back as HTTP 409, which the caller surfaces as
 * "the file changed, reload".
 */
export function uploadFile(
  documentId: string,
  file: File,
  onProgress?: (percent: number) => void,
  previousFileId?: string,
) {
  const formData = new FormData()
  formData.append('id', documentId)
  formData.append('file', file)
  if (previousFileId) formData.append('previousFileId', previousFileId)
  return api.put('/file', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: onProgress
      ? (event) => onProgress(toPercent(event.loaded, event.total))
      : undefined,
  })
}

/**
 * Build the URL for a file's data. For the derived `web`/`thumb` rasters a `rotation` bakes into
 * the served bytes, so a `?v=<rotation>` cache-bust key is appended when a rotation is supplied —
 * the raster is cached long-lived (private, ~1yr), so every consumer must vary the URL by rotation
 * to pick up the freshly-oriented image after a rotate. The key is omitted for the original file
 * (`content`/no size — never rotated) and when rotation is 0/absent (keeps existing URLs stable).
 */
export function getFileUrl(
  fileId: string,
  size?: 'web' | 'thumb' | 'content',
  shareId?: string,
  rotation?: number,
) {
  const query = new URLSearchParams()
  if (size) query.set('size', size)
  if (shareId) query.set('share', shareId)
  if ((size === 'web' || size === 'thumb') && rotation) query.set('v', String(rotation))
  const suffix = query.toString()
  return `api/file/${fileId}/data${suffix ? `?${suffix}` : ''}`
}

/**
 * Persist an absolute clockwise rotation ({0,90,180,270}) for a file via
 * POST /api/file/:id/rotation (form-encoded). The backend regenerates the web/thumb rasters from
 * the ORIGINAL upright bytes, baking in the rotation; the original file and OCR content are
 * untouched. Idempotent — re-sending the same value never compounds.
 */
export function setRotation(fileId: string, degrees: number) {
  const params = new URLSearchParams()
  params.set('rotation', String(degrees))
  return api.post(`/file/${fileId}/rotation`, params)
}

/**
 * Persist a new file order for a document via POST /file/reorder (form-encoded).
 * `orderedIds` is the FULL list of the document's file IDs in the desired order;
 * the backend rewrites each file's `order` from its position in the list and
 * requires WRITE permission on the document (a read-only viewer is rejected).
 */
export function reorderFiles(documentId: string, orderedIds: string[]) {
  const params = new URLSearchParams()
  params.set('id', documentId)
  for (const id of orderedIds) params.append('order', id)
  return api.post('/file/reorder', params)
}

/**
 * One page in a page-operation manifest. `source` is the 0-based index of a page in the
 * CURRENT PDF; the array order is the new page order and omitting a source deletes that
 * page. `rotate` (optional) is the ABSOLUTE clockwise orientation in degrees, a multiple
 * of 90 — omit it (or 0) to keep the page's current orientation.
 */
export interface PageOperation {
  source: number
  rotate?: number
}

/**
 * The v1 page-operation manifest posted to POST /file/:id/pages. `baseVersion` is the
 * expected version of the file being operated on (optimistic concurrency): the backend
 * rejects a stale base so two editors cannot silently clobber each other.
 */
export interface PageManifest {
  version: 1
  baseVersion: number
  pages: PageOperation[]
}

/**
 * Apply a v1 page-operation manifest (reorder / delete / per-page rotate) to a PDF file,
 * saving the result as a NEW version via POST /file/:id/pages (form-encoded, like the
 * rotation/reorder endpoints). `fileId` is the current (latest) file id; the original is
 * preserved as a prior version. The backend rejects a stale base (409), an over-ceiling /
 * signed / encrypted / empty-output result (typed 4xx), or a saturated concurrency limit
 * (429) — each surfaced to the caller via the error `type`/status.
 */
export function applyPageOperations(fileId: string, manifest: PageManifest) {
  const params = new URLSearchParams()
  params.set('manifest', JSON.stringify(manifest))
  return api.post(`/file/${fileId}/pages`, params)
}

export function deleteFile(fileId: string) {
  return api.delete(`/file/${fileId}`)
}

export function renameFile(fileId: string, name: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  return api.post(`/file/${fileId}`, params)
}

export function reprocessFile(fileId: string) {
  return api.post(`/file/${fileId}/process`)
}

/**
 * List the files of a document, including the live `processing` flag for each.
 * Backed by GET /file/list?id=<documentId>. Used to poll processing state
 * cheaply without refetching the whole document detail.
 */
export async function getFileList(documentId: string): Promise<FileListItem[]> {
  const res = await api.get(`/file/list`, { params: { id: documentId } })
  return (res.data?.files ?? []) as FileListItem[]
}

/**
 * List all versions of a file, newest revisions and the original included.
 * Backed by GET /file/:id/versions. There is no restore endpoint on the
 * backend, so this is read-only history.
 */
export async function getFileVersions(fileId: string): Promise<FileVersion[]> {
  const res = await api.get(`/file/${fileId}/versions`)
  return (res.data?.files ?? []) as FileVersion[]
}

export async function getFileContent(fileId: string, shareId?: string): Promise<string> {
  const params: Record<string, string> = { size: 'content' }
  // An anonymous share reader must thread the share credential so the content read
  // passes the document's SHARE ACL, exactly as the image/original reads do.
  if (shareId) params.share = shareId
  const res = await api.get(`/file/${fileId}/data`, {
    params,
    responseType: 'text',
    transformResponse: [(data: string) => data],
  })
  return res.data
}

/**
 * Build the GET URL for a document's whole-file ZIP (server-side zip of every file of the
 * document, GET /file/zip?id=<docId>). Used by the per-document Download affordance when a
 * document has more than one file; a single-file document links straight to the file instead.
 */
export function getDocumentZipUrl(documentId: string): string {
  return `api/file/zip?id=${encodeURIComponent(documentId)}`
}

/**
 * POST an explicit list of file IDs to /file/zip and resolve the ZIP as a Blob. The backend
 * zips exactly these files (it does not scope by document), so the caller assembles the id
 * list — e.g. the union of several documents' files for a bulk download. Sent form-encoded
 * (repeated `files` params) to match the existing @FormParam("files") endpoint.
 */
export async function zipFilesBlob(fileIds: string[]): Promise<Blob> {
  const params = new URLSearchParams()
  for (const id of fileIds) params.append('files', id)
  const res = await api.post('/file/zip', params, { responseType: 'blob' })
  return res.data as Blob
}
