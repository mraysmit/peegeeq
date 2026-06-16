import { test, expect } from '@playwright/test'

/**
 * Overview Page – WS/SSE Reconnecting Banner Tests
 *
 * Covers gap:
 *   4. The wsReconnecting / sseReconnecting states (gold status tags reading
 *      "Reconnecting…") are never asserted.
 *
 * Strategy: use page.routeWebSocket() (Playwright ≥ 1.48) to intercept the
 * system monitoring WebSocket and close it immediately.  The WebSocketService
 * onclose handler calls scheduleReconnect() → onReconnecting() → the Zustand
 * store's setWsReconnecting(true), switching the tag to gold "Reconnecting…".
 *
 * ── NO-MOCK POLICY EXCEPTION (fault injection) ─────────────────────────────
 * Sanctioned exception to the no-mock rule (decision 2026-06-15). routeWebSocket closes the
 * /ws/monitoring connection to force the reconnecting state — a healthy backend will not drop
 * a valid WebSocket on demand. Deliberate fault injection to exercise the gold "Reconnecting…"
 * tag, NOT data mocking.
 */
test.describe('Overview – Reconnecting Banner', () => {

    test('WebSocket status tag shows gold "Reconnecting" when connection is dropped', async ({ page }) => {
        // Intercept the system monitoring WebSocket and close it immediately,
        // triggering the reconnect cycle without connecting to the real backend.
        await page.routeWebSocket('**/ws/monitoring', ws => {
            ws.close()
        })

        await page.goto('/')
        await page.waitForLoadState('load')

        const wsTag = page.getByTestId('websocket-status')
        await expect(wsTag).toBeVisible({ timeout: 5000 })

        // WebSocketService.scheduleReconnect() fires onReconnecting → gold tag
        await expect(wsTag).toContainText('Reconnecting', { timeout: 10000 })

        const tagClass = await wsTag.getAttribute('class')
        expect(tagClass).toContain('ant-tag-gold')
    })
})
