import type { Router } from 'vue-router'

/**
 * Capture a navigation the user issues while the app is still booting, and replay it
 * once the router is ready (#216).
 *
 * vue-router's first navigation ends by REPLACING the URL with its own target
 * (`finalizeNavigation`'s isFirstNavigation branch) and only THEN marks the router ready
 * and starts listening for history events. Anything the user does in between — a
 * bookmark, a typed URL, an in-page anchor, the Back button — changes `location`, is
 * never observed by the router, and is silently reverted by that replace: the URL snaps
 * back and the app renders the route the user has already moved on from.
 *
 * Awaiting `router.isReady()` before mounting cannot cover that on its own. Routing
 * starts when the router is INSTALLED, which is long before anything mounts, so a Back
 * press or a typed URL still lands inside the window; and once the router is ready the
 * evidence is gone, because the clobbering replace already made the URL and the current
 * route agree. The intent therefore has to be recorded WHILE the window is open, which
 * is what this does.
 *
 * What it records is deliberately narrow: only a genuine history event. The initial deep
 * link never fires one — it is already in the URL when the page loads — so it can never
 * be mistaken for a mid-boot intent and replayed on top of itself. Neither the router's
 * own finalization nor an auth-guard redirect fires one either: both go through
 * `history.replaceState`, which emits neither `popstate` nor `hashchange`. That is also
 * why the replay uses `replace` — it cannot re-trigger the latch, and it leaves
 * Back/Forward pointing where the user's own navigation left them.
 *
 * The latch is consumed BEFORE the replay navigates, so it is strictly one-shot: the
 * auth guard runs for the replay (it must — the replayed target is subject to exactly
 * the same access rules as any other navigation) and whatever it redirects to is
 * terminal. There is no second replay to loop with.
 */
export type BootNavigationReplay = (router: Router) => Promise<void>

export function armBootNavigationLatch(): BootNavigationReplay {
  let latched: string | null = null

  // The target comes from the EVENT, not from live `window.location`. A hashchange can be
  // DELIVERED after vue-router's finalize-replace has already rewritten the URL back to the
  // first navigation's target, and a handler that read `location` at that moment would latch
  // the clobber itself — replaying the very navigation the user was trying to leave.
  // `newURL` is the location the event was raised FOR, so it survives that reordering.
  const recordHashChange = (event: HashChangeEvent) => {
    latched = new URL(event.newURL).hash
  }
  // A popstate carries no target URL, so the only reading available is the location at
  // delivery — which is correct for a traversal: the browser applies the history entry
  // before dispatching.
  const recordPopState = () => {
    latched = window.location.hash
  }
  window.addEventListener('hashchange', recordHashChange)
  window.addEventListener('popstate', recordPopState)

  return async (router: Router): Promise<void> => {
    // Let the task queue turn once before disarming. An event raised just before the router
    // became ready can still be sitting in the queue undelivered, and tearing the listeners
    // down first would drop it — the navigation would be lost exactly as it is without this
    // latch. One macrotask is enough: the queued event is dispatched ahead of this timer.
    await new Promise((resolve) => setTimeout(resolve, 0))

    window.removeEventListener('hashchange', recordHashChange)
    window.removeEventListener('popstate', recordPopState)
    const pending = latched
    latched = null
    if (pending === null) return

    // Hash history puts the route path after the '#'. A hash the user typed by hand can
    // arrive without the leading slash, which vue-router's own location parsing also
    // normalises rather than resolving relative to the current route.
    const path = pending.replace(/^#/, '')
    try {
      await router.replace(path.startsWith('/') ? path : `/${path}`)
    } catch {
      // A replay that cannot be resolved (a chunk that will not load, a guard that threw)
      // must not take the boot down with it — the app still mounts on whatever route the
      // router settled on, which is what it would have shown without the replay.
    }
  }
}
