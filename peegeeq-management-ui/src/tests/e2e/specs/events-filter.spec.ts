import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Events Page – Filter Functionality Tests
 *
 * Creates a dedicated event store and posts 5 events with known attributes,
 * then validates every client-side filter on the Events page:
 *
 *   - Event Type filter  (exact and partial match)
 *   - Correlation/Causation ID filter
 *   - Combining multiple filters
 *   - Clearing a filter restores the full result set
 *
 * Data set (5 events, posted via API):
 *   1. OrderCreated        correlationId=corr-filter-test   aggregateId=order-001
 *   2. OrderShipped        correlationId=corr-filter-test   aggregateId=order-001
 *   3. PaymentProcessed    correlationId=corr-filter-test   aggregateId=order-001
 *   4. OrderCancelled      (no correlationId)               aggregateId=order-002
 *   5. CustomerRegistered  (no correlationId)               aggregateId=cust-001
 *
 * Standalone – creates its own event store, no dependencies beyond the
 * default setup existing (setup-prerequisite runs before this project).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Events Page – Filter Functionality', () => {

    let eventStoreName = ''
    const CORRELATION_ID = 'corr-filter-test'

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Post a single event directly via the backend API. */
    async function postEvent(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: {
            eventType: string
            eventData: object
            correlationId?: string
            aggregateId?: string
        }
    ): Promise<void> {
        const body: Record<string, unknown> = {
            eventType: payload.eventType,
            eventData: payload.eventData,
        }
        if (payload.correlationId) body.correlationId = payload.correlationId
        if (payload.aggregateId)   body.aggregateId   = payload.aggregateId

        const response = await page.request.post(
            `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
            { data: body }
        )
        if (!response.ok()) {
            throw new Error(
                `POST event ${payload.eventType} failed: ${response.status()} ${await response.text()}`
            )
        }
    }

    /**
     * Navigate to the Events page, select setup + event store, click Load Events,
     * and wait until the table shows the expected number of rows.
     */
    async function loadEvents(
        page: Parameters<Parameters<typeof test>[1]>[0],
        expectedRows: number
    ): Promise<void> {
        await page.goto('/events')
        await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })

        await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)

        const eventStoreSelect = page.getByTestId('query-eventstore-select')
        await expect(eventStoreSelect).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(eventStoreSelect, eventStoreName)

        await page.getByRole('button', { name: 'Load Events' }).click()
        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 15000 })

        // Wait for table rows
        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').first()
        ).toBeVisible({ timeout: 10000 })

        const rows = page.locator('.ant-table-tbody tr.ant-table-row')
        await expect(rows).toHaveCount(expectedRows, { timeout: 10000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create event store and seed 5 events', async ({ page }) => {
        test.setTimeout(120000)

        // ── Create event store via UI ─────────────────────────────────────────
        eventStoreName = `filter_test_store_${Date.now()}`

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

        // ── Post 5 events via API ─────────────────────────────────────────────
        await postEvent(page, {
            eventType: 'OrderCreated',
            eventData: { orderId: 'order-001', amount: 99.99 },
            correlationId: CORRELATION_ID,
            aggregateId: 'order-001',
        })
        await postEvent(page, {
            eventType: 'OrderShipped',
            eventData: { orderId: 'order-001', carrier: 'UPS' },
            correlationId: CORRELATION_ID,
            aggregateId: 'order-001',
        })
        await postEvent(page, {
            eventType: 'PaymentProcessed',
            eventData: { orderId: 'order-001', amount: 99.99 },
            correlationId: CORRELATION_ID,
            aggregateId: 'order-001',
        })
        await postEvent(page, {
            eventType: 'OrderCancelled',
            eventData: { orderId: 'order-002', reason: 'out of stock' },
            aggregateId: 'order-002',
        })
        await postEvent(page, {
            eventType: 'CustomerRegistered',
            eventData: { customerId: 'cust-001', email: 'test@example.com' },
            aggregateId: 'cust-001',
        })
    })

    // ── 1. Baseline ───────────────────────────────────────────────────────────

    test('01 loading events shows all 5 rows and correct footer', async ({ page }) => {
        await loadEvents(page, 5)

        // Card title shows count
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (5)' })).toBeVisible()

        // Footer shows total events count, no "filtered" suffix
        const footer = page.locator('.ant-table-footer')
        await expect(footer).toContainText('Total Events: 5')
        await expect(footer).not.toContainText('Showing')
    })

    // ── 2. Event Type filter ──────────────────────────────────────────────────

    test('02 event type filter – exact match narrows to 1 row', async ({ page }) => {
        await loadEvents(page, 5)

        await page.getByPlaceholder('Event Type').fill('OrderCreated')

        // Card title and row count update instantly (client-side filter)
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (1)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
        await expect(page.locator('.ant-table-footer')).toContainText('Showing 1 filtered')

        // The single visible row must be OrderCreated
        await expect(
            page.locator('.ant-table-tbody tr.ant-table-row').first().locator('.ant-tag').first()
        ).toContainText('OrderCreated')
    })

    test('03 event type filter – partial match "Order" narrows to 3 rows', async ({ page }) => {
        await loadEvents(page, 5)

        await page.getByPlaceholder('Event Type').fill('Order')

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (3)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(3)
        await expect(page.locator('.ant-table-footer')).toContainText('Showing 3 filtered')
    })

    test('04 clearing event type filter restores all 5 rows', async ({ page }) => {
        await loadEvents(page, 5)

        const filterInput = page.getByPlaceholder('Event Type')
        await filterInput.fill('OrderCreated')
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (1)' })).toBeVisible()

        // Clear via the allowClear × button
        await filterInput.clear()

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (5)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(5)
        await expect(page.locator('.ant-table-footer')).not.toContainText('Showing')
    })

    // ── 3. Correlation ID filter ──────────────────────────────────────────────

    test('05 correlation ID filter shows only the 3 correlated events', async ({ page }) => {
        await loadEvents(page, 5)

        await page.getByPlaceholder('Correlation/Causation ID').fill(CORRELATION_ID)

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (3)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(3)
        await expect(page.locator('.ant-table-footer')).toContainText('Showing 3 filtered')
    })

    test('06 clearing correlation ID filter restores all 5 rows', async ({ page }) => {
        await loadEvents(page, 5)

        const filterInput = page.getByPlaceholder('Correlation/Causation ID')
        await filterInput.fill(CORRELATION_ID)
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (3)' })).toBeVisible()

        await filterInput.clear()

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (5)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(5)
    })

    // ── 4. Combined filters ───────────────────────────────────────────────────

    test('07 combining event type and correlation ID narrows to 1 row', async ({ page }) => {
        // Filter: eventType = "OrderCreated" AND correlationId = CORRELATION_ID → exactly 1 row
        await loadEvents(page, 5)

        await page.getByPlaceholder('Event Type').fill('OrderCreated')
        await page.getByPlaceholder('Correlation/Causation ID').fill(CORRELATION_ID)

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (1)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
        await expect(page.locator('.ant-table-footer')).toContainText('Showing 1 filtered')
    })

    test('08 combining event type and correlation ID with no match shows empty table', async ({ page }) => {
        // CustomerRegistered has no correlationId → 0 results
        await loadEvents(page, 5)

        await page.getByPlaceholder('Event Type').fill('CustomerRegistered')
        await page.getByPlaceholder('Correlation/Causation ID').fill(CORRELATION_ID)

        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Events (0)' })).toBeVisible()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(0)
        // Empty state placeholder shown instead of rows
        await expect(page.locator('.ant-table-placeholder')).toBeVisible()
    })
})
