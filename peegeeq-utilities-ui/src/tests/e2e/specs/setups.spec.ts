import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import * as fs from 'fs'
import * as path from 'path'

const API_BASE_URL = 'http://127.0.0.1:8088'

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

/**
 * Setups Page E2E tests  (project: 5-setups)
 *
 * Tests the /setups list page end-to-end.  This spec runs AFTER 4-quick-setup,
 * which has already created SETUP_ID ('e2e-test-setup') in the database.
 *
 * Coverage:
 *   A. Navigation  – sidebar link reaches /setups, heading is visible
 *   B. List state  – table renders, created setup is listed, queue/event-store counts visible
 *   C. Detail page – Details navigates to /setups/:id, shows status + queue/event-store names
 *   D. Create Setup button – navigates to /generator/setup/new
 *   E. Full delete flow – create a throwaway setup, verify it appears, delete it,
 *                         verify it is removed from the list
 *
 * Tests within each describe block run serially.
 */

test.describe.configure({ mode: 'serial' })

// ── A. Navigation ─────────────────────────────────────────────────────────────

test.describe('Setups page navigation', () => {

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('Setups sidebar link navigates to /setups', async ({ page, basePage }) => {
    await page.goto('/')
    await basePage.navigateTo('setups')
    await expect(page).toHaveURL(/\/setups/)
  })

  test('direct navigation to /setups loads the page', async ({ page }) => {
    await page.goto('/setups')
    await page.waitForLoadState('load')
    await expect(page).toHaveURL(/\/setups/)
  })

  test('Setups page shows the "Setups" heading', async ({ page }) => {
    await page.goto('/setups')
    await expect(page.getByRole('heading', { name: /^Setups$/i })).toBeVisible()
  })

  test('"Database Setups" card title is visible', async ({ page }) => {
    await page.goto('/setups')
    await expect(page.getByText('Database Setups')).toBeVisible()
  })

  test('Setups nav item is highlighted when on /setups', async ({ page }) => {
    await page.goto('/setups')
    await page.waitForLoadState('load')
    const activeItem = page.locator('.ant-menu-item-selected')
    await expect(activeItem).toContainText('Setups')
  })

})

// ── B. List state ─────────────────────────────────────────────────────────────

test.describe('Setups list', () => {

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('setups table is visible', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
  })

  test('setup created by quick-setup spec appears in the table', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(page.locator(`tr:has-text("${SETUP_ID}")`)).toBeVisible({ timeout: 15000 })
  })

  test('at least one setup is listed', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    const count = await setupsPage.getSetupCount()
    expect(count).toBeGreaterThanOrEqual(1)
  })

  test('table row for e2e-test-setup shows queue and event-store count cells', async ({ page }) => {
    await page.goto('/setups')
    // The row for SETUP_ID should be present and contain Tag cells
    const row = page.locator(`tr:has-text("${SETUP_ID}")`)
    await expect(row).toBeVisible({ timeout: 15000 })
    // Status tag (ACTIVE) should be visible in that row
    await expect(row.locator('.ant-tag').first()).toBeVisible()
  })

  test('setup row shows an ACTIVE status tag', async ({ page }) => {
    await page.goto('/setups')
    const row = page.locator(`tr:has-text("${SETUP_ID}")`)
    await expect(row).toBeVisible({ timeout: 15000 })
    await expect(row.getByText('ACTIVE')).toBeVisible()
  })

  test('"No setups found" alert is NOT shown when setups exist', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getNoSetupsAlert()).not.toBeVisible()
  })

  test('Refresh button reloads the list and keeps the setup visible', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await setupsPage.getRefreshButton().click()
    await expect(page.locator(`tr:has-text("${SETUP_ID}")`)).toBeVisible({ timeout: 15000 })
  })

})

// ── C. Detail page ────────────────────────────────────────────────────────────

