import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { configureStore } from '@reduxjs/toolkit'
import { queuesApi } from './queuesApi'
import { resetBackendConfig, saveBackendConfig } from '../../services/configService'
import { HttpTestServer } from '../../tests/fixtures/httpTestServer'

const writeJson = (response: import('node:http').ServerResponse, value: unknown, status = 200): void => {
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify(value))
}

const createQueueApiStore = () => configureStore({
  reducer: { [queuesApi.reducerPath]: queuesApi.reducer },
  middleware: getDefaultMiddleware => getDefaultMiddleware().concat(queuesApi.middleware),
})

describe('queuesApi', () => {
  let server: HttpTestServer
  let store: ReturnType<typeof createQueueApiStore>
  const transportFetch = globalThis.fetch
  const transportRequest = globalThis.Request

  beforeEach(async () => {
    localStorage.clear()
    globalThis.fetch = (input, init) => transportFetch(input, { ...init, signal: undefined })
    globalThis.Request = class RequestWithoutCrossRealmSignal extends transportRequest {
      constructor(input: RequestInfo | URL, init?: RequestInit) {
        super(input, { ...init, signal: undefined })
      }
    }
    server = new HttpTestServer()
    const baseUrl = await server.start()
    saveBackendConfig({ apiUrl: baseUrl })
    store = createQueueApiStore()
  })

  afterEach(async () => {
    store.dispatch(queuesApi.util.resetApiState())
    globalThis.fetch = transportFetch
    globalThis.Request = transportRequest
    resetBackendConfig()
    await server.stop()
  })

  it('serializes queue filters and validates the list response', async () => {
    server.setResponder((_request, response) => writeJson(response, {
      queues: [{
        setupId: 'alpha',
        queueName: 'orders',
        type: 'native',
        status: 'active',
        createdAt: 1_700_000_000_000,
      }],
      queueCount: 1,
    }))

    const subscription = store.dispatch(queuesApi.endpoints.getQueues.initiate({
      type: ['native', 'outbox'],
      status: ['active'],
      setupId: 'alpha',
      search: 'order',
      sortBy: 'createdAt',
      sortOrder: 'desc',
      page: 2,
      pageSize: 25,
    }))
    const result = await subscription
    subscription.unsubscribe()

    expect(result.data).toMatchObject({ total: 1, page: 1, pageSize: 1 })
    expect(result.data?.queues[0]).toMatchObject({
      setupId: 'alpha',
      queueName: 'orders',
      messageCount: 0,
      consumerCount: 0,
      updatedAt: '',
    })
    expect(result.data?.queues[0].createdAt).toBe(new Date(1_700_000_000_000).toISOString())
    expect(server.requests[0].url).toBe(
      '/api/v1/management/queues?type=native%2Coutbox&status=active&setupId=alpha&search=order&sortBy=createdAt&sortOrder=desc&page=2&pageSize=25',
    )
  })

  it('maps backend queue details and nested statistics into the UI model', async () => {
    server.setResponder((_request, response) => writeJson(response, {
      setup: 'alpha',
      name: 'orders',
      implementationType: 'outbox',
      status: 'paused',
      messages: 17,
      consumers: 3,
      messageRate: 4.5,
      errorRate: 0.25,
      createdAt: 1_700_000_000_000,
      lastActivity: '2026-08-30T10:00:00Z',
      config: { visibilityTimeoutSeconds: 45, maxRetries: 5, fifoEnabled: true },
      consumersList: [{ id: 'consumer-1' }],
      statistics: {
        totalMessages: 19,
        messagesPerSecond: 6.5,
        activeConsumers: 2,
        avgProcessingTimeMs: 12,
        processingTime: { p50: 8, p95: 20, p99: 30 },
        queueDepth: 11,
        errorRate: 0.1,
      },
    }))

    const subscription = store.dispatch(queuesApi.endpoints.getQueueDetails.initiate({
      setupId: 'alpha',
      queueName: 'orders',
    }))
    const result = await subscription
    subscription.unsubscribe()

    expect(result.data).toMatchObject({
      setupId: 'alpha',
      queueName: 'orders',
      type: 'outbox',
      status: 'paused',
      messageCount: 17,
      consumerCount: 3,
      messagesPerSecond: 4.5,
      updatedAt: '2026-08-30T10:00:00Z',
      config: {
        visibilityTimeoutSeconds: 45,
        maxRetries: 5,
        deadLetterEnabled: true,
        batchSize: 10,
        pollingIntervalSeconds: 5,
        fifoEnabled: true,
        deadLetterQueueName: null,
      },
      statistics: {
        messageCount: 19,
        messagesPerSecond: 6.5,
        consumerCount: 2,
        activeConsumers: 2,
        processingTime: { avg: 12, p50: 8, p95: 20, p99: 30 },
        errorRate: 0.1,
        queueDepth: 11,
      },
    })
    expect(server.requests[0].url).toBe('/api/v1/queues/alpha/orders')
  })

  it('sends queue creation, configuration, publishing, and move request bodies', async () => {
    server.setResponder((_request, response) => writeJson(response, { ok: true }))

    await store.dispatch(queuesApi.endpoints.createQueue.initiate({
      setupId: 'alpha',
      name: 'orders',
      visibilityTimeoutSeconds: 60,
    }))
    await store.dispatch(queuesApi.endpoints.updateQueueConfig.initiate({
      setupId: 'alpha', queueName: 'orders', config: { maxRetries: 7 },
    }))
    await store.dispatch(queuesApi.endpoints.publishMessage.initiate({
      setupId: 'alpha', queueName: 'orders', message: { payload: '{"id":42}' },
    }))
    await store.dispatch(queuesApi.endpoints.moveMessages.initiate({
      setupId: 'alpha', queueName: 'orders',
      request: { targetSetupId: 'beta', targetQueueName: 'archive', messageCount: 10 },
    }))

    expect(server.requests.map(request => ({
      method: request.method,
      url: request.url,
      body: JSON.parse(request.body),
    }))).toEqual([
      {
        method: 'POST', url: '/api/v1/management/queues',
        body: { setup: 'alpha', name: 'orders', type: 'native', visibilityTimeoutSeconds: 60 },
      },
      {
        method: 'PATCH', url: '/api/v1/management/queues/alpha/orders/config',
        body: { maxRetries: 7 },
      },
      {
        method: 'POST', url: '/api/v1/queues/alpha/orders/publish',
        body: { payload: '{"id":42}' },
      },
      {
        method: 'POST', url: '/api/v1/management/queues/alpha/orders/move',
        body: { targetSetupId: 'beta', targetQueueName: 'archive', messageCount: 10 },
      },
    ])
  })

  it('routes every supported queue operation and its conditional delete flags', async () => {
    server.setResponder((_request, response) => writeJson(response, { ok: true }))

    for (const request of [
      { operation: 'PURGE' as const },
      { operation: 'DELETE' as const, options: { ifEmpty: true, ifUnused: true } },
      { operation: 'PAUSE' as const },
      { operation: 'RESUME' as const },
    ]) {
      await store.dispatch(queuesApi.endpoints.performQueueOperation.initiate({
        setupId: 'alpha', queueName: 'orders', request,
      }))
    }

    expect(server.requests.map(request => `${request.method} ${request.url}`)).toEqual([
      'POST /api/v1/queues/alpha/orders/purge',
      'DELETE /api/v1/management/queues/alpha/orders?ifEmpty=true&ifUnused=true',
      'POST /api/v1/queues/alpha/orders/pause',
      'POST /api/v1/queues/alpha/orders/resume',
    ])
  })

  it('encodes message and chart query options', async () => {
    server.setResponder((request, response) => {
      if (request.url.includes('/messages')) {
        writeJson(response, { messages: [], total: 0, hasMore: false })
        return
      }
      writeJson(response, { messageRates: [], queueDepth: [], errorRates: [], processingTimes: [] })
    })

    const messages = store.dispatch(queuesApi.endpoints.getMessages.initiate({
      setupId: 'alpha', queueName: 'orders',
      options: { count: 25, ackMode: 'NO_ACK', offset: 10, filter: 'priority>5' },
    }))
    await messages
    messages.unsubscribe()

    const charts = store.dispatch(queuesApi.endpoints.getQueueChartData.initiate({
      setupId: 'alpha', queueName: 'orders', timeRange: '24h',
    }))
    await charts
    charts.unsubscribe()

    expect(server.requests.map(request => request.url)).toEqual([
      '/api/v1/queues/alpha/orders/messages?count=25&ackMode=NO_ACK&offset=10&filter=priority%3E5',
      '/api/v1/management/queues/alpha/orders/charts?timeRange=24h',
    ])
  })
})
