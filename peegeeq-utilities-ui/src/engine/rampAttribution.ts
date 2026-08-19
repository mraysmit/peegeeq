/** Pure derivation for rich ramp saturation attribution (G.1b). */
import { CHURN_TABLE, churnDeltaFor } from './comparePlan'
import type { CompareSideName } from '../types/compare'
import type {
  RampAttributionFinding,
  RampDatabaseAttribution,
  RampPhaseTelemetrySummary,
  RampTelemetryReport,
  SetupSaturationSnapshot,
  SystemMetricsSnapshot,
} from '../types/rampTelemetry'

/** A visible scheduling stall rather than normal sub-tick timing noise. */
const EVENT_LOOP_EVIDENCE_MS = 10

function maxOf(values: Array<number | undefined>): number | undefined {
  const measured = values.filter((value): value is number => value !== undefined)
  return measured.length === 0 ? undefined : Math.max(...measured)
}

function setupSaturation(
  snapshot: SystemMetricsSnapshot,
  setupId: string
): SetupSaturationSnapshot | undefined {
  return snapshot.saturation?.find((entry) => entry.setupId === setupId)
}

export function summarizeRampPhases(
  report: RampTelemetryReport,
  phaseCount: number
): RampPhaseTelemetrySummary[] {
  return Array.from({ length: phaseCount }, (_, phaseIndex) => {
    const queue = report.queueSamples.filter((sample) => sample.phaseIndex === phaseIndex)
    const system = report.systemSamples.filter((sample) => sample.phaseIndex === phaseIndex)
    const pools = system
      .map((sample) => sample.snapshot.dbPool?.perSetup.find(
        (setup) => setup.setupId === report.target.setupId
      ))
      .filter((pool) => pool !== undefined)
    const saturation = system.map((sample) =>
      setupSaturation(sample.snapshot, report.target.setupId)
    )
    const summary: RampPhaseTelemetrySummary = {
      phaseIndex,
      queueSampleCount: queue.length,
      systemSampleCount: system.length,
    }
    const maxPendingMessages = maxOf(queue.map((sample) => sample.snapshot.pendingMessages))
    if (maxPendingMessages !== undefined) summary.maxPendingMessages = maxPendingMessages
    const maxMessagesPerSecond = maxOf(queue.map((sample) => sample.snapshot.messagesPerSecond))
    if (maxMessagesPerSecond !== undefined) summary.maxMessagesPerSecond = maxMessagesPerSecond
    const maxEventLoopLagMs = maxOf(saturation.map((entry) => entry?.eventLoopLagMaxMs))
    if (maxEventLoopLagMs !== undefined) summary.maxEventLoopLagMs = maxEventLoopLagMs
    const maxPoolAcquireWaitMs = maxOf(saturation.map((entry) => entry?.poolAcquireWaitMaxMs))
    if (maxPoolAcquireWaitMs !== undefined) summary.maxPoolAcquireWaitMs = maxPoolAcquireWaitMs
    const maxDbPoolActive = maxOf(pools.map((pool) => pool.active))
    if (maxDbPoolActive !== undefined) summary.maxDbPoolActive = maxDbPoolActive
    const maxDbPoolPending = maxOf(pools.map((pool) => pool.pending))
    if (maxDbPoolPending !== undefined) summary.maxDbPoolPending = maxDbPoolPending
    return summary
  })
}

function implementationType(report: RampTelemetryReport): string | undefined {
  return report.queueSamples[report.queueSamples.length - 1]?.snapshot.implementationType
}

