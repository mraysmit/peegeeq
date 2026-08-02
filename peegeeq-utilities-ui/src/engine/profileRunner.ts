/**
 * Traffic-profile sequencer (design §19.3 — Phase G.3a).
 *
 * Runs an ordered list of phases back to back against ONE target, driving the
 * existing publication engine once per phase through the shared runStarter —
 * the engine, run identity and store wiring are unchanged. The runner owns only
 * the sequencing and the per-phase aggregation.
 *
 * Two rules the shape depends on:
 *
 * - An IDLE phase (rate 0) starts NO run. The engine floors its per-second
 *   quota at 1 message and RunConfig requires rate ≥ 1, so driving idle through
 *   a run would publish traffic the profile says should not exist. The runner
 *   waits out the duration instead.
 * - A phase that ends in ERROR aborts the profile. Continuing would report an
 *   achieved shape that never happened, and the later phases would be measured
 *   against a backend already known to be failing.
 *
 * The run starter and timer are injected so tests drive real fakes instead of
 * mocking modules.
 */
import { startGeneratorRun } from './runStarter'
import type { RunHandle, RunHooks } from './runStarter'
import type { RunConfig } from '../types/generator'
import type { ProfilePhase, ProfilePhaseResult } from '../types/profile'

export interface ProfileHooks {
  /** Fires as each phase begins — the UI shows which phase is live. */
  onPhaseStart?(index: number, phase: ProfilePhase): void
  /** Fires as each phase settles, with that phase's achieved figures. */
  onPhaseComplete?(result: ProfilePhaseResult): void
  onProfileComplete?(results: ProfilePhaseResult[]): void
  onProfileStopped?(results: ProfilePhaseResult[]): void
  onProfileError?(results: ProfilePhaseResult[], reason: string): void
  /**
   * Evaluated after each phase settles, with that phase's result and EVERY
   * result so far (a plateau check needs the earlier steps). Returning a reason
   * halts the sequence — the mechanism a ramp uses to stop at the knee.
   */
  shouldHaltAfterPhase?(
    result: ProfilePhaseResult,
    results: ProfilePhaseResult[]
  ): string | null
  /**
   * The sequence halted early via shouldHaltAfterPhase. This is a NORMAL
   * outcome — a ramp that found its breaking point succeeded — so it is neither
   * a completion nor an error.
   */
  onProfileHalted?(results: ProfilePhaseResult[], reason: string): void
}

export interface ProfileHandle {
  /** Stop the profile: the active phase stops, later phases never start. */
  stop(): void
}

export interface ProfileRunner {
  start(base: RunConfig, phases: ProfilePhase[], hooks: ProfileHooks): ProfileHandle | null
}

export interface ProfileRunnerDeps {
  startRun?: (config: RunConfig, hooks: RunHooks) => RunHandle | null
  setTimer?: (fn: () => void, ms: number) => ReturnType<typeof setTimeout>
  clearTimer?: (id: ReturnType<typeof setTimeout>) => void
}

export function createProfileRunner(deps: ProfileRunnerDeps = {}): ProfileRunner {
  const startRun = deps.startRun ?? startGeneratorRun
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms))
  const clearTimer = deps.clearTimer ?? ((id) => clearTimeout(id))

  return {
    start(base, phases, hooks) {
      if (phases.length === 0) return null

      const results: ProfilePhaseResult[] = []
      let index = 0
      let activeRun: RunHandle | null = null
      let idleTimer: ReturnType<typeof setTimeout> | null = null
      let stopRequested = false
      let settled = false

      function settleStopped(): void {
        if (settled) return
        settled = true
        hooks.onProfileStopped?.(results)
      }

      function record(result: ProfilePhaseResult): void {
        results.push(result)
        hooks.onPhaseComplete?.(result)
      }

      /** True when the halt hook stopped the sequence (already settled). */
      function haltedAfter(result: ProfilePhaseResult): boolean {
        const reason = hooks.shouldHaltAfterPhase?.(result, results) ?? null
        if (reason === null || settled) return false
        settled = true
        hooks.onProfileHalted?.(results, reason)
        return true
      }

      function runNext(): void {
        if (settled) return
        if (stopRequested) {
          settleStopped()
          return
        }
        if (index >= phases.length) {
          settled = true
          hooks.onProfileComplete?.(results)
          return
        }

        const phase = phases[index]
        hooks.onPhaseStart?.(index, phase)
        index++

        if (phase.rate === 0) {
          // Idle: publish nothing, just hold the shape for its duration.
          idleTimer = setTimer(() => {
            idleTimer = null
            const result: ProfilePhaseResult = {
              phaseId: phase.id,
              label: phase.label,
              sent: 0,
              errors: 0,
              status: 'completed',
              durationMs: phase.durationSecs * 1000,
            }
            record(result)
            if (haltedAfter(result)) return
            runNext()
          }, phase.durationSecs * 1000)
          return
        }

        const config: RunConfig = { ...base, rate: phase.rate, durationSecs: phase.durationSecs }
        activeRun = startRun(config, {
          onTerminal: (summary, status, reason) => {
            activeRun = null
            const result: ProfilePhaseResult = {
              phaseId: phase.id,
              label: phase.label,
              sent: summary.totalSent,
              errors: summary.totalErrors,
              status,
              durationMs: summary.durationMs,
            }
            record(result)
            if (status === 'error') {
              if (settled) return
              settled = true
              hooks.onProfileError?.(
                results,
                `Profile aborted in phase "${phase.label}": ${reason ?? 'the run ended in error'}`
              )
              return
            }
            if (status === 'stopped') {
              settleStopped()
              return
            }
            // The knee check runs on a COMPLETED phase only: a stopped or
            // errored phase did not measure the rate it was asked to.
            if (haltedAfter(result)) return
            runNext()
          },
        })

        if (activeRun === null) {
          // startGeneratorRun refuses while another run is active. Report it
          // rather than leaving a profile that silently never advances.
          settled = true
          hooks.onProfileError?.(
            results,
            `Profile could not start phase "${phase.label}": a run is already active.`
          )
        }
      }

      runNext()
      // A synchronous refusal (empty start, or the starter refusing) has already
      // settled; there is nothing for the caller to hold.
      if (settled && results.length === 0) return null

      return {
        stop() {
          if (settled || stopRequested) return
          stopRequested = true
          if (idleTimer !== null) {
            clearTimer(idleTimer)
            idleTimer = null
            settleStopped()
            return
          }
          // With a live run, its onTerminal('stopped') settles the profile.
          activeRun?.stop()
        },
      }
    },
  }
}
