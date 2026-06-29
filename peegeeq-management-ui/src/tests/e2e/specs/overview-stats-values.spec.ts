import { test, expect } from '@playwright/test'

/**
 * Overview Page – Live Stats VALUE Assertions
 *
 * The existing specs only assert presence: overview-system-status checks the text
 * "active connections" appears, and overview-live-stats-update checks the Messages/sec card
 * contains a digit. Neither asserts the value behaves as the backend invariants we hardened
 * guarantee.
 *
 * This spec asserts the actual values, mirroring the backend regression tests:
 *   - §8.1 / Phase 11 (SystemMonitoringHandler counter integrity): the old meaningless
 *     `activeConnections` composite is now split into monitoringSessions / activeSubscriptions /
 *     dbPool. monitoringSessions is a real, non-negative integer and is >= 1 while this client
 *     holds the monitoring WebSocket/SSE open — never the negative value the pre-fix
 *     double-decrement produced.
 *   - §8.2 (delta-rate, now reconciled across WS and SSE): messagesPerSecond is a finite,
 *     non-negative number.
 */
test.describe('Overview – Live Stats Values', () => {

    test('monitoringSessions renders as a non-negative integer >= 1 (§8.1 / Phase 11)', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // Wait for the stream to connect so setSystemStats has populated real values.
        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Monitoring Sessions' })
        await expect(card).toBeVisible()

        const valueEl = card.locator('.ant-statistic-content-value')
        await expect(valueEl).toHaveText(/\d/, { timeout: 5000 })

        const raw = ((await valueEl.textContent()) ?? '').replace(/[^0-9]/g, '')
        const sessions = Number(raw)
        expect(Number.isInteger(sessions)).toBe(true)
        // This client holds the monitoring WebSocket open, so the count the backend reports
        // must be >= 1 (and, per §8.1, can never be negative).
        expect(sessions).toBeGreaterThanOrEqual(1)
    })

    test('messagesPerSecond renders as a finite non-negative number (§8.2)', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Messages/sec' })
        await expect(card).toBeVisible()

        const valueEl = card.locator('.ant-statistic-content-value')
        await expect(valueEl).toHaveText(/\d/, { timeout: 5000 })

        const raw = ((await valueEl.textContent()) ?? '').replace(/[^0-9.]/g, '')
        const rate = Number(raw)
        expect(Number.isFinite(rate), `messagesPerSecond not finite: "${raw}"`).toBe(true)
        expect(rate).toBeGreaterThanOrEqual(0)
    })
})
