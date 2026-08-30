import { defineConfig, devices } from '@playwright/test'

/** The one viewport used by every Utilities screenshot case. */
const CAPTURE_VIEWPORT = { width: 1440, height: 900 }

/**
 * Dedicated Playwright configuration for capturing documentation screenshots.
 *
 * This config is intentionally separate from `playwright.config.ts` so the
 * screenshot run does NOT execute as part of the normal e2e suite. It reuses
 * the same TestContainers global setup/teardown and Vite dev server, but runs
 * only the screenshot suites at the fixed desktop viewport. Each named
 * functionality test attaches both a focused-element and a viewport capture.
 *
 * Run with:
 *   npx playwright test --config=playwright.screenshots.config.ts
 */
export default defineConfig({
  testDir: './src/tests/e2e',
  globalSetup: './src/tests/global-setup-testcontainers.ts',
  globalTeardown: './src/tests/global-teardown.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report/screenshots', open: 'never' }],
  ],
  timeout: 180 * 1000,
  expect: {
    timeout: 15 * 1000,
  },
  use: {
    baseURL: 'http://localhost:3001',
    ignoreHTTPSErrors: true,
    waitForLoadState: 'load',
    actionTimeout: 15 * 1000,
    navigationTimeout: 30 * 1000,
  },
  projects: [
    {
      name: 'screenshots',
      testMatch: [
        '**/screenshots.spec.ts',
        '**/functionality-screenshots.spec.ts',
        '**/functional-state-screenshots.spec.ts',
      ],
      /* The explicit viewport follows the device spread so Desktop Chrome's
         1280x720 default cannot override the suite's one fixed viewport. */
      use: { ...devices['Desktop Chrome'], headless: true, viewport: CAPTURE_VIEWPORT },
    },
  ],
  webServer: [
    {
      command: 'npm run dev',
      url: 'http://localhost:3001',
      reuseExistingServer: !process.env.CI,
      timeout: 120 * 1000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
})
