/** Pure attribution tests for rich breaking-point ramps (G.1b). */
import { describe, expect, it } from 'vitest'
import {
  attributionFindings,
  databaseAttribution,
  summarizeRampPhases,
} from '../../engine/rampAttribution'
import type { DbTableStats, DbTelemetrySnapshot } from '../../types/compare'
import type { RampTelemetryReport } from '../../types/rampTelemetry'

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

function db(sampledAt: number, tables: DbTableStats[], overrides: Partial<DbTelemetrySnapshot['cluster']> = {}): DbTelemetrySnapshot {
  return {
    setupId: 's1', databaseName: 'db', schema: 'public', sampledAt, tables,
    cluster: {
      backendsHoldingXmin: 0, locksTotal: 1, locksWaiting: 0, xidAge: 1,
      notifyQueueUsage: 0, walRecords: 0, walBytes: 0, walLsnBytes: 0,
      checkpointsTimed: 0, checkpointsRequested: 0, buffersCheckpoint: 0,
      xactCommit: 0, xactRollback: 0, deadlocks: 0, tupReturned: 0,
      tupFetched: 0, numbackends: 1, blksHit: 0, blksRead: 0, ...overrides,
    },
  }
}

function report(): RampTelemetryReport {
  return {
    target: { setupId: 's1', queueName: 'orders' },
    queueSamples: [
      {
        phaseIndex: 0,
        snapshot: {
          queueName: 'orders', setupId: 's1', implementationType: 'native', healthy: true,
          totalMessages: 2, pendingMessages: 2, inFlightMessages: 0, processedMessages: 0,
          deadLetteredMessages: 0, messagesPerSecond: 5, avgProcessingTimeMs: 0,
          successRatePercent: 100, timestamp: 1,
        },
      },
      {
        phaseIndex: 1,
        snapshot: {
          queueName: 'orders', setupId: 's1', implementationType: 'native', healthy: true,
          totalMessages: 12, pendingMessages: 12, inFlightMessages: 0, processedMessages: 0,
          deadLetteredMessages: 0, messagesPerSecond: 9, avgProcessingTimeMs: 0,
          successRatePercent: 100, timestamp: 2,
        },
      },
    ],
    systemSamples: [
      {
        phaseIndex: 0,
        snapshot: {
          timestamp: 1,
          dbPool: { active: 1, idle: 1, pending: 0, total: 2,
            perSetup: [{ setupId: 's1', active: 1, idle: 1, pending: 0, total: 2 }] },
          saturation: [{ setupId: 's1', eventLoopLagMaxMs: 3 }],
        },
      },
      {
        phaseIndex: 1,
        snapshot: {
          timestamp: 2,
          dbPool: { active: 4, idle: 0, pending: 1, total: 5,
            perSetup: [{ setupId: 's1', active: 4, idle: 0, pending: 1, total: 5 }] },
          saturation: [{ setupId: 's1', eventLoopLagMaxMs: 40, poolAcquireWaitMaxMs: 25 }],
        },
      },
    ],
    database: {
      baseline: { ok: true, snapshot: db(1, [table({ nTupIns: 10, nDeadTup: 2 })]) },
      final: {
        ok: true,
        snapshot: db(2, [table({ nTupIns: 40, nDeadTup: 12, seqScan: 7, totalBytes: 4096 })], {
          locksWaiting: 2, notifyQueueUsage: 0.25, longestTxnSeconds: 8,
        }),
      },
    },
    streamErrors: [],
  }
}

describe('rampAttribution', () => {
  it('summarizes pushed samples per phase without filling absent values', () => {
    const summaries = summarizeRampPhases(report(), 3)
    expect(summaries).toHaveLength(3)
    expect(summaries[1]).toMatchObject({
      queueSampleCount: 1,
      systemSampleCount: 1,
      maxPendingMessages: 12,
      maxMessagesPerSecond: 9,
      maxEventLoopLagMs: 40,
      maxPoolAcquireWaitMs: 25,
      maxDbPoolPending: 1,
    })
    expect(summaries[2].maxEventLoopLagMs).toBeUndefined()
    expect(summaries[2].maxPendingMessages).toBeUndefined()
  })

  it('derives database deltas from the implementation table and cluster boundary samples', () => {
    const attribution = databaseAttribution(report())
    expect(attribution).not.toBeNull()
    expect(attribution?.tableName).toBe('queue_messages')
    expect(attribution?.churn?.insertedTuples).toBe(30)
    expect(attribution?.churn?.deadTupleChange).toBe(10)
    expect(attribution?.notifyQueueUsageFinal).toBe(0.25)
    expect(attribution?.locksWaitingFinal).toBe(2)
    expect(attribution?.longestTxnSecondsFinal).toBe(8)
  })

  it('names observed pressure evidence without claiming it caused the knee', () => {
    const findings = attributionFindings(report())
    expect(findings.map((finding) => finding.kind)).toEqual(
      expect.arrayContaining(['pool-wait', 'event-loop-lag', 'database-locks', 'notify-backlog'])
    )
    expect(findings.every((finding) => !finding.text.toLowerCase().includes('caused'))).toBe(true)
  })

  it('returns unknown database attribution after a failed boundary read', () => {
    const value = report()
    value.database.final = { ok: false, error: 'Database telemetry for s1 could not be read: 503' }
    expect(databaseAttribution(value)).toBeNull()
    expect(attributionFindings(value).map((finding) => finding.kind)).not.toContain('database-locks')
  })
})
