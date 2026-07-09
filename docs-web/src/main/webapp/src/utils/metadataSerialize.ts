import type { MetadataDefinition } from '../api/metadata'

// A custom-metadata value as held by the DocumentEdit form inputs.
export type MetadataValue = string | number | boolean | Date | null

// One serialized metadata pair destined for the document save form params.
export interface MetadataParam {
  id: string
  value: string
}

/**
 * Decide whether a field carries a submittable value.
 *
 * The backend validates numeric/date metadata values as numbers/timestamps and
 * rejects the ENTIRE document save on a blank — so an unset INTEGER/FLOAT/DATE must
 * be omitted, never sent as a blank pair. BOOLEAN is special: a set value of `false`
 * is meaningful and must be submitted, but an unset boolean must stay unset (not be
 * coerced to an explicit "false" on an unrelated save). We therefore treat a boolean
 * as submittable only when it was explicitly set (its id is in `setIds`).
 */
function isSubmittable(
  type: MetadataDefinition['type'],
  value: MetadataValue,
  id: string,
  setIds: Set<string>,
): boolean {
  if (type === 'BOOLEAN') {
    // Only submit a boolean the user actually set (or that already had a value).
    return setIds.has(id) && typeof value === 'boolean'
  }
  return value != null && value !== ''
}

/**
 * Serialize one typed value to the string the backend expects. Assumes the field is
 * already known to be submittable.
 */
export function serializeMetadataValue(
  type: MetadataDefinition['type'],
  value: MetadataValue,
): string {
  if (type === 'DATE') return String((value as Date).getTime())
  if (type === 'BOOLEAN') return value ? 'true' : 'false'
  return String(value)
}

/**
 * Build the ordered list of metadata_id/metadata_value pairs to submit, omitting
 * every unset field so the backend does not reject the document save.
 */
export function buildMetadataParams(
  definitions: MetadataDefinition[],
  values: Record<string, MetadataValue>,
  setIds: Set<string>,
): MetadataParam[] {
  const params: MetadataParam[] = []
  for (const def of definitions) {
    const value = values[def.id] ?? null
    if (!isSubmittable(def.type, value, def.id, setIds)) continue
    params.push({ id: def.id, value: serializeMetadataValue(def.type, value) })
  }
  return params
}

/**
 * Decide whether a document save must send the `metadata_reset=true` sentinel.
 *
 * The backend preserves custom-metadata values on an omitted set (POST
 * /document/{id} — the P8 partial-update contract), so clearing the last set value
 * would be a silent no-op. We send the clear-all sentinel ONLY on a genuine clear:
 * the document HAD set metadata values at load, AND the current save emits zero
 * metadata_id params. When params ARE present they are a normal update and take
 * precedence (no sentinel); when the document never had values, an empty save is
 * not a clear and must not send the sentinel either.
 */
export function shouldResetMetadata(hadSetValues: boolean, params: MetadataParam[]): boolean {
  return hadSetValues && params.length === 0
}
