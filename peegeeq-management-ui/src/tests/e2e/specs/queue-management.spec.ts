import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import * as fs from 'fs'

/**
 * Queue Management Tests
 *
 * Self-contained tests that create their own database setup.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Management', () => {

  // Create setup as first test
  test('should create database setup for queue tests', async ({ page }) => {
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
      await page.getByLabel(/database name/i).fill(`e2e_queue_test_${Date.now()}`)
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

  test.describe('Queues Page', () => {

    test('should display queues page with table', async ({ page }) => {
      await page.goto('/queues')

      // Verify table is visible
      await expect(page.locator('.ant-table')).toBeVisible()

      // Verify action buttons are visible
      await expect(page.getByRole('button', { name: /create queue/i })).toBeVisible()
      await expect(page.getByRole('button', { name: /refresh/i })).toBeVisible()
    })

    test('should refresh queue list', async ({ page }) => {
      await page.goto('/queues')

      // Find and click refresh button
      const refreshButton = page.getByRole('button', { name: /refresh/i })
      await expect(refreshButton).toBeVisible()
      await refreshButton.click()

      // Wait for table to reload
      await page.waitForTimeout(500)
      await expect(page.locator('.ant-table')).toBeVisible()
    })
  })

  test.describe('Queue Creation', () => {

    test('should create queue with valid data', async ({ page }) => {
      await page.goto('/queues')

      const queueName = `ui-test-queue-${Date.now()}`

      // Click Create Queue button
      const createButton = page.getByRole('button', { name: /create queue/i })
      await expect(createButton).toBeVisible()
      await createButton.click()

      // Wait for modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in queue name
      await page.getByTestId('queue-name-input').fill(queueName)

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

      // Refresh queue list
      const refreshButton = page.getByRole('button', { name: /refresh/i })
      await refreshButton.click()
      await page.waitForTimeout(500)

      // Verify queue appears in table
      await expect(page.locator('.ant-table-tbody').getByText(queueName).first()).toBeVisible({ timeout: 5000 })
    })

    test('should validate required fields', async ({ page }) => {
      await page.goto('/queues')

      // Open create modal
      const createButton = page.getByRole('button', { name: /create queue/i })
      await createButton.click()

      // Try to submit without filling required fields
      const okButton = page.locator('.ant-modal .ant-btn-primary')
      await okButton.click()

      // Modal should still be visible (validation failed)
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Should show validation errors for both required fields
      await expect(page.getByText('Please enter queue name')).toBeVisible()
      await expect(page.getByText('Please select setup')).toBeVisible()
    })
  })

  test.describe('Queue Operations', () => {

    test('should display queue details when clicking on queue', async ({ page }) => {
      await page.goto('/queues')

      const queueCount = await page.locator('.ant-table-tbody tr:not([aria-hidden="true"])').count()

      if (queueCount > 0) {
        // Click on the first visible data row
        const firstQueue = page.locator('.ant-table-tbody tr:not([aria-hidden="true"])').first()
        await firstQueue.click()
        // Should navigate to queue details page or show details panel
        await page.waitForTimeout(500)
      }
    })
  })
})
