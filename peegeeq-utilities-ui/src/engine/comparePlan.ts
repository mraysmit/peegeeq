/**
 * Native-vs-Outbox comparison: the pure decisions (design §19.2 — Phase G.2a).
 *
 * A module in the rampPlan/exerciserPlan mould. It owns the decisions — are
 * these two targets a valid comparison, what churn did the run actually cause,
 * which side sustained the load better — and nothing else. Sequencing lives in
 * comparisonRunner; HTTP lives in telemetryService.
 *
 * Everything is derived from the run summaries and the telemetry snapshots.
 * Nothing derivable is stored, so a displayed figure can never disagree with
 * the run it describes.
 *
 * **The counters this module reads are cumulative.** `pg_stat_*` counts since
 * the last stats reset (telemetry §4A), and the `/stats` percentile histograms
 * cover everything the backend INSTANCE has seen for that topic since it
 * started (telemetry G1/G2; per-run scoping is gap G5, still open). Only a
 * baseline-to-final delta describes a run, which is why every function here
 * takes a {@link TelemetryPair} rather than a single snapshot — and why a
 * failed read yields `null`, never a zero.
 */
import type { RateSettings, RunSummary } from '../types/generator'
import type {
  CompareReport,
  CompareSettings,
  CompareSideName,
  CompareTarget,
  DbTableStats,
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TableChurnDelta,
  TelemetryPair,
} from '../types/compare'

/**
 * Acknowledged rates within this share of each other are reported as the same
 * rather than as a winner. Client-side metering cannot resolve a difference
 * that small, and naming a winner on it would dress noise up as a finding.
 */
const RATE_TIE_TOLERANCE = 0.01

/** Messages the shared load asks each side for — derived, never stored. */
export function requestedFor(settings: RateSettings): number {
  return settings.rate * settings.durationSecs
}

/** Every setup a comparison touches, once each — the db-telemetry fan-out. */
export function distinctSetupIds(settings: CompareSettings): string[] {
  const ids: string[] = []
  for (const target of [settings.native, settings.outbox]) {
    if (target !== null && !ids.includes(target.setupId)) ids.push(target.setupId)
  }
  return ids
}

/**
 * The outcome of validating a comparison's two targets: either both resolved
 * targets, or the reason they cannot be compared.
 *
 * A discriminated result rather than a nullable-reason string, so the runner
 * gets its non-null targets from the same check that produced the reason. The
 * alternative — check the reason, then re-check for null to satisfy the type
 * system — leaves a branch no caller can reach.
 */
export type CompareTargetsCheck =
  | { ok: true; native: CompareTarget; outbox: CompareTarget }
  | { ok: false; reason: string }

/**
 * Validate a comparison's two targets.
 *
 * The two slots are ROLES. Firing "native vs outbox" at two native queues
 * produces a side-by-side table that measures the same implementation twice
 * while labelling one of them outbox — a fabricated finding, so it is refused
 * rather than run.
 *
 * An implementation type the backend did NOT report is not a mismatch: unknown
 * and wrong are different facts. That case is surfaced by
 * {@link unverifiedTypeWarning} instead, which does not block.
 */
export function checkCompareTargets(settings: CompareSettings): CompareTargetsCheck {
  const { native, outbox } = settings
  if (native === null) {
    return { ok: false, reason: 'Choose the native queue for the native side of the comparison.' }
  }
  if (outbox === null) {
    return { ok: false, reason: 'Choose the outbox queue for the outbox side of the comparison.' }
  }

  if (native.setupId === outbox.setupId && native.queueName === outbox.queueName) {
    return {
      ok: false,
      reason: `Both sides point at the same queue (${native.setupId} / ${native.queueName}) — a comparison needs two different queues.`,
    }
  }

  const wrong = wrongTypeFor('native', native) ?? wrongTypeFor('outbox', outbox)
  if (wrong !== undefined) return { ok: false, reason: wrong }
  return { ok: true, native, outbox }
}

/**
 * Why this pair of targets cannot be compared, or undefined when it can — the
 * form Zone D's `startBlockedReason` takes, so the disabled button explains
 * itself instead of refusing silently.
 */
export function targetMismatchReason(settings: CompareSettings): string | undefined {
  const check = checkCompareTargets(settings)
  return check.ok ? undefined : check.reason
}

