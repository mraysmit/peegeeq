import { test, expect } from '../page-objects'
import * as fs from 'fs'
import * as path from 'path'

const API_BASE_URL = 'http://127.0.0.1:8088'

/**
 * Dedicated setup for the Create Queue spec.
 *
 * This spec creates and owns its own setup (rather than reusing the shared
 * e2e-test-setup) so it can freely create and delete queues without disturbing
 * other specs.
 */
const SETUP_ID = 'e2e-create-queue-setup'
const DATABASE_NAME = 'peegeeq_create_queue'

// Queue names must start with a letter/underscore followed by alphanumerics or
// underscores only (no hyphens) — enforced by the backend.
const NATIVE_QUEUE = 'orders_native'
const OUTBOX_QUEUE = 'payments_outbox'
const DUP_QUEUE = 'dup_queue'

interface DbConnectionInfo {
  host: string
  port: number
  username: string
  password: string
}

function readDbConfig(): DbConnectionInfo {
  const filePath = path.join(process.cwd(), 'testcontainers-db.json')
  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'))
  return { host: raw.host, port: raw.port, username: raw.username, password: raw.password }
}

function createSetupBody(db: DbConnectionInfo) {
  return {
    setupId: SETUP_ID,
    databaseConfig: {
      host: db.host,
      port: db.port,
      databaseName: DATABASE_NAME,
      username: db.username,
      password: db.password,
      schema: 'public',
      sslEnabled: false,
      templateDatabase: 'template0',
      encoding: 'UTF8',
    },
    queues: [],
    eventStores: [],
  }
}

