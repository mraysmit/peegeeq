/**
 * Shared run-starter (Scheduled Runs design §9 — SCH.2).
 *
 * The single wiring between a RunConfig and the publication engine, used by the
 * Start button (MessageGeneratorPage) and the scheduler (schedulerRuntime).
 * Extracted from MessageGeneratorPage.handleStart without contract changes:
 * the generatorStore owns run state and generates the runId; the engine generates
 * nothing; terminal callbacks write transitionTo + setSummary; the single-use
 * engine is discarded on terminal state.
 */
import { createPublicationEngine } from './publicationEngine'
import { useGeneratorStore } from '../stores/generatorStore'
import type { RunConfig, RunStatus, RunSummary } from '../types/generator'

export interface RunHandle {
  /** Stop the run. The engine's onStop settles the store and the terminal hook. */
  stop(): void
}

export interface RunHooks {
  /** Fires once, AFTER the store has its terminal status and summary. */
  onTerminal?(summary: RunSummary, status: RunStatus, reason?: string): void
}

/**
 * The active run's handle. The run and engine are singletons, but a page-local
 * ref only reaches runs that page started — the Stop button was a no-op for
 * scheduler-started and "Run now" runs. Set on every start, cleared on every
 * terminal callback.
 */
let activeHandle: RunHandle | null = null

/** Stop the active run regardless of which surface started it. No-op when idle. */
export function stopActiveRun(): void {
  activeHandle?.stop()
}

/**
 * Start a run. Returns null — refusing, with no side effects — when a run is
 * already active (the engine and run state are singletons).
 */
export function startGeneratorRun(config: RunConfig, hooks: RunHooks = {}): RunHandle | null {
  const store = useGeneratorStore.getState()
  if (store.runState.status === 'running') return null

  store.setConfig(config)
  store.startRun()
  const runId = useGeneratorStore.getState().runState.runId
  if (runId === null) return null // startRun refused — nothing to drive

  const engine = createPublicationEngine()

  function finishRun(finalStatus: RunStatus, summary: RunSummary, reason?: string) {
    activeHandle = null // the singleton run is over; a stale stop must not reach a settled engine
    const s = useGeneratorStore.getState()
    s.transitionTo(finalStatus, reason)
    s.setSummary(summary)
    hooks.onTerminal?.(summary, finalStatus, reason)
  }

  // Registered BEFORE engine.start: if the first tick terminates synchronously,
  // finishRun's clear must run after the assignment, never be overwritten by it.
  const handle: RunHandle = { stop: () => engine.stop() }
  activeHandle = handle

  try {
    engine.start(config, { runId, correlationId: crypto.randomUUID() }, {
      onTick: (sent, errors, consecErrors, elapsedMs) =>
        useGeneratorStore.getState().tickUpdate(sent, errors, consecErrors, elapsedMs),
      onComplete: (summary) => finishRun('completed', summary),
      onStop: (summary) => finishRun('stopped', summary),
      onError: (summary, reason) => finishRun('error', summary, reason),
    })
  } catch (error) {
    // A synchronous start failure (e.g. the value-list snapshot throwing) must
    // not leave the store stuck in RUNNING with no engine and no handle —
    // that blocks Start and skips every scheduled firing until a reload.
    const reason = `Run failed to start: ${error instanceof Error ? error.message : String(error)}`
    finishRun(
      'error',
      {
        totalSent: 0,
        targetTotal: config.rate * config.durationSecs,
        avgRate: 0,
        durationMs: 0,
        totalErrors: 0,
        finalStatus: 'error',
        runId,
        errors: [],
      },
      reason
    )
    return null
  }

  return handle
}
