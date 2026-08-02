// Minimal ESLint flat config focused on i18n hygiene (FE-02): flag hardcoded
// user-facing text in Vue templates so it can't silently bypass vue-i18n.
// Scope is deliberately narrow — this is not a full lint suite.
import vueI18n from '@intlify/eslint-plugin-vue-i18n'
import vueParser from 'vue-eslint-parser'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'

// #187 — the teardown-in-`finally` ban (rationale in the e2e block below). Hoisted to a
// constant because a flat-config entry REPLACES a rule's options rather than merging them:
// the spec-only entry that adds the #224 selector has to restate these three, and a copy
// would be a second place to forget.
const TEARDOWN_IN_FINALLY = [
  {
    selector:
      "TryStatement > BlockStatement.finalizer > ExpressionStatement > :not(CallExpression[callee.name='guardedTeardown'], AwaitExpression)",
    message:
      'No teardown in `finally` (#187): a cleanup call here supersedes the body\'s real failure — and wrapping it in `&&`, `?:`, `,` or `void` does not change that. Register it with `cleanup.defer(label, fn)` instead, or wrap it in `guardedTeardown(label, fn)`.',
  },
  {
    selector:
      "TryStatement > BlockStatement.finalizer > ExpressionStatement > AwaitExpression > :not(CallExpression[callee.name='guardedTeardown'])",
    message:
      'No teardown in `finally` (#187): an awaited cleanup call here supersedes the body\'s real failure — `await` of anything but a direct `guardedTeardown(...)` call is banned, including `await (cond && cleanup())`. Register it with `cleanup.defer(label, fn)` instead, or wrap it in `guardedTeardown(label, fn)`.',
  },
  {
    selector: 'TryStatement > BlockStatement.finalizer > :not(ExpressionStatement)',
    message:
      'No teardown in `finally` (#187): a `finally` block may contain only `guardedTeardown(...)` calls — conditionals, loops and declarations there are teardown logic that belongs in `cleanup.defer(label, fn)`.',
  },
]

// #224 — no bare navigation in a spec. `page.goto` resolves on `load`, which on a contended
// runner lands INSIDE vue-router's first navigation: the URL then reads as the new route
// while the app keeps rendering the old one, and the next locator times out mute, blaming an
// element instead of the navigation that never happened (#215/#203). Every spec navigation
// therefore goes through a readiness helper that also asserts the destination route mounted.
//
// The selector matches the MEMBER CALL, not the identifier `page`: secondary page objects are
// named `anon`, `anonPage`, `userPage`, `guestPage`, `viewer`, `departingPage`, `p` … and a
// rule keyed on `page` would wave every one of them through.
//
// Sanctioned escape: `gotoRaw(page, url)` (e2e/helpers.ts) — a named, greppable wrapper for
// the cases where raw navigation IS the subject (a static non-SPA path, a guard bounce, a
// deep link whose query param is consumed during mount, a goto whose readiness belongs after
// a following reload). e2e/helpers.ts is not a spec file, so the wrapper itself is exempt.
const BARE_GOTO_IN_SPEC = {
  selector: "CallExpression[callee.type='MemberExpression'][callee.property.name='goto']",
  message:
    'No bare navigation in a spec (#224): `.goto()` resolves before vue-router has finalized the route, so the next action can run against the PREVIOUS page. Use `gotoRouteReady(page, url, ROUTE_ROOT.<route>)` (or `gotoDocumentList(page)`), `expectRouteReady(page, url, root)` after a reload — or, when raw navigation is genuinely the subject, `gotoRaw(page, url)` with a one-line reason.',
}

