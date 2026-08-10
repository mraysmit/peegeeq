/**
 * Tests for comparisonRunner.ts (design §19.2 — Phase G.2a).
 *
 * Contract under test (written before the runner):
 * - an invalid target pair is refused before any engine starts, with the reason
 * - baseline telemetry is captured BEFORE either side publishes; a baseline
 *   taken after the run would understate every churn delta
 * - BOTH sides start concurrently — §19.2 is "identical load at the same time",
 *   so neither side waits for the other
 * - each side runs the SHARED rate/duration/template at its OWN target, under
 *   its OWN run identity
 * - the generatorStore is never written: it holds ONE RunState and cannot
 *   represent two concurrent runs, so merging them into it would report a run
 *   that never happened
 * - the report is built only after BOTH sides settle, and carries each side's
 *   real terminal status rather than a merged one
 * - a side ending in ERROR does NOT stop the other side: the sides are
 *   concurrent, and cutting the healthy one short would turn its real figures
 *   into a partial run
 * - db-telemetry is read ONCE per setup — two sides in one setup share a schema
 * - a failed telemetry read does not abort the run and lands in the report as
 *   its stated reason
 * - stop() stops both sides; stopping before the engines start prevents them
 *   starting and says so
 *
 * Added for G.2e — the churn settle poll:
 * - the final db sample is re-read until each side's insert delta reaches the
 *   rows that side acknowledged; a single read races PostgreSQL's per-backend
 *   statistics flush and can report one side's inserts as zero
 * - agreement between two consecutive samples is NOT the settle condition: two
 *   reads of a backend that has not flushed agree with each other
 * - when the counters never reconcile, the final read is recorded as unusable,
 *   so the churn renders as unknown rather than as zero
 * - a run that acknowledged nothing, and a failed baseline, are not polled at
 *   all: neither can ever reconcile
 *
 * No module mocking: the engine factory, the telemetry captures and the run-id
 * source are injected, and these tests pass real fakes (the profileRunner
 * pattern).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  CHURN_SETTLE_DEADLINE_MS,
  CHURN_SETTLE_INTERVAL_MS,
  createComparisonRunner,
} from '../../engine/comparisonRunner'
import { useGeneratorStore } from '../../stores/generatorStore'
import type {
  EngineCallbacks,
  PublicationEngine,
  RunIdentity,
} from '../../engine/publicationEngine'
import type { MessageTemplate, RunConfig, RunStatus, RunSummary } from '../../types/generator'
import type {
  CompareSettings,
  CompareTarget,
  DbTableStats,
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TelemetryCapture,
} from '../../types/compare'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1',
    name: 'T',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

/** The shared load. setupId/queueName are placeholders each side overwrites. */
function makeBase(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1',
    queueName: 'orders',
    rate: 200,
    durationSecs: 60,
    maxBatchSize: 10,
    warnThreshold: 0,
    maxConsecErrors: 3,
    template: makeTemplate(),
    previewIndex: 1,
    ...overrides,
  }
}

function target(overrides: Partial<CompareTarget> = {}): CompareTarget {
  return { setupId: 's1', queueName: 'orders', implementationType: 'native', ...overrides }
}

function makeSettings(overrides: Partial<CompareSettings> = {}): CompareSettings {
  return {
    native: target({ queueName: 'orders', implementationType: 'native' }),
    outbox: target({ queueName: 'events', implementationType: 'outbox' }),
    ...overrides,
  }
}

function makeSummary(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    totalSent: 12000,
    totalAttempted: 12000,
    targetTotal: 12000,
    avgRate: 200,
    durationMs: 60000,
    totalErrors: 0,
    finalStatus: 'completed' as RunStatus,
    runId: 'r1',
    errors: [],
    ...overrides,
  }
}

