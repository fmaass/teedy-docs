import { readonly, ref } from 'vue'

/**
 * "There could be an UI toggle to show/unshow the tag icons" (#287).
 *
 * A per-device display preference, stored the way this app has always stored them — a
 * `teedy_*` key in localStorage, read once at boot (`teedy_tag_view_mode`,
 * `teedy_document_view_mode`, `teedy_document_page_size`). Deliberately NOT a server-side user
 * setting: it says how THIS screen should be drawn, it has to be answerable before the first
 * request comes back (otherwise every chip would flicker its icon in), and nothing else in the
 * app needs to know about it.
 *
 * The stored value is the exception, not the rule: only the string `hidden` hides icons.
 * Absent, unreadable or anything else means SHOW, which is both the default and what every
 * existing installation gets on upgrade without writing anything.
 */
export const TAG_ICONS_STORAGE_KEY = 'teedy_tag_icons'

const HIDDEN = 'hidden'

function readStoredVisibility(): boolean {
  try {
    return localStorage.getItem(TAG_ICONS_STORAGE_KEY) !== HIDDEN
  } catch {
    // A browser with site data blocked still gets a working app, with icons shown.
    return true
  }
}

/**
 * Module-level so every chip on the page shares ONE source of truth: flipping the toggle in
 * Settings has to redraw the document list behind it, and a per-component ref would leave each
 * chip reading its own stale copy.
 */
const visible = ref(readStoredVisibility())

/** Whether tag icons are drawn. Read-only — write through {@link setTagIconsVisible}. */
export const tagIconsVisible = readonly(visible)

export function setTagIconsVisible(value: boolean): void {
  visible.value = value
  try {
    if (value) {
      localStorage.removeItem(TAG_ICONS_STORAGE_KEY)
    } else {
      localStorage.setItem(TAG_ICONS_STORAGE_KEY, HIDDEN)
    }
  } catch {
    // The choice still applies to this page; it just will not survive a reload.
  }
}

/**
 * Re-reads the stored preference. The module-level ref is initialised once per process, so a
 * test that seeds localStorage after import would otherwise be reading the value from before
 * the seed.
 */
export function resetTagIconsVisibility(): void {
  visible.value = readStoredVisibility()
}