export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'scripts/**', '*.config.*'],
  },
  // TypeScript coverage: the ~100 .ts files in src/ were previously linted by NOTHING
  // (only *.vue matched). Apply the @typescript-eslint RECOMMENDED (non-type-checked) set
  // so type-agnostic correctness rules run across the whole TS tree.
  {
    files: ['src/**/*.ts'],
    plugins: { '@typescript-eslint': tsPlugin },
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      // Honour the established `_`-prefix convention for intentionally-unused params
      // (e.g. mock signatures that must match an arity but ignore their args).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
    },
  },
  // e2e (#187): teardown must not live in a test body's `finally`. A throwing teardown
  // SUPERSEDES the body's real exception — and teardown drives the very UI a failed body
  // left broken, so the masking is the common case — while a hanging one turns a precise
  // assertion failure into a bare test timeout. The `cleanup` fixture (e2e/fixtures.ts)
  // runs deferred steps after the body, individually caught and individually bounded.
  //
  // The ban is on the SHAPE, not on a helper name: the recurring form of this defect is
  // written with inline `request.delete(...)` / `expect(...)` calls, which a rule keyed on
  // `deleteDoc` would never see. A `finally` block may therefore contain nothing but calls
  // to `guardedTeardown` (e2e/helpers.ts) — the single sanctioned exemption, which cannot
  // throw out of the finalizer and so preserves the body's error by construction.
  //
  // e2e/lint-fixtures/** is excluded from `npm run lint` and asserted the other way round
  // by `npm run lint:teardown-rule`, which fails if this rule stops firing.
  //
  // The three selectors below are a WHITELIST INVERSION, not an enumeration of bad shapes.
  // An earlier form matched only the finalizer's direct `ExpressionStatement > CallExpression`
  // (optionally through one `AwaitExpression`), so every expression wrapper slipped through —
  // `id && request.delete(...)`, `id ? a() : b()`, `a(), b()`, `void a()`, `await (id && a())`.
  // Rather than chase each wrapper, the rule now permits exactly two statement shapes in a
  // finalizer — `guardedTeardown(...)` and `await guardedTeardown(...)` — and rejects everything
  // else at the TOP of the expression, which covers wrappers of any depth or kind (including
  // TS-only ones) and reports each offending statement exactly once.
  //
  // Out of static reach, accepted: a locally-shadowed `guardedTeardown` (a same-named local
  // binding that is not the real helper) passes the name check — ESLint has no scope/type
  // resolution here, and code review is the control for that counterfeit.
  {
    files: ['e2e/**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      'no-restricted-syntax': ['error', ...TEARDOWN_IN_FINALLY],
    },
  },
  // Spec files carry the #224 bare-goto ban ON TOP of the #187 selectors. The teardown
  // selectors are restated (not inherited) because this entry replaces the rule's whole
  // options array for the files it matches.
  {
    files: ['e2e/**/*.spec.ts'],
    rules: {
      'no-restricted-syntax': ['error', ...TEARDOWN_IN_FINALLY, BARE_GOTO_IN_SPEC],
    },
  },
  {
    files: ['src/**/*.vue'],
    plugins: { '@intlify/vue-i18n': vueI18n },
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tsParser, ecmaVersion: 'latest', sourceType: 'module' },
    },
    settings: {
      'vue-i18n': {
        localeDir: './src/locale/*.json',
        messageSyntaxVersion: '^9.0.0',
      },
    },
    rules: {
      '@intlify/vue-i18n/no-raw-text': [
        'error',
        {
          // Also flag hardcoded user-facing attribute strings (aria-label, placeholder,
          // title, alt) — not just template text — so a11y labels can't bypass i18n.
          attributes: { '/.+/': ['aria-label', 'placeholder', 'title', 'alt'] },
          // Non-translatable glyphs, separators, and established acronyms/brand.
          ignorePattern: '^[\\s\\d\\p{P}\\p{S}]*$',
          ignoreText: [
            '2FA', 'teedy', 'Teedy', 'OCR', 'API', 'PDF', 'URL', 'SSO', 'HTML',
            '{"event": "EVENT_NAME", "id": "entity_id"}',
          ],
        },
      ],
    },
  },
]
