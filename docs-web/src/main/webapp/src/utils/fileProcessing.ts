/**
 * Pure predicate for the OCR / content-extraction processing indicator.
 *
 * The backend exposes a real per-file `processing` boolean on GET /file/list
 * (RestUtil.fileToJsonObjectBuilder -> FileUtil.isProcessingFile). The view
 * polls that endpoint only while something is still processing; this predicate
 * decides whether another poll is warranted so the polling logic can be tested
 * without Vue.
 */

/** A minimal shape carrying the backend's processing flag. */
export interface HasProcessing {
  processing?: boolean
}

/**
 * True if any file reports `processing === true`. Missing/undefined flags are
 * treated as not-processing, so an empty list or a list of settled files stops
 * polling.
 */
export function shouldPoll(files: readonly HasProcessing[] | null | undefined): boolean {
  if (!files?.length) return false
  return files.some((f) => f.processing === true)
}
