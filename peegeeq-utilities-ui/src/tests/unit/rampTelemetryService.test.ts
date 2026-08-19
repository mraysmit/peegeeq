/**
 * Tests for the pushed telemetry collector used by rich ramp attribution
 * (design §19.1 — G.1b).
 *
 * No module mocks: the browser EventSource boundary and database capture read
 * are injected, following profileRunner's real-fake pattern. The collector's
 * parsing, phase attribution, lifecycle and error recording all run for real.
 */
import { describe, expect, it } from 'vitest'
import { createRampTelemetryCollector } from '../../services/rampTelemetryService'
import type { DbTelemetrySnapshot, TelemetryCapture } from '../../types/compare'
import type { RampEventSource } from '../../services/rampTelemetryService'

class FakeEventSource implements RampEventSource {
  readonly listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>()
  closed = false

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener)
    this.listeners.set(type, listeners)
  }

  close(): void {
    this.closed = true
  }

  emit(type: string, data: unknown): void {
    const event = { data: JSON.stringify(data) } as MessageEvent<string>
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

function dbSnapshot(sampledAt: number): DbTelemetrySnapshot {
  return {
    setupId: 'setup-1',
    databaseName: 'db-1',
    schema: 'public',
    sampledAt,
    tables: [],
    cluster: {
      backendsHoldingXmin: 0,
      locksTotal: 1,
      locksWaiting: 0,
      xidAge: 10,
      notifyQueueUsage: 0,
      walRecords: 1,
      walBytes: 10,
      walLsnBytes: 10,
      checkpointsTimed: 0,
      checkpointsRequested: 0,
      buffersCheckpoint: 0,
      xactCommit: 1,
      xactRollback: 0,
      deadlocks: 0,
      tupReturned: 1,
      tupFetched: 1,
      numbackends: 1,
      blksHit: 1,
      blksRead: 0,
    },
  }
}

const QUEUE_STATS = {
  queueName: 'orders',
  setupId: 'setup-1',
  implementationType: 'native',
  healthy: true,
  totalMessages: 12,
  pendingMessages: 4,
  inFlightMessages: 0,
  processedMessages: 8,
  deadLetteredMessages: 0,
  messagesPerSecond: 8,
  avgProcessingTimeMs: 2,
  successRatePercent: 100,
  timestamp: 1000,
}

describe('rampTelemetryService', () => {
  it('opens both pushed streams after the baseline and attributes their samples to the live phase', async () => {
    const sources: Array<{ url: string; source: FakeEventSource }> = []
    const captures: number[] = []
    const liveUpdates: number[] = []
    const collector = createRampTelemetryCollector({
      openEventSource: (url) => {
        const source = new FakeEventSource()
        sources.push({ url, source })
        return source
      },
      captureDbTelemetry: async (): Promise<TelemetryCapture<DbTelemetrySnapshot>> => {
        captures.push(captures.length + 1)
        return { ok: true, snapshot: dbSnapshot(captures.length) }
      },
    })

    const session = await collector.start(
      { setupId: 'setup-1', queueName: 'orders' },
      { onLive: (live) => liveUpdates.push(live.queueSampleCount + live.systemSampleCount) }
    )

    expect(captures).toHaveLength(1)
    expect(sources).toHaveLength(2)
    const queue = sources.find(({ url }) => url.includes('/stats/stream'))?.source
    const system = sources.find(({ url }) => url.includes('/sse/metrics'))?.source
    expect(queue).toBeTruthy()
    expect(system).toBeTruthy()

    session.setPhase(2)
    queue?.emit('stats', QUEUE_STATS)
    system?.emit('metrics', {
      timestamp: 1001,
      dbPool: {
        active: 2,
        idle: 1,
        pending: 0,
        total: 3,
        perSetup: [{ setupId: 'setup-1', active: 2, idle: 1, pending: 0, total: 3 }],
      },
      saturation: [
        {
          setupId: 'setup-1',
          eventLoopLagMaxMs: 12,
          eventLoopLagLatestMs: 3,
          sampleCount: 5,
          windowSeconds: 60,
          poolAcquireWaitMaxMs: 7,
          poolAcquireWaitLatestMs: 2,
          poolAcquireWaitSampleCount: 2,
        },
      ],
    })

    expect(liveUpdates).toEqual([1, 2])
    const report = await session.finish()
    expect(report.queueSamples).toHaveLength(1)
    expect(report.queueSamples[0].phaseIndex).toBe(2)
    expect(report.queueSamples[0].snapshot.pendingMessages).toBe(4)
    expect(report.systemSamples).toHaveLength(1)
    expect(report.systemSamples[0].phaseIndex).toBe(2)
    expect(report.systemSamples[0].snapshot.saturation?.[0].poolAcquireWaitMaxMs).toBe(7)
    expect(captures).toHaveLength(2)
    expect(sources.every(({ source }) => source.closed)).toBe(true)
  })

  it('preserves absent saturation fields instead of fabricating zeroes', async () => {
    const sources: Array<{ url: string; source: FakeEventSource }> = []
    const collector = createRampTelemetryCollector({
      openEventSource: (url) => {
        const source = new FakeEventSource()
        sources.push({ url, source })
        return source
      },
      captureDbTelemetry: async () => ({ ok: true, snapshot: dbSnapshot(1) }),
    })
    const session = await collector.start({ setupId: 'setup-1', queueName: 'orders' })
    session.setPhase(0)
    sources.find(({ url }) => url.includes('/sse/metrics'))?.source.emit('metrics', {
      timestamp: 1001,
      dbPool: { active: 0, idle: 0, pending: 0, total: 0, perSetup: [] },
    })

    const report = await session.finish()
    expect(report.systemSamples[0].snapshot.saturation).toBeUndefined()
    expect(report.systemSamples[0].snapshot.eventLoopLagMs).toBeUndefined()
    expect(report.systemSamples[0].snapshot.poolAcquireWaitMs).toBeUndefined()
  })

  it('records malformed and server-declared stream errors with their source', async () => {
    const sources: Array<{ url: string; source: FakeEventSource }> = []
    const surfaced: string[] = []
    const collector = createRampTelemetryCollector({
      openEventSource: (url) => {
        const source = new FakeEventSource()
        sources.push({ url, source })
        return source
      },
      captureDbTelemetry: async () => ({ ok: true, snapshot: dbSnapshot(1) }),
    })
    const session = await collector.start(
      { setupId: 'setup-1', queueName: 'orders' },
      { onError: (reason) => surfaced.push(reason) }
    )
    session.setPhase(0)
    sources.find(({ url }) => url.includes('/stats/stream'))?.source.emit('stats', { nope: true })
    sources.find(({ url }) => url.includes('/stats/stream'))?.source.emit('error', {
      error: 'queue disappeared',
    })

    const report = await session.finish()
    expect(surfaced).toHaveLength(2)
    expect(surfaced[0]).toMatch(/queue stats stream/i)
    expect(surfaced[1]).toContain('queue disappeared')
    expect(report.streamErrors).toEqual(surfaced)
  })
})
