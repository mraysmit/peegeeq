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

  // -------------------------------------------------------------------------
  // Route helper — injects a controlled setup list so action/state tests do
  // not depend on real DB state.
  // Handles both:
  //   GET /api/v1/setups           → { setupIds }
  //   GET /api/v1/setups/{setupId} → { queueFactories, eventStores, status }
  // -------------------------------------------------------------------------

  async function mockSetupList(page: any, setupIds: string[]) {
    await page.route('**/api/v1/setups**', route => {
      if (route.request().method() !== 'GET') return route.continue()
      const url: string = route.request().url()
      const isListRequest = /\/api\/v1\/setups\/?$/.test(url)
      if (isListRequest) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ setupIds }),
        })
      }
      // Detail request: /api/v1/setups/{setupId}
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ queueFactories: [], eventStores: [], status: 'active' }),
      })
    })
  }

  test.describe('Empty State', () => {

    test('should show empty state alert when no setups exist', async ({ page, databaseSetupsPage }) => {
      await mockSetupList(page, [])
      await page.goto('/database-setups')
      await page.waitForLoadState('networkidle')

      await expect(databaseSetupsPage.getEmptyStateAlert()).toBeVisible()
    })
  })

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
      // This test deliberately injects a 500 — remove the beforeEach console error
      // listener so that the expected browser errors do not pollute the test output.
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

    test('delete setup should call DELETE endpoint after confirmation', async ({ page, databaseSetupsPage }) => {
      await mockSetupList(page, [SETUP_ID])

      const deleteRequests: string[] = []
      await page.route('**/api/v1/database-setup/**', route => {
        if (route.request().method() === 'DELETE') {
          deleteRequests.push(route.request().url())
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'deleted' }),
          })
        }
        return route.continue()
      })

      await page.goto('/database-setups')
      await page.waitForLoadState('networkidle')

      await databaseSetupsPage.getActionButton(SETUP_ID).click()

      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('Delete Setup').click()

      const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
      await expect(confirmBtn).toBeVisible({ timeout: 3000 })
      await confirmBtn.click()

      await page.waitForTimeout(500)
      expect(deleteRequests.length, 'DELETE /api/v1/database-setup/... was not called').toBeGreaterThanOrEqual(1)
      expect(deleteRequests[0]).toContain(`database-setup/${SETUP_ID}`)
    })

    test('view details should show info toast', async ({ page, databaseSetupsPage }) => {
      await mockSetupList(page, [SETUP_ID])
      await page.goto('/database-setups')
      await page.waitForLoadState('networkidle')

      await databaseSetupsPage.getActionButton(SETUP_ID).click()

      const dropdown = page.locator('.ant-dropdown')
        .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
        .last()
      await expect(dropdown).toBeVisible()
      await dropdown.getByText('View Details').click()

      await expect(
        page.locator('.ant-message-notice').filter({ hasText: 'View details coming soon' })
      ).toBeVisible({ timeout: 3000 })
    })
  })
})

