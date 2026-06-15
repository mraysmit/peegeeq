import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Event Visualization – causation data contract (real backend, NO mock)
 *
 * Rewritten 2026-06-15 from a fully-mocked spec to use the REAL backend, as part of
 * the module-wide no-mock cleanup. Creates an isolated event store, seeds a 3-event
 * causation chain (RootEvent → ChildEvent → GrandChildEvent), then reads the events
 * back from GET /api/v1/eventstores/{setup}/{store}/events and asserts the causation
 * linkage that the Causation Tree page is built from.
 *
 * The UI tree rendering itself (RootEvent → ChildEvent → GrandChildEvent nodes) is
 * covered for real by causation-tree.spec.ts (tests 05/06); this spec deliberately
 * does NOT duplicate the rendering and focuses on the backend response contract.
 *
 * Previously this spec validated a hand-written MOCK_EVENTS fixture against a
 * hand-written JSON schema — two fixtures checked against each other, proving nothing
 * about the real API. Both fixtures were removed.
 *
 * Depends on setup-prerequisite (SETUP_ID must exist).
 */
test.describe.configure({ mode: 'serial' })

test.describe('Event Visualization – causation data contract', () => {

  let eventStoreName = ''
  let correlationId = ''
  let aggregateId = ''
  let rootId = ''
  let childId = ''

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

  test('00 setup: create event store and seed a 3-event causation chain', async ({ page }) => {
    test.setTimeout(120000)

    eventStoreName = `viz_contract_${Date.now()}`
    correlationId  = `corr-viz-${Date.now()}`
    aggregateId    = `agg-viz-${Date.now()}`

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

    rootId = await postEvent(page, {
      eventType: 'RootEvent',
      eventData: { step: 1 },
      correlationId,
      aggregateId,
    })
    childId = await postEvent(page, {
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

  test('01 the real events response exposes the causation linkage the tree is built from', async ({ page }) => {
    const resp = await page.request.get(`/api/v1/eventstores/${SETUP_ID}/${eventStoreName}/events`)
    expect(resp.ok()).toBeTruthy()
    const body = await resp.json()

    // Response envelope the visualization reads
    expect(Array.isArray(body.events), 'response has an events array').toBeTruthy()

    const seeded = body.events.filter((e: any) => e.correlationId === correlationId)
    expect(seeded.length, 'all 3 seeded events are returned').toBe(3)

    const root = seeded.find((e: any) => e.eventType === 'RootEvent')
    const child = seeded.find((e: any) => e.eventType === 'ChildEvent')
    const grandChild = seeded.find((e: any) => e.eventType === 'GrandChildEvent')
    expect(root && child && grandChild, 'all three event types are present').toBeTruthy()

    // Every event carries the fields the Causation Tree consumes
    for (const e of seeded) {
      expect(e.eventId, 'eventId present').toBeTruthy()
      expect(e.correlationId, 'correlationId present').toBe(correlationId)
      expect(e.aggregateId, 'aggregateId present').toBe(aggregateId)
      expect(e.eventData, 'eventData present').toBeTruthy()
    }

    // Causation linkage matches exactly what we posted: root → child → grandchild
    expect(root.eventId, 'root event id').toBe(rootId)
    expect(root.causationId == null, 'RootEvent has no causationId').toBeTruthy()
    expect(child.causationId, 'ChildEvent.causationId === RootEvent.eventId').toBe(rootId)
    expect(grandChild.causationId, 'GrandChildEvent.causationId === ChildEvent.eventId').toBe(childId)
  })
})
