import { test, expect } from '@playwright/test'

/**
 * Overview Page – SSE Reconnecting Banner
 *
 * Counterpart to overview-reconnecting-banner (which only covers the WebSocket). Until now
 * the metrics SSE had no reconnecting-state wiring: createSystemMetricsSSE() passed no
 * onReconnecting callback, so a dropped SSE went straight to orange "Disconnected" and the
 * gold "Reconnecting…" tag could never appear.
 *
 * After wiring SSEService to map a native EventSource auto-reconnect (readyState CONNECTING)
 * to onReconnecting → setSseReconnecting(true), aborting the stream must show the gold tag.
 *
 * ── NO-MOCK POLICY EXCEPTION (fault injection) ─────────────────────────────
 * Sanctioned exception to the no-mock rule (decision 2026-06-15). route.abort on the metrics
 * SSE forces the EventSource auto-reconnect cycle — a healthy backend will not refuse a valid
 * SSE on demand. Deliberate fault injection to exercise the gold "Reconnecting…" wiring, NOT
 * data mocking.
 */
test.describe('Overview – SSE Reconnecting Banner', () => {

    test('SSE status tag shows gold "Reconnecting" when the metrics stream fails', async ({ page }) => {
        // Abort the metrics SSE request so the EventSource enters its auto-reconnect cycle
        // (readyState CONNECTING). The health-probe SSE (/sse/health) is a different path and
        // is intentionally left untouched.
        await page.route('**/sse/metrics**', route => route.abort('connectionrefused'))

        await page.goto('/')
        await page.waitForLoadState('load')

        const sseTag = page.getByTestId('sse-status')
        await expect(sseTag).toBeVisible({ timeout: 5000 })

        // EventSource auto-reconnect → SSEService.onReconnecting → gold "Reconnecting…".
        await expect(sseTag).toContainText('Reconnecting', { timeout: 10000 })
        const tagClass = await sseTag.getAttribute('class')
        expect(tagClass).toContain('ant-tag-gold')
    })
})
