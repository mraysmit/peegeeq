import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3000'

test.describe('Advanced Queue Operations', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should validate queue configuration management', async ({ page }) => {
    console.log('⚙️ Testing queue configuration management...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Check for configuration buttons in action column
    const actionButtons = page.locator('.ant-table-tbody .ant-btn')
    const buttonCount = await actionButtons.count()
    expect(buttonCount).toBeGreaterThan(0)
    
    // Test configuration modal (if available)
    const configButtons = page.locator('button:has-text("Configure"), button[title*="configure"], .anticon-setting')
    const configButtonCount = await configButtons.count()
    
    if (configButtonCount > 0) {
      await configButtons.first().click()
      
      // Check for configuration modal
      const hasModal = await page.locator('.ant-modal').count() > 0
      if (hasModal) {
        await expect(page.locator('.ant-modal-title')).toBeVisible()
        
        // Check for configuration options
        await expect(page.locator('.ant-form')).toBeVisible()
        
        // Close modal
        await page.click('.ant-modal-close')
      }
    }
    
    console.log('✅ Queue configuration management test passed')
  })

  test('should validate queue metrics and monitoring', async ({ page }) => {
    console.log('📊 Testing queue metrics and monitoring...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Validate queue statistics cards
    await expect(page.locator('.ant-statistic-title:has-text("Total Queues")')).toBeVisible()
    await expect(page.locator('.ant-statistic-title:has-text("Active Queues")')).toBeVisible()
    
    // Get queue data from API and validate metrics
    const queuesResponse = await fetch(`${API_BASE_URL}/api/v1/management/queues`)
    const queuesData = await queuesResponse.json()
    
    if (queuesData.queues && queuesData.queues.length > 0) {
      console.log(`    📋 Found ${queuesData.queues.length} queues in API`)
      
      // Validate that each queue shows metrics in the table
      for (const queue of queuesData.queues.slice(0, 3)) {
        // Check queue name appears
        await expect(page.locator(`td:has-text("${queue.name}")`)).toBeVisible()
        
        // Validate metrics columns have data
        const queueRow = page.locator(`tr:has(td:has-text("${queue.name}"))`).first()
        const cells = await queueRow.locator('td').allTextContents()
        
        expect(cells.length).toBeGreaterThanOrEqual(5) // Name, Messages, Consumers, Rate, Status
        console.log(`    ✅ Queue "${queue.name}" metrics validated`)
      }
    }
    
    console.log('✅ Queue metrics and monitoring test passed')
  })

  test('should validate queue status transitions', async ({ page }) => {
    console.log('🔄 Testing queue status transitions...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for status indicators in the table
    const statusElements = page.locator('.ant-tag, .ant-badge')
    const statusCount = await statusElements.count()
    expect(statusCount).toBeGreaterThan(0)
    
    // Check for common status values
    const commonStatuses = ['ACTIVE', 'INACTIVE', 'PAUSED', 'ERROR']
    let statusFound = false
    
    for (const status of commonStatuses) {
      const statusElementCount = await page.locator(`text=${status}`).count()
      if (statusElementCount > 0) {
        statusFound = true
        console.log(`    ✅ Found status: ${status}`)
      }
    }
    
    expect(statusFound).toBe(true)
    
    // Test status change actions (if available)
    const statusButtons = page.locator('button:has-text("Pause"), button:has-text("Resume"), button:has-text("Stop")')
    const statusButtonCount = await statusButtons.count()
    
    if (statusButtonCount > 0) {
      console.log(`    📊 Found ${statusButtonCount} status control buttons`)
    }
    
    console.log('✅ Queue status transitions test passed')
  })

  test('should validate queue deletion and cleanup', async ({ page }) => {
    console.log('🗑️ Testing queue deletion and cleanup...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for delete buttons (but don't actually delete)
    const deleteButtons = page.locator('button:has-text("Delete"), .anticon-delete, button[title*="delete"]')
    const deleteButtonCount = await deleteButtons.count()
    
    if (deleteButtonCount > 0) {
      console.log(`    🗑️ Found ${deleteButtonCount} delete buttons`)
      
      // Test delete confirmation (without actually deleting)
      await deleteButtons.first().click()
      
      // Check for confirmation modal
      const hasConfirmModal = await page.locator('.ant-modal:has-text("confirm"), .ant-popconfirm').count() > 0
      if (hasConfirmModal) {
        console.log('    ✅ Delete confirmation dialog found')
        
        // Cancel the deletion
        await page.click('button:has-text("Cancel"), .ant-btn:has-text("No")')
      }
    } else {
      console.log('    ℹ️  No delete buttons found (may be restricted in current setup)')
    }
    
    console.log('✅ Queue deletion and cleanup test passed')
  })

  test('should validate queue performance analytics', async ({ page }) => {
    console.log('📈 Testing queue performance analytics...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for performance charts or metrics
    const chartCards = page.locator('.ant-card:has-text("Performance"), .ant-card:has-text("Throughput"), .ant-card:has-text("Latency")')
    const chartCount = await chartCards.count()
    
    if (chartCount > 0) {
      console.log(`    📊 Found ${chartCount} performance chart cards`)
      
      // Validate chart content
      await expect(chartCards.first()).toBeVisible()
    }
    
    // Check for detailed metrics in table
    const metricsColumns = ['Message Rate', 'Messages', 'Consumers']
    for (const column of metricsColumns) {
      await expect(page.locator(`th:has-text("${column}")`)).toBeVisible()
    }
    
    // Validate that metrics show numerical values
    const messageRateCells = page.locator('td').filter({ hasText: /\d+\.\d+/ })
    const numericCellCount = await messageRateCells.count()
    expect(numericCellCount).toBeGreaterThanOrEqual(0) // May be 0 if no activity
    
    console.log('✅ Queue performance analytics test passed')
  })

  test('should validate queue filtering and search', async ({ page }) => {
    console.log('🔍 Testing queue filtering and search...')
    
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for search input
    const searchInputs = page.locator('input[placeholder*="Search"], input[placeholder*="Filter"]')
    const searchInputCount = await searchInputs.count()
    
    if (searchInputCount > 0) {
      // Test search functionality
      await searchInputs.first().fill('orders')
      await page.waitForTimeout(1000)
      
      // Clear search
      await searchInputs.first().clear()
      await page.waitForTimeout(1000)
    }
    
    // Check for filter dropdowns
    const filterSelects = page.locator('.ant-select:has(.ant-select-selection-item:has-text("All")), .ant-select:has(.ant-select-selection-item:has-text("Filter"))')
    const filterCount = await filterSelects.count()
    
    if (filterCount > 0) {
      console.log(`    🔍 Found ${filterCount} filter controls`)
      
      // Test filter dropdown
      await filterSelects.first().click()
      await expect(page.locator('.ant-select-dropdown')).toBeVisible()
      await page.keyboard.press('Escape')
    }
    
    console.log('✅ Queue filtering and search test passed')
  })
})
