import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Causation Tree Page – Integration Tests
 *
 * Creates a dedicated event store and posts a 3-event causation chain
 * (RootEvent → ChildEvent → GrandChildEvent) via the backend API, then
 * validates the Causation Tree page against a real backend.
 *
 * Data set:
 *   - RootEvent        correlationId=<dynamic>  causationId=null
 *   - ChildEvent       correlationId=<dynamic>  causationId=<rootEventId>
 *   - GrandChildEvent  correlationId=<dynamic>  causationId=<childEventId>
 *
 * Standalone – creates its own event store; depends only on
 * setup-prerequisite (SETUP_ID must exist).
 */

test.describe.configure({ mode: 'serial' })

test.describe('Causation Tree Page', () => {

    let eventStoreName = ''
    let correlationId = ''
    let aggregateId = ''

    // ── helpers ───────────────────────────────────────────────────────────────

    async function postEvent(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: {
            eventType: string
            eventData: object
            correlationId: string
            causationId?: string
            aggregateId: string
        }
    ): Promise<string> {
        const body: Record<string, unknown> = {
            eventType: payload.eventType,
            eventData: payload.eventData,
            correlationId: payload.correlationId,
            aggregateId: payload.aggregateId,
        }
        if (payload.causationId) body.causationId = payload.causationId

        const response = await page.request.post(
            `/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`,
            { data: body }
        )
        if (!response.ok()) {
            throw new Error(`POST ${payload.eventType} failed: ${response.status()} ${await response.text()}`)
        }
        const json = await response.json()
        return json.eventId as string
    }

    async function selectStore(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await selectAntOption(page.getByTestId('causation-setup-select'), SETUP_ID)
        const storeSelect = page.getByTestId('causation-eventstore-select')
        await expect(storeSelect).not.toHaveClass(/ant-select-disabled/, { timeout: 5000 })
        await selectAntOption(storeSelect, eventStoreName)
    }

    // ── 0. Setup ──────────────────────────────────────────────────────────────

    test('00 setup: create event store and seed 3-event causation chain', async ({ page }) => {
        test.setTimeout(120000)

        eventStoreName = `causation_test_${Date.now()}`
        correlationId  = `corr-causation-${Date.now()}`
        aggregateId    = `agg-causation-${Date.now()}`

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

        const rootId  = await postEvent(page, {
            eventType: 'RootEvent',
            eventData: { step: 1 },
            correlationId,
            aggregateId,
        })
        const childId = await postEvent(page, {
            eventType: 'ChildEvent',
            eventData: { step: 2 },
            correlationId,
            causationId: rootId,
            aggregateId,
        })
        await postEvent(page, {
            eventType: 'GrandChildEvent',
            eventData: { step: 3 },
            correlationId,
            causationId: childId,
            aggregateId,
        })
    })

    // ── 1. Page structure ─────────────────────────────────────────────────────

    test('01 page renders heading and selector card', async ({ page }) => {
        await page.goto('/causation-tree')

        await expect(page.locator('h1.ant-typography')).toContainText('Causation Tree')
        await expect(page.locator('.ant-card-head-title').filter({ hasText: 'Select Event Store' })).toBeVisible()
        await expect(page.getByTestId('causation-setup-select')).toBeVisible()
        await expect(page.getByTestId('causation-eventstore-select')).toBeVisible()
        await expect(page.getByTestId('causation-correlation-input')).toBeVisible()
    })

    test('02 Trace button is disabled before store is selected', async ({ page }) => {
        await page.goto('/causation-tree')
        await expect(page.getByRole('button', { name: /trace/i })).toBeDisabled()
    })

    test('03 event-store dropdown is disabled until setup is selected', async ({ page }) => {
        await page.goto('/causation-tree')
        await expect(page.getByTestId('causation-eventstore-select')).toHaveClass(/ant-select-disabled/)
        await selectAntOption(page.getByTestId('causation-setup-select'), SETUP_ID)
        await expect(page.getByTestId('causation-eventstore-select')).not.toHaveClass(/ant-select-disabled/)
    })

    test('04 Trace button enables after setup and store are selected', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)
        await expect(page.getByRole('button', { name: /trace/i })).toBeEnabled()
    })

    // ── 2. Trace ──────────────────────────────────────────────────────────────

    test('05 tracing the correlation ID renders all 3 tree nodes', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)

        await page.getByTestId('causation-correlation-input').fill(correlationId)
        await page.getByRole('button', { name: /trace/i }).click()

        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
        await expect(page.locator('.ant-tree-treenode').filter({ hasText: /RootEvent/ }).first()).toBeVisible()
        await expect(page.locator('.ant-tree-treenode').filter({ hasText: /ChildEvent/ }).first()).toBeVisible()
        await expect(page.locator('.ant-tree-treenode').filter({ hasText: /GrandChildEvent/ }).first()).toBeVisible()
    })

    test('06 exactly 3 tree nodes are rendered', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)

        await page.getByTestId('causation-correlation-input').fill(correlationId)
        await page.getByRole('button', { name: /trace/i }).click()

        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
        await expect(page.locator('.ant-tree-node-content-wrapper')).toHaveCount(3)
    })

    test('07 pressing Enter in the correlation input triggers Trace', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)

        await page.getByTestId('causation-correlation-input').fill(correlationId)
        await page.getByTestId('causation-correlation-input').press('Enter')

        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
    })

    test('08 empty state is shown before any trace is performed', async ({ page }) => {
        await page.goto('/causation-tree')
        // No selectStore, no input, no Trace click — initial page state must show the empty prompt

        await expect(page.locator('.ant-empty')).toBeVisible({ timeout: 10000 })
        // No Trace has been run so the tree must have no nodes
        await expect(page.locator('.ant-tree-node-content-wrapper')).toHaveCount(0, { timeout: 5000 })
    })

    // ── 3. Event detail drawer ────────────────────────────────────────────────

    test('09 clicking the detail icon on a tree node opens the drawer', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)

        await page.getByTestId('causation-correlation-input').fill(correlationId)
        await page.getByRole('button', { name: /trace/i }).click()
        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })

        await page.locator('.ant-tree-node-content-wrapper').first().getByRole('button').click()

        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })
        await expect(page.locator('.ant-drawer-title')).toContainText('Event Details')
        await expect(page.locator('.ant-descriptions-item-label').filter({ hasText: 'Event Type' })).toBeVisible()
        await expect(page.locator('.ant-descriptions-item-label').filter({ hasText: 'Correlation ID' })).toBeVisible()
    })

    test('10 closing the drawer hides it', async ({ page }) => {
        await page.goto('/causation-tree')
        await selectStore(page)

        await page.getByTestId('causation-correlation-input').fill(correlationId)
        await page.getByRole('button', { name: /trace/i }).click()
        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })

        await page.locator('.ant-tree-node-content-wrapper').first().getByRole('button').click()
        await expect(page.locator('.ant-drawer-open')).toBeVisible({ timeout: 5000 })

        await page.locator('.ant-drawer-close').click()
        await expect(page.locator('.ant-drawer-open')).not.toBeVisible({ timeout: 5000 })
    })

    // ── 4. Deep-link from Aggregate Stream (I5) ───────────────────────────────

    test('11 deep-link URL params auto-populate the form and trigger trace without manual Trace click', async ({ page }) => {
        // I5: CausationTreePage reads correlationId/setupId/eventStore from URL search params
        // on mount and auto-triggers fetchCausationTree() once the event-store list loads.
        // This test verifies the full deep-link path — no Trace button click required.
        await page.goto(
            `/causation-tree` +
            `?correlationId=${encodeURIComponent(correlationId)}` +
            `&setupId=${encodeURIComponent(SETUP_ID)}` +
            `&eventStore=${encodeURIComponent(eventStoreName)}`
        )

        // All 3 seeded events must appear without any manual Trace button click
        await expect(page.locator('.ant-tree-node-content-wrapper').first()).toBeVisible({ timeout: 20000 })
        await expect(page.locator('.ant-tree-node-content-wrapper')).toHaveCount(3)

        // The correlation ID input must be pre-populated from the URL param
        await expect(page.getByTestId('causation-correlation-input')).toHaveValue(correlationId)
    })
})
