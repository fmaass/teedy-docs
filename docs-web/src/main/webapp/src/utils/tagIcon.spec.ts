import { describe, it, expect } from 'vitest'
import {
  MAX_EMOJI_LENGTH,
  SUGGESTED_EMOJI,
  emojiIconRef,
  isSingleEmoji,
  parseTagIcon,
  setIconRef,
  tagIconDataUrl,
} from './tagIcon'

/**
 * The client-side half of the emoji rule (#287). The SERVER is the authority — TagIconUtil
 * validates every write — and this exists so the field can say "that is not one emoji" before a
 * Save round trip. The two implementations are therefore held to the SAME cases: every example
 * below has a twin in docs-web's TestTagIconAssignment.
 */
describe('isSingleEmoji', () => {
  it('accepts a plain emoji, with or without its variation selector', () => {
    expect(isSingleEmoji('\u{1F396}\u{FE0F}')).toBe(true)
    expect(isSingleEmoji('\u{1F396}')).toBe(true)
    expect(isSingleEmoji('\u{2B50}')).toBe(true)
  })

  it('accepts a ZWJ sequence as ONE emoji, though it is eleven code units', () => {
    const family = '\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}\u{200D}\u{1F466}'
    expect(family.length).toBe(11)
    expect(isSingleEmoji(family)).toBe(true)
  })

  it('accepts a skin-tone modified emoji and a flag', () => {
    expect(isSingleEmoji('\u{1F44D}\u{1F3FD}')).toBe(true)
    expect(isSingleEmoji('\u{1F1E8}\u{1F1ED}')).toBe(true)
  })

  it('accepts a keycap', () => {
    expect(isSingleEmoji('1\u{FE0F}\u{20E3}')).toBe(true)
  })

  it('rejects a bare ASCII digit, which Unicode nonetheless gives the Emoji property', () => {
    // The case a naive "every code point is an emoji" check gets wrong: `1`, `#` and `*` all
    // carry the Emoji property, so the rule needs the pictographic/flag/keycap clause.
    expect(isSingleEmoji('1')).toBe(false)
    expect(isSingleEmoji('#')).toBe(false)
    expect(isSingleEmoji('*')).toBe(false)
  })

  it('rejects letters, punctuation and an ASCII smiley', () => {
    expect(isSingleEmoji('a')).toBe(false)
    expect(isSingleEmoji('tag')).toBe(false)
    expect(isSingleEmoji(':)')).toBe(false)
  })

  it('rejects two emoji', () => {
    expect(isSingleEmoji('\u{1F396}\u{FE0F}\u{1F44D}')).toBe(false)
  })

  it('rejects an emoji with text around it', () => {
    expect(isSingleEmoji('\u{2B50} star')).toBe(false)
  })

  it('rejects nothing and whitespace', () => {
    expect(isSingleEmoji('')).toBe(false)
    expect(isSingleEmoji('   ')).toBe(false)
  })

  it('rejects a joiner chain longer than the column holds', () => {
    const chain = '\u{1F468}' + '\u{200D}\u{1F468}'.repeat(12)
    expect(chain.length).toBeGreaterThan(MAX_EMOJI_LENGTH)
    expect(isSingleEmoji(chain)).toBe(false)
  })

  it('ignores surrounding whitespace on an otherwise valid emoji', () => {
    expect(isSingleEmoji(' \u{2B50} ')).toBe(true)
  })

  it('accepts every emoji in the suggested grid', () => {
    // A suggestion the field would then mark invalid would be indefensible.
    for (const emoji of SUGGESTED_EMOJI) {
      expect(isSingleEmoji(emoji), `${emoji} must be one emoji`).toBe(true)
    }
  })
})

describe('parseTagIcon', () => {
  it('reads an emoji reference', () => {
    expect(parseTagIcon('emoji:\u{2B50}')).toEqual({ kind: 'emoji', emoji: '\u{2B50}' })
  })

  it('reads a set reference', () => {
    expect(parseTagIcon('set:8b1e4f22-0000-4000-8000-000000000001')).toEqual({
      kind: 'set',
      id: '8b1e4f22-0000-4000-8000-000000000001',
    })
  })

  it('reads nothing at all as no icon', () => {
    // Absent is what the API sends for a tag with no icon, and it must never become a broken box.
    expect(parseTagIcon(undefined)).toBeNull()
    expect(parseTagIcon(null)).toBeNull()
    expect(parseTagIcon('')).toBeNull()
  })

  it('reads an unrecognised scheme as no icon rather than throwing', () => {
    // A chip is drawn on every row of the document list. An icon reference a future build wrote
    // and this one does not understand must render as nothing, not as an error.
    expect(parseTagIcon('fontawesome:star')).toBeNull()
    expect(parseTagIcon('emoji:')).toBeNull()
    expect(parseTagIcon('set:')).toBeNull()
  })

  it('reads a set id that could not be an id as no icon', () => {
    expect(parseTagIcon('set:../../etc/passwd')).toBeNull()
    expect(parseTagIcon('set:AAAA')).toBeNull()
  })
})

describe('reference builders and the data URL', () => {
  it('round-trips an emoji through its stored form', () => {
    expect(parseTagIcon(emojiIconRef('\u{1F525}'))).toEqual({ kind: 'emoji', emoji: '\u{1F525}' })
  })

  it('round-trips a set id through its stored form', () => {
    const id = '8b1e4f22-0000-4000-8000-000000000001'
    expect(parseTagIcon(setIconRef(id))).toEqual({ kind: 'set', id })
  })

  it('builds a RELATIVE icon URL, like the file endpoints', () => {
    // Root-absolute would break an installation served under a sub-path.
    expect(tagIconDataUrl('abc')).toBe('api/tag/icon/abc/data')
    expect(tagIconDataUrl('abc').startsWith('/')).toBe(false)
  })
})
