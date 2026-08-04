/**
 * Tests for exerciserPlan.ts (design §19.5 — Phase G.5-send).
 *
 * Contract under test (written FIRST):
 * - assignments are DETERMINISTIC in (settings, runId, index): the manifest is
 *   derived after the run from the same function the engine used per message,
 *   so the two can never disagree
 * - delay: fixed is constant; random stays within [0, maxSeconds] and varies
 *   across indices; per-index-ramp grows by stepSeconds and caps at maxSeconds
 * - priority: fixed is constant; round-robin cycles 1..10 by index
 * - group: single is constant; round-robin cycles grp-0..grp-(n-1); per-key
 *   picks a member of the named value list deterministically
 * - per-key with a missing or empty list THROWS naming the list — assigning a
 *   fabricated group would report FIFO ordering the run never exercised
 * - buildManifest returns one entry per message, ids 1-based, each entry equal
 *   to assignmentFor at that index
 */
import { describe, it, expect } from 'vitest'
import { assignmentFor, buildManifest } from '../../engine/exerciserPlan'
import type { ExerciserSettings } from '../../types/exerciser'

const RUN_ID = 'run-fixed-1'
const NO_LISTS: Record<string, string[]> = {}

function settings(overrides: Partial<ExerciserSettings> = {}): ExerciserSettings {
  return {
    delay: { kind: 'fixed', seconds: 5 },
    priority: { kind: 'fixed', priority: 5 },
    group: { kind: 'round-robin', groups: 4 },
    ...overrides,
  }
}

describe('assignmentFor — determinism', () => {
  it('returns identical assignments for identical inputs', () => {
    const config = settings({
      delay: { kind: 'random', maxSeconds: 10 },
      priority: { kind: 'round-robin' },
      group: { kind: 'per-key', listName: 'customers' },
    })
    const lists = { customers: ['a', 'b', 'c'] }

    const first = assignmentFor(config, RUN_ID, 7, lists)
    const second = assignmentFor(config, RUN_ID, 7, lists)

    expect(second).toEqual(first)
  })

  it('a different runId reassigns the random values', () => {
    const config = settings({ delay: { kind: 'random', maxSeconds: 1000 } })

    const delays = (runId: string) =>
      Array.from({ length: 20 }, (_, i) => assignmentFor(config, runId, i, NO_LISTS).delaySeconds)

    // 20 draws from 1001 values colliding across two runs is (1/1001)^20.
    expect(delays('run-a')).not.toEqual(delays('run-b'))
  })
})

describe('assignmentFor — delay strategies', () => {
  it('fixed assigns the same delay to every message', () => {
    const config = settings({ delay: { kind: 'fixed', seconds: 8 } })

    for (const index of [0, 1, 99]) {
      expect(assignmentFor(config, RUN_ID, index, NO_LISTS).delaySeconds).toBe(8)
    }
  })

  it('random stays within [0, maxSeconds] and varies across indices', () => {
    const config = settings({ delay: { kind: 'random', maxSeconds: 10 } })

    const delays = Array.from(
      { length: 50 },
      (_, i) => assignmentFor(config, RUN_ID, i, NO_LISTS).delaySeconds
    )

    expect(delays.every((d) => Number.isInteger(d) && d >= 0 && d <= 10)).toBe(true)
    expect(new Set(delays).size).toBeGreaterThan(1)
  })

  it('per-index-ramp grows by stepSeconds and caps at maxSeconds', () => {
    const config = settings({ delay: { kind: 'per-index-ramp', stepSeconds: 2, maxSeconds: 7 } })

    const delays = [0, 1, 2, 3, 4, 100].map(
      (i) => assignmentFor(config, RUN_ID, i, NO_LISTS).delaySeconds
    )

    expect(delays).toEqual([0, 2, 4, 6, 7, 7])
  })
})

describe('assignmentFor — priority strategies', () => {
  it('fixed assigns the same priority to every message', () => {
    const config = settings({ priority: { kind: 'fixed', priority: 9 } })

    for (const index of [0, 1, 99]) {
      expect(assignmentFor(config, RUN_ID, index, NO_LISTS).priority).toBe(9)
    }
  })

  it('round-robin cycles 1..10 by index', () => {
    const config = settings({ priority: { kind: 'round-robin' } })

    const priorities = Array.from(
      { length: 12 },
      (_, i) => assignmentFor(config, RUN_ID, i, NO_LISTS).priority
    )

    expect(priorities).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2])
  })
})

describe('assignmentFor — group strategies', () => {
  it('single assigns the named group to every message', () => {
    const config = settings({ group: { kind: 'single', group: 'orders' } })

    for (const index of [0, 1, 99]) {
      expect(assignmentFor(config, RUN_ID, index, NO_LISTS).messageGroup).toBe('orders')
    }
  })

  it('round-robin cycles grp-0..grp-(n-1) by index', () => {
    const config = settings({ group: { kind: 'round-robin', groups: 3 } })

    const groups = Array.from(
      { length: 6 },
      (_, i) => assignmentFor(config, RUN_ID, i, NO_LISTS).messageGroup
    )

    expect(groups).toEqual(['grp-0', 'grp-1', 'grp-2', 'grp-0', 'grp-1', 'grp-2'])
  })

  it('per-key picks a member of the named list, deterministically', () => {
    const config = settings({ group: { kind: 'per-key', listName: 'customers' } })
    const lists = { customers: ['cust-a', 'cust-b', 'cust-c'] }

    const groups = Array.from(
      { length: 30 },
      (_, i) => assignmentFor(config, RUN_ID, i, lists).messageGroup
    )

    expect(groups.every((g) => lists.customers.includes(g))).toBe(true)
    // Spread across the list, not pinned to one member.
    expect(new Set(groups).size).toBeGreaterThan(1)
    // Deterministic: recomputing yields the same sequence.
    const again = Array.from(
      { length: 30 },
      (_, i) => assignmentFor(config, RUN_ID, i, lists).messageGroup
    )
    expect(again).toEqual(groups)
  })

  it('per-key with a MISSING list throws, naming the list', () => {
    const config = settings({ group: { kind: 'per-key', listName: 'customers' } })

    expect(() => assignmentFor(config, RUN_ID, 0, NO_LISTS)).toThrowError(/customers/)
  })

  it('per-key with an EMPTY list throws, naming the list', () => {
    const config = settings({ group: { kind: 'per-key', listName: 'customers' } })

    expect(() => assignmentFor(config, RUN_ID, 0, { customers: [] })).toThrowError(/customers/)
  })
})

describe('buildManifest', () => {
  it('returns one entry per message with 1-based ids matching {{messageId}}', () => {
    const manifest = buildManifest(settings(), RUN_ID, 5, NO_LISTS)

    expect(manifest).toHaveLength(5)
    expect(manifest.map((e) => e.messageId)).toEqual([1, 2, 3, 4, 5])
  })

  it('every entry equals assignmentFor at that index — one function, no drift', () => {
    const config = settings({
      delay: { kind: 'random', maxSeconds: 20 },
      priority: { kind: 'round-robin' },
      group: { kind: 'round-robin', groups: 3 },
    })

    const manifest = buildManifest(config, RUN_ID, 10, NO_LISTS)

    manifest.forEach((entry) => {
      const assignment = assignmentFor(config, RUN_ID, entry.messageId - 1, NO_LISTS)
      expect(entry).toEqual({ messageId: entry.messageId, ...assignment })
    })
  })

  it('a zero count yields an empty manifest', () => {
    expect(buildManifest(settings(), RUN_ID, 0, NO_LISTS)).toEqual([])
  })
})
