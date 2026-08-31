import { defineConfig, devices } from '@playwright/test'

const CAPTURE_VIEWPORT = { width: 1440, height: 900 }

/** Dedicated one-viewport configuration for documentation screenshot cases. */
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
  expect: { timeout: 15 * 1000 },
  use: {
    baseURL: 'http://localhost:3000',
    ignoreHTTPSErrors: true,
    waitForLoadState: 'load',
    actionTimeout: 15 * 1000,
    navigationTimeout: 30 * 1000,
  },
  projects: [
    {
      name: 'screenshots',
      testMatch: '**/take-screenshots.spec.ts',
      use: { ...devices['Desktop Chrome'], headless: true, viewport: CAPTURE_VIEWPORT },
    },
  ],
  webServer: [
    {
      command: 'npm run dev -- --mode test',
      url: 'http://localhost:3000',
      reuseExistingServer: !process.env.CI,
      timeout: 120 * 1000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
})
