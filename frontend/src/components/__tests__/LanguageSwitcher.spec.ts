import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { createAppI18n, LOCALE_STORAGE_KEY } from '@/i18n'

function mountSwitcher() {
  const i18n = createAppI18n('en')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [i18n] } })
  return { wrapper, i18n }
}

describe('LanguageSwitcher', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
  })

  it('labels the select and names each language in its own language', () => {
    const { wrapper } = mountSwitcher()

    const label = wrapper.find('label')
    expect(label.text()).toBe('Language')
    expect(label.attributes('for')).toBe(wrapper.find('select').attributes('id'))
    expect(
      wrapper
        .findAll('option')
        .map((option) => [option.attributes('value'), option.attributes('lang'), option.text()]),
    ).toEqual([
      ['en', 'en', 'English'],
      ['fr', 'fr', 'Français'],
    ])
  })

  it('switches the interface language and remembers the choice', async () => {
    const { wrapper, i18n } = mountSwitcher()

    await wrapper.find('select').setValue('fr')

    expect(i18n.global.locale.value).toBe('fr')
    expect(wrapper.find('label').text()).toBe('Langue')
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('fr')
  })

  it('still switches when the browser storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    const { wrapper, i18n } = mountSwitcher()

    await wrapper.find('select').setValue('fr')

    expect(i18n.global.locale.value).toBe('fr')
  })
})
