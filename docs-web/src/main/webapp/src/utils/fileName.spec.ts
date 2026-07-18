import { describe, it, expect } from 'vitest'
import { displayName } from './fileName'

// The helper is i18n-free: it takes the caller's `t` so a null/empty file name renders a
// stable localized label instead of a blank cell. The real `ui.file_view.untitled` key
// resolves to "Untitled file" (en) / "Unbenannte Datei" (de); the stub mirrors that key.
const t = (key: string) => (key === 'ui.file_view.untitled' ? 'Untitled file' : key)

describe('displayName', () => {
  it('returns the localized fallback for a null name', () => {
    expect(displayName(null, t)).toBe('Untitled file')
  })

  it('returns the localized fallback for an undefined name', () => {
    expect(displayName(undefined, t)).toBe('Untitled file')
  })

  it('returns the localized fallback for an empty string', () => {
    expect(displayName('', t)).toBe('Untitled file')
  })

  it('passes a real name through unchanged', () => {
    expect(displayName('quarterly-report.pdf', t)).toBe('quarterly-report.pdf')
  })
})
