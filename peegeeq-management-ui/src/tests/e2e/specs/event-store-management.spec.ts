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

    test('should display event stores page with table', async ({ page }) => {
      await page.goto('/event-stores')

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

    test('should create event store with valid data', async ({ page }) => {
      await page.goto('/event-stores')

      // Click Create Event Store button
      const createButton = page.getByRole('button', { name: /create event store/i })
      await expect(createButton).toBeVisible()
      await createButton.click()

      // Wait for modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in event store name - capture the exact name for later verification
      const eventStoreName = `test-event-store-${Date.now()}`
      const nameInput = page.getByLabel(/event store name/i)
      await nameInput.fill(eventStoreName)

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

      // Verify the SPECIFIC event store we just created appears in table
      // Use .first() to handle multiple event stores (testing multiple creates is valid)
      await expect(page.locator('.ant-table-tbody').getByText(eventStoreName).first()).toBeVisible({ timeout: 5000 })
    })

    test('should display backend error for duplicate event store name', async ({ page }) => {
      // Verify setup exists first - global setup may have deleted it
      await page.goto('/database-setups')
      const setupExists = await page.locator(`tr:has-text("${SETUP_ID}")`).count() > 0

      if (!setupExists) {
        // Recreate setup if it was deleted
        const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))
        await page.getByRole('button', { name: /create setup/i }).click()
        await page.getByLabel(/setup id/i).fill(SETUP_ID)
        await page.getByLabel(/host/i).fill(dbConfig.host)
        await page.getByLabel(/port/i).fill(String(dbConfig.port))
        await page.getByLabel(/database name/i).fill(`e2e_eventstore_dup_test_${Date.now()}`)
        await page.getByLabel(/username/i).fill(dbConfig.username)
        await page.getByLabel(/password/i).fill(dbConfig.password)
        await page.getByLabel(/schema/i).fill('public')
        await page.locator('.ant-modal .ant-btn-primary').click()
        await page.waitForTimeout(1500)
      }

      await page.goto('/event-stores')

      // Generate unique name for this test run
      const duplicateName = `duplicate-event-store-${Date.now()}`

      // Create the first event store
      await page.getByRole('button', { name: /create event store/i }).click()
      await expect(page.locator('.ant-modal')).toBeVisible()
      await page.getByLabel(/event store name/i).fill(duplicateName)
      await page.getByTestId('refresh-setups-btn').click()
      await page.waitForTimeout(300)

      const setupSelect = page.locator('.ant-select').filter({ hasText: 'Select setup' })
      await setupSelect.click()
      const dropdown = page.locator('.ant-select-dropdown:visible')
      await expect(dropdown).toBeVisible({ timeout: 3000 })
      const setupOption = page.locator('.ant-select-item-option-content').filter({ hasText: SETUP_ID })
      await expect(setupOption).toBeVisible({ timeout: 3000 })
      await setupOption.click()

      // Submit first event store
      await page.locator('.ant-modal .ant-btn-primary').click()
      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 5000 })

      // Now try to create a duplicate with the same name
      await page.getByRole('button', { name: /create event store/i }).click()
      await expect(page.locator('.ant-modal')).toBeVisible()
      await page.getByLabel(/event store name/i).fill(duplicateName)
      await page.getByTestId('refresh-setups-btn').click()
      await page.waitForTimeout(300)

      const setupSelect2 = page.locator('.ant-select').filter({ hasText: 'Select setup' })
      await setupSelect2.click()
      const dropdown2 = page.locator('.ant-select-dropdown:visible')
      await expect(dropdown2).toBeVisible({ timeout: 3000 })
      const setupOption2 = page.locator('.ant-select-item-option-content').filter({ hasText: SETUP_ID })
      await expect(setupOption2).toBeVisible({ timeout: 3000 })
      await setupOption2.click()

      // Submit duplicate event store - should fail
      await page.locator('.ant-modal .ant-btn-primary').click()

      // Wait for error message to appear
      const errorMessage = page.locator('.ant-message-error')
      await expect(errorMessage.first()).toBeVisible({ timeout: 5000 })

      // Get the actual error message text
      const errorText = await errorMessage.first().textContent()
      console.log('Backend error message displayed:', errorText)

      // Verify the error message contains the event store name and backend details
      expect(errorText).toBeTruthy()
      expect(errorText).toContain(duplicateName)
      expect(errorText).toContain('Failed to add event store')

      // Modal should stay open on error
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Close the modal
      await page.keyboard.press('Escape')
      await expect(page.locator('.ant-modal')).not.toBeVisible()
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

      // Cancel the modal - use specific role selector for Cancel button
      const cancelButton = page.getByRole('button', { name: /cancel/i })
      await cancelButton.click()
      await expect(page.locator('.ant-modal')).not.toBeVisible()
    })
  })

  test.describe('Navigation Tests', () => {

    test('should verify all tabs are visible and navigable', async ({ page }) => {
      await page.goto('/event-stores')
      await page.waitForTimeout(2000)

      // Define expected tabs - Event Stores page has 2 tabs
      const expectedTabs = ['Event Stores', 'Events']

      // Verify each tab exists and is clickable
      for (const tabName of expectedTabs) {
        const tab = page.getByRole('tab', { name: new RegExp(tabName, 'i') })
        await expect(tab).toBeVisible({ timeout: 5000 })

        // Click the tab
        await tab.click()
        await page.waitForTimeout(500)

        // Verify tab is active using aria-selected attribute
        await expect(tab).toHaveAttribute('aria-selected', 'true')

        console.log(`✅ Tab "${tabName}" is visible and navigable`)
      }
    })

    test('should verify Event Stores tab content', async ({ page }) => {
      await page.goto('/event-stores')
      await page.waitForTimeout(2000)

      // Click Event Stores tab (should be default)
      const eventStoresTab = page.getByRole('tab', { name: /event stores/i })
      await eventStoresTab.click()
      await page.waitForTimeout(500)

      // Verify table is visible
      await expect(page.locator('.ant-table')).toBeVisible()

      // Verify Create Event Store button is visible
      const createButton = page.getByRole('button', { name: /create event store/i })
      await expect(createButton).toBeVisible()

      console.log('✅ Event Stores tab content is visible with table and create button')
    })

    test('should verify Events tab content', async ({ page }) => {
      await page.goto('/event-stores')
      await page.waitForTimeout(2000)

      // Click Events tab
      const eventsTab = page.getByRole('tab', { name: /^events/i })
      await eventsTab.click()
      await page.waitForTimeout(500)

      // Verify events content area exists
      await expect(page.locator('.ant-table')).toBeVisible()

      console.log('✅ Events tab content is visible')
    })
  })
})
