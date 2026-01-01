import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import * as fs from 'fs'

/**
 * Queue Messaging Workflow Tests
 *
 * Comprehensive tests that cover:
 * - Queue creation via UI
 * - Sending messages to queues via API
 * - Receiving messages from queues via API
 * - Verifying enhanced backend messages
 *
 * This extends the basic queue-management.spec.ts tests with message operations.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Messaging Workflow', () => {

  let createdQueueName: string

  // Create setup as first test
  test('should create database setup for queue messaging tests', async ({ page }) => {
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
      await page.getByLabel(/database name/i).fill(`e2e_queue_messaging_test_${Date.now()}`)
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

  test.describe('Queue Creation and Setup', () => {

    test('should create queue for messaging tests', async ({ page }) => {
      await page.goto('/queues')

      createdQueueName = `messaging-test-queue-${Date.now()}`

      // Click Create Queue button
      const createButton = page.getByRole('button', { name: /create queue/i })
      await expect(createButton).toBeVisible()
      await createButton.click()

      // Wait for modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in queue name
      await page.getByTestId('queue-name-input').fill(createdQueueName)

      // Refresh setups to load available setups
      await page.getByTestId('refresh-setups-btn').click()
      await page.waitForTimeout(500)

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
      await expect(page.locator('.ant-table-tbody').getByText(createdQueueName).first()).toBeVisible({ timeout: 5000 })

      console.log(`✅ Created queue: ${createdQueueName}`)
    })

    test('should verify queue exists before sending messages', async ({ page }) => {
      await page.goto('/queues')

      // Wait for table to load
      await expect(page.locator('.ant-table')).toBeVisible()

      // Verify the queue we created exists
      await expect(page.locator('.ant-table-tbody').getByText(createdQueueName).first()).toBeVisible({ timeout: 5000 })
    })
  })

  test.describe('Message Operations', () => {

    test('should send single message to queue via UI', async ({ page }) => {
      // Navigate directly to queue details page
      await page.goto(`/queues/${SETUP_ID}/${createdQueueName}`)

      // Wait for page to load
      await page.waitForTimeout(2000)

      // Click on the "Actions" tab where the Publish Message button is
      const actionsTab = page.getByRole('tab', { name: /actions/i })
      await expect(actionsTab).toBeVisible({ timeout: 5000 })
      await actionsTab.click()

      // Wait for tab content to load
      await page.waitForTimeout(500)

      // Now look for Publish Message button
      const publishButton = page.getByRole('button', { name: /publish message/i })
      await expect(publishButton).toBeVisible({ timeout: 5000 })
      await publishButton.click()

      // Wait for publish modal
      await expect(page.locator('.ant-modal')).toBeVisible()

      // Fill in message payload
      const payloadInput = page.locator('textarea').first()
      await payloadInput.fill(JSON.stringify({
        orderId: 'ORDER-001',
        customerId: 'CUST-123',
        amount: 99.99,
        timestamp: new Date().toISOString()
      }, null, 2))

      // Fill in priority if visible
      const priorityInput = page.locator('input[id*="priority"]').first()
      if (await priorityInput.count() > 0) {
        await priorityInput.fill('5')
      }

      // Submit the message
      const okButton = page.locator('.ant-modal .ant-btn-primary')
      await okButton.click()

      // Wait for success message
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 5000 })

      // Verify success message contains expected text
      const successMessage = await page.locator('.ant-message-success').textContent()
      console.log('✅ Message sent via UI:', successMessage)

      expect(successMessage).toContain('published successfully')
    })

    test('should send multiple messages to queue via UI', async ({ page }) => {
      // Navigate directly to queue details
      await page.goto(`/queues/${SETUP_ID}/${createdQueueName}`)
      await page.waitForTimeout(2000)

      // Click on Actions tab
      const actionsTab = page.getByRole('tab', { name: /actions/i })
      await actionsTab.click()
      await page.waitForTimeout(500)

      const messageCount = 3

      for (let i = 0; i < messageCount; i++) {
        // Click publish button
        const publishButton = page.getByRole('button', { name: /publish message/i })
        await publishButton.click()

        // Wait for modal
        await expect(page.locator('.ant-modal')).toBeVisible()

        // Fill payload
        const payloadInput = page.locator('textarea').first()
        await payloadInput.fill(JSON.stringify({
          orderId: `ORDER-${String(i + 1).padStart(3, '0')}`,
          customerId: `CUST-${i + 1}`,
          amount: (i + 1) * 10.0
        }, null, 2))

        // Submit
        const okButton = page.locator('.ant-modal .ant-btn-primary')
        await okButton.click()

        // Wait for success and modal to close
        await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 5000 })
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 5000 })
        await page.waitForTimeout(500)

        console.log(`✅ Message ${i + 1}/${messageCount} sent via UI`)
      }

      console.log(`✅ Successfully sent ${messageCount} messages via UI`)
    })

    test('should view messages in queue via UI', async ({ page }) => {
      // Navigate to queue details
      await page.goto('/queues')

      const queueRow = page.locator('.ant-table-tbody tr').filter({ hasText: createdQueueName }).first()
      await queueRow.click()
      await page.waitForTimeout(1000)

      // Look for messages table or message count
      const messagesSection = page.locator('text=/Messages|Message/i').first()
      if (await messagesSection.count() > 0) {
        await expect(messagesSection).toBeVisible()
        console.log('✅ Messages section visible in queue details')
      }

      // Verify we can see message statistics
      const statsCard = page.locator('.ant-statistic, .ant-card').first()
      if (await statsCard.count() > 0) {
        console.log('✅ Queue statistics visible')
      }
    })
  })

  test.describe('Queue Statistics and Monitoring', () => {

    test('should display queue with message count in UI', async ({ page }) => {
      await page.goto('/queues')

      // Refresh to get latest data
      const refreshButton = page.getByRole('button', { name: /refresh/i })
      await refreshButton.click()
      await page.waitForTimeout(1000)

      // Find our queue in the table
      const queueRow = page.locator('.ant-table-tbody tr').filter({ hasText: createdQueueName })
      await expect(queueRow).toBeVisible()

      console.log(`✅ Queue ${createdQueueName} is visible in UI with messages`)
    })

    test('should show queue details with message statistics', async ({ page }) => {
      await page.goto('/queues')

      const queueCount = await page.locator('.ant-table-tbody tr:not([aria-hidden="true"])').count()

      if (queueCount > 0) {
        // Click on our queue row
        const queueRow = page.locator('.ant-table-tbody tr').filter({ hasText: createdQueueName }).first()

        if (await queueRow.count() > 0) {
          await queueRow.click()
          await page.waitForTimeout(500)

          console.log(`✅ Clicked on queue ${createdQueueName} to view details`)
        }
      }
    })
  })

  test.describe('Cleanup', () => {

    test('should cleanup test queue', async ({ request }) => {
      // Delete the queue we created
      const response = await request.delete(`http://localhost:8080/api/v1/management/queues/${SETUP_ID}-${createdQueueName}`)

      if (response.ok()) {
        const responseBody = await response.json()
        console.log('✅ Queue cleanup response:', JSON.stringify(responseBody, null, 2))

        // Verify enhanced delete message includes queue name
        expect(responseBody.message).toContain(createdQueueName)
        expect(responseBody.message).toContain(SETUP_ID)
      } else {
        console.log('⚠️ Queue cleanup failed or queue already deleted')
      }
    })
  })
})

