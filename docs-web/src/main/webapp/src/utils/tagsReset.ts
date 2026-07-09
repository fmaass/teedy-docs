// The backend preserves tags on an omitted `tags` param, so submitting an empty
// tag list is a silent no-op. Removing the LAST tag on an EDIT must send the
// explicit clear-all sentinel (POST /document/{id} tags_reset=true) — mirroring
// the metadata_reset path and bulkOps' buildRemoveTagParams (BL-025). A CREATE
// with no tags has nothing to reset, so it must never emit the sentinel.
export function shouldResetTags(isEdit: boolean, tagCount: number): boolean {
  return isEdit && tagCount === 0
}