/**
 * Create Queue Page E2E tests  (project: 6-create-queue)
 *
 * Full-coverage end-to-end tests for the CreateQueuePage component and every
 * piece of its functionality.  The spec owns a dedicated setup created via the
 * REST API in beforeAll and torn down in afterAll.
 *
 * Coverage:
 *   A. Navigation  – reached from the setup detail "Create queue" button and via
 *                    direct URL; Back and Cancel both return to /setups/:setupId
 *   B. Rendering   – heading shows the setup id, queue-name + implementation-type
 *                    fields render, implementation-type defaults to native
 *   C. Advanced    – the "Advanced settings" collapse expands and exposes the
 *                    maxRetries / visibility-timeout / batch-size / dead-letter /
 *                    FIFO controls
 *   D. Validation  – submitting with an empty queue name shows the inline
 *                    validation message and stays on the page (no queue created)
 *   E. Create native – creating a native queue navigates back to the detail page
 *                      and the queue appears with a native type tag
 *   F. Create outbox – creating an outbox queue (Select switched to outbox)
 *                      appears with an outbox type tag
 *   G. Error path  – creating a duplicate queue name shows the inline error Alert
 *                    and stays on the Create Queue page
 *   H. Delete      – a created queue can be deleted from the detail page
 *
 * Tests run serially because later tests depend on queues created by earlier ones.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Create Queue page', () => {

  let dbConfig: DbConnectionInfo

  test.beforeAll(async ({ request }) => {
    dbConfig = readDbConfig()
    // Start from a clean slate: drop any leftover setup from a previous run.
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${SETUP_ID}`)
    // Create the dedicated setup this spec owns.
    const response = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: createSetupBody(dbConfig),
      timeout: 120000,
    })
    expect(response.ok()).toBeTruthy()
  })

  test.afterAll(async ({ request }) => {
    // Tear down the setup (cascades to all queues created during the spec).
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${SETUP_ID}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  // ── A. Navigation ───────────────────────────────────────────────────────────

  test('setup detail "Create queue" button navigates to the Create Queue page', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailQueues()).toBeVisible({ timeout: 15000 })

    await setupsPage.getDetailCreateQueueButton().click()
    await page.waitForURL(`**/setups/${SETUP_ID}/queues/new`)
    await expect(page.getByTestId('create-queue-page')).toBeVisible()
  })

  test('direct navigation loads the Create Queue page with the setup id in the heading', async ({ createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getPageRoot()).toBeVisible({ timeout: 15000 })
    await expect(createQueuePage.getHeading(SETUP_ID)).toBeVisible()
  })

  test('Back button returns to the setup detail page', async ({ page, createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getPageRoot()).toBeVisible({ timeout: 15000 })

    await createQueuePage.getBackButton().click()
    await page.waitForURL(new RegExp(`/setups/${SETUP_ID}$`))
    await expect(page.getByTestId('setup-detail-page')).toBeVisible({ timeout: 15000 })
  })

  test('Cancel button returns to the setup detail page', async ({ page, createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getPageRoot()).toBeVisible({ timeout: 15000 })

    await createQueuePage.getCancelButton().click()
    await page.waitForURL(new RegExp(`/setups/${SETUP_ID}$`))
    await expect(page.getByTestId('setup-detail-page')).toBeVisible({ timeout: 15000 })
  })

  // ── B. Rendering ────────────────────────────────────────────────────────────

  test('renders the queue-name input and implementation-type select', async ({ createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getQueueNameInput()).toBeVisible({ timeout: 15000 })
    await expect(createQueuePage.getImplementationTypeSelect()).toBeVisible()
  })

  test('implementation-type select defaults to native', async ({ createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getSelectedImplementationType()).toHaveText('native', { timeout: 15000 })
  })

  test('implementation-type select can be switched to outbox', async ({ createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getImplementationTypeSelect()).toBeVisible({ timeout: 15000 })
    await createQueuePage.selectImplementationType('outbox')
    await expect(createQueuePage.getSelectedImplementationType()).toHaveText('outbox')
  })

  // ── C. Advanced settings ────────────────────────────────────────────────────

  test('Advanced settings collapse expands to reveal the advanced fields', async ({ createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getAdvancedSettingsHeader()).toBeVisible({ timeout: 15000 })

    // Advanced fields are hidden until the collapse is expanded.
    await expect(createQueuePage.getMaxRetriesInput()).toBeHidden()

    await createQueuePage.expandAdvancedSettings()

    await expect(createQueuePage.getMaxRetriesInput()).toBeVisible()
    await expect(createQueuePage.getVisibilityTimeoutInput()).toBeVisible()
    await expect(createQueuePage.getBatchSizeInput()).toBeVisible()
    await expect(createQueuePage.getDeadLetterCheckbox()).toBeVisible()
    await expect(createQueuePage.getFifoCheckbox()).toBeVisible()
  })

  // ── D. Validation ───────────────────────────────────────────────────────────

  test('submitting with an empty queue name shows a validation message and stays on the page', async ({ page, createQueuePage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getCreateButton()).toBeVisible({ timeout: 15000 })

    await createQueuePage.getCreateButton().click()

    await expect(createQueuePage.getValidationMessage()).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`/setups/${SETUP_ID}/queues/new`))
  })

  // ── E. Create native queue ──────────────────────────────────────────────────

  test('creating a native queue navigates back to the detail page and shows a native tag', async ({ page, createQueuePage, setupsPage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getQueueNameInput()).toBeVisible({ timeout: 15000 })

    await createQueuePage.fillQueueName(NATIVE_QUEUE)
    // Leave the implementation type at its default (native).
    await createQueuePage.getCreateButton().click()

    // On success the page navigates back to the setup detail page.
    await page.waitForURL(new RegExp(`/setups/${SETUP_ID}$`), { timeout: 30000 })
    await expect(setupsPage.getDetailQueues()).toBeVisible({ timeout: 15000 })

    await expect(setupsPage.getQueueRow(NATIVE_QUEUE)).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getQueueTypeTag(NATIVE_QUEUE)).toContainText('native')
  })

  // ── F. Create outbox queue ──────────────────────────────────────────────────

  test('creating an outbox queue shows an outbox tag on the detail page', async ({ page, createQueuePage, setupsPage }) => {
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getQueueNameInput()).toBeVisible({ timeout: 15000 })

    await createQueuePage.fillQueueName(OUTBOX_QUEUE)
    await createQueuePage.selectImplementationType('outbox')
    await createQueuePage.getCreateButton().click()

    await page.waitForURL(new RegExp(`/setups/${SETUP_ID}$`), { timeout: 30000 })
    await expect(setupsPage.getDetailQueues()).toBeVisible({ timeout: 15000 })

    await expect(setupsPage.getQueueRow(OUTBOX_QUEUE)).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getQueueTypeTag(OUTBOX_QUEUE)).toContainText('outbox')
  })

  // ── G. Error path — duplicate queue name ────────────────────────────────────

  test('creating a duplicate queue name shows the inline error and stays on the page', async ({ page, createQueuePage }) => {
    // First creation succeeds and navigates away.
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getQueueNameInput()).toBeVisible({ timeout: 15000 })
    await createQueuePage.fillQueueName(DUP_QUEUE)
    await createQueuePage.getCreateButton().click()
    await page.waitForURL(new RegExp(`/setups/${SETUP_ID}$`), { timeout: 30000 })

    // Second creation with the same name must fail with an inline error.
    await createQueuePage.goto(SETUP_ID)
    await expect(createQueuePage.getQueueNameInput()).toBeVisible({ timeout: 15000 })
    await createQueuePage.fillQueueName(DUP_QUEUE)
    await createQueuePage.getCreateButton().click()

    await expect(createQueuePage.getErrorAlert()).toBeVisible({ timeout: 30000 })
    await expect(page).toHaveURL(new RegExp(`/setups/${SETUP_ID}/queues/new`))
  })

  // ── H. Delete a queue from the detail page ──────────────────────────────────

  test('a created queue can be deleted from the setup detail page', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getQueueRow(NATIVE_QUEUE)).toBeVisible({ timeout: 15000 })

    await setupsPage.getDeleteQueueButton(NATIVE_QUEUE).click()
    const popconfirmOkBtn = page
      .locator('.ant-popconfirm-buttons button')
      .filter({ hasText: /^Delete$/ })
    await expect(popconfirmOkBtn).toBeVisible({ timeout: 5000 })
    await popconfirmOkBtn.click()

    await expect(setupsPage.getQueueRow(NATIVE_QUEUE)).not.toBeVisible({ timeout: 15000 })
  })

})
