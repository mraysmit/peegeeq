/**
 * Tests for CompareResultsPanel (Zone E — Compare mode, design §19.2 — G.2c).
 *
 * Contract under test (written before the component):
 * - side-by-side figures for native and outbox, with REQUESTED derived from
 *   the shared load rather than stored beside the achieved counts
 * - a latency distribution the backend did not report reads as "not measured",
 *   never as 0 ms — nothing consumed the queue, so no enqueue-to-claim time
 *   exists, and a zero would read as instant delivery
 * - a telemetry read that FAILED reads as a failure, and its reason is shown
 * - database churn is the run-window delta per implementation's own table
 *   (queue_messages against outbox), and shows as unknown when unavailable
 * - an errored side's reason is surfaced, not dropped
 * - the verdict line is rendered
 * - before any run there is an empty state; while running, live counters
 *
 * No mocks: the panel is presentational over comparePlan, which is pure.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import CompareResultsPanel from '../../pages/generator/CompareResultsPanel'
import type { RunStatus, RunSummary } from '../../types/generator'
import type {
  CompareReport,
  CompareSideName,
  CompareSideProgress,
  DbTableStats,
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TelemetryPair,
} from '../../types/compare'

function summary(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    totalSent: 11980,
    totalAttempted: 12000,
    targetTotal: 12000,
    avgRate: 199.7,
    durationMs: 60000,
    totalErrors: 0,
    finalStatus: 'completed' as RunStatus,
    runId: 'r1',
    errors: [],
    ...overrides,
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

function dbSnapshot(tables: DbTableStats[]): DbTelemetrySnapshot {
  return {
    setupId: 's1',
    databaseName: 'db1',
    schema: 'public',
    sampledAt: 1,
    tables,
    cluster: {
      backendsHoldingXmin: 0,
      locksTotal: 0,
      locksWaiting: 0,
      xidAge: 0,
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

function makeReport(overrides: Partial<CompareReport> = {}): CompareReport {
  const base: CompareReport = {
    native: {
      side: 'native',
      target: { setupId: 's1', queueName: 'orders', implementationType: 'native' },
      summary: summary({ runId: 'r-native' }),
    },
    outbox: {
      side: 'outbox',
      target: { setupId: 's1', queueName: 'events', implementationType: 'outbox' },
      summary: summary({ runId: 'r-outbox', totalSent: 11400, avgRate: 190, totalErrors: 12 }),
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

const NO_PROGRESS: Record<CompareSideName, CompareSideProgress | null> = {
  native: null,
  outbox: null,
}

function renderPanel(
  report: CompareReport | null,
  progress = NO_PROGRESS,
  requested = 12000,
  settledSides = 0
) {
  return render(
    <ConfigProvider>
      <CompareResultsPanel
        progress={progress}
        report={report}
        requested={requested}
        settledSides={settledSides}
      />
    </ConfigProvider>
  )
}

const BOTH_LIVE: Record<CompareSideName, CompareSideProgress | null> = {
  native: { side: 'native', sent: 4000, errors: 0, elapsedMs: 20000 },
  outbox: { side: 'outbox', sent: 3800, errors: 2, elapsedMs: 20000 },
}

function liveNote(): string {
  return screen.getByTestId('compare-live-note').textContent ?? ''
}

function cell(side: CompareSideName, key: string): string {
  return screen.getByTestId(`compare-${side}-${key}`).textContent ?? ''
}

describe('CompareResultsPanel', () => {
  it('shows an empty state before any comparison has run', () => {
    renderPanel(null)
    expect(screen.getByTestId('compare-results-empty')).toBeTruthy()
    expect(screen.queryByTestId('compare-results-panel')).toBeNull()
  })

  it('shows live acknowledged and error counts per side while running', () => {
    renderPanel(null, {
      native: { side: 'native', sent: 4000, errors: 0, elapsedMs: 20000 },
      outbox: { side: 'outbox', sent: 3800, errors: 2, elapsedMs: 20000 },
    })

    expect(cell('native', 'acked')).toContain('4000')
    expect(cell('outbox', 'acked')).toContain('3800')
    expect(cell('outbox', 'errors')).toContain('2')
    expect(screen.getByTestId('compare-live-note')).toBeTruthy()
  })

  // ── the live note must describe the state the run is ACTUALLY in (G.2e) ────
  //
  // The report is published only once the final database sample accounts for
  // the run, so there is now a window of seconds in which both sides have
  // stopped and no report exists yet. A note that says "both sides are running"
  // through that window states something false.

  it('says both sides are running while neither has settled', () => {
    renderPanel(null, BOTH_LIVE, 12000, 0)
    expect(liveNote()).toMatch(/both sides are running/i)
    expect(liveNote()).not.toMatch(/waiting/i)
  })

  it('does not claim both sides are running when only one has settled', () => {
    renderPanel(null, BOTH_LIVE, 12000, 1)
    expect(liveNote()).not.toMatch(/both sides are running/i)
    expect(liveNote()).toMatch(/still running/i)
  })

  it('says it is waiting on the database once BOTH sides have settled', () => {
    renderPanel(null, BOTH_LIVE, 12000, 2)
    // Nothing is publishing any more; claiming otherwise for the length of the
    // settle window is the same class of defect as reporting a zero for an
    // unknown figure.
    expect(liveNote()).not.toMatch(/running/i)
    expect(liveNote()).toMatch(/waiting/i)
    expect(liveNote()).toMatch(/database/i)
  })

  it('shows the side-by-side figures with REQUESTED derived from the shared load', () => {
    renderPanel(makeReport())

    expect(cell('native', 'requested')).toContain('12000')
    expect(cell('outbox', 'requested')).toContain('12000')
    expect(cell('native', 'attempted')).toContain('12000')
    expect(cell('native', 'acked')).toContain('11980')
    expect(cell('outbox', 'acked')).toContain('11400')
    expect(cell('native', 'rate')).toContain('199.7')
    expect(cell('outbox', 'errors')).toContain('12')
  })

  it('reports a latency distribution the backend did not send as NOT MEASURED, not 0 ms', () => {
    renderPanel(makeReport())

    // The utilities-ui only publishes; with nothing consuming these queues no
    // enqueue-to-claim time exists. "0 ms" would read as instant delivery.
    expect(cell('native', 'deliveryP95')).toMatch(/not measured/i)
    expect(cell('native', 'deliveryP95')).not.toMatch(/0\.0 ms/)
  })

  it('shows the measured p95 for each side when both were measured', () => {
    renderPanel(
      makeReport({
        telemetry: {
          stats: {
            native: statsPair(
              statsSnapshot(),
              statsSnapshot({
                deliveryLatency: { p50Ms: 2, p95Ms: 4, p99Ms: 9, sampleCount: 12000 },
              })
            ),
            outbox: statsPair(
              statsSnapshot(),
              statsSnapshot({
                deliveryLatency: { p50Ms: 12, p95Ms: 28, p99Ms: 44, sampleCount: 11400 },
              })
            ),
          },
          db: {},
        },
      })
    )

    expect(cell('native', 'deliveryP95')).toContain('4.0 ms')
    expect(cell('outbox', 'deliveryP95')).toContain('28.0 ms')
    // How much of the cumulative distribution THIS run contributed.
    expect(cell('native', 'deliverySamples')).toContain('12000')
  })

  it('reports a FAILED telemetry read as a failure and shows its reason', () => {
    renderPanel(
      makeReport({
        telemetry: {
          stats: {
            native: {
              baseline: { ok: true, snapshot: statsSnapshot() },
              final: { ok: false, error: 'Queue stats for s1/orders could not be read: 503' },
            },
            outbox: statsPair(statsSnapshot(), statsSnapshot()),
          },
          db: {},
        },
      })
    )

    expect(cell('native', 'deliveryP95')).toMatch(/read failed/i)
    expect(screen.getByTestId('compare-telemetry-errors')).toBeTruthy()
    expect(screen.getByTestId('compare-telemetry-error-0').textContent).toContain('503')
  })

  it('shows each implementation\'s DB churn from its OWN table, as a run-window delta', () => {
    renderPanel(
      makeReport({
        telemetry: {
          stats: {
            native: statsPair(statsSnapshot(), statsSnapshot()),
            outbox: statsPair(statsSnapshot(), statsSnapshot()),
          },
          db: {
            s1: {
              baseline: {
                ok: true,
                snapshot: dbSnapshot([
                  table({ tableName: 'queue_messages', nTupIns: 100, seqScan: 10 }),
                  table({ tableName: 'outbox', nTupIns: 50, nTupUpd: 5, seqScan: 200 }),
                ]),
              },
              final: {
                ok: true,
                snapshot: dbSnapshot([
                  table({ tableName: 'queue_messages', nTupIns: 12100, seqScan: 60 }),
                  table({ tableName: 'outbox', nTupIns: 11450, nTupUpd: 11405, seqScan: 3200 }),
                ]),
              },
            },
          },
        },
      })
    )

    // native writes queue_messages; outbox writes outbox. Reading one table for
    // both sides would report the same churn twice.
    expect(cell('native', 'churnInserted')).toContain('12000')
    expect(cell('outbox', 'churnInserted')).toContain('11400')
    expect(cell('outbox', 'churnUpdated')).toContain('11400')
    // The polling seq_scan gap is the native-vs-outbox difference §4A names.
    expect(cell('native', 'churnSeqScans')).toContain('50')
    expect(cell('outbox', 'churnSeqScans')).toContain('3000')
  })

  it('shows churn as unknown when the database telemetry is unavailable', () => {
    renderPanel(makeReport())
    // No db entry at all — an unavailable figure must not render as 0 churn.
    expect(cell('native', 'churnInserted')).not.toMatch(/\b0\b/)
    expect(cell('native', 'churnInserted')).toContain('—')
  })

  it('renders the verdict line', () => {
    renderPanel(makeReport())
    expect(screen.getByTestId('compare-verdict')).toBeTruthy()
    expect(screen.getByTestId('compare-verdict').textContent).toMatch(/native|outbox/i)
  })

  it('surfaces an errored side\'s reason rather than dropping it', () => {
    renderPanel(
      makeReport({
        outbox: {
          side: 'outbox',
          target: { setupId: 's1', queueName: 'events', implementationType: 'outbox' },
          summary: summary({ finalStatus: 'error', totalErrors: 4 }),
          errorReason: 'Auto-stopped: 3 consecutive errors. Last: 503',
        },
      })
    )

    expect(screen.getByTestId('compare-error-outbox').textContent).toContain('Auto-stopped')
    expect(screen.queryByTestId('compare-error-native')).toBeNull()
  })

  it('states that the percentiles are not scoped to the run', () => {
    renderPanel(makeReport())
    // Gap G5 is open, so a reader must not take p95 as this run's figure.
    expect(screen.getByTestId('compare-scope-note').textContent).toMatch(/not only this run/i)
  })
})