export function databaseAttribution(
  report: RampTelemetryReport
): RampDatabaseAttribution | null {
  const { baseline, final } = report.database
  if (!baseline.ok || !final.ok) return null
  const implementation = implementationType(report)
  const side: CompareSideName | undefined =
    implementation === 'native' || implementation === 'outbox' ? implementation : undefined
  const tableName = side === undefined ? undefined : CHURN_TABLE[side]
  const attribution: RampDatabaseAttribution = {
    churn: tableName === undefined ? null : churnDeltaFor(report.database, tableName),
    notifyQueueUsageBaseline: baseline.snapshot.cluster.notifyQueueUsage,
    notifyQueueUsageFinal: final.snapshot.cluster.notifyQueueUsage,
    locksWaitingBaseline: baseline.snapshot.cluster.locksWaiting,
    locksWaitingFinal: final.snapshot.cluster.locksWaiting,
  }
  if (implementation !== undefined) attribution.implementationType = implementation
  if (tableName !== undefined) attribution.tableName = tableName
  if (baseline.snapshot.cluster.longestTxnSeconds !== undefined) {
    attribution.longestTxnSecondsBaseline = baseline.snapshot.cluster.longestTxnSeconds
  }
  if (final.snapshot.cluster.longestTxnSeconds !== undefined) {
    attribution.longestTxnSecondsFinal = final.snapshot.cluster.longestTxnSeconds
  }
  return attribution
}

function oneDecimal(value: number): string {
  return (Math.round(value * 10) / 10).toFixed(1)
}

export function attributionFindings(report: RampTelemetryReport): RampAttributionFinding[] {
  const findings: RampAttributionFinding[] = []
  const phases = summarizeRampPhases(
    report,
    Math.max(
      0,
      ...report.queueSamples.map((sample) => sample.phaseIndex + 1),
      ...report.systemSamples.map((sample) => sample.phaseIndex + 1)
    )
  )
  const maxPoolWait = maxOf(phases.map((phase) => phase.maxPoolAcquireWaitMs))
  if (maxPoolWait !== undefined && maxPoolWait > 0) {
    findings.push({
      kind: 'pool-wait',
      text: `Core measured database-pool acquisition waits up to ${oneDecimal(maxPoolWait)} ms during the ramp.`,
    })
  }
  const maxPoolPending = maxOf(phases.map((phase) => phase.maxDbPoolPending))
  if (maxPoolPending !== undefined && maxPoolPending > 0) {
    findings.push({
      kind: 'db-pool-pending',
      text: `The setup's PostgreSQL activity snapshot showed up to ${maxPoolPending} pending session${maxPoolPending === 1 ? '' : 's'}.`,
    })
  }
  const maxLag = maxOf(phases.map((phase) => phase.maxEventLoopLagMs))
  if (maxLag !== undefined && maxLag >= EVENT_LOOP_EVIDENCE_MS) {
    findings.push({
      kind: 'event-loop-lag',
      text: `The setup event loop recorded a window maximum of ${oneDecimal(maxLag)} ms (the panel flags values at or above ${EVENT_LOOP_EVIDENCE_MS} ms).`,
    })
  }

  const database = databaseAttribution(report)
  if (database !== null) {
    if (database.locksWaitingFinal > 0) {
      findings.push({
        kind: 'database-locks',
        text: `The final database snapshot had ${database.locksWaitingFinal} waiting lock${database.locksWaitingFinal === 1 ? '' : 's'} (baseline ${database.locksWaitingBaseline}).`,
      })
    }
    if (database.notifyQueueUsageFinal > 0) {
      findings.push({
        kind: 'notify-backlog',
        text: `PostgreSQL NOTIFY queue usage ended at ${(database.notifyQueueUsageFinal * 100).toFixed(2)}% (baseline ${(database.notifyQueueUsageBaseline * 100).toFixed(2)}%).`,
      })
    }
    if (database.longestTxnSecondsFinal !== undefined && database.longestTxnSecondsFinal > 0) {
      findings.push({
        kind: 'long-transaction',
        text: `The final snapshot observed a longest open transaction of ${oneDecimal(database.longestTxnSecondsFinal)} s.`,
      })
    }
    if (database.churn !== null && database.churn.deadTupleChange > 0) {
      findings.push({
        kind: 'dead-tuples',
        text: `${database.tableName ?? 'The queue table'} gained ${database.churn.deadTupleChange} estimated dead tuples over the ramp window.`,
      })
    }
    if (database.churn !== null && database.churn.seqScans > 0) {
      findings.push({
        kind: 'sequential-scans',
        text: `${database.tableName ?? 'The queue table'} recorded ${database.churn.seqScans} sequential scans over the ramp window.`,
      })
    }
  }
  return findings
}
