import { createI18n } from 'vue-i18n'

import en from './locales/en.json'
import fr from './locales/fr.json'

export const SUPPORTED_LOCALES = ['en', 'fr'] as const
export type AppLocale = (typeof SUPPORTED_LOCALES)[number]

export const LOCALE_STORAGE_KEY = 'firstpass.locale'

export function isSupportedLocale(value: unknown): value is AppLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value)
}

/** Reads the saved choice. Storage can be unavailable (private mode, blocked site data). */
export function readSavedLocale(): AppLocale | null {
  try {
    const saved = window.localStorage.getItem(LOCALE_STORAGE_KEY)
    return isSupportedLocale(saved) ? saved : null
  } catch {
    return null
  }
}

export function saveLocale(locale: AppLocale): void {
  try {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // Storage unavailable: the choice only lasts for the current page view.
  }
}

/** The saved choice first, then the browser languages, then English. */
export function detectInitialLocale(): AppLocale {
  const saved = readSavedLocale()
  if (saved) {
    return saved
  }
  for (const language of navigator.languages) {
    const base = language.toLowerCase().split('-')[0]
    if (isSupportedLocale(base)) {
      return base
    }
  }
  return 'en'
}

export function createAppI18n(locale: AppLocale = detectInitialLocale()) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { en, fr },
  })
}

export const i18n = createAppI18n()
