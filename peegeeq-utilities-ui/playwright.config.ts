import { defineConfig, devices } from '@playwright/test'

const chromeMaximized = {
  ...devices['Desktop Chrome'],
  headless: false,
}

/**
 * Comprehensive Playwright configuration for PeeGeeQ Utilities UI testing
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: './src/tests/e2e',
  /* Global setup with TestContainers - starts PostgreSQL and checks backend */
  globalSetup: './src/tests/global-setup-testcontainers.ts',
  /* Global teardown - stops TestContainers */
  globalTeardown: './src/tests/global-teardown.ts',
  /* Run test FILES sequentially - navigation tests run before overview tests */
  fullyParallel: false,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Run with a single worker so spec files execute sequentially and do not race on shared state. */
  workers: 1,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
    ['junit', { outputFile: 'test-results/junit.xml' }]
  ],
  /* Global timeout for each test */
  timeout: 60 * 1000,
  /* Expect timeout for assertions */
  expect: {
    timeout: 10 * 1000,
    /* Visual regression settings */
    toHaveScreenshot: {
      maxDiffPixels: 200,    // Allow up to 200 different pixels (accounts for timestamps, dynamic content)
      threshold: 0.2,        // 20% tolerance for color differences
    },
  },
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('/')`. */
    baseURL: 'http://localhost:3001',

    /* Slow down operations for visibility during development */
    launchOptions: {
      slowMo: process.env.SLOW_MO ? parseInt(process.env.SLOW_MO) : 0,
    },

    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',

    /* Take screenshot on failure */
    screenshot: 'only-on-failure',

    /* Record video on failure */
    video: 'on-first-retry',

    /* Ignore HTTPS errors */
    ignoreHTTPSErrors: true,

    /* Wait for network idle before considering navigation complete */
    waitForLoadState: 'load',

    /* Action timeout */
    actionTimeout: 10 * 1000,

    /* Navigation timeout */
    navigationTimeout: 30 * 1000,
  },

  /* Configure projects for major browsers */
  projects: [
    // Step 1: Navigation - Validates app shell and all sidebar navigation links
    {
      name: '1-navigation',
      testMatch: '**/navigation.spec.ts',
      use: chromeMaximized,
    },
    // Step 2: Overview - Tests Overview page heading, status, stats, charts, and queue table
    {
      name: '2-overview',
      testMatch: '**/overview.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-navigation'],
    },
    // Step 3: Generator - Tests Message Generator, Template Manager, Value Lists, and Tools pages
    {
      name: '3-generator',
      testMatch: '**/generator.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-navigation'],
    },
    // Step 4: Setups - /setups list page, detail page, and connect navigation.
    // Provisions its own SETUP_ID via REST (admin path) in beforeAll, so it depends on
    // 3-generator only for ordering (generator's empty-state tests must run before any
    // setup exists).
    {
      name: '5-setups',
      testMatch: '**/setups.spec.ts',
      use: chromeMaximized,
      dependencies: ['3-generator'],
    },
    // Connect-to-existing-setup — independent, real backend (provision → detach → connect via UI).
    // Runs headless and owns its own throwaway setup, so it has no ordering dependency.
    {
      name: 'connect',
      testMatch: '**/connect-setup.spec.ts',
      use: { ...devices['Desktop Chrome'] },
    },
    // Generator run (B.5/B.6) — full Zone A–E flow with real publishing.
    // Owns its own throwaway setup + queue; depends on 3-generator only for
    // ordering (the generator empty-state tests must run before any setup exists).
    {
      name: '4-generator-run',
      testMatch: '**/generator-run.spec.ts',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['3-generator'],
    },
    // Scheduled runs (SCH.7) — schedule via the UI, real firing with real
    // publishing, history/filter/template journey, and the missed-run rule.
    // Owns its own throwaway setup + queue.
    {
      name: '6-generator-schedules',
      testMatch: '**/generator-schedule.spec.ts',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['3-generator'],
    },
  ],

  /* Run your local dev server before starting the tests */
  webServer: [
    {
      command: 'npm run dev',
      url: 'http://localhost:3001',
      reuseExistingServer: !process.env.CI,  // Reuse in dev, fresh in CI
      timeout: 120 * 1000,
      stdout: 'pipe',  // Capture dev server output
      stderr: 'pipe',
    }
  ],
})
