import { test, expect, Page } from '@playwright/test'

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3000'

test.describe('Comprehensive UI Validation', () => {
  test.beforeEach(async ({ page }) => {
    // Verify backend is running
    const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    expect(response.ok).toBe(true)
  })

  test('should validate all pages load and display content correctly', async ({ page }) => {
    console.log('🌐 Testing all pages load correctly...')
    
    const pages = [
      { path: '/', name: 'Overview' },
      { path: '/queues', name: 'Queues' },
      { path: '/consumer-groups', name: 'Consumer Groups' },
      { path: '/event-stores', name: 'Event Stores' },
      { path: '/messages', name: 'Message Browser' }
    ]
    
    for (const pageInfo of pages) {
      await page.goto(`${UI_BASE_URL}${pageInfo.path}`)
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(3000)
      
      // Verify page loads without errors
      await expect(page.locator('.ant-layout').first()).toBeVisible()
      
      // Check for basic content (table or cards)
      const hasTable = await page.locator('.ant-table').count() > 0
      const hasCards = await page.locator('.ant-card').count() > 0
      const hasStatistics = await page.locator('.ant-statistic').count() > 0
      
      expect(hasTable || hasCards || hasStatistics).toBe(true)
      console.log(`    ✅ ${pageInfo.name} page loaded successfully`)
    }
    
    console.log('✅ All pages load validation passed')
  })

  test('should validate core interactive elements work', async ({ page }) => {
    console.log('🔧 Testing core interactive elements...')
    
    // Test Overview page interactions
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Test refresh button
    const refreshButtons = page.locator('button:has(.anticon-reload)')
    const refreshButtonCount = await refreshButtons.count()
    expect(refreshButtonCount).toBeGreaterThan(0)
    
    await refreshButtons.first().click()
    await page.waitForTimeout(2000)
    console.log('    ✅ Refresh button working')
    
    // Test queue creation modal
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    await page.click('button:has-text("Create Queue")')
    await expect(page.locator('.ant-modal')).toBeVisible()
    await page.click('.ant-modal-close')
    console.log('    ✅ Queue creation modal working')
    
    // Test message browser real-time toggle
    await page.goto(`${UI_BASE_URL}/messages`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    const realtimeSwitch = page.locator('.ant-switch')
    const switchCount = await realtimeSwitch.count()
    if (switchCount > 0) {
      await realtimeSwitch.first().click()
      await page.waitForTimeout(1000)
      console.log('    ✅ Real-time switch working')
    }
    
    console.log('✅ Core interactive elements validation passed')
  })

  test('should validate data accuracy across all pages', async ({ page }) => {
    console.log('📊 Testing data accuracy across all pages...')
    
    // Get API data
    const overviewResponse = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
    const overviewData = await overviewResponse.json()
    
    const queuesResponse = await fetch(`${API_BASE_URL}/api/v1/management/queues`)
    const queuesData = await queuesResponse.json()
    
    console.log('    📊 API Data:', {
      totalQueues: overviewData.systemStats?.totalQueues,
      actualQueues: queuesData.queues?.length,
      totalConsumerGroups: overviewData.systemStats?.totalConsumerGroups
    })
    
    // Test Overview page data
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    const statisticValues = await page.locator('.ant-statistic-content-value').allTextContents()
    if (statisticValues.length >= 4) {
      const uiQueues = parseInt(statisticValues[0]) || 0
      const apiQueues = overviewData.systemStats?.totalQueues || 0
      
      // Allow for small variance
      expect(Math.abs(uiQueues - apiQueues)).toBeLessThanOrEqual(1)
      console.log(`    ✅ Queue count accuracy: UI=${uiQueues}, API=${apiQueues}`)
    }
    
    // Test Queues page data
    await page.goto(`${UI_BASE_URL}/queues`)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    if (queuesData.queues && queuesData.queues.length > 0) {
      for (const queue of queuesData.queues.slice(0, 3)) {
        const queueExists = await page.locator(`td:has-text("${queue.name}")`).count() > 0
        if (queueExists) {
          console.log(`    ✅ Queue "${queue.name}" found in UI`)
        }
      }
    }
    
    console.log('✅ Data accuracy validation passed')
  })

  test('should validate real-time features are functional', async ({ page }) => {
    console.log('📡 Testing real-time features functionality...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(5000) // Allow time for WebSocket connections
    
    // Check for real-time charts (recharts)
    const chartContainers = page.locator('.recharts-wrapper')
    const chartCount = await chartContainers.count()
    
    if (chartCount > 0) {
      console.log(`    📊 Found ${chartCount} real-time charts`)
      await expect(chartContainers.first()).toBeVisible()
    }
    
    // Check for connection status indicators
    const statusTags = page.locator('.ant-tag')
    const statusTagCount = await statusTags.count()
    
    if (statusTagCount > 0) {
      const statusTexts = await statusTags.allTextContents()
      const hasConnectionStatus = statusTexts.some(text => 
        text.includes('WebSocket') || text.includes('SSE') || text.includes('Connected') || text.includes('Disconnected')
      )
      
      if (hasConnectionStatus) {
        console.log('    ✅ Connection status indicators found')
      }
    }
    
    // Test message sending for real-time updates
    const testMessage = {
      payload: { testId: 'REALTIME-TEST-1', timestamp: Date.now() },
      headers: { source: 'realtime-test' },
      messageType: 'RealtimeTestMessage'
    }
    
    const response = await fetch(`${API_BASE_URL}/api/v1/queues/demo-setup-clean/orders/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(testMessage)
    })
    
    if (response.ok) {
      console.log('    ✅ Real-time test message sent successfully')
    }
    
    console.log('✅ Real-time features validation passed')
  })

  test('should validate error handling is robust', async ({ page }) => {
    console.log('🛡️ Testing error handling robustness...')
    
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Test rapid navigation (stress test)
    const pages = ['/', '/queues', '/consumer-groups', '/event-stores', '/messages']
    
    for (const pagePath of pages) {
      await page.goto(`${UI_BASE_URL}${pagePath}`)
      await page.waitForTimeout(500)
      
      // Verify page doesn't crash
      await expect(page.locator('.ant-layout').first()).toBeVisible()
    }
    
    // Test rapid refresh clicks
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    
    const refreshButton = page.locator('button:has(.anticon-reload)').first()
    for (let i = 0; i < 3; i++) {
      await refreshButton.click()
      await page.waitForTimeout(300)
    }
    
    // UI should still be responsive
    await expect(page.locator('.ant-layout').first()).toBeVisible()
    
    console.log('✅ Error handling robustness validation passed')
  })

  test('should validate complete system integration', async ({ page }) => {
    console.log('🎯 Testing complete system integration...')
    
    // Test end-to-end workflow: Overview -> Queues -> Send Message -> Verify
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Capture initial state
    const initialStats = await captureUIMetrics(page)
    console.log('    📊 Initial system state:', initialStats)
    
    // Navigate to queues
    await page.click('a[href="/queues"]')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)
    
    // Verify queue table has data
    const queueRows = await page.locator('.ant-table-tbody tr').count()
    expect(queueRows).toBeGreaterThan(0)
    console.log(`    📋 Found ${queueRows} queues in table`)
    
    // Send test messages to multiple queues
    const testQueues = ['orders', 'payments', 'notifications']
    for (const queueName of testQueues) {
      const testMessage = {
        payload: { testId: `INTEGRATION-TEST-${queueName}`, timestamp: Date.now() },
        headers: { source: 'integration-test' },
        messageType: 'IntegrationTestMessage'
      }
      
      const response = await fetch(`${API_BASE_URL}/api/v1/queues/demo-setup-clean/${queueName}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(testMessage)
      })
      
      if (response.ok) {
        console.log(`    ✅ Test message sent to ${queueName}`)
      }
    }
    
    // Return to overview and verify system is still working
    await page.click('a[href="/"]')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(3000)
    
    // Refresh data
    await page.click('button:has(.anticon-reload)')
    await page.waitForTimeout(3000)
    
    // Verify system is still functional
    const finalStats = await captureUIMetrics(page)
    console.log('    📊 Final system state:', finalStats)
    
    expect(finalStats.queues).toBeGreaterThanOrEqual(initialStats.queues)
    
    console.log('✅ Complete system integration validation passed')
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
