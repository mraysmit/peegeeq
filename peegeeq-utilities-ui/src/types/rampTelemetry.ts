/**
 * Telemetry model for rich breaking-point attribution (design §19.1 — G.1b).
 *
 * Queue and system samples are pushed by SSE and tagged with the phase that
 * was live when they arrived. Database telemetry is deliberately sampled only
 * at the run boundaries: its pg_stat counters are cumulative and heavier than
 * the one-second stream. Optional fields remain optional all the way to the UI
 * so "not measured" can never be rendered as a healthy zero.
 */
import type {
  DbTelemetrySnapshot,
  QueueStatsSnapshot,
  TableChurnDelta,
  TelemetryPair,
} from './compare'

export interface RampTelemetryTarget {
  setupId: string
  queueName: string
}

export interface DbPoolSetupSnapshot {
  setupId: string
  active: number
  idle: number
  pending: number
  total: number
}

export interface DbPoolSnapshot {
  active: number
  idle: number
  pending: number
  total: number
  perSetup: DbPoolSetupSnapshot[]
}

/** Each saturation component is independently absent until core has sampled it. */
export interface SetupSaturationSnapshot {
  setupId: string
  eventLoopLagMaxMs?: number
  eventLoopLagLatestMs?: number
  sampleCount?: number
  windowSeconds?: number
  poolAcquireWaitMaxMs?: number
  poolAcquireWaitLatestMs?: number
  poolAcquireWaitSampleCount?: number
}

/** Relevant subset of one `/sse/metrics` `metrics` event. */
export interface SystemMetricsSnapshot {
  timestamp: number
  dbPool?: DbPoolSnapshot
  saturation?: SetupSaturationSnapshot[]
  /** Worst-across-setups headlines; absent until a setup has sampled. */
  eventLoopLagMs?: number
  poolAcquireWaitMs?: number
}

export interface RampQueueTelemetrySample {
  phaseIndex: number
  snapshot: QueueStatsSnapshot
}

export interface RampSystemTelemetrySample {
  phaseIndex: number
  snapshot: SystemMetricsSnapshot
}

/** Small live projection; the complete sample arrays stay inside the collector. */
export interface RampTelemetryLive {
  queueSampleCount: number
  systemSampleCount: number
  latestQueue?: QueueStatsSnapshot
  latestSystem?: SystemMetricsSnapshot
  streamErrors: string[]
}

export interface RampTelemetryReport {
  target: RampTelemetryTarget
  queueSamples: RampQueueTelemetrySample[]
  systemSamples: RampSystemTelemetrySample[]
  database: TelemetryPair<DbTelemetrySnapshot>
  streamErrors: string[]
}

export interface RampPhaseTelemetrySummary {
  phaseIndex: number
  queueSampleCount: number
  systemSampleCount: number
  maxPendingMessages?: number
  maxMessagesPerSecond?: number
  maxEventLoopLagMs?: number
  maxPoolAcquireWaitMs?: number
  maxDbPoolActive?: number
  maxDbPoolPending?: number
}

export interface RampDatabaseAttribution {
  implementationType?: string
  tableName?: string
  churn: TableChurnDelta | null
  notifyQueueUsageBaseline: number
  notifyQueueUsageFinal: number
  locksWaitingBaseline: number
  locksWaitingFinal: number
  longestTxnSecondsBaseline?: number
  longestTxnSecondsFinal?: number
}

export type RampAttributionFindingKind =
  | 'pool-wait'
  | 'db-pool-pending'
  | 'event-loop-lag'
  | 'database-locks'
  | 'notify-backlog'
  | 'long-transaction'
  | 'dead-tuples'
  | 'sequential-scans'

export interface RampAttributionFinding {
  kind: RampAttributionFindingKind
  text: string
}
