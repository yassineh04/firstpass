import { expect, test } from '@playwright/test'

test('the first Tab reaches the skip link, which moves focus to the main content', async ({
  page,
}) => {
  await page.goto('/')

  await page.keyboard.press('Tab')
  const skipLink = page.getByRole('link', { name: 'Skip to main content' })
  await expect(skipLink).toBeFocused()
  await expect(skipLink).toBeVisible()

  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
})

test('switching to French translates the page and survives a reload', async ({ page }) => {
  await page.goto('/')

  await page.getByLabel('Language').selectOption('fr')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Première revue de code par IA pour GitHub',
  )
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr')

  await page.reload()
  await expect(page.getByLabel('Langue')).toHaveValue('fr')
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr')
})

test('the not-found page links back home and focus moves to the new heading', async ({ page }) => {
  await page.goto('/#/does-not-exist')

  await page.getByRole('link', { name: 'Back to the home page' }).click()

  const heading = page.getByRole('heading', { level: 1 })
  await expect(heading).toHaveText('AI first-pass code review for GitHub')
  await expect(heading).toBeFocused()
})
