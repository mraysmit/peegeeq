import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3001'

test.describe('Event Store Management', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should display event store overview and statistics', async ({ page }) => {
    console.log('📊 Testing Event Store overview and statistics...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Validate page loaded correctly
    expect(page.url()).toContain('/event-stores')
    
    // Check for Event Stores page content (no specific page title card)
    await expect(page.locator('text=Event Stores')).toBeVisible()

    // Validate statistics cards (actual titles from implementation)
    await expect(page.locator('.ant-statistic-title:has-text("Event Stores")')).toBeVisible()
    await expect(page.locator('.ant-statistic-title:has-text("Total Events")')).toBeVisible()
    await expect(page.locator('.ant-statistic-title:has-text("Storage Used")')).toBeVisible()
    
    // Check for event stores table
    await expect(page.locator('.ant-table')).toBeVisible()
    
    console.log('✅ Event Store overview validation passed')
  })

  test('should handle event store creation', async ({ page }) => {
    console.log('🏗️ Testing Event Store creation...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Click Create Event Store button
    await page.click('button:has-text("Create Event Store")')
    
    // Verify modal opens
    await expect(page.locator('.ant-modal-title:has-text("Create Event Store")')).toBeVisible()
    
    // Check form fields
    await expect(page.locator('input[placeholder*="aggregate"]')).toBeVisible()
    await expect(page.locator('.ant-select')).toBeVisible()
    
    // Test form validation
    await page.click('button:has-text("Create")')
    await expect(page.locator('.ant-form-item-explain-error')).toBeVisible()
    
    // Fill form with valid data
    await page.fill('input[placeholder*="aggregate"]', 'test-aggregate-store')
    await page.click('.ant-select')
    await page.click('.ant-select-item:has-text("PostgreSQL")')
    
    // Close modal without creating (to avoid side effects)
    await page.click('.ant-modal-close')
    await expect(page.locator('.ant-modal')).not.toBeVisible()
    
    console.log('✅ Event Store creation test passed')
  })

  test('should validate bi-temporal event browsing', async ({ page }) => {
    console.log('🔍 Testing bi-temporal event browsing...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for bi-temporal controls
    await expect(page.locator('text=Business Time')).toBeVisible()
    await expect(page.locator('text=System Time')).toBeVisible()
    
    // Check for date range pickers
    const datePickerCount = await page.locator('.ant-picker').count()
    expect(datePickerCount).toBeGreaterThanOrEqual(2)
    
    // Check for event timeline
    await expect(page.locator('.ant-timeline')).toBeVisible()
    
    // Test time range selection
    await page.click('.ant-picker')
    await expect(page.locator('.ant-picker-dropdown')).toBeVisible()
    await page.keyboard.press('Escape') // Close picker
    
    console.log('✅ Bi-temporal event browsing test passed')
  })

  test('should validate event querying and filtering', async ({ page }) => {
    console.log('🔎 Testing event querying and filtering...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for search and filter controls
    await expect(page.locator('input[placeholder*="Search"]')).toBeVisible()
    await expect(page.locator('.ant-select:has(.ant-select-selection-item:has-text("All Types"))')).toBeVisible()
    
    // Test search functionality
    await page.fill('input[placeholder*="Search"]', 'test-event')
    await page.waitForTimeout(1000)
    
    // Test filter dropdown
    await page.click('.ant-select:has(.ant-select-selection-item:has-text("All Types"))')
    await expect(page.locator('.ant-select-dropdown')).toBeVisible()
    await page.keyboard.press('Escape') // Close dropdown
    
    // Check for correlation ID search
    await expect(page.locator('input[placeholder*="Correlation ID"]')).toBeVisible()
    
    console.log('✅ Event querying and filtering test passed')
  })

  test('should validate event inspection capabilities', async ({ page }) => {
    console.log('🔬 Testing event inspection capabilities...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for event details panel (uses "Event Information" title)
    await expect(page.locator('.ant-card:has-text("Event Information")')).toBeVisible()
    
    // Check for metadata display
    await expect(page.locator('text=Event ID')).toBeVisible()
    await expect(page.locator('text=Aggregate ID')).toBeVisible()
    await expect(page.locator('text=Event Type')).toBeVisible()
    await expect(page.locator('text=Business Time')).toBeVisible()
    await expect(page.locator('text=System Time')).toBeVisible()
    
    // Check for payload viewer (uses "Event Data" title)
    await expect(page.locator('.ant-card:has-text("Event Data")')).toBeVisible()
    
    // Check for JSON formatting
    await expect(page.locator('pre')).toBeVisible()
    
    console.log('✅ Event inspection capabilities test passed')
  })

  test('should validate storage statistics and monitoring', async ({ page }) => {
    console.log('📈 Testing storage statistics and monitoring...')
    
    await page.goto(`${UI_BASE_URL}/event-stores`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Validate storage statistics
    const statisticTitles = [
      'Total Event Stores',
      'Total Events', 
      'Storage Used',
      'Events/sec'
    ]
    
    for (const title of statisticTitles) {
      await expect(page.locator(`.ant-statistic-title:has-text("${title}")`)).toBeVisible()
    }
    
    // Check for storage utilization chart
    await expect(page.locator('.ant-card:has-text("Storage Utilization")')).toBeVisible()
    
    // Validate that statistics show reasonable values
    const statisticValues = await page.locator('.ant-statistic-content-value').allTextContents()
    expect(statisticValues.length).toBeGreaterThanOrEqual(4)
    
    console.log('✅ Storage statistics and monitoring test passed')
  })
})
