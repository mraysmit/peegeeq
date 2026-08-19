/**
 * Pushed telemetry collector for rich ramp attribution (G.1b).
 *
 * It joins two stateless SSE feeds while a ramp runs:
 * - the target queue's 1 Hz `/stats/stream`
 * - the system `/sse/metrics` feed, filtered to the target setup
 *
 * Database telemetry is read only at the boundaries. Every stream failure is
 * retained in the report and surfaced live; malformed data is never converted
 * into an empty/zero sample.
 */
import { getVersionedApiUrl } from './configService'
import { captureDbTelemetry, queueStatsSnapshotOf } from './telemetryService'
import type { DbTelemetrySnapshot, TelemetryCapture } from '../types/compare'
import type {
  DbPoolSetupSnapshot,
  DbPoolSnapshot,
  RampTelemetryLive,
  RampTelemetryReport,
  RampTelemetryTarget,
  SetupSaturationSnapshot,
  SystemMetricsSnapshot,
} from '../types/rampTelemetry'

export interface RampEventSource {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void
  close(): void
}

export interface RampTelemetryCallbacks {
  onLive?(live: RampTelemetryLive): void
  onError?(reason: string): void
}

export interface RampTelemetrySession {
  setPhase(index: number | null): void
  finish(): Promise<RampTelemetryReport>
  abort(): void
}

interface RampTelemetryDeps {
  openEventSource?: (url: string) => RampEventSource
  captureDbTelemetry?: (setupId: string) => Promise<TelemetryCapture<DbTelemetrySnapshot>>
}

export interface RampTelemetryCollector {
  start(
    target: RampTelemetryTarget,
    callbacks?: RampTelemetryCallbacks
  ): Promise<RampTelemetrySession>
}

function browserEventSource(url: string): RampEventSource {
  const source = new EventSource(url)
  return {
    addEventListener(type, listener) {
      source.addEventListener(type, listener as EventListener)
    },
    close() {
      source.close()
    },
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function eventData(event: MessageEvent<string>): unknown {
  if (typeof event.data !== 'string') throw new Error('event carried no JSON data')
  return JSON.parse(event.data)
}

function optionalNumber(record: Record<string, unknown>, key: string): number | undefined {
  const value = record[key]
  return typeof value === 'number' ? value : undefined
}

function dbPoolSetupOf(value: unknown): DbPoolSetupSnapshot | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as Record<string, unknown>
  if (
    typeof record.setupId !== 'string' ||
    typeof record.active !== 'number' ||
    typeof record.idle !== 'number' ||
    typeof record.pending !== 'number' ||
    typeof record.total !== 'number'
  ) return null
  return {
    setupId: record.setupId,
    active: record.active,
    idle: record.idle,
    pending: record.pending,
    total: record.total,
  }
}

function dbPoolOf(value: unknown): DbPoolSnapshot | undefined {
  if (typeof value !== 'object' || value === null) return undefined
  const record = value as Record<string, unknown>
  if (
    typeof record.active !== 'number' ||
    typeof record.idle !== 'number' ||
    typeof record.pending !== 'number' ||
    typeof record.total !== 'number' ||
    !Array.isArray(record.perSetup)
  ) return undefined
  return {
    active: record.active,
    idle: record.idle,
    pending: record.pending,
    total: record.total,
    perSetup: record.perSetup
      .map(dbPoolSetupOf)
      .filter((setup): setup is DbPoolSetupSnapshot => setup !== null),
  }
}

function saturationOf(value: unknown): SetupSaturationSnapshot | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as Record<string, unknown>
  if (typeof record.setupId !== 'string') return null
  const snapshot: SetupSaturationSnapshot = { setupId: record.setupId }
  for (const key of [
    'eventLoopLagMaxMs',
    'eventLoopLagLatestMs',
    'sampleCount',
    'windowSeconds',
    'poolAcquireWaitMaxMs',
    'poolAcquireWaitLatestMs',
    'poolAcquireWaitSampleCount',
  ] as const) {
    const number = optionalNumber(record, key)
    if (number !== undefined) snapshot[key] = number
  }
  return snapshot
}

export function systemMetricsSnapshotOf(value: unknown): SystemMetricsSnapshot {
  if (typeof value !== 'object' || value === null) {
    throw new Error(`system metrics event is not an object: ${JSON.stringify(value)}`)
  }
  const record = value as Record<string, unknown>
  if (typeof record.timestamp !== 'number') {
    throw new Error(`system metrics event has no timestamp: ${JSON.stringify(value)}`)
  }
  const snapshot: SystemMetricsSnapshot = { timestamp: record.timestamp }
  const dbPool = dbPoolOf(record.dbPool)
  if (dbPool !== undefined) snapshot.dbPool = dbPool
  if (Array.isArray(record.saturation)) {
    const saturation = record.saturation
      .map(saturationOf)
      .filter((entry): entry is SetupSaturationSnapshot => entry !== null)
    if (saturation.length > 0) snapshot.saturation = saturation
  }
  const eventLoopLagMs = optionalNumber(record, 'eventLoopLagMs')
  if (eventLoopLagMs !== undefined) snapshot.eventLoopLagMs = eventLoopLagMs
  const poolAcquireWaitMs = optionalNumber(record, 'poolAcquireWaitMs')
  if (poolAcquireWaitMs !== undefined) snapshot.poolAcquireWaitMs = poolAcquireWaitMs
  return snapshot
}

