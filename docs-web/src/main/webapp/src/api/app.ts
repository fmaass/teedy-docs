import api from './client'

export interface AppInfo {
  current_version: string
  min_version?: string
  guest_login?: boolean
  oidc_enabled?: boolean
  // Trash retention window (days) before automatic purge; absent on older servers.
  trash_retention_days?: number
  // Admin-configurable settings surfaced to the Config screen (absent on older servers).
  default_language?: string
  tag_search_mode?: string
  ocr_enabled?: boolean
  max_upload_size?: number
}

// GET /api/app carries the running server version in `current_version`
// (the backend already strips any "-SNAPSHOT" suffix before returning it).
export function getAppInfo() {
  return api.get<AppInfo>('/app').then((r) => r.data)
}

/**
 * Log levels the backend's GET /api/app/log accepts for the `level` filter
 * (a MINIMUM level — DEBUG returns everything, FATAL only the most severe).
 * Order is severity-ascending so the Select reads FATAL..DEBUG top-to-bottom.
 */
export const LOG_LEVELS = ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG'] as const
export type LogLevel = (typeof LOG_LEVELS)[number]

/** One in-memory server log line (the MEMORY appender ring buffer). */
export interface LogEntry {
  date: number
  level: string
  tag: string
  message: string
}

export interface LogPage {
  total: number
  logs: LogEntry[]
}

export interface LogQuery {
  level?: string
  tag?: string
  message?: string
  limit?: number
  offset?: number
}

/**
 * GET /api/app/log (ADMIN). Returns a paginated slice of the server's in-memory
 * log buffer filtered by minimum `level`, logger `tag`, and free-text `message`.
 * Only params with a value are sent so the backend's stripToNull treats an empty
 * filter as "no filter". There is no tail/streaming param — the caller paginates
 * via limit/offset.
 */
export function getLogs(query: LogQuery = {}) {
  const params: Record<string, string | number> = {}
  if (query.level) params.level = query.level
  if (query.tag) params.tag = query.tag
  if (query.message) params.message = query.message
  if (query.limit != null) params.limit = query.limit
  if (query.offset != null) params.offset = query.offset
  return api.get<LogPage>('/app/log', { params }).then((r) => r.data)
}
