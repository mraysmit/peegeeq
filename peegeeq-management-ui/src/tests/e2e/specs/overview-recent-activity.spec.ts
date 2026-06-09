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
 */
test.describe.configure({ mode: 'serial' })

test.describe('Overview – Recent Activity and Queue Overview', () => {

    let queueName = ''

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create a queue to seed overview activity', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `ov_activity_${Date.now()}`

        const resp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!resp.ok()) {
            throw new Error(`Create queue failed: ${resp.status()} ${await resp.text()}`)
        }

        console.log('Created queue for overview activity test:', queueName)
    })

    // ── 1. Recent Activity ────────────────────────────────────────────────────

    test('01 Recent Activity card renders table on the Overview page', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('networkidle')

        const activityCard = page.locator('.ant-card').filter({ hasText: 'Recent Activity' })
        await expect(activityCard).toBeVisible()

        // The card contains an Ant Design table
        await expect(activityCard.locator('.ant-table')).toBeVisible()
    })

    test('02 Recent Activity table shows rows after backend operations', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('networkidle')

        const activityCard = page.locator('.ant-card').filter({ hasText: 'Recent Activity' })
        await expect(activityCard).toBeVisible()

        // Table should have at least one row (queue creation in test 00 logged activity)
        const rows = activityCard.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })

        const count = await rows.count()
        expect(count).toBeGreaterThanOrEqual(1)

        console.log(`Recent Activity: ${count} row(s) rendered`)
    })

    test('03 Recent Activity row status tags carry a valid colour class', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('networkidle')

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
        await page.waitForLoadState('networkidle')

        await expect(page.getByTestId('queue-overview-table')).toBeVisible()
    })

    test('05 Queue Overview table shows rows for the selected setup', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('networkidle')

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
        await page.waitForLoadState('networkidle')

        const queueOverviewCard = page.locator('.ant-card').filter({ hasText: 'Queue Overview' })
        await expect(queueOverviewCard).toBeVisible()

        const viewAllBtn = queueOverviewCard.getByRole('button', { name: /view all/i })
        await expect(viewAllBtn).toBeVisible()
    })

    test('07 "View All" button in Queue Overview navigates to /queues', async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('networkidle')

        const queueOverviewCard = page.locator('.ant-card').filter({ hasText: 'Queue Overview' })
        const viewAllBtn = queueOverviewCard.getByRole('button', { name: /view all/i })
        await expect(viewAllBtn).toBeVisible()

        await viewAllBtn.click()

        await expect(page).toHaveURL(/\/queues/, { timeout: 5000 })
    })
})