function statsSnapshot(queueName: string): QueueStatsSnapshot {
  return {
    queueName,
    setupId: 's1',
    implementationType: 'native',
    healthy: true,
    totalMessages: 0,
    pendingMessages: 0,
    inFlightMessages: 0,
    processedMessages: 0,
    deadLetteredMessages: 0,
    messagesPerSecond: 0,
    avgProcessingTimeMs: 0,
    successRatePercent: 100,
    timestamp: 1,
  }
}

function dbSnapshot(setupId: string): DbTelemetrySnapshot {
  return {
    setupId,
    databaseName: `${setupId}_db`,
    schema: 'public',
    sampledAt: 1,
    tables: [],
    cluster: {
      backendsHoldingXmin: 0,
      locksTotal: 0,
      locksWaiting: 0,
      xidAge: 0,
      notifyQueueUsage: 0,
      walRecords: 0,
      walBytes: 0,
      walLsnBytes: 0,
      checkpointsTimed: 0,
      checkpointsRequested: 0,
      buffersCheckpoint: 0,
      xactCommit: 0,
      xactRollback: 0,
      deadlocks: 0,
      tupReturned: 0,
      tupFetched: 0,
      numbackends: 1,
      blksHit: 0,
      blksRead: 0,
    },
  }
}

function tableStats(tableName: string, nTupIns: number): DbTableStats {
  return {
    tableName,
    nTupIns,
    nTupUpd: 0,
    nTupDel: 0,
    nTupHotUpd: 0,
    nLiveTup: 0,
    nDeadTup: 0,
    seqScan: 0,
    idxScan: 0,
    vacuumCount: 0,
    autovacuumCount: 0,
    heapBlksHit: 0,
    heapBlksRead: 0,
    heapBytes: 0,
    indexBytes: 0,
    totalBytes: 0,
  }
}

/** A db snapshot carrying the two churn-bearing tables at stated insert counts. */
function dbSnapshotWith(
  setupId: string,
  queueMessagesIns: number,
  outboxIns: number
): TelemetryCapture<DbTelemetrySnapshot> {
  return {
    ok: true,
    snapshot: {
      ...dbSnapshot(setupId),
      tables: [
        tableStats('queue_messages', queueMessagesIns),
        tableStats('outbox', outboxIns),
      ],
    },
  }
}

/**
 * A db-telemetry reader that walks a scripted list of captures, repeating the
 * last one once the script runs out.
 *
 * The script is how a stats-flush lag is expressed: the same query returns a
 * counter that is right for one table and stale for the other, then catches up
 * on a later read. A single fixed snapshot cannot express that at all, which is
 * why the existing fake could not have caught this.
 */
function scriptedDb(script: Array<TelemetryCapture<DbTelemetrySnapshot>>) {
  const calls: string[] = []
  return {
    calls,
    captureDb: async (setupId: string): Promise<TelemetryCapture<DbTelemetrySnapshot>> => {
      const capture = script[Math.min(calls.length, script.length - 1)]
      calls.push(setupId)
      return capture
    },
  }
}

/**
 * A fake engine factory: records every start and hands back control so the
 * test decides when — and how — each side terminates.
 *
 * `order` is a log shared with {@link fakeTelemetry}. Asserting that both a
 * baseline read and an engine start HAPPENED proves nothing about which came
 * first, so the ordering test needs one sequence covering both.
 */
