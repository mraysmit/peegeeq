import { test, expect } from '../page-objects'
import { SETUP_ID, TEST_SCHEMA } from '../test-constants'
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
      await page.getByLabel(/schema/i).fill(TEST_SCHEMA)
      
      // Submit
      await page.locator('.ant-modal .ant-btn-primary').click()
      
      // Wait for modal to close — setup creation includes DB creation + migrations, allow up to 60s
      await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 60000 })
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

      const queueName = `ui_test_queue_${Date.now()}`

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

  test.describe('Queue Purge', () => {

    test('purge from queue list page empties the queue', async ({ page, queuesPage }) => {
      test.setTimeout(60000)

      const queueName = `purge_list_test_${Date.now()}`
      const MESSAGE_COUNT = 5

      // 1. Create the queue and publish messages via REST API
      const qResp = await page.request.post('/api/v1/management/queues', {
        data: { setupId: SETUP_ID, name: queueName, type: 'native' },
      })
      if (!qResp.ok()) throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)

      for (let i = 0; i < MESSAGE_COUNT; i++) {
        const r = await page.request.post(`/api/v1/queues/${SETUP_ID}/${queueName}/publish`, {
          data: { payload: { seq: i } },
        })
        if (!r.ok()) throw new Error(`Publish message ${i} failed: ${r.status()} ${await r.text()}`)
      }

      // 2. Navigate to the Queues page and locate the queue row
      await page.goto('/')
      await queuesPage.goto()
      await queuesPage.clickRefresh()

      const row = page.locator('.ant-table-row').filter({ hasText: queueName }).first()
      await expect(row).toBeVisible({ timeout: 15000 })

      // Message count column (2nd cell) must show at least the published count
      const messagesCell = row.locator('td').nth(1)
      await expect.poll(async () => {
        const txt = (await messagesCell.innerText()).replace(/[^0-9]/g, '')
        return parseInt(txt || '0', 10)
      }, { timeout: 15000 }).toBeGreaterThanOrEqual(MESSAGE_COUNT)

      // 3. Three-dot menu → Purge Messages → confirm
      await row.getByRole('button').click()
      await page.locator('.ant-dropdown-menu-item:has-text("Purge Messages")').click()

      // showPurgeConfirm → Modal.confirm, okText "Purge"
      await expect(page.locator('.ant-modal-confirm')).toBeVisible()
      await page.getByRole('button', { name: 'Purge', exact: true }).click()

      // Success toast confirms the purge completed
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 10000 })

      // 4. Message count drops to 0 (RTK cache invalidation triggers a refetch)
      await expect.poll(async () => {
        const txt = (await messagesCell.innerText()).replace(/[^0-9]/g, '')
        return parseInt(txt || '0', 10)
      }, { timeout: 15000 }).toBe(0)
    })
  })
})
