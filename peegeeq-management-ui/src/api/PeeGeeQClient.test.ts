import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  PeeGeeQApiError,
  PeeGeeQClient,
  PeeGeeQNetworkError,
} from './PeeGeeQClient'
import { resetBackendConfig, saveBackendConfig } from '../services/configService'
import { HttpTestServer } from '../tests/fixtures/httpTestServer'

const writeJson = (response: import('node:http').ServerResponse, value: unknown, status = 200): void => {
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify(value))
}

describe('PeeGeeQClient', () => {
  let server: HttpTestServer
  let client: PeeGeeQClient
  const transportFetch = globalThis.fetch

  beforeEach(async () => {
    localStorage.clear()
    // Node's fetch rejects jsdom's cross-realm AbortSignal. Strip only that signal while
    // retaining native fetch so serialization and transport still cross a real HTTP socket.
    globalThis.fetch = (input, init) => transportFetch(input, { ...init, signal: undefined })
    server = new HttpTestServer()
    const baseUrl = await server.start()
    saveBackendConfig({ apiUrl: baseUrl })
    client = new PeeGeeQClient({ retryAttempts: 1, retryDelayMs: 0 })
  })

  afterEach(async () => {
    globalThis.fetch = transportFetch
    resetBackendConfig()
    await server.stop()
  })

  it('serializes a request and parses its JSON response over HTTP', async () => {
    server.setResponder((_request, response) => {
      writeJson(response, { setupId: 'alpha', status: 'created' })
    })

    const result = await client.createSetup({ setupId: 'alpha' } as never)

    expect(result).toEqual({ setupId: 'alpha', status: 'created' })
    expect(server.requests).toHaveLength(1)
    expect(server.requests[0]).toMatchObject({
      method: 'POST',
      url: '/api/v1/setups',
      body: JSON.stringify({ setupId: 'alpha' }),
    })
    expect(server.requests[0].headers['content-type']).toBe('application/json')
  })

  it('encodes event query fields and omits undefined values', async () => {
    const resultBody = { events: [], total: 0 }
    server.setResponder((_request, response) => writeJson(response, resultBody))

    await client.queryEvents('alpha', 'orders', {
      eventType: 'OrderCreated',
      aggregateId: 'order/42',
      includeCorrections: true,
      limit: 25,
      validTimeRange: { start: '2026-01-01T00:00:00Z', end: '2026-01-31T00:00:00Z' },
      transactionTimeRange: { start: '2026-02-01T00:00:00Z', end: '2026-02-28T00:00:00Z' },
    })

    const url = new URL(server.requests[0].url, 'http://test.local')
    expect(url.pathname).toBe('/api/v1/eventstores/alpha/orders/events')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      eventType: 'OrderCreated',
      aggregateId: 'order/42',
      includeCorrections: 'true',
      limit: '25',
      validTimeFrom: '2026-01-01T00:00:00Z',
      validTimeTo: '2026-01-31T00:00:00Z',
      transactionTimeFrom: '2026-02-01T00:00:00Z',
      transactionTimeTo: '2026-02-28T00:00:00Z',
    })
  })

  it('returns undefined for a 204 response', async () => {
    server.setResponder((_request, response) => {
      response.statusCode = 204
      response.end()
    })

    await expect(client.deleteSetup('alpha')).resolves.toBeUndefined()
  })

  it('surfaces a structured 4xx response without retrying it', async () => {
    server.setResponder((_request, response) => {
      writeJson(response, {
        error: 'Bad Request',
        message: 'setup is invalid',
        statusCode: 400,
        timestamp: '2026-08-30T00:00:00Z',
      }, 400)
    })

    const error = await client.getSetup('bad').catch(value => value)

    expect(error).toBeInstanceOf(PeeGeeQApiError)
    expect(error).toMatchObject({ statusCode: 400, message: 'setup is invalid' })
    expect(server.requests).toHaveLength(1)
  })

  it('uses response metadata when an error body is not JSON', async () => {
    server.setResponder((_request, response) => {
      response.statusCode = 404
      response.statusMessage = 'Missing'
      response.setHeader('Content-Type', 'text/plain')
      response.end('not-json')
    })

    const error = await client.getSetup('missing').catch(value => value)

    expect(error).toBeInstanceOf(PeeGeeQApiError)
    expect(error).toMatchObject({ statusCode: 404, message: 'Missing' })
  })

  it('retries server failures and returns the first successful response', async () => {
    let attempts = 0
    client = new PeeGeeQClient({ retryAttempts: 3, retryDelayMs: 0 })
    server.setResponder((_request, response) => {
      attempts += 1
      if (attempts < 3) {
        writeJson(response, { message: 'temporary failure' }, 503)
        return
      }
      writeJson(response, [{ setupId: 'recovered' }])
    })

    await expect(client.listSetups()).resolves.toEqual([{ setupId: 'recovered' }])
    expect(attempts).toBe(3)
  })

  it('wraps transport failures as PeeGeeQNetworkError', async () => {
    await server.stop()

    const error = await client.listSetups().catch(value => value)

    expect(error).toBeInstanceOf(PeeGeeQNetworkError)
    expect(error).toMatchObject({ message: 'Network error' })
    expect((error as PeeGeeQNetworkError).cause).toBeInstanceOf(Error)
  })

  it('routes the public operation families through their protocol endpoints', async () => {
    const operations: Array<() => Promise<unknown>> = [
      () => client.listSetups(),
      () => client.getSetupStatus('alpha'),
      () => client.addQueue('alpha', { name: 'orders' } as never),
      () => client.addEventStore('alpha', { name: 'events' } as never),
      () => client.listSetupQueues('alpha'),
      () => client.listSetupEventStores('alpha'),
      () => client.listDeadLetters('alpha', { page: 2, pageSize: 25, topic: 'orders' }),
      () => client.getDeadLetter('alpha', 7),
      () => client.reprocessDeadLetter('alpha', 7),
      () => client.deleteDeadLetter('alpha', 7),
      () => client.cleanupDeadLetters('alpha', 30),
      () => client.getDeadLetterStats('alpha'),
      () => client.listSubscriptions('alpha', 'orders'),
      () => client.getSubscription('alpha', 'orders', 'workers'),
      () => client.pauseSubscription('alpha', 'orders', 'workers'),
      () => client.resumeSubscription('alpha', 'orders', 'workers'),
      () => client.sendHeartbeat('alpha', 'orders', 'workers'),
      () => client.cancelSubscription('alpha', 'orders', 'workers'),
      () => client.getOverallHealth('alpha'),
      () => client.getComponentsHealth('alpha'),
      () => client.getComponentHealth('alpha', 'database'),
      () => client.listEventStores('alpha'),
      () => client.getEventStore('alpha', 'events'),
      () => client.appendEvent('alpha', 'events', { eventType: 'created', payload: {} } as never),
      () => client.getEvent('alpha', 'events', 'event-1'),
      () => client.getEventVersions('alpha', 'events', 'event-1'),
      () => client.correctEvent('alpha', 'events', 'event-1', { payload: {} } as never),
      () => client.getUniqueAggregates('alpha', 'events', 'Order', 20, 5),
      () => client.listConsumerGroups('alpha', 'orders'),
      () => client.getConsumerGroup('alpha', 'orders', 'workers'),
      () => client.getConsumerGroupMembers('alpha', 'orders', 'workers'),
      () => client.getConsumerGroupStats('alpha', 'orders', 'workers'),
      () => client.sendMessage('alpha', 'orders', { payload: { id: 1 } } as never),
      () => client.getMessages('alpha', 'orders', { count: 10 }),
      () => client.acknowledgeMessage('alpha', 'orders', 'message-1'),
      () => client.negativeAcknowledgeMessage('alpha', 'orders', 'message-1'),
      () => client.getQueueDetails('alpha', 'orders'),
      () => client.getQueueStats('alpha', 'orders'),
      () => client.getQueueConsumers('alpha', 'orders'),
      () => client.getQueueBindings('alpha', 'orders'),
      () => client.purgeQueue('alpha', 'orders'),
      () => client.createWebhookSubscription('alpha', 'orders', { url: 'https://example.test/hook' } as never),
      () => client.listWebhookSubscriptions('alpha', 'orders'),
      () => client.getWebhookSubscription('subscription-1'),
      () => client.updateWebhookSubscription('subscription-1', { enabled: false } as never),
      () => client.deleteWebhookSubscription('subscription-1'),
      () => client.getSubscriptionOptions('alpha', 'orders', 'workers'),
      () => client.updateSubscriptionOptions('alpha', 'orders', 'workers', { batchSize: 10 } as never),
    ]

    for (const operation of operations) {
      await operation()
    }

    expect(server.requests).toHaveLength(operations.length)
    expect(server.requests.map(request => `${request.method} ${request.url}`)).toEqual(expect.arrayContaining([
      'GET /api/v1/setups/alpha/status',
      'GET /api/v1/setups/alpha/deadletter/messages?page=2&pageSize=25&topic=orders',
      'POST /api/v1/setups/alpha/subscriptions/orders/workers/pause',
      'GET /api/v1/eventstores/alpha/events/aggregates?limit=20&offset=5&eventType=Order',
      'POST /api/v1/queues/alpha/orders/messages/message-1/ack',
      'DELETE /api/v1/webhook-subscriptions/subscription-1',
      'PUT /api/v1/setups/alpha/subscriptions/orders/workers/options',
    ]))
  })
})

