import { defineConfig, devices } from '@playwright/test'

// End-to-end tests drive a REAL running Teedy instance (the production Docker
// image on port 8080, context path "/") via its native form login — NOT Authelia
// (Authelia only fronts production). scripts/e2e-run.sh boots the container and
// waits for /api/user before invoking `npx playwright test`.
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  // A storageState produced by global-setup logs in as admin/admin once; specs
  // that need the login form itself opt out via `test.use({ storageState: {…} })`.
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 30_000,
  expect: {
    timeout: 10_000,
    // Visual-regression tolerance for the STANDING visual gate (e2e/visual.spec.ts:
    // key screens × {desktop,mobile} × {en,de}). Baselines are RENDERER/OS-sensitive:
    // Playwright name-spaces them by OS (`*-linux.png`), and CI runs Linux, so ONLY the
    // committed `*-linux.png` baselines are authoritative — a macOS PNG must NEVER be
    // committed as the source of truth (see e2e/COVERAGE.md for the Docker generation
    // recipe). maxDiffPixelRatio absorbs sub-pixel AA noise — and nothing more than that,
    // see the calibration below; `animations` freezes CSS/Web animations at capture so a
    // shot is a settled frame.
    toHaveScreenshot: {
      // CALIBRATED, not guessed (#259). The previous 0.06 was ~1500x looser than the
      // noise it was meant to absorb, so it swallowed real changes: a reworded UI string
      // passed green against a baseline showing the OLD wording. The value below was
      // derived from measurements taken in the pinned jammy container this gate runs in
      // (2026-08-11, 4 compare runs over all 28 baselines + two deliberate red probes).
      //
      // AA FLOOR — the diff a re-run produces with NO code change, at threshold 0.2:
      //   26 of 28 baselines: 0 differing pixels, three runs in a row.
      //   gallery-{en,de}-desktop: exactly 70 px of 921,600 (= 0.000076) in every run —
      //     scattered glyph/icon AA against baselines generated in an earlier session;
      //     stable, not jitter. Those two calls carry a per-call opt-out (visual.spec.ts).
      //   (At threshold 0 — raw byte equality — 7 baselines differ, by 1..108 px. That is
      //    the sub-pixel primeicons AA the gate deliberately does not chase; see the
      //    threshold note below.)
      //
      // SIGNAL — what a real change costs, measured with probes that were then reverted:
      //   one reworded description line  -> settings-hub-en:    1289 px mobile / 684 px desktop
      //   one reworded field label       -> settings-config-en:  271 px mobile / 260 px desktop
      //   one extra admin card on the hub-> settings-hub, ALL four combos: 1021/1145 px
      //                                     desktop en/de, 1048/1177 px mobile en/de
      //                                     (the ffc31d5f case the old 0.06 swallowed)
      //
      // 0.00004 (40 ppm) sits above every globally-governed screen's measured floor (0 px)
      // and at least 2.1x below the smallest probe signal — the binding case is the
      // largest image, settings-config-en-desktop: 3,072,000 px x 40 ppm = 122 px allowed
      // against the 260 px a short label reword moves. Per-screen budgets run from 2 px
      // (rich-editor mobile, 60,722 px) to 122 px (settings-config desktop).
      //
      // If a screen ever shows a STABLE small delta on another renderer, the fix is a
      // per-call opt-out on that one screenshot carrying the measured number (as gallery
      // desktop has) — NOT a looser global, which is how the gate went blind in the first
      // place.
      maxDiffPixelRatio: 0.00004,
      // Per-pixel YIQ tolerance: an anti-aliased edge whose colour drifts slightly is not
      // a difference. Kept at 0.2 — measured: it removes ~90% of the raw AA noise (the 7
      // baselines that differ by 1..108 px at threshold 0 collapse to 2 at threshold 0.2)
      // while every probe signal above is counted at 0.2, so it costs no sensitivity.
      threshold: 0.2,
      animations: 'disabled',
    },
  },
  use: {
    baseURL,
    // A trace is only useful if the run that failed KEPT one. `on-first-retry` records
    // nothing where retries are zero — which is every local/pinned-CPU control run
    // (`retries` is 1 only under CI) — so the one run worth diagnosing was exactly the run
    // with no evidence (#203). `retain-on-failure` records every test and discards the
    // trace of the ones that pass, so a failing test always leaves its trace behind, on CI
    // and locally alike, at the cost of recording overhead on the passing majority.
    trace: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    // Pin Chromium's rasterisation so the visual baselines churn less between regenerations
    // (#267). Headless Chromium otherwise picks CPU-specific Skia code paths and sub-pixel LCD
    // text positioning that vary run to run, so icon-dense screens (primeicons glyphs in the
    // header actions, the default-password banner, the settings-hub cards) regenerated
    // byte-differently — sub-threshold, absorbed by maxDiffPixelRatio, but noisy in a diff.
    // These flags cut that churn markedly (measured ~13 -> ~10 byte-unstable of 28 across three
    // regenerations); they do NOT make it byte-deterministic — headless Skia icon-glyph AA is
    // not fully byte-stable, especially at the mobile DPR 2.75 — which is exactly why the gate
    // keeps a pixel-ratio tolerance rather than demanding byte-equality. Rendering-only flags,
    // inert for the functional specs; the baselines are generated WITH them, so CI (same config)
    // compares like-for-like.
    launchOptions: {
      args: [
        '--disable-skia-runtime-opts',
        '--font-render-hinting=none',
        '--force-color-profile=srgb',
        '--disable-lcd-text',
      ],
    },
  },
  projects: [
    {
      // DESKTOP: Desktop Chrome (1280×720). Runs the FULL spec set unchanged —
      // this is the existing behaviour (project was named `chromium`; renamed to
      // `desktop` now that a sibling `mobile` project exists). It ignores only the
      // mobile-only responsive spec, which asserts the isMobile branch and would be
      // meaningless at desktop width.
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/admin.json',
      },
      testIgnore: /responsive\.spec\.ts/,
    },
    {
      // MOBILE: a real Playwright device descriptor (Pixel 5 — 393×851, touch,
      // DPR 2.75) so AppLayout's `matchMedia('(max-width: 1024px)')` branch (Drawer
      // + hamburger instead of the desktop side-panel) actually renders. Reuses the
      // SAME admin storageState global-setup produces, so it starts logged in exactly
      // like desktop.
      //
      // This project re-runs the ENTIRE spec set at the mobile viewport (a full
      // mobile REGRESSION suite to catch app-wide mobile CSS glitches), EXCEPT the
      // explicitly-listed desktop-only specs below. The shared specs are made
      // viewport-agnostic by routing their navigation through the openNav/tagTreePanel
      // helpers (e2e/helpers.ts), which open the Drawer on mobile.
      //
      // MOBILE EXCLUSIONS (testIgnore) — each with its reason:
      //   * docs-screenshots.spec.ts — DELIBERATELY pins a fixed 1280×800 viewport
      //     (VIEWPORT const) to capture desktop marketing/doc screenshots at a stable
      //     frame; it is a capture tool, not a responsive behaviour test, and running
      //     it on mobile would fight its own setViewportSize and produce desktop-framed
      //     shots under the mobile project. Its assertions are already proven on desktop.
      // (responsive.spec.ts is NOT excluded here — it is the mobile-only spec and MUST
      //  run under this project; testMatch is not needed because it's ignored on desktop.)
      name: 'mobile',
      use: {
        ...devices['Pixel 5'],
        storageState: 'e2e/.auth/admin.json',
      },
      testIgnore: [/docs-screenshots\.spec\.ts/],
    },
  ],
})