/** The reason a slot holds a queue of the other implementation type. */
function wrongTypeFor(side: CompareSideName, target: CompareTarget): string | undefined {
  if (target.implementationType === null || target.implementationType === side) return undefined
  return `The ${side} side is set to queue "${target.queueName}", which the backend reports as ${target.implementationType}. A native-vs-outbox comparison needs one queue of each type.`
}

/**
 * A non-blocking warning naming targets whose implementation type the backend
 * did not report, or undefined when both were reported.
 *
 * Running is still the operator's call — but an unconfirmed pair must not be
 * presented as a confirmed native-vs-outbox comparison.
 */
export function unverifiedTypeWarning(settings: CompareSettings): string | undefined {
  const unreported = [settings.native, settings.outbox]
    .filter((target): target is CompareTarget => target !== null)
    .filter((target) => target.implementationType === null)
    .map((target) => target.queueName)
  if (unreported.length === 0) return undefined
  return `The backend did not report an implementation type for: ${unreported.join(', ')}. The comparison will run, but it cannot confirm the two sides are actually native and outbox.`
}

/**
 * The churn one run added to one table, or null when it cannot be known.
 *
 * Null covers three cases that must not be confused with "no churn": either
 * telemetry read failed, or the table was absent from either snapshot. A zero
 * delta in any of them would report an idle table for a run that hammered it —
 * the client-side form of the TYPED-ERASURE pattern the project's `.recover()`
 * audit catalogues.
 */
export function churnDeltaFor(
  pair: TelemetryPair<DbTelemetrySnapshot>,
  tableName: string
): TableChurnDelta | null {
  if (!pair.baseline.ok || !pair.final.ok) return null
  const before = findTable(pair.baseline.snapshot, tableName)
  const after = findTable(pair.final.snapshot, tableName)
  if (before === undefined || after === undefined) return null

  const delta: TableChurnDelta = {
    tableName,
    insertedTuples: after.nTupIns - before.nTupIns,
    updatedTuples: after.nTupUpd - before.nTupUpd,
    deletedTuples: after.nTupDel - before.nTupDel,
    hotUpdatedTuples: after.nTupHotUpd - before.nTupHotUpd,
    seqScans: after.seqScan - before.seqScan,
    autovacuums: after.autovacuumCount - before.autovacuumCount,
    // Gauges, not counters: this is a change in LEVEL, and it is negative when
    // autovacuum reclaimed more than the run produced.
    deadTupleChange: after.nDeadTup - before.nDeadTup,
    liveTupleChange: after.nLiveTup - before.nLiveTup,
    totalBytesGrowth: after.totalBytes - before.totalBytes,
  }
  // Omitted rather than zeroed: idx_scan is NULL for a table with no indexes,
  // and "no index scans happened" is a different fact from "no index exists".
  if (before.idxScan !== undefined && after.idxScan !== undefined) {
    delta.indexScans = after.idxScan - before.idxScan
  }
  return delta
}

function findTable(snapshot: DbTelemetrySnapshot, tableName: string): DbTableStats | undefined {
  return snapshot.tables.find((table) => table.tableName === tableName)
}

/**
 * The churn-bearing table each implementation actually writes (telemetry §4A
 * "Verified schema").
 *
 * The per-queue table named after the queue is an inert marker: native and
 * outbox both route through these shared tables by topic. Mapped to the
 * queue-named table, every churn figure would read zero.
 */
export const CHURN_TABLE: Record<CompareSideName, string> = {
  native: 'queue_messages',
  outbox: 'outbox',
}

/**
 * Whether a table's churn delta accounts for the rows a run acknowledged.
 *
 * PostgreSQL does not make a committed INSERT visible in `pg_stat_user_tables`
 * at commit: each backend accumulates its counters and flushes them on a rate
 * limit. The two publish paths commit on DIFFERENT connections, so one sample
 * can read one side's inserts correctly and the other's as zero — "10
 * acknowledged, 0 rows inserted", which cannot both be true.
 *
 * The acknowledged count is a LOWER BOUND, not an expectation: `queue_messages`
 * and `outbox` are shared by topic, so another producer's rows land in the same
 * counter. Meeting or exceeding it is the settle condition.
 *
 * A delta that cannot be computed at all is NOT reconciled. Unknown is not
 * settled, and treating it as settled would freeze an unknowable figure into
 * the report.
 */
export function churnReconciled(
  pair: TelemetryPair<DbTelemetrySnapshot>,
  tableName: string,
  acknowledged: number
): boolean {
  const delta = churnDeltaFor(pair, tableName)
  if (delta === null) return false
  return delta.insertedTuples >= acknowledged
}

