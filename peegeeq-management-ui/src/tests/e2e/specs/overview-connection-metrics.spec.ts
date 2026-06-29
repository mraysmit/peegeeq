import { test, expect } from '@playwright/test'

/**
 * Overview Page – Connection Metrics (Phase 11)
 *
 * The old Overview page surfaced a single meaningless `activeConnections` composite. Phase 11
 * split it backend-side (SystemMonitoringHandler) into three distinct dimensions emitted on the
 * `system_stats` frame and surfaced here as three cards plus a stacked DB-pool area chart:
 *   - monitoringSessions  — browser sessions watching the live feed (the monitoring WebSocket)
 *   - activeSubscriptions — registered consumer-group subscriptions
 *   - dbPool.{active,idle,pending,total} — live PostgreSQL connection breakdown (pg_stat_activity)
 *
 * This spec asserts the three cards render with the real, live values streamed over the
 * monitoring WebSocket, and that the DB-pool invariant active <= total holds in the UI.
 *
 * Standalone — no setup prerequisite; the Overview page opens the monitoring WS/SSE on load.
 */
test.describe('Overview – Connection Metrics (Phase 11)', () => {

    test.beforeEach(async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')
        // Wait for the stream to connect so setSystemStats has populated real values.
        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })
    })

    /** Read the integer in a Statistic card identified by its title text. */
    async function readMetric(page: import('@playwright/test').Page, title: string): Promise<number> {
        const card = page.locator('.ant-statistic').filter({ hasText: title })
        await expect(card).toBeVisible()
        const valueEl = card.locator('.ant-statistic-content-value')
        await expect(valueEl).toHaveText(/\d/, { timeout: 5000 })
        const raw = ((await valueEl.textContent()) ?? '').replace(/[^0-9]/g, '')
        return Number(raw)
    }

    test('the three split connection-metric cards render with non-negative integers', async ({ page }) => {
        const metrics = page.getByTestId('connection-metrics')
        await expect(metrics).toBeVisible()

        const monitoringSessions = await readMetric(page, 'Monitoring Sessions')
        const activeSubscriptions = await readMetric(page, 'Active Subscriptions')
        const dbConnections = await readMetric(page, 'DB Connections')

        for (const v of [monitoringSessions, activeSubscriptions, dbConnections]) {
            expect(Number.isInteger(v)).toBe(true)
            expect(v).toBeGreaterThanOrEqual(0)
        }

        // This client holds the monitoring WebSocket open, so there is always at least one session.
        expect(monitoringSessions).toBeGreaterThanOrEqual(1)
    })

    test('DB Connections card shows "active / total" with active <= total', async ({ page }) => {
        const card = page.locator('.ant-statistic').filter({ hasText: 'DB Connections' })
        await expect(card).toBeVisible()

        const active = Number((((await card.locator('.ant-statistic-content-value').textContent()) ?? '')
            .replace(/[^0-9]/g, '')))
        // The suffix renders the live pool total as "/ N total".
        const suffix = (await card.locator('.ant-statistic-content-suffix').textContent()) ?? ''
        const totalMatch = suffix.match(/(\d+)\s*total/)
        expect(totalMatch, `expected "/ <n> total" suffix, got: "${suffix}"`).not.toBeNull()
        const total = Number(totalMatch![1])

        expect(Number.isInteger(active)).toBe(true)
        expect(Number.isInteger(total)).toBe(true)
        expect(active).toBeGreaterThanOrEqual(0)
        // The core Phase 11 backend invariant, mirrored client-side.
        expect(active).toBeLessThanOrEqual(total)
    })

    test('the DB Pool Connections chart card replaces the old "Active Connections" line chart', async ({ page }) => {
        // Title was renamed; the meaningless single-series "Active Connections" chart is gone.
        await expect(page.getByText('DB Pool Connections', { exact: true })).toBeVisible()
        await expect(page.locator('.ant-card-head-title', { hasText: /^Active Connections$/ })).toHaveCount(0)
    })

    test('recent-activity text reports active DB connections, not the old composite', async ({ page }) => {
        const systemStatus = page.getByTestId('system-status-info')
        await expect(systemStatus).toContainText(/active DB connections/, { timeout: 5000 })

        const text = (await systemStatus.textContent()) ?? ''
        const match = text.match(/(\d+)\s+active DB connections/)
        expect(match, `expected "<n> active DB connections" in: ${text}`).not.toBeNull()
        expect(Number.isInteger(Number(match![1]))).toBe(true)
    })
})
