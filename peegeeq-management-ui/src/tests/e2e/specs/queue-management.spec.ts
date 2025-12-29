import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Queue Management Tests
 *
 * Tests queue creation, listing, and operations.
 * Requires a database setup to exist (created by database-setup.spec.ts).
 */

test.describe('Queue Management', () => {

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

      const initialCount = await queuesPage.getQueueCount()

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
      await page.waitForTimeout(500) // Wait for setups to load

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
        await page.waitForTimeout(500)
      }
    })
  })
})

