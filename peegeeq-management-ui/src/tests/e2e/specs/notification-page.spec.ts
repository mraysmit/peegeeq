import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Notifications Page (`/notifications`) — Phase 7a
 *
 * The notification feed is held in-memory in `managementStore` (reset on full page
 * reload, no backend source until Phase 8). Each test therefore generates a fresh
 * notification by creating a queue through the UI — the QueuesEnhanced create handler
 * calls `addNotification(...)` on success — then navigates to /notifications via the
 * sidebar (client-side nav, so the in-memory store survives) and asserts the history.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist) for the create-queue flow.
 */
test.describe.configure({ mode: 'serial' })

/**
 * Create a queue through the Queues page UI, which fires a "queue created"
 * notification into the store, then return to the caller still in the same page
 * session (no reload) so the notification persists for the /notifications view.
 */
async function createQueueViaUI(
    page: Parameters<Parameters<typeof test>[1]>[0],
    queueName: string
): Promise<void> {
    await page.goto('/queues')
    await expect(page.getByTestId('create-queue-btn')).toBeVisible({ timeout: 15000 })
    await page.getByTestId('create-queue-btn').click()

    const modal = page.locator('.ant-modal')
    await expect(modal).toBeVisible()

    await modal.getByTestId('queue-name-input').fill(queueName)
    await modal.getByTestId('refresh-setups-btn').click()
    await expect(modal.getByTestId('queue-setup-select')).toBeEnabled()
    await selectAntOption(modal.getByTestId('queue-setup-select'), SETUP_ID)

    await modal.locator('.ant-btn-primary').click()
    await expect(modal).not.toBeVisible({ timeout: 15000 })
}

test.describe('Notifications Page', () => {

    test('notifications page shows events from resource actions', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_show_${Date.now()}`
        await createQueueViaUI(page, queueName)

        // Client-side nav keeps the in-memory notification feed intact
        await page.getByTestId('nav-notifications').click()
        await expect(page).toHaveURL(/\/notifications$/)

        // Table renders, not the empty state
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        // A row for the queue-creation notification, marked unread ("New")
        const row = page.locator('.ant-table-row').filter({ hasText: queueName })
        await expect(row).toBeVisible({ timeout: 10000 })
        await expect(row.locator('.ant-tag').filter({ hasText: 'New' })).toBeVisible()
    })

    test('mark all read clears unread status and resets the bell badge', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_read_${Date.now()}`
        await createQueueViaUI(page, queueName)

        await page.getByTestId('nav-notifications').click()
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        await page.getByTestId('mark-all-read-btn').click()

        // No row should still show the "New" tag
        await expect(page.locator('.ant-table-row .ant-tag').filter({ hasText: 'New' })).toHaveCount(0)

        // Header bell badge resets to 0 (Ant renders no count element when unreadCount === 0)
        await expect(page.getByTestId('app-header').locator('.ant-badge-count')).toHaveCount(0)
    })

    test('clear all empties the notifications list', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_clear_${Date.now()}`
        await createQueueViaUI(page, queueName)

        await page.getByTestId('nav-notifications').click()
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        await page.getByTestId('clear-all-btn').click()

        // Confirm the Modal.confirm dialog
        const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
        await expect(confirmBtn).toBeVisible({ timeout: 3000 })
        await confirmBtn.click()

        await expect(page.getByTestId('notifications-empty')).toBeVisible({ timeout: 10000 })
        await expect(page.getByTestId('app-header').locator('.ant-badge-count')).toHaveCount(0)
    })
})
