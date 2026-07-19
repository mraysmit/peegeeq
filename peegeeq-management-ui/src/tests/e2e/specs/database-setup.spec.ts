import { test, expect } from '../page-objects'
import * as fs from 'fs'
import { SETUP_ID, TEST_SCHEMA } from '../test-constants'

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
        schema: TEST_SCHEMA
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

    test('connect modal should show validation errors when submitted empty', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.clickConnect()

      const modal = page.locator('.ant-modal').filter({ hasText: 'Connect to Existing Setup' })
      await expect(modal).toBeVisible()

      // Submit without filling required fields
      await page.locator('.ant-modal-footer .ant-btn-primary').click()

      // Required field errors (Setup ID, Database Name, Password, Schema) must appear
      await expect(modal.locator('.ant-form-item-explain-error').first()).toBeVisible({ timeout: 5000 })

      await page.keyboard.press('Escape')
    })
  })

  test.describe('Setup Actions', () => {

    test('detach setup removes it from the active list', async ({ page, databaseSetupsPage }) => {
      // Real backend, no stub. Create a dedicated throwaway setup, DETACH it through the
      // UI (non-destructive), and verify it is gone from the active list — both from the
      // table and from the real GET /api/v1/setups response. Uses a unique id so it never
      // touches SETUP_ID (which the rest of the suite depends on).
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
            schema: TEST_SCHEMA,
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

      // Detach through the UI: action menu → Detach Setup → confirm
      await databaseSetupsPage.getActionButton(throwawayId).click()

      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('Detach Setup').click()

      // Detach is non-destructive, so the confirm OK is a primary (not danger) button.
      const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-primary')
      await expect(confirmBtn).toBeVisible({ timeout: 3000 })
      await confirmBtn.click()

      // Success toast, and the row disappears from the table
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 15000 })
      await expect(row).not.toBeVisible({ timeout: 15000 })

      // Verify against the real backend: the setup is detached (gone from the active list)
      await expect.poll(async () => {
        const resp = await page.request.get('/api/v1/setups')
        const body = await resp.json()
        return (body.setupIds ?? []).includes(throwawayId)
      }, { timeout: 15000 }).toBe(false)
    })

    test('drop database requires typing the exact database name and destroys the database', async ({ page, databaseSetupsPage }) => {
      // Real backend, no stub. Create a dedicated throwaway setup, open the guarded Drop Database
      // modal, verify the type-to-confirm guard (button disabled until the EXACT database name is
      // typed), drop it, and prove against the real backend that the database is actually gone —
      // a reconnect to the same coordinates must now fail.
      test.setTimeout(120000)

      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
      const dropId = `drop_test_${Date.now()}`
      const dropDb = `drop_test_db_${Date.now()}`

      const createResp = await page.request.post('/api/v1/database-setup/create', {
        data: {
          setupId: dropId,
          databaseConfig: {
            host: dbConfig.host,
            port: dbConfig.port,
            databaseName: dropDb,
            username: dbConfig.username,
            password: dbConfig.password,
            schema: TEST_SCHEMA,
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

      const row = page.locator('.ant-table-row').filter({ hasText: dropId })
      await expect(row).toBeVisible({ timeout: 10000 })

      // Open the Drop Database modal: action menu → Drop Database…
      await databaseSetupsPage.getActionButton(dropId).click()
      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('Drop Database…').click()

      const confirmInput = page.getByTestId('drop-database-confirm-input')
      const dropBtn = page.getByTestId('drop-database-confirm-btn')
      await expect(confirmInput).toBeVisible({ timeout: 5000 })

      // Type-to-confirm guard: disabled empty, disabled on a wrong name, enabled on the exact name.
      await expect(dropBtn).toBeDisabled()
      await confirmInput.fill('definitely_not_the_db')
      await expect(dropBtn).toBeDisabled()
      await confirmInput.fill(dropDb)
      await expect(dropBtn).toBeEnabled()
      await dropBtn.click()

      // Success toast, and the row disappears from the table
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 30000 })
      await expect(row).not.toBeVisible({ timeout: 15000 })

      // Verify against the real backend: gone from the active list...
      await expect.poll(async () => {
        const resp = await page.request.get('/api/v1/setups')
        const body = await resp.json()
        return (body.setupIds ?? []).includes(dropId)
      }, { timeout: 15000 }).toBe(false)

      // ...and the database itself is destroyed — reconnecting to the same coordinates must fail
      // (connect is non-destructive and requires the schema to exist).
      const reconnectResp = await page.request.post('/api/v1/database-setup/connect', {
        data: {
          setupId: dropId,
          databaseConfig: {
            host: dbConfig.host,
            port: dbConfig.port,
            databaseName: dropDb,
            username: dbConfig.username,
            password: dbConfig.password,
            schema: TEST_SCHEMA,
            templateDatabase: 'template0',
            encoding: 'UTF8',
          },
          queues: [],
          eventStores: [],
        },
        timeout: 30000,
      })
      expect(reconnectResp.ok()).toBeFalsy()
    })

    test('drop database modal cancel closes without dropping anything', async ({ page, databaseSetupsPage }) => {
      // Real backend. Open the guarded drop modal for SETUP_ID (safe: the drop button is
      // never enabled — nothing is typed), cancel it, and verify the setup is untouched.
      await page.goto('/database-setups')
      await page.waitForLoadState('networkidle')

      const row = page.locator('.ant-table-row').filter({ hasText: SETUP_ID })
      await expect(row).toBeVisible({ timeout: 10000 })

      await databaseSetupsPage.getActionButton(SETUP_ID).click()
      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('Drop Database…').click()

      const confirmInput = page.getByTestId('drop-database-confirm-input')
      await expect(confirmInput).toBeVisible({ timeout: 5000 })
      await expect(page.getByTestId('drop-database-confirm-btn')).toBeDisabled()

      await page.locator('.ant-modal-footer .ant-btn', { hasText: 'Cancel' }).click()
      await expect(confirmInput).not.toBeVisible({ timeout: 5000 })

      // Nothing was dropped: the setup is still present and active on the real backend.
      await expect(row).toBeVisible()
      const resp = await page.request.get('/api/v1/setups')
      const body = await resp.json()
      expect(body.setupIds ?? []).toContain(SETUP_ID)
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

  test.describe('Connect to Existing Setup', () => {

    test('connects to a detached setup through the UI modal and it becomes active', async ({ page, databaseSetupsPage }) => {
      // Real backend, no stub. Provision a setup out-of-band, DETACH it (non-destructive DELETE —
      // the database + registry persist, only the in-memory binding is dropped), then CONNECT to it
      // through the UI modal and verify it is active again via the real GET /api/v1/setups.
      test.setTimeout(120000)

      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
      const connectId = `connect_test_${Date.now()}`
      const connectDb = `connect_test_db_${Date.now()}`

      const createResp = await page.request.post('/api/v1/database-setup/create', {
        data: {
          setupId: connectId,
          databaseConfig: {
            host: dbConfig.host,
            port: dbConfig.port,
            databaseName: connectDb,
            username: dbConfig.username,
            password: dbConfig.password,
            schema: TEST_SCHEMA,
            templateDatabase: 'template0',
            encoding: 'UTF8',
          },
          queues: [],
          eventStores: [],
        },
        timeout: 90000,
      })
      if (!createResp.ok()) {
        throw new Error(`Provision (create) failed: ${createResp.status()} ${await createResp.text()}`)
      }

      // Detach (non-destructive) so the UI can connect fresh.
      const detachResp = await page.request.delete(`/api/v1/setups/${connectId}`)
      expect(detachResp.ok()).toBeTruthy()
      await expect.poll(async () => {
        const resp = await page.request.get('/api/v1/setups')
        const body = await resp.json()
        return (body.setupIds ?? []).includes(connectId)
      }, { timeout: 15000 }).toBe(false)

      // Connect through the UI modal.
      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.connectSetup({
        setupId: connectId,
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName: connectDb,
        username: dbConfig.username,
        password: dbConfig.password,
        schema: TEST_SCHEMA,
      })

      // Row appears in the table, and the real backend reports it active again.
      await expect(page.locator(`tr:has-text("${connectId}")`)).toBeVisible({ timeout: 15000 })
      await expect.poll(async () => {
        const resp = await page.request.get('/api/v1/setups')
        const body = await resp.json()
        return (body.setupIds ?? []).includes(connectId)
      }, { timeout: 15000 }).toBe(true)

      // Cleanup: non-destructive detach.
      await page.request.delete(`/api/v1/setups/${connectId}`)
    })

    test('connect to a nonexistent database shows error toast and keeps the modal open', async ({ page, databaseSetupsPage }) => {
      // Real backend, no stub or injection: connecting to a database that does not exist is a
      // genuine failure the backend refuses, so the error UX is exercised for real.
      // The component logs the failure via console.error — remove the listener so the
      // expected error does not pollute the output.
      await page.removeAllListeners('console')

      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))

      await page.goto('/')
      await databaseSetupsPage.goto()
      await databaseSetupsPage.clickConnect()

      const modal = page.locator('.ant-modal').filter({ hasText: 'Connect to Existing Setup' })
      await modal.getByLabel('Setup ID').fill('does-not-exist')
      await modal.getByLabel('Host').fill(dbConfig.host)
      await modal.getByLabel('Port').fill(String(dbConfig.port))
      await modal.getByLabel('Database Name').fill(`no_such_db_${Date.now()}`)
      await modal.getByLabel('Username').fill(dbConfig.username)
      await modal.getByLabel('Password').fill(dbConfig.password)
      await modal.getByLabel('Schema').fill(TEST_SCHEMA)

      await page.locator('.ant-modal-footer .ant-btn-primary').click()

      // Error toast appears and the modal stays open — the failed connect did not dismiss it.
      await expect(page.locator('.ant-message-error')).toBeVisible({ timeout: 30000 })
      await expect(modal).toBeVisible()

      await page.keyboard.press('Escape')
    })
  })
})

