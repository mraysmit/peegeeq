/**
 * Type definitions for the Native-vs-Outbox comparison run (design §19.2 —
 * Phase G.2).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/profile.ts and src/types/ramp.ts.
 *
 * Two rules shape everything here:
 *
 * 1. **Nothing derivable is stored.** A side's achieved rate, its requested
 *    total and its shortfall are computed from the RunSummary and the shared
 *    rate settings where they are displayed (comparePlan), never recorded
 *    beside them — that is where an achieved figure and a stale requested one
 *    drift apart (the ProfilePhaseResult precedent).
 * 2. **Absence is a value; zero is not.** Every backend telemetry field the
 *    server omits is OPTIONAL here too, and a telemetry read that FAILED is a
 *    {@link TelemetryCapture} carrying its reason — not a zeroed snapshot. The
 *    backend's own contracts say the same (DurationPercentiles "a missing
 *    distribution is represented by the ABSENCE of this object, never by
 *    zeroes"; DatabaseTelemetryHandler "no fabricated zero/empty payloads on
 *    failure"), and erasing that at the client would recreate the defect the
 *    backend went out of its way to avoid.
 */
import type { RunSummary } from './generator'
import type { QueueImplementationType } from './queue'

/**
 * The two slots of a comparison. These are ROLES, not free labels: a
 * "native vs outbox" run whose two targets are both native measures nothing it
 * claims to, so comparePlan refuses to start on a known mismatch.
 */
export type CompareSideName = 'native' | 'outbox'

/** One side's publish target. */
export interface CompareTarget {
  setupId: string
  queueName: string
  /**
   * The queue's real implementation type as reported by `listQueueDetails`.
   * `null` means the backend did not say (the names-only fallback) — unknown,
   * which is neither a match nor a mismatch and is surfaced as such.
   */
  implementationType: QueueImplementationType | null
}

/** The two targets a comparison fires identical load at. */
export interface CompareSettings {
  native: CompareTarget | null
  outbox: CompareTarget | null
}

/**
 * What one side of the comparison achieved.
 *
 * The whole {@link RunSummary} is kept rather than copied field by field: it is
 * the engine's own output, so there is exactly one source for `totalSent`
 * (server-acknowledged), `totalAttempted` (built and handed to publish),
 * `totalErrors` and `durationMs`. A parallel record of the same numbers would
 * be derived data that can disagree with its source.
 */
export interface CompareSideResult {
  side: CompareSideName
  target: CompareTarget
  summary: RunSummary
  /**
   * The engine's terminal reason, present only when this side ended in error
   * (auto-stop after consecutive failures, or a build failure). The engine
   * passes it alongside the summary and it is not derivable from one, so it is
   * carried rather than lost — an errored side with no stated cause is the
   * error-swallowing this codebase bans.
   */
  errorReason?: string
}

/** Live per-side progress while a comparison is running. */
export interface CompareSideProgress {
  side: CompareSideName
  /** Server-acknowledged messages so far. */
  sent: number
  errors: number
  elapsedMs: number
}

// ── Backend telemetry (Phase T: G1, G2, G7) ─────────────────────────────────

/**
 * A latency distribution reported by `GET /queues/{setup}/{queue}/stats`
 * (telemetry G1/G2). Mirrors the backend `DurationPercentiles` minus `meanMs`,
 * which `QueueHandler.getQueueStats` does not put on the response.
 *
 * **Scope, stated because it changes how the number may be read:** these cover
 * every message the backend INSTANCE has seen for that topic since it started,
 * not just this run. Per-run scoping is telemetry gap G5 and is still open, so
 * `sampleCount` is the honest way to see how much of the distribution this run
 * contributed — comparePlan reports its delta rather than implying the
 * percentile belongs to the run.
 */
export interface QueueDurationPercentiles {
  p50Ms: number
  p95Ms: number
  p99Ms: number
  sampleCount: number
}

/** One `GET /api/v1/queues/{setupId}/{queueName}/stats` response. */
export interface QueueStatsSnapshot {
  queueName: string
  setupId: string
  implementationType: string
  healthy: boolean
  totalMessages: number
  pendingMessages: number
  inFlightMessages: number
  processedMessages: number
  deadLetteredMessages: number
  messagesPerSecond: number
  avgProcessingTimeMs: number
  successRatePercent: number
  timestamp: number
  /**
   * Consumer handler time (telemetry G1). ABSENT when this backend instance
   * has measured nothing for the topic.
   */
  processingTime?: QueueDurationPercentiles
  /**
   * Enqueue → claim on the database clock (telemetry G2) — the measurement
   * that IS the native-vs-outbox difference (LISTEN/NOTIFY against polling).
   * ABSENT when nothing has claimed a message: the utilities-ui only
   * publishes, so a queue with no consumer running produces no delivery
   * latency at all. That absence is a fact about the environment, not a zero.
   */
  deliveryLatency?: QueueDurationPercentiles
}

