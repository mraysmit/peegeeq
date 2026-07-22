import { defineConfig, devices } from '@playwright/test'

/**
 * Tall viewport so full-page screenshots capture all content: the app layout is
 * height:100vh with an internally-scrolling content area, so `fullPage` alone
 * only ever yields one viewport's worth.
 */
const CAPTURE_VIEWPORT = { width: 1440, height: 2200 }

/**
 * Dedicated Playwright configuration for capturing documentation screenshots.
 *
 * This config is intentionally separate from `playwright.config.ts` so the
 * screenshot run does NOT execute as part of the normal e2e suite. It reuses
 * the same TestContainers global setup/teardown and Vite dev server, but runs
 * only `screenshots.spec.ts` with a tall viewport so full-page captures show
 * all page content without internal scrolling.
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
  reporter: [['list']],
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
      testMatch: '**/screenshots.spec.ts',
      /* CAPTURE_VIEWPORT must come AFTER the devices spread: devices['Desktop
         Chrome'] carries its own 1280x720 viewport, and a project-level `use`
         overrides the config-level one wholesale. Declaring the tall viewport
         at config level (as this file did until 2026-07-22) was silently
         discarded, so full-page captures were clipped at 720px and pages like
         the Message Generator cut off mid-payload. */
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
