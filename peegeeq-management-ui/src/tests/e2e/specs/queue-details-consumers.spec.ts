import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Queue Details – Consumers Tab
 *
 * Verifies that the Consumers tab renders real subscription data from
 * GET /api/v1/queues/:setupId/:queueName/consumers — not the stub banner.
 *
 * Creates its own queue and consumer group via the API so the tab has data.
 * Depends only on setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Details – Consumers Tab', () => {

    let queueName = ''
    let groupName = ''

    // ── Setup ─────────────────────────────────────────────────────────────────

    test('00 setup: create queue and subscribe a consumer group', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `qdcons_${Date.now()}`
        groupName = `qdcons_group_${Date.now()}`

        const qResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!qResp.ok()) {
            throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)
        }

        await page.waitForTimeout(500)

        const cgResp = await page.request.post(
            '/api/v1/management/consumer-groups',
            { data: { name: groupName, setup: SETUP_ID, queueName } }
        )
        if (!cgResp.ok()) {
            throw new Error(`Create consumer group failed: ${cgResp.status()} ${await cgResp.text()}`)
        }
    })

    // ── Tab render ────────────────────────────────────────────────────────────

    test('01 consumers tab shows a table, not the stub banner', async ({ page }) => {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15000 })

        await page.getByRole('tab', { name: /Consumers/i }).click()

        // Stub banner must be absent
        await expect(
            page.locator('.ant-alert').filter({ hasText: 'Consumers Tab - Coming in Week 5' })
        ).not.toBeVisible()

        // Table must be present
        await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 })
    })

    test('02 consumers tab shows at least one row with the consumer group name', async ({ page }) => {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15000 })

        await page.getByRole('tab', { name: /Consumers/i }).click()

        await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 })

        // At least one row must contain the group name
        const row = page.locator('.ant-table-row').filter({ hasText: groupName })
        await expect(row.first()).toBeVisible({ timeout: 5000 })
    })

    test('03 consumers tab row shows a status tag', async ({ page }) => {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 15000 })

        await page.getByRole('tab', { name: /Consumers/i }).click()

        await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 })

        const row = page.locator('.ant-table-row').filter({ hasText: groupName }).first()
        // Status column must contain an Ant Design tag
        const statusTag = row.locator('.ant-tag')
        await expect(statusTag.first()).toBeVisible()
    })

    // ── Cleanup ───────────────────────────────────────────────────────────────

    test('99 cleanup: delete the test consumer group and queue', async ({ page }) => {
        const cgResp = await page.request.delete(
            `/api/v1/management/consumer-groups/${SETUP_ID}/${queueName}/${groupName}`
        )
        expect([200, 204, 404]).toContain(cgResp.status())

        const qResp = await page.request.delete(`/api/v1/queues/${SETUP_ID}/${queueName}`)
        expect([200, 204, 404]).toContain(qResp.status())
    })
})
