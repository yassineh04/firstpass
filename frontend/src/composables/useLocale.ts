import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { type AppLocale, saveLocale } from '@/i18n'

/** Current interface language, and a setter that also remembers the choice. */
export function useLocale() {
  const { locale } = useI18n({ useScope: 'global' })

  const current = computed(() => locale.value as AppLocale)

  function setLocale(next: AppLocale) {
    locale.value = next
    saveLocale(next)
  }

  return { locale: current, setLocale }
}
