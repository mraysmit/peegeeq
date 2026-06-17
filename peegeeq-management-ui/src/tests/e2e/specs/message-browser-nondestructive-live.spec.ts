import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – Live Mode is NON-DESTRUCTIVE
 *
 * The management UI is an admin/observability tool: viewing must never consume messages.
 * Live mode previously opened a CONSUMING SSE stream (`/queues/{s}/{q}/stream` →
 * createConsumer/subscribe), which stole messages from the application's real consumers.
 * It now browse-polls the read-only listing (`management/messages`) faster (every 3 s).
 *
 * Verifies:
 *   1. enabling Live shows the "Real-time Mode Active" banner (toggle-driven);
 *   2. Live does NOT open the consuming `/stream` SSE (the destructive read is gone);
 *   3. Live browse-polls — a newly published message appears with no manual refresh;
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

    async function loadMessages(page: Parameters<Parameters<typeof test>[1]>[0]): Promise<void> {
        await page.goto('/messages')
        await page.waitForLoadState('load')
        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)
        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(queueSelector, queueName)
        await page.getByRole('button', { name: /refresh/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first())
            .toBeVisible({ timeout: 15000 })
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

    test('02 Live mode does NOT open the consuming /stream SSE', async ({ page }) => {
        test.setTimeout(30000)

        let streamRequested = false
        await page.route(`**/queues/${SETUP_ID}/${queueName}/stream**`, (route) => {
            streamRequested = true
            return route.abort('connectionrefused')
        })

        await loadMessages(page)
        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Give it well past a couple of poll intervals; the consuming stream must never be hit.
        await page.waitForTimeout(7000)
        expect(
            streamRequested,
            'Live mode must NOT open the consuming /stream SSE — admin views must be non-destructive'
        ).toBe(false)
    })

    // ── 3. Live browse-polls: a newly published message appears with no manual refresh ──

    test('03 Live browse-poll surfaces a newly published message', async ({ page }) => {
        test.setTimeout(30000)
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        const before = await rows.count()
        expect(before).toBeGreaterThanOrEqual(3)

        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // Publish via the API; the 3s browse-poll must surface it without a manual Refresh.
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
})
