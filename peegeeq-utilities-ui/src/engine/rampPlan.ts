/**
 * Ramp planning and knee detection (design §19.1 — Phase G.1a).
 *
 * A ramp IS a profile whose phases are computed, plus a stop rule, so this
 * module owns only the pure decisions — build the steps, decide when the knee
 * is reached, report the max sustained rate — and the sequencing stays in
 * profileRunner (which drives the same engine per step).
 *
 * Everything here is derived from {@link RampSettings} and the per-step
 * results. Nothing is stored, so the plan can never disagree with the settings
 * that produced it.
 */
import type { ProfilePhase, ProfilePhaseResult } from '../types/profile'
import type { RampSettings } from '../types/ramp'

/**
 * Hard bound on an uncapped ramp. Without a cap the operator asked to climb
 * until the stop rule trips — but a rule that never trips must not produce an
 * unbounded plan, so the ramp is finite by construction.
 */
const MAX_STEPS = 100

/** A step delivering at least this share of its request counts as sustained. */
const SUSTAINED_THRESHOLD = 0.99

/** Rising less than this much while the request rose counts as a plateau. */
const PLATEAU_GROWTH = 0.1

/** Messages a step asked for — derived, never stored. */
function requestedFor(phase: ProfilePhase): number {
  return phase.rate * phase.durationSecs
}

/** Achieved rate of a settled step, in msg/s. */
function achievedRate(phase: ProfilePhase, result: ProfilePhaseResult): number {
  return phase.durationSecs > 0 ? result.sent / phase.durationSecs : 0
}

/** The ordered steps a ramp will run. */
export function buildRampPhases(settings: RampSettings): ProfilePhase[] {
  const { startRate, stepRate, stepSecs, maxRate } = settings
  if (maxRate !== null && startRate > maxRate) return []

  const phases: ProfilePhase[] = []
  let rate = startRate
  while (phases.length < MAX_STEPS) {
    phases.push({
      id: crypto.randomUUID(),
      label: `${rate} msg/s`,
      rate,
      durationSecs: stepSecs,
    })
    if (maxRate !== null && rate >= maxRate) break
    const next = rate + stepRate
    if (stepRate <= 0) break // a non-ascending ramp is one step, not an infinite loop
    // Clamp the overshooting step to the cap: it is a rate the operator asked
    // to reach, so it is run rather than skipped.
    rate = maxRate !== null && next > maxRate ? maxRate : next
  }
  return phases
}

/**
 * Why the ramp should stop after this step, or null to keep climbing.
 *
 * Supplied to profileRunner as `shouldHaltAfterPhase`.
 */
export function rampHaltReason(
  settings: RampSettings,
  phases: ProfilePhase[],
  result: ProfilePhaseResult,
  results: ProfilePhaseResult[]
): string | null {
  const index = results.length - 1
  const phase = phases[index]
  if (!phase) return null

  if (settings.stopOn === 'error-rate') {
    const requested = requestedFor(phase)
    if (requested <= 0) return null
    const percent = (result.errors / requested) * 100
    if (percent > settings.errorRatePercent) {
      return `Error rate ${percent.toFixed(1)}% at ${phase.rate} msg/s exceeded the ${settings.errorRatePercent}% threshold`
    }
    return null
  }

  // Plateau: throughput stopped rising even though the request did. That is
  // saturation — the definition of the knee.
  // The first step has nothing to compare against: index - 1 is out of range on
  // both lists, so this single guard covers it. (An explicit `index === 0`
  // early return was here too; a mutation probe proved removing it changed no
  // behaviour, so it was redundant and is gone.)
  const previousPhase = phases[index - 1]
  const previousResult = results[index - 1]
  if (!previousPhase || !previousResult) return null

  const previousAchieved = achievedRate(previousPhase, previousResult)
  const currentAchieved = achievedRate(phase, result)
  const requestedRose = phase.rate > previousPhase.rate
  const grewEnough = currentAchieved > previousAchieved * (1 + PLATEAU_GROWTH)
  if (requestedRose && !grewEnough) {
    return `Throughput plateaued at ~${Math.round(currentAchieved)} msg/s: requesting ${phase.rate} msg/s did not raise it above ${Math.round(previousAchieved)} msg/s`
  }
  return null
}

/**
 * The highest rate the target actually sustained, or null when no step did.
 *
 * Null is deliberate: reporting the lowest attempted rate as the "breaking
 * point" when even that step could not keep up would fabricate a finding.
 */
export function sustainedRate(
  phases: ProfilePhase[],
  results: ProfilePhaseResult[]
): number | null {
  let best: number | null = null
  results.forEach((result, index) => {
    const phase = phases[index]
    if (!phase) return
    const requested = requestedFor(phase)
    if (requested <= 0) return
    if (result.sent >= requested * SUSTAINED_THRESHOLD) {
      best = best === null || phase.rate > best ? phase.rate : best
    }
  })
  return best
}
