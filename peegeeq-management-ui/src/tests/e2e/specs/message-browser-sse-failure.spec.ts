import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – EventSource (SSE) Failure Recovery Tests
 *
 * Covers gap:
 *   2. No tests check handling of EventSource connection timeouts, dropouts, or
 *      backend API socket errors when the Live mode SSE stream fails.
 *
 * How live mode works in MessageBrowser.tsx:
 *   - When isRealTimeEnabled becomes true AND a setup/queue are selected, a
 *     native EventSource is opened at:
 *       /api/v1/queues/{setupId}/{queueName}/stream
 *   - The EventSource has no onerror handler — failures are silent at the
 *     component level.
 *   - The browser automatically retries the EventSource connection after the
 *     SSE reconnection interval (~3 s).
 *   - The component cleanup function closes the EventSource when isRealTimeEnabled
 *     becomes false or when the component unmounts.
 *
 * Test strategy:
 *   1. Create a dedicated queue and publish 3 known messages.
 *   2. Load those messages in the Message Browser via the scope selectors.
 *   3. Register a route handler that ABORTS the SSE stream endpoint, simulating
 *      a backend socket error / connection failure.
 *   4. Enable Live mode (click the live-switch).
 *   5. Verify the SSE request was attempted (and aborted by the route handler).
 *   6. Verify the "Real-time Mode Active" banner is visible — the component
 *      entered live mode despite the SSE failure.
 *   7. Verify previously loaded messages are still present in the table —
 *      the SSE failure does NOT wipe existing data.
 *   8. Verify Live mode can be turned off cleanly (toggle is still responsive).
 *   9. After disabling live mode, verify the banner is gone.
 *
 * ── NO-MOCK POLICY EXCEPTION (fault injection) ─────────────────────────────
 * The project rule is "no mocking": tests hit the real backend. The setup/queue
 * and messages here ARE real (created via the API). The only stub is route.abort
 * on the SSE stream — a sanctioned exception (decision 2026-06-15) because a
 * healthy backend will not drop a valid SSE connection on demand. This is
 * deliberate fault injection to exercise the Live-mode failure path, NOT data
 * mocking.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Message Browser – EventSource Failure Recovery', () => {

    let queueName = ''

    // ── helpers ───────────────────────────────────────────────────────────────

    async function publishMessage(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: object
    ): Promise<void> {
        const resp = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload } }
        )
        if (!resp.ok()) {
            throw new Error(`Publish failed: ${resp.status()} ${await resp.text()}`)
        }
    }

    async function loadMessages(
        page: Parameters<Parameters<typeof test>[1]>[0]
    ): Promise<void> {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(queueSelector, queueName)

        await page.getByRole('button', { name: /refresh/i }).click()

        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').first()
        ).toBeVisible({ timeout: 15000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish 3 test messages', async ({ page }) => {
        test.setTimeout(120000)

        queueName = `mb_sse_fail_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }

        await page.waitForTimeout(1000)

        await publishMessage(page, { orderId: 'fail-01', category: 'SseFailureTest' })
        await publishMessage(page, { orderId: 'fail-02', category: 'SseFailureTest' })
        await publishMessage(page, { orderId: 'fail-03', category: 'SseFailureTest' })

        console.log('Created queue for SSE failure test:', queueName)
    })

    // ── 1. SSE failure does not crash the page ────────────────────────────────

    test('01 enabling Live mode with a failing SSE endpoint shows the live banner', async ({ page }) => {
        test.setTimeout(30000)

        // Register abort BEFORE loading the page so the route is in place when
        // the EventSource is opened
        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) =>
            route.abort('connectionrefused')
        )

        await loadMessages(page)

        // Enable live mode
        const liveSwitch = page.getByTestId('live-switch')
        await liveSwitch.click()

        // The component enters live mode (isRealTimeEnabled = true) regardless of
        // SSE connection state — the banner reflects the toggle state, not the
        // connection health
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })
        await expect(page.getByTestId('live-alert')).toContainText('Real-time Mode Active')
    })

    test('02 SSE failure preserves previously loaded messages in the table', async ({ page }) => {
        test.setTimeout(30000)

        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) =>
            route.abort('connectionrefused')
        )

        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        const initialCount = await rows.count()
        expect(initialCount).toBeGreaterThanOrEqual(3)

        // Enable live mode while SSE is failing
        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Wait a moment to let any erroneous side-effects clear
        await page.waitForTimeout(2000)

        // Row count must not drop — SSE failure must not wipe existing messages
        const countAfterFailure = await rows.count()
        expect(
            countAfterFailure,
            'SSE failure must not remove previously loaded messages from the table'
        ).toBeGreaterThanOrEqual(initialCount)
    })

    test('03 Live switch can be turned OFF after an SSE failure', async ({ page }) => {
        test.setTimeout(30000)

        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) =>
            route.abort('connectionrefused')
        )

        await loadMessages(page)

        const liveSwitch = page.getByTestId('live-switch')

        // Turn on
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Turn off — the switch must be responsive even after an SSE error
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).not.toBeVisible({ timeout: 5000 })
        await expect(liveSwitch).not.toHaveClass(/ant-switch-checked/)
    })

    test('04 SSE abort is attempted when Live mode is enabled', async ({ page }) => {
        test.setTimeout(30000)

        let sseAttempted = false
        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) => {
            sseAttempted = true
            return route.abort('connectionrefused')
        })

        await loadMessages(page)

        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Allow a brief window for the EventSource request to fire
        await page.waitForTimeout(2000)

        expect(
            sseAttempted,
            'MessageBrowser must attempt to connect to the SSE stream when Live mode is enabled'
        ).toBeTruthy()
    })

    test('05 messages remain visible and table is stable after repeated SSE failure retries', async ({ page }) => {
        test.setTimeout(30000)

        let abortCount = 0
        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) => {
            abortCount++
            return route.abort('connectionrefused')
        })

        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        const baselineCount = await rows.count()

        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Allow time for at least one retry cycle (SSE default retry is ~3 s)
        await page.waitForTimeout(5000)

        // Table must still be stable
        const finalCount = await rows.count()
        expect(
            finalCount,
            'Table row count must not decrease after SSE failure retries'
        ).toBeGreaterThanOrEqual(baselineCount)

        console.log(`SSE stream was aborted ${abortCount} times during the test`)
    })
})
