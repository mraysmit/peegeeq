import { test, expect } from '@playwright/test'
import { generateTestName } from '../fixtures/testData'
import { createQueue, deleteQueue, waitForQueue } from '../fixtures/apiHelpers'
import { fillAntInput, selectAntOption, clickModalButton, waitForModal } from '../fixtures/formHelpers'

/**
 * Example E2E Test with Proper Fixtures
 * 
 * This demonstrates how to use test fixtures and helpers
 * to create robust, maintainable E2E tests.
 */

test.describe('Queue Management with Fixtures Example', () => {
  let testQueueName: string
  
  test.beforeEach(async ({ page, request }) => {
    // Generate unique test name to avoid conflicts
    testQueueName = generateTestName('example-queue')
    
    // Navigate to queues page
    await page.goto('/queues')
    await page.waitForLoadState('networkidle')
  })
  
  test.afterEach(async ({ request }) => {
    // Clean up: Delete test queue if it exists
    try {
      await deleteQueue(request, testQueueName)
    } catch (error) {
      // Queue might not exist if test failed before creation
      console.log(`Cleanup: Queue ${testQueueName} not found`)
    }
  })

  test('should create queue via UI using form helpers', async ({ page, request }) => {
    // Click Create Queue button
    await page.click('button:has-text("Create Queue")')
    
    // Wait for modal to appear
    await waitForModal(page, 'Create Queue')
    
    // Fill form using form helpers (handles Ant Design components)
    await fillAntInput(page, 'Queue Name', testQueueName)
    await selectAntOption(page, 'Setup', 'Production')
    await selectAntOption(page, 'Durability', 'Durable')
    
    // Submit form
    await clickModalButton(page, 'OK')
    
    // Wait for modal to close
    await page.waitForTimeout(1000)
    
    // Verify queue appears in list
    await expect(page.locator(`text=${testQueueName}`)).toBeVisible({ timeout: 10000 })
    
    // Verify via API
    const created = await waitForQueue(request, testQueueName)
    expect(created).toBeTruthy()
  })

  test('should display queue created via API', async ({ page, request }) => {
    // Create queue via API
    const queue = await createQueue(request, {
      name: testQueueName,
      setup: 'Production',
      durability: 'Durable',
      description: 'Example test queue'
    })
    
    expect(queue).toBeTruthy()
    
    // Wait for queue to be created
    await waitForQueue(request, testQueueName)
    
    // Reload page to see new queue
    await page.reload()
    await page.waitForLoadState('networkidle')
    
    // Verify queue is visible
    await expect(page.locator(`text=${testQueueName}`)).toBeVisible()
  })

  test('should delete queue created via API', async ({ page, request }) => {
    // Setup: Create queue via API
    await createQueue(request, {
      name: testQueueName,
      setup: 'Production',
      durability: 'Durable'
    })
    
    await waitForQueue(request, testQueueName)
    
    // Reload to see the queue
    await page.reload()
    await page.waitForLoadState('networkidle')
    
    // Find the queue row
    const queueRow = page.locator(`tr:has-text("${testQueueName}")`).first()
    await expect(queueRow).toBeVisible()
    
    // Click delete button (adjust selector based on actual UI)
    await queueRow.locator('button:has-text("Delete"), button[aria-label="Delete"]').first().click()
    
    // Confirm deletion in modal
    await waitForModal(page)
    await clickModalButton(page, 'OK')
    
    // Wait a bit for deletion to process
    await page.waitForTimeout(1000)
    
    // Verify queue is removed from UI
    await expect(page.locator(`text=${testQueueName}`)).not.toBeVisible()
  })

  test('should validate form with empty fields', async ({ page }) => {
    // Open create queue modal
    await page.click('button:has-text("Create Queue")')
    await waitForModal(page, 'Create Queue')
    
    // Try to submit without filling anything
    await clickModalButton(page, 'OK')
    
    // Should show validation errors
    await expect(page.locator('.ant-form-item-explain-error').first()).toBeVisible()
    
    // Cancel modal
    await clickModalButton(page, 'Cancel')
  })
})
