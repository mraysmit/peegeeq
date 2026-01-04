import { defineConfig, devices } from '@playwright/test'

const chromeMaximized = {
  ...devices['Desktop Chrome'],
}

/**
 * Comprehensive Playwright configuration for PeeGeeQ Management UI testing
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: './src/tests/e2e',
  /* Global setup with TestContainers - starts PostgreSQL and checks backend */
  globalSetup: './src/tests/global-setup-testcontainers.ts',
  /* Global teardown - stops TestContainers */
  globalTeardown: './src/tests/global-setup-testcontainers.ts',
  /* Run test FILES sequentially - queue tests depend on database setup tests */
  fullyParallel: false,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests on CI. */
  workers: process.env.CI ? 1 : undefined,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
    ['junit', { outputFile: 'test-results/junit.xml' }]
  ],
  /* Global timeout for each test */
  timeout: 30 * 1000,
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
    baseURL: 'http://localhost:3000',

    /* Slow down operations for visibility during development */
    launchOptions: {
      slowMo: 0,
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
    // Quick test: WebSocket and SSE connection validation (standalone, no dependencies)
    {
      name: 'websocket-sse-quick',
      testMatch: '**/websocket-sse-connection.spec.ts',
      use: chromeMaximized,
    },
    // Step 1: Settings - Validates REST API connection to backend (MUST run first)
    {
      name: '1-settings',
      testMatch: '**/settings.spec.ts',
      use: chromeMaximized,
    },
    // Step 2: Connection Status - Tests connection status functionality
    {
      name: '2-connection-status',
      testMatch: '**/connection-status.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-settings'],
    },
    // Step 3: System Integration - Validates overall system integration
    {
      name: '3-system-integration',
      testMatch: '**/system-integration.spec.ts',
      use: chromeMaximized,
      dependencies: ['2-connection-status'],
    },
    // Step 3b: Overview System Status - Tests Overview page system status
    {
      name: '3b-overview-system-status',
      testMatch: '**/overview-system-status.spec.ts',
      use: chromeMaximized,
      dependencies: ['3-system-integration'],
    },
    // Step 3c: Setup Prerequisite - Creates default setup for queue/event store tests
    {
      name: '3c-setup-prerequisite',
      testMatch: '**/setup-prerequisite.spec.ts',
      use: chromeMaximized,
      dependencies: ['3b-overview-system-status'],
    },
    // Step 4: Database Setup - Creates database setup via REST API
    {
      name: '4-database-setup',
      testMatch: '**/database-setup.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 5: Queue Management - Standalone tests (creates own database setup)
    {
      name: '5-queue-management',
      testMatch: '**/queue-management.spec.ts',
      use: chromeMaximized,
      // No dependencies - standalone like event store tests
    },
    // Step 6: Event Store Management - Tests event store CRUD operations (standalone - creates own setup)
    {
      name: '6-event-store-management',
      testMatch: '**/event-store-management.spec.ts',
      use: chromeMaximized,
    },
    // Step 7: Queue Messaging Workflow - Comprehensive queue and messaging tests (standalone)
    {
      name: '7-queue-messaging-workflow',
      testMatch: '**/queue-messaging-workflow.spec.ts',
      use: chromeMaximized,
      // No dependencies - standalone test that creates queue and sends messages
    },
    // Step 8: Event Store Workflow - Comprehensive event store workflow with event posting (standalone)
    {
      name: '8-event-store-workflow',
      testMatch: '**/event-store-workflow.spec.ts',
      use: chromeMaximized,
      // No dependencies - standalone test that creates event store and posts events
    },
    // Step 9: Event Visualization - Tests Causation Tree and Aggregate Stream (standalone)
    {
      name: '9-event-visualization',
      testMatch: '**/event-visualization.spec.ts',
      use: chromeMaximized,
    },
    // Smoke Test: Visualization Tab
    {
      name: 'smoke-visualization-tab',
      testMatch: '**/visualization-tab-smoke.spec.ts',
      use: chromeMaximized,
    },
    // Isolated Visualization Test
    {
      name: 'visualization-isolated',
      testMatch: '**/visualization-isolated.spec.ts',
      use: chromeMaximized,
    },
  ],

  /* Run your local dev server before starting the tests */
  webServer: [
    {
      command: 'npm run dev',
      url: 'http://localhost:3000',
      reuseExistingServer: !process.env.CI,  // Reuse in dev, fresh in CI
      timeout: 120 * 1000,
      stdout: 'pipe',  // Capture dev server output
      stderr: 'pipe',
    }
  ],
})
