/**
 * A tag's icon (#287) is ONE nullable string on the tag, discriminated by a prefix:
 *
 *   `emoji:🎖️`   — a single emoji grapheme, stored verbatim
 *   `set:<id>`   — an icon uploaded into the instance's one custom icon set
 *
 * One column rather than two was deliberate: a tag has at most one icon, and two nullable
 * columns would make "both set" representable and leave every reader to decide which wins.
 *
 * The reporter's own words settled the emoji half: "we can already mess around with copy paste
 * emojis" — he pastes them into tag NAMES today, so the picker is the affordance he is already
 * improvising, not a new concept. That is why there is no bundled icon font and no third-party
 * picker dependency: a validated one-emoji field plus a small curated grid.
 */

/** The prefix a stored emoji icon carries. */
const EMOJI_PREFIX = 'emoji:'
/** The prefix a stored icon-set reference carries. */
const SET_PREFIX = 'set:'

/**
 * The longest emoji payload accepted, in UTF-16 code units. A ZWJ family sequence is 11 of them
 * and the longest standard sequence in Unicode 15 is well inside 32; the cap is what stops a
 * pathological chain of joiners from being pasted in. The server enforces the same number, and
 * `varchar(64)` holds either form with room to spare.
 */
export const MAX_EMOJI_LENGTH = 32

/**
 * Every code point an emoji sequence may be built from. `Emoji_Component` covers the variation
 * selectors, the skin-tone modifiers, the regional indicators and the keycap combiner; ZWJ joins
 * the parts of a composite emoji. Anything else — a letter, a punctuation mark, whitespace — is
 * not an emoji and is refused.
 */
const EMOJI_CODEPOINTS = /^[\p{Emoji}\p{Emoji_Component}\u200D\uFE0E\uFE0F]+$/u

/**
 * At least one code point that actually DRAWS something emoji-ish.
 *
 * This clause exists because the `Emoji` property is true for the plain ASCII digits and for `#`
 * and `*` — `1` alone would otherwise pass as an emoji. A real emoji carries an
 * Extended_Pictographic code point (🎖️, 👍🏽, a ZWJ family), or is a flag (two regional
 * indicators), or is a keycap (…U+20E3).
 */
const EMOJI_SIGNIFICANT = /[\p{Extended_Pictographic}\p{Regional_Indicator}\u20E3]/u

/**
 * True when `value` is exactly ONE emoji.
 *
 * Mirrors the server-side rule in `TagIconUtil.validateIconReference` — the server is the
 * authority and validates every write, this is the field's own feedback. "Exactly one" is counted
 * in extended grapheme clusters, not characters: 👨‍👩‍👧‍👦 is eleven code units and one emoji.
 */
export function isSingleEmoji(value: string): boolean {
  const emoji = value.trim()
  if (!emoji || emoji.length > MAX_EMOJI_LENGTH) return false
  if (!EMOJI_CODEPOINTS.test(emoji)) return false
  if (!EMOJI_SIGNIFICANT.test(emoji)) return false
  return graphemeCount(emoji) === 1
}

/**
 * Number of extended grapheme clusters in `value`. `Intl.Segmenter` is the only thing in the
 * platform that implements UAX #29 — splitting on code points would count a ZWJ family as four
 * emoji. Where it is unavailable the count is reported as 1 so the field stays usable; the server
 * still refuses a multi-emoji value, so nothing invalid can be stored either way.
 */
function graphemeCount(value: string): number {
  const Segmenter = (Intl as { Segmenter?: typeof Intl.Segmenter }).Segmenter
  if (!Segmenter) return 1
  return [...new Segmenter('en', { granularity: 'grapheme' }).segment(value)].length
}

export type TagIconRef =
  | { kind: 'emoji'; emoji: string }
  | { kind: 'set'; id: string }

/**
 * Reads a stored icon value into what the chip should draw, or null when there is nothing to
 * draw. A value this cannot parse — an empty string, a reference to a scheme this build does not
 * know — is null rather than an error: an unrecognised icon must render as NO icon, never as a
 * broken box, because the chip is drawn in the document list on every row.
 */
export function parseTagIcon(icon: string | null | undefined): TagIconRef | null {
  if (!icon) return null
  if (icon.startsWith(EMOJI_PREFIX)) {
    const emoji = icon.slice(EMOJI_PREFIX.length)
    return emoji ? { kind: 'emoji', emoji } : null
  }
  if (icon.startsWith(SET_PREFIX)) {
    const id = icon.slice(SET_PREFIX.length)
    return /^[a-z0-9-]{1,36}$/.test(id) ? { kind: 'set', id } : null
  }
  return null
}

/** Builds the stored form of an emoji icon. */
export function emojiIconRef(emoji: string): string {
  return EMOJI_PREFIX + emoji.trim()
}

/** Builds the stored form of an icon-set reference. */
export function setIconRef(id: string): string {
  return SET_PREFIX + id
}

/**
 * The image URL for one uploaded icon. Relative, like `getFileUrl` — the SPA and the API share
 * an origin and a base path, and a root-absolute URL would break a deployment served under a
 * sub-path. The response is immutable for a given id (an edit uploads a new icon), so the server
 * may and does cache it hard.
 */
export function tagIconDataUrl(id: string): string {
  return `api/tag/icon/${id}/data`
}

/**
 * The suggested grid. A short, hand-picked list rather than a full emoji catalogue: this is a
 * shortcut for the common cases the reporter named — companies/vendors, topics, and
 * warning/danger markers — and the field beside it takes any emoji at all, pasted or typed with
 * the OS picker. Static, so nothing here reaches the network.
 */
export const SUGGESTED_EMOJI: readonly string[] = [
  '\u{1F4C1}', // file folder
  '\u{1F4C4}', // page
  '\u{1F4B0}', // money bag
  '\u{1F9FE}', // receipt
  '\u{1F3E0}', // house
  '\u{1F3E2}', // office building
  '\u{1F697}', // car
  '\u{2708}\u{FE0F}', // airplane
  '\u{1F4BC}', // briefcase
  '\u{1F393}', // graduation cap
  '\u{1F3E5}', // hospital
  '\u{1F48A}', // pill
  '\u{2696}\u{FE0F}', // balance scale
  '\u{1F512}', // lock
  '\u{1F6E1}\u{FE0F}', // shield
  '\u{26A0}\u{FE0F}', // warning
  '\u{1F6A8}', // rotating light
  '\u{1F525}', // fire
  '\u{2B50}', // star
  '\u{2764}\u{FE0F}', // red heart
  '\u{1F4CC}', // pushpin
  '\u{1F516}', // bookmark
  '\u{1F5D3}\u{FE0F}', // spiral calendar
  '\u{23F1}\u{FE0F}', // stopwatch
  '\u{2705}', // check mark button
  '\u{274C}', // cross mark
  '\u{1F4E7}', // e-mail
  '\u{1F4DE}', // telephone receiver
  '\u{1F527}', // wrench
  '\u{1F5A5}\u{FE0F}', // desktop computer
  '\u{1F4F7}', // camera
  '\u{1F3B5}', // musical note
  '\u{1F4DA}', // books
  '\u{1F30D}', // globe
  '\u{1F3AF}', // direct hit
  '\u{1F396}\u{FE0F}', // military medal
]
