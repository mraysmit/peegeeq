import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Queue Details – Live Messages (non-destructive)
 *
 * Phase 5 (§7.5), rebuilt to honour the admin-tool principle: the live view must NOT consume
 * messages. The "Live" toggle browse-polls GET /queues/{s}/{q}/messages
 * (createBrowser().browse() server-side) — it never subscribes a consumer — so viewing the
 * queue never drains it.
 *
 * Verifies:
 *   1. enabling Live auto-surfaces a newly published message with no manual refresh;
 *   2. viewing does NOT consume — the message is still browsable on a fresh page load (a
 *      destructive read would have removed it).
 *
 * Self-contained: creates its own queue. Depends only on setup-prerequisite (SETUP_ID).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Queue Details – Live Messages (non-destructive)', () => {

    let queueName = ''

    async function gotoMessagesTabWithLive(page: any) {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15000 })
        await page.getByRole('tab', { name: /Messages/i }).click()
        const liveSwitch = page.getByTestId('messages-live-switch')
        await expect(liveSwitch).toBeVisible()
        await liveSwitch.click()
        await expect(page.getByTestId('messages-live-indicator')).toBeVisible()
    }

    test('00 setup: create an empty queue', async ({ page }) => {
        test.setTimeout(60000)
        queueName = `qdlive_${Date.now()}`
        const r = await page.request.post('/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } })
        if (!r.ok()) {
            throw new Error(`Create queue failed: ${r.status()} ${await r.text()}`)
        }
        await page.waitForTimeout(1000) // brief queue-initialisation pause before publishing
    })

    test('01 enabling Live auto-surfaces a newly published message (read-only, no manual refresh)', async ({ page }) => {
        test.setTimeout(30000)
        await gotoMessagesTabWithLive(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        // Queue starts empty (no manual "Get Messages" performed).
        await expect(rows).toHaveCount(0)

        // Publish via the API (not the UI). The live browse-poll must surface it on its own.
        const pub = await page.request.post(`/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload: { source: 'qd-live-test' } } })
        if (!pub.ok()) {
            throw new Error(`Publish failed: ${pub.status()} ${await pub.text()}`)
        }

        // Appears via the live poll (≤ a couple of 3s intervals) — no Get Messages click.
        await expect(rows.first()).toBeVisible({ timeout: 12000 })
    })

    test('02 viewing does NOT consume — the message is still browsable on a fresh load', async ({ page }) => {
        test.setTimeout(30000)
        // Fresh navigation. If the earlier live view had consumed the message it would be gone;
        // because the view is a non-destructive browse, it must still be present.
        await gotoMessagesTabWithLive(page)
        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 12000 })
    })
})
