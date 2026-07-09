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
