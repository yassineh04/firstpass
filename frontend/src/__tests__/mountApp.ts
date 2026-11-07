import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from '@/App.vue'
import { type AppLocale, createAppI18n } from '@/i18n'
import { routes } from '@/router'

/** Mounts the whole app on a route, with real routes and translations, attached to the DOM. */
export async function mountApp(path = '/', locale: AppLocale = 'en') {
  const i18n = createAppI18n(locale)
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(App, {
    global: { plugins: [createPinia(), i18n, router] },
    attachTo: document.body,
  })
  return { wrapper, router, i18n }
}
