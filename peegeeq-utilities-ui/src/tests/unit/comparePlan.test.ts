/**
 * Tests for comparePlan.ts (design §19.2 — Phase G.2a).
 *
 * Contract under test (written before the module):
 * - a "native vs outbox" run whose two targets are the SAME implementation
 *   type is refused with a reason: it measures nothing it claims to measure
 * - a target whose type the backend did not report is NOT treated as a
 *   mismatch, and NOT silently accepted either — it is named in a warning
 * - db-telemetry counters are cumulative, so the churn a run caused is the
 *   DELTA between the baseline and the final snapshot; a table missing from
 *   either snapshot yields null, never a fabricated zero delta
 * - `nDeadTup`/`nLiveTup` are gauges: their delta is a level change and may be
 *   negative when autovacuum kept up
 * - `/stats` percentiles are cumulative for the backend instance, so only the
 *   sampleCount DELTA describes this run
 * - the verdict refuses to declare a winner unless BOTH sides completed, and
 *   cites delivery latency only when BOTH sides actually measured it
 *
 * Added for G.2e:
 * - a churn delta is RECONCILED once its insert count reaches the rows the run
 *   acknowledged; the acknowledged count is a lower bound, because the churn
 *   tables are shared by topic
 * - a delta that cannot be computed is not reconciled — unknown is not settled
 *
 * No mocks: every function here is pure.
 */
import { describe, it, expect } from 'vitest'
import {
  CHURN_TABLE,
  churnDeltaFor,
  churnReconciled,
  compareVerdict,
  distinctSetupIds,
  latencySampleDelta,
  requestedFor,
  targetMismatchReason,
  unverifiedTypeWarning,
} from '../../engine/comparePlan'
import type { RateSettings, RunStatus, RunSummary } from '../../types/generator'
import type {
  CompareReport,
  CompareSettings,
  CompareTarget,
  DbTableStats,
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TelemetryPair,
} from '../../types/compare'

function target(overrides: Partial<CompareTarget> = {}): CompareTarget {
  return { setupId: 's1', queueName: 'orders', implementationType: 'native', ...overrides }
}

function settings(overrides: Partial<CompareSettings> = {}): CompareSettings {
  return {
    native: target({ queueName: 'orders', implementationType: 'native' }),
    outbox: target({ queueName: 'events', implementationType: 'outbox' }),
    ...overrides,
  }
}

function rate(overrides: Partial<RateSettings> = {}): RateSettings {
  return {
    rate: 200,
    durationSecs: 60,
    maxBatchSize: 10,
    warnThreshold: 0,
    maxConsecErrors: 0,
    ...overrides,
  }
}

function summary(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    totalSent: 12000,
    totalAttempted: 12000,
    targetTotal: 12000,
    avgRate: 200,
    durationMs: 60000,
    totalErrors: 0,
    finalStatus: 'completed' as RunStatus,
    runId: 'r-native',
    errors: [],
    ...overrides,
  }
}

function table(overrides: Partial<DbTableStats> = {}): DbTableStats {
  return {
    tableName: 'queue_messages',
    nTupIns: 0,
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
    ...overrides,
  }
}

