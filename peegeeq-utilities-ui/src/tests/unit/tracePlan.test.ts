/**
 * Tests for tracePlan.ts (design §19.6 — Phase G.6).
 *
 * Contract under test (written FIRST):
 * - assignments are DETERMINISTIC in (settings, runId, index, rate,
 *   maxBatchSize): the report is derived after the run from the same function
 *   the engine used per message, so the two can never disagree
 * - per-run mints ONE id; every-n mints a new id every n messages
 * - per-batch follows the ENGINE'S real batch boundaries: per tick the quota
 *   (rate) is split into groups of ≤ maxBatchSize, and the remainder batch
 *   ends at the tick edge — floor(index / maxBatchSize) would be wrong when
 *   rate is not a multiple of maxBatchSize
 * - ids are UUID-shaped and distinct across groups; a different runId mints
 *   different ids
 * - causation chains are an ID SCHEME: groups are organized into chains of
 *   (1 root + childrenPerParent); children reference the chain root; NOTHING
 *   about chains changes the id a message carries
 * - buildTraceReport aggregates entries (id, role, parent, first id, count)
 *   and the chain count, and equals the per-message assignments
 */
import { describe, it, expect } from 'vitest'
import { buildTraceReport, traceFor } from '../../engine/tracePlan'
import type { TraceSettings } from '../../types/trace'

const RUN_ID = 'run-fixed-1'
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function settings(overrides: Partial<TraceSettings> = {}): TraceSettings {
  return {
    correlation: { kind: 'every-n', n: 100 },
    causation: { enabled: false, childrenPerParent: 3 },
    ...overrides,
  }
}

describe('traceFor — determinism and id shape', () => {
  it('returns identical assignments for identical inputs', () => {
    const config = settings()

    const first = traceFor(config, RUN_ID, 250, 50, 10)
    const second = traceFor(config, RUN_ID, 250, 50, 10)

    expect(second).toEqual(first)
  })

  it('mints UUID-shaped ids', () => {
    const { correlationId } = traceFor(settings(), RUN_ID, 0, 50, 10)

    expect(correlationId).toMatch(UUID_SHAPE)
  })

  it('a different runId mints different ids', () => {
    const config = settings()

    const a = traceFor(config, 'run-a', 0, 50, 10).correlationId
    const b = traceFor(config, 'run-b', 0, 50, 10).correlationId

    expect(a).not.toBe(b)
  })
})

describe('traceFor — correlation strategies', () => {
  it('per-run gives every message the same id', () => {
    const config = settings({ correlation: { kind: 'per-run' } })

    const ids = [0, 1, 500, 9999].map((i) => traceFor(config, RUN_ID, i, 50, 10).correlationId)

    expect(new Set(ids).size).toBe(1)
  })

  it('every-n mints a new id at each n-message boundary', () => {
    const config = settings({ correlation: { kind: 'every-n', n: 3 } })

    const ids = Array.from({ length: 9 }, (_, i) => traceFor(config, RUN_ID, i, 50, 10).correlationId)

    expect(ids[0]).toBe(ids[1])
    expect(ids[1]).toBe(ids[2])
    expect(ids[3]).toBe(ids[5])
    expect(ids[6]).toBe(ids[8])
    expect(new Set(ids).size).toBe(3)
  })

  it('per-batch follows the engine batch boundaries, including the tick-edge remainder', () => {
    // rate 25, maxBatchSize 10: each tick fires batches of 10, 10, 5. The
    // remainder batch ENDS at the tick edge — message 25 (next tick) starts
    // batch 3, not "the rest of batch 2". floor(index / 10) would merge them.
    const config = settings({ correlation: { kind: 'per-batch' } })
    const id = (index: number) => traceFor(config, RUN_ID, index, 25, 10).correlationId

    expect(id(0)).toBe(id(9)) // batch 0
    expect(id(10)).toBe(id(19)) // batch 1
    expect(id(20)).toBe(id(24)) // batch 2 (remainder, size 5)
    expect(id(9)).not.toBe(id(10))
    expect(id(24)).not.toBe(id(25)) // tick edge: 25 opens batch 3
    expect(id(25)).toBe(id(34)) // batch 3 is a full 10 again
  })
})

