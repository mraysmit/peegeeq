import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'

/**
 * Queue Details - Operations Tests
 *
 * Covers the untested action flows on the Queue Details page against the
 * real backend:
 *
 *   - Get Messages modal: opens, submits count, shows messages table
 *   - View Payload:       clicking the View button in a message row opens
 *                         a Modal.info with the raw JSON payload
 *   - Pause Queue:        confirms dialog, calls POST .../pause, shows toast
 *   - Resume Queue:       confirms dialog, calls POST .../resume, shows toast
 *   - Purge Messages:     confirms dialog, calls POST .../purge, shows toast
 *   - Delete Queue:       confirms dialog, calls DELETE, navigates to /queues
 *
 * Standalone — creates its own queue and publishes test messages via the
 * backend API. Depends only on setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Details - Operations', () => {

    let queueName = ''

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Publish a single message via the backend API. */
    async function publishMessage(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: object
    ): Promise<void> {
        const response = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload } }
        )
        if (!response.ok()) {
            throw new Error(`Publish failed: ${response.status()} ${await response.text()}`)
        }
    }

    /** Open the Actions dropdown on the Queue Details page. */
    async function openActionsMenu(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await page.getByTestId('queue-actions-btn').click()
        const dropdown = page.locator('.ant-dropdown')
            .filter({ hasNot: page.locator('.ant-dropdown-hidden') })
            .last()
        await expect(dropdown).toBeVisible({ timeout: 5000 })
        return dropdown
    }

    /** Navigate to the queue details page and wait for tabs to load. */
    async function gotoDetails(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await page.goto(`/queues/${SETUP_ID}/${queueName}`)
        await expect(page.getByRole('tablist')).toBeVisible({ timeout: 10000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish 3 test messages', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `qdops_test_${Date.now()}`

        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }

        // Brief pause for queue initialisation before publishing
        await page.waitForTimeout(1000)

        await publishMessage(page, { orderId: 'OP-001', category: 'OrderOps' })
        await publishMessage(page, { orderId: 'OP-002', category: 'OrderOps' })
        await publishMessage(page, { orderId: 'OP-003', category: 'OrderOps' })

        console.log('Queue created:', queueName)
    })

    // ── 1. Get Messages modal ─────────────────────────────────────────────────

    test('01 Get Messages button opens the modal with count input', async ({ page }) => {
        await gotoDetails(page)

        await page.getByRole('tab', { name: /messages/i }).click()
        await expect(page.getByRole('tab', { name: /messages/i })).toHaveAttribute('aria-selected', 'true')

        await page.getByRole('button', { name: /get messages/i }).click()

        const modal = page.locator('.ant-modal')
        await expect(modal).toBeVisible()
        await expect(modal.locator('.ant-modal-title')).toContainText('Get Messages')
        await expect(modal.locator('input[type="number"], .ant-input-number-input')).toBeVisible()
        await expect(modal.getByText('Non-Destructive Read')).toBeVisible()
    })

    test('02 Get Messages fetches messages and displays them in the table', async ({ page }) => {
        await gotoDetails(page)

        await page.getByRole('tab', { name: /messages/i }).click()
        await expect(page.getByRole('tab', { name: /messages/i })).toHaveAttribute('aria-selected', 'true')

        await page.getByRole('button', { name: /get messages/i }).click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        // Set limit to 10 (default) and confirm
        await page.locator('.ant-modal').getByRole('button', { name: /get messages/i }).click()

        // Modal closes and success toast appears
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 10000 })
        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })

        // Messages table shows rows
        const rows = page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })
        const count = await rows.count()
        expect(count).toBeGreaterThanOrEqual(1)

        console.log(`Get Messages returned ${count} row(s)`)
    })

    test('03 View button in message row opens payload modal', async ({ page }) => {
        await gotoDetails(page)

        await page.getByRole('tab', { name: /messages/i }).click()
        await expect(page.getByRole('tab', { name: /messages/i })).toHaveAttribute('aria-selected', 'true')

        // Fetch messages first
        await page.getByRole('button', { name: /get messages/i }).click()
        await expect(page.locator('.ant-modal')).toBeVisible()
        await page.locator('.ant-modal').getByRole('button', { name: /get messages/i }).click()
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 10000 })

        // Click View button in first row
        const rows = page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row')
        await expect(rows.first()).toBeVisible({ timeout: 10000 })
        await rows.first().getByRole('button', { name: /view/i }).click()

        // Modal.info() in Ant Design v5 renders with .ant-modal-confirm-info,
        // not the v4 class .ant-modal-info.  Filter by title text to be robust.
        const payloadModal = page.locator('.ant-modal-confirm').filter({ hasText: 'Message Payload' })
        await expect(payloadModal).toBeVisible({ timeout: 5000 })
        await expect(payloadModal).toContainText('Message Payload')
        await expect(payloadModal.locator('pre')).toBeVisible()

        // Close the modal
        await payloadModal.getByRole('button', { name: /ok/i }).click()
        await expect(payloadModal).not.toBeVisible({ timeout: 5000 })
    })

    // ── 2. Pause / Resume ─────────────────────────────────────────────────────

    test('04 Pause Queue shows confirm dialog and calls POST .../pause', async ({ page }) => {
        await gotoDetails(page)

        // Intercept the pause API call
        const pauseRequests: string[] = []
        page.on('request', req => {
            if (req.url().includes('/pause') && req.method() === 'POST') {
                pauseRequests.push(req.url())
            }
        })

        const dropdown = await openActionsMenu(page)
        await dropdown.getByText('Pause Queue').click()

        // Confirm dialog
        const confirmDialog = page.locator('.ant-modal-confirm')
        await expect(confirmDialog).toBeVisible({ timeout: 5000 })
        await expect(confirmDialog.locator('.ant-modal-confirm-title')).toContainText('Pause Queue')
        await confirmDialog.getByRole('button', { name: 'Pause' }).click()

        // Success toast and API called
        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })
        expect(pauseRequests.length).toBeGreaterThanOrEqual(1)
        expect(pauseRequests[0]).toContain(`/queues/${SETUP_ID}/${queueName}/pause`)

        console.log('Pause Queue: API called and toast shown')
    })

    test('05 Resume Queue shows confirm dialog and calls POST .../resume', async ({ page }) => {
        await gotoDetails(page)

        // Queue is paused from previous test — dropdown should show "Resume Queue"
        const resumeRequests: string[] = []
        page.on('request', req => {
            if (req.url().includes('/resume') && req.method() === 'POST') {
                resumeRequests.push(req.url())
            }
        })

        const dropdown = await openActionsMenu(page)
        await dropdown.getByText('Resume Queue').click()

        const confirmDialog = page.locator('.ant-modal-confirm')
        await expect(confirmDialog).toBeVisible({ timeout: 5000 })
        await expect(confirmDialog.locator('.ant-modal-confirm-title')).toContainText('Resume Queue')
        await confirmDialog.getByRole('button', { name: 'Resume' }).click()

        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })
        expect(resumeRequests.length).toBeGreaterThanOrEqual(1)
        expect(resumeRequests[0]).toContain(`/queues/${SETUP_ID}/${queueName}/resume`)

        console.log('Resume Queue: API called and toast shown')
    })

    // ── 3. Purge ──────────────────────────────────────────────────────────────

    test('06 Purge Messages shows confirm dialog and calls POST .../purge', async ({ page }) => {
        await gotoDetails(page)

        const purgeRequests: string[] = []
        page.on('request', req => {
            if (req.url().includes('/purge') && req.method() === 'POST') {
                purgeRequests.push(req.url())
            }
        })

        const dropdown = await openActionsMenu(page)
        await dropdown.getByText('Purge Messages').click()

        const confirmDialog = page.locator('.ant-modal-confirm')
        await expect(confirmDialog).toBeVisible({ timeout: 5000 })
        await expect(confirmDialog.locator('.ant-modal-confirm-title')).toContainText('Purge Queue')
        await confirmDialog.locator('.ant-btn-dangerous').click()

        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })
        expect(purgeRequests.length).toBeGreaterThanOrEqual(1)
        expect(purgeRequests[0]).toContain(`/queues/${SETUP_ID}/${queueName}/purge`)

        const toastText = await page.locator('.ant-message-success').first().textContent()
        expect(toastText).toMatch(/purged successfully/i)

        console.log('Purge Messages toast:', toastText)
    })

    // ── 4. Delete via UI ──────────────────────────────────────────────────────

    test('07 Delete Queue shows confirm dialog, calls DELETE, and navigates to /queues', async ({ page }) => {
        await gotoDetails(page)

        const deleteRequests: string[] = []
        page.on('request', req => {
            if (req.url().includes(`/queues/${SETUP_ID}/${queueName}`) && req.method() === 'DELETE') {
                deleteRequests.push(req.url())
            }
        })

        const dropdown = await openActionsMenu(page)
        await dropdown.getByText('Delete Queue').click()

        const confirmDialog = page.locator('.ant-modal-confirm')
        await expect(confirmDialog).toBeVisible({ timeout: 5000 })
        await expect(confirmDialog.locator('.ant-modal-confirm-title')).toContainText('Delete Queue')
        await confirmDialog.locator('.ant-btn-dangerous').click()

        // After deletion the app navigates back to /queues
        await expect(page).toHaveURL(/\/queues$/, { timeout: 15000 })

        expect(deleteRequests.length).toBeGreaterThanOrEqual(1)

        // Queue is no longer in the list
        await expect(page.locator('.ant-table')).toBeVisible()
        await expect(page.locator('.ant-table-tbody').getByText(queueName)).not.toBeVisible({ timeout: 5000 })

        console.log('Delete Queue: navigated to /queues, queue absent from table')
    })
})
