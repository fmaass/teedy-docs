// Minimal ESLint flat config focused on i18n hygiene (FE-02): flag hardcoded
// user-facing text in Vue templates so it can't silently bypass vue-i18n.
// Scope is deliberately narrow — this is not a full lint suite.
import vueI18n from '@intlify/eslint-plugin-vue-i18n'
import vueParser from 'vue-eslint-parser'
import tsParser from '@typescript-eslint/parser'

export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'scripts/**', '*.config.*'],
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
