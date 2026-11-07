import { afterEach, describe, expect, it, vi } from 'vitest'

import { detectInitialLocale, LOCALE_STORAGE_KEY, readSavedLocale } from '@/i18n'
import en from '@/i18n/locales/en.json'
import fr from '@/i18n/locales/fr.json'

type Messages = { [key: string]: string | Messages }

function flatten(messages: Messages, prefix = ''): Record<string, string> {
  return Object.entries(messages).reduce<Record<string, string>>((flat, [key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return typeof value === 'string'
      ? { ...flat, [path]: value }
      : { ...flat, ...flatten(value, path) }
  }, {})
}

describe('translation files', () => {
  it('have exactly the same keys in English and French', () => {
    expect(Object.keys(flatten(fr)).sort()).toEqual(Object.keys(flatten(en)).sort())
  })

  it.each([
    ['en', en],
    ['fr', fr],
  ])('%s has no empty message', (_locale, messages) => {
    const empty = Object.entries(flatten(messages))
      .filter(([, value]) => value.trim() === '')
      .map(([key]) => key)

    expect(empty).toEqual([])
  })
})

describe('detectInitialLocale', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
  })

  it('prefers the saved choice', () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'fr')

    expect(detectInitialLocale()).toBe('fr')
  })

  it('ignores an unsupported saved value', () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'de')

    expect(readSavedLocale()).toBeNull()
  })

  it('falls back to the first supported browser language', () => {
    vi.spyOn(Navigator.prototype, 'languages', 'get').mockReturnValue(['de-DE', 'fr-CA', 'en'])

    expect(detectInitialLocale()).toBe('fr')
  })

  it('uses English when no browser language is supported', () => {
    vi.spyOn(Navigator.prototype, 'languages', 'get').mockReturnValue(['de-DE'])

    expect(detectInitialLocale()).toBe('en')
  })

  it('still works when the browser storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    vi.spyOn(Navigator.prototype, 'languages', 'get').mockReturnValue(['fr'])

    expect(detectInitialLocale()).toBe('fr')
  })
})
