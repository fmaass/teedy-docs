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
