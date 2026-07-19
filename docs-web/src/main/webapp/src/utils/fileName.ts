/**
 * Display fallback for a file whose name may be null or empty.
 *
 * The backend serializes a file's `name` via {@code JsonUtil.nullable}, so legacy/inbox rows
 * (and any file created without a name) arrive with `name: null`. Every surface that renders a
 * file name routes through this helper so the UI shows a stable localized label instead of a
 * blank cell / empty alt text.
 *
 * The helper stays a pure, i18n-free util: the caller passes its own `t` (from useI18n). This is
 * the single source of the fallback — do not re-inline `name || t('ui.file_view.untitled')`
 * anywhere else.
 */
export function displayName(
  name: string | null | undefined,
  t: (key: string) => string,
): string {
  return name || t('ui.file_view.untitled')
}
