import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const WCAG_21_AA = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

const pages = [
  { name: 'home', path: '/' },
  { name: 'not found', path: '/#/does-not-exist' },
]

for (const colorScheme of ['light', 'dark'] as const) {
  for (const { name, path } of pages) {
    test(`${name} page has no WCAG 2.1 AA violations in ${colorScheme} mode`, async ({ page }) => {
      await page.emulateMedia({ colorScheme })
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

      const results = await new AxeBuilder({ page }).withTags(WCAG_21_AA).analyze()

      expect(results.violations).toEqual([])
    })
  }
}