function serverErrorReason(value: unknown): string {
  if (typeof value !== 'object' || value === null) return JSON.stringify(value)
  const record = value as Record<string, unknown>
  if (typeof record.error === 'string') return record.error
  if (typeof record.message === 'string') return record.message
  return JSON.stringify(value)
}

export function createRampTelemetryCollector(
  deps: RampTelemetryDeps = {}
): RampTelemetryCollector {
  const openEventSource = deps.openEventSource ?? browserEventSource
  const captureDb = deps.captureDbTelemetry ?? captureDbTelemetry

  return {
    async start(target, callbacks = {}) {
      // Baseline completes before either the streams or publication begin, so
      // run activity cannot leak into the starting pg_stat snapshot.
      const baseline = await captureDb(target.setupId)
      const queueSamples: RampTelemetryReport['queueSamples'] = []
      const systemSamples: RampTelemetryReport['systemSamples'] = []
      const streamErrors: string[] = []
      let activePhase: number | null = null
      let latestQueue: RampTelemetryLive['latestQueue']
      let latestSystem: RampTelemetryLive['latestSystem']
      let closed = false
      let finishPromise: Promise<RampTelemetryReport> | null = null

      const publishLive = (): void => {
        const live: RampTelemetryLive = {
          queueSampleCount: queueSamples.length,
          systemSampleCount: systemSamples.length,
          streamErrors: [...streamErrors],
        }
        if (latestQueue !== undefined) live.latestQueue = latestQueue
        if (latestSystem !== undefined) live.latestSystem = latestSystem
        callbacks.onLive?.(live)
      }

      const recordError = (reason: string): void => {
        streamErrors.push(reason)
        callbacks.onError?.(reason)
        publishLive()
      }

      const queueUrl = getVersionedApiUrl(
        `queues/${encodeURIComponent(target.setupId)}/${encodeURIComponent(target.queueName)}/stats/stream?intervalMs=1000`
      )
      const systemUrl = getVersionedApiUrl('sse/metrics?interval=1')
      const queueSource = openEventSource(queueUrl)
      const systemSource = openEventSource(systemUrl)

      queueSource.addEventListener('stats', (event) => {
        if (closed || activePhase === null) return
        try {
          latestQueue = queueStatsSnapshotOf(
            eventData(event),
            `Queue stats stream for ${target.setupId}/${target.queueName}`
          )
          queueSamples.push({ phaseIndex: activePhase, snapshot: latestQueue })
          publishLive()
        } catch (error) {
          recordError(
            `Queue stats stream for ${target.setupId}/${target.queueName} could not be read: ${messageOf(error)}`
          )
        }
      })
      queueSource.addEventListener('error', (event) => {
        if (closed) return
        try {
          recordError(
            `Queue stats stream for ${target.setupId}/${target.queueName} failed: ${serverErrorReason(eventData(event))}`
          )
        } catch (error) {
          recordError(
            `Queue stats stream for ${target.setupId}/${target.queueName} was interrupted; the browser will retry: ${messageOf(error)}`
          )
        }
      })

      systemSource.addEventListener('metrics', (event) => {
        if (closed || activePhase === null) return
        try {
          latestSystem = systemMetricsSnapshotOf(eventData(event))
          systemSamples.push({ phaseIndex: activePhase, snapshot: latestSystem })
          publishLive()
        } catch (error) {
          recordError(`System metrics stream could not be read: ${messageOf(error)}`)
        }
      })
      systemSource.addEventListener('error', (event) => {
        if (closed) return
        try {
          recordError(`System metrics stream failed: ${serverErrorReason(eventData(event))}`)
        } catch (error) {
          recordError(`System metrics stream was interrupted; the browser will retry: ${messageOf(error)}`)
        }
      })

      const closeStreams = (): void => {
        if (closed) return
        closed = true
        activePhase = null
        queueSource.close()
        systemSource.close()
      }

      return {
        setPhase(index) {
          if (!closed) activePhase = index
        },
        finish() {
          if (finishPromise !== null) return finishPromise
          closeStreams()
          finishPromise = captureDb(target.setupId).then((final) => ({
            target,
            queueSamples: [...queueSamples],
            systemSamples: [...systemSamples],
            database: { baseline, final },
            streamErrors: [...streamErrors],
          }))
          return finishPromise
        },
        abort() {
          closeStreams()
        },
      }
    },
  }
}
