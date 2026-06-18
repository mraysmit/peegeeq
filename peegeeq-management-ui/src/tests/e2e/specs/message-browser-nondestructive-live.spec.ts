import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – Live Mode is NON-DESTRUCTIVE
 *
 * The management UI is an admin/observability tool: viewing must never consume messages.
 * Live mode previously opened a CONSUMING SSE stream (`/queues/{s}/{q}/stream` →
 * createConsumer/subscribe), which stole messages from the application's real consumers.
 * It now opens the NON-DESTRUCTIVE message stream (`/queues/{s}/{q}/messages/stream`, backed by
 * QueueBrowser.tail() → plain SELECT, never a consumer) and refreshes the read-only listing on
 * each push.
 *
 * Verifies:
 *   1. enabling Live shows the "Real-time Mode Active" banner (toggle-driven);
 *   2. Live opens the non-destructive `/messages/stream` SSE and NEVER the consuming `/stream`;
 *   3. Live surfaces a newly published message over SSE with no manual refresh;
 *   4. messages are preserved / re-loadable (viewing does not consume);
 *   5. the Live toggle can be turned off cleanly.
 *
 * Replaces the old "EventSource Failure Recovery" spec, whose premise (a consuming SSE that
 * could fail) no longer exists. Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Message Browser – Live Mode (non-destructive)', () => {

    let queueName = ''

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
        page: Parameters<Parameters<typeof test>[1]>[0],
        expectRows = true
    ): Promise<void> {
        await page.goto('/messages')
        await page.waitForLoadState('load')
        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(queueSelector, queueName)
        await page.getByRole('button', { name: /refresh/i }).click()
        if (expectRows) {
            await expect(page.locator('.ant-table-tbody tr.ant-table-row').first())
                .toBeVisible({ timeout: 15000 })
        }
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish 3 test messages', async ({ page }) => {
        test.setTimeout(120000)
        queueName = `mb_live_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }
        await page.waitForTimeout(1000)
        await publishMessage(page, { orderId: 'live-01', category: 'LiveTest' })
        await publishMessage(page, { orderId: 'live-02', category: 'LiveTest' })
        await publishMessage(page, { orderId: 'live-03', category: 'LiveTest' })
    })

    // ── 1. Banner is toggle-driven ─────────────────────────────────────────────

    test('01 enabling Live shows the Real-time Mode banner', async ({ page }) => {
        test.setTimeout(30000)
        await loadMessages(page)
        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })
        await expect(page.getByTestId('live-alert')).toContainText('Real-time Mode Active')
    })

    // ── 2. Live does NOT open the consuming SSE stream (non-destructive) ─────────

    test('02 Live opens the non-destructive /messages/stream, never the consuming /stream', async ({ page }) => {
        test.setTimeout(30000)

        let consumingStreamRequested = false
        // The OLD consuming stream (.../{queueName}/stream) must never be hit. This glob does NOT
        // match the new .../{queueName}/messages/stream (different path segment).
        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) => {
            consumingStreamRequested = true
            return route.abort('connectionrefused')
        })

        await loadMessages(page)

        // The non-destructive message stream MUST be opened when Live is enabled.
        const nonDestructiveStream = page.waitForRequest(
            req => req.url().includes(`/queues/${SETUP_ID}/${queueName}/messages/stream`),
            { timeout: 10000 }
        )
        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        await nonDestructiveStream  // fails if the non-destructive SSE stream was not opened

        // And the consuming stream must never have been hit.
        await page.waitForTimeout(2000)
        expect(
            consumingStreamRequested,
            'Live mode must NOT open the consuming /stream SSE — admin views must be non-destructive'
        ).toBe(false)
    })

    // ── 3. Live SSE: a newly published message appears with no manual refresh ──

    test('03 Live SSE surfaces a newly published message', async ({ page }) => {
        test.setTimeout(30000)
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        const before = await rows.count()
        expect(before).toBeGreaterThanOrEqual(3)

        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Publish via the API; the live SSE push must surface it without a manual Refresh.
        await publishMessage(page, { orderId: 'live-04', category: 'LiveTest' })

        await expect.poll(async () => rows.count(), { timeout: 12000 })
            .toBeGreaterThan(before)
    })

    // ── 4. Non-destructive: messages are still there on a fresh load ────────────

    test('04 viewing does not consume — messages still load on a fresh visit', async ({ page }) => {
        test.setTimeout(30000)
        await loadMessages(page)
        const count = await page.locator('.ant-table-tbody tr.ant-table-row').count()
        expect(count, 'previously published messages must still be browsable (not consumed)')
            .toBeGreaterThanOrEqual(3)
    })

    // ── 5. Toggle off ──────────────────────────────────────────────────────────

    test('05 Live toggle turns off cleanly', async ({ page }) => {
        test.setTimeout(30000)
        await loadMessages(page)
        const liveSwitch = page.getByTestId('live-switch')
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).not.toBeVisible({ timeout: 5000 })
        await expect(liveSwitch).not.toHaveClass(/ant-switch-checked/)
    })

    // ── 6. Live SSE pushes a 10-message burst to the UI ─────────────────────────

    test('06 Live SSE pushes a 10-message burst to the UI without manual refresh', async ({ page }) => {
        test.setTimeout(120000)

        // Self-contained: create a fresh queue so this test can be run in isolation too — e.g.
        // headed:  npx playwright test --project=14c2-message-browser-nondestructive-live \
        //          --workers=1 --headed -g "10-message burst"
        // (it does not rely on the serial '00 setup' test having created the shared queue).
        queueName = `mb_burst_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }
        await page.waitForTimeout(1000)

        // Fresh queue is empty, so don't wait for an initial row.
        await loadMessages(page, false)

        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Publish 10 messages via the API; the live SSE push must surface all 10 with no manual Refresh.
        const burstId = `burst-${Date.now()}`
        for (let i = 1; i <= 10; i++) {
            await publishMessage(page, { orderId: `${burstId}-${i}`, category: 'LiveBurst' })
        }

        // Each non-destructive SSE push wakes a browse refresh; all 10 must appear (no manual refresh).
        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect.poll(async () => rows.count(), { timeout: 25000 }).toBeGreaterThanOrEqual(10)

        // Non-destructive: all 10 are still browsable on a fresh load (nothing was consumed).
        await loadMessages(page, true)
        await expect.poll(async () => rows.count(), { timeout: 10000 }).toBeGreaterThanOrEqual(10)
    })
})
