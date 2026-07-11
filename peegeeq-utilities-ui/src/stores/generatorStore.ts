/**
 * Zustand store for publication run state (§11 of the feature design).
 *
 * Holds the active {@link RunConfig} and live {@link RunState}. The store owns
 * state only; the timing/tick loop lives in the publicationEngine, which calls
 * {@link tickUpdate} and {@link transitionTo} on this store.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import type { PublishError, RunConfig, RunState, RunStatus } from '../types/generator'

const IDLE_STATE: RunState = {
  status: 'idle',
  totalToSend: 0,
  sent: 0,
  errors: [],
  elapsedMs: 0,
  currentRate: 0,
  consecErrors: 0,
  runId: null,
  startedAt: null,
}

interface GeneratorState {
  config: RunConfig | null
  runState: RunState
  setConfig: (config: RunConfig) => void
  startRun: () => void
  stopRun: () => void
  resetRun: () => void
  tickUpdate: (
    sent: number,
    errors: PublishError[],
    consecErrors: number,
    elapsedMs: number
  ) => void
  transitionTo: (status: RunStatus, autoStopReason?: string) => void
}

/** Width of the rolling window used for RunState.currentRate (§6.1 Zone E). */
const RATE_WINDOW_MS = 1000

export const useGeneratorStore = create<GeneratorState>()(
  devtools(
    (set, get) => {
      // Per-run tick samples backing the rolling-window rate. Held in the store
      // closure (not in RunState) — they are an implementation detail, not
      // observable state. Reset on startRun/resetRun.
      let rateSamples: Array<{ elapsedMs: number; sent: number }> = []

      /**
       * Rolling-window rate (msg/s) over the last RATE_WINDOW_MS. Falls back to
       * the cumulative average until a second sample exists (first tick).
       */
      function computeCurrentRate(sent: number, elapsedMs: number): number {
        rateSamples.push({ elapsedMs, sent })
        // Drop samples older than the window, always keeping one as the baseline.
        while (rateSamples.length > 1 && rateSamples[0].elapsedMs < elapsedMs - RATE_WINDOW_MS) {
          rateSamples.shift()
        }
        const base = rateSamples[0]
        const windowMs = elapsedMs - base.elapsedMs
        if (windowMs > 0) {
          return ((sent - base.sent) / windowMs) * 1000
        }
        return elapsedMs > 0 ? sent / (elapsedMs / 1000) : 0
      }

      return {
        config: null,
        runState: { ...IDLE_STATE },

        setConfig: (config) => set({ config }),

        startRun: () => {
          const { config } = get()
          if (!config) return
          rateSamples = []
          set({
            runState: {
              status: 'running',
              totalToSend: config.rate * config.durationSecs,
              sent: 0,
              errors: [],
              elapsedMs: 0,
              currentRate: 0,
              consecErrors: 0,
              runId: crypto.randomUUID(),
              startedAt: Date.now(),
              autoStopReason: undefined,
            },
          })
        },

        stopRun: () => get().transitionTo('stopped'),

        resetRun: () => {
          rateSamples = []
          set({ runState: { ...IDLE_STATE } })
        },

        tickUpdate: (sent, errors, consecErrors, elapsedMs) =>
          set((state) => ({
            runState: {
              ...state.runState,
              sent,
              errors,
              consecErrors,
              elapsedMs,
              currentRate: computeCurrentRate(sent, elapsedMs),
            },
          })),

        transitionTo: (status, autoStopReason) =>
          set((state) => ({
            runState: { ...state.runState, status, autoStopReason },
          })),
      }
    },
    { name: 'GeneratorStore' }
  )
)