/** One table's row from `GET /api/v1/setups/{setupId}/db-telemetry`. */
export interface DbTableStats {
  tableName: string
  nTupIns: number
  nTupUpd: number
  nTupDel: number
  nTupHotUpd: number
  nLiveTup: number
  nDeadTup: number
  seqScan: number
  /** Absent for a table with no indexes (`pg_stat_user_tables.idx_scan` NULL). */
  idxScan?: number
  vacuumCount: number
  autovacuumCount: number
  heapBlksHit: number
  heapBlksRead: number
  heapBytes: number
  indexBytes: number
  totalBytes: number
  /** Absent until the first (auto)vacuum/analyze has run. ISO 8601. */
  lastVacuum?: string
  lastAutovacuum?: string
  lastAutoanalyze?: string
}

/** The cluster/database-wide block of a db-telemetry snapshot. */
export interface DbClusterStats {
  backendsHoldingXmin: number
  locksTotal: number
  locksWaiting: number
  xidAge: number
  walRecords: number
  walBytes: number
  walLsnBytes: number
  checkpointsTimed: number
  checkpointsRequested: number
  buffersCheckpoint: number
  xactCommit: number
  xactRollback: number
  deadlocks: number
  tupReturned: number
  tupFetched: number
  numbackends: number
  blksHit: number
  blksRead: number
  /** Absent when no transaction is in progress — a zero would claim one of age 0. */
  longestTxnSeconds?: number
}

/** One `GET /api/v1/setups/{setupId}/db-telemetry` response. */
export interface DbTelemetrySnapshot {
  setupId: string
  databaseName: string
  schema: string
  /** The REST server's clock, for plotting only. Counters are the database's own. */
  sampledAt: number
  tables: DbTableStats[]
  cluster: DbClusterStats
}

/**
 * One telemetry read: the snapshot, or the reason it could not be taken.
 *
 * A discriminated union rather than an optional snapshot, so a caller cannot
 * reach the data without deciding what a failure means. A failed read must
 * never degrade into "all zeroes" — that is the TYPED-ERASURE pattern the
 * project's `.recover()` audit catalogues, and the reason a run's telemetry
 * would silently read as an idle database.
 */
export type TelemetryCapture<T> =
  | { ok: true; snapshot: T }
  | { ok: false; error: string }

/** The same telemetry read at run start and at run end, so deltas are derivable. */
export interface TelemetryPair<T> {
  baseline: TelemetryCapture<T>
  final: TelemetryCapture<T>
}

/**
 * Everything the comparison sampled around the run.
 *
 * `stats` is per SIDE — each side has its own queue. `db` is per SETUP, keyed
 * by `setupId`: `db-telemetry` covers a whole schema, so two sides living in
 * one setup share ONE snapshot pair, and the per-table rows are what separate
 * native (`queue_messages`) from outbox (`outbox`).
 */
export interface CompareTelemetry {
  stats: Record<CompareSideName, TelemetryPair<QueueStatsSnapshot>>
  db: Record<string, TelemetryPair<DbTelemetrySnapshot>>
}

/** The finished comparison: both sides' outcomes plus the telemetry around them. */
export interface CompareReport {
  native: CompareSideResult
  outbox: CompareSideResult
  telemetry: CompareTelemetry
}

/**
 * The churn a run added to one table, over the run window.
 *
 * `pg_stat_*` counters are cumulative since the last stats reset (telemetry
 * §4A "Deltas"), so only the DIFFERENCE between the baseline and the final
 * snapshot describes the run. `nDeadTup`/`nLiveTup` are gauges, not counters —
 * their delta is a change in level and is named as such.
 */
export interface TableChurnDelta {
  tableName: string
  insertedTuples: number
  updatedTuples: number
  deletedTuples: number
  hotUpdatedTuples: number
  seqScans: number
  /** Absent when either snapshot omitted `idxScan` (table has no indexes). */
  indexScans?: number
  autovacuums: number
  /** Change in the dead-tuple LEVEL — can be negative when autovacuum kept up. */
  deadTupleChange: number
  liveTupleChange: number
  totalBytesGrowth: number
}
