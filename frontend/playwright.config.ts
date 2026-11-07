import process from 'node:process'
import { defineConfig, devices } from '@playwright/test'

const isCI = !!process.env.CI

/**
 * End-to-end and accessibility tests. See https://playwright.dev/docs/test-configuration.
 * Chromium only for now; Firefox and WebKit are added together with the dashboard pages.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: isCI ? [['github'], ['html', { open: 'never' }]] : [['html', { open: 'never' }]],
  use: {
    // On CI, test the production build served by `vite preview`.
    baseURL: isCI ? 'http://localhost:4173' : 'http://localhost:5173',
    locale: 'en-US',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: isCI ? 'npm run preview' : 'npm run dev',
    port: isCI ? 4173 : 5173,
    reuseExistingServer: !isCI,
  },
})
