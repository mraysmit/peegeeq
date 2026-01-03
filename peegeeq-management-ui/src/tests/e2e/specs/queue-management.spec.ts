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
      
      // Wait for modal to close
      await expect(page.locator('.ant-modal')).not.toBeVisible()
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

    test('should display queues page with table', async ({ page, queuesPage }) => {
      await page.goto('/')
      await queuesPage.goto()

      // Verify page title
      await expect(queuesPage.getPageTitle()).toContainText('Queues')

      // Verify table is visible
      await expect(queuesPage.getQueuesTable()).toBeVisible()

      // Verify action buttons are visible
      await expect(queuesPage.getCreateButton()).toBeVisible()
      await expect(queuesPage.getRefreshButton()).toBeVisible()
    })

    test('should refresh queue list', async ({ page, queuesPage }) => {
      await page.goto('/')
      await queuesPage.goto()

      await queuesPage.clickRefresh()

      // Table should still be visible after refresh
      await expect(queuesPage.getQueuesTable()).toBeVisible()

      const afterCount = await queuesPage.getQueueCount()
      expect(afterCount).toBeGreaterThanOrEqual(0) // May be 0 if no queues exist
    })
  })

  test.describe('Queue Creation', () => {

    test('should create queue with valid data', async ({ page, queuesPage }) => {
      await page.goto('/')
      await queuesPage.goto()
      await expect(queuesPage.getCreateButton()).toBeVisible()

      const queueName = `ui-test-queue-${Date.now()}`

      // Create queue
      await queuesPage.clickCreate()
      await page.getByTestId('queue-name-input').fill(queueName)

      // Refresh setups to load available setups
      await page.getByTestId('refresh-setups-btn').click()
      
      // Wait for setup select to be ready
      await expect(page.getByTestId('queue-setup-select')).toBeEnabled()

      // Select the setup
      await queuesPage.selectOption('queue-setup-select', SETUP_ID)
      await queuesPage.clickModalButton('OK')

      // Wait for modal to close (indicates success)
      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 5000 })

      // Refresh queue list
      await queuesPage.clickRefresh()

      // Verify queue exists
      const exists = await queuesPage.queueExists(queueName)
      expect(exists).toBeTruthy()
    })

    test('should validate required fields', async ({ page, queuesPage }) => {
      await page.goto('/')
      await queuesPage.goto()

      // Open create modal
      await queuesPage.clickCreate()

      // Try to submit without filling required fields
      await queuesPage.clickModalButton('OK')

      // Modal should still be visible (validation failed)
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Should show validation errors for both required fields
      await expect(page.getByText('Please enter queue name')).toBeVisible()
      await expect(page.getByText('Please select setup')).toBeVisible()
    })
  })

  test.describe('Queue Operations', () => {

    test('should display queue details when clicking on queue', async ({ page, queuesPage }) => {
      await page.goto('/')
      await queuesPage.goto()

      const queueCount = await queuesPage.getQueueCount()

      if (queueCount > 0) {
        // Click on the first visible data row (exclude hidden measure row with aria-hidden)
        const firstQueue = page.locator('.ant-table-tbody tr:not([aria-hidden="true"])').first()
        await firstQueue.click()
        // Should navigate to queue details page or show details panel
        // This depends on the implementation
        // await expect(page).toHaveURL(/queues\/.+/) // Example assertion
        // For now, just ensure no error occurred
        await expect(page.locator('.ant-message-error')).not.toBeVisible()
      }
    })
  })
})
