import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – Live Mode failure & recovery
 *
 * Complements `message-browser-nondestructive-live.spec.ts` (which proves the happy path) by
 * covering the Live-mode dropout path that was previously untested end-to-end (Open Items #3):
 *
 *   1. A TERMINAL disconnect of the non-destructive `/messages/stream` SSE surfaces the
 *      `message.error('Live message stream disconnected…')` toast. `MessageBrowser`'s `es.onerror`
 *      only reports when `readyState === EventSource.CLOSED`, so transient auto-reconnects (native
 *      EventSource, readyState CONNECTING) do NOT spam the user — only a genuine failure does.
 *   2. Recovery via the documented action ("Toggle Live off and on to retry"): once the stream is
 *      reachable again, toggling Live off then on re-establishes it and a freshly published message
 *      surfaces over the live push with no manual refresh.
 *
 * A terminal failure is forced deterministically by fulfilling the SSE request with a non
 * `text/event-stream` response: per the EventSource spec the browser fails the connection and sets
 * `readyState = CLOSED` (no auto-reconnect), which is exactly the branch the toast guards.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Message Browser – Live Mode (failure & recovery)', () => {

    let queueName = ''

    // Glob for the non-destructive live SSE stream (NOT the removed consuming `/stream`).
    const streamGlob = () => `**/queues/${SETUP_ID}/${queueName}/messages/stream**`

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

    test('00 setup: create queue and publish a message', async ({ page }) => {
        test.setTimeout(120000)
        queueName = `mb_fail_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }
        await page.waitForTimeout(1000)
        await publishMessage(page, { orderId: 'fail-01', category: 'FailTest' })
    })

    // ── 1. Terminal disconnect surfaces the error toast ─────────────────────────

    test('01 a terminal stream disconnect surfaces message.error', async ({ page }) => {
        test.setTimeout(30000)

        // Force a permanent EventSource failure: a 200 with a non text/event-stream content type
        // makes the browser fail the connection (readyState CLOSED) rather than auto-reconnect.
        await page.route(streamGlob(), (route) =>
            route.fulfill({ status: 200, contentType: 'text/plain', body: 'not-an-event-stream' }))

        await loadMessages(page)
        await page.getByTestId('live-switch').click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })

        // The onerror → CLOSED branch must report the terminal disconnect to the user.
        await expect(page.locator('.ant-message-error'))
            .toContainText('Live message stream disconnected', { timeout: 10000 })

        // The page must remain usable — the previously browsed message is still listed
        // (a failed live stream must not break the read-only view).
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first())
            .toBeVisible({ timeout: 5000 })
    })

    // ── 2. Recovery: toggle off/on re-establishes the stream ────────────────────

    test('02 Live recovers when the stream is reachable again and re-toggled', async ({ page }) => {
        test.setTimeout(60000)

        // Start with the stream failing terminally, as in test 01.
        await page.route(streamGlob(), (route) =>
            route.fulfill({ status: 200, contentType: 'text/plain', body: 'not-an-event-stream' }))

        await loadMessages(page)
        const liveSwitch = page.getByTestId('live-switch')
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })
        await expect(page.locator('.ant-message-error'))
            .toContainText('Live message stream disconnected', { timeout: 10000 })

        // Stream becomes reachable again: drop the interception so the real SSE connects.
        await page.unroute(streamGlob())

        // Documented recovery action: toggle Live off, then on, to re-open the stream.
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).not.toBeVisible({ timeout: 5000 })
        const reopened = page.waitForRequest(
            req => req.url().includes(`/queues/${SETUP_ID}/${queueName}/messages/stream`),
            { timeout: 10000 }
        )
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 8000 })
        await reopened  // the non-destructive stream must be re-opened on recovery

        // A freshly published message must now surface over the recovered live push.
        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        const before = await rows.count()
        await publishMessage(page, { orderId: 'fail-recovered', category: 'FailTest' })
        await expect.poll(async () => rows.count(), { timeout: 15000 }).toBeGreaterThan(before)
    })
})
