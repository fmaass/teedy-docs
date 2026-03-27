/**
 * All languages supported by Teedy's OCR engine (Tesseract).
 * Codes match Constants.SUPPORTED_LANGUAGES on the backend and the
 * tesseract-ocr-* packages installed in the Docker image.
 */
export const SUPPORTED_LANGUAGES = [
  { label: 'English', value: 'eng' },
  { label: 'Deutsch', value: 'deu' },
  { label: 'Français', value: 'fra' },
  { label: 'Español', value: 'spa' },
  { label: 'Italiano', value: 'ita' },
  { label: 'Português', value: 'por' },
  { label: 'Nederlands', value: 'nld' },
  { label: 'Polski', value: 'pol' },
  { label: 'Русский', value: 'rus' },
  { label: 'Українська', value: 'ukr' },
  { label: 'Čeština', value: 'ces' },
  { label: 'Magyar', value: 'hun' },
  { label: 'Suomi', value: 'fin' },
  { label: 'Svenska', value: 'swe' },
  { label: 'Dansk', value: 'dan' },
  { label: 'Norsk', value: 'nor' },
  { label: 'Latviešu', value: 'lav' },
  { label: 'Türkçe', value: 'tur' },
  { label: 'Ελληνικά', value: 'ell' },
  { label: 'العربية', value: 'ara' },
  { label: 'עברית', value: 'heb' },
  { label: 'हिन्दी', value: 'hin' },
  { label: 'ไทย', value: 'tha' },
  { label: 'Tiếng Việt', value: 'vie' },
  { label: 'Shqip', value: 'sqi' },
  { label: '中文（简体）', value: 'chi_sim' },
  { label: '中文（繁體）', value: 'chi_tra' },
  { label: '日本語', value: 'jpn' },
  { label: '한국어', value: 'kor' },
]

export function languageLabel(code: string): string {
  return SUPPORTED_LANGUAGES.find((l) => l.value === code)?.label ?? code
}
