import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

import HomePage from '@/pages/HomePage.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** Translation key of the page title, shown in the browser tab. */
    titleKey?: string
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: HomePage,
    meta: { titleKey: 'pages.home.title' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundPage.vue'),
    meta: { titleKey: 'pages.notFound.title' },
  },
]

const router = createRouter({
  // GitHub Pages cannot rewrite URLs, so routes live in the URL hash (see ADR 0010).
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes,
})

export default router
