import { test, expect } from '@playwright/test'

/**
 * Targeted test for WebSocket and SSE connection status labels
 * This test validates that the connection status indicators on the Overview page
 * accurately reflect the actual WebSocket and SSE connection states.
 */
test.describe('WebSocket and SSE Connection Status', () => {
  test('should show WebSocket and SSE as Connected on Overview page', async ({ page }) => {
    // First ensure we have the correct backend config (127.0.0.1:8080)
    await page.goto('/settings')
    await page.waitForLoadState('load')
    
    // Reset to defaults to ensure we're using 127.0.0.1:8080
    await page.getByTestId('reset-settings-btn').click()
    await expect(page.getByText(/Configuration reset to defaults/)).toBeVisible({ timeout: 3000 })
    
    // Navigate to home/overview page
    await page.goto('/')
    await page.waitForLoadState('load')

    // Locate the WebSocket and SSE status indicators
    const wsStatus = page.getByTestId('websocket-status')
    const sseStatus = page.getByTestId('sse-status')

    // Wait for elements to be visible
    await expect(wsStatus).toBeVisible({ timeout: 5000 })
    await expect(sseStatus).toBeVisible({ timeout: 5000 })

    // Check status - should show Connected (allow up to 15 seconds for connection establishment after config reset)
    await expect(wsStatus).toContainText('Connected', { timeout: 15000 })
    await expect(sseStatus).toContainText('Connected', { timeout: 15000 })

    // Verify they have the correct color class (green for connected)
    const wsClass = await wsStatus.getAttribute('class')
    const sseClass = await sseStatus.getAttribute('class')
    
    expect(wsClass).toContain('ant-tag-green')
    expect(sseClass).toContain('ant-tag-green')
  })
})
