/**
 * Tests for publicationEngine.ts (§7, §13 of the feature design).
 *
 * Uses fake timers to drive the setInterval tick loop deterministically and
 * mocks the publishService network boundary.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPublicationEngine } from '../../engine/publicationEngine'
import { publishBatch } from '../../services/publishService'
import type { RunConfig, MessageTemplate, RunSummary, PublishError } from '../../types/generator'

vi.mock('../../services/publishService')
const mockedPublishBatch = vi.mocked(publishBatch)

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1',
    name: 'T',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: { source: 'generator' },
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1',
    queueName: 'orders',
    rate: 20,
    durationSecs: 1,
    maxBatchSize: 10,
    warnThreshold: 0,
    maxConsecErrors: 0,
    template: makeTemplate(),
    previewIndex: 1,
    ...overrides,
  }
}

describe('publicationEngine', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('publishes batches on each tick and reports progress via onTick', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const onTick = vi.fn()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, durationSecs: 1, maxBatchSize: 10 }), {
      onTick,
      onComplete: vi.fn(),
      onStop: vi.fn(),
      onError: vi.fn(),
    })

    // tickMs = (10/20)*1000 = 500ms; one productive tick before completion at 1000ms.
    await vi.advanceTimersByTimeAsync(500)

    expect(mockedPublishBatch).toHaveBeenCalledOnce()
    expect(onTick).toHaveBeenCalled()
    const [sent] = onTick.mock.calls.at(-1)!
    expect(sent).toBe(10)
  })

  it('builds a batch of size min(maxBatchSize, floor(rate)) per tick', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, durationSecs: 1, maxBatchSize: 10 }), {
      onTick: vi.fn(),
      onComplete: vi.fn(),
      onStop: vi.fn(),
      onError: vi.fn(),
    })

    await vi.advanceTimersByTimeAsync(500)

    const [, , batchReq] = mockedPublishBatch.mock.calls[0]
    expect(batchReq.messages).toHaveLength(10)
    expect(batchReq.messages[0].messageType).toBe('order.created')
    expect(batchReq.messages[0].headers).toEqual({ source: 'generator' })
  })

  it('transitions to COMPLETED when the duration elapses', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const onComplete = vi.fn()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, durationSecs: 1, maxBatchSize: 10 }), {
      onTick: vi.fn(),
      onComplete,
      onStop: vi.fn(),
      onError: vi.fn(),
    })

    await vi.advanceTimersByTimeAsync(1000)

    expect(onComplete).toHaveBeenCalledOnce()
    const summary = onComplete.mock.calls[0][0] as RunSummary
    expect(summary.finalStatus).toBe('completed')
    expect(summary.totalSent).toBe(10)
    expect(summary.targetTotal).toBe(20)
  })

  it('stop() halts the loop and reports STOPPED', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const onStop = vi.fn()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, durationSecs: 10, maxBatchSize: 10 }), {
      onTick: vi.fn(),
      onComplete: vi.fn(),
      onStop,
      onError: vi.fn(),
    })

    await vi.advanceTimersByTimeAsync(500)
    engine.stop()
    await vi.advanceTimersByTimeAsync(2000)

    expect(onStop).toHaveBeenCalledOnce()
    const summary = onStop.mock.calls[0][0] as RunSummary
    expect(summary.finalStatus).toBe('stopped')
    // No further publishing after stop.
    expect(mockedPublishBatch).toHaveBeenCalledOnce()
  })

  it('auto-stops with ERROR after maxConsecErrors consecutive failures', async () => {
    mockedPublishBatch.mockRejectedValue(
      Object.assign(new Error('boom'), { response: { status: 500 } })
    )
    const onError = vi.fn()
    const engine = createPublicationEngine()

    engine.start(
      makeConfig({ rate: 20, durationSecs: 10, maxBatchSize: 10, maxConsecErrors: 2 }),
      {
        onTick: vi.fn(),
        onComplete: vi.fn(),
        onStop: vi.fn(),
        onError,
      }
    )

    await vi.advanceTimersByTimeAsync(1000)

    expect(onError).toHaveBeenCalledOnce()
    const [summary, reason] = onError.mock.calls[0] as [RunSummary, string]
    expect(summary.finalStatus).toBe('error')
    expect(reason).toContain('Auto-stopped')
    expect((summary.errors as PublishError[]).length).toBeGreaterThanOrEqual(2)
  })

  it('does not auto-stop when maxConsecErrors is 0 (disabled)', async () => {
    mockedPublishBatch.mockRejectedValue(new Error('boom'))
    const onError = vi.fn()
    const onComplete = vi.fn()
    const engine = createPublicationEngine()

    engine.start(
      makeConfig({ rate: 20, durationSecs: 1, maxBatchSize: 10, maxConsecErrors: 0 }),
      {
        onTick: vi.fn(),
        onComplete,
        onStop: vi.fn(),
        onError,
      }
    )

    await vi.advanceTimersByTimeAsync(1000)

    expect(onError).not.toHaveBeenCalled()
    expect(onComplete).toHaveBeenCalledOnce()
  })
})
