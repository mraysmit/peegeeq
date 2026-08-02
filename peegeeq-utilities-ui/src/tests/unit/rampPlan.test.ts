/**
 * Tests for rampPlan.ts (design §19.1 — Phase G.1a).
 *
 * A ramp is a PROFILE whose phases are computed, plus a stop rule. This module
 * owns the pure decisions — build the steps, decide when the knee is reached,
 * report the max sustained rate — so the sequencing stays in profileRunner.
 *
 * Contract under test (written FIRST):
 * - steps ascend from startRate by stepRate, each held for stepSecs
 * - maxRate CAPS the ramp, and a step landing beyond the cap is clamped to it
 *   rather than dropped (the cap is a rate the operator asked to reach)
 * - a start above the cap yields no steps at all, rather than one silent step
 * - the error-rate rule halts when a step's errors exceed the threshold share
 *   of what that step REQUESTED
 * - the plateau rule halts when a step's achieved rate fails to rise despite a
 *   higher requested rate — the definition of saturation
 * - the plateau rule needs a previous step; it never halts on the first
 * - the knee is the highest step that SUSTAINED its requested rate; when no
 *   step sustained, there is no knee rather than a fabricated one
 */
import { describe, it, expect } from 'vitest'
import { buildRampPhases, rampHaltReason, sustainedRate } from '../../engine/rampPlan'
import type { RampSettings } from '../../types/ramp'
import type { ProfilePhase, ProfilePhaseResult } from '../../types/profile'

function settings(overrides: Partial<RampSettings> = {}): RampSettings {
  return {
    startRate: 10,
    stepRate: 50,
    stepSecs: 10,
    maxRate: null,
    stopOn: 'error-rate',
    errorRatePercent: 5,
    ...overrides,
  }
}

function resultFor(phase: ProfilePhase, overrides: Partial<ProfilePhaseResult> = {}): ProfilePhaseResult {
  return {
    phaseId: phase.id,
    label: phase.label,
    sent: phase.rate * phase.durationSecs,
    errors: 0,
    status: 'completed',
    durationMs: phase.durationSecs * 1000,
    ...overrides,
  }
}

describe('buildRampPhases', () => {
  it('ascends from the start rate by the step size, holding each for stepSecs', () => {
    const phases = buildRampPhases(settings({ startRate: 10, stepRate: 50, stepSecs: 10, maxRate: 210 }))

    expect(phases.map((p) => p.rate)).toEqual([10, 60, 110, 160, 210])
    expect(phases.every((p) => p.durationSecs === 10)).toBe(true)
  })

  it('clamps a step that would overshoot the cap to the cap itself', () => {
    // 10, 60, 110 then 160 > 150 — the operator asked to reach 150, so the last
    // step IS 150 rather than being dropped or overshooting.
    const phases = buildRampPhases(settings({ startRate: 10, stepRate: 50, maxRate: 150 }))

    expect(phases.map((p) => p.rate)).toEqual([10, 60, 110, 150])
  })

  it('yields no steps when the start rate is already above the cap', () => {
    expect(buildRampPhases(settings({ startRate: 500, maxRate: 100 }))).toEqual([])
  })

  it('labels each step with its rate so results read as a ramp', () => {
    const phases = buildRampPhases(settings({ startRate: 10, stepRate: 50, maxRate: 60 }))

    expect(phases[0].label).toContain('10')
    expect(phases[1].label).toContain('60')
  })

  it('gives every step a distinct id', () => {
    const phases = buildRampPhases(settings({ startRate: 10, stepRate: 10, maxRate: 50 }))

    expect(new Set(phases.map((p) => p.id)).size).toBe(phases.length)
  })

  it('without a cap it still produces a bounded ramp rather than running forever', () => {
    const phases = buildRampPhases(settings({ maxRate: null }))

    expect(phases.length).toBeGreaterThan(0)
    expect(phases.length).toBeLessThanOrEqual(100)
  })
})

describe('rampHaltReason — error-rate rule', () => {
  const config = settings({ stopOn: 'error-rate', errorRatePercent: 5, startRate: 100, stepSecs: 10 })
  const phases = buildRampPhases({ ...config, maxRate: 300 })

  it('does not halt while errors stay under the threshold share of the request', () => {
    // 1000 requested, 40 errors = 4% < 5%.
    const result = resultFor(phases[0], { errors: 40 })

    expect(rampHaltReason(config, phases, result, [result])).toBeNull()
  })

  it('halts when errors exceed the threshold share of what the step requested', () => {
    const result = resultFor(phases[0], { errors: 120 }) // 12% of 1000

    const reason = rampHaltReason(config, phases, result, [result])

    expect(reason).toMatch(/error rate/i)
    expect(reason).toContain('12')
  })
})

describe('rampHaltReason — plateau rule', () => {
  const config = settings({ stopOn: 'plateau', startRate: 100, stepRate: 100, stepSecs: 10 })
  const phases = buildRampPhases({ ...config, maxRate: 400 })

  it('never halts on the first step — a plateau needs something to compare against', () => {
    const first = resultFor(phases[0])

    expect(rampHaltReason(config, phases, first, [first])).toBeNull()
  })

  it('does not halt while throughput keeps rising with the requested rate', () => {
    const first = resultFor(phases[0], { sent: 1000 }) // 100/s achieved
    const second = resultFor(phases[1], { sent: 2000 }) // 200/s achieved

    expect(rampHaltReason(config, phases, second, [first, second])).toBeNull()
  })

  it('halts when the achieved rate stops rising despite a higher requested rate', () => {
    const first = resultFor(phases[0], { sent: 1000 }) // 100/s
    const second = resultFor(phases[1], { sent: 1020 }) // 102/s for a 200/s request

    const reason = rampHaltReason(config, phases, second, [first, second])

    expect(reason).toMatch(/plateau|saturat/i)
  })
})

describe('sustainedRate', () => {
  const config = settings({ startRate: 100, stepRate: 100, stepSecs: 10, maxRate: 400 })
  const phases = buildRampPhases(config)

  it('reports the highest step that delivered what it requested', () => {
    const results = [
      resultFor(phases[0], { sent: 1000 }), // 100/s requested, delivered
      resultFor(phases[1], { sent: 2000 }), // 200/s requested, delivered
      resultFor(phases[2], { sent: 2100 }), // 300/s requested, only 210/s
    ]

    expect(sustainedRate(phases, results)).toBe(200)
  })

  it('returns null when no step sustained its rate, rather than inventing a knee', () => {
    const results = [resultFor(phases[0], { sent: 100 })] // 100/s requested, 10/s achieved

    expect(sustainedRate(phases, results)).toBeNull()
  })

  it('tolerates a small shortfall — near-perfect delivery still counts as sustained', () => {
    // 1000 requested, 995 acknowledged: the run boundary, not a failure to keep up.
    const results = [resultFor(phases[0], { sent: 995 })]

    expect(sustainedRate(phases, results)).toBe(100)
  })
})
