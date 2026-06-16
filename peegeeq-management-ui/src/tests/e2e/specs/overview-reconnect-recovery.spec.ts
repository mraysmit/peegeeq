import { test, expect } from '@playwright/test'

/**
 * Overview Page – WebSocket reconnect RECOVERY
 *
 * The existing overview-reconnecting-banner spec only asserts that the gold
 * "Reconnecting…" tag APPEARS when the system-monitoring WebSocket is dropped. It never
 * asserts that the UI actually RECOVERS.
 *
 * This spec drops /ws/monitoring on its first attempt (→ gold "Reconnecting…"), then lets
 * subsequent reconnect attempts proxy through to the real backend, and asserts the tag
 * returns to green "Connected".
 *
 * Backend counterpart:
 *   SystemMonitoringHandlerTest.testWebSocketReconnectAfterDropResumesWithFreshSession
 *   (drop → reconnect → fresh session, stream resumes, counter stays consistent).
 */
test.describe('Overview – WebSocket reconnect recovery', () => {

    test('WebSocket recovers to Connected (green) after a transient drop', async ({ page }) => {
        let attempt = 0
        await page.routeWebSocket('**/ws/monitoring', ws => {
            attempt++
            if (attempt === 1) {
                // First attempt: simulate a transient drop. The WebSocketService onclose
                // handler calls scheduleReconnect() → onReconnecting() → gold tag.
                ws.close()
            } else {
                // Subsequent attempts: let the client reach the real backend so it recovers.
                ws.connectToServer()
            }
        })

        await page.goto('/')
        await page.waitForLoadState('load')

        const wsTag = page.getByTestId('websocket-status')
        await expect(wsTag).toBeVisible({ timeout: 5000 })

        // After the drop the tag must go gold "Reconnecting…".
        await expect(wsTag).toContainText('Reconnecting', { timeout: 10000 })

        // The reconnect attempt (5s default interval) proxies to the real backend → recovery.
        await expect(wsTag).toContainText('Connected', { timeout: 20000 })
        const tagClass = await wsTag.getAttribute('class')
        expect(tagClass).toContain('ant-tag-green')
    })
})
