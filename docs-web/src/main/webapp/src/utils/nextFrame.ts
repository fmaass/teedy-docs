/**
 * Resolves inside the browser's next rendering update, after that update has delivered
 * every scroll event queued before the call (#213).
 *
 * The ordering is the point, not the delay. A scroll does not dispatch its event where
 * the scrolling happens: the position moves immediately, the `scroll` event is queued and
 * fired at the start of the next rendering update — and rendering updates run the scroll
 * steps BEFORE the animation-frame callbacks. So a `requestAnimationFrame` callback is the
 * first moment at which no scroll from before the call can still be in flight, however far
 * behind the main thread has fallen.
 *
 * That matters for anything that arms a scroll-dismissed overlay: PrimeVue binds a scroll
 * listener on the anchor's scrollable ancestors as the overlay mounts and reads a single
 * scroll event as "the anchor moved, dismiss". Opened inline, such an overlay can be torn
 * down by a scroll the user had already finished — on a loaded machine the queued event
 * arrives after the overlay is up. Arming it here instead means it only ever sees scrolls
 * that happened after it opened.
 */
export function nextFrame(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => resolve())
  })
}