function dbSnapshot(tables: DbTableStats[], sampledAt = 1000): DbTelemetrySnapshot {
  return {
    setupId: 's1',
    databaseName: 'db1',
    schema: 'public',
    sampledAt,
    tables,
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

function dbPair(
  baselineTables: DbTableStats[],
  finalTables: DbTableStats[]
): TelemetryPair<DbTelemetrySnapshot> {
  return {
    baseline: { ok: true, snapshot: dbSnapshot(baselineTables, 1000) },
    final: { ok: true, snapshot: dbSnapshot(finalTables, 61000) },
  }
}

function statsSnapshot(overrides: Partial<QueueStatsSnapshot> = {}): QueueStatsSnapshot {
  return {
    queueName: 'orders',
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
    ...overrides,
  }
}

function statsPair(
  baseline: QueueStatsSnapshot,
  final: QueueStatsSnapshot
): TelemetryPair<QueueStatsSnapshot> {
  return { baseline: { ok: true, snapshot: baseline }, final: { ok: true, snapshot: final } }
}

function report(overrides: Partial<CompareReport> = {}): CompareReport {
  const base: CompareReport = {
    native: {
      side: 'native',
      target: target({ queueName: 'orders', implementationType: 'native' }),
      summary: summary({ runId: 'r-native' }),
    },
    outbox: {
      side: 'outbox',
      target: target({ queueName: 'events', implementationType: 'outbox' }),
      summary: summary({ runId: 'r-outbox' }),
    },
    telemetry: {
      stats: {
        native: statsPair(statsSnapshot(), statsSnapshot()),
        outbox: statsPair(statsSnapshot(), statsSnapshot()),
      },
      db: {},
    },
  }
  return { ...base, ...overrides }
}

describe('comparePlan', () => {
  // ── requestedFor ──────────────────────────────────────────────────────────

  it('derives the requested total from rate × duration', () => {
    expect(requestedFor(rate({ rate: 200, durationSecs: 60 }))).toBe(12000)
  })

  // ── target roles ──────────────────────────────────────────────────────────

  it('accepts one native and one outbox target', () => {
    expect(targetMismatchReason(settings())).toBeUndefined()
  })

  it('refuses two targets of the SAME implementation type', () => {
    const both = settings({
      outbox: target({ queueName: 'orders2', implementationType: 'native' }),
    })
    const reason = targetMismatchReason(both)
    expect(reason).toBeDefined()
    // The reason must name the slot that is wrong, not just say "invalid".
    expect(reason).toMatch(/outbox/i)
    expect(reason).toMatch(/native/i)
  })

  it('refuses a native slot holding an outbox queue', () => {
    // The queue name must differ from the outbox slot's, or the same-queue
    // guard fires first and this asserts the wrong rule.
    const swapped = settings({
      native: target({ queueName: 'orders', implementationType: 'outbox' }),
    })
    const reason = targetMismatchReason(swapped)
    expect(reason).toMatch(/native/i)
    expect(reason).toMatch(/orders/)
  })

  it('refuses until BOTH targets are chosen', () => {
    expect(targetMismatchReason(settings({ native: null }))).toMatch(/native/i)
    expect(targetMismatchReason(settings({ outbox: null }))).toMatch(/outbox/i)
  })

  it('refuses the same queue on both sides', () => {
    const same = settings({
      native: target({ setupId: 's1', queueName: 'orders', implementationType: 'native' }),
      outbox: target({ setupId: 's1', queueName: 'orders', implementationType: 'outbox' }),
    })
    // Whatever the reported types claim, one queue cannot be both sides of a
    // comparison — the two runs would publish into the same table.
    expect(targetMismatchReason(same)).toMatch(/same queue/i)
  })

  it('does NOT treat an unreported implementation type as a mismatch', () => {
    const unknown = settings({ native: target({ implementationType: null }) })
    expect(targetMismatchReason(unknown)).toBeUndefined()
  })

  it('names an unreported implementation type in a non-blocking warning', () => {
    const unknown = settings({ native: target({ queueName: 'orders', implementationType: null }) })
    const warning = unverifiedTypeWarning(unknown)
    expect(warning).toBeDefined()
    expect(warning).toMatch(/orders/)
  })

  it('warns about nothing when both types were reported', () => {
    expect(unverifiedTypeWarning(settings())).toBeUndefined()
  })

  // ── setup fan-out ─────────────────────────────────────────────────────────

  it('lists each setup once — two sides in one setup share a db-telemetry read', () => {
    expect(distinctSetupIds(settings())).toEqual(['s1'])
  })

  it('lists both setups when the sides live in different setups', () => {
    const split = settings({ outbox: target({ setupId: 's2', implementationType: 'outbox' }) })
    expect(distinctSetupIds(split)).toEqual(['s1', 's2'])
  })

  // ── db churn deltas ───────────────────────────────────────────────────────

  it('reports the churn a run added as the DELTA between the two snapshots', () => {
    const pair = dbPair(
      [table({ nTupIns: 1000, nTupDel: 400, seqScan: 50, idxScan: 10, totalBytes: 8192 })],
      [table({ nTupIns: 13000, nTupDel: 12400, seqScan: 1250, idxScan: 60, totalBytes: 40960 })]
    )
    const delta = churnDeltaFor(pair, 'queue_messages')

    expect(delta).not.toBeNull()
    expect(delta!.insertedTuples).toBe(12000)
    expect(delta!.deletedTuples).toBe(12000)
    expect(delta!.seqScans).toBe(1200)
    expect(delta!.indexScans).toBe(50)
    expect(delta!.totalBytesGrowth).toBe(32768)
  })

  it('reports a dead-tuple LEVEL change, which is negative when autovacuum kept up', () => {
    const pair = dbPair([table({ nDeadTup: 900, nLiveTup: 100 })], [table({ nDeadTup: 40, nLiveTup: 60 })])
    const delta = churnDeltaFor(pair, 'queue_messages')

    expect(delta!.deadTupleChange).toBe(-860)
    expect(delta!.liveTupleChange).toBe(-40)
  })

  it('returns null when the table is absent from either snapshot', () => {
    const missingInBaseline = dbPair([], [table()])
    const missingInFinal = dbPair([table()], [])

    expect(churnDeltaFor(missingInBaseline, 'queue_messages')).toBeNull()
    expect(churnDeltaFor(missingInFinal, 'queue_messages')).toBeNull()
  })

  it('returns null when either telemetry read failed — never a zero delta', () => {
    const failed: TelemetryPair<DbTelemetrySnapshot> = {
      baseline: { ok: false, error: 'Request failed with status code 503' },
      final: { ok: true, snapshot: dbSnapshot([table({ nTupIns: 12000 })]) },
    }
    // A zeroed delta here would report "this run inserted nothing" for a run
    // that inserted 12,000 rows. Absence is the only truthful answer.
    expect(churnDeltaFor(failed, 'queue_messages')).toBeNull()
  })

  it('omits indexScans when either snapshot omitted idxScan', () => {
    const noIndex = dbPair(
      [{ ...table(), idxScan: undefined }],
      [{ ...table({ nTupIns: 10 }), idxScan: undefined }]
    )
    const delta = churnDeltaFor(noIndex, 'queue_messages')

    expect(delta).not.toBeNull()
    expect(delta!.indexScans).toBeUndefined()
  })

  // ── churn reconciliation (G.2e) ───────────────────────────────────────────

  it('names the churn-bearing table each implementation actually writes', () => {
    // Both implementations route through these shared tables by topic; the
    // per-queue table named after the queue is an inert marker. Mapped to the
    // queue-named table, every churn figure would read zero.
    expect(CHURN_TABLE.native).toBe('queue_messages')
    expect(CHURN_TABLE.outbox).toBe('outbox')
  })

  it('reconciles when the insert delta reaches the rows the run acknowledged', () => {
    const pair = dbPair([table({ nTupIns: 5 })], [table({ nTupIns: 15 })])
    expect(churnReconciled(pair, 'queue_messages', 10)).toBe(true)
  })

  it('reconciles when the insert delta EXCEEDS the acknowledged count', () => {
    // queue_messages and outbox are shared by topic, so another producer's rows
    // land in the same counter. The acknowledged count is a lower bound on what
    // this run put there, never an exact expectation.
    const pair = dbPair([table({ nTupIns: 0 })], [table({ nTupIns: 40 })])
    expect(churnReconciled(pair, 'queue_messages', 10)).toBe(true)
  })

  it('does NOT reconcile while the insert delta falls short of the acknowledged count', () => {
    // Ten messages acknowledged and zero rows inserted cannot both be true:
    // the database has not yet made those inserts visible in pg_stat_user_tables.
    const pair = dbPair([table({ nTupIns: 0 })], [table({ nTupIns: 0 })])
    expect(churnReconciled(pair, 'queue_messages', 10)).toBe(false)
  })

  it('does NOT reconcile when the delta cannot be computed at all', () => {
    // Unknown is not reconciled. A failed read or an absent table says nothing
    // about whether the counters caught up, and treating it as settled would
    // freeze an unknowable figure into the report.
    const failed: TelemetryPair<DbTelemetrySnapshot> = {
      baseline: { ok: false, error: 'Request failed with status code 503' },
      final: { ok: true, snapshot: dbSnapshot([table({ nTupIns: 12000 })]) },
    }
    expect(churnReconciled(failed, 'queue_messages', 10)).toBe(false)
    expect(churnReconciled(dbPair([], [table()]), 'queue_messages', 10)).toBe(false)
  })

  it('reconciles immediately when the run acknowledged nothing', () => {
    // A run that sent nothing is owed no rows, so there is nothing to wait for.
    const pair = dbPair([table({ nTupIns: 7 })], [table({ nTupIns: 7 })])
    expect(churnReconciled(pair, 'queue_messages', 0)).toBe(true)
  })

  // ── latency sample deltas ─────────────────────────────────────────────────

  it('reports how many delivery-latency samples THIS run contributed', () => {
    const pair = statsPair(
      statsSnapshot({ deliveryLatency: { p50Ms: 2, p95Ms: 4, p99Ms: 9, sampleCount: 500 } }),
      statsSnapshot({ deliveryLatency: { p50Ms: 3, p95Ms: 7, p99Ms: 12, sampleCount: 12500 } })
    )
    expect(latencySampleDelta(pair, 'deliveryLatency')).toBe(12000)
  })

  it('counts a distribution that did not exist at baseline from zero', () => {
    const pair = statsPair(
      statsSnapshot(),
      statsSnapshot({ deliveryLatency: { p50Ms: 3, p95Ms: 7, p99Ms: 12, sampleCount: 900 } })
    )
    expect(latencySampleDelta(pair, 'deliveryLatency')).toBe(900)
  })

  it('returns null when the final snapshot has no distribution at all', () => {
    // Nothing consumed the queue, so nothing was ever claimed. "No measurement"
    // is not "0 samples measured".
    expect(latencySampleDelta(statsPair(statsSnapshot(), statsSnapshot()), 'deliveryLatency')).toBeNull()
  })

  it('returns null when the final telemetry read failed', () => {
    const pair: TelemetryPair<QueueStatsSnapshot> = {
      baseline: { ok: true, snapshot: statsSnapshot() },
      final: { ok: false, error: 'Network Error' },
    }
    expect(latencySampleDelta(pair, 'deliveryLatency')).toBeNull()
  })

  // ── verdict ───────────────────────────────────────────────────────────────

  it('refuses a verdict when a side did not complete, naming the side and status', () => {
    const stopped = report({
      outbox: {
        side: 'outbox',
        target: target({ queueName: 'events', implementationType: 'outbox' }),
        summary: summary({ finalStatus: 'stopped', totalSent: 300 }),
      },
    })
    const verdict = compareVerdict(stopped)

    expect(verdict).toMatch(/no verdict/i)
    expect(verdict).toMatch(/outbox/i)
    expect(verdict).toMatch(/stopped/i)
  })

  it('refuses a verdict when a side errored', () => {
    const errored = report({
      native: {
        side: 'native',
        target: target({ implementationType: 'native' }),
        summary: summary({ finalStatus: 'error', totalErrors: 4 }),
      },
    })
    expect(compareVerdict(errored)).toMatch(/no verdict/i)
  })

  it('names the side that sustained the higher acknowledged rate, with both figures', () => {
    const decided = report({
      outbox: {
        side: 'outbox',
        target: target({ queueName: 'events', implementationType: 'outbox' }),
        summary: summary({ totalSent: 11400, avgRate: 190 }),
      },
    })
    const verdict = compareVerdict(decided)

    expect(verdict).toMatch(/native/i)
    expect(verdict).toContain('200')
    expect(verdict).toContain('190')
  })

  it('reports a tie rather than inventing a winner from equal rates', () => {
    expect(compareVerdict(report())).toMatch(/same|equal|tie/i)
  })

  it('cites delivery-latency p95 only when BOTH sides measured it', () => {
    const withLatency = report({
      telemetry: {
        stats: {
          native: statsPair(
            statsSnapshot(),
            statsSnapshot({ deliveryLatency: { p50Ms: 2, p95Ms: 4, p99Ms: 6, sampleCount: 100 } })
          ),
          outbox: statsPair(
            statsSnapshot(),
            statsSnapshot({ deliveryLatency: { p50Ms: 12, p95Ms: 28, p99Ms: 40, sampleCount: 100 } })
          ),
        },
        db: {},
      },
    })
    const verdict = compareVerdict(withLatency)

    expect(verdict).toContain('4')
    expect(verdict).toContain('28')
    expect(verdict).toMatch(/p95/i)
  })

  it('says delivery latency was not measured rather than implying it was equal', () => {
    const oneSideOnly = report({
      telemetry: {
        stats: {
          native: statsPair(
            statsSnapshot(),
            statsSnapshot({ deliveryLatency: { p50Ms: 2, p95Ms: 4, p99Ms: 6, sampleCount: 100 } })
          ),
          outbox: statsPair(statsSnapshot(), statsSnapshot()),
        },
        db: {},
      },
    })
    const verdict = compareVerdict(oneSideOnly)

    expect(verdict).toMatch(/not measured|no delivery latency/i)
    // A one-sided number must not be presented as a comparison.
    expect(verdict).not.toMatch(/p95.*lower|lower.*p95/i)
  })

  it('calls out errors on one side when the other had none', () => {
    const withErrors = report({
      outbox: {
        side: 'outbox',
        target: target({ queueName: 'events', implementationType: 'outbox' }),
        summary: summary({ totalSent: 11400, avgRate: 190, totalErrors: 12 }),
      },
    })
    const verdict = compareVerdict(withErrors)

    expect(verdict).toContain('12')
    expect(verdict).toMatch(/error/i)
  })
})
