import { test, expect } from '../page-objects'
import { SETUP_ID } from '../test-constants'
import { selectAntOption } from '../utils/ant-helpers'

/**
 * Queue Updates SSE – Direct API Tests
 *
 * These tests verify the real-time queue-list notification endpoint:
 *   GET /api/v1/sse/queues/{setupId}
 *
 * The backend publishes to the Vert.x event bus address
 * `peegeeq.queues.changed.{setupId}` whenever a queue is created, updated,
 * or deleted.  The SSE handler consumes that address and pushes named events
 * to all connected clients.
 *
 * Approach (same pattern as message-sse-stream.spec.ts):
 *   1. Open a native EventSource in the browser context  (page.evaluate)
 *      → same origin as the app, routed through the Vite proxy
 *   2. Trigger queue mutations via the REST management API (page.request)
 *   3. Poll for received named SSE events                 (page.waitForFunction)
 *   4. Assert payload fields
 *
 * Named SSE events used:
 *   - "connected"      emitted once on connection establishment
 *   - "queue-changed"  emitted on QUEUE_CREATED / QUEUE_UPDATED / QUEUE_DELETED
 *   - "heartbeat"      emitted every 30 s (not tested here)
 *
 * Depends on: 3c-setup-prerequisite (SETUP_ID must exist)
 */

test.describe.configure({ mode: 'serial' })

