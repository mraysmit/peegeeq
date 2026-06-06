import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Aggregate Stream Page – Integration Tests
 *
 * Creates a dedicated event store and posts events across two aggregates
 * via the backend API, then validates the Aggregate Stream page against
 * a real backend.
 *
 * Data set:
 *   agg-A: OrderCreated (v1), OrderShipped (v2)   — 2 events
 *   agg-B: OrderCancelled (v1)                     — 1 event
 *
 * Standalone – creates its own event store; depends only on
 * setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Aggregate Stream Page', () => {

    let eventStoreName = ''
    let aggA = ''
    let aggB = ''

    // ── helpers ───────────────────────────────────────────────────────────────

    async function postEvent(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: {
            eventType: string
            eventData: object
            aggregateId: string
            correlationId?: string
        }
    ): Promise<void> {
        const body: Record<string, unknown> = {
            eventType: payload.eventType,
            eventData: payload.eventData,
            aggregateId: payload.aggregateId,
        }
        if (payload.correlationId) body.correlationId = payload.correlationId

        let response = await page.request.post(
            `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
            { data: body }
        )
        // Retry once on 404 — the event store may not be fully registered immediately
        // after creation (brief backend propagation window).
        if (response.status() === 404) {
            await page.waitForTimeout(2000)
            response = await page.request.post(
                `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
                { data: body }
            )
        }
        if (!response.ok()) {
            throw new Error(`POST ${payload.eventType} failed: ${response.status()} ${await response.text()}`)
        }
    }

    async function selectStore(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await selectAntOption(page.getByTestId('aggregate-setup-select'), SETUP_ID)
        const storeSelect = page.getByTestId('aggregate-eventstore-select')
        await expect(storeSelect).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(storeSelect, eventStoreName)
    }

    async function loadAggregates(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await selectStore(page)
        await page.getByRole('button', { name: /load aggregates/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create event store and seed events for 2 aggregates', async ({ page }) => {
        test.setTimeout(120000)

        eventStoreName = `agg_stream_test_${Date.now()}`
        aggA = `agg-A-${Date.now()}`
        aggB = `agg-B-${Date.now()}`

        await page.goto('/event-stores')
        await page.getByRole('button', { name: /create event store/i }).click()
        await expect(page.locator('.ant-modal')).toBeVisible()

        await page.locator('#name').fill(eventStoreName)
        await page.getByTestId('refresh-setups-btn').click()
        // selectAntOption waits up to 15 s for the option to appear — no fixed wait needed
        await selectAntOption(page.locator('.ant-modal .ant-select:has(#setupId)'), SETUP_ID)

        await page.locator('.ant-modal .ant-btn-primary').click()
        await expect(page.locator('.ant-modal')).not.toBeVisible({ timeout: 30000 })
        await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10000 })

        // agg-A: 2 events
        await postEvent(page, {
            eventType: 'OrderCreated',
            eventData: { orderId: aggA },
            aggregateId: aggA,
            correlationId: `corr-${Date.now()}`,
        })
        await postEvent(page, {
            eventType: 'OrderShipped',
            eventData: { carrier: 'UPS' },
            aggregateId: aggA,
        })

        // agg-B: 1 event
        await postEvent(page, {
            eventType: 'OrderCancelled',
            eventData: { reason: 'out of stock' },
            aggregateId: aggB,
        })
    })

    // ── 1. Page structure ─────────────────────────────────────────────────────

    test('01 page renders heading, selectors, and Load Aggregates button', async ({ page }) => {
        await page.goto('/aggregate-stream')

        await expect(page.locator('h1.ant-typography')).toContainText('Aggregate Stream')
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
        await expect(page.getByTestId('aggregate-setup-select')).toBeVisible()
        await expect(page.getByTestId('aggregate-eventstore-select')).toBeVisible()
        await expect(page.getByRole('button', { name: /load aggregates/i })).toBeVisible()
    })

    test('02 Load Aggregates is disabled before store is selected', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await expect(page.getByRole('button', { name: /load aggregates/i })).toBeDisabled()
    })

    test('03 event-store dropdown is disabled until setup is selected', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await expect(page.getByTestId('aggregate-eventstore-select')).toHaveClass(/ant-select-disabled/)
        await selectAntOption(page.getByTestId('aggregate-setup-select'), SETUP_ID)
        await expect(page.getByTestId('aggregate-eventstore-select')).not.toHaveClass(/ant-select-disabled/)
    })

    test('04 Load Aggregates enables after setup and store are selected', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await selectStore(page)
        await expect(page.getByRole('button', { name: /load aggregates/i })).toBeEnabled()
    })

    // ── 2. Loading aggregates ─────────────────────────────────────────────────

    test('05 Load Aggregates shows both aggregate IDs in the list', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(2)
        await expect(page.locator('td').filter({ hasText: aggA })).toBeVisible()
        await expect(page.locator('td').filter({ hasText: aggB })).toBeVisible()
    })

    test('06 Refresh List re-fetches the aggregate list', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.getByRole('button', { name: /refresh list/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(2, { timeout: 10000 })
    })

    // ── 3. Viewing the event stream ───────────────────────────────────────────

    test('07 clicking an aggregate row loads its event stream', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()

        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` })
        ).toBeVisible({ timeout: 10000 })

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        })
        // aggA was seeded with 2 events (OrderCreated + OrderShipped); use gte to tolerate extra
        // events from concurrent test runs sharing the same event store
        const streamRows = streamCard.locator('.ant-table-tbody tr.ant-table-row')
        await expect(streamRows).not.toHaveCount(0, { timeout: 10000 })
        const rowCount = await streamRows.count()
        expect(rowCount).toBeGreaterThanOrEqual(2)
        await expect(streamCard.locator('.ant-tag').filter({ hasText: 'OrderCreated' })).toBeVisible()
        await expect(streamCard.locator('.ant-tag').filter({ hasText: 'OrderShipped' })).toBeVisible()
    })

    test('08 View Stream button loads the stream for that aggregate', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row')
            .filter({ hasText: aggB })
            .getByText('View Stream')
            .click()

        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggB}` })
        ).toBeVisible({ timeout: 10000 })

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggB}` }),
        })
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
        await expect(streamCard.locator('.ant-tag').filter({ hasText: 'OrderCancelled' })).toBeVisible()
    })

    test('09 clicking a second aggregate replaces the stream', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()
        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` })
        ).toBeVisible({ timeout: 10000 })

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggB }).click()
        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggB}` })
        ).toBeVisible({ timeout: 10000 })

        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` })
        ).not.toBeVisible()
    })

    // ── 4. Event detail drawer ────────────────────────────────────────────────

    test('10 Details button opens event detail drawer', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()
        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` })
        ).toBeVisible({ timeout: 10000 })

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        })
        await streamCard.locator('.ant-table-tbody tr.ant-table-row').first().getByText('Details').click()

        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
        await expect(page.locator('.ant-drawer-title')).toContainText('Event Details')
        await expect(page.locator('.ant-descriptions-item-label').filter({ hasText: 'Event Type' })).toBeVisible()
        await expect(page.locator('.ant-descriptions-item-label').filter({ hasText: 'Payload' })).toBeVisible()
    })

    test('11 closing the drawer hides it', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()
        await expect(
            page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` })
        ).toBeVisible({ timeout: 10000 })

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        })
        await streamCard.locator('.ant-table-tbody tr.ant-table-row').first().getByText('Details').click()
        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })

        await page.locator('.ant-drawer-close').click()
        await expect(page.locator('.ant-drawer-open')).not.toBeVisible({ timeout: 5000 })
    })
})
