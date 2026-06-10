import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Overview Page – Recent Activity and Queue Overview Table Tests
 *
 * Covers gaps:
 *   1. Recent Activity table (GET /management/overview) renders rows and
 *      status tag colours are correct (success=green, warning=orange, error=red).
 *   3. Queue Overview table shows correctly filtered items when a setup is selected.
 *   3. "View All" button in the Queue Overview card navigates to /queues.
 *
 * Uses real backend data – no route interception.
 * Depends on setup-prerequisite (SETUP_ID must exist).
 *
 * NOTE: Recent Activity is sourced from BiTemporal event stores (not queue ops).
 * The setup test creates an event store and posts one event so the table is non-empty.
 */
test.describe.configure({ mode: 'serial' })

test.describe('Overview – Recent Activity and Queue Overview', () => {

    let queueName = ''
    let eventStoreName = ''

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create event store + queue to seed overview data', async ({ page }) => {
        test.setTimeout(120000)

        // Create an event store so GET /management/overview has activity to show
        eventStoreName = `ov_activity_es_${Date.now()}`
        await page.goto('/event-stores')
        await page.getByRole('button', { name: /create event store/i }).click()
        await expect(page.locator('.ant-modal')).toBeVisible()
        await page.locator('#name').fill(eventStoreName)
        await page.getByTestId('refresh-setups-btn').click()
        await page.waitForTimeout(600)
        await selectAntOption(page.locator('.ant-modal .ant-select:has(#setupId)'), SETUP_ID)
        await page.locator('.ant-modal .ant-btn-primary').click()
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 30000 })
        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })

        // Post one event so GET /management/overview returns ≥1 activity row
        const evtResp = await page.request.post(
            `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
            {
                data: {
                    eventType: 'OverviewTestEvent',
                    eventData: { source: 'overview-recent-activity.spec.ts' },
                    aggregateId: 'ov-test-001',
                },
            }
        )
        if (!evtResp.ok()) {
            throw new Error(`Post event failed: ${evtResp.status()} ${await evtResp.text()}`)
        }

        // Also create a queue so the Queue Overview table has something to show
        queueName = `ov_activity_q_${Date.now()}`
        const qResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!qResp.ok()) {
            throw new Error(`Create queue failed: ${qResp.status()} ${await qResp.text()}`)
        }

        console.log('Seeded event store:', eventStoreName, '— queue:', queueName)
    })

    // ── 1. Recent Activity ────────────────────────────────────────────────────

    test('01 Recent Activity card renders table on the Overview page', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const activityCard = page.locator('.ant-card').filter({ hasText: 'Recent Activity' })
        await expect(activityCard).toBeVisible()

        // The card contains an Ant Design table
        await expect(activityCard.locator('.ant-table')).toBeVisible()
    })

    test('02 Recent Activity table shows rows after backend operations', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const activityCard = page.locator('.ant-card').filter({ hasText: 'Recent Activity' })
        await expect(activityCard).toBeVisible()

        // Table should have at least one row (event posted to event store in test 00)
        const rows = activityCard.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })

        const count = await rows.count()
        expect(count).toBeGreaterThanOrEqual(1)

        console.log(`Recent Activity: ${count} row(s) rendered`)
    })

    test('03 Recent Activity row status tags carry a valid colour class', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const activityCard = page.locator('.ant-card').filter({ hasText: 'Recent Activity' })
        const rows = activityCard.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })

        // Every status tag in the table must have one of the three valid colour classes
        const tags = rows.locator('.ant-tag')
        const tagCount = await tags.count()
        expect(tagCount).toBeGreaterThanOrEqual(1)

        for (let i = 0; i < tagCount; i++) {
            const cls = await tags.nth(i).getAttribute('class')
            const validColour =
                cls?.includes('ant-tag-green') ||
                cls?.includes('ant-tag-orange') ||
                cls?.includes('ant-tag-red')
            expect(
                validColour,
                `Tag ${i} class="${cls}" does not contain green, orange, or red`
            ).toBeTruthy()
        }
    })

    // ── 2. Queue Overview table ───────────────────────────────────────────────

    test('04 Queue Overview table is present on the page', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        await expect(page.getByTestId('queue-overview-table')).toBeVisible()
    })

    test('05 Queue Overview table shows rows for the selected setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        // Select the test setup in the scope bar
        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const queueTable = page.getByTestId('queue-overview-table')
        await expect(queueTable).toBeVisible()

        // After selecting a setup, the table should contain at least the queue created in test 00
        const rows = queueTable.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 15000 })

        const count = await rows.count()
        expect(count).toBeGreaterThanOrEqual(1)

        console.log(`Queue Overview: ${count} row(s) for setup "${SETUP_ID}"`)
    })

    test('06 "View All" button in Queue Overview card is visible', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const queueOverviewCard = page.locator('.ant-card').filter({ hasText: 'Queue Overview' })
        await expect(queueOverviewCard).toBeVisible()

        const viewAllBtn = queueOverviewCard.getByRole('button', { name: /view all/i })
        await expect(viewAllBtn).toBeVisible()
    })

    test('07 "View All" button in Queue Overview navigates to /queues', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')

        const queueOverviewCard = page.locator('.ant-card').filter({ hasText: 'Queue Overview' })
        const viewAllBtn = queueOverviewCard.getByRole('button', { name: /view all/i })
        await expect(viewAllBtn).toBeVisible()

        await viewAllBtn.click()

        await expect(page).toHaveURL(/\/queues/, { timeout: 5000 })
    })
})
