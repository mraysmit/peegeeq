import { test, expect } from '@playwright/test'

/**
 * Overview Page - System Status Tests
 * 
 * Tests that the System Status section on the Overview (Home) page
 * displays correct WebSocket and SSE connection status, and that
 * these statuses are in sync with the actual connection state and
 * the connection status in the header.
 */
test.describe('Overview - System Status', () => {
  
  test('should display system status section with WebSocket and SSE indicators', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // System status info should be visible
    const systemStatus = page.getByTestId('system-status-info')
    await expect(systemStatus).toBeVisible()

    // WebSocket status tag should be visible
    const wsStatus = page.getByTestId('websocket-status')
    await expect(wsStatus).toBeVisible()

    // SSE status tag should be visible
    const sseStatus = page.getByTestId('sse-status')
    await expect(sseStatus).toBeVisible()
  })

  test('should show WebSocket and SSE as Disconnected initially (no real-time backend)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // Without real-time services, both should show Disconnected
    // Note: This test assumes the backend doesn't have WebSocket/SSE endpoints implemented
    const wsStatus = page.getByTestId('websocket-status')
    const sseStatus = page.getByTestId('sse-status')

    // Wait a bit for connection attempts to fail
    await page.waitForTimeout(2000)

    // Check status - should be Disconnected since WebSocket/SSE endpoints don't exist
    await expect(wsStatus).toContainText('Disconnected')
    await expect(sseStatus).toContainText('Disconnected')
  })

  test('should show system uptime and active connections', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    const systemStatus = page.getByTestId('system-status-info')
    
    // Should contain uptime information
    await expect(systemStatus).toContainText(/running for/)
    
    // Should contain active connections count
    await expect(systemStatus).toContainText(/active connections/)
  })

  test('WebSocket and SSE status should be independent of REST API connection', async ({ page }) => {
    // Navigate to home page - REST API is working (we can load data)
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // Header connection status should show Online (REST API works)
    const headerStatus = page.getByTestId('connection-status')
    await expect(headerStatus).toContainText('Online')

    // But WebSocket/SSE should be Disconnected (those endpoints don't exist)
    const wsStatus = page.getByTestId('websocket-status')
    const sseStatus = page.getByTestId('sse-status')
    
    await page.waitForTimeout(2000)
    
    await expect(wsStatus).toContainText('Disconnected')
    await expect(sseStatus).toContainText('Disconnected')
  })

  test('should update WebSocket/SSE status colors based on connection state', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    await page.waitForTimeout(2000)

    const wsTag = page.getByTestId('websocket-status')
    const sseTag = page.getByTestId('sse-status')

    // Disconnected tags should have orange color
    // Note: Ant Design uses class names for colors
    const wsClass = await wsTag.getAttribute('class')
    const sseClass = await sseTag.getAttribute('class')

    // Orange color for disconnected state
    expect(wsClass).toContain('ant-tag-orange')
    expect(sseClass).toContain('ant-tag-orange')
  })

  test('system status should refresh when clicking refresh button', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    // Get initial uptime text
    const systemStatus = page.getByTestId('system-status-info')
    const initialText = await systemStatus.textContent()

    // Click refresh button
    await page.getByRole('button', { name: /refresh/i }).click()

    // Wait for refresh to complete
    await page.waitForTimeout(1000)

    // System status should still be visible (may have updated data)
    await expect(systemStatus).toBeVisible()
    
    // WebSocket/SSE status should still be present
    await expect(page.getByTestId('websocket-status')).toBeVisible()
    await expect(page.getByTestId('sse-status')).toBeVisible()
  })
})
