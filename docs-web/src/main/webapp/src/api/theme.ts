import api from './client'

/** The theme images an admin can replace, and reset back to the bundled default. */
export type ThemeImageType = 'logo' | 'background' | 'favicon'

// GET /api/theme (public). The custom application name drives the browser tab title; `color`
// and the custom CSS back the stylesheet useThemeBranding links into the document, and
// `main_color` is derived into the PrimeVue primary palette.
export interface ThemeConfig {
  name: string
  color: string
  /**
   * The brand colour the UI palette is derived from, or null when unset — null MEANS something
   * here: the derived-palette feature is off and the app keeps its stock accent. Absent on
   * servers older than this feature.
   */
  main_color?: string | null
  css: string
  // Cache-bust token for the favicon — the uploaded file's last-modified epoch
  // millis (0 when no custom favicon). Changes whenever the image is replaced,
  // so the SPA can force the browser past the 15-day favicon cache. Absent on
  // older servers that predate the favicon feature.
  favicon_version?: number
  // Same, for the two images the Branding settings screen previews. Without them a
  // just-uploaded logo would keep showing its predecessor for up to 15 days.
  logo_version?: number
  background_version?: number
  /**
   * Cache-bust token for GET /theme/stylesheet, derived from the compiled body (the generated
   * colour rule included), so changing only the navbar colour still busts it.
   */
  stylesheet_version?: string
  /**
   * Cache-bust token for GET /theme/script. EMPTY means there is no custom script at all — the
   * SPA skips the injection entirely rather than loading an empty file.
   */
  script_version?: string
}

/**
 * The theme fields POST /theme accepts. Every one is optional and the distinction matters:
 * OMITTING a field preserves the stored value, sending it EMPTY clears it.
 */
export interface ThemeUpdate {
  name?: string
  color?: string
  main_color?: string
}

export async function getTheme(): Promise<ThemeConfig> {
  const { data } = await api.get<ThemeConfig>('/theme')
  return data
}

export async function saveTheme(update: ThemeUpdate): Promise<void> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(update)) {
    // `undefined` means "do not touch this field" and must not reach the wire; an empty string
    // is a deliberate clear and must.
    if (value !== undefined) params.set(key, value)
  }
  await api.post('/theme', params)
}

/** The stored custom CSS. Read from the theme payload, which reports the EFFECTIVE stylesheet. */
export async function saveThemeStylesheet(css: string): Promise<void> {
  await api.put('/theme/stylesheet', css, { headers: { 'Content-Type': 'text/plain' } })
}

export async function deleteThemeStylesheet(): Promise<void> {
  await api.delete('/theme/stylesheet')
}

export async function getThemeScript(): Promise<string> {
  // responseType 'text' keeps axios from trying to JSON-parse a JavaScript body.
  const { data } = await api.get<string>('/theme/script', { responseType: 'text' })
  return data ?? ''
}

export async function saveThemeScript(script: string): Promise<void> {
  await api.put('/theme/script', script, { headers: { 'Content-Type': 'text/plain' } })
}

export async function deleteThemeScript(): Promise<void> {
  await api.delete('/theme/script')
}

export async function uploadThemeImage(type: ThemeImageType, file: File): Promise<void> {
  const form = new FormData()
  form.append('image', file)
  await api.put(`/theme/image/${type}`, form)
}

export async function deleteThemeImage(type: ThemeImageType): Promise<void> {
  await api.delete(`/theme/image/${type}`)
}
