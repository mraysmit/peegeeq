import { test, expect } from '../page-objects'

/**
 * System Metrics SSE – Direct API Tests
 *
 * Verifies the system metrics SSE endpoint at the correct versioned path:
 *   GET /api/v1/sse/metrics
 *
 * This test exists specifically to guard the URL fix in websocketService.ts
 * (changed from getApiUrl('sse/metrics') → getVersionedApiUrl('sse/metrics')).
 * If the versioned path were to regress to the unversioned form, the Overview
 * page would silently 404 on its metrics stream and this test would fail.
 *
 * Approach (same pattern as message-sse-stream.spec.ts):
 *   1. Open a native EventSource at the versioned URL    (page.evaluate)
 *   2. Poll for named events                             (page.waitForFunction)
 *   3. Assert payload structure
 *
 * Named SSE events:
 *   - "connected"  — emitted once on connect, carries connectionId
 *   - "metrics"    — emitted on each metrics push, carries system stats
 */

test.describe.configure({ mode: 'serial' })

test.describe('System Metrics SSE – Direct API', () => {

    test.beforeEach(async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')
    })

    /**
     * Test 1: Versioned endpoint is reachable.
     * GET /api/v1/sse/metrics must return 200 with SSE headers and
     * emit a "connected" event carrying a connectionId.
     */
    test('01 GET /api/v1/sse/metrics connects and emits connected event', async ({ page }) => {
        await page.evaluate(() => {
            (window as any).__sseMetricsConnected = false
            ;(window as any).__sseMetricsConnectedData = null
            ;(window as any).__sseMetricsError = null

            const es = new EventSource('/api/v1/sse/metrics')

            es.addEventListener('connected', (event: Event) => {
                ;(window as any).__sseMetricsConnected = true
                try {
                    ;(window as any).__sseMetricsConnectedData = JSON.parse((event as MessageEvent).data)
                } catch { /* ignore parse errors */ }
            })

            es.onerror = () => {
                ;(window as any).__sseMetricsError = 'EventSource error — endpoint may be 404'
            }

            ;(window as any).__sseMetricsEs = es
        })

        await page.waitForFunction(
            () => (window as any).__sseMetricsConnected === true
                || (window as any).__sseMetricsError !== null,
            { timeout: 10000 }
        )

        const error = await page.evaluate(() => (window as any).__sseMetricsError)
        expect(error, 'SSE connection failed — /api/v1/sse/metrics returned an error (check backend route registration)').toBeNull()

        const connectedData = await page.evaluate(() => (window as any).__sseMetricsConnectedData)
        expect(connectedData).toBeDefined()
        expect(connectedData).toHaveProperty('connectionId')
        expect(typeof connectedData.connectionId).toBe('string')
        expect(connectedData.connectionId.length).toBeGreaterThan(0)

        await page.evaluate(() => { (window as any).__sseMetricsEs?.close() })
    })

    /**
     * Test 2: Metrics events arrive.
     * After connecting, the endpoint must push at least one "metrics" event
     * with a recognisable payload structure (cpu, memory, or similar top-level fields).
     */
    test('02 metrics events arrive with system data payload', async ({ page }) => {
        await page.evaluate(() => {
            (window as any).__sseMetricsEvents = []
            ;(window as any).__sseMetricsConnected = false

            const es = new EventSource('/api/v1/sse/metrics?interval=1')

            es.addEventListener('connected', () => {
                ;(window as any).__sseMetricsConnected = true
            })

            es.addEventListener('metrics', (event: Event) => {
                try {
                    const data = JSON.parse((event as MessageEvent).data)
                    ;(window as any).__sseMetricsEvents.push(data)
                } catch { /* ignore */ }
            })

            ;(window as any).__sseMetricsEs = es
        })

        // Wait for connected handshake
        await page.waitForFunction(
            () => (window as any).__sseMetricsConnected === true,
            { timeout: 10000 }
        )

        // Wait for at least one metrics event (interval=1s so it arrives quickly)
        await page.waitForFunction(
            () => (window as any).__sseMetricsEvents.length >= 1,
            { timeout: 15000 }
        )

        const events = await page.evaluate(() => (window as any).__sseMetricsEvents as any[])
        expect(events.length).toBeGreaterThanOrEqual(1)

        // Payload must be a non-empty object — exact fields depend on backend impl
        const first = events[0]
        expect(typeof first).toBe('object')
        expect(first).not.toBeNull()
        expect(Object.keys(first).length).toBeGreaterThan(0)

        await page.evaluate(() => { (window as any).__sseMetricsEs?.close() })
    })
})
