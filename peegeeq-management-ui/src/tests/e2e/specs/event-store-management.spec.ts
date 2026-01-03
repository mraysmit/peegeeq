import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import * as fs from 'fs'

/**
 * Event Store Management Tests
 *
 * Self-contained tests that create their own database setup.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Event Store Management', () => {

  // Create setup as first test
  test('should create database setup for event store tests', async ({ page }) => {
    const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
    
    await page.goto('/database-setups')
    
    // Check if setup exists
    const setupExists = await page.locator(`tr:has-text("${SETUP_ID}")`).count() > 0
    
    if (!setupExists) {
      // Click Create Setup
      await page.getByRole('button', { name: /create setup/i }).click()
      
      // Fill form
      await page.getByLabel(/setup id/i).fill(SETUP_ID)
      await page.getByLabel(/host/i).fill(dbConfig.host)
      await page.getByLabel(/port/i).fill(String(dbConfig.port))
      await page.getByLabel(/database name/i).fill(`e2e_eventstore_test_${Date.now()}`)
      await page.getByLabel(/username/i).fill(dbConfig.username)
      await page.getByLabel(/password/i).fill(dbConfig.password)
      await page.getByLabel(/schema/i).fill('public')
      
      // Submit
      await page.locator('.ant-modal .ant-btn-primary').click()
      await page.waitForTimeout(2000)
    }
  })

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

  test.describe('Event Stores Page', () => {

    test('should display event stores page with table', async ({ page, basePage }) => {
      await page.goto('/')
      await basePage.navigateTo('event-stores')

      // Verify page title
      await expect(basePage.getPageTitle()).toContainText('Event Stores')

      // Verify URL
      expect(page.url()).toContain('/event-stores')

      // Verify table is present
      await expect(page.locator('.ant-table')).toBeVisible()
    })

    test('should refresh event store list', async ({ page }) => {
      await page.goto('/event-stores')

      // Find and click refresh button
      const refreshButton = page.getByRole('button', { name: /refresh/i })
      await expect(refreshButton).toBeVisible()
      await refreshButton.click()

      // Wait for table to reload
      await page.waitForTimeout(500)
      await expect(page.locator('.ant-table')).toBeVisible()
    })
  })

  test.describe('Event Store Creation', () => {

    test('should create event store with valid data', { retries: 0 }, async ({ page }) => {
      await page.goto('/event-stores')

      // Click Create Event Store button
      const createButton = page.getByRole('button', { name: /create event store/i })
      await expect(createButton).toBeVisible()
      await createButton.click()

      // Wait for modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in event store name
      const nameInput = page.getByLabel(/event store name/i)
      await nameInput.fill(`test-event-store-${Date.now()}`)

      // Refresh setups to load available setups
      await page.getByTestId('refresh-setups-btn').click()
      await page.waitForTimeout(500) // Wait for setups to load

      // Click to open the setup dropdown
      const setupSelect = page.locator('.ant-select').filter({ hasText: 'Select setup' })
      await setupSelect.click()
      
      // Wait for dropdown to appear
      const dropdown = page.locator('.ant-select-dropdown:visible')
      await expect(dropdown).toBeVisible({ timeout: 5000 })
      
      // Wait for the setup option to appear
      const setupOption = page.locator('.ant-select-item-option-content').filter({ hasText: SETUP_ID })
      await expect(setupOption).toBeVisible({ timeout: 5000 })
      await setupOption.click()

      // Submit form
      const okButton = page.locator('.ant-modal .ant-btn-primary')
      await okButton.click()

      // Wait for modal to close (indicates success)
      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 5000 })

      // Refresh event store list
      const refreshButton = page.getByRole('button', { name: /refresh/i })
      await refreshButton.click()
      await page.waitForTimeout(500)

      // Verify event store appears in table
      await expect(page.locator('.ant-table-tbody').getByText(/test-event-store-/)).toBeVisible({ timeout: 5000 })
    })

    test('should validate required fields', async ({ page }) => {
      await page.goto('/event-stores')

      // Click Create Event Store button
      const createButton = page.getByRole('button', { name: /create event store/i })
      await createButton.click()

      // Wait for modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Try to submit without filling fields
      const okButton = page.locator('.ant-modal .ant-btn-primary')
      await okButton.click()

      // Should show validation errors
      await expect(page.getByText(/please enter event store name/i)).toBeVisible({ timeout: 2000 })
      await expect(page.getByText(/please select setup/i)).toBeVisible({ timeout: 2000 })

      // Cancel the modal
      const cancelButton = page.locator('.ant-modal .ant-btn:not(.ant-btn-primary)')
      await cancelButton.click()
      await expect(page.locator('.ant-modal')).not.toBeVisible()
    })
  })

  test.describe('Modal Close Behavior', () => {

    test('should close create modal with X button', async ({ page }) => {
      await page.goto('/event-stores')

      // Open create modal
      const createButton = page.getByRole('button', { name: /create event store/i })
      await createButton.click()

      // Wait for modal to be visible
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in some data
      const nameInput = page.getByLabel(/event store name/i)
      await nameInput.fill('test-cancel-store')

      // Click the X button to close modal
      const closeButton = page.locator('.ant-modal-close')
      await expect(closeButton).toBeVisible()
      await closeButton.click()

      // Modal should be closed
      await expect(page.locator('.ant-modal')).not.toBeVisible()
    })

    test('should close create modal with Escape key', async ({ page }) => {
      await page.goto('/event-stores')

      // Open create modal
      const createButton = page.getByRole('button', { name: /create event store/i })
      await createButton.click()

      // Wait for modal to be visible
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in some data
      const nameInput = page.getByLabel(/event store name/i)
      await nameInput.fill('test-escape-store')

      // Press Escape key to close modal
      await page.keyboard.press('Escape')

      // Modal should be closed
      await expect(page.locator('.ant-modal')).not.toBeVisible()
    })

    test('should clear form when modal is reopened', async ({ page }) => {
      await page.goto('/event-stores')

      // Open create modal
      const createButton = page.getByRole('button', { name: /create event store/i })
      await createButton.click()

      // Wait for modal to be visible
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in data
      const nameInput = page.getByLabel(/event store name/i)
      await nameInput.fill('test-clear-store')

      // Close modal with X button
      const closeButton = page.locator('.ant-modal-close')
      await closeButton.click()
      await expect(page.locator('.ant-modal')).not.toBeVisible()

      // Reopen modal
      await createButton.click()
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Verify form is cleared
      const nameInputReopened = page.getByLabel(/event store name/i)
      await expect(nameInputReopened).toHaveValue('')

      // Close modal again
      await page.keyboard.press('Escape')
    })
  })

  test.describe('Event Store Operations', () => {

    test('should display event store details when clicking view', async ({ page }) => {
      await page.goto('/event-stores')

      // Ensure we have at least one event store
      const tableBody = page.locator('.ant-table-tbody')
      await expect(tableBody.locator('tr').first()).toBeVisible({ timeout: 5000 })

      // Find the first event store's action menu
      const firstRowActions = page.locator('.ant-table-tbody tr').first().locator('[data-icon="more"]')
      await firstRowActions.click()

      // Click View Details
      const viewDetailsOption = page.getByText(/view details/i)
      await expect(viewDetailsOption).toBeVisible({ timeout: 3000 })
      await viewDetailsOption.click()

      // Wait for details modal
      await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 3000 })
      
      // Verify modal has event store information
      await expect(page.locator('.ant-modal').getByText(/event store details/i)).toBeVisible()
    })
  })
})
