// The backend answers a rejected request with `{ "type": "...", "message": "..." }` (see
// ClientException / the REST exception mappers). A component that swallows that body can only show a
// generic "it failed" toast, which is what left a reporter with an unstartable workflow and nothing to
// go on (#312). These helpers read the body defensively: the error reaching a catch block may be an
// Axios error, a network failure with no response at all, or something that is not an Error.

interface ApiErrorShape {
  response?: {
    data?: {
      type?: string
      message?: string
    }
  }
}

/**
 * The backend's human-readable message for a failed request, or undefined when the failure carries
 * none (a network error, a non-JSON body, a thrown value that is not an API error). Blank messages are
 * treated as absent so a caller can fall back to its own wording.
 */
export function apiErrorMessage(error: unknown): string | undefined {
  const message = (error as ApiErrorShape | null | undefined)?.response?.data?.message
  if (typeof message !== 'string') return undefined
  const trimmed = message.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

/**
 * The backend's error type discriminator (e.g. `ValidationError`, `InvalidRouteModel`), or undefined.
 */
export function apiErrorType(error: unknown): string | undefined {
  const type = (error as ApiErrorShape | null | undefined)?.response?.data?.type
  if (typeof type !== 'string') return undefined
  const trimmed = type.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

/**
 * What to put in a toast's `detail`: the backend's error type and message together
 * (`InvalidRouteModel: A step has an invalid target`) when both are present, whichever one exists
 * otherwise, and undefined when the failure carries neither — the caller's localized summary then
 * stands alone. The type is worth showing next to the message because it is the stable, searchable
 * token a user can quote in a bug report, while the message is the human half.
 */
export function apiErrorDetail(error: unknown): string | undefined {
  const type = apiErrorType(error)
  const message = apiErrorMessage(error)
  if (type && message) return `${type}: ${message}`
  return type ?? message
}
