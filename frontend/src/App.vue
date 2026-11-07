<script setup lang="ts">
import { nextTick, onUnmounted, useTemplateRef, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, RouterView, START_LOCATION, useRoute, useRouter } from 'vue-router'

import LanguageSwitcher from '@/components/LanguageSwitcher.vue'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const main = useTemplateRef<HTMLElement>('main')

// Keep the page language and the tab title in sync with the chosen locale (WCAG 3.1.1, 2.4.2).
watchEffect(() => {
  document.documentElement.lang = locale.value
  const titleKey = route.meta.titleKey
  document.title = titleKey ? `${t(titleKey)} · ${t('app.name')}` : t('app.name')
})

// The URL hash belongs to the router, so the skip link moves focus itself.
function skipToContent() {
  main.value?.focus()
}

// After an in-app navigation, move focus to the new page heading so screen readers announce it.
const removeAfterEach = router.afterEach(async (_to, from) => {
  if (from === START_LOCATION) {
    return
  }
  await nextTick()
  main.value?.querySelector<HTMLElement>('h1')?.focus()
})
onUnmounted(removeAfterEach)
</script>

<template>
  <div
    class="flex min-h-screen flex-col bg-white text-slate-900 dark:bg-slate-950 dark:text-slate-100"
  >
    <a
      href="#main-content"
      class="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-50 focus:rounded-md focus:bg-white focus:px-4 focus:py-2 focus:text-blue-800 focus:shadow-lg dark:focus:bg-slate-900 dark:focus:text-blue-200"
      @click.prevent="skipToContent"
    >
      {{ t('a11y.skipToContent') }}
    </a>

    <header class="border-b border-slate-200 dark:border-slate-800">
      <div class="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-4 px-4 py-3">
        <RouterLink to="/" class="text-lg font-semibold">{{ t('app.name') }}</RouterLink>
        <LanguageSwitcher />
      </div>
    </header>

    <main
      id="main-content"
      ref="main"
      tabindex="-1"
      class="mx-auto w-full max-w-5xl flex-1 px-4 py-8 focus:outline-none"
    >
      <RouterView />
    </main>

    <footer class="border-t border-slate-200 dark:border-slate-800">
      <p class="mx-auto max-w-5xl px-4 py-4 text-sm text-slate-700 dark:text-slate-300">
        {{ t('footer.tagline') }}
      </p>
    </footer>
  </div>
</template>
