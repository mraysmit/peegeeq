/**
 * Backend telemetry reads for the Native-vs-Outbox comparison (design §19.2;
 * telemetry gaps G1, G2, G7 — Phase G.2a).
 *
 * Two endpoints, both non-destructive reads:
 *
 * - `GET /api/v1/queues/{setupId}/{queueName}/stats` — depth, throughput and
 *   the app-side latency distributions T.1/T.2 added (`processingTime*Ms`,
 *   `deliveryLatency*Ms`).
 * - `GET /api/v1/setups/{setupId}/db-telemetry` — the per-table `pg_stat_*`
 *   churn/vacuum/scan/IO/size rows and the cluster signals T.7 added.
 *
 * **Absence is preserved, at both layers.** The backend omits a latency
 * distribution when it has measured nothing, and omits `idxScan` /
 * `last*vacuum` / `longestTxnSeconds` when they do not exist. This service
 * folds the flat percentile fields into one optional object per distribution
 * and otherwise passes the payload through — it never substitutes a zero for
 * an omission. `DurationPercentiles` states the contract on the backend side
 * ("a missing distribution is represented by the ABSENCE of this object, never
 * by zeroes"); erasing it here would put the defect back.
 *
 * **Two shapes per read, deliberately.** The `get*` readers THROW on failure,
 * matching queueService/setupService ("caller is responsible for error
 * display"). The `capture*` wrappers turn a failure into a
 * {@link TelemetryCapture} carrying its reason. That is not error swallowing:
 * the caller cannot reach the data without branching on `ok`, and the reason
 * is displayed. It exists so a failed telemetry read cannot abort a comparison
 * run that is publishing real load — and, equally, cannot pass itself off as
 * an idle database.
 */
import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type {
  DbTelemetrySnapshot,
  QueueDurationPercentiles,
  QueueStatsSnapshot,
  TelemetryCapture,
} from '../types/compare'

/**
 * The `/stats` response as the backend sends it: the percentile fields are
 * FLAT and each block is present or wholly absent
 * (`QueueHandler.getQueueStats`).
 */
interface QueueStatsResponse {
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
  processingTimeP50Ms?: number
  processingTimeP95Ms?: number
  processingTimeP99Ms?: number
  processingTimeSampleCount?: number
  deliveryLatencyP50Ms?: number
  deliveryLatencyP95Ms?: number
  deliveryLatencyP99Ms?: number
  deliveryLatencySampleCount?: number
}

/**
 * Map the flat REST/SSE queue-stats payload into the UI snapshot shape.
 * Exported because `/stats` and `/stats/stream` deliberately carry the same
 * payload; two mappers would let their absence semantics drift apart.
 */
export function queueStatsSnapshotOf(value: unknown, source: string): QueueStatsSnapshot {
  const data = value as QueueStatsResponse
  if (typeof data?.queueName !== 'string') {
    throw new Error(`${source} is not a stats payload: ${JSON.stringify(value)}`)
  }
  const snapshot: QueueStatsSnapshot = {
    queueName: data.queueName,
    setupId: data.setupId,
    implementationType: data.implementationType,
    healthy: data.healthy,
    totalMessages: data.totalMessages,
    pendingMessages: data.pendingMessages,
    inFlightMessages: data.inFlightMessages,
    processedMessages: data.processedMessages,
    deadLetteredMessages: data.deadLetteredMessages,
    messagesPerSecond: data.messagesPerSecond,
    avgProcessingTimeMs: data.avgProcessingTimeMs,
    successRatePercent: data.successRatePercent,
    timestamp: data.timestamp,
  }
  const processingTime = percentilesOf(
    data.processingTimeP50Ms,
    data.processingTimeP95Ms,
    data.processingTimeP99Ms,
    data.processingTimeSampleCount
  )
  if (processingTime !== undefined) snapshot.processingTime = processingTime
  const deliveryLatency = percentilesOf(
    data.deliveryLatencyP50Ms,
    data.deliveryLatencyP95Ms,
    data.deliveryLatencyP99Ms,
    data.deliveryLatencySampleCount
  )
  if (deliveryLatency !== undefined) snapshot.deliveryLatency = deliveryLatency
  return snapshot
}