test.describe('Setup detail page', () => {

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('clicking Details navigates to the setup detail page', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await setupsPage.getViewDetailsButton(SETUP_ID).click()
    await page.waitForURL(`**/setups/${SETUP_ID}`)
    await expect(setupsPage.getDetailPageRoot()).toBeVisible({ timeout: 10000 })
  })

  test('detail page heading includes the setup ID', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(page.getByRole('heading', { name: new RegExp(SETUP_ID) })).toBeVisible({ timeout: 10000 })
  })

  test('detail page shows Setup ID in the descriptions', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailDescriptions()).toBeVisible({ timeout: 10000 })
    await expect(setupsPage.getDetailDescriptions().getByText(SETUP_ID, { exact: true })).toBeVisible()
  })

  test('detail page shows a Status row', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailDescriptions()).toBeVisible({ timeout: 10000 })
    await expect(setupsPage.getDetailDescriptions().getByText('Status')).toBeVisible()
  })

  test('detail page shows the Queues section', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailQueues()).toBeVisible({ timeout: 10000 })
  })

  test('detail page shows the Event stores section', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailEventStores()).toBeVisible({ timeout: 10000 })
  })

  test('Refresh button on the detail page reloads details', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailDescriptions()).toBeVisible({ timeout: 10000 })
    await setupsPage.getDetailRefreshButton().click()
    await expect(setupsPage.getDetailDescriptions()).toBeVisible({ timeout: 10000 })
  })

  test('Back button on the detail page returns to /setups', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(SETUP_ID)
    await expect(setupsPage.getDetailBackButton()).toBeVisible({ timeout: 10000 })
    await setupsPage.getDetailBackButton().click()
    await page.waitForURL('**/setups')
    await expect(page.getByRole('heading', { name: /^Setups$/i })).toBeVisible()
  })

})

// ── D. Create Setup button ────────────────────────────────────────────────────

test.describe('Create Setup navigation from Setups page', () => {

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('Create Setup button navigates to /generator/setup/new', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await setupsPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')
    await expect(page.getByRole('heading', { name: /^Create Setup$/i })).toBeVisible()
  })

  test('Back button on Create Setup page returns to /setups', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await setupsPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')
    await page.getByTestId('back-button').click()
    await page.waitForURL('**/setups')
    await expect(page.getByRole('heading', { name: /^Setups$/i })).toBeVisible()
  })

})

// ── E. Full delete flow ───────────────────────────────────────────────────────

test.describe('Delete setup', () => {

  const DELETE_SETUP_ID = 'e2e-delete-me'
  let dbConfig: DbConnectionInfo

  // Clean up throwaway setup before the suite so re-runs start from a clean state
  test.beforeAll(async ({ request }) => {
    dbConfig = readDbConfig()
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${DELETE_SETUP_ID}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('creates a throwaway setup, verifies it in the list, then deletes it', async ({ page, setupsPage, generatorPage }) => {
    // 1. Navigate to Create Setup from the Setups page
    await setupsPage.goto()
    await setupsPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    // 2. Fill the form with real testcontainers credentials
    await generatorPage.getWizardSetupNameInput().fill(DELETE_SETUP_ID)
    await generatorPage.getWizardDatabaseNameInput().fill('peegeeq_delete_me')
    await generatorPage.getWizardPasswordInput().fill(dbConfig.password)
    // Expand connection details to fill actual testcontainers host/port
    await generatorPage.expandConnectionDetails()
    await generatorPage.getWizardHostInput().fill(dbConfig.host)
    await generatorPage.getWizardPortInput().fill(String(dbConfig.port))
    await generatorPage.getWizardCreateButton().click()

    // 3. Redirects to /setups and the new setup appears
    await page.waitForURL('**/setups', { timeout: 30000 })
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(page.locator(`tr:has-text("${DELETE_SETUP_ID}")`)).toBeVisible({ timeout: 15000 })

    // 4. Click Delete button — Popconfirm appears
    await setupsPage.getDeleteButton(DELETE_SETUP_ID).click()
    const popconfirmOkBtn = page.locator('.ant-popconfirm-buttons button').filter({ hasText: /^Delete$/ })
    await expect(popconfirmOkBtn).toBeVisible({ timeout: 5000 })

    // 5. Confirm deletion
    await popconfirmOkBtn.click()

    // 6. Table reloads — setup is gone
    await expect(page.locator(`tr:has-text("${DELETE_SETUP_ID}")`)).not.toBeVisible({ timeout: 15000 })
  })

  test('cancelling the delete Popconfirm leaves the setup in the list', async ({ page, setupsPage }) => {
    // Assumes e2e-test-setup still exists from quick-setup spec
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await setupsPage.getDeleteButton(SETUP_ID).click()

    const popconfirmCancelBtn = page.locator('.ant-popconfirm-buttons button').filter({ hasText: /^Cancel$/ })
    await expect(popconfirmCancelBtn).toBeVisible({ timeout: 5000 })
    await popconfirmCancelBtn.click()

    // Setup must still be present
    await expect(page.locator(`tr:has-text("${SETUP_ID}")`)).toBeVisible({ timeout: 10000 })
  })

})

// ── F. Multiple setups in the list ────────────────────────────────────────────
//
// Verifies the list renders MORE THAN ONE setup at the same time, with correct
// per-row data for each. Two throwaway setups are created directly via the REST
// API (fast and deterministic), asserted to coexist in the UI table, then removed.

test.describe('Multiple setups', () => {

  const MULTI_SETUP_A = 'e2e-multi-a'
  const MULTI_SETUP_B = 'e2e-multi-b'
  let dbConfig: DbConnectionInfo

  function createSetupBody(setupId: string, databaseName: string) {
    return {
      setupId,
      databaseConfig: {
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName,
        username: dbConfig.username,
        password: dbConfig.password,
        schema: 'public',
        sslEnabled: false,
        templateDatabase: 'template0',
        encoding: 'UTF8',
      },
      queues: [],
      eventStores: [],
    }
  }

  test.beforeAll(async ({ request }) => {
    dbConfig = readDbConfig()
    // Clean any leftovers from a prior run, then create both setups fresh.
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${MULTI_SETUP_A}`)
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${MULTI_SETUP_B}`)
    const respA = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: createSetupBody(MULTI_SETUP_A, 'peegeeq_multi_a'),
      timeout: 120000,
    })
    expect(respA.ok()).toBeTruthy()
    const respB = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: createSetupBody(MULTI_SETUP_B, 'peegeeq_multi_b'),
      timeout: 120000,
    })
    expect(respB.ok()).toBeTruthy()
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${MULTI_SETUP_A}`)
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${MULTI_SETUP_B}`)
  })

  test.beforeEach(async ({ page }) => {
    page.on('pageerror', error => console.error('Page error:', error.message))
  })

  test('both setups appear in the table at the same time', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getSetupRow(MULTI_SETUP_A)).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getSetupRow(MULTI_SETUP_B)).toBeVisible({ timeout: 15000 })
  })

  test('the list contains at least three setups (e2e-test-setup + the two new ones)', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getSetupRow(MULTI_SETUP_A)).toBeVisible({ timeout: 15000 })
    const count = await setupsPage.getSetupCount()
    expect(count).toBeGreaterThanOrEqual(3)
  })

  test('each new setup row shows an ACTIVE status tag', async ({ page, setupsPage }) => {
    await setupsPage.goto()
    await expect(setupsPage.getSetupsTable()).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getSetupRow(MULTI_SETUP_A).getByText('ACTIVE')).toBeVisible({ timeout: 15000 })
    await expect(setupsPage.getSetupRow(MULTI_SETUP_B).getByText('ACTIVE')).toBeVisible({ timeout: 15000 })
  })

  test('each new setup has its own working detail page', async ({ page, setupsPage }) => {
    await setupsPage.gotoDetail(MULTI_SETUP_A)
    await expect(setupsPage.getDetailDescriptions().getByText(MULTI_SETUP_A, { exact: true })).toBeVisible({ timeout: 10000 })

    await setupsPage.gotoDetail(MULTI_SETUP_B)
    await expect(setupsPage.getDetailDescriptions().getByText(MULTI_SETUP_B, { exact: true })).toBeVisible({ timeout: 10000 })
  })

})

