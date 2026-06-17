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
  globalTeardown: './src/tests/global-teardown.ts',
  /* Run test FILES sequentially - queue tests depend on database setup tests */
  fullyParallel: false,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI; allow one local retry so transient infra failures (e.g. a headed
     single-worker browser dying mid-run → "browser.newContext: ...has been closed")
     self-heal on a fresh browser/context instead of failing the whole run. */
  retries: process.env.CI ? 2 : 1,
  /* Run with a single worker so spec files execute sequentially and do not race on shared state (e.g. setup ID creation). */
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
    baseURL: 'http://localhost:3000',

    /* Slow down operations for visibility during development */
    launchOptions: {
      slowMo: process.env.SLOW_MO ? parseInt(process.env.SLOW_MO) : 0,
    },

    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',

    /* Take screenshot on every test */
    screenshot: 'on',

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
    // Step 0: Database Setups Empty State - asserts the genuine first-run empty state
    // against the REAL backend (global setup deletes all setups at the start of every
    // run). MUST be first, with no dependencies, so it runs before any setup is created.
    {
      name: '0-setup-empty-state',
      testMatch: '**/setup-empty-state.spec.ts',
      use: chromeMaximized,
    },
    // Quick test: WebSocket and SSE connection validation (standalone, no dependencies)
    {
      name: 'websocket-sse-quick',
      testMatch: '**/websocket-sse-connection.spec.ts',
      use: chromeMaximized,
    },
    // Quick test: System Metrics SSE – validates /api/v1/sse/metrics versioned URL (standalone)
    {
      name: 'system-metrics-sse',
      testMatch: '**/system-metrics-sse.spec.ts',
      use: chromeMaximized,
    },
    // Step 1: Settings - Validates REST API connection to backend (MUST run first)
    {
      name: '1-settings',
      testMatch: '**/settings.spec.ts',
      use: chromeMaximized,
    },
    // Step 1b: Settings Health Checks - Ping buttons, auto-ping toggles, Disconnect
    {
      name: '1b-settings-health-checks',
      testMatch: '**/settings-health-checks.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-settings'],
    },
    // Step 1c: Settings Ping Utilities – individual REST/WS/SSE ping buttons
    {
      name: '1c-settings-ping-utilities',
      testMatch: '**/settings-ping-utilities.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-settings'],
    },
    // Step 1d: Settings Auto-Ping – toggle + interval fires background pings
    {
      name: '1d-settings-auto-ping',
      testMatch: '**/settings-auto-ping.spec.ts',
      use: chromeMaximized,
      dependencies: ['1-settings'],
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
    // Step 4b: Database Setup Form Defaults and Port Range Validation
    {
      name: '4b-database-setup-form',
      testMatch: '**/database-setup-form-defaults.spec.ts',
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
    // Step 7b: Queue Details Operations - Pause/Resume, Get Messages, Purge, Delete via UI
    {
      name: '7b-queue-details-operations',
      testMatch: '**/queue-details-operations.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 7c: Queue Details Overview – validates field mapping between backend response and UI
    {
      name: '7c-queue-details-overview',
      testMatch: '**/queue-details-overview.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 7c2: Queue Details Consumers – Consumers tab renders real subscription data
    {
      name: '7c2-queue-details-consumers',
      testMatch: '**/queue-details-consumers.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 7c3: Queue Details Live Messages – non-destructive browse-poll live view (Phase 5)
    {
      name: '7c3-queue-details-live-messages',
      testMatch: '**/queue-details-live-messages.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 7d: Queue Config Create and Display – create-form fields + config card on Overview tab
    {
      name: '7d-queue-config-create-and-display',
      testMatch: '**/queue-config-create-and-display.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 8: Event Store Workflow - Comprehensive event store workflow with event posting (standalone)
    {
      name: '8-event-store-workflow',
      testMatch: '**/event-store-workflow.spec.ts',
      use: chromeMaximized,
      // No dependencies - standalone test that creates event store and posts events
    },
    // Step 8b: Events Filter - Creates event store, seeds 5 events, validates all filter controls
    {
      name: '8b-events-filter',
      testMatch: '**/events-filter.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 8b2: Event Detail Modal - Verifies every card in the detail modal (event info, bitemporal, correlation, metadata, event data)
    {
      name: '8b2-event-detail-modal',
      testMatch: '**/event-detail-modal.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 8c: Events Scope Selector – inline setup selector on Events page
    {
      name: '8c-events-scope-selector',
      testMatch: '**/events-scope-selector.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
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
    // Event Visualization causation data contract (real backend — seeds an isolated
    // event store + causation chain; requires SETUP_ID from setup-prerequisite)
    {
      name: 'visualization-isolated',
      testMatch: '**/visualization-isolated.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Visualization Scope Selector – setup/event-store selectors on Causation Tree and Aggregate Stream
    {
      name: 'visualization-scope-selector',
      testMatch: '**/visualization-scope-selector.spec.ts',
      use: chromeMaximized,
      dependencies: ['4-database-setup'],
    },
    // Causation Tree Page - integration tests (requires setup-prerequisite)
    {
      name: 'causation-tree',
      testMatch: '**/causation-tree.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Aggregate Stream Page - integration tests (requires setup-prerequisite)
    {
      name: 'aggregate-stream',
      testMatch: '**/aggregate-stream.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 10: Overview Scope Selector - Tests Setup selector on Overview page
    {
      name: '10-overview-scope-selector',
      testMatch: '**/overview-setup-selector.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 10a: Scope Selector Persistence – selected setup/queue survives page nav and reload
    {
      name: '10a-scope-selector-persistence',
      testMatch: '**/scope-selector-persistence.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 10b: Overview Setup Details Modal - Tests the "..." details button and modal
    {
      name: '10b-overview-setup-details-modal',
      testMatch: '**/overview-setup-details-modal.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 10c: Overview Recent Activity - Recent Activity table rows, status tags, Queue Overview table + "View All"
    {
      name: '10c-overview-recent-activity',
      testMatch: '**/overview-recent-activity.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 10d: Overview Reconnecting Banner - WS/SSE gold reconnecting tag via route interception
    {
      name: '10d-overview-reconnecting-banner',
      testMatch: '**/overview-reconnecting-banner.spec.ts',
      use: chromeMaximized,
    },
    // Step 10e: Overview Live Stats Update – SSE metrics event updates stats cards/charts
    {
      name: '10e-overview-live-stats-update',
      testMatch: '**/overview-live-stats-update.spec.ts',
      use: chromeMaximized,
    },
    // Step 10f: Overview WS reconnect recovery – drop /ws/monitoring then proxy back → tag returns green (Phase 6)
    {
      name: '10f-overview-reconnect-recovery',
      testMatch: '**/overview-reconnect-recovery.spec.ts',
      use: chromeMaximized,
    },
    // Step 10g: Overview SSE reconnecting banner – aborted SSE → gold "Reconnecting" tag (Phase 6)
    {
      name: '10g-overview-sse-reconnecting-banner',
      testMatch: '**/overview-sse-reconnecting-banner.spec.ts',
      use: chromeMaximized,
    },
    // Step 10h: Overview live stats values – activeConnections (§8.1) and messagesPerSecond (§8.2) invariants
    {
      name: '10h-overview-stats-values',
      testMatch: '**/overview-stats-values.spec.ts',
      use: chromeMaximized,
    },
    // Step 11: Queues Scope Selector - Tests Setup selector on Queues page
    {
      name: '11-queues-scope-selector',
      testMatch: '**/queues-setup-selector.spec.ts',
      use: chromeMaximized,
      dependencies: ['4-database-setup'],
    },
    // Step 11b: Queues Filter and Sort – search box, type/status multi-select, column sort
    {
      name: '11b-queues-filter-sort',
      testMatch: '**/queues-filter-sort.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 12: Event Stores Scope Selector - Tests Setup selector on Event Stores page
    {
      name: '12-event-stores-scope-selector',
      testMatch: '**/event-stores-setup-selector.spec.ts',
      use: chromeMaximized,
      dependencies: ['4-database-setup'],
    },
    // Step 12b: Event Stores Scope Filter – row count updates when scope changes
    {
      name: '12b-event-stores-scope-filter',
      testMatch: '**/event-stores-scope-filter.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 13: Consumer Groups Scope Selectors - Tests Setup+Queue selectors on Consumer Groups page
    {
      name: '13-consumer-groups-scope-selectors',
      testMatch: '**/consumer-groups-scope-selectors.spec.ts',
      use: chromeMaximized,
      dependencies: ['4-database-setup'],
    },
    // Step 13b: Consumer Groups Validation – duplicate name produces error toast
    {
      name: '13b-consumer-groups-validation',
      testMatch: '**/consumer-groups-validation.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 14: Message Browser Scope Selectors - Tests Setup+Queue selectors on Message Browser page
    {
      name: '14-message-browser-scope-selectors',
      testMatch: '**/message-browser-scope-selectors.spec.ts',
      use: chromeMaximized,
      dependencies: ['4-database-setup'],
    },
    // Step 14b: Message Browser - Retrieval, filtering, and SSE Live mode integration tests
    {
      name: '14b-message-browser',
      testMatch: '**/message-browser.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 14b2: Message Browser Advanced Filters – drawer filter inputs applied to table rows
    {
      name: '14b2-message-browser-advanced-filters',
      testMatch: '**/message-browser-advanced-filters.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 14c: Message SSE Stream – Direct API tests (no UI components, pure REST+EventSource)
    {
      name: '14c-message-sse-stream',
      testMatch: '**/message-sse-stream.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 14c2: Message Browser Live mode is non-destructive – browse-poll, no consuming SSE
    {
      name: '14c2-message-browser-nondestructive-live',
      testMatch: '**/message-browser-nondestructive-live.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Step 14d: Queue Updates SSE – Direct API tests for GET /api/v1/sse/queues/:setupId
    {
      name: '14d-queue-updates-sse',
      testMatch: '**/queue-updates-sse.spec.ts',
      use: chromeMaximized,
      dependencies: ['3c-setup-prerequisite'],
    },
    // Error paths: validates that backend error responses surface as UI toasts (standalone, no dependencies)
    {
      name: 'error-paths',
      testMatch: '**/api-error-paths.spec.ts',
      use: chromeMaximized,
    },
    // Header page title mapping (Phase 4a) – route-derived <h1> title incl. dynamic Queue Details (standalone)
    {
      name: 'header-page-title',
      testMatch: '**/header-page-title.spec.ts',
      use: chromeMaximized,
    },
    // Screenshots: regenerates documentation screenshots (run manually)
    {
      name: 'take-screenshots',
      testMatch: '**/take-screenshots.spec.ts',
      use: chromeMaximized,
    },
  ],

  /* Run your local dev server before starting the tests */
  webServer: [
    {
      command: 'npm run dev -- --mode test',
      url: 'http://localhost:3000',
      reuseExistingServer: !process.env.CI,  // Reuse in dev, fresh in CI
      timeout: 120 * 1000,
      stdout: 'pipe',  // Capture dev server output
      stderr: 'pipe',
    }
  ],
})
