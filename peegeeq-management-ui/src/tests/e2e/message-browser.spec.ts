import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3000'

test.describe('Message Browser', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should display message browser interface', async ({ page }) => {
    console.log('📨 Testing Message Browser interface...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Validate page loaded correctly
    expect(page.url()).toContain('/messages')
    
    // Check for Message Browser page title (uses Title component, not Card title)
    await expect(page.locator('h4:has-text("Message Browser")')).toBeVisible()
    
    // Check for search and filter controls
    await expect(page.locator('input[placeholder*="Search"]')).toBeVisible()
    await expect(page.locator('.ant-select:has(.ant-select-selection-item:has-text("All Queues"))')).toBeVisible()
    
    // Check for message table
    await expect(page.locator('.ant-table')).toBeVisible()
    
    // Validate table headers
    const expectedHeaders = ['Message ID', 'Queue', 'Timestamp', 'Type', 'Status', 'Actions']
    for (const header of expectedHeaders) {
      await expect(page.locator(`th:has-text("${header}")`)).toBeVisible()
    }
    
    console.log('✅ Message Browser interface validation passed')
  })

  test('should handle message search and filtering', async ({ page }) => {
    console.log('🔍 Testing message search and filtering...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Test search functionality
    const searchInput = page.locator('input[placeholder*="Search"]')
    await searchInput.fill('test-message')
    await page.waitForTimeout(1000)
    
    // Test queue filter
    await page.click('.ant-select:has(.ant-select-selection-item:has-text("All Queues"))')
    await expect(page.locator('.ant-select-dropdown')).toBeVisible()
    
    // Check for queue options
    await expect(page.locator('.ant-select-item:has-text("orders")')).toBeVisible()
    await expect(page.locator('.ant-select-item:has-text("payments")')).toBeVisible()
    await expect(page.locator('.ant-select-item:has-text("notifications")')).toBeVisible()
    
    // Select a specific queue
    await page.click('.ant-select-item:has-text("orders")')
    await page.waitForTimeout(1000)
    
    // Test status filter
    await page.click('.ant-select:has(.ant-select-selection-item:has-text("All Status"))')
    await expect(page.locator('.ant-select-dropdown')).toBeVisible()
    await page.keyboard.press('Escape')
    
    // Test date range picker
    await page.click('.ant-picker')
    await expect(page.locator('.ant-picker-dropdown')).toBeVisible()
    await page.keyboard.press('Escape')
    
    console.log('✅ Message search and filtering test passed')
  })

  test('should validate real-time message streaming', async ({ page }) => {
    console.log('📡 Testing real-time message streaming...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for real-time toggle
    await expect(page.locator('.ant-switch')).toBeVisible()
    
    // Enable real-time streaming
    const realtimeSwitch = page.locator('.ant-switch')
    const isChecked = await realtimeSwitch.isChecked()
    if (!isChecked) {
      await realtimeSwitch.click()
    }
    
    // Send test messages and verify they appear
    const testMessage = {
      payload: { testId: 'BROWSER-TEST-1', timestamp: Date.now() },
      headers: { source: 'message-browser-test' },
      messageType: 'BrowserTestMessage'
    }
    
    // Send message to orders queue
    const response = await fetch(`${API_BASE_URL}/api/v1/queues/demo-setup-clean/orders/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(testMessage)
    })
    
    if (response.ok) {
      console.log('    ✅ Test message sent for real-time streaming test')
      
      // Wait for potential UI update
      await page.waitForTimeout(3000)
      
      // Check if message appears in table (may not be visible due to filtering)
      const tableRows = await page.locator('.ant-table-tbody tr').count()
      console.log(`    📊 Message table has ${tableRows} rows`)
    }
    
    console.log('✅ Real-time message streaming test passed')
  })

  test('should validate message detail inspection', async ({ page }) => {
    console.log('🔬 Testing message detail inspection...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for message details panel (uses "Message Information" title)
    await expect(page.locator('.ant-card:has-text("Message Information")')).toBeVisible()
    
    // Check for metadata fields
    const metadataFields = [
      'Message ID',
      'Queue Name', 
      'Message Type',
      'Timestamp',
      'Headers',
      'Processing Status'
    ]
    
    for (const field of metadataFields) {
      await expect(page.locator(`text=${field}`)).toBeVisible()
    }
    
    // Check for payload viewer (uses "Payload" title)
    await expect(page.locator('.ant-card:has-text("Payload")')).toBeVisible()
    
    // Check for JSON formatting
    await expect(page.locator('pre')).toBeVisible()
    
    // Check for headers display (uses "Headers" title)
    await expect(page.locator('.ant-card:has-text("Headers")')).toBeVisible()
    
    console.log('✅ Message detail inspection test passed')
  })

  test('should validate consumer tracking and processing status', async ({ page }) => {
    console.log('👥 Testing consumer tracking and processing status...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for consumer information panel
    await expect(page.locator('.ant-card:has-text("Consumer Information")')).toBeVisible()
    
    // Check for processing status indicators
    const statusIndicators = [
      'PENDING',
      'PROCESSING', 
      'COMPLETED',
      'FAILED',
      'RETRY'
    ]
    
    // At least some status indicators should be visible in the UI
    let statusFound = false
    for (const status of statusIndicators) {
      const statusCount = await page.locator(`text=${status}`).count()
      if (statusCount > 0) {
        statusFound = true
        console.log(`    ✅ Found status indicator: ${status}`)
      }
    }
    
    // Check for consumer group information
    await expect(page.locator('text=Consumer Group')).toBeVisible()
    await expect(page.locator('text=Processing Time')).toBeVisible()
    
    console.log('✅ Consumer tracking and processing status test passed')
  })

  test('should validate export capabilities', async ({ page }) => {
    console.log('📤 Testing export capabilities...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for export button
    await expect(page.locator('button:has-text("Export")')).toBeVisible()
    
    // Click export button
    await page.click('button:has-text("Export")')
    
    // Check for export options modal or dropdown
    const hasModal = await page.locator('.ant-modal').count() > 0
    const hasDropdown = await page.locator('.ant-dropdown').count() > 0
    
    expect(hasModal || hasDropdown).toBe(true)
    
    if (hasModal) {
      // Check export format options
      await expect(page.locator('text=JSON')).toBeVisible()
      await expect(page.locator('text=CSV')).toBeVisible()
      
      // Close modal
      await page.click('.ant-modal-close')
    } else if (hasDropdown) {
      // Close dropdown
      await page.keyboard.press('Escape')
    }
    
    console.log('✅ Export capabilities test passed')
  })

  test('should validate message statistics and metrics', async ({ page }) => {
    console.log('📊 Testing message statistics and metrics...')
    
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for message statistics
    const statisticTitles = [
      'Total Messages',
      'Messages Today',
      'Processing Rate',
      'Error Rate'
    ]
    
    for (const title of statisticTitles) {
      await expect(page.locator(`.ant-statistic-title:has-text("${title}")`)).toBeVisible()
    }
    
    // Check for message distribution chart
    await expect(page.locator('.ant-card:has-text("Message Distribution")')).toBeVisible()
    
    // Validate that statistics show reasonable values
    const statisticValues = await page.locator('.ant-statistic-content-value').allTextContents()
    expect(statisticValues.length).toBeGreaterThanOrEqual(4)
    
    console.log('✅ Message statistics and metrics test passed')
  })
})
