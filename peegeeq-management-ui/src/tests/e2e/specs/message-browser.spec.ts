import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – Comprehensive Integration Tests
 *
 * Creates a dedicated queue, publishes 4 known messages via the backend API,
 * then validates:
 *
 *   - Message retrieval (management/messages endpoint)
 *   - Client-side search filter (payload and ID content)
 *   - Clear filter button
 *   - Refresh triggers API re-fetch
 *   - Advanced filters drawer
 *   - Message detail modal
 *   - Live / SSE mode: real messages streamed from the backend
 *
 * Data set (4 messages, published via API):
 *   1. payload: { orderId: "apple-001", category: "FruitOrder" }
 *   2. payload: { orderId: "banana-002", category: "FruitOrder" }
 *   3. payload: { paymentId: "pay-003",  category: "Payment" }
 *   4. payload: { alertId:   "alert-004", category: "SystemAlert" }
 *
 * Standalone – creates its own queue, depends only on setup-prerequisite
 * (SETUP_ID must already exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Message Browser', () => {

    let queueName = ''
    let publishedMessageIds: string[] = []

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Publish a single message via the backend API and return its assigned ID. */
    async function publishMessage(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: object
    ): Promise<string> {
        const response = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload } }
        )
        if (!response.ok()) {
            throw new Error(`Publish message failed: ${response.status()} ${await response.text()}`)
        }
        const json = await response.json()
        return json.messageId as string
    }

    /**
     * Navigate to /messages, select the test setup and queue, click Refresh,
     * and wait until the table shows at least one row.
     */
    async function loadMessages(
        page: Parameters<Parameters<typeof test>[1]>[0]
    ): Promise<void> {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const setupSelector = page.getByTestId('setup-scope-selector')
        await selectAntOption(setupSelector, SETUP_ID)

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(queueSelector, queueName)

        await page.getByRole('button', { name: /refresh/i }).click()

        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').first()
        ).toBeVisible({ timeout: 15000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish 4 test messages', async ({ page }) => {
        test.setTimeout(120000)

        // Create the queue via management API
        queueName = `mb_test_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }

        // Give the queue a moment to be fully initialised before publishing
        await page.waitForTimeout(1000)

        // Publish 4 messages with distinct, searchable payloads
        const id1 = await publishMessage(page, { orderId: 'apple-001', category: 'FruitOrder' })
        const id2 = await publishMessage(page, { orderId: 'banana-002', category: 'FruitOrder' })
        const id3 = await publishMessage(page, { paymentId: 'pay-003', category: 'Payment' })
        const id4 = await publishMessage(page, { alertId: 'alert-004', category: 'SystemAlert' })

        publishedMessageIds = [id1, id2, id3, id4]
        expect(publishedMessageIds.every(id => id && id.length > 0)).toBeTruthy()

        console.log('Queue created:', queueName)
        console.log('Published message IDs:', publishedMessageIds)
    })

    // ── 1. Page structure ─────────────────────────────────────────────────────

    test('01 page renders title, table, controls', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await expect(page.locator('h1.ant-typography').filter({ hasText: /message browser/i })).toBeVisible()
        await expect(page.locator('.ant-table')).toBeVisible()
        await expect(page.locator('input[placeholder="Search messages..."]')).toBeVisible()
        await expect(page.getByRole('button', { name: /refresh/i })).toBeVisible()
        await expect(page.getByTestId('live-switch')).toBeVisible()
    })

    // ── 2. Message retrieval ──────────────────────────────────────────────────

    test('02 messages load from backend after selecting setup and queue', async ({ page }) => {
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(4, { timeout: 10000 })
    })

    test('03 published message payload content appears in the table', async ({ page }) => {
        await loadMessages(page)

        // The payloads we published contain known text — verify it is rendered in a table cell
        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: 'apple-001' }).first()
        ).toBeVisible({ timeout: 10000 })
        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: 'banana-002' }).first()
        ).toBeVisible({ timeout: 10000 })
    })

    // ── 3. Search / filter ────────────────────────────────────────────────────

    test('04 search filter by payload content narrows the table', async ({ page }) => {
        await loadMessages(page)

        const searchInput = page.locator('input[placeholder="Search messages..."]')

        // "apple" matches only message 1
        await searchInput.fill('apple-001')
        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(1, { timeout: 5000 })

        // "FruitOrder" matches messages 1 and 2
        await searchInput.clear()
        await searchInput.fill('FruitOrder')
        await expect(rows).toHaveCount(2, { timeout: 5000 })

        // Clear restores all 4
        await searchInput.clear()
        await expect(rows).toHaveCount(4, { timeout: 5000 })
    })

    test('05 search filter by message ID narrows to a single row', async ({ page }) => {
        await loadMessages(page)

        // The browse API returns integer IDs — extract the rendered ID from a specific row
        const targetRow = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: 'pay-003' })
        const messageIdCell = targetRow.locator('td').first().locator('code')
        const messageId = await messageIdCell.textContent({ timeout: 5000 })
        expect(messageId).toBeTruthy()

        const searchInput = page.locator('input[placeholder="Search messages..."]')
        await searchInput.fill(messageId!)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(1, { timeout: 5000 })
        await expect(page.locator(`[data-row-key="${messageId}"]`)).toBeVisible()
    })

    test('06 Clear button resets search filter and restores all rows', async ({ page }) => {
        await loadMessages(page)

        const searchInput = page.locator('input[placeholder="Search messages..."]')
        await searchInput.fill('apple-001')
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1, { timeout: 5000 })

        // Use data-testid to avoid matching Ant Design Input's own clear button
        await page.getByTestId('clear-filters-btn').click()

        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(4, { timeout: 5000 })
        await expect(searchInput).toHaveValue('')
    })

    // ── 4. Refresh ────────────────────────────────────────────────────────────

    test('07 Refresh button triggers re-fetch from management/messages', async ({ page }) => {
        await loadMessages(page)

        const capturedUrls: string[] = []
        await page.route('**/management/messages**', route => {
            capturedUrls.push(route.request().url())
            return route.continue()
        })

        await page.getByRole('button', { name: /refresh/i }).click()
        await page.waitForTimeout(500)

        expect(capturedUrls.length).toBeGreaterThanOrEqual(1)
        expect(capturedUrls[0]).toContain('/management/messages')
        expect(capturedUrls[0]).toContain(`queue=${queueName}`)
    })

    // ── 5. Filters drawer ─────────────────────────────────────────────────────

    test('08 Filters button opens the Advanced Filters drawer', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await page.getByRole('button', { name: /filters/i }).click()
        const drawer = page.locator('.ant-drawer-open')
        await expect(drawer).toBeVisible({ timeout: 5000 })
        await expect(drawer.locator('.ant-drawer-title')).toContainText('Advanced Filters')
    })

    test('09 Advanced Filters drawer contains Message Filters and Time Range sections', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await page.getByRole('button', { name: /filters/i }).click()
        const drawer = page.locator('.ant-drawer-open')
        await expect(drawer).toBeVisible({ timeout: 5000 })

        await expect(drawer.getByText('Message Filters')).toBeVisible()
        await expect(drawer.getByText('Time Range')).toBeVisible()
        await expect(drawer.getByText('Content Search')).toBeVisible()

        await page.keyboard.press('Escape')
    })

    // ── 6. Message detail modal ───────────────────────────────────────────────

    test('10 clicking the eye icon opens the Message Details modal', async ({ page }) => {
        await loadMessages(page)

        // Click the eye (View Details) button in the first table row
        await page.getByTestId('view-message-btn').first().click()

        // In Ant Design v5 the modal title is directly queryable once the modal opens
        const modalTitle = page.locator('.ant-modal-title').filter({ hasText: 'Message Details' })
        await expect(modalTitle).toBeVisible({ timeout: 5000 })

        // Payload section card title must be present (use card heading to avoid matching JSON content)
        await expect(page.locator('.ant-modal-content .ant-card-head-title').filter({ hasText: 'Payload' })).toBeVisible()
    })

    test('11 Message Details modal closes on Close button click', async ({ page }) => {
        await loadMessages(page)

        await page.getByTestId('view-message-btn').first().click()

        const modalTitle = page.locator('.ant-modal-title').filter({ hasText: 'Message Details' })
        await expect(modalTitle).toBeVisible({ timeout: 5000 })

        // Click the footer Close button specifically (not the X close icon)
        await page.locator('.ant-modal-footer').getByRole('button', { name: /close/i }).click()
        await expect(modalTitle).not.toBeVisible({ timeout: 5000 })
    })

    // ── 7. Live / SSE mode ────────────────────────────────────────────────────

    test('12 enabling Live switch shows Real-time Mode Active alert', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const liveSwitch = page.getByTestId('live-switch')
        await expect(liveSwitch).toBeVisible()

        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 5000 })
        await expect(page.getByTestId('live-alert')).toContainText('Real-time Mode Active')
    })

    test('13 disabling Live switch hides the Real-time Mode Active alert', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const liveSwitch = page.getByTestId('live-switch')
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 5000 })

        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).not.toBeVisible({ timeout: 5000 })
    })

    test('14 closing the Live alert disables real-time mode', async ({ page }) => {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        const liveSwitch = page.getByTestId('live-switch')
        await liveSwitch.click()
        await expect(page.getByTestId('live-alert')).toBeVisible({ timeout: 5000 })

        // Close the alert using its × button
        await page.getByTestId('live-alert').locator('.ant-alert-close-icon').click()
        await expect(page.getByTestId('live-alert')).not.toBeVisible({ timeout: 5000 })

        // The switch should now be unchecked
        await expect(liveSwitch).not.toHaveClass(/ant-switch-checked/)
    })

    // Removed 2026-06-18 (Phase 12.0): test '15 Live SSE' asserted the consuming /stream
    // EventSource, which was removed (data-loss hazard). The non-destructive live view is
    // covered by message-browser-nondestructive-live.spec.ts.
})
