/**
 * Type definitions for ramp (breaking-point) runs (design §19.1 — Phase G.1a).
 *
 * Pure type declarations with no runtime behaviour.
 */

/** When a ramp stops climbing. */
export type RampStopRule = 'error-rate' | 'plateau'

/**
 * A ramp's controls. The STEPS are not stored here — they are derived from
 * these settings by `buildRampPhases`, so the plan can never disagree with the
 * settings that produced it.
 */
export interface RampSettings {
  startRate: number // msg/s of the first step
  stepRate: number // msg/s added per step
  stepSecs: number // how long each step is held
  /** Highest rate to attempt; null means "climb until the stop rule trips". */
  maxRate: number | null
  stopOn: RampStopRule
  /** Share of a step's REQUESTED messages that may fail before halting. */
  errorRatePercent: number
}
