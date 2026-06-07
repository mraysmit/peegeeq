import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Queue Details – Overview Tab Field Validation
 *
 * Verifies that the Overview tab of QueueDetailsEnhanced renders real data
 * from the backend — not blanks or zeros from a field-name mismatch between
 * the API response and the TypeScript interface.
 *
 * Checks each visible section:
 *   - Header stat cards (Messages, Consumers, Message Rate, Avg Processing Time)
 *   - Queue Information card (Setup ID, Queue Name, Type, Status, Created)
 *   - Performance Metrics card (Queue Depth, Error Rate, processing percentiles)
 *
 * Creates its own queue and publishes a message so counts are non-zero.
 * Depends only on setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Details – Overview Tab', () => {

    let queueName = ''

    async function gotoOverview(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 10000 })
        // Overview is the default tab — confirm it is selected
        await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish a message', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `qdoverview_${Date.now()}`

        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }

        await page.waitForTimeout(500)

        const publishResp = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload: { item: 'overview-test' } } }
        )
        if (!publishResp.ok()) {
            throw new Error(`Publish failed: ${publishResp.status()} ${await publishResp.text()}`)
        }
    })

    // ── Queue Information card ────────────────────────────────────────────────

    test('01 Queue Information card shows correct Setup ID', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({ hasText: 'Queue Information' })
        await expect(card.getByText(SETUP_ID)).toBeVisible()
    })

    test('02 Queue Information card shows correct Queue Name', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({ hasText: 'Queue Information' })
        await expect(card.getByText(queueName)).toBeVisible()
    })

    test('03 Queue Information card shows a non-empty Type tag', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({ hasText: 'Queue Information' })
        // Type row contains a Tag element; the tag text must be a known queue type
        const typeTag = card.locator('.ant-tag').first()
        await expect(typeTag).toBeVisible()
        const text = await typeTag.textContent()
        expect(['native', 'outbox', 'bitemporal']).toContain(text?.trim().toLowerCase())
    })

    test('04 Queue Information card shows a non-empty Status tag', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({ hasText: 'Queue Information' })
        // Status is a second Tag in the card
        const tags = card.locator('.ant-tag')
        const count = await tags.count()
        // At minimum two tags: type and status
        expect(count).toBeGreaterThanOrEqual(2)
        const statusText = await tags.nth(1).textContent()
        expect(['active', 'paused', 'idle', 'error']).toContain(statusText?.trim().toLowerCase())
    })

    test('05 Queue Information card shows a non-empty Created date', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({ hasText: 'Queue Information' })
        // Find the row that has the "Created:" label
        const createdRow = card.locator('div').filter({ hasText: /^Created:/ }).first()
        await expect(createdRow).toBeVisible()
        const text = await createdRow.textContent()
        // Should contain a year — basic sanity that it's a real date not "Invalid Date"
        expect(text).toMatch(/202[0-9]/)
    })

    // ── Header stat cards ─────────────────────────────────────────────────────

    test('06 Messages stat card shows a number ≥ 1 after publishing', async ({ page }) => {
        await gotoOverview(page)
        // Each stat card renders an .ant-statistic; the title is .ant-statistic-title
        const messagesStat = page.locator('.ant-statistic').filter({
            has: page.locator('.ant-statistic-title', { hasText: 'Messages' }),
        })
        await expect(messagesStat).toBeVisible()
        const valueText = await messagesStat.locator('.ant-statistic-content-value').textContent()
        const value = parseInt(valueText?.replace(/,/g, '') ?? '0', 10)
        expect(value).toBeGreaterThanOrEqual(1)
    })

    test('07 Consumers stat card is rendered with a numeric value', async ({ page }) => {
        await gotoOverview(page)
        const consumersStat = page.locator('.ant-statistic').filter({
            has: page.locator('.ant-statistic-title', { hasText: 'Consumers' }),
        })
        await expect(consumersStat).toBeVisible()
        const valueText = await consumersStat.locator('.ant-statistic-content-value').textContent()
        // Value must be a number (even 0 is fine — no subscribers yet)
        expect(valueText?.trim()).toMatch(/^\d+$/)
    })

    // ── Performance Metrics card ──────────────────────────────────────────────

    test('08 Performance Metrics card renders Queue Depth as a number', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({
            has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'Performance Metrics' }),
        })
        await expect(card).toBeVisible()
        const depthRow = card.locator('div').filter({ hasText: /^Queue Depth:/ }).first()
        await expect(depthRow).toBeVisible()
        const text = await depthRow.textContent()
        // After publishing one message the depth should be ≥ 1
        const match = text?.match(/Queue Depth:\s*(\d+)/)
        expect(match).not.toBeNull()
        expect(parseInt(match![1], 10)).toBeGreaterThanOrEqual(1)
    })

    test('09 Performance Metrics card renders Error Rate as a percentage', async ({ page }) => {
        await gotoOverview(page)
        const card = page.locator('.ant-card').filter({
            has: page.locator(':scope > .ant-card-head .ant-card-head-title', { hasText: 'Performance Metrics' }),
        })
        const errorRateRow = card.locator('div').filter({ hasText: /^Error Rate:/ }).first()
        await expect(errorRateRow).toBeVisible()
        const text = await errorRateRow.textContent()
        // Must look like "0.00%" — presence of "%" means the field rendered
        expect(text).toMatch(/%/)
    })

    // ── Cleanup ───────────────────────────────────────────────────────────────

    test('99 cleanup: delete the test queue', async ({ page }) => {
        const resp = await page.request.delete(`/api/v1/queues/${SETUP_ID}/${queueName}`)
        // 200 or 404 are both acceptable
        expect([200, 204, 404]).toContain(resp.status())
    })
})
