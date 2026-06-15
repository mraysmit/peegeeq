import { test, expect } from '../page-objects'
import * as fs from 'fs'
import { SETUP_ID } from '../test-constants'

/**
 * Database Setup Tests
 *
 * Tests database setup creation and management.
 * These tests run sequentially to ensure proper setup state.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Database Setup', () => {

  test.beforeEach(async ({ page }) => {
    // Capture console errors for debugging
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('❌ Browser console error:', msg.text())
      }
    })
    page.on('pageerror', error => {
      console.error('❌ Page error:', error.message)
    })
  })

  test.describe('Setup Creation', () => {

    test('should create database setup through UI', async ({ page, databaseSetupsPage }) => {
      // Read TestContainers connection details
      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))

      await page.goto('/')
      await databaseSetupsPage.goto()

      // Verify we're on the database setups page
      expect(page.url()).toContain('/database-setups')

      // Skip creation if the setup already exists (idempotent across re-runs)
      const alreadyExists = await databaseSetupsPage.setupExists(SETUP_ID)
      if (alreadyExists) return

      // Create setup through UI
      await databaseSetupsPage.createSetup({
        setupId: SETUP_ID,
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName: 'e2e_test_db',
        username: dbConfig.username,
        password: dbConfig.password,
        schema: 'public'
      })

      // Verify setup was created
      const exists = await databaseSetupsPage.setupExists(SETUP_ID)
      expect(exists).toBeTruthy()

      // Verify setup appears in table
      const setupCount = await databaseSetupsPage.getSetupCount()
      expect(setupCount).toBeGreaterThanOrEqual(1)
    })

    test('should display created setup in table', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()

      // Setup should exist from previous test
      const exists = await databaseSetupsPage.setupExists(SETUP_ID)
      expect(exists).toBeTruthy()

      // Table should show the setup
      await expect(databaseSetupsPage.getSetupsTable()).toBeVisible()
      await expect(page.locator(`tr:has-text("${SETUP_ID}")`)).toBeVisible()
    })
  })

  test.describe('Setup Management', () => {

    test('should navigate to database setups page', async ({ page, basePage }) => {
      await page.goto('/')
      await basePage.navigateTo('database-setups')
      expect(page.url()).toContain('/database-setups')
    })

    test('should display database setups table', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()

      // Table should be visible
      await expect(databaseSetupsPage.getSetupsTable()).toBeVisible()
    })
  })

  // Empty-state coverage moved to setup-empty-state.spec.ts (project 0-setup-empty-state),
  // which asserts the genuine first-run empty state against the REAL backend before any
  // setup is created — no mock. It cannot live here: this spec depends on a created setup.

  test.describe('Form Validation', () => {

    test('create modal should show validation errors when submitted empty', async ({ page, databaseSetupsPage }) => {
      // Submitting empty triggers form.validateFields() rejection which the component
      // logs via console.error — remove the beforeEach listener so it does not appear.
      await page.removeAllListeners('console')

      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.clickCreate()

      await expect(page.locator('.ant-modal')).toBeVisible()

      // Submit without filling required fields
      await page.locator('.ant-modal-footer .ant-btn-primary').click()

      // Required field errors (Setup ID, Database Name, Username, Password) must appear
      const modal = page.locator('.ant-modal')
      await expect(modal.locator('.ant-form-item-explain-error').first()).toBeVisible({ timeout: 5000 })

      await page.keyboard.press('Escape')
    })

    test('cancel button should close the create modal without creating anything', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.clickCreate()

      await expect(page.locator('.ant-modal')).toBeVisible()

      await page.locator('.ant-modal-footer .ant-btn:not(.ant-btn-primary)').click()

      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 5000 })
    })

    test('API error during create should show error toast and keep modal open', async ({ page, databaseSetupsPage }) => {
      // NO-MOCK POLICY EXCEPTION (fault injection, sanctioned 2026-06-15):
      // A healthy backend will not return 500 to a valid create request on demand, so
      // the only way to exercise the error-toast / modal-stays-open path is to inject
      // the failure. This is deliberate fault injection, NOT data mocking.
      //
      // Injecting a 500 — remove the beforeEach console error listener so that the
      // expected browser errors do not pollute the test output.
      await page.removeAllListeners('console')

      await page.route('**/api/v1/database-setup/create', route =>
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Internal server error' }),
        })
      )

      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.clickCreate()
      await expect(page.locator('.ant-modal')).toBeVisible()

      await page.getByLabel('Setup ID').fill('error-test')
      await page.getByLabel('Database Name').fill('error_db')
      await page.getByLabel('Username').fill('postgres')
      await page.getByLabel('Password').fill('secret')

      await page.locator('.ant-modal-footer .ant-btn-primary').click()

      await expect(page.locator('.ant-message-error')).toBeVisible({ timeout: 5000 })
      // Modal must remain open — the error did not dismiss it
      await expect(page.locator('.ant-modal')).toBeVisible()

      await page.keyboard.press('Escape')
    })
  })

  test.describe('Setup Actions', () => {

    test('delete setup removes it from the list', async ({ page, databaseSetupsPage }) => {
      // Real backend, no stub. Create a dedicated throwaway setup, delete it through
      // the UI, and verify it is actually gone — both from the table and from the real
      // GET /api/v1/setups response. Uses a unique id so it never touches SETUP_ID
      // (which the rest of the suite depends on).
      test.setTimeout(120000) // setup creation provisions a real database + migrations

      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
      const throwawayId = `del_test_${Date.now()}`

      const createResp = await page.request.post('/api/v1/database-setup/create', {
        data: {
          setupId: throwawayId,
          databaseConfig: {
            host: dbConfig.host,
            port: dbConfig.port,
            databaseName: `del_test_db_${Date.now()}`,
            username: dbConfig.username,
            password: dbConfig.password,
            schema: 'public',
            templateDatabase: 'template0',
            encoding: 'UTF8',
          },
          queues: [],
          eventStores: [],
        },
        timeout: 90000,
      })
      if (!createResp.ok()) {
        throw new Error(`Create throwaway setup failed: ${createResp.status()} ${await createResp.text()}`)
      }

      await page.goto('/database-setups')
      await page.waitForLoadState('networkidle')

      const row = page.locator('.ant-table-row').filter({ hasText: throwawayId })
      await expect(row).toBeVisible({ timeout: 10000 })

      // Delete through the UI: action menu → Delete Setup → confirm
      await databaseSetupsPage.getActionButton(throwawayId).click()

      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('Delete Setup').click()

      const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
      await expect(confirmBtn).toBeVisible({ timeout: 3000 })
      await confirmBtn.click()

      // Success toast, and the row disappears from the table
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 15000 })
      await expect(row).not.toBeVisible({ timeout: 15000 })

      // Verify against the real backend: the setup is actually deleted
      await expect.poll(async () => {
        const resp = await page.request.get('/api/v1/setups')
        const body = await resp.json()
        return (body.setupIds ?? []).includes(throwawayId)
      }, { timeout: 15000 }).toBe(false)
    })

    test('view details modal shows setup configuration', async ({ page, databaseSetupsPage }) => {
      // No route stubbing — this exercises the real GET /api/v1/setups/{setupId}.
      // SETUP_ID exists for real (created by the Setup Creation tests above in this
      // serial file, and by the 3c-setup-prerequisite project dependency).
      await page.goto('/')
      await databaseSetupsPage.goto()
      await page.waitForLoadState('networkidle')

      await databaseSetupsPage.getActionButton(SETUP_ID).click()

      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('View Details').click()

      // A modal opens (not a toast)
      const detailsBody = page.getByTestId('setup-details-modal')
      await expect(detailsBody).toBeVisible({ timeout: 5000 })

      // Setup ID is the row we opened
      await expect(detailsBody).toContainText(SETUP_ID)

      // The configuration labels render
      await expect(detailsBody).toContainText('Host')
      await expect(detailsBody).toContainText('Port')
      await expect(detailsBody).toContainText('Database Name')
      await expect(detailsBody).toContainText('Schema')
      await expect(detailsBody).toContainText('Status')

      // Every field is populated from the real backend response — once the fetch
      // resolves, no '—' placeholders remain. This proves the modal rendered live
      // GET /api/v1/setups/{setupId} data, not stubbed values.
      await expect(detailsBody).not.toContainText('—', { timeout: 10000 })

      // Close button dismisses the modal
      await page.locator('.ant-modal-footer button', { hasText: 'Close' }).click()
      await expect(detailsBody).not.toBeVisible({ timeout: 5000 })
    })
  })
})

