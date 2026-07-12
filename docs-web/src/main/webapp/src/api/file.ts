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
 */
export function uploadFile(
  documentId: string,
  file: File,
  onProgress?: (percent: number) => void,
) {
  const formData = new FormData()
  formData.append('id', documentId)
  formData.append('file', file)
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

export async function getFileContent(fileId: string): Promise<string> {
  const res = await api.get(`/file/${fileId}/data`, {
    params: { size: 'content' },
    responseType: 'text',
    transformResponse: [(data: string) => data],
  })
  return res.data
}
