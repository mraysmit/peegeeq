/**
 * Type definitions for traffic profiles (design §19.3 — Phase G.3).
 *
 * Pure type declarations with no runtime behaviour, mirroring
 * src/types/generator.ts.
 */
import type { RunStatus } from './generator'

/**
 * One phase of a traffic profile: a rate held for a duration.
 *
 * `rate: 0` means IDLE — publish nothing for `durationSecs`. Idle is a real
 * shape (burst → steady → spike → idle) and cannot be delegated to the
 * publication engine: the engine floors its per-tick quota at 1 message
 * (publicationEngine tick()) and RunConfig requires rate ≥ 1, so a "0 rate run"
 * would quietly publish traffic. The profile runner waits instead.
 */
export interface ProfilePhase {
  id: string
  label: string
  rate: number // msg/s; 0 = idle
  durationSecs: number
}

/**
 * What one phase actually achieved.
 *
 * `sent` is the server-acknowledged count from the phase's RunSummary — the
 * same truthful number the run summary reports. The REQUESTED total
 * (`rate × durationSecs`) is deliberately absent: it is derivable from the
 * phase and is computed where it is displayed, never stored alongside the
 * achieved figure where the two could drift apart.
 */
export interface ProfilePhaseResult {
  phaseId: string
  label: string
  sent: number
  errors: number
  status: RunStatus
  durationMs: number
}
