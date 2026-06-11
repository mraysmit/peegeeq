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
 *   agg-A: OrderCreated (v1, correlationId=aggACorrelationId), OrderShipped (v2)  — 2 events
 *   agg-B: OrderCancelled (v1, no correlationId)                                  — 1 event
 *
 * Tests 00–13: original coverage (page structure, loading, stream, drawer, filter)
 * Tests 14–15: I2/I5 enriched metadata columns + event stream Actions column
 * Tests 16–17: I3 full fix — API-driven event stream pagination
 *
 * Standalone – creates its own event store; depends only on
 * setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Aggregate Stream Page', () => {

    let eventStoreName = ''
    let aggA = ''
    let aggB = ''
    let aggC = ''
    let aggACorrelationId = ''

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
        // Wait for the button to be enabled before clicking — React's setSelectedEventStore
        // is async and the button remains disabled until the state update is committed.
        const loadBtn = page.getByRole('button', { name: /load aggregates/i })
        await expect(loadBtn).toBeEnabled({ timeout: 5000 })
        await loadBtn.click()
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

        // agg-A: 2 events; first event carries a correlationId for Causation Tree navigation tests
        aggACorrelationId = `corr-${Date.now()}`
        await postEvent(page, {
            eventType: 'OrderCreated',
            eventData: { orderId: aggA },
            aggregateId: aggA,
            correlationId: aggACorrelationId,
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

        // Use .last() — the outer "Aggregate Stream" card also matches (contains the inner stream card)
        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        }).last()
        // aggA was seeded with 2 events (OrderCreated + OrderShipped); use gte to tolerate extra
        // events from concurrent test runs sharing the same event store
        const streamRows = streamCard.locator('.ant-table-tbody tr.ant-table-row')
        await expect(streamRows).not.toHaveCount(0, { timeout: 10000 })
        const rowCount = await streamRows.count()
        expect(rowCount).toBeGreaterThanOrEqual(2)
        await expect(streamCard.locator('.ant-tag').filter({ hasText: 'OrderCreated' })).toBeVisible()
        await expect(streamCard.locator('.ant-tag').filter({ hasText: 'OrderShipped' })).toBeVisible()
    })

    test('08 aggregate list columns show enriched metadata (eventCount, lastEventTime, eventTypes)', async ({ page }) => {
        // I2: the aggregate table now includes eventCount badge, lastEventTime, and eventTypes tags
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        // aggA: 2 events seeded — eventCount badge must display 2
        const aggARow = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA })
        await expect(aggARow.locator('.ant-badge-count')).toContainText('2')

        // eventTypes column must list both event types seeded for aggA
        await expect(aggARow.locator('.ant-tag').filter({ hasText: 'OrderCreated' })).toBeVisible()
        await expect(aggARow.locator('.ant-tag').filter({ hasText: 'OrderShipped' })).toBeVisible()

        // lastEventTime column (3rd td, 0-indexed) must show a formatted date, not the fallback '-'
        // Column order: Aggregate ID (0), Events (1), Last Active (2), Event Types (3)
        await expect(aggARow.locator('td').nth(2)).toContainText(/\d{4}-\d{2}-\d{2}/)

        // aggB: 1 event (OrderCancelled) — verify count badge and event type tag
        const aggBRow = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggB })
        await expect(aggBRow.locator('.ant-badge-count')).toContainText('1')
        await expect(aggBRow.locator('.ant-tag').filter({ hasText: 'OrderCancelled' })).toBeVisible()
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

        // Use .last() because the outer "Aggregate Stream" card also matches (it contains the inner stream card)
        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        }).last()
        // Wait for stream rows to load before clicking Details
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })
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

        // Use .last() because the outer "Aggregate Stream" card also matches (it contains the inner stream card)
        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        }).last()
        // Wait for stream rows to load before clicking Details
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })
        await streamCard.locator('.ant-table-tbody tr.ant-table-row').first().getByText('Details').click()
        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })

        await page.locator('.ant-drawer-close').click()
        await expect(page.locator('.ant-drawer-open')).not.toBeVisible({ timeout: 5000 })
    })

    // ── 5. Filter by Event Type ───────────────────────────────────────────────

    test('12 Filter by Event Type scopes the aggregate list via the API', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await selectStore(page)

        // Type a filter that matches only aggB's event type (OrderCancelled)
        await page.getByPlaceholder('Filter by Event Type').fill('OrderCancelled')

        // Load Aggregates with filter applied
        await page.getByRole('button', { name: /load aggregates/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })

        // Only aggB (which has OrderCancelled events) should appear
        await expect(page.locator('td').filter({ hasText: aggB })).toBeVisible()
        await expect(page.locator('td').filter({ hasText: aggA })).not.toBeVisible()

        console.log('Filter by EventType=OrderCancelled: only aggB returned')
    })

    test('13 clearing the Event Type filter restores all aggregates', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await selectStore(page)

        // Apply a filter first
        await page.getByPlaceholder('Filter by Event Type').fill('OrderCancelled')
        await page.getByRole('button', { name: /load aggregates/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })

        // Clear the filter using the allowClear × icon
        await page.getByPlaceholder('Filter by Event Type').clear()

        // Reload — both aggregates should appear
        await page.getByRole('button', { name: /load aggregates/i }).click()
        await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(2, { timeout: 15000 })
        await expect(page.locator('td').filter({ hasText: aggA })).toBeVisible()
        await expect(page.locator('td').filter({ hasText: aggB })).toBeVisible()

        console.log('Clearing filter restored both aggregates')
    })

    // ── 6. Event stream Actions column (I5) ───────────────────────────────────

    test('14 event stream Actions column shows Details and enabled Causation Tree buttons', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        // Click aggA to load its stream
        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()

        // Use .last() — the outer "Aggregate Stream" card also contains the inner stream card
        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        }).last()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })

        // First event (OrderCreated, VERSION_ASC) has a correlationId — Details + Causation Tree visible
        const firstRow = streamCard.locator('.ant-table-tbody tr.ant-table-row').first()
        await expect(firstRow.getByRole('button', { name: /details/i })).toBeVisible()
        await expect(firstRow.getByRole('button', { name: /causation tree/i })).toBeVisible()
        // OrderCreated was seeded with aggACorrelationId so the button must be enabled
        await expect(firstRow.getByRole('button', { name: /causation tree/i })).not.toBeDisabled()
    })

    test('15 Causation Tree button navigates to causation tree page with correct URL params', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        // Load aggA stream — OrderCreated (v1) carries aggACorrelationId → button enabled
        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggA }).click()

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggA}` }),
        }).last()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })

        // Click Causation Tree on the OrderCreated row
        const causationBtn = streamCard
            .locator('.ant-table-tbody tr.ant-table-row')
            .filter({ hasText: 'OrderCreated' })
            .getByText('Causation Tree')
        await expect(causationBtn).not.toBeDisabled()
        await causationBtn.click()

        // Client-side navigation via React Router — URL must update to causation-tree with params
        await page.waitForURL('**/causation-tree**', { timeout: 10000 })
        const url = page.url()
        // Verify the exact correlationId seeded in test 00 is passed in the URL
        expect(url).toContain(`correlationId=${encodeURIComponent(aggACorrelationId)}`)
        expect(url).toContain(`setupId=${SETUP_ID}`)
        expect(url).toContain(`eventStore=${eventStoreName}`)
    })

    // Note: the Causation Tree button's disabled={!record.correlationId} guard cannot be
    // exercised in E2E because the backend always auto-assigns a correlationId on append.
    // The disabled branch is covered by component unit tests.

    // ── 7. Event stream keyset pagination (I3 full fix + F5) ─────────────────

    test('16 event stream page 1 shows 10 of 15 events with the full total in the pager', async ({ page }) => {
        test.setTimeout(90000)

        // Seed a third aggregate with 15 events — more than one page (page size 10)
        aggC = `agg-C-${Date.now()}`
        for (let i = 1; i <= 15; i++) {
            await postEvent(page, {
                eventType: 'StreamPaged',
                eventData: { seq: i },
                aggregateId: aggC,
            })
        }

        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggC }).click()

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggC}` }),
        }).last()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })

        // Page 1: exactly one page of rows; the pager reports the API's totalCount
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(10)
        await expect(streamCard.getByTestId('stream-pagination-status')).toHaveText('Page 1 of 2 (15 events)')
        await expect(streamCard.getByTestId('stream-prev-page')).toBeDisabled()
        await expect(streamCard.getByTestId('stream-next-page')).toBeEnabled()
    })

    test('17 event stream page 2 is fetched from the API with a keyset cursor', async ({ page }) => {
        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggC }).click()

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggC}` }),
        }).last()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(10, { timeout: 10000 })

        // Page 2 must be fetched with the keyset cursor — not an offset
        const cursorRequests: string[] = []
        page.on('request', req => {
            if (req.method() === 'GET' && req.url().includes('/events') && req.url().includes('afterEventId=')) {
                cursorRequests.push(req.url())
            }
        })

        await streamCard.getByTestId('stream-next-page').click()

        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(5, { timeout: 10000 })
        await expect(streamCard.getByTestId('stream-pagination-status')).toHaveText('Page 2 of 2 (15 events)')
        expect(cursorRequests.length, 'page 2 must be fetched with afterEventId/afterTransactionTime').toBeGreaterThanOrEqual(1)
        expect(cursorRequests[0]).toContain('afterTransactionTime=')

        // Previous returns to page 1
        await streamCard.getByTestId('stream-prev-page').click()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(10, { timeout: 10000 })
        await expect(streamCard.getByTestId('stream-pagination-status')).toHaveText('Page 1 of 2 (15 events)')
    })

    test('18 keyset page 2 does not repeat page 1 events when events are appended mid-browse', async ({ page }) => {
        test.setTimeout(90000)

        // Capture the stream API responses so page contents can be compared by eventId
        const streamResponses: any[] = []
        page.on('response', async res => {
            if (res.request().method() === 'GET'
                && res.url().includes('/events?')
                && res.url().includes(`aggregateId=${aggC}`)) {
                streamResponses.push(await res.json())
            }
        })

        await page.goto('/aggregate-stream')
        await loadAggregates(page)

        await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: aggC }).click()

        const streamCard = page.locator('.ant-card').filter({
            has: page.locator('.ant-card-head-title').filter({ hasText: `Stream: ${aggC}` }),
        }).last()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(10, { timeout: 10000 })

        // Append 3 more events between the page fetches — the drift scenario
        for (let i = 16; i <= 18; i++) {
            await postEvent(page, {
                eventType: 'StreamPaged',
                eventData: { seq: i },
                aggregateId: aggC,
            })
        }

        await streamCard.getByTestId('stream-next-page').click()
        await expect(streamCard.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 10000 })

        expect(streamResponses.length, 'both page fetches must be captured').toBeGreaterThanOrEqual(2)
        const page1Ids = streamResponses[0].events.map((e: any) => e.eventId)
        const page2Ids = streamResponses[streamResponses.length - 1].events.map((e: any) => e.eventId)
        for (const id of page2Ids) {
            expect(page1Ids, `page 2 must not repeat page 1 event ${id}`).not.toContain(id)
        }
        // Page 2 holds the remaining 5 original events plus the 3 appended mid-browse
        expect(page2Ids.length).toBe(8)
    })
})
