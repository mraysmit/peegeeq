import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Notifications — Phase 7a
 *
 * The notification feed lives in-memory in `managementStore` (reset on full page reload,
 * no backend source until Phase 8). It is populated by the frontend's own success handlers
 * calling `addNotification(...)` after resource CRUD. These tests emit notifications by
 * creating queues through the UI (QueuesEnhanced fires "queue created"), then navigate
 * client-side — so the in-memory feed survives — to assert receipt (bell badge + drawer +
 * page), read state, ordering, and clearing.
 *
 * NOTE: a full `page.goto(...)` clears the feed, so multiple notifications must be emitted
 * within a single page load (one `gotoQueues`, several `createQueueOnQueuesPage`).
 *
 * Depends on setup-prerequisite (SETUP_ID must exist) for the create-queue flow.
 */
test.describe.configure({ mode: 'serial' })

type TestPage = Parameters<Parameters<typeof test>[1]>[0]

async function gotoQueues(page: TestPage): Promise<void> {
    await page.goto('/queues')
    await expect(page.getByTestId('create-queue-btn')).toBeVisible({ timeout: 15000 })
}

/**
 * Create a queue from the (already-loaded) Queues page, firing one "queue created"
 * notification. Does NOT navigate, so several can be created in one page session and the
 * in-memory feed accumulates.
 */
async function createQueueOnQueuesPage(page: TestPage, queueName: string): Promise<void> {
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

/** The header bell's count badge, scoped to the notifications button so it can't match another badge. */
const headerBadge = (page: TestPage) =>
    page.locator('.ant-badge:has([data-testid="notifications-btn"]) .ant-badge-count')

test.describe('Notifications — page (history, read, clear)', () => {

    test('notifications page shows events from resource actions', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_show_${Date.now()}`
        await gotoQueues(page)
        await createQueueOnQueuesPage(page, queueName)

        await page.getByTestId('nav-notifications').click()
        await expect(page).toHaveURL(/\/notifications$/)
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        const row = page.locator('.ant-table-row').filter({ hasText: queueName })
        await expect(row).toBeVisible({ timeout: 10000 })
        await expect(row).toContainText('queue created')
        await expect(row.locator('.ant-tag').filter({ hasText: 'New' })).toBeVisible()
    })

    test('mark all read clears unread status and resets the bell badge', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_read_${Date.now()}`
        await gotoQueues(page)
        await createQueueOnQueuesPage(page, queueName)

        await page.getByTestId('nav-notifications').click()
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        await page.getByTestId('mark-all-read-btn').click()
        await expect(page.locator('.ant-table-row .ant-tag').filter({ hasText: 'New' })).toHaveCount(0)
        await expect(headerBadge(page)).toHaveCount(0)
    })

    test('clear all empties the notifications list', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_clear_${Date.now()}`
        await gotoQueues(page)
        await createQueueOnQueuesPage(page, queueName)

        await page.getByTestId('nav-notifications').click()
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })

        await page.getByTestId('clear-all-btn').click()
        const confirmBtn = page.locator('.ant-modal-confirm .ant-btn-dangerous')
        await expect(confirmBtn).toBeVisible({ timeout: 3000 })
        await confirmBtn.click()

        await expect(page.getByTestId('notifications-empty')).toBeVisible({ timeout: 10000 })
        await expect(headerBadge(page)).toHaveCount(0)
    })
})

test.describe('Notifications — feed (send / receive / read)', () => {

    test('a resource action sends a notification received by the bell badge and drawer', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_recv_${Date.now()}`
        await gotoQueues(page)
        await createQueueOnQueuesPage(page, queueName)

        // Received: the header bell badge shows the unread count
        await expect(headerBadge(page)).toContainText('1', { timeout: 10000 })

        // Received: the bell drawer lists it (opening the drawer also marks all read)
        await page.getByTestId('notifications-btn').click()
        const drawer = page.locator('.ant-drawer')
        await expect(drawer).toBeVisible()
        await expect(drawer.getByText(queueName)).toBeVisible()
        await expect(drawer.getByText(/queue created/i)).toBeVisible()
    })

    test('multiple actions accumulate and show newest-first on the page', async ({ page }) => {
        test.setTimeout(60000)
        const ts = Date.now()
        const firstQueue = `notif_multi_a_${ts}`
        const secondQueue = `notif_multi_b_${ts}`

        await gotoQueues(page)
        await createQueueOnQueuesPage(page, firstQueue)
        await createQueueOnQueuesPage(page, secondQueue)

        // Two unread received
        await expect(headerBadge(page)).toContainText('2', { timeout: 10000 })

        await page.getByTestId('nav-notifications').click()
        await expect(page.getByTestId('notifications-table')).toBeVisible({ timeout: 10000 })
        await expect(page.locator('.ant-table-row').filter({ hasText: firstQueue })).toBeVisible()
        await expect(page.locator('.ant-table-row').filter({ hasText: secondQueue })).toBeVisible()

        // Newest first: the most recently created queue is the first row
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toContainText(secondQueue)
    })

    test('opening the bell drawer marks notifications read (reflected on the page)', async ({ page }) => {
        test.setTimeout(60000)
        const queueName = `notif_drawerread_${Date.now()}`
        await gotoQueues(page)
        await createQueueOnQueuesPage(page, queueName)
        await expect(headerBadge(page)).toContainText('1', { timeout: 10000 })

        // Open the bell drawer → marks all read → badge clears
        await page.getByTestId('notifications-btn').click()
        await expect(page.locator('.ant-drawer')).toBeVisible()
        await expect(headerBadge(page)).toHaveCount(0)
        await page.keyboard.press('Escape')

        // The page now shows the entry as Read, not New
        await page.getByTestId('nav-notifications').click()
        const row = page.locator('.ant-table-row').filter({ hasText: queueName })
        await expect(row).toBeVisible({ timeout: 10000 })
        await expect(row.locator('.ant-tag').filter({ hasText: 'Read' })).toBeVisible()
        await expect(row.locator('.ant-tag').filter({ hasText: 'New' })).toHaveCount(0)
    })
})