test.describe('Queue Updates SSE – Direct API', () => {

    // ── helpers ───────────────────────────────────────────────────────────────

    type PageArg = Parameters<Parameters<typeof test>[1]>[0]

    async function createQueue(page: PageArg, name: string) {
        const resp = await page.request.post('/api/v1/management/queues', {
            data: { setupId: SETUP_ID, name, type: 'native' },
        })
        if (!resp.ok()) throw new Error(`Create queue failed: ${resp.status()} ${await resp.text()}`)
        // The POST returns before the queue is registered in the setup's queueFactories.
        // Callers navigate to a fresh page whose queue-list fetch (and page SSE, subscribed
        // only after nav — so it misses this QUEUE_CREATED) would otherwise race that
        // registration and not show the row. Poll the source of truth instead of a fixed delay.
        await expect(async () => {
            const listResp = await page.request.get(`/api/v1/setups/${SETUP_ID}`)
            expect(listResp.ok()).toBeTruthy()
            const factories = (await listResp.json())?.queueFactories
            const names: string[] = Array.isArray(factories)
                ? factories
                : (factories && typeof factories === 'object' ? Object.keys(factories) : [])
            expect(names).toContain(name)
        }).toPass({ timeout: 15000 })
    }

    async function deleteQueue(page: PageArg, name: string) {
        const resp = await page.request.delete(`/api/v1/management/queues/${SETUP_ID}/${name}`)
        if (!resp.ok()) throw new Error(`Delete queue failed: ${resp.status()} ${await resp.text()}`)
    }

    /**
     * Open a native EventSource at /api/v1/sse/queues/{SETUP_ID} inside the
     * browser context and store:
     *   window.__sseConnected   – true once the "connected" event fires
     *   window.__sseQueueEvents – array of parsed "queue-changed" event payloads
     */
    async function openQueueUpdatesSse(page: PageArg) {
        const url = `/api/v1/sse/queues/${SETUP_ID}`

        await page.evaluate((sseUrl: string) => {
            (window as any).__sseConnected   = false
            ;(window as any).__sseQueueEvents = []
            ;(window as any).__sseError      = null

            const es = new EventSource(sseUrl)

            es.addEventListener('connected', (event: Event) => {
                (window as any).__sseConnected = true
                ;(window as any).__sseConnectedData = JSON.parse((event as MessageEvent).data)
            })

            es.addEventListener('queue-changed', (event: Event) => {
                try {
                    const data = JSON.parse((event as MessageEvent).data)
                    ;(window as any).__sseQueueEvents.push(data)
                } catch { /* ignore malformed frames */ }
            })

            es.onerror = () => {
                (window as any).__sseError = 'EventSource error'
            }

            ;(window as any).__sseQueueEs = es
        }, url)

        // Wait for the initial "connected" handshake
        await page.waitForFunction(
            () => (window as any).__sseConnected === true,
            { timeout: 10000 }
        )
    }

    async function closeQueueUpdatesSse(page: PageArg) {
        await page.evaluate(() => {
            (window as any).__sseQueueEs?.close()
            ;(window as any).__sseQueueEs = null
        })
    }

    async function waitForQueueEvents(page: PageArg, count: number, timeout = 15000) {
        await page.waitForFunction(
            (n: number) => (window as any).__sseQueueEvents.length >= n,
            count,
            { timeout }
        )
    }

    async function getQueueEvents(page: PageArg) {
        return page.evaluate(() => (window as any).__sseQueueEvents as any[])
    }

    async function getConnectedData(page: PageArg) {
        return page.evaluate(() => (window as any).__sseConnectedData as any)
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    test.beforeEach(async ({ page }) => {
        await page.goto('/')
        await page.waitForLoadState('load')
    })

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Test 1: Connection establishment
     * The endpoint returns 200 and emits a "connected" event with the
     * correct setupId and a connectionId field.
     */
    test('01 connection emits "connected" event with correct setupId', async ({ page }) => {
        await openQueueUpdatesSse(page)

        const connectedData = await getConnectedData(page)

        expect(connectedData).toBeDefined()
        expect(connectedData.setupId).toBe(SETUP_ID)
        expect(connectedData.connectionId).toBeDefined()
        expect(typeof connectedData.connectionId).toBe('string')
        expect(connectedData.connectionId.length).toBeGreaterThan(0)

        await closeQueueUpdatesSse(page)
    })

    /**
     * Test 2: Queue create notification
     * Creating a queue via the management API must push a "queue-changed"
     * event with event=QUEUE_CREATED to all connected SSE clients.
     */
    test('02 creating a queue triggers QUEUE_CREATED notification', async ({ page }) => {
        const queueName = `sse_upd_create_${Date.now()}`

        await openQueueUpdatesSse(page)

        await createQueue(page, queueName)

        await waitForQueueEvents(page, 1)

        const events = await getQueueEvents(page)

        expect(events.length).toBeGreaterThanOrEqual(1)
        const created = events.find((e: any) => e.event === 'QUEUE_CREATED' && e.queueName === queueName)
        expect(created).toBeDefined()
        expect(created.setupId).toBe(SETUP_ID)
        expect(typeof created.timestamp).toBe('number')

        await closeQueueUpdatesSse(page)
    })

    /**
     * Test 3: Queue delete notification
     * Deleting a queue must push a "queue-changed" event with
     * event=QUEUE_DELETED.
     */
    test('03 deleting a queue triggers QUEUE_DELETED notification', async ({ page }) => {
        const queueName = `sse_upd_delete_${Date.now()}`

        // Pre-create the queue
        await createQueue(page, queueName)

        await openQueueUpdatesSse(page)

        // Clear any events from the initial connection window
        await page.evaluate(() => { (window as any).__sseQueueEvents = [] })

        await deleteQueue(page, queueName)

        await waitForQueueEvents(page, 1)

        const events = await getQueueEvents(page)

        expect(events.length).toBeGreaterThanOrEqual(1)
        const deleted = events.find((e: any) => e.event === 'QUEUE_DELETED' && e.queueName === queueName)
        expect(deleted).toBeDefined()
        expect(deleted.setupId).toBe(SETUP_ID)

        await closeQueueUpdatesSse(page)
    })

    /**
     * Test 4: Payload completeness
     * Every "queue-changed" event must carry event, setupId, queueName,
     * and timestamp fields.
     */
    test('04 queue-changed payload contains required fields', async ({ page }) => {
        const queueName = `sse_upd_payload_${Date.now()}`

        await openQueueUpdatesSse(page)

        await createQueue(page, queueName)

        await waitForQueueEvents(page, 1)

        const events = await getQueueEvents(page)
        const ev = events.find((e: any) => e.queueName === queueName)

        expect(ev).toBeDefined()
        expect(ev).toHaveProperty('event')
        expect(ev).toHaveProperty('setupId')
        expect(ev).toHaveProperty('queueName')
        expect(ev).toHaveProperty('timestamp')
        expect(typeof ev.event).toBe('string')
        expect(typeof ev.timestamp).toBe('number')

        await closeQueueUpdatesSse(page)
    })

    /**
     * Test 5: Multiple mutations — events arrive in order
     * Create then delete a queue; both change events must arrive and
     * carry the correct event type.
     */
    test('05 create-then-delete produces two ordered notifications', async ({ page }) => {
        const queueName = `sse_upd_order_${Date.now()}`

        await openQueueUpdatesSse(page)

        await createQueue(page, queueName)
        await waitForQueueEvents(page, 1)

        await deleteQueue(page, queueName)
        await waitForQueueEvents(page, 2)

        const events = await getQueueEvents(page)
        const forQueue = events.filter((e: any) => e.queueName === queueName)

        expect(forQueue.length).toBe(2)
        expect(forQueue[0].event).toBe('QUEUE_CREATED')
        expect(forQueue[1].event).toBe('QUEUE_DELETED')

        await closeQueueUpdatesSse(page)
    })

    /**
     * Test 6 (Phase 9): the Queues page live message count refreshes in real time after a
     * message is published via the REST API — no manual refresh. This is the end-to-end UI
     * proof: publish → backend `MESSAGE_SENT` queue-changed → the page's own queue-updates SSE
     * → `refetch()` → the Messages column updates. The page subscribes to the SSE only when a
     * setup is selected (createQueueUpdatesSSE is gated on selectedSetupId), so the setup is
     * scoped first.
     */
    test('06 publishing a message updates the queues-list count in real time (no manual refresh)', async ({ page }) => {
        const queueName = `sse_upd_count_${Date.now()}`
        await createQueue(page, queueName) // fresh queue → count 0

        await page.getByTestId('nav-queues').click()
        await page.waitForLoadState('load')

        // Scope to the setup so the page subscribes to /api/v1/sse/queues/{setupId}
        const setupSelector = page.getByTestId('setup-scope-selector')
        if (!(await setupSelector.locator('.ant-select-selection-item').isVisible())) {
            await selectAntOption(setupSelector, SETUP_ID)
        }

        // The new queue's row; its Messages cell (column index 1) starts at 0
        // Late in the full suite the setup holds many queues, so the new one paginates off page 1.
        // Filter the list to it (the search box filters live on input) so its row is on the page.
        await page.getByPlaceholder('Search queues...').fill(queueName)

        const row = page.locator('.ant-table-row').filter({ hasText: queueName })
        await expect(row).toBeVisible({ timeout: 30000 })
        const messagesCell = row.locator('td.ant-table-cell').nth(1)
        await expect(messagesCell).toContainText('0')

        // Allow the per-setup SSE connection to establish before mutating
        await page.waitForTimeout(1000)

        // Publish via the REST API directly — not via the UI, and with no manual refresh click
        const resp = await page.request.post(`/api/v1/queues/${SETUP_ID}/${queueName}/messages`, {
            data: { payload: { test: true }, headers: {} },
        })
        if (!resp.ok()) throw new Error(`Publish message failed: ${resp.status()} ${await resp.text()}`)

        // Count refreshes via SSE → refetch within a few seconds, without touching the refresh button
        await expect(messagesCell).toContainText('1', { timeout: 8000 })
    })
})
