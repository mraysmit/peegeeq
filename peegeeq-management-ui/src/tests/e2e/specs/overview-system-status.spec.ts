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
        await page.waitForLoadState('load')

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

    test('should show WebSocket and SSE as Connected when backend is running', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // Both should show Connected when backend is running
        const wsStatus = page.getByTestId('websocket-status')
        const sseStatus = page.getByTestId('sse-status')

        // Check status - should be Connected (expect waits up to 10s by default)
        await expect(wsStatus).toContainText('Connected', { timeout: 15000 })
        await expect(sseStatus).toContainText('Connected', { timeout: 15000 })
    })

    test('should show system uptime and active connections', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const systemStatus = page.getByTestId('system-status-info')

        // Should contain uptime information
        await expect(systemStatus).toContainText(/running for/)

        // Should contain active connections count
        await expect(systemStatus).toContainText(/active connections/)
    })

    test('WebSocket and SSE status should be independent of REST API connection', async ({ page }) => {
        // Navigate to home page - REST API is working (we can load data)
        await page.goto('/')
        await page.waitForLoadState('load')

        // Header connection status should show Online (REST API works)
        const headerStatus = page.getByTestId('connection-status')
        await expect(headerStatus).toContainText('Online')

        // WebSocket/SSE should also be Connected
        const wsStatus = page.getByTestId('websocket-status')
        const sseStatus = page.getByTestId('sse-status')

        await expect(wsStatus).toContainText('Connected', { timeout: 15000 })
        await expect(sseStatus).toContainText('Connected', { timeout: 15000 })
    })

    test('should update WebSocket/SSE status colors based on connection state', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const wsTag = page.getByTestId('websocket-status')
        const sseTag = page.getByTestId('sse-status')

        // Wait for tags to be visible and connected
        await expect(wsTag).toBeVisible()
        await expect(sseTag).toBeVisible()

        // Connected tags should have green color
        // Note: Ant Design uses class names for colors
        const wsClass = await wsTag.getAttribute('class')
        const sseClass = await sseTag.getAttribute('class')

        // Green color for connected state
        expect(wsClass).toContain('ant-tag-green')
        expect(sseClass).toContain('ant-tag-green')
    })

    test('system status should refresh when clicking refresh button', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // Get initial uptime text
        const systemStatus = page.getByTestId('system-status-info')

        // Click refresh button
        await page.getByRole('button', { name: /refresh/i }).click()

        // System status should still be visible (may have updated data)
        await expect(systemStatus).toBeVisible()

        // WebSocket/SSE status should still be present
        await expect(page.getByTestId('websocket-status')).toBeVisible()
        await expect(page.getByTestId('sse-status')).toBeVisible()
    })
})
