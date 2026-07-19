/**
 * Save a Blob to the user's machine under `filename` by clicking a transient object-URL
 * anchor. Used where the bytes arrive as a fetched Blob rather than a plain navigable URL
 * (the bulk file-ZIP and the account export), so the caller controls the download name and
 * can surface errors instead of navigating away.
 */
export function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // Revoke on the next tick: revoking synchronously can cancel the click-initiated download
  // in some browsers, so let the download start first.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
