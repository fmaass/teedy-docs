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
    // recipe). A generous maxDiffPixelRatio absorbs sub-pixel AA noise; `animations`
    // freezes CSS/Web animations at capture so a shot is a settled frame.
    toHaveScreenshot: {
      // Absorbs sub-pixel AA noise at mask boundaries and font hinting (observed
      // run-to-run diffs land at 0.03-0.04) while staying far below a REAL layout
      // break — a German-overflow / broken-CSS regression produces a 0.13-0.45 diff
      // (measured), so this comfortably separates noise from a genuine glitch.
      maxDiffPixelRatio: 0.06,
      // Anti-alias / hinting differences on individual pixels shouldn't trip a diff.
      threshold: 0.2,
      animations: 'disabled',
    },
  },
  use: {
    baseURL,
    trace: 'on-first-retry',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
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
