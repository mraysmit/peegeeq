import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'

/**
 * API Error-Path E2E Tests
 *
 * Uses page.route() to intercept axios API calls and inject backend error responses
 * (400/404/503). Asserts that Ant Design .ant-message-error toasts appear correctly.
 *
 * Standalone: no TestContainers dependency — all backend traffic is intercepted.
 */

// ---------------------------------------------------------------------------
// Shared mock helpers
// ---------------------------------------------------------------------------

/**
 * Intercept queue-related endpoints.
 *
 * - GET  /api/v1/management/queues* → 200 empty queue list (RTK Query page load)
 * - POST /api/v1/management/queues  → injected error status + body
 * - GET  /api/v1/setups*            → 200 with a single fake setup
 */
async function routeQueueEndpoints(
  page: Page,
  postStatus: number,
  postErrorText: string,
): Promise<void> {
  const timestamp = new Date().toISOString()
  const emptyQueueList = { queues: [], total: 0, queueCount: 0, page: 1, pageSize: 10 }
  const fakeSetups = { count: 1, setupIds: ['test-setup'] }
  const errorBody = { error: postErrorText, timestamp }

  // Intercept GET /api/v1/setups* → fake setup list (used by QueuesEnhanced.fetchSetups)
  await page.route('**/api/v1/setups*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(fakeSetups),
    })
  })

  // Intercept /api/v1/management/queues* — differentiate GET (list) vs POST (create)
  await page.route('**/api/v1/management/queues**', (route: Route) => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status: postStatus,
        contentType: 'application/json',
        body: JSON.stringify(errorBody),
      })
    } else {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(emptyQueueList),
      })
    }
  })
}

/**
 * Open the Create Queue modal, fill in queue name and select the setup, then submit.
 */
async function openQueueCreateAndSubmit(page: Page): Promise<void> {
  await page.getByTestId('create-queue-btn').click()
  await page.locator('.ant-modal').waitFor({ state: 'visible' })

  await page.getByTestId('queue-name-input').fill('error-test-queue')

  // Select the setup from the dropdown
  const setupSelect = page.getByTestId('queue-setup-select')
  await expect(setupSelect).toBeVisible()
  await setupSelect.click()

  const dropdown = page.locator('.ant-select-dropdown')
    .filter({ hasNot: page.locator('.ant-select-dropdown-hidden') })
    .filter({ hasText: 'test-setup' })
    .last()
  await expect(dropdown).toBeVisible()

  await dropdown
    .locator('.ant-select-item-option-content')
    .filter({ hasText: 'test-setup' })
    .first()
    .click()

  // Submit modal
  await page.locator('.ant-modal-footer .ant-btn-primary').click()
}

// ---------------------------------------------------------------------------
// Phase 1 — Queue create error paths
// ---------------------------------------------------------------------------

