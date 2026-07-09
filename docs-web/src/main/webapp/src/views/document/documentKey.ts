import { inject, type InjectionKey, type Ref } from 'vue'
import type { DocumentDetail } from '../../api/document'

/**
 * Typed provide/inject key for the current document detail.
 *
 * DocumentView provides the reactive `useQuery` data ref under this key; its tab
 * child views (Content/Text/Permissions/Activity/Comments) inject it. Using a
 * typed InjectionKey instead of the magic string `'document'` gives the injected
 * ref its type without a non-null assertion at each call site — a missing provider
 * is a type error, not a runtime surprise. The provided ref is the `useQuery` data
 * ref, which is `undefined` until the first fetch resolves (not `null`), so the key
 * carries `| undefined` to match what DocumentView actually provides.
 */
export const DocumentKey: InjectionKey<Ref<DocumentDetail | undefined>> = Symbol('document')

/**
 * Inject the current document ref inside a DocumentView tab child.
 *
 * Throws if no provider is in scope (a tab rendered outside DocumentView) rather
 * than handing back an `undefined` ref that would fail obscurely later. Returns a
 * fully-typed ref — no non-null assertion at the call site.
 */
export function injectDocument(): Ref<DocumentDetail | undefined> {
  const doc = inject(DocumentKey)
  if (!doc) throw new Error('injectDocument() called without a DocumentKey provider')
  return doc
}