function fakeEngines(order: string[] = []) {
  const starts: Array<{
    config: RunConfig
    identity: RunIdentity
    callbacks: EngineCallbacks
  }> = []
  let stopped = 0
  const createEngine = (): PublicationEngine => ({
    start(config, identity, callbacks) {
      order.push(`engine-start:${config.queueName}`)
      starts.push({ config, identity, callbacks })
    },
    stop() {
      stopped++
    },
  })
  function callbacksFor(queueName: string): EngineCallbacks {
    const entry = starts.find((s) => s.config.queueName === queueName)
    if (!entry) throw new Error(`No engine was started for queue "${queueName}"`)
    return entry.callbacks
  }
  return {
    createEngine,
    starts,
    stopCount: () => stopped,
    queueNames: () => starts.map((s) => s.config.queueName),
    complete(queueName: string, summary = makeSummary()) {
      callbacksFor(queueName).onComplete({ ...summary, finalStatus: 'completed' })
    },
    stop(queueName: string, summary = makeSummary()) {
      callbacksFor(queueName).onStop({ ...summary, finalStatus: 'stopped' })
    },
    fail(queueName: string, reason: string, summary = makeSummary()) {
      callbacksFor(queueName).onError({ ...summary, finalStatus: 'error' }, reason)
    },
    tick(queueName: string, sent: number, errorCount: number, elapsedMs: number) {
      const errors = Array.from({ length: errorCount }, (_, i) => ({
        messageIndex: i,
        message: 'boom',
        timestamp: new Date().toISOString(),
      }))
      callbacksFor(queueName).onTick(sent, errors, errorCount, elapsedMs)
    },
  }
}

/**
 * Telemetry fakes that record every call, so read counts can be asserted.
 * `order` is the sequence log shared with {@link fakeEngines}.
 */
function fakeTelemetry(order: string[] = []) {
  const statsCalls: Array<[string, string]> = []
  const dbCalls: string[] = []
  return {
    statsCalls,
    dbCalls,
    captureStats: async (
      setupId: string,
      queueName: string
    ): Promise<TelemetryCapture<QueueStatsSnapshot>> => {
      order.push(`stats:${setupId}/${queueName}`)
      statsCalls.push([setupId, queueName])
      return { ok: true, snapshot: statsSnapshot(queueName) }
    },
    captureDb: async (setupId: string): Promise<TelemetryCapture<DbTelemetrySnapshot>> => {
      order.push(`db:${setupId}`)
      dbCalls.push(setupId)
      return { ok: true, snapshot: dbSnapshot(setupId) }
    },
  }
}

let idCounter = 0
const nextId = () => `id-${++idCounter}`

