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
 * Create Setup E2E tests.
 *
 * Tests the full "Create Setup" flow from the Message Generator page:
 *   1. Empty state -> "No PeeGeeQ setup found" alert + "Create Setup" button
 *   2. Clicking "Create Setup" navigates to /generator/setup/new
 *   3. Fill form -> submit -> setup created in backend -> navigate to /setups list
 *   4. /setups shows the created setup in the table
 *   5. /generator shows no-queues state after setup exists
 *
 * This spec depends on a running testcontainers backend (started by
 * global-setup-testcontainers.ts). It must run AFTER navigation.spec.ts.
 *
 * NOTE: This spec intentionally creates a setup in the database.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Quick Setup Wizard', () => {

  let dbConfig: DbConnectionInfo

  // Delete e2e-test-setup before the suite so every run starts from clean empty state.
  // Matches management-ui pattern: use Playwright request API to clean up via backend.
  test.beforeAll(async ({ request }) => {
    dbConfig = readDbConfig()
    await request.delete(`${API_BASE_URL}/api/v1/database-setup/${SETUP_ID}`)
    // Ignore response — 404 is fine if it doesn't exist yet
  })

  test.beforeEach(async ({ page }) => {
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('Browser console error:', msg.text())
      }
    })
    page.on('pageerror', error => {
      console.error('Page error:', error.message)
    })
  })

  // -- Appearance / navigation --------------------------------------------------

  test('clicking Create Setup navigates to /generator/setup/new', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getEmptyStateAlert()).toBeVisible({ timeout: 15000 })

    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')
    await expect(generatorPage.getCreateSetupPageHeading()).toBeVisible()
  })

  test('create-setup page shows Setup name, Database name, and Password fields', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await expect(generatorPage.getWizardSetupNameInput()).toBeVisible()
    await expect(generatorPage.getWizardDatabaseNameInput()).toBeVisible()
    await expect(generatorPage.getWizardPasswordInput()).toBeVisible()
  })

  test('create-setup page shows Database Permissions Required warning', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await expect(page.getByText(/Database Permissions Required/i)).toBeVisible()
  })

  test('Cancel button navigates back to generator without creating a setup', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await generatorPage.getWizardCancelButton().click()
    await page.waitForURL('**/generator')
    await expect(generatorPage.getEmptyStateAlert()).toBeVisible()
  })

  test('create-setup page shows validation errors when submitted with empty fields', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await generatorPage.getWizardCreateButton().click()

    await expect(page.getByText(/Please enter a setup name/i)).toBeVisible()
    // Still on the create-setup page after validation failure
    await expect(page).toHaveURL(/\/generator\/setup\/new/)
  })

  // -- Connection details panel ------------------------------------------------

  test('connection details panel is collapsed by default', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await expect(page.getByText('Connection details')).toBeVisible()
    await expect(generatorPage.getWizardHostInput()).not.toBeVisible()

    await generatorPage.getWizardCancelButton().click()
    await page.waitForURL('**/generator')
  })

  test('expanding connection details reveals Host field with default value', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await generatorPage.expandConnectionDetails()

    await expect(generatorPage.getWizardHostInput()).toBeVisible()
    await expect(generatorPage.getWizardHostInput()).toHaveValue('localhost')
    await expect(generatorPage.getWizardPortInput()).toBeVisible()

    await generatorPage.getWizardCancelButton().click()
    await page.waitForURL('**/generator')
  })

  // -- Happy path: create setup ------------------------------------------------

  test('creates a setup and navigates to /setups list', async ({ page, generatorPage, setupsPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getCreateSetupButton()).toBeVisible({ timeout: 15000 })
    await generatorPage.getCreateSetupButton().click()
    await page.waitForURL('**/generator/setup/new')

    await generatorPage.getWizardSetupNameInput().fill(SETUP_ID)
    await generatorPage.getWizardDatabaseNameInput().fill('peegeeq_e2e')
    await generatorPage.getWizardPasswordInput().fill(dbConfig.password)

    // Expand connection details and fill the real testcontainers host/port.
    // (createConfiguration uses the request values directly, not env vars.)
    await generatorPage.expandConnectionDetails()
    await generatorPage.getWizardHostInput().fill(dbConfig.host)
    await generatorPage.getWizardPortInput().fill(String(dbConfig.port))

    await generatorPage.getWizardCreateButton().click()

    // On success, page navigates to /setups and the new setup appears in the table
    await page.waitForURL('**/setups', { timeout: 30000 })
    await expect(setupsPage.getHeading()).toBeVisible()
    await expect(page.locator(`text=${SETUP_ID}`)).toBeVisible({ timeout: 15000 })
  })

  // -- No-queues state ---------------------------------------------------------

  test('generator shows no-queues state after setup is created', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getNoQueuesAlert()).toBeVisible({ timeout: 15000 })
  })

  test('no-queues state shows "Setups page" link', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getNoQueuesAlert()).toBeVisible({ timeout: 15000 })
    await expect(generatorPage.getQueuesPageLink()).toBeVisible()
  })

  test('"Setups page" link navigates to /setups', async ({ page, generatorPage }) => {
    await generatorPage.goto()
    await expect(generatorPage.getQueuesPageLink()).toBeVisible({ timeout: 15000 })

    await generatorPage.getQueuesPageLink().click()
    await page.waitForLoadState('load')

    expect(page.url()).toContain('/setups')
  })

  // -- Backend error handling --------------------------------------------------

  test('create-setup page shows inline error when the backend rejects the request', async ({ page, generatorPage }) => {
    // Submitting an identical setup is idempotent on the backend (returns 201),
    // so to exercise the UI error path we force a real backend failure with
    // invalid database credentials against the reachable testcontainers host.
    await page.goto('/generator/setup/new')
    await page.waitForLoadState('load')

    await generatorPage.getWizardSetupNameInput().fill(`${SETUP_ID}-invalid-creds`)
    await generatorPage.getWizardDatabaseNameInput().fill('peegeeq_e2e_invalid')
    await generatorPage.getWizardPasswordInput().fill('definitely-wrong-password')
    await generatorPage.expandConnectionDetails()
    await generatorPage.getWizardHostInput().fill(dbConfig.host)
    await generatorPage.getWizardPortInput().fill(String(dbConfig.port))
    await generatorPage.getWizardUsernameInput().fill('nonexistent_user_zzz')
    await generatorPage.getWizardCreateButton().click()

    await expect(generatorPage.getWizardErrorAlert()).toBeVisible({ timeout: 30000 })
    // Still on the create-setup page after error
    await expect(page).toHaveURL(/\/generator\/setup\/new/)
  })

})
