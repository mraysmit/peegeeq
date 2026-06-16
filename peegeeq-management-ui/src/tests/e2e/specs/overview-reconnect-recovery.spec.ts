import { test, expect } from '@playwright/test'

/**
 * Overview Page – WebSocket reconnect RECOVERY
 *
 * The existing overview-reconnecting-banner spec only asserts that the gold
 * "Reconnecting…" tag APPEARS when the system-monitoring WebSocket is dropped. It never
 * asserts that the UI actually RECOVERS.
 *
 * Strategy: route /ws/monitoring through a stateful handler. While `allowConnect` is false
 * it closes EVERY connection — so the reconnecting state is stable and observable no matter
 * how many connections open (React StrictMode double-mounts the Overview effect in dev, so a
 * simple "close the first attempt" counter is unreliable). Once we've observed the gold
 * "Reconnecting…" tag we flip `allowConnect`, and the next reconnect attempt (5s default
 * interval) proxies to the real backend, so the tag must return to green "Connected".
 *
 * Backend counterpart:
 *   SystemMonitoringHandlerTest.testWebSocketReconnectAfterDropResumesWithFreshSession.
 */
test.describe('Overview – WebSocket reconnect recovery', () => {

    test('WebSocket recovers to Connected (green) after a transient drop', async ({ page }) => {
        let allowConnect = false
        await page.routeWebSocket('**/ws/monitoring', ws => {
            if (allowConnect) {
                // Recovery phase: proxy to the real backend so the client reconnects.
                ws.connectToServer()
            } else {
                // Down phase: drop every connection → stable "Reconnecting…" state.
                ws.close()
            }
        })

        await page.goto('/')
        await page.waitForLoadState('load')

        const wsTag = page.getByTestId('websocket-status')
        await expect(wsTag).toBeVisible({ timeout: 5000 })

        // Down phase: the WebSocketService cycles connect → close → scheduleReconnect, so the
        // tag must show gold "Reconnecting…" within a couple of 5s reconnect cycles.
        await expect(wsTag).toContainText('Reconnecting', { timeout: 15000 })

        // Allow recovery: the next reconnect attempt now reaches the real backend.
        allowConnect = true

        // The tag must return to green "Connected" ("Connected" is not a substring of
        // "Reconnecting" or "Disconnected", so this matches only the recovered state).
        await expect(wsTag).toContainText('Connected', { timeout: 20000 })
        const tagClass = await wsTag.getAttribute('class')
        expect(tagClass).toContain('ant-tag-green')
    })
})
