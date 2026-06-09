import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Message Browser – Advanced Drawer Filter Application Tests
 *
 * Covers gap:
 *   1. Although the drawer's elements are verified, the tests do not verify that
 *      inputs (Message Type, Status selection, date range RangePicker, and text
 *      area content search) are applied to filter rows in the table.
 *
 * Strategy: publish 4 known messages via the API, load them, then apply each
 * advanced drawer filter and verify the row count changes.
 *
 * The filters are applied client-side in MessageBrowser.tsx:
 *   - statusFilter:      msg.status === statusFilter
 *   - messageTypeFilter: msg.messageType contains the typed text
 *   - dateRange:         message.createdAt within the picker range
 *   - searchText (in drawer): covers payload + id text search
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Message Browser – Advanced Drawer Filters', () => {

    let queueName = ''

    // ── helpers ───────────────────────────────────────────────────────────────

    async function publishMessage(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: object
    ): Promise<void> {
        const resp = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload } }
        )
        if (!resp.ok()) {
            throw new Error(`Publish failed: ${resp.status()} ${await resp.text()}`)
        }
    }

    async function loadMessages(
        page: Parameters<Parameters<typeof test>[1]>[0]
    ): Promise<void> {
        await page.goto('/messages')
        await page.waitForLoadState('networkidle')

        await selectAntOption(page.getByTestId('setup-scope-selector'), SETUP_ID)

        const queueSelector = page.getByTestId('queue-scope-selector')
        await expect(queueSelector).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(queueSelector, queueName)

        await page.getByRole('button', { name: /refresh/i }).click()

        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').first()
        ).toBeVisible({ timeout: 15000 })
    }

    async function openAdvancedDrawer(
        page: Parameters<Parameters<typeof test>[1]>[0]
    ): Promise<void> {
        await page.getByRole('button', { name: /filters/i }).click()
        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create queue and publish 4 test messages', async ({ page }) => {
        test.setTimeout(120000)

        queueName = `mb_adv_${Date.now()}`
        const createResp = await page.request.post(
            '/api/v1/management/queues',
            { data: { setupId: SETUP_ID, name: queueName, type: 'native' } }
        )
        if (!createResp.ok()) {
            throw new Error(`Create queue failed: ${createResp.status()} ${await createResp.text()}`)
        }

        await page.waitForTimeout(1000)

        await publishMessage(page, { orderId: 'adv-001', category: 'FruitOrder' })
        await publishMessage(page, { orderId: 'adv-002', category: 'FruitOrder' })
        await publishMessage(page, { orderId: 'adv-003', category: 'Payment' })
        await publishMessage(page, { orderId: 'adv-004', category: 'SystemAlert' })

        console.log('Created queue and published 4 messages:', queueName)
    })

    // ── 1. Status filter ──────────────────────────────────────────────────────

    test('01 status filter "completed" applied from drawer reduces visible rows to 0', async ({ page }) => {
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(4, { timeout: 10000 })

        await openAdvancedDrawer(page)

        // Set the Status filter inside the drawer to "completed"
        // (published messages are "pending" — none should match "completed")
        const drawerStatusSelect = page.getByTestId('drawer-status-filter-select')
        await selectAntOption(drawerStatusSelect, 'Completed')

        // Row count updates without closing the drawer (reactive state)
        await expect(rows).toHaveCount(0, { timeout: 5000 })

        // Close drawer and verify count is still 0
        await page.keyboard.press('Escape')
        await expect(page.locator('.ant-drawer-open')).not.toBeVisible({ timeout: 3000 })
        await expect(rows).toHaveCount(0)
    })

    test('02 clearing the status filter restores all rows', async ({ page }) => {
        await loadMessages(page)

        await openAdvancedDrawer(page)

        // Apply filter to reduce rows
        const drawerStatusSelect = page.getByTestId('drawer-status-filter-select')
        await selectAntOption(drawerStatusSelect, 'Completed')

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(0, { timeout: 5000 })

        // Clear the status filter via the allowClear × on the select
        await page.getByTestId('drawer-status-filter-select').hover()
        const clearBtn = page.getByTestId('drawer-status-filter-select').locator('.ant-select-clear')
        await expect(clearBtn).toBeVisible()
        await clearBtn.click()

        // Rows return
        await expect(rows).toHaveCount(4, { timeout: 5000 })
    })

    // ── 2. Content search (drawer TextArea) ───────────────────────────────────

    test('03 content search in drawer filters by payload text', async ({ page }) => {
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(4, { timeout: 10000 })

        await openAdvancedDrawer(page)

        // Type into the Content Search TextArea
        const contentSearchArea = page.locator('.ant-drawer-open').getByPlaceholder(/search in message payload/i)
        await contentSearchArea.fill('adv-001')

        // Client-side filter runs reactively; only the one matching row should remain
        await expect(rows).toHaveCount(1, { timeout: 5000 })

        // Clear the search
        await contentSearchArea.clear()
        await expect(rows).toHaveCount(4, { timeout: 5000 })
    })

    // ── 3. Date range filter (RangePicker in drawer) ──────────────────────────

    test('04 date range filter set entirely in the past excludes all rows', async ({ page }) => {
        await loadMessages(page)

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(4, { timeout: 10000 })

        await openAdvancedDrawer(page)

        // Set a date range entirely in the past (2025) — all messages were created in 2026
        const drawer = page.locator('.ant-drawer-open')
        const rangeStart = drawer.locator('.ant-picker-range input').first()
        await rangeStart.click()
        await rangeStart.fill('2025-01-01 00:00:00')
        await page.keyboard.press('Tab')
        const rangeEnd = drawer.locator('.ant-picker-range input').last()
        await rangeEnd.fill('2025-12-31 23:59:59')
        await page.keyboard.press('Enter')

        await expect(rows).toHaveCount(0, { timeout: 5000 })

        // Clear the range picker
        const pickerClear = drawer.locator('.ant-picker-range .ant-picker-clear')
        if (await pickerClear.isVisible()) {
            await pickerClear.click()
        }

        await expect(rows).toHaveCount(4, { timeout: 5000 })
    })
})