/**
 * One distribution, or undefined when the backend did not report it.
 *
 * A PARTIALLY reported block is treated as absent: reporting it would mean
 * inventing the missing percentiles, and a half-built distribution is not a
 * distribution. The backend sends all four fields together or none of them, so
 * this branch only fires on a contract change — which is exactly when a silent
 * fabrication would do the most damage.
 */
function percentilesOf(
  p50Ms: number | undefined,
  p95Ms: number | undefined,
  p99Ms: number | undefined,
  sampleCount: number | undefined
): QueueDurationPercentiles | undefined {
  if (
    typeof p50Ms !== 'number' ||
    typeof p95Ms !== 'number' ||
    typeof p99Ms !== 'number' ||
    typeof sampleCount !== 'number'
  ) {
    return undefined
  }
  return { p50Ms, p95Ms, p99Ms, sampleCount }
}

/**
 * Read one queue's statistics snapshot.
 *
 * GET /api/v1/queues/{setupId}/{queueName}/stats
 *
 * Throws on non-2xx and on a response that is not a stats payload; the caller
 * decides what a failure means.
 */
export async function getQueueStats(
  setupId: string,
  queueName: string
): Promise<QueueStatsSnapshot> {
  const res = await axios.get<QueueStatsResponse>(
    getVersionedApiUrl(`queues/${setupId}/${queueName}/stats`)
  )
  return queueStatsSnapshotOf(res.data, `Queue stats response for ${setupId}/${queueName}`)
}

/**
 * Read one database-level telemetry snapshot for a setup.
 *
 * GET /api/v1/setups/{setupId}/db-telemetry
 *
 * The payload's field names already match {@link DbTelemetrySnapshot}, so it
 * passes through unchanged — including every omitted optional. Throws on
 * non-2xx (404 unknown setup, 503 query failure) and on a malformed payload.
 */
export async function getDbTelemetry(setupId: string): Promise<DbTelemetrySnapshot> {
  const res = await axios.get<DbTelemetrySnapshot>(
    getVersionedApiUrl(`setups/${setupId}/db-telemetry`)
  )
  const data = res.data
  if (!Array.isArray(data?.tables) || typeof data?.cluster !== 'object' || data.cluster === null) {
    throw new Error(
      `Database telemetry response for ${setupId} is missing tables/cluster: ${JSON.stringify(data)}`
    )
  }
  return data
}

/** The message an error carries, whatever was thrown. */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/**
 * Read one queue's stats, recording a failure as its reason.
 *
 * The reason names the queue: with two sides in one comparison, an
 * unattributed error cannot be acted on.
 */
export async function captureQueueStats(
  setupId: string,
  queueName: string
): Promise<TelemetryCapture<QueueStatsSnapshot>> {
  try {
    return { ok: true, snapshot: await getQueueStats(setupId, queueName) }
  } catch (error) {
    return {
      ok: false,
      error: `Queue stats for ${setupId}/${queueName} could not be read: ${messageOf(error)}`,
    }
  }
}

/**
 * Read a setup's database telemetry, recording a failure as its reason.
 *
 * The reason names the setup: a comparison spanning two setups gets two reads,
 * and either can fail on its own.
 */
export async function captureDbTelemetry(
  setupId: string
): Promise<TelemetryCapture<DbTelemetrySnapshot>> {
  try {
    return { ok: true, snapshot: await getDbTelemetry(setupId) }
  } catch (error) {
    return {
      ok: false,
      error: `Database telemetry for ${setupId} could not be read: ${messageOf(error)}`,
    }
  }
}
