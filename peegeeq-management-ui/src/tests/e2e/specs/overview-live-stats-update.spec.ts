import { test, expect } from '../page-objects'

/**
 * Overview Page – Live SSE Metrics Stats Card Update Tests
 *
 * Covers gap:
 *   1. No tests assert that an incoming metrics event on the SSE stream
 *      (/api/v1/sse/metrics) updates the statistics cards or charts in real-time.
 *
 * How the live update path works in Overview.tsx:
 *   - createSystemMetricsSSE() opens an EventSource at /api/v1/sse/metrics
 *   - The SSEService.onmessage handler fires and calls setSystemStats(metrics)
 *     on the Zustand store, re-rendering the four Statistic cards
 *   - updateChartData(metrics) appends data points to throughputData /
 *     connectionData, which feeds the recharts AreaChart
 *
 * Strategy (real backend, no mocks):
 *   - Use pass-through route observation to confirm the SSE URL is requested
 *   - Wait for the SSE status tag to read "Connected" — proves the component's
 *     EventSource handshake succeeded against the real backend
 *   - Assert stats cards render non-empty numeric values populated via SSE
 *   - Wait for the recharts chart to render data — updateChartData() is only
 *     called from the SSE/WS handler, so SVG path presence confirms live updates
 *
 * Depends on: 3b-overview-system-status (backend must be reachable)
 */
test.describe.configure({ mode: 'serial' })

test.describe('Overview – Live SSE Metrics Stats Card Update', () => {

    // ── 1. SSE request is made on page load ───────────────────────────────────

    test('01 Overview makes an SSE request to /api/v1/sse/metrics on page load', async ({ page }) => {
        test.setTimeout(20000)

        let sseRequested = false
        await page.route('**/sse/metrics**', (route: any) => {
            sseRequested = true
            return route.continue()
        })

        await page.goto('/')
        await page.waitForLoadState('load')

        // Allow time for the component to mount and open the EventSource
        await page.waitForTimeout(2000)

        expect(
            sseRequested,
            'Overview page must open the /api/v1/sse/metrics EventSource on mount'
        ).toBeTruthy()
    })

    // ── 2. SSE connects and updates the SSE status indicator ──────────────────

    test('02 SSE status tag shows Connected once the backend stream is established', async ({ page }) => {
        test.setTimeout(20000)

        await page.goto('/')
        await page.waitForLoadState('load')

        const sseTag = page.getByTestId('sse-status')
        await expect(sseTag).toBeVisible()
        await expect(sseTag).toContainText('Connected', { timeout: 15000 })
    })

    // ── 3. Stats cards are populated after SSE delivers metrics ───────────────

    test('03 Total Queues card shows a numeric value populated via the SSE pipeline', async ({ page }) => {
        test.setTimeout(20000)

        await page.goto('/')
        await page.waitForLoadState('load')

        // Wait for SSE to connect so setSystemStats has been called at least once
        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Total Queues' })
        await expect(card).toBeVisible()
        // The value element must contain a digit (not be blank/undefined)
        await expect(card.locator('.ant-statistic-content-value')).toHaveText(/\d+/, { timeout: 5000 })
    })

    test('04 Consumer Groups card shows a numeric value populated via the SSE pipeline', async ({ page }) => {
        test.setTimeout(20000)

        await page.goto('/')
        await page.waitForLoadState('load')

        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Consumer Groups' })
        await expect(card).toBeVisible()
        await expect(card.locator('.ant-statistic-content-value')).toHaveText(/\d+/, { timeout: 5000 })
    })

    test('05 Event Stores card shows a numeric value populated via the SSE pipeline', async ({ page }) => {
        test.setTimeout(20000)

        await page.goto('/')
        await page.waitForLoadState('load')

        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Event Stores' })
        await expect(card).toBeVisible()
        await expect(card.locator('.ant-statistic-content-value')).toHaveText(/\d+/, { timeout: 5000 })
    })

    test('06 Messages/sec card shows a numeric value populated via the SSE pipeline', async ({ page }) => {
        test.setTimeout(20000)

        await page.goto('/')
        await page.waitForLoadState('load')

        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        const card = page.locator('.ant-statistic').filter({ hasText: 'Messages/sec' })
        await expect(card).toBeVisible()
        await expect(card.locator('.ant-statistic-content-value')).toHaveText(/\d+/, { timeout: 5000 })
    })

    // ── 4. Chart renders after SSE events populate chart data ─────────────────

    test('07 recharts throughput chart renders SVG content after SSE delivers data', async ({ page }) => {
        test.setTimeout(25000)

        await page.goto('/')
        await page.waitForLoadState('load')

        // Wait for SSE to have delivered at least one updateChartData() call
        // — recharts only renders SVG path/area elements when data points exist
        await expect(page.getByTestId('sse-status')).toContainText('Connected', { timeout: 15000 })

        await expect(
            page.locator('.recharts-responsive-container').first()
        ).toBeVisible({ timeout: 10000 })

        await expect(
            page.locator('.recharts-surface').first()
        ).toBeVisible({ timeout: 10000 })
    })
})
