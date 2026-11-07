import vueI18n from '@intlify/eslint-plugin-vue-i18n'
import pluginVitest from '@vitest/eslint-plugin'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from 'eslint-config-prettier/flat'
import pluginPlaywright from 'eslint-plugin-playwright'
import pluginVue from 'eslint-plugin-vue'
import { globalIgnores } from 'eslint/config'

export default [
  ...defineConfigWithVueTs(
    {
      name: 'app/files-to-lint',
      files: ['**/*.{vue,ts,mts,tsx}'],
    },

    globalIgnores([
      '**/dist/**',
      '**/coverage/**',
      '**/playwright-report/**',
      '**/test-results/**',
    ]),

    ...pluginVue.configs['flat/recommended'],
    vueTsConfigs.recommended,

    {
      ...pluginPlaywright.configs['flat/recommended'],
      files: ['e2e/**/*.{test,spec}.{js,ts,jsx,tsx}'],
    },

    {
      ...pluginVitest.configs.recommended,
      files: ['src/**/__tests__/*'],
    },

    skipFormatting,
  ),

  // Every user-facing string must come from the translation files (English and French).
  // Added outside defineConfigWithVueTs, whose types do not accept this plugin's configs.
  ...vueI18n.configs.recommended,
  {
    name: 'app/i18n',
    rules: {
      '@intlify/vue-i18n/no-raw-text': 'error',
      '@intlify/vue-i18n/no-missing-keys': 'error',
    },
    settings: {
      'vue-i18n': {
        localeDir: './src/i18n/locales/*.json',
        messageSyntaxVersion: '^11.0.0',
      },
    },
  },
]