describe('comparisonRunner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    idCounter = 0
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('refuses an invalid target pair before starting any engine, with the reason', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareAborted = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    const handle = runner.start(
      makeBase(),
      makeSettings({ outbox: target({ queueName: 'orders2', implementationType: 'native' }) }),
      { onCompareAborted }
    )
    await vi.advanceTimersByTimeAsync(0)

    expect(handle).toBeNull()
    expect(engines.starts).toHaveLength(0)
    expect(telemetry.statsCalls).toHaveLength(0)
    expect(onCompareAborted).toHaveBeenCalledTimes(1)
    expect(onCompareAborted.mock.calls[0][0]).toMatch(/outbox/i)
  })

  it('captures baseline telemetry BEFORE either side publishes', async () => {
    // One sequence across both fakes. Asserting only that the reads and the
    // starts both HAPPENED passes just as well when the order is reversed —
    // which is the whole thing under test, since a baseline taken after the
    // run would understate every churn delta.
    const order: string[] = []
    const engines = fakeEngines(order)
    const telemetry = fakeTelemetry(order)
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), {})
    expect(engines.starts).toHaveLength(0) // nothing publishes synchronously

    await vi.advanceTimersByTimeAsync(0)

    const firstStart = order.findIndex((event) => event.startsWith('engine-start:'))
    expect(firstStart).toBeGreaterThan(-1)
    // EVERY baseline read lands before the first engine start.
    expect(order.slice(0, firstStart)).toEqual([
      'stats:s1/orders',
      'stats:s1/events',
      'db:s1',
    ])
    expect(order.slice(firstStart)).toEqual([
      'engine-start:orders',
      'engine-start:events',
    ])
  })

  it('starts BOTH sides concurrently, neither waiting for the other', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), {})
    await vi.advanceTimersByTimeAsync(0)

    // Both live at once — the profileRunner's one-at-a-time rule is exactly
    // what a comparison must NOT do.
    expect(engines.queueNames()).toEqual(['orders', 'events'])
  })

  it('runs each side with the shared load at its own target', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const base = makeBase({ rate: 200, durationSecs: 60 })
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(base, makeSettings(), {})
    await vi.advanceTimersByTimeAsync(0)

    for (const start of engines.starts) {
      expect(start.config.rate).toBe(200)
      expect(start.config.durationSecs).toBe(60)
      expect(start.config.maxConsecErrors).toBe(3)
      expect(start.config.template).toEqual(base.template)
    }
    expect(engines.starts[0].config.queueName).toBe('orders')
    expect(engines.starts[1].config.queueName).toBe('events')
  })

  it('gives each side its own run identity', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), {})
    await vi.advanceTimersByTimeAsync(0)

    const [first, second] = engines.starts
    expect(first.identity.runId).not.toBe(second.identity.runId)
    expect(first.identity.correlationId).not.toBe(first.identity.runId)
  })

  it('never writes the generator store — it cannot represent two runs at once', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), {})
    await vi.advanceTimersByTimeAsync(0)
    engines.tick('orders', 500, 0, 2500)

    // Writing either side into the single RunState would report a merged run
    // that neither side performed.
    expect(useGeneratorStore.getState().runState.status).toBe('idle')
    expect(useGeneratorStore.getState().runState.sent).toBe(0)
    expect(useGeneratorStore.getState().config).toBeNull()
  })

  it('reports per-side progress while both sides are live', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onSideProgress = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onSideProgress })
    await vi.advanceTimersByTimeAsync(0)

    engines.tick('orders', 400, 0, 2000)
    engines.tick('events', 380, 2, 2000)

    expect(onSideProgress).toHaveBeenCalledTimes(2)
    expect(onSideProgress.mock.calls[0][0]).toEqual({
      side: 'native',
      sent: 400,
      errors: 0,
      elapsedMs: 2000,
    })
    expect(onSideProgress.mock.calls[1][0]).toEqual({
      side: 'outbox',
      sent: 380,
      errors: 2,
      elapsedMs: 2000,
    })
  })

  it('builds the report only after BOTH sides settle', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    engines.complete('orders', makeSummary({ totalSent: 12000 }))
    await vi.advanceTimersByTimeAsync(0)
    expect(onCompareComplete).not.toHaveBeenCalled()

    engines.complete('events', makeSummary({ totalSent: 11400, avgRate: 190 }))
    await vi.advanceTimersByTimeAsync(0)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    const report = onCompareComplete.mock.calls[0][0]
    expect(report.native.summary.totalSent).toBe(12000)
    expect(report.outbox.summary.totalSent).toBe(11400)
    expect(report.native.target.queueName).toBe('orders')
    expect(report.outbox.target.queueName).toBe('events')
  })

  it('reports each side settling as it happens', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onSideSettled = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onSideSettled })
    await vi.advanceTimersByTimeAsync(0)

    engines.complete('orders')
    expect(onSideSettled).toHaveBeenCalledTimes(1)
    expect(onSideSettled.mock.calls[0][0].side).toBe('native')
  })

  it('does NOT stop the healthy side when the other errors', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    engines.fail('events', 'Auto-stopped: 3 consecutive errors. Last: 503')
    await vi.advanceTimersByTimeAsync(0)

    // Cutting the healthy side short would make its figures a partial run —
    // worse for the comparison than letting it finish and refusing a verdict.
    expect(engines.stopCount()).toBe(0)
    expect(onCompareComplete).not.toHaveBeenCalled()

    engines.complete('orders')
    await vi.advanceTimersByTimeAsync(0)

    const report = onCompareComplete.mock.calls[0][0]
    expect(report.outbox.summary.finalStatus).toBe('error')
    expect(report.outbox.errorReason).toMatch(/Auto-stopped/)
    expect(report.native.summary.finalStatus).toBe('completed')
  })

  it('captures final telemetry after both sides settle', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    expect(telemetry.statsCalls).toHaveLength(2) // baseline only

    engines.complete('orders')
    engines.complete('events')
    await vi.advanceTimersByTimeAsync(0)

    expect(telemetry.statsCalls).toHaveLength(4) // baseline + final, per side
    const report = onCompareComplete.mock.calls[0][0]
    expect(report.telemetry.stats.native.baseline.ok).toBe(true)
    expect(report.telemetry.stats.native.final.ok).toBe(true)
  })

  it('reads db-telemetry once per SETUP, not once per side', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), {})
    await vi.advanceTimersByTimeAsync(0)

    // Both sides live in s1, and db-telemetry covers a whole schema.
    expect(telemetry.dbCalls).toEqual(['s1'])
  })

  it('reads db-telemetry for each setup when the sides are in different setups', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(
      makeBase(),
      makeSettings({ outbox: target({ setupId: 's2', queueName: 'events', implementationType: 'outbox' }) }),
      { onCompareComplete }
    )
    await vi.advanceTimersByTimeAsync(0)
    expect(telemetry.dbCalls).toEqual(['s1', 's2'])

    engines.complete('orders')
    engines.complete('events')
    await vi.advanceTimersByTimeAsync(0)

    const report = onCompareComplete.mock.calls[0][0]
    expect(Object.keys(report.telemetry.db).sort()).toEqual(['s1', 's2'])
  })

  it('a failed telemetry read does not abort the run and lands in the report as its reason', async () => {
    const engines = fakeEngines()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: async () => ({ ok: false, error: 'stats unavailable: 503' }),
      captureDb: async () => ({ ok: false, error: 'db telemetry unavailable: 503' }),
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    // The run still publishes: telemetry is measurement, not a precondition.
    expect(engines.starts).toHaveLength(2)

    engines.complete('orders')
    engines.complete('events')
    await vi.advanceTimersByTimeAsync(0)

    const report = onCompareComplete.mock.calls[0][0]
    expect(report.telemetry.stats.native.baseline.ok).toBe(false)
    expect(report.telemetry.stats.native.baseline.error).toMatch(/503/)
    expect(report.telemetry.db.s1.final.ok).toBe(false)
  })

  it('stop() stops both sides', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    const handle = runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    handle!.stop()
    expect(engines.stopCount()).toBe(2)

    engines.stop('orders', makeSummary({ totalSent: 900 }))
    engines.stop('events', makeSummary({ totalSent: 850 }))
    await vi.advanceTimersByTimeAsync(0)

    // A stopped comparison still carries real figures; the verdict is what
    // refuses to name a winner, not the report.
    const report = onCompareComplete.mock.calls[0][0]
    expect(report.native.summary.finalStatus).toBe('stopped')
    expect(report.outbox.summary.finalStatus).toBe('stopped')
  })

  it('stop() before the engines start prevents them starting and says so', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareAborted = vi.fn()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    const handle = runner.start(makeBase(), makeSettings(), {
      onCompareAborted,
      onCompareComplete,
    })
    handle!.stop() // while the baseline reads are still in flight
    await vi.advanceTimersByTimeAsync(0)

    expect(engines.starts).toHaveLength(0)
    expect(onCompareComplete).not.toHaveBeenCalled()
    expect(onCompareAborted).toHaveBeenCalledTimes(1)
    expect(onCompareAborted.mock.calls[0][0]).toMatch(/stopped/i)
  })

  it('a side whose engine throws on start settles as error, leaving the other running', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    // The outbox engine refuses synchronously — the shape of a value-list
    // snapshot failure inside engine.start (the runStarter precedent).
    let created = 0
    const runner = createComparisonRunner({
      createEngine: () => {
        created++
        if (created === 2) {
          return {
            start() {
              throw new Error('storage exploded')
            },
            stop() {},
          }
        }
        return engines.createEngine()
      },
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    // The native side is publishing real load and is not cut short.
    expect(engines.queueNames()).toEqual(['orders'])
    expect(onCompareComplete).not.toHaveBeenCalled()

    engines.complete('orders')
    await vi.advanceTimersByTimeAsync(0)

    const report = onCompareComplete.mock.calls[0][0]
    expect(report.outbox.summary.finalStatus).toBe('error')
    expect(report.outbox.summary.totalSent).toBe(0)
    expect(report.outbox.errorReason).toMatch(/failed to start/i)
    expect(report.native.summary.finalStatus).toBe('completed')
  })

  // ── churn settle poll (G.2e) ──────────────────────────────────────────────
  //
  // PostgreSQL does not make a committed INSERT visible in pg_stat_user_tables
  // at commit: each backend accumulates its counters and flushes them on a rate
  // limit. The native and outbox publish paths commit on DIFFERENT connections,
  // so a single final sample can read one side correctly and the other as zero.
  // "10 acknowledged, 0 rows inserted" is what that looks like on screen, and it
  // is the defect these tests exist to prevent.

  it('keeps re-reading the final db sample until the counters reflect the acknowledged sends', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const db = scriptedDb([
      dbSnapshotWith('s1', 0, 0), // baseline
      dbSnapshotWith('s1', 10, 0), // final #1 — the outbox backend has not flushed
      dbSnapshotWith('s1', 10, 10), // final #2 — it has now
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.complete('orders', makeSummary({ totalSent: 10 }))
    engines.complete('events', makeSummary({ totalSent: 10 }))
    await vi.advanceTimersByTimeAsync(0)

    // The first final read is short by ten rows on the outbox side, so the
    // report is not built on it.
    expect(onCompareComplete).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(CHURN_SETTLE_INTERVAL_MS)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    expect(db.calls).toEqual(['s1', 's1', 's1'])
    const report = onCompareComplete.mock.calls[0][0]
    expect(report.telemetry.db.s1.final.ok).toBe(true)
    const tables = report.telemetry.db.s1.final.snapshot.tables
    expect(tables.find((t: DbTableStats) => t.tableName === 'outbox').nTupIns).toBe(10)

    // Only the db sample is re-read. Re-reading /stats would move the latency
    // sample delta after the run had already ended, crediting this run with
    // measurements taken after it finished.
    expect(telemetry.statsCalls).toHaveLength(4)
  })

  it('does NOT stop polling because two consecutive samples agree', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const db = scriptedDb([
      dbSnapshotWith('s1', 0, 0), // baseline
      dbSnapshotWith('s1', 10, 0), // final #1
      dbSnapshotWith('s1', 10, 0), // final #2 — identical, and still wrong
      dbSnapshotWith('s1', 10, 10), // final #3 — the flush lands
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.complete('orders', makeSummary({ totalSent: 10 }))
    engines.complete('events', makeSummary({ totalSent: 10 }))
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(CHURN_SETTLE_INTERVAL_MS)

    // Two reads of a backend that has not flushed return the same number and
    // agree with each other. Stability is not completeness — stopping here
    // reports the zero it was meant to fix.
    expect(onCompareComplete).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(CHURN_SETTLE_INTERVAL_MS)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    const report = onCompareComplete.mock.calls[0][0]
    const tables = report.telemetry.db.s1.final.snapshot.tables
    expect(tables.find((t: DbTableStats) => t.tableName === 'outbox').nTupIns).toBe(10)
  })

  it('reports the churn as UNAVAILABLE, never zero, when the counters never reconcile', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const db = scriptedDb([
      dbSnapshotWith('s1', 0, 0), // baseline
      dbSnapshotWith('s1', 10, 0), // every final read, forever
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.complete('orders', makeSummary({ totalSent: 10 }))
    engines.complete('events', makeSummary({ totalSent: 10 }))
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(CHURN_SETTLE_DEADLINE_MS + CHURN_SETTLE_INTERVAL_MS)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    const report = onCompareComplete.mock.calls[0][0]
    // Handing back the unreconciled snapshot would put "0 rows inserted" next to
    // "10 acknowledged" on screen. The read is recorded as unusable instead, so
    // churnDeltaFor yields null and the panel renders it as unknown.
    expect(report.telemetry.db.s1.final.ok).toBe(false)
    expect(report.telemetry.db.s1.final.error).toMatch(/acknowledg/i)
    expect(report.telemetry.db.s1.final.error).toContain('s1')

    // Bounded: it gives up at the deadline rather than polling for ever.
    const maxCalls = 2 + Math.ceil(CHURN_SETTLE_DEADLINE_MS / CHURN_SETTLE_INTERVAL_MS)
    expect(db.calls.length).toBeLessThanOrEqual(maxCalls)
  })

  it('does not poll at all when neither side acknowledged anything', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    // The final read FAILS: that is the only way a run owed no rows could end
    // up waiting, so it is the case that proves the guard is doing something.
    const db = scriptedDb([
      dbSnapshotWith('s1', 4, 4), // baseline
      { ok: false, error: 'db telemetry unavailable: 503' }, // final
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.stop('orders', makeSummary({ totalSent: 0 }))
    engines.stop('events', makeSummary({ totalSent: 0 }))
    await vi.advanceTimersByTimeAsync(0)

    // Nothing was sent, so no rows are owed and there is nothing to wait for —
    // not even a read that could be retried.
    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    expect(db.calls).toEqual(['s1', 's1'])
    // The read's own reason survives, unwrapped: it never entered the settle
    // path, so nothing "failed to settle".
    const report = onCompareComplete.mock.calls[0][0]
    expect(report.telemetry.db.s1.final.error).toBe('db telemetry unavailable: 503')
  })

  it('does not poll when the BASELINE read failed — the delta can never be computed', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const db = scriptedDb([
      { ok: false, error: 'baseline unavailable: 503' }, // baseline
      { ok: false, error: 'db telemetry unavailable: 503' }, // final
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.complete('orders', makeSummary({ totalSent: 10 }))
    engines.complete('events', makeSummary({ totalSent: 10 }))
    await vi.advanceTimersByTimeAsync(0)

    // With no baseline there is no delta to reconcile, so waiting for one would
    // burn the whole deadline to reach the same answer.
    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    expect(db.calls).toEqual(['s1', 's1'])
  })

  it('retries a FAILING final db read and lands its reason at the deadline', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const db = scriptedDb([
      dbSnapshotWith('s1', 0, 0), // baseline
      { ok: false, error: 'db telemetry unavailable: 503' }, // every final read
    ])
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: db.captureDb,
      newRunId: nextId,
    })

    runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)
    engines.complete('orders', makeSummary({ totalSent: 10 }))
    engines.complete('events', makeSummary({ totalSent: 10 }))
    await vi.advanceTimersByTimeAsync(0)
    expect(onCompareComplete).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(CHURN_SETTLE_DEADLINE_MS + CHURN_SETTLE_INTERVAL_MS)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
    expect(db.calls.length).toBeGreaterThan(2)
    const report = onCompareComplete.mock.calls[0][0]
    // The read's own reason survives: "503" is a more actionable statement than
    // "the counters did not reconcile".
    expect(report.telemetry.db.s1.final.ok).toBe(false)
    expect(report.telemetry.db.s1.final.error).toMatch(/503/)
  })

  it('stop() is idempotent and never settles the comparison twice', async () => {
    const engines = fakeEngines()
    const telemetry = fakeTelemetry()
    const onCompareComplete = vi.fn()
    const runner = createComparisonRunner({
      createEngine: engines.createEngine,
      captureStats: telemetry.captureStats,
      captureDb: telemetry.captureDb,
      newRunId: nextId,
    })

    const handle = runner.start(makeBase(), makeSettings(), { onCompareComplete })
    await vi.advanceTimersByTimeAsync(0)

    handle!.stop()
    handle!.stop()
    engines.stop('orders')
    engines.stop('events')
    await vi.advanceTimersByTimeAsync(0)
    handle!.stop()
    await vi.advanceTimersByTimeAsync(0)

    expect(onCompareComplete).toHaveBeenCalledTimes(1)
  })
})
