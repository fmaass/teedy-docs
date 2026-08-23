import type { LocationQuery } from 'vue-router'

// The saved-filter query oracle (#42 capture/apply, #297 active-filter highlight).
//
// The filter dimensions the documents route carries. `favorites` is deliberately NOT a
// member: the reporter treats favourites as an informal collection (#209), not part of a
// saved filter's identity, so it is excluded BY CONSTRUCTION from serialization AND from
// the comparison — every function here iterates this one key set. `workflow` and
// `favorites` are owned by DocumentList's component state (see stores/tagFilter.ts
// buildFilterQuery), which is why only `workflow` appears in a saved filter.
export const FILTER_KEYS = ['tags', 'exclude', 'mode', 'search', 'workflow'] as const
export type FilterKey = (typeof FILTER_KEYS)[number]

// The dimensions whose ONE value is a comma-joined ID SET. tagFilter.buildFilterQuery
// joins them from an insertion-ordered Set, and toggleTag's 3-state cycle moves a
// re-added id to the end, so the same selection serialises in different orders over an
// ordinary session. `search` (free text), `mode` and `workflow` (scalars) are NOT set-
// valued: a comma there is part of the value and reordering it would call two different
// filters the same one.
const SET_VALUED_KEYS: readonly FilterKey[] = ['tags', 'exclude']

/**
 * Serialize the CURRENT route query VERBATIM into a stored filter query string: every
 * value the URL actually carries is preserved exactly — including empty values and (were
 * the URL ever malformed) repeated keys, which are appended as-is and left to the backend
 * contract to reject. Only non-filter keys are dropped. The URL is the source of truth;
 * no normalization happens here — that is `equals`' job, and only for COMPARISON.
 */
export function serialize(query: LocationQuery): string {
  const params = new URLSearchParams()
  for (const k of FILTER_KEYS) {
    const raw = query[k]
    if (raw === undefined) continue
    for (const v of Array.isArray(raw) ? raw : [raw]) {
      params.append(k, v ?? '')
    }
  }
  return params.toString()
}

/**
 * Parse a stored query string back into a vue-router LocationQuery. Applying flows
 * through the existing initFromUrl() via router.push — no new hydration path.
 */
export function parse(query: string): LocationQuery {
  const out: LocationQuery = {}
  new URLSearchParams(query).forEach((v, k) => {
    out[k] = v
  })
  return out
}

/**
 * Canonical form for COMPARISON only: the filter dimensions that actually constrain the
 * result set, in a fixed key order, each key's values as a sorted multiset.
 *
 * - The ID-SET dimensions (`SET_VALUED_KEYS`) compare as SETS: `tags=a,b` and `tags=b,a`
 *   select the same documents. Every other dimension keeps its value verbatim.
 * - Key ORDER is irrelevant: a stored string carries whatever order the URL had when it
 *   was saved, while the canonical URL is rebuilt by tagFilter.buildFilterQuery in ITS
 *   insertion order. Naive string equality would call the same filter a different one.
 * - EMPTY values are no values: buildFilterQuery emits `mode` only for 'or' and never
 *   emits an empty value, so `mode=` and an absent `mode` describe the same filter.
 * - Non-filter keys (including `favorites`) never enter the form.
 *
 * The form is JSON so that a value which happens to contain `&` or `=` can never be read
 * back as two dimensions.
 */
function canonical(query: string): string {
  const params = new URLSearchParams(query)
  const dimensions: [FilterKey, string[]][] = []
  for (const k of FILTER_KEYS) {
    let values = params.getAll(k).filter((v) => v !== '')
    if (!values.length) continue
    if (SET_VALUED_KEYS.includes(k)) {
      values = values.map((v) =>
        v
          .split(',')
          .filter((id) => id !== '')
          .sort()
          .join(','),
      )
    }
    dimensions.push([k, [...values].sort()])
  }
  return JSON.stringify(dimensions)
}

/** True when both query strings select the same documents (see `canonical`). */
export function equals(a: string, b: string): boolean {
  return canonical(a) === canonical(b)
}
