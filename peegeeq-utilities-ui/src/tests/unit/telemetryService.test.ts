/**
 * Tests for telemetryService.ts (design §19.2 / telemetry G1, G2, G7 —
 * Phase G.2a).
 *
 * Contract under test (written before the module):
 * - queue stats are read from GET /queues/{setupId}/{queueName}/stats and the
 *   FLAT percentile fields are folded into one optional object per
 *   distribution, so the backend's absence contract survives the mapping
 * - a distribution the backend omitted stays ABSENT — never zeroes, which
 *   would claim a 0 ms tail was measured
 * - database telemetry is read from GET /setups/{setupId}/db-telemetry, with
 *   the optional per-table stamps and idxScan preserved as absent
 * - the raw readers THROW on failure (the queueService/setupService contract);
 *   the capture wrappers record the reason instead, so one failed telemetry
 *   read cannot abort a comparison run OR silently read as an idle database
 *
 * Uses real axios with vi.mock to intercept at the network boundary only —
 * the queueService.test.ts pattern. No business logic is mocked.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import {
  captureDbTelemetry,
  captureQueueStats,
  getDbTelemetry,
  getQueueStats,
} from '../../services/telemetryService'

vi.mock('axios')
const mockedAxios = vi.mocked(axios, true)

/** The stats payload QueueHandler.getQueueStats always sends. */
const BASE_STATS = {
  queueName: 'orders',
  setupId: 'demo-setup',
  implementationType: 'native',
  healthy: true,
  totalMessages: 120,
  pendingMessages: 4,
  processedMessages: 116,
  inFlightMessages: 0,
  deadLetteredMessages: 0,
  messagesPerSecond: 12.5,
  avgProcessingTimeMs: 3.25,
  successRatePercent: 100,
  timestamp: 1783258014287,
}

/** The db-telemetry payload DatabaseTelemetryHandler sends. */
const BASE_CLUSTER = {
  backendsHoldingXmin: 2,
  locksTotal: 14,
  locksWaiting: 0,
  xidAge: 731,
  walRecords: 900,
  walBytes: 81920,
  walLsnBytes: 167772160,
  checkpointsTimed: 3,
  checkpointsRequested: 0,
  buffersCheckpoint: 44,
  xactCommit: 5000,
  xactRollback: 1,
  deadlocks: 0,
  tupReturned: 90000,
  tupFetched: 4000,
  numbackends: 6,
  blksHit: 120000,
  blksRead: 900,
}

