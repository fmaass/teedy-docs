import api from './client'

// One configurable footer/imprint link. Both fields are validated server-side
// (label <= 40 chars non-blank; url http(s) only, <= 500 chars).
export interface FooterLink {
  label: string
  url: string
}

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
  // Configurable footer/imprint links (public chrome). ABSENT when none configured,
  // so an empty/legacy server renders today's chrome (nothing).
  footer_links?: FooterLink[]
}

// POST /api/app/footer_links (ADMIN). Persists the whole list; an empty array
// clears the config. The backend re-serializes normalised {label, url} entries.
export function saveFooterLinks(links: FooterLink[]) {
  const params = new URLSearchParams()
  params.set('links', JSON.stringify(links))
  return api.post('/app/footer_links', params)
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

/**
 * Admin usage-statistics dashboard payload (GET /api/app/stats). Admin-only; a single
 * snapshot with no pagination. `window` is one of 7/30/90 UTC days and drives the length
 * of the two time series. Counts: documents/tags = non-deleted; files = non-deleted rows
 * INCLUDING historical versions; users = non-deleted incl. disabled; favorites = raw row
 * count (aggregate only). documents_created buckets on the document's create date;
 * activity counts retained audit-log entries for {Document, File, Comment, Route, Tag}.
 */
export const STATS_WINDOWS = [7, 30, 90] as const
export type StatsWindow = (typeof STATS_WINDOWS)[number]

export interface StatsSeriesPoint {
  date: string
  count: number
}

export interface StatsUserStorage {
  username: string
  storage_current: number
  storage_quota: number
}

export interface AppStats {
  window: number
  totals: {
    documents: number
    files: number
    users: number
    tags: number
    favorites: number
  }
  storage: {
    global: number
    per_user: StatsUserStorage[]
  }
  series: {
    documents_created: StatsSeriesPoint[]
    activity: StatsSeriesPoint[]
  }
}

export function getAppStats(window: StatsWindow) {
  return api.get<AppStats>('/app/stats', { params: { window } }).then((r) => r.data)
}

// GET /api/app/config_smtp returns the SMTP server config WITHOUT the password.
// The password is write-only (BL-028 sibling): the GET never returns it and there
// is NO password_set flag. The POST keeps the stored value when the password field
// is absent/empty, so the UI shows a "leave blank to keep" affordance. Fields set
// via an environment variable (DOCS_SMTP_*) are OMITTED from the GET response
// entirely (the backend suppresses them), so an absent key means env-managed.
export interface SmtpConfig {
  hostname?: string | null
  port?: number | null
  username?: string | null
  password?: string
  from?: string | null
}

// getSmtpConfig returns the response body untouched: an ABSENT key stays absent
// (undefined — env-managed), a present-but-null key stays null (unset, editable).
// Callers must not default absent keys away before smtpEnvManagedFields has seen them.
export function getSmtpConfig() {
  return api.get<SmtpConfig>('/app/config_smtp').then((r) => r.data)
}

/** The SMTP fields the backend suppresses from the GET when env-managed. */
export type SmtpEnvManagedField = 'hostname' | 'port' | 'username'

const SMTP_ENV_MANAGED_CANDIDATES: readonly SmtpEnvManagedField[] = ['hostname', 'port', 'username']

/**
 * Derives which SMTP fields are managed by a DOCS_SMTP_* environment variable.
 * The backend OMITS such a field from the GET response entirely (AppResource
 * config_smtp: the env check wraps the response.add), while an unset-but-editable
 * field is returned as null. Absence — not nullness — is the env-managed signal.
 */
export function smtpEnvManagedFields(config: SmtpConfig): Set<SmtpEnvManagedField> {
  return new Set(SMTP_ENV_MANAGED_CANDIDATES.filter((field) => !Object.hasOwn(config, field)))
}

export function saveSmtpConfig(config: SmtpConfig) {
  const params = new URLSearchParams()
  // Every field is keep-on-empty server-side: only send the ones the admin filled
  // in. An empty password preserves the stored secret.
  if (config.hostname != null && config.hostname !== '') params.set('hostname', config.hostname)
  if (config.port != null) params.set('port', String(config.port))
  if (config.username != null && config.username !== '') params.set('username', config.username)
  if (config.password != null && config.password !== '') params.set('password', config.password)
  if (config.from != null && config.from !== '') params.set('from', config.from)
  return api.post('/app/config_smtp', params)
}

// GET /api/app/config_inbox returns the IMAP inbox-scan config WITHOUT the password
// (write-only, keep-on-empty on POST — same affordance as SMTP/LDAP). `port` is a
// nullable Integer; `enabled`/`autoTagsEnabled`/`deleteImported`/`starttls` are
// always-present booleans. `last_sync` carries the outcome of the previous scan.
export interface InboxLastSync {
  date?: number | null
  error?: string | null
  count?: number
}

export interface InboxConfig {
  enabled: boolean
  autoTagsEnabled: boolean
  deleteImported: boolean
  starttls: boolean
  hostname?: string | null
  port?: number | null
  username?: string | null
  password?: string
  folder?: string | null
  tag?: string | null
  last_sync?: InboxLastSync
}

export function getInboxConfig() {
  return api.get<InboxConfig>('/app/config_inbox').then((r) => r.data)
}

export function saveInboxConfig(config: InboxConfig) {
  const params = new URLSearchParams()
  // The four booleans are validateRequired server-side — always send them.
  params.set('enabled', String(config.enabled))
  params.set('autoTagsEnabled', String(config.autoTagsEnabled))
  params.set('deleteImported', String(config.deleteImported))
  params.set('starttls', String(config.starttls))
  // The rest are keep-on-empty: only send non-empty values. An empty password
  // preserves the stored secret.
  if (config.hostname != null && config.hostname !== '') params.set('hostname', config.hostname)
  if (config.port != null) params.set('port', String(config.port))
  if (config.username != null && config.username !== '') params.set('username', config.username)
  if (config.password != null && config.password !== '') params.set('password', config.password)
  if (config.folder != null && config.folder !== '') params.set('folder', config.folder)
  if (config.tag != null && config.tag !== '') params.set('tag', config.tag)
  return api.post('/app/config_inbox', params)
}

// POST /api/app/test_inbox takes NO params — it tests the SAVED inbox config and
// returns the count of unread messages found. The UI must therefore SAVE the form
// before testing (save-then-test), otherwise the test runs against stale config.
export interface InboxTestResult {
  count: number
}

export function testInbox() {
  return api.post<InboxTestResult>('/app/test_inbox').then((r) => r.data)
}

// GET /api/app/batch/clean_storage/dry_run previews what a real cleanup would reclaim
// WITHOUT mutating anything. The UI reads total + reclaimed_bytes to show a summary confirm
// before the real run. The paginated files array is available but the confirm only needs
// the totals.
export interface CleanStorageDryRunFile {
  id: string
  document_id: string | null
  document_title: string | null
  size: number
  reason: string
}

export interface CleanStorageDryRun {
  total: number
  // Disk bytes the run would free — the actual on-disk footprint of the removal closure plus any
  // age-eligible filesystem orphans (#72).
  reclaimed_bytes: number
  primary_pointer_cleared_count: number
  limit: number
  offset: number
  files: CleanStorageDryRunFile[]
}

export function cleanStorageDryRun(limit = 100, offset = 0) {
  return api
    .get<CleanStorageDryRun>('/app/batch/clean_storage/dry_run', { params: { limit, offset } })
    .then((r) => r.data)
}

// POST /api/app/batch/clean_storage removes orphaned files and DB rows. Returns
// { status: 'ok', file_count, bytes } — the count/bytes actually reclaimed by the run.
export interface CleanStorageResult {
  status: string
  file_count: number
  bytes: number
}

export function cleanStorage() {
  return api.post<CleanStorageResult>('/app/batch/clean_storage').then((r) => r.data)
}
