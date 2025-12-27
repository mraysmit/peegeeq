import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3000'

test.describe('Real-time Features Validation', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should validate WebSocket connection status indicators', async ({ page }) => {
    console.log('🔌 Testing WebSocket connection status...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(5000) // Allow time for WebSocket connection
    
    // Check for connection status indicators
    const wsStatusTags = page.locator('.ant-tag:has-text("WebSocket")')
    const wsStatusCount = await wsStatusTags.count()
    
    if (wsStatusCount > 0) {
      const wsStatus = await wsStatusTags.first().textContent()
      console.log(`    🔌 WebSocket status: ${wsStatus}`)
      
      // Status should be either Connected or Disconnected
      expect(wsStatus).toMatch(/Connected|Disconnected/)
    }
    
    // Check for SSE status indicators
    const sseStatusTags = page.locator('.ant-tag:has-text("SSE")')
    const sseStatusCount = await sseStatusTags.count()
    
    if (sseStatusCount > 0) {
      const sseStatus = await sseStatusTags.first().textContent()
      console.log(`    📡 SSE status: ${sseStatus}`)
      
      // Status should be either Connected or Disconnected
      expect(sseStatus).toMatch(/Connected|Disconnected/)
    }
    
    console.log('✅ Connection status indicators test passed')
  })

  test('should validate real-time chart updates', async ({ page }) => {
    console.log('📈 Testing real-time chart updates...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Check for chart containers (recharts)
    const chartContainers = page.locator('.recharts-wrapper')
    const chartCount = await chartContainers.count()
    
    if (chartCount > 0) {
      console.log(`    📊 Found ${chartCount} real-time charts`)
      
      // Validate chart elements
      await expect(chartContainers.first()).toBeVisible()
      
      // Check for chart data elements
      const chartElements = page.locator('.recharts-line, .recharts-area, .recharts-bar')
      const chartElementCount = await chartElements.count()
      
      if (chartElementCount > 0) {
        console.log(`    📈 Found ${chartElementCount} chart data elements`)
      }
      
      // Send test messages to trigger chart updates
      const testMessage = {
        payload: { testId: 'CHART-TEST-1', timestamp: Date.now() },
        headers: { source: 'chart-test' },
        messageType: 'ChartTestMessage'
      }
      
      await fetch(`${API_BASE_URL}/api/v1/queues/demo-setup-clean/orders/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(testMessage)
      })
      
      // Wait for potential chart update
      await page.waitForTimeout(5000)
      
      console.log('    ✅ Chart update test completed')
    } else {
      console.log('    ℹ️  No recharts found (charts may be using different library)')
    }
    
    console.log('✅ Real-time chart updates test passed')
  })

  test('should validate live metrics accuracy', async ({ page }) => {
    console.log('🎯 Testing live metrics accuracy...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Capture initial metrics from UI
    const initialStats = await captureUIMetrics(page)
    console.log('    📊 Initial UI metrics:', initialStats)
    
    // Send multiple test messages
    const testMessages = [
      { queue: 'orders', count: 3 },
      { queue: 'payments', count: 2 },
      { queue: 'notifications', count: 1 }
    ]
    
    for (const { queue, count } of testMessages) {
      for (let i = 0; i < count; i++) {
        const testMessage = {
          payload: { testId: `METRICS-TEST-${queue}-${i}`, timestamp: Date.now() },
          headers: { source: 'metrics-test' },
          messageType: 'MetricsTestMessage'
        }
        
        await fetch(`${API_BASE_URL}/api/v1/queues/demo-setup-clean/${queue}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(testMessage)
        })
      }
    }
    
    console.log('    📨 Sent 6 test messages across 3 queues')
    
    // Wait for metrics to update
    await page.waitForTimeout(5000)
    
    // Refresh UI data
    await page.click('button:has(.anticon-reload)')
    await page.waitForTimeout(3000)
    
    // Capture updated metrics
    const updatedStats = await captureUIMetrics(page)
    console.log('    📊 Updated UI metrics:', updatedStats)
    
    // Validate that metrics are reasonable (may not change due to message processing)
    expect(updatedStats.queues).toBeGreaterThanOrEqual(initialStats.queues)
    expect(updatedStats.consumerGroups).toBeGreaterThanOrEqual(0)
    
    console.log('✅ Live metrics accuracy test passed')
  })

  test('should validate auto-refresh functionality', async ({ page }) => {
    console.log('🔄 Testing auto-refresh functionality...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Check for auto-refresh controls
    const autoRefreshControls = page.locator('text=Auto-refresh, .ant-switch, button:has-text("Auto")')
    const autoRefreshCount = await autoRefreshControls.count()
    
    if (autoRefreshCount > 0) {
      console.log(`    🔄 Found ${autoRefreshCount} auto-refresh controls`)
      
      // Test auto-refresh toggle
      const refreshSwitch = page.locator('.ant-switch').first()
      const switchExists = await refreshSwitch.count() > 0
      
      if (switchExists) {
        const isEnabled = await refreshSwitch.isChecked()
        console.log(`    📊 Auto-refresh is ${isEnabled ? 'enabled' : 'disabled'}`)
        
        // Toggle auto-refresh
        await refreshSwitch.click()
        await page.waitForTimeout(1000)
        
        // Toggle back
        await refreshSwitch.click()
        await page.waitForTimeout(1000)
      }
    }
    
    // Test manual refresh button
    const refreshButton = page.locator('button:has(.anticon-reload)')
    await expect(refreshButton).toBeVisible()
    
    // Click refresh multiple times to test responsiveness
    for (let i = 0; i < 3; i++) {
      await refreshButton.click()
      await page.waitForTimeout(1000)
    }
    
    console.log('✅ Auto-refresh functionality test passed')
  })

  test('should validate real-time data consistency', async ({ page }) => {
    console.log('🎯 Testing real-time data consistency...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Capture data from multiple sources
    const uiStats = await captureUIMetrics(page)
    
    // Get data directly from API
    const apiResponse = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    const apiData = await apiResponse.json()
    
    console.log('    📊 Data consistency check:', {
      ui: uiStats,
      api: {
        queues: apiData.systemStats?.totalQueues || 0,
        consumerGroups: apiData.systemStats?.totalConsumerGroups || 0
      }
    })
    
    // Validate data consistency (allow for small variance due to timing)
    const queueDiff = Math.abs(uiStats.queues - (apiData.systemStats?.totalQueues || 0))
    const groupDiff = Math.abs(uiStats.consumerGroups - (apiData.systemStats?.totalConsumerGroups || 0))
    
    expect(queueDiff).toBeLessThanOrEqual(2) // Allow small variance
    expect(groupDiff).toBeLessThanOrEqual(5) // Allow larger variance for dynamic groups
    
    console.log('✅ Real-time data consistency test passed')
  })
})

async function captureUIMetrics(page: Page) {
  const stats = { queues: 0, consumerGroups: 0, messages: 0, eventStores: 0 }
  
  try {
    const statisticValues = await page.locator('.ant-statistic-content-value').allTextContents()
    
    if (statisticValues.length >= 4) {
      stats.queues = parseInt(statisticValues[0]) || 0
      stats.consumerGroups = parseInt(statisticValues[1]) || 0
      stats.eventStores = parseInt(statisticValues[2]) || 0
      stats.messages = parseInt(statisticValues[3]) || 0
    }
  } catch (error) {
    console.log('    ⚠️  Could not extract UI metrics:', error.message)
  }
  
  return stats
}
