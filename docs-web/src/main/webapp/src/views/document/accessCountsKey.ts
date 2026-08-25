import { inject, type InjectionKey, type Ref } from 'vue'
import type { DocumentAccessCounts } from '../../api/access'

/**
 * Typed provide/inject key for the CALLING user's own access counts (#300).
 *
 * DocumentView owns the one query (see `useAccessCounts`) and provides its data ref here; the
 * Content tab injects it for the per-file numbers. Providing rather than re-querying is what makes
 * "one request per document view" structural instead of a convention the next consumer might break.
 * The ref is `undefined` until the counts resolve, so consumers must render nothing rather than a
 * placeholder zero.
 */
export const AccessCountsKey: InjectionKey<Ref<DocumentAccessCounts | undefined>> =
  Symbol('accessCounts')

/**
 * Inject the access-counts ref inside a DocumentView tab child. Returns an always-undefined ref
 * when no provider is in scope, so a tab rendered on its own (a unit test, a future standalone
 * route) simply shows no counts instead of throwing.
 *
 * @return The counts ref
 */
export function injectAccessCounts(): Ref<DocumentAccessCounts | undefined> {
  return inject(AccessCountsKey, { value: undefined } as Ref<DocumentAccessCounts | undefined>)
}
