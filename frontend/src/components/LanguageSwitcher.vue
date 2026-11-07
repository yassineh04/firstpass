<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'

import { useLocale } from '@/composables/useLocale'
import { SUPPORTED_LOCALES, isSupportedLocale } from '@/i18n'

const { t } = useI18n()
const { locale, setLocale } = useLocale()
const selectId = useId()

const selected = computed({
  get: () => locale.value,
  set: (value: string) => {
    if (isSupportedLocale(value)) {
      setLocale(value)
    }
  },
})
</script>

<template>
  <div class="flex items-center gap-2">
    <label :for="selectId" class="text-sm font-medium">{{ t('language.label') }}</label>
    <select
      :id="selectId"
      v-model="selected"
      class="rounded-md border border-slate-500 bg-white px-2 py-1 text-sm text-slate-900 dark:border-slate-400 dark:bg-slate-900 dark:text-slate-100"
    >
      <!-- Each language name is written in that language (WCAG 3.1.2). -->
      <option v-for="code in SUPPORTED_LOCALES" :key="code" :value="code" :lang="code">
        {{ t(`language.names.${code}`) }}
      </option>
    </select>
  </div>
</template>