/**
 * How many latency measurements THIS run contributed, or null when that cannot
 * be known.
 *
 * The percentiles themselves cover the backend instance's whole lifetime for
 * the topic (gap G5), so they cannot be attributed to a run. The sample count
 * can: its delta is the honest measure of how much of the reported
 * distribution this run is responsible for. Both reads must have succeeded —
 * treating a failed baseline as zero would credit the run with every sample
 * the backend had ever taken.
 */
export function latencySampleDelta(
  pair: TelemetryPair<QueueStatsSnapshot>,
  which: 'processingTime' | 'deliveryLatency'
): number | null {
  if (!pair.baseline.ok || !pair.final.ok) return null
  const after = pair.final.snapshot[which]
  if (after === undefined) return null
  const before = pair.baseline.snapshot[which]
  return after.sampleCount - (before?.sampleCount ?? 0)
}

/** The delivery-latency distribution measured at the end of a side's run. */
function finalDeliveryLatency(
  pair: TelemetryPair<QueueStatsSnapshot>
): QueueStatsSnapshot['deliveryLatency'] {
  return pair.final.ok ? pair.final.snapshot.deliveryLatency : undefined
}

/** Round to one decimal so a rate reads as a rate, not as float noise. */
function rateText(value: number): string {
  return String(Math.round(value * 10) / 10)
}

/**
 * The §19.2 summary line: which implementation sustained the load better.
 *
 * Two refusals are built in, because a comparison that overstates itself is
 * worse than no comparison:
 *
 * - **No verdict unless BOTH sides completed.** A completed run and a stopped
 *   or errored one did not carry the same load, so "which sustained it better"
 *   has no answer — only the side and its status are reported.
 * - **Delivery latency is cited only when both sides measured it.** The
 *   utilities-ui publishes and does not consume, so a queue nothing consumed
 *   produces no delivery latency at all. Presenting one side's number, or
 *   reading two absences as "equal", would both invent a result.
 */
export function compareVerdict(report: CompareReport): string {
  const incomplete = [report.native, report.outbox].find(
    (side) => side.summary.finalStatus !== 'completed'
  )
  if (incomplete !== undefined) {
    return `No verdict: the ${incomplete.side} run ended "${incomplete.summary.finalStatus}". A comparison needs both sides to complete the same load.`
  }

  const parts: string[] = [rateSentence(report.native.summary, report.outbox.summary)]

  const nativeLatency = finalDeliveryLatency(report.telemetry.stats.native)
  const outboxLatency = finalDeliveryLatency(report.telemetry.stats.outbox)
  if (nativeLatency !== undefined && outboxLatency !== undefined) {
    parts.push(
      `Delivery latency p95: native ${rateText(nativeLatency.p95Ms)} ms, outbox ${rateText(outboxLatency.p95Ms)} ms.`
    )
  } else {
    parts.push(
      'Delivery latency was not measured on both sides — nothing claimed messages from these queues during the run, so enqueue-to-claim latency is not compared.'
    )
  }

  parts.push(errorSentence(report.native.summary, report.outbox.summary))
  return parts.join(' ')
}

function rateSentence(native: RunSummary, outbox: RunSummary): string {
  const nativeRate = native.avgRate
  const outboxRate = outbox.avgRate
  const higher = Math.max(nativeRate, outboxRate)
  const withinTolerance =
    higher === 0 || Math.abs(nativeRate - outboxRate) / higher <= RATE_TIE_TOLERANCE
  if (withinTolerance) {
    return `Both sides sustained the same acknowledged rate: ${rateText(nativeRate)} msg/s native, ${rateText(outboxRate)} msg/s outbox.`
  }
  const winner: CompareSideName = nativeRate > outboxRate ? 'native' : 'outbox'
  return `${winner} sustained the higher acknowledged rate: native ${rateText(nativeRate)} msg/s against outbox ${rateText(outboxRate)} msg/s.`
}

function errorSentence(native: RunSummary, outbox: RunSummary): string {
  const nativeErrors = native.totalErrors
  const outboxErrors = outbox.totalErrors
  if (nativeErrors === 0 && outboxErrors === 0) {
    return 'Neither side recorded a batch error.'
  }
  return `Batch errors: native ${nativeErrors}, outbox ${outboxErrors}.`
}
