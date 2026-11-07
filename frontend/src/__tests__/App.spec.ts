import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { mountApp } from './mountApp'

describe('App', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('starts with the skip link, then the header, main and footer landmarks', async () => {
    ;({ wrapper } = await mountApp())

    expect(wrapper.find('a').text()).toBe('Skip to main content')
    expect(wrapper.find('header').exists()).toBe(true)
    expect(wrapper.find('main#main-content').exists()).toBe(true)
    expect(wrapper.find('footer').exists()).toBe(true)
    expect(wrapper.findAll('h1')).toHaveLength(1)
  })

  it('moves focus to the main content when the skip link is activated', async () => {
    ;({ wrapper } = await mountApp())

    await wrapper.find('a').trigger('click')

    expect(document.activeElement).toBe(wrapper.find('main').element)
  })

  it('sets the page language and the tab title from the current locale', async () => {
    ;({ wrapper } = await mountApp('/', 'fr'))

    expect(document.documentElement.lang).toBe('fr')
    expect(document.title).toBe('Première revue de code par IA pour GitHub · FirstPass')
  })

  it('shows the not-found page for an unknown route', async () => {
    ;({ wrapper } = await mountApp('/does-not-exist'))
    await flushPromises()

    expect(wrapper.find('h1').text()).toBe('Page not found')
    expect(document.title).toBe('Page not found · FirstPass')
  })

  it('moves focus to the new page heading after an in-app navigation', async () => {
    let router
    ;({ wrapper, router } = await mountApp('/does-not-exist'))
    await flushPromises()

    await router.push('/')
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.find('h1').element)
  })
})
