// Minimal ESLint flat config focused on i18n hygiene (FE-02): flag hardcoded
// user-facing text in Vue templates so it can't silently bypass vue-i18n.
// Scope is deliberately narrow — this is not a full lint suite.
import vueI18n from '@intlify/eslint-plugin-vue-i18n'
import vueParser from 'vue-eslint-parser'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'

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
  {
    files: ['e2e/**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "TryStatement > BlockStatement.finalizer > ExpressionStatement > AwaitExpression > CallExpression:not([callee.name='guardedTeardown'])",
          message:
            'No teardown in `finally` (#187): an awaited cleanup call here supersedes the body\'s real failure. Register it with `cleanup.defer(label, fn)` instead, or wrap it in `guardedTeardown(label, fn)`.',
        },
        {
          selector:
            "TryStatement > BlockStatement.finalizer > ExpressionStatement > CallExpression:not([callee.name='guardedTeardown'])",
          message:
            'No teardown in `finally` (#187): a cleanup call here supersedes the body\'s real failure. Register it with `cleanup.defer(label, fn)` instead, or wrap it in `guardedTeardown(label, fn)`.',
        },
        {
          selector: 'TryStatement > BlockStatement.finalizer > :not(ExpressionStatement)',
          message:
            'No teardown in `finally` (#187): a `finally` block may contain only `guardedTeardown(...)` calls — conditionals, loops and declarations there are teardown logic that belongs in `cleanup.defer(label, fn)`.',
        },
      ],
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
