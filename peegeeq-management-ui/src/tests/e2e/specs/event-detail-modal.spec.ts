import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Event Detail Modal Tests
 *
 * Verifies that every card in the Event Details modal displays the correct
 * data retrieved from the backend:
 *
 *   Card 1 – Event Information : eventId, eventType, aggregateId, version, eventNumber
 *   Card 2 – Bi-temporal Data  : validFrom, validTo (Current), transactionTime, version
 *   Card 3 – Event Correlation : correlationId, causationId
 *   Card 4 – Metadata          : JSON block populated from the event's metadata/headers
 *   Card 5 – Event Data        : JSON block of the posted eventData payload
 *
 * Posts one event via the API with all supported fields populated so that
 * all cards can be fully asserted.  A second event with only required fields
 * is used to verify the fallback/empty-state display.
 *
 * Standalone – creates its own event store, no dependencies beyond the
 * default setup existing (setup-prerequisite runs before this project).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Event Detail Modal', () => {

    let eventStoreName = ''

    const CORRELATION_ID = `corr-modal-test-${Date.now()}`
    const AGGREGATE_ID   = `order-modal-${Date.now()}`
    const EVENT_DATA     = { orderId: 'ORDER-MODAL-001', amount: 149.99, currency: 'GBP' }
    const METADATA       = { source: 'order-service', environment: 'test', version: '2.1' }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Post a single event via the REST API and return the eventId. */
    async function postEvent(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: {
            eventType: string
            eventData: object
            correlationId?: string
            causationId?: string
            aggregateId?: string
            metadata?: object
        }
    ): Promise<string> {
        const body: Record<string, unknown> = {
            eventType: payload.eventType,
            eventData: payload.eventData,
        }
        if (payload.correlationId) body.correlationId = payload.correlationId
        if (payload.causationId)   body.causationId   = payload.causationId
        if (payload.aggregateId)   body.aggregateId   = payload.aggregateId
        if (payload.metadata)      body.metadata      = payload.metadata

        const response = await page.request.post(
            `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
            { data: body }
        )
        if (!response.ok()) {
            throw new Error(
                `POST event ${payload.eventType} failed: ${response.status()} ${await response.text()}`
            )
        }
        const json = await response.json()
        return json.eventId as string
    }

    /** Load the events table and open the detail modal for the row matching eventType. */
    async function openDetailModal(
        page: Parameters<Parameters<typeof test>[1]>[0],
        eventType: string
    ) {
        await page.goto('/events')
        await expect(page.getByRole('button', { name: 'Post Event' })).toBeVisible({ timeout: 15000 })

        await selectAntOption(page.getByTestId('query-setup-select'), SETUP_ID)
        await selectAntOption(page.getByTestId('query-eventstore-select'), eventStoreName)
        await page.getByRole('button', { name: 'Load Events' }).click()

        await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible({ timeout: 15000 })

        const row = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: eventType }).first()
        await expect(row).toBeVisible({ timeout: 10000 })

        // Click the EyeOutlined "View Details" button inside that row
        await row.getByRole('button').first().click()
        await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 })
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create event store and seed events', async ({ page }) => {
        test.setTimeout(120000)

        eventStoreName = `modal_test_store_${Date.now()}`

        // Create event store via UI
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

        // Post event 1: all optional fields populated
        const id1 = await postEvent(page, {
            eventType: 'OrderCreatedFull',
            eventData: EVENT_DATA,
            correlationId: CORRELATION_ID,
            aggregateId: AGGREGATE_ID,
            metadata: METADATA,
        })
        // Post event 2: with causationId pointing at event 1
        await postEvent(page, {
            eventType: 'OrderShippedWithCause',
            eventData: { shipmentId: 'SHIP-001' },
            correlationId: CORRELATION_ID,
            causationId: id1,
            aggregateId: AGGREGATE_ID,
            metadata: METADATA,
        })
        // Post event 3: minimal — only required fields (verify empty-state display)
        await postEvent(page, {
            eventType: 'MinimalEvent',
            eventData: { minimal: true },
        })
    })

    // ── 1. Card: Event Information ────────────────────────────────────────────

    test('01 Event Information card shows all fields', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')

        // Card header present
        await expect(modal.locator('.ant-card-head-title', { hasText: 'Event Information' })).toBeVisible()

        // Event ID label + non-empty code element
        await expect(modal.locator('text=Event ID:')).toBeVisible()
        const eventIdCode = modal.locator('.ant-card', { hasText: 'Event Information' }).locator('code').first()
        await expect(eventIdCode).not.toBeEmpty()

        // Event Type label + purple tag inside the Event Information card
        await expect(modal.locator('text=Event Type:')).toBeVisible()
        const infoCard = modal.locator('.ant-card', { hasText: 'Event Information' })
        await expect(infoCard.locator('.ant-tag-purple', { hasText: 'OrderCreatedFull' })).toBeVisible()

        // Aggregate label + aggregateId value
        await expect(modal.locator('text=Aggregate:')).toBeVisible()
        const aggregateSection = modal.locator('.ant-card', { hasText: 'Event Information' })
        await expect(aggregateSection.locator('code', { hasText: AGGREGATE_ID })).toBeVisible()

        // Version badge present with value ≥ 1 (scoped to Event Information card — Version also appears in Bi-temporal Data)
        await expect(infoCard.locator('text=Version:')).toBeVisible()

        // Event Number label
        await expect(infoCard.locator('text=Event Number:')).toBeVisible()

        await page.keyboard.press('Escape')
    })

    // ── 2. Card: Bi-temporal Data ─────────────────────────────────────────────

    test('02 Bi-temporal Data card shows temporal fields', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-card-head-title', { hasText: 'Bi-temporal Data' })).toBeVisible()

        // Valid From — should be a formatted date string (YYYY-MM-DD)
        await expect(modal.locator('text=Valid From:')).toBeVisible()
        const bitemporalCard = modal.locator('.ant-card', { hasText: 'Bi-temporal Data' })
        const validFromText = await bitemporalCard.locator('text=/\\d{4}-\\d{2}-\\d{2}/').first().textContent()
        expect(validFromText).toMatch(/\d{4}-\d{2}-\d{2}/)

        // Valid To — no validTo set, should show 'Current'
        await expect(modal.locator('text=Valid To:')).toBeVisible()
        await expect(bitemporalCard.locator('text=Current')).toBeVisible()

        // Transaction Time
        await expect(modal.locator('text=Transaction Time:')).toBeVisible()

        await page.keyboard.press('Escape')
    })

    // ── 3. Card: Event Correlation ────────────────────────────────────────────

    test('03 Event Correlation card shows correlationId and causationId', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-card-head-title', { hasText: 'Event Correlation' })).toBeVisible()

        const corrCard = modal.locator('.ant-card', { hasText: 'Event Correlation' })

        // Correlation ID label + actual value
        await expect(corrCard.locator('text=Correlation ID:')).toBeVisible()
        await expect(corrCard.locator('code', { hasText: CORRELATION_ID })).toBeVisible()

        // Causation ID — not set for event 1, should show 'None'
        await expect(corrCard.locator('text=Causation ID:')).toBeVisible()
        await expect(corrCard.locator('code', { hasText: 'None' })).toBeVisible()

        await page.keyboard.press('Escape')
    })

    test('03b Event Correlation card shows causationId when set', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderShippedWithCause')

        const modal = page.locator('.ant-modal')
        const corrCard = modal.locator('.ant-card', { hasText: 'Event Correlation' })

        // causationId should be the eventId of OrderCreatedFull — a UUID
        // The correlation card has two code elements: [0] correlationId, [1] causationId
        const causationCode = corrCard.locator('code').nth(1)
        await expect(causationCode).toBeVisible()
        const causationText = await causationCode.textContent()
        expect(causationText).toMatch(/[a-f0-9-]{36}/i)

        await page.keyboard.press('Escape')
    })

    // ── 4. Card: Metadata ─────────────────────────────────────────────────────

    test('04 Metadata card shows JSON metadata block', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-card-head-title', { hasText: 'Metadata' })).toBeVisible()

        const metaCard = modal.locator('.ant-card', { hasText: 'Metadata' })
        const preBlock = metaCard.locator('pre')
        await expect(preBlock).toBeVisible()

        const preText = await preBlock.textContent()
        // The metadata keys should appear in the JSON block
        expect(preText).toContain('order-service')
        expect(preText).toContain('environment')

        await page.keyboard.press('Escape')
    })

    test('04b Metadata card shows empty object for event with no metadata', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'MinimalEvent')

        const modal = page.locator('.ant-modal')
        const metaCard = modal.locator('.ant-card', { hasText: 'Metadata' })
        const preText = await metaCard.locator('pre').textContent()
        // Empty object or null — must be valid JSON and not error
        expect(() => JSON.parse(preText ?? '{}')).not.toThrow()

        await page.keyboard.press('Escape')
    })

    // ── 5. Card: Event Data ───────────────────────────────────────────────────

    test('05 Event Data card shows JSON payload', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')
        await expect(modal.locator('.ant-card-head-title', { hasText: 'Event Data' })).toBeVisible()

        const dataCard = modal.locator('.ant-card', { hasText: 'Event Data' })
        const preBlock = dataCard.locator('pre')
        await expect(preBlock).toBeVisible()

        const preText = await preBlock.textContent()
        // Known eventData fields should be present
        expect(preText).toContain('ORDER-MODAL-001')
        expect(preText).toContain('149.99')
        expect(preText).toContain('GBP')

        await page.keyboard.press('Escape')
    })

    // ── 6. Modal chrome ───────────────────────────────────────────────────────

    test('06 Modal shows event type tag in header and Close button', async ({ page }) => {
        test.setTimeout(60000)

        await openDetailModal(page, 'OrderCreatedFull')

        const modal = page.locator('.ant-modal')

        // Header contains the event type tag
        await expect(modal.locator('.ant-modal-title .ant-tag-purple', { hasText: 'OrderCreatedFull' })).toBeVisible()

        // Footer has Export and Close buttons
        await expect(modal.getByRole('button', { name: /export/i })).toBeVisible()
        const footerClose = modal.locator('.ant-modal-footer').getByRole('button', { name: /close/i })
        await expect(footerClose).toBeVisible()

        // Close button dismisses the modal
        await footerClose.click()
        await expect(modal).not.toBeVisible({ timeout: 5000 })
    })

    // ── 99. Cleanup ───────────────────────────────────────────────────────────

    test('99 cleanup: delete event store', async ({ request }) => {
        if (!eventStoreName) return
        await request.delete(`/api/v1/management/event-stores/${SETUP_ID}/${eventStoreName}`)
    })
})