test.describe('Queue create — API error paths', () => {
  test.describe.configure({ mode: 'serial' })

  test('E1: createQueue_503_showsToastAndModalStaysOpen', async ({ page }) => {
    await routeQueueEndpoints(page, 503, 'Service unavailable')
    await page.goto('/queues')
    await expect(page.getByTestId('create-queue-btn')).toBeVisible({ timeout: 10000 })

    await openQueueCreateAndSubmit(page)

    // Toast must show the injected server error text
    await expect(
      page.locator('.ant-message-error').filter({ hasText: 'Service unavailable' }).first(),
    ).toBeVisible({ timeout: 5000 })

    // Modal must remain open (no setIsModalVisible(false) in catch block)
    await expect(page.locator('.ant-modal')).toBeVisible()
  })

  test('E2: createQueue_404_showsToastAndModalStaysOpen', async ({ page }) => {
    await routeQueueEndpoints(page, 404, 'Setup not found: test-setup')
    await page.goto('/queues')
    await expect(page.getByTestId('create-queue-btn')).toBeVisible({ timeout: 10000 })

    await openQueueCreateAndSubmit(page)

    // Toast must contain the injected server error text
    await expect(
      page.locator('.ant-message-error').filter({ hasText: 'Setup not found' }).first(),
    ).toBeVisible({ timeout: 5000 })

    // Modal must remain open
    await expect(page.locator('.ant-modal')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Phase 2 — Database Setup create error paths
// ---------------------------------------------------------------------------

/**
 * Intercept database-setup endpoints.
 *
 * - GET  /api/v1/setups* → 200 empty list (no existing rows in table)
 * - POST /api/v1/database-setup/create → injected error status + body
 */
async function routeSetupEndpoints(
  page: Page,
  postStatus: number,
  postErrorText: string,
): Promise<void> {
  const timestamp = new Date().toISOString()
  const emptySetupList = { count: 0, setupIds: [] }
  const errorBody = { error: postErrorText, timestamp }

  // Empty setup list → no per-ID detail requests and no rows in table
  await page.route('**/api/v1/setups*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(emptySetupList),
    })
  })

  // Intercept the create POST
  await page.route('**/api/v1/database-setup/create', (route: Route) => {
    route.fulfill({
      status: postStatus,
      contentType: 'application/json',
      body: JSON.stringify(errorBody),
    })
  })
}

/**
 * Open the Create Setup modal, fill all required fields, then submit.
 */
async function openSetupCreateAndSubmit(page: Page): Promise<void> {
  await page.getByRole('button', { name: /create setup/i }).click()
  await page.locator('.ant-modal').waitFor({ state: 'visible' })

  await page.getByLabel(/setup id/i).fill('error-test-setup')
  await page.getByLabel(/host/i).fill('localhost')
  await page.getByLabel(/port/i).fill('5432')
  await page.getByLabel(/database name/i).fill('testdb')
  await page.getByLabel(/username/i).fill('testuser')
  await page.getByLabel(/password/i).fill('testpass')

  // Submit modal
  await page.locator('.ant-modal-footer .ant-btn-primary').click()
}

test.describe('Database setup create — API error paths', () => {
  test.describe.configure({ mode: 'serial' })

  test('E3: createSetup_503_showsServerErrorToast', async ({ page }) => {
    await routeSetupEndpoints(page, 503, 'Database connection failed')
    await page.goto('/database-setups')
    await expect(page.getByRole('button', { name: /create setup/i })).toBeVisible({ timeout: 10000 })

    await openSetupCreateAndSubmit(page)

    // DatabaseSetups.handleModalOk shows error.response?.data?.error directly (no prefix)
    await expect(
      page.locator('.ant-message-error').filter({ hasText: 'Database connection failed' }).first(),
    ).toBeVisible({ timeout: 5000 })

    // Modal must remain open (no setIsModalVisible(false) in catch block)
    await expect(page.locator('.ant-modal')).toBeVisible()
  })

  test('E4: createSetup_400_showsValidationErrorToast', async ({ page }) => {
    await routeSetupEndpoints(page, 400, 'setupId is required')
    await page.goto('/database-setups')
    await expect(page.getByRole('button', { name: /create setup/i })).toBeVisible({ timeout: 10000 })

    await openSetupCreateAndSubmit(page)

    await expect(
      page.locator('.ant-message-error').filter({ hasText: 'setupId is required' }).first(),
    ).toBeVisible({ timeout: 5000 })

    // Modal must remain open
    await expect(page.locator('.ant-modal')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Phase 3 — Database Setup delete error path
// ---------------------------------------------------------------------------

/**
 * Intercept endpoints for the delete-setup scenario.
 *
 * The DatabaseSetups page on load calls:
 *   1. GET /api/v1/setups        → list of setup IDs
 *   2. GET /api/v1/setups/{id}   → per-ID detail (one call per ID)
 *
 * We mock one fake setup so the table renders a row, then intercept the DELETE.
 */
async function routeDeleteSetupEndpoints(page: Page): Promise<void> {
  const fakeSetupId = 'fake-setup'

  // GET /api/v1/setups (exact list endpoint)
  await page.route('**/api/v1/setups', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ count: 1, setupIds: [fakeSetupId] }),
      })
    } else {
      route.continue()
    }
  })

  // GET /api/v1/setups/{id} (per-ID detail)
  await page.route('**/api/v1/setups/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          setupId: fakeSetupId,
          host: 'localhost',
          port: 5432,
          databaseName: 'testdb',
          schema: fakeSetupId,
          queueFactories: [],
          eventStores: [],
          status: 'ACTIVE',
        }),
      })
    } else {
      route.continue()
    }
  })

  // DELETE /api/v1/database-setup/{id}
  await page.route('**/api/v1/database-setup/**', (route: Route) => {
    if (route.request().method() === 'DELETE') {
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Cannot delete setup: service unavailable', timestamp: new Date().toISOString() }),
      })
    } else {
      route.continue()
    }
  })
}

test.describe('Database setup delete — API error path', () => {
  test('E5: deleteSetup_503_showsGenericErrorToast', async ({ page }) => {
    await routeDeleteSetupEndpoints(page)
    await page.goto('/database-setups')

    // Wait for the fake-setup row to appear in the table
    const row = page.locator('tr').filter({ hasText: 'fake-setup' })
    await expect(row).toBeVisible({ timeout: 10000 })

    // Click the MoreOutlined (⋯) actions button in that row
    await row.locator('.anticon-more').click()

    // Click Delete in the dropdown menu
    await page.locator('.ant-dropdown-menu-item').filter({ hasText: 'Delete' }).click()

    // Confirm the Modal.confirm dialog — the danger "Delete" button is the primary button
    const confirmBtn = page.locator('.ant-modal-confirm-btns .ant-btn-dangerous')
    await expect(confirmBtn).toBeVisible({ timeout: 5000 })
    await confirmBtn.click()

    // DatabaseSetups.handleDeleteSetup now shows server error text
    await expect(
      page.locator('.ant-message-error').filter({ hasText: 'Cannot delete setup: service unavailable' }).first(),
    ).toBeVisible({ timeout: 5000 })
  })
})