describe('telemetryService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ── getQueueStats ─────────────────────────────────────────────────────────

  describe('getQueueStats', () => {
    it('GETs /queues/{setupId}/{queueName}/stats', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: BASE_STATS })

      await getQueueStats('demo-setup', 'orders')

      expect(mockedAxios.get).toHaveBeenCalledWith(
        expect.stringContaining('/queues/demo-setup/orders/stats')
      )
    })

    it('carries the always-present counters through unchanged', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: BASE_STATS })

      const stats = await getQueueStats('demo-setup', 'orders')

      expect(stats.implementationType).toBe('native')
      expect(stats.pendingMessages).toBe(4)
      expect(stats.messagesPerSecond).toBe(12.5)
      expect(stats.successRatePercent).toBe(100)
    })

    it('folds the flat processing-time fields into one distribution object', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          ...BASE_STATS,
          processingTimeP50Ms: 2.5,
          processingTimeP95Ms: 8,
          processingTimeP99Ms: 19,
          processingTimeSampleCount: 640,
        },
      })

      const stats = await getQueueStats('demo-setup', 'orders')

      expect(stats.processingTime).toEqual({ p50Ms: 2.5, p95Ms: 8, p99Ms: 19, sampleCount: 640 })
    })

    it('folds the flat delivery-latency fields into one distribution object', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          ...BASE_STATS,
          deliveryLatencyP50Ms: 1,
          deliveryLatencyP95Ms: 4,
          deliveryLatencyP99Ms: 11,
          deliveryLatencySampleCount: 12000,
        },
      })

      const stats = await getQueueStats('demo-setup', 'orders')

      expect(stats.deliveryLatency).toEqual({ p50Ms: 1, p95Ms: 4, p99Ms: 11, sampleCount: 12000 })
    })

    it('leaves an omitted distribution ABSENT rather than zeroing it', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: BASE_STATS })

      const stats = await getQueueStats('demo-setup', 'orders')

      // The backend omits these when nothing has been measured. A zeroed
      // object here would claim a 0 ms tail was observed.
      expect(stats.processingTime).toBeUndefined()
      expect(stats.deliveryLatency).toBeUndefined()
    })

    it('treats a partially reported distribution as absent', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: { ...BASE_STATS, deliveryLatencyP95Ms: 4 },
      })

      const stats = await getQueueStats('demo-setup', 'orders')

      // Half a distribution cannot be reported as a distribution; the missing
      // percentiles would have to be invented.
      expect(stats.deliveryLatency).toBeUndefined()
    })

    it('propagates a failure to the caller', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Request failed with status code 404'))

      await expect(getQueueStats('demo-setup', 'orders')).rejects.toThrow(/404/)
    })
  })

  // ── getDbTelemetry ────────────────────────────────────────────────────────

  describe('getDbTelemetry', () => {
    it('GETs /setups/{setupId}/db-telemetry', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          setupId: 'demo-setup',
          databaseName: 'demo_setup_db',
          schema: 'public',
          sampledAt: 1783258014287,
          tables: [],
          cluster: BASE_CLUSTER,
        },
      })

      await getDbTelemetry('demo-setup')

      expect(mockedAxios.get).toHaveBeenCalledWith(
        expect.stringContaining('/setups/demo-setup/db-telemetry')
      )
    })

    it('carries per-table churn rows and the cluster block through', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          setupId: 'demo-setup',
          databaseName: 'demo_setup_db',
          schema: 'public',
          sampledAt: 1783258014287,
          tables: [
            {
              tableName: 'queue_messages',
              nTupIns: 12000,
              nTupUpd: 0,
              nTupDel: 11800,
              nTupHotUpd: 0,
              nLiveTup: 200,
              nDeadTup: 11800,
              seqScan: 1450,
              idxScan: 60,
              vacuumCount: 0,
              autovacuumCount: 2,
              heapBlksHit: 88000,
              heapBlksRead: 120,
              heapBytes: 40960,
              indexBytes: 16384,
              totalBytes: 57344,
              lastAutovacuum: '2026-08-06T09:15:00Z',
            },
          ],
          cluster: { ...BASE_CLUSTER, longestTxnSeconds: 12 },
        },
      })

      const snapshot = await getDbTelemetry('demo-setup')

      expect(snapshot.tables).toHaveLength(1)
      expect(snapshot.tables[0].tableName).toBe('queue_messages')
      expect(snapshot.tables[0].nDeadTup).toBe(11800)
      expect(snapshot.tables[0].lastAutovacuum).toBe('2026-08-06T09:15:00Z')
      expect(snapshot.cluster.longestTxnSeconds).toBe(12)
      expect(snapshot.cluster.locksWaiting).toBe(0)
    })

    it('keeps omitted optional fields absent', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          setupId: 'demo-setup',
          databaseName: 'demo_setup_db',
          schema: 'public',
          sampledAt: 1,
          tables: [
            {
              tableName: 'processed_ledger',
              nTupIns: 0,
              nTupUpd: 0,
              nTupDel: 0,
              nTupHotUpd: 0,
              nLiveTup: 0,
              nDeadTup: 0,
              seqScan: 0,
              vacuumCount: 0,
              autovacuumCount: 0,
              heapBlksHit: 0,
              heapBlksRead: 0,
              heapBytes: 0,
              indexBytes: 0,
              totalBytes: 0,
            },
          ],
          cluster: BASE_CLUSTER,
        },
      })

      const snapshot = await getDbTelemetry('demo-setup')

      // "no index exists" and "no transaction in progress" are facts the
      // backend states by omission; a 0 here would be a different claim.
      expect(snapshot.tables[0].idxScan).toBeUndefined()
      expect(snapshot.tables[0].lastAutovacuum).toBeUndefined()
      expect(snapshot.cluster.longestTxnSeconds).toBeUndefined()
    })

    it('propagates a failure to the caller', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Request failed with status code 503'))

      await expect(getDbTelemetry('demo-setup')).rejects.toThrow(/503/)
    })
  })

  // ── capture wrappers ──────────────────────────────────────────────────────

  describe('capture wrappers', () => {
    it('captureQueueStats returns the snapshot on success', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: BASE_STATS })

      const capture = await captureQueueStats('demo-setup', 'orders')

      expect(capture.ok).toBe(true)
      expect(capture.ok && capture.snapshot.queueName).toBe('orders')
    })

    it('captureQueueStats records the failure REASON instead of throwing', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Request failed with status code 503'))

      const capture = await captureQueueStats('demo-setup', 'orders')

      // A comparison run must not be aborted by a telemetry read — but the
      // failure must survive as a stated reason, never as a zeroed snapshot.
      expect(capture.ok).toBe(false)
      expect(capture.ok === false && capture.error).toMatch(/503/)
    })

    it('captureDbTelemetry records the failure REASON instead of throwing', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Network Error'))

      const capture = await captureDbTelemetry('demo-setup')

      expect(capture.ok).toBe(false)
      expect(capture.ok === false && capture.error).toMatch(/Network Error/)
    })

    it('captureDbTelemetry names the setup it failed for', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Network Error'))

      const capture = await captureDbTelemetry('demo-setup')

      // With two setups in one comparison, an unattributed error is useless.
      expect(capture.ok === false && capture.error).toContain('demo-setup')
    })
  })
})
