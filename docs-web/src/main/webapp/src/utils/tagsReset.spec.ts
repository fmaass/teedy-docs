import { describe, it, expect } from 'vitest'
import { shouldResetTags } from './tagsReset'

// --- BL-025: DocumentEdit could not remove the LAST tag ---
//
// The backend PRESERVES tags on an omitted `tags` param, so an empty tags array is
// a silent no-op on update. Clearing the last tag on an EDIT must send the explicit
// tags_reset=true sentinel (mirrors the metadata_reset path and bulkOps). A CREATE
// with no tags must NOT send the sentinel (there is nothing to reset).

describe('shouldResetTags (BL-025)', () => {
  it('is true when editing and the tag list is now empty', () => {
    expect(shouldResetTags(true, 0)).toBe(true)
  })

  it('is false when editing but tags remain', () => {
    expect(shouldResetTags(true, 2)).toBe(false)
  })

  it('is false on create even with an empty tag list (nothing to reset)', () => {
    expect(shouldResetTags(false, 0)).toBe(false)
  })

  it('is false on create with tags', () => {
    expect(shouldResetTags(false, 3)).toBe(false)
  })
})
