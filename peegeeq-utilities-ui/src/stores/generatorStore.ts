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

export const useGeneratorStore = create<GeneratorState>()(
  devtools(
    (set, get) => ({
      config: null,
      runState: { ...IDLE_STATE },

      setConfig: (config) => set({ config }),

      startRun: () => {
        const { config } = get()
        if (!config) return
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

      resetRun: () => set({ runState: { ...IDLE_STATE } }),

      tickUpdate: (sent, errors, consecErrors, elapsedMs) =>
        set((state) => ({
          runState: {
            ...state.runState,
            sent,
            errors,
            consecErrors,
            elapsedMs,
            currentRate: elapsedMs > 0 ? sent / (elapsedMs / 1000) : 0,
          },
        })),

      transitionTo: (status, autoStopReason) =>
        set((state) => ({
          runState: { ...state.runState, status, autoStopReason },
        })),
    }),
    { name: 'GeneratorStore' }
  )
)
