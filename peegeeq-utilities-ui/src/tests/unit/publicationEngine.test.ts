/**
 * Tests for publicationEngine.ts (§7.1 as respecified 2026-07-18, §13).
 *
 * Contract under test (written FIRST, before the B.0 engine upgrade):
 * - 1-second ticks; the FIRST fan-out fires immediately at start
 * - each tick carries the full per-second quota (rate messages), split into
 *   ceil(rate / maxBatchSize) batches fired concurrently
 * - the caller supplies RunIdentity (runId, correlationId); the engine generates none of the ids
 * - per-batch consecutive-error counting in batch order; any success resets the streak
 * - the in-flight guard covers the whole fan-out: ticks are skipped until it settles
 * - sent counts server-acknowledged messagesSent
 *
 * Uses fake timers to drive the tick loop deterministically. publishService is
 * the one boundary that is mocked: an in-browser tick loop cannot call a real
 * backend under fake timers. Real-backend publishing is covered by the Phase B
 * generator e2e.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPublicationEngine } from '../../engine/publicationEngine'
import type { RunIdentity } from '../../engine/publicationEngine'
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
    payloadSchema: '{"id":"{{messageId}}","run":"{{runId}}"}',
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

const IDENTITY: RunIdentity = { runId: 'run-fixed-1', correlationId: 'corr-fixed-1' }

function callbacks() {
  return { onTick: vi.fn(), onComplete: vi.fn(), onStop: vi.fn(), onError: vi.fn() }
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

  it('a throwing terminal callback is surfaced, never swallowed', async () => {
    // e.g. recordOutcome hits a localStorage quota error inside onComplete.
    // The runTick catch used to see `finished === true` and drop the error —
    // the run's outcome went unrecorded with no report anywhere.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    cbs.onComplete.mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    const engine = createPublicationEngine()

    engine.start(makeConfig({ durationSecs: 1 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(1000)

    expect(cbs.onComplete).toHaveBeenCalled()
    expect(consoleError).toHaveBeenCalledWith('Run terminal callback failed:', expect.any(Error))
  })

  it('fires the first fan-out immediately at start', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 5 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(0)

    // rate 20 / batch 10 → 2 concurrent groups, before any interval elapses.
    expect(mockedPublishBatch).toHaveBeenCalledTimes(2)
    expect(cbs.onTick).toHaveBeenCalled()
    const [sent] = cbs.onTick.mock.calls.at(-1)!
    expect(sent).toBe(20)
  })

  it('splits the per-second quota into batches of at most maxBatchSize', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 25, maxBatchSize: 10, durationSecs: 5 }), IDENTITY, callbacks())
    await vi.advanceTimersByTimeAsync(0)

    expect(mockedPublishBatch).toHaveBeenCalledTimes(3)
    const lengths = mockedPublishBatch.mock.calls.map(([, , req]) => req.messages.length)
    expect(lengths).toEqual([10, 10, 5])
    const first = mockedPublishBatch.mock.calls[0][2].messages[0]
    expect(first.messageType).toBe('order.created')
    expect(first.headers).toEqual({ source: 'generator' })
  })

  it('uses the supplied RunIdentity — generates none of the ids', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 10, maxBatchSize: 10, durationSecs: 1 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(0)

    const message = mockedPublishBatch.mock.calls[0][2].messages[0]
    expect(message.correlationId).toBe('corr-fixed-1')
    expect((message.payload as { run: string }).run).toBe('run-fixed-1')

    await vi.advanceTimersByTimeAsync(1000)
    const summary = cbs.onComplete.mock.calls[0][0] as RunSummary
    expect(summary.runId).toBe('run-fixed-1')
  })

  it('assigns sequential messageIds across the concurrent groups of a tick', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 5 }), IDENTITY, callbacks())
    await vi.advanceTimersByTimeAsync(0)

    const ids = mockedPublishBatch.mock.calls.flatMap(([, , req]) =>
      req.messages.map((m) => (m.payload as { id: string }).id)
    )
    expect(ids).toEqual(
      Array.from({ length: 20 }, (_, i) => String(i + 1).padStart(8, '0'))
    )
  })

  it('transitions to COMPLETED when the duration elapses, having sent every quota', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 2 }), IDENTITY, cbs)
    // Immediate tick (t=0) + tick at t=1000 are productive; tick at t=2000 completes.
    await vi.advanceTimersByTimeAsync(2000)

    expect(cbs.onComplete).toHaveBeenCalledOnce()
    const summary = cbs.onComplete.mock.calls[0][0] as RunSummary
    expect(summary.finalStatus).toBe('completed')
    expect(summary.totalSent).toBe(40)
    expect(summary.targetTotal).toBe(40)
    // 2 productive ticks × 2 groups.
    expect(mockedPublishBatch).toHaveBeenCalledTimes(4)
  })

  it('stop() halts the loop and reports STOPPED', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 10, maxBatchSize: 10, durationSecs: 10 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(0)
    engine.stop()
    await vi.advanceTimersByTimeAsync(3000)

    expect(cbs.onStop).toHaveBeenCalledOnce()
    const summary = cbs.onStop.mock.calls[0][0] as RunSummary
    expect(summary.finalStatus).toBe('stopped')
    expect(mockedPublishBatch).toHaveBeenCalledOnce()
  })

  it('stop during an in-flight fan-out waits for the settle and counts acknowledged sends', async () => {
    // Decision 2026-07-18 (a): the summary is the run's record — stop must not
    // discard server-acknowledged messages from a fan-out that was in flight.
    mockedPublishBatch.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({ messagesSent: 10 }), 1500))
    )
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 10, maxBatchSize: 10, durationSecs: 60 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(0) // fan-out fired, settles at t=1500

    engine.stop()
    // The fan-out has not settled — stop must WAIT, not report an understated summary.
    await vi.advanceTimersByTimeAsync(200)
    expect(cbs.onStop).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1500) // settle
    expect(cbs.onStop).toHaveBeenCalledOnce()
    const summary = cbs.onStop.mock.calls[0][0] as RunSummary
    expect(summary.finalStatus).toBe('stopped')
    expect(summary.totalSent).toBe(10) // the acknowledged in-flight batch is counted

    // No further publishing or ticks after the stop settles.
    await vi.advanceTimersByTimeAsync(3000)
    expect(mockedPublishBatch).toHaveBeenCalledOnce()
    expect(cbs.onStop).toHaveBeenCalledOnce()
  })

  it('skips ticks while the previous fan-out is still in flight', async () => {
    // Each fan-out takes 2.5 s to settle; ticks at t=1000/2000 must be skipped.
    mockedPublishBatch.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({ messagesSent: 10 }), 2500))
    )
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 10, maxBatchSize: 10, durationSecs: 60 }), IDENTITY, callbacks())
    await vi.advanceTimersByTimeAsync(0)
    expect(mockedPublishBatch).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(2400) // t=2400: still in flight → t=1000/2000 skipped
    expect(mockedPublishBatch).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(700) // settles at 2500; next tick t=3000 fires
    expect(mockedPublishBatch).toHaveBeenCalledTimes(2)
  })

  it('auto-stops with ERROR when a single tick accumulates maxConsecErrors failed batches', async () => {
    mockedPublishBatch.mockRejectedValue(
      Object.assign(new Error('boom'), { response: { status: 500 } })
    )
    const cbs = callbacks()
    const engine = createPublicationEngine()

    // rate 20 / batch 10 → 2 groups per tick; both fail → streak reaches 2 in one tick.
    engine.start(
      makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 10, maxConsecErrors: 2 }),
      IDENTITY,
      cbs
    )
    await vi.advanceTimersByTimeAsync(0)

    expect(cbs.onError).toHaveBeenCalledOnce()
    const [summary, reason] = cbs.onError.mock.calls[0] as [RunSummary, string]
    expect(summary.finalStatus).toBe('error')
    expect(reason).toContain('Auto-stopped')
    expect((summary.errors as PublishError[]).length).toBe(2)
  })

  it('a successful batch resets the consecutive-error streak within a tick', async () => {
    // Batch order per tick: first rejects, second resolves → streak never reaches 2.
    let call = 0
    mockedPublishBatch.mockImplementation(() =>
      call++ % 2 === 0
        ? Promise.reject(new Error('boom'))
        : Promise.resolve({ messagesSent: 10 })
    )
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(
      makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 2, maxConsecErrors: 2 }),
      IDENTITY,
      cbs
    )
    await vi.advanceTimersByTimeAsync(2000)

    expect(cbs.onError).not.toHaveBeenCalled()
    expect(cbs.onComplete).toHaveBeenCalledOnce()
    const summary = cbs.onComplete.mock.calls[0][0] as RunSummary
    // 2 productive ticks: one failed + one acknowledged batch each.
    expect(summary.totalSent).toBe(20)
    expect(summary.totalErrors).toBe(2)
  })

  it('resolves placeholders in header values per message (§5.3)', async () => {
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const engine = createPublicationEngine()
    const template = {
      ...makeTemplate(),
      headers: { trace: '{{uuid}}', run: '{{runId}}', fixed: 'constant' },
    }

    engine.start(
      makeConfig({ rate: 2, maxBatchSize: 10, durationSecs: 5, template }),
      IDENTITY,
      callbacks()
    )
    await vi.advanceTimersByTimeAsync(0)

    const messages = mockedPublishBatch.mock.calls[0][2].messages
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
    expect(messages[0].headers!.trace).toMatch(uuidPattern)
    expect(messages[1].headers!.trace).toMatch(uuidPattern)
    // Per-message resolution: each message gets its own {{uuid}}.
    expect(messages[0].headers!.trace).not.toBe(messages[1].headers!.trace)
    expect(messages[0].headers!.run).toBe('run-fixed-1')
    expect(messages[0].headers!.fixed).toBe('constant')
  })

  it('a template that fails to resolve at run time transitions to ERROR — never hangs', async () => {
    // resolveTemplate throws (JSON.parse) inside the tick. The engine must
    // surface this through onError, not swallow it as an unhandled rejection
    // leaving the run stuck in RUNNING (the fire-and-forget failure mode).
    mockedPublishBatch.mockResolvedValue({ messagesSent: 10 })
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(
      makeConfig({
        rate: 5,
        durationSecs: 5,
        maxBatchSize: 10,
        template: { ...makeTemplate(), payloadSchema: '{"broken": }' },
      }),
      IDENTITY,
      cbs
    )
    await vi.advanceTimersByTimeAsync(0)

    expect(cbs.onError).toHaveBeenCalledOnce()
    const [summary, reason] = cbs.onError.mock.calls[0] as [RunSummary, string]
    expect(summary.finalStatus).toBe('error')
    expect(reason).toMatch(/template/i)
    // Nothing was published and the loop is dead — no further ticks fire.
    expect(mockedPublishBatch).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(3000)
    expect(cbs.onError).toHaveBeenCalledOnce()
    expect(cbs.onTick).not.toHaveBeenCalled()
  })

  it('does not auto-stop when maxConsecErrors is 0 (disabled)', async () => {
    mockedPublishBatch.mockRejectedValue(new Error('boom'))
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(
      makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 1, maxConsecErrors: 0 }),
      IDENTITY,
      cbs
    )
    await vi.advanceTimersByTimeAsync(1000)

    expect(cbs.onError).not.toHaveBeenCalled()
    expect(cbs.onComplete).toHaveBeenCalledOnce()
  })

  it('records the first messageId of the failed batch as the error messageIndex', async () => {
    // First group succeeds, second group (ids 11–20) fails.
    let call = 0
    mockedPublishBatch.mockImplementation(() =>
      call++ === 0
        ? Promise.resolve({ messagesSent: 10 })
        : Promise.reject(Object.assign(new Error('boom'), { response: { status: 503 } }))
    )
    const cbs = callbacks()
    const engine = createPublicationEngine()

    engine.start(makeConfig({ rate: 20, maxBatchSize: 10, durationSecs: 1 }), IDENTITY, cbs)
    await vi.advanceTimersByTimeAsync(1000)

    const summary = cbs.onComplete.mock.calls[0][0] as RunSummary
    expect(summary.totalErrors).toBe(1)
    const error = (summary.errors as PublishError[])[0]
    expect(error.messageIndex).toBe(11)
    expect(error.httpStatus).toBe(503)
  })
})
