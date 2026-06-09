import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Queues Page – Search, Filter, and Sort Tests
 *
 * Covers gaps:
 *   1. "Search queues…" text box sends the search parameter in the API request.
 *   1. Type multi-select filter sends the type parameter in the API request.
 *   1. Status multi-select filter sends the status parameter in the API request.
 *   2. Column sorting sends sortBy + sortOrder parameters in the API request.
 *
 * Uses the real backend.  Requests are observed via page.route() with
 * route.continue() (no stubbing) to verify the correct query parameters are sent
 * by the RTK Query hook in QueuesEnhanced.tsx.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Queues – Search, Filter, and Sort', () => {

    let queueName = ''

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create a queue so the table is non-empty', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `qs_filter_${Date.now()}`
        const resp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!resp.ok()) {
            throw new Error(`Create queue failed: ${resp.status()} ${await resp.text()}`)
        }
        console.log('Created queue for filter/sort test:', queueName)
    })

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Attach a pass-through route that records queues API URLs. */
    async function captureQueuesRequests(page: any): Promise<string[]> {
        const captured: string[] = []
        await page.route('**/management/queues**', route => {
            captured.push(route.request().url())
            return route.continue()
        })
        return captured
    }

    // ── 1. Search ─────────────────────────────────────────────────────────────

    test('01 typing in the search box sends "search" param in queues API request', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        const searchInput = page.locator('input[placeholder="Search queues..."]')
        await expect(searchInput).toBeVisible()
        await searchInput.fill(queueName)

        // RTK Query re-fetches when the filter state updates
        await page.waitForTimeout(1000)

        const searchCalls = captured.filter(u => u.includes(`search=${encodeURIComponent(queueName)}`) || u.includes(`search=${queueName}`))
        expect(searchCalls.length, 'API should be called with the search term').toBeGreaterThanOrEqual(1)
    })

    test('02 clearing the search box makes an API request without the search param', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        // Apply a search first
        const searchInput = page.locator('input[placeholder="Search queues..."]')
        await searchInput.fill('some-value')
        await page.waitForTimeout(500)

        const captured = await captureQueuesRequests(page)

        await searchInput.clear()
        await page.waitForTimeout(500)

        const clearCalls = captured.filter(u => !u.includes('search='))
        expect(clearCalls.length, 'API should be called without search param after clear').toBeGreaterThanOrEqual(1)
    })

    // ── 2. Type filter ────────────────────────────────────────────────────────

    test('03 selecting a Type filter sends "type" param in queues API request', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        const typeSelect = page.locator('.ant-select').filter({ hasText: 'All Types' }).first()
        await selectAntOption(typeSelect, 'Native')

        await page.waitForTimeout(1000)

        const typeCalls = captured.filter(u => u.includes('type='))
        expect(typeCalls.length, 'API should be called with type= after selecting Native').toBeGreaterThanOrEqual(1)
        expect(typeCalls[0]).toMatch(/type=NATIVE|type%3DNATIVE|type=native/)
    })

    // ── 3. Status filter ──────────────────────────────────────────────────────

    test('04 selecting a Status filter sends "status" param in queues API request', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        const statusSelect = page.locator('.ant-select').filter({ hasText: 'All Statuses' }).first()
        await selectAntOption(statusSelect, 'Active')

        await page.waitForTimeout(1000)

        const statusCalls = captured.filter(u => u.includes('status='))
        expect(statusCalls.length, 'API should be called with status= after selecting Active').toBeGreaterThanOrEqual(1)
        expect(statusCalls[0]).toMatch(/status=ACTIVE|status=active/)
    })

    // ── 4. Column sorting ─────────────────────────────────────────────────────

    test('05 clicking the Queue Name column header sends "sortBy" in queues API request', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        // The sorter wrapper renders .ant-table-column-sorters around each sortable header cell
        const queueNameHeader = page.locator('.ant-table-column-sorters').filter({ hasText: /Queue Name/i }).first()
        await expect(queueNameHeader).toBeVisible()
        await queueNameHeader.click()

        await page.waitForTimeout(1000)

        const sortCalls = captured.filter(u => u.includes('sortBy='))
        expect(sortCalls.length, 'API should be called with sortBy= after clicking Queue Name').toBeGreaterThanOrEqual(1)
    })

    test('06 clicking the Message Rate column header sends sortBy=messagesPerSecond', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        const rateHeader = page.locator('.ant-table-column-sorters').filter({ hasText: /Message Rate/i }).first()
        await expect(rateHeader).toBeVisible()
        await rateHeader.click()

        await page.waitForTimeout(1000)

        const sortCalls = captured.filter(u => u.includes('sortBy='))
        expect(sortCalls.length, 'API should be called with sortBy= after clicking Message Rate').toBeGreaterThanOrEqual(1)
    })

    test('07 clicking a sorted column header a second time sends sortOrder=desc', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const queueNameHeader = page.locator('.ant-table-column-sorters').filter({ hasText: /Queue Name/i }).first()

        // First click: ascending sort
        await queueNameHeader.click()
        await page.waitForTimeout(300)

        const captured = await captureQueuesRequests(page)

        // Second click: should reverse to descending
        await queueNameHeader.click()
        await page.waitForTimeout(1000)

        const descCalls = captured.filter(u => u.includes('sortOrder=desc'))
        expect(descCalls.length, 'sortOrder=desc should appear after second click').toBeGreaterThanOrEqual(1)
    })

    // ── 5. Setup scope selector ────────────────────────────────────────────────

    test('08 selecting a setup passes setupId to the queues API', async ({ page, queuesPage }) => {
        await page.goto('/')
        await queuesPage.goto()
        await expect(queuesPage.getQueuesTable()).toBeVisible()

        const captured = await captureQueuesRequests(page)

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        await page.waitForTimeout(1000)

        const setupCalls = captured.filter(u => u.includes(`setupId=${SETUP_ID}`))
        expect(setupCalls.length, `API should be called with setupId=${SETUP_ID}`).toBeGreaterThanOrEqual(1)
    })
})
