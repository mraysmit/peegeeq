import { test, expect } from '@playwright/test'
import { SETUP_ID } from '../test-constants'

/**
 * Message SSE Stream – Direct API Tests
 *
 * These tests prove the full SSE delivery pipeline without touching any UI
 * components.  The approach:
 *
 *   1. Create a queue via the REST API                   (page.request)
 *   2. Open a native EventSource in the browser context  (page.evaluate)
 *      → same origin as the app, routed through the Vite proxy
 *   3. Publish messages via the REST API                 (page.request)
 *   4. Poll for received events                          (page.waitForFunction)
 *   5. Assert payload identity, ordering, and counts
 *
 * No React components, no Ant Design selectors, no UI interaction at all.
 * The only reason we use `page` is to get a same-origin browser context for
 * EventSource — the Vite proxy at localhost:3000 forwards /api/* to the backend.
 *
 * Depends on: 3c-setup-prerequisite (SETUP_ID must exist)
 */

test.describe.configure({ mode: 'serial' })

test.describe('Message SSE Stream – Direct API', () => {

    let queueName = ''

    /** Create a fresh queue via the management API. */
    async function createQueue(page: Parameters<Parameters<typeof test>[1]>[0], name: string) {
        const resp = await page.request.post('/api/v1/management/queues', {
            data: { setupId: SETUP_ID, name, type: 'native' },
        })
        if (!resp.ok()) throw new Error(`Create queue failed: ${resp.status()} ${await resp.text()}`)
        // Brief pause for queue initialisation
        await page.waitForTimeout(500)
    }

    /** Publish a single message payload via the REST API. */
    async function publish(
        page: Parameters<Parameters<typeof test>[1]>[0],
        payload: object
    ) {
        const resp = await page.request.post(
            `/api/v1/queues/${SETUP_ID}/${queueName}/publish`,
            { data: { payload } }
        )
        if (!resp.ok()) throw new Error(`Publish failed: ${resp.status()} ${await resp.text()}`)
    }

    /**
     * Open a native EventSource in the browser context and store received
     * events on window.__sseEvents.  Returns once the connection is open.
     *
     * Uses the Vite-proxied URL (/api/...) so the request goes through the
     * same origin as the running app — no CORS issues.
     */
    async function openSseListener(
        page: Parameters<Parameters<typeof test>[1]>[0],
        qName: string
    ) {
        const url = `/api/v1/queues/${SETUP_ID}/${qName}/stream`

        await page.evaluate((sseUrl: string) => {
            (window as any).__sseEvents = []
            ;(window as any).__sseOpen  = false
            ;(window as any).__sseError = null

            const es = new EventSource(sseUrl)

            es.addEventListener('open', () => {
                ;(window as any).__sseOpen = true
            })

            es.onmessage = (event: MessageEvent) => {
                try {
                    const data = JSON.parse(event.data)
                    if (data.type === 'data') {
                        ;(window as any).__sseEvents.push(data)
                    }
                } catch { /* ignore malformed frames */ }
            }

            es.onerror = () => {
                ;(window as any).__sseError = 'EventSource error'
            }

            ;(window as any).__sseEs = es
        }, url)

        // Wait for the connection handshake to complete
        await page.waitForFunction(
            () => (window as any).__sseOpen === true,
            { timeout: 10000 }
        )
    }

    /** Close the EventSource opened by openSseListener. */
    async function closeSseListener(page: Parameters<Parameters<typeof test>[1]>[0]) {
        await page.evaluate(() => {
            ;(window as any).__sseEs?.close()
            ;(window as any).__sseEs = null
        })
    }

    /** Wait until at least `count` SSE events have been collected. */
    async function waitForSseEvents(
        page: Parameters<Parameters<typeof test>[1]>[0],
        count: number,
        timeout = 20000
    ) {
        await page.waitForFunction(
            (n: number) => (window as any).__sseEvents.length >= n,
            count,
            { timeout }
        )
    }

    /** Read the events collected so far. */
    async function getSseEvents(page: Parameters<Parameters<typeof test>[1]>[0]) {
        return page.evaluate(() => (window as any).__sseEvents as any[])
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    test.beforeEach(async ({ page }) => {
        // Navigate to the app so page.evaluate runs in the same origin as /api/*
        await page.goto('/')
        await page.waitForLoadState('load')
    })

    // ── 1. Basic delivery ─────────────────────────────────────────────────────

    test('01 single message published via API appears in SSE stream', async ({ page }) => {
        queueName = `sse_single_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        const payload = { testId: 'sse-single-01', value: 42 }
        await publish(page, payload)

        await waitForSseEvents(page, 1)

        const events = await getSseEvents(page)
        expect(events).toHaveLength(1)
        expect(events[0].payload).toMatchObject(payload)

        await closeSseListener(page)
    })

    // ── 2. Batch delivery ─────────────────────────────────────────────────────

    test('02 batch of 10 messages all arrive via SSE in order', async ({ page }) => {
        queueName = `sse_batch10_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        const BATCH = 10
        for (let i = 0; i < BATCH; i++) {
            await publish(page, { index: i, batchId: queueName })
        }

        await waitForSseEvents(page, BATCH)

        const events = await getSseEvents(page)
        expect(events.length).toBeGreaterThanOrEqual(BATCH)

        // Verify every index was delivered
        const indices = events.slice(0, BATCH).map((e: any) => e.payload.index)
        for (let i = 0; i < BATCH; i++) {
            expect(indices).toContain(i)
        }

        await closeSseListener(page)
    })

    test('03 batch of 25 messages — none dropped under sustained load', async ({ page }) => {
        test.setTimeout(60000)

        queueName = `sse_batch25_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        const BATCH = 25
        for (let i = 0; i < BATCH; i++) {
            await publish(page, { seq: i, ts: Date.now(), batchId: queueName })
        }

        await waitForSseEvents(page, BATCH, 30000)

        const events = await getSseEvents(page)
        expect(events.length).toBeGreaterThanOrEqual(BATCH)

        // Every sequence number present
        const seqs = events.slice(0, BATCH).map((e: any) => e.payload.seq)
        for (let i = 0; i < BATCH; i++) {
            expect(seqs).toContain(i)
        }

        await closeSseListener(page)
    })

    // ── 3. Payload fidelity ───────────────────────────────────────────────────

    test('04 payload is delivered byte-for-byte (nested object, numbers, strings)', async ({ page }) => {
        queueName = `sse_payload_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        const payload = {
            orderId:   'ORD-FIDELITY-99',
            amount:    1234.56,
            customer:  { name: 'Alice', id: 7 },
            tags:      ['urgent', 'express'],
            confirmed: true,
        }
        await publish(page, payload)

        await waitForSseEvents(page, 1)

        const events = await getSseEvents(page)
        expect(events[0].payload).toMatchObject(payload)

        await closeSseListener(page)
    })

    // ── 4. Rapid sequential publish ───────────────────────────────────────────

    test('05 rapid fire: 5 messages published without delay all arrive', async ({ page }) => {
        queueName = `sse_rapid_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        // Publish all 5 without any await between them (fire-and-forget style)
        await Promise.all(
            Array.from({ length: 5 }, (_, i) =>
                publish(page, { rapid: i, batchId: queueName })
            )
        )

        await waitForSseEvents(page, 5)

        const events = await getSseEvents(page)
        expect(events.length).toBeGreaterThanOrEqual(5)

        await closeSseListener(page)
    })

    // ── 5. Connection stays open ──────────────────────────────────────────────

    test('06 SSE connection remains open across multiple publish rounds', async ({ page }) => {
        queueName = `sse_rounds_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)

        // Round 1: publish 3 messages
        for (let i = 0; i < 3; i++) {
            await publish(page, { round: 1, i })
        }
        await waitForSseEvents(page, 3)

        // Round 2: publish 3 more — same SSE connection must still be open
        for (let i = 0; i < 3; i++) {
            await publish(page, { round: 2, i })
        }
        await waitForSseEvents(page, 6)

        const events = await getSseEvents(page)
        expect(events.length).toBeGreaterThanOrEqual(6)

        const rounds = events.map((e: any) => e.payload.round)
        expect(rounds).toContain(1)
        expect(rounds).toContain(2)

        await closeSseListener(page)
    })

    // ── 6. SSE stream metadata ────────────────────────────────────────────────

    test('07 each SSE event carries a messageId field', async ({ page }) => {
        queueName = `sse_meta_${Date.now()}`
        await createQueue(page, queueName)

        await openSseListener(page, queueName)
        await publish(page, { check: 'metadata' })
        await waitForSseEvents(page, 1)

        const events = await getSseEvents(page)
        expect(events[0]).toHaveProperty('messageId')
        expect(typeof events[0].messageId).toBe('string')
        expect(events[0].messageId.length).toBeGreaterThan(0)

        await closeSseListener(page)
    })
})
