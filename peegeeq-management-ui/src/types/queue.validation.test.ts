import { describe, it, expect } from 'vitest'
import {
    QueueTypeSchema,
    QueueStatusSchema,
    QueueConsumerSchema,
    QueueSchema,
    QueueStatisticsSchema,
    QueueListResponseSchema,
    validateQueueListResponse,
    validateQueue,
    validateQueueStatistics,
} from './queue.validation'

// ── enum schemas ───────────────────────────────────────────────────────────────

describe('QueueTypeSchema', () => {
    it.each(['native', 'outbox', 'bitemporal'])('accepts lowercase value "%s"', (v) => {
        expect(() => QueueTypeSchema.parse(v)).not.toThrow()
    })

    it.each(['NATIVE', 'OUTBOX', 'Unknown'])('rejects invalid value "%s"', (v) => {
        expect(() => QueueTypeSchema.parse(v)).toThrow()
    })
})

describe('QueueStatusSchema', () => {
    it.each(['active', 'paused', 'idle', 'error'])('accepts lowercase value "%s"', (v) => {
        expect(() => QueueStatusSchema.parse(v)).not.toThrow()
    })

    it.each(['ACTIVE', 'PAUSED', 'running'])('rejects invalid value "%s"', (v) => {
        expect(() => QueueStatusSchema.parse(v)).toThrow()
    })
})

describe('QueueConsumerSchema', () => {
    const validConsumer = {
        id: 'c-1',
        status: 'ACTIVE',
        connectedAt: '2026-06-15T10:00:00Z',
        lastHeartbeat: '2026-06-15T10:05:00Z',
    }

    it('parses a minimal valid consumer with defaults for optional numeric fields', () => {
        const result = QueueConsumerSchema.parse(validConsumer)
        expect(result.id).toBe('c-1')
        expect(result.status).toBe('ACTIVE')
        expect(result.messagesProcessed).toBe(0)
        expect(result.errorCount).toBe(0)
    })

    it('rejects an invalid status value', () => {
        expect(() => QueueConsumerSchema.parse({ ...validConsumer, status: 'UNKNOWN' })).toThrow()
    })
})

// ── QueueSchema ────────────────────────────────────────────────────────────────

describe('QueueSchema', () => {
    const validQueue = {
        setupId: 'default',
        queueName: 'orders',
        type: 'native',
        status: 'active',
        createdAt: '2026-06-01T00:00:00Z',
        updatedAt: '2026-06-15T00:00:00Z',
    }

    it('parses a valid queue object', () => {
        const result = QueueSchema.parse(validQueue)
        expect(result.setupId).toBe('default')
        expect(result.type).toBe('native')
        expect(result.messageCount).toBe(0)
    })

    it('transforms a numeric createdAt (epoch ms) to an ISO string', () => {
        const ts = new Date('2026-06-01T00:00:00Z').getTime()
        const result = QueueSchema.parse({ ...validQueue, createdAt: ts })
        expect(result.createdAt).toBe(new Date(ts).toISOString())
    })

    it('applies default 0 for missing optional numeric counters', () => {
        const result = QueueSchema.parse(validQueue)
        expect(result.messageCount).toBe(0)
        expect(result.consumerCount).toBe(0)
        expect(result.messagesPerSecond).toBe(0)
        expect(result.errorRate).toBe(0)
    })
})

// ── QueueStatisticsSchema ──────────────────────────────────────────────────────

describe('QueueStatisticsSchema', () => {
    it('applies defaults for all missing fields', () => {
        const result = QueueStatisticsSchema.parse({})
        expect(result.messageCount).toBe(0)
        expect(result.messagesPerSecond).toBe(0)
        expect(result.consumerCount).toBe(0)
        expect(result.processingTime).toEqual({ avg: 0, p50: 0, p95: 0, p99: 0 })
        expect(result.errorRate).toBe(0)
    })

    it('preserves supplied values', () => {
        const result = QueueStatisticsSchema.parse({ messageCount: 42, errorRate: 0.05 })
        expect(result.messageCount).toBe(42)
        expect(result.errorRate).toBe(0.05)
    })
})

// ── QueueListResponseSchema ────────────────────────────────────────────────────

describe('QueueListResponseSchema', () => {
    const minimalQueue = {
        setupId: 'default',
        queueName: 'orders',
        type: 'native',
        status: 'active',
        createdAt: '2026-06-01T00:00:00Z',
        updatedAt: '2026-06-15T00:00:00Z',
    }

    it('transforms the response and uses queueCount as total when total is absent', () => {
        const result = QueueListResponseSchema.parse({ queues: [minimalQueue], queueCount: 1 })
        expect(result.queues).toHaveLength(1)
        expect(result.total).toBe(1)
        expect(result.page).toBe(1)
    })

    it('prefers explicit total over queueCount', () => {
        const result = QueueListResponseSchema.parse({
            queues: [minimalQueue], queueCount: 1, total: 99, page: 2, pageSize: 10,
        })
        expect(result.total).toBe(99)
        expect(result.page).toBe(2)
        expect(result.pageSize).toBe(10)
    })

    it('falls back to queues.length when both total and queueCount are absent', () => {
        const result = QueueListResponseSchema.parse({ queues: [minimalQueue, minimalQueue] })
        expect(result.total).toBe(2)
    })
})

// ── validateQueueListResponse ──────────────────────────────────────────────────

describe('validateQueueListResponse', () => {
    const validPayload = {
        queues: [{
            setupId: 'default', queueName: 'orders', type: 'native', status: 'active',
            createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-15T00:00:00Z',
        }],
        queueCount: 1,
    }

    it('returns parsed data for a valid payload', () => {
        const result = validateQueueListResponse(validPayload)
        expect(result.queues).toHaveLength(1)
        expect(result.total).toBe(1)
    })

    it('returns safe defaults instead of throwing for invalid input', () => {
        const result = validateQueueListResponse({ queues: [{ broken: true }] })
        expect(result.queues).toHaveLength(0)
        expect(result.total).toBe(0)
        expect(result.page).toBe(1)
        expect(result.pageSize).toBe(10)
    })

    it('returns safe defaults for completely unexpected input', () => {
        const result = validateQueueListResponse(null)
        expect(result.queues).toHaveLength(0)
    })
})

// ── validateQueue ──────────────────────────────────────────────────────────────

describe('validateQueue', () => {
    const validQueue = {
        setupId: 'default', queueName: 'orders', type: 'native', status: 'active',
        createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-15T00:00:00Z',
    }

    it('returns the parsed queue for valid input', () => {
        const result = validateQueue(validQueue)
        expect(result.queueName).toBe('orders')
    })

    it('re-throws for invalid input', () => {
        expect(() => validateQueue({ queueName: 'orders', type: 'INVALID' })).toThrow()
    })
})

// ── validateQueueStatistics ────────────────────────────────────────────────────

describe('validateQueueStatistics', () => {
    it('returns parsed stats for valid input', () => {
        const result = validateQueueStatistics({ messageCount: 5, errorRate: 0.1 })
        expect(result.messageCount).toBe(5)
        expect(result.errorRate).toBe(0.1)
    })

    it('returns safe defaults for invalid input without throwing', () => {
        const result = validateQueueStatistics('not an object')
        expect(result.messageCount).toBe(0)
        expect(result.processingTime).toEqual({ avg: 0, p50: 0, p95: 0, p99: 0 })
    })
})
