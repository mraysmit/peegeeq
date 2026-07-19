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
    const s = useGeneratorStore.getState()
    s.transitionTo(finalStatus, reason)
    s.setSummary(summary)
    hooks.onTerminal?.(summary, finalStatus, reason)
  }

  engine.start(config, { runId, correlationId: crypto.randomUUID() }, {
    onTick: (sent, errors, consecErrors, elapsedMs) =>
      useGeneratorStore.getState().tickUpdate(sent, errors, consecErrors, elapsedMs),
    onComplete: (summary) => finishRun('completed', summary),
    onStop: (summary) => finishRun('stopped', summary),
    onError: (summary, reason) => finishRun('error', summary, reason),
  })

  return { stop: () => engine.stop() }
}