describe('traceFor — causation chains (id scheme only)', () => {
  const config = settings({
    correlation: { kind: 'every-n', n: 2 },
    causation: { enabled: true, childrenPerParent: 3 },
  })

  it('organizes groups into chains of one root plus childrenPerParent', () => {
    // Groups (every 2 messages): 0 1 2 3 | 4 5 6 7 — chain size 4.
    const root = traceFor(config, RUN_ID, 0, 50, 10)
    const child1 = traceFor(config, RUN_ID, 2, 50, 10)
    const child3 = traceFor(config, RUN_ID, 6, 50, 10)
    const nextRoot = traceFor(config, RUN_ID, 8, 50, 10)

    expect(root.parentCorrelationId).toBeUndefined()
    expect(child1.parentCorrelationId).toBe(root.correlationId)
    expect(child3.parentCorrelationId).toBe(root.correlationId)
    expect(nextRoot.parentCorrelationId).toBeUndefined()
    expect(nextRoot.correlationId).not.toBe(root.correlationId)
  })

  it('enabling chains does not change which id a message carries', () => {
    const withChains = config
    const withoutChains = settings({ correlation: { kind: 'every-n', n: 2 } })

    for (const index of [0, 2, 4, 6, 8]) {
      expect(traceFor(withChains, RUN_ID, index, 50, 10).correlationId).toBe(
        traceFor(withoutChains, RUN_ID, index, 50, 10).correlationId
      )
    }
  })
})

describe('buildTraceReport', () => {
  it('aggregates one entry per minted id with first message id and count', () => {
    const config = settings({ correlation: { kind: 'every-n', n: 5 } })

    const report = buildTraceReport(config, RUN_ID, 12, 50, 10)

    expect(report.runId).toBe(RUN_ID)
    expect(report.totalMessages).toBe(12)
    // 12 messages, new id every 5: groups of 5, 5, 2.
    expect(report.entries).toHaveLength(3)
    expect(report.entries.map((e) => e.firstMessageId)).toEqual([1, 6, 11])
    expect(report.entries.map((e) => e.messageCount)).toEqual([5, 5, 2])
  })

  it('entries equal the per-message assignments — one function, no drift', () => {
    const config = settings({
      correlation: { kind: 'every-n', n: 3 },
      causation: { enabled: true, childrenPerParent: 1 },
    })

    const report = buildTraceReport(config, RUN_ID, 12, 50, 10)

    report.entries.forEach((entry) => {
      const assignment = traceFor(config, RUN_ID, entry.firstMessageId - 1, 50, 10)
      expect(entry.correlationId).toBe(assignment.correlationId)
      expect(entry.parentCorrelationId).toBe(assignment.parentCorrelationId)
      expect(entry.role).toBe(assignment.parentCorrelationId === undefined ? 'root' : 'child')
    })
  })

  it('reports the chain count from the design mock arithmetic', () => {
    // §19.6: "1,200 messages under 12 correlation ids / 3 causation chains" —
    // every 100 msgs, 3 children per parent → 12 ids in chains of 4.
    const config = settings({
      correlation: { kind: 'every-n', n: 100 },
      causation: { enabled: true, childrenPerParent: 3 },
    })

    const report = buildTraceReport(config, RUN_ID, 1200, 50, 10)

    expect(report.entries).toHaveLength(12)
    expect(report.chainCount).toBe(3)
    expect(report.entries.filter((e) => e.role === 'root')).toHaveLength(3)
    expect(report.entries.filter((e) => e.role === 'child')).toHaveLength(9)
  })

  it('without chains every entry is a root and chainCount equals the id count', () => {
    const config = settings({ correlation: { kind: 'every-n', n: 4 } })

    const report = buildTraceReport(config, RUN_ID, 8, 50, 10)

    expect(report.entries.every((e) => e.role === 'root')).toBe(true)
    expect(report.chainCount).toBe(2)
  })

  it('a zero count yields an empty report', () => {
    const report = buildTraceReport(settings(), RUN_ID, 0, 50, 10)

    expect(report.entries).toEqual([])
    expect(report.totalMessages).toBe(0)
    expect(report.chainCount).toBe(0)
  })
})