describe('PeeGeeQClient event streaming', () => {
  class BrowserEventSourceFake {
    static instances: BrowserEventSourceFake[] = []

    onmessage: ((event: MessageEvent<string>) => void) | null = null
    onerror: (() => void) | null = null
    closed = false

    constructor(readonly url: string) {
      BrowserEventSourceFake.instances.push(this)
    }

    close(): void {
      this.closed = true
    }

    emit(data: string): void {
      this.onmessage?.(new MessageEvent('message', { data }))
    }

    fail(): void {
      this.onerror?.()
    }
  }

  const originalEventSource = globalThis.EventSource

  afterEach(() => {
    BrowserEventSourceFake.instances = []
    if (originalEventSource) globalThis.EventSource = originalEventSource
    else delete (globalThis as { EventSource?: typeof EventSource }).EventSource
  })

  it('delivers parsed events, reports stream errors, and closes cleanly', () => {
    globalThis.EventSource = BrowserEventSourceFake as unknown as typeof EventSource
    const client = new PeeGeeQClient({ baseUrl: 'http://stream.test' })
    const events: unknown[] = []
    const errors: Error[] = []

    const close = client.streamEvents('alpha', 'events', event => events.push(event), error => errors.push(error))
    const source = BrowserEventSourceFake.instances[0]

    expect(source.url).toBe('http://stream.test/api/v1/eventstores/alpha/events/stream')
    source.emit(JSON.stringify({ id: 'event-1', payload: { value: 42 } }))
    source.emit('{invalid-json')
    source.fail()
    close()

    expect(events).toEqual([{ id: 'event-1', payload: { value: 42 } }])
    expect(errors).toHaveLength(2)
    expect(errors[0]).toBeInstanceOf(SyntaxError)
    expect(errors[1].message).toBe('SSE connection error')
    expect(source.closed).toBe(true)
  })
})
