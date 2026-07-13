import api from './client'

// GET /api/theme (public). The custom application name drives the browser tab
// title; color/css back the injected stylesheet (consumed elsewhere).
export interface ThemeConfig {
  name: string
  color: string
  css: string
  // Cache-bust token for the favicon — the uploaded file's last-modified epoch
  // millis (0 when no custom favicon). Changes whenever the image is replaced,
  // so the SPA can force the browser past the 15-day favicon cache. Absent on
  // older servers that predate the favicon feature.
  favicon_version?: number
}

export async function getTheme(): Promise<ThemeConfig> {
  const { data } = await api.get<ThemeConfig>('/theme')
  return data
}