// ── G. Duplicate setup ID behavior ────────────────────────────────────────────
//
// Characterization test that pins down what the backend does when the SAME setup
// ID is created twice. The PeeGeeQ create handler returns 409 only when the
// underlying error message contains "already exists"; in practice the create can
// be idempotent and return 201. This test records the actual contract so the
// Create Setup form's error handling is verified against real behavior.

test.describe('Duplicate setup ID', () => {

  const DUP_SETUP_ID = 'e2e-dup-setup'
  let dbConfig: DbConnectionInfo

  function createSetupBody() {
    return {
      setupId: DUP_SETUP_ID,
      databaseConfig: {
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName: 'peegeeq_dup',
        username: dbConfig.username,
        password: dbConfig.password,
        schema: 'public',
        sslEnabled: false,
        templateDatabase: 'template0',
        encoding: 'UTF8',
      },
      queues: [],
      eventStores: [],
    }
  }

  test.beforeAll(async ({ request }) => {
    dbConfig = readDbConfig()
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${DUP_SETUP_ID}`)
    const first = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: createSetupBody(),
      timeout: 120000,
    })
    expect(first.ok()).toBeTruthy()
  })

  test.afterAll(async ({ request }) => {
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${DUP_SETUP_ID}`)
  })

  test('re-creating an existing setup returns a documented status (201 idempotent or 409 conflict)', async ({ request }) => {
    const second = await request.post(`${API_BASE_URL}/api/v1/database-setup/create`, {
      data: createSetupBody(),
      timeout: 120000,
    })
    const status = second.status()
    const bodyText = await second.text()
    // Log the actual observed contract so the behavior is recorded in test output.
    console.log(`[duplicate-setup] re-create status=${status} body=${bodyText}`)

    expect([201, 409]).toContain(status)
    if (status === 409) {
      // When the backend reports a conflict, the body must carry an "already exists" error
      // so the Create Setup form can surface a meaningful message.
      expect(bodyText.toLowerCase()).toContain('already exists')
    }
  })

})
