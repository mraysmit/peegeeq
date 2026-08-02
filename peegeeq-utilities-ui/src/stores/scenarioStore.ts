/**
 * Zustand store for saved scenarios (design §19.4 — Phase G.4).
 *
 * Scenarios are persisted to localStorage (key `peegeeq_scenarios`) via the
 * scenarioService. The store is the single source of truth for the UI; every
 * mutation writes through to storage. Mirrors templateStore exactly, including
 * the `selected` handoff: the Tools page selects a scenario and navigates to
 * /generator, which consumes the selection as its working configuration.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import type { Scenario } from '../types/scenario'
import { loadAll, saveAll } from '../services/scenarioService'

interface ScenarioState {
  scenarios: Scenario[]
  selected: Scenario | null
  loadFromStorage: () => void
  add: (scenario: Scenario) => void
  update: (scenario: Scenario) => void
  remove: (id: string) => void
  select: (id: string | null) => void
  importScenarios: (incoming: Scenario[]) => { added: number; skipped: string[] }
}

export const useScenarioStore = create<ScenarioState>()(
  devtools(
    (set, get) => ({
      scenarios: [],
      selected: null,

      loadFromStorage: () => set({ scenarios: loadAll() }),

      add: (scenario) =>
        set((state) => {
          const scenarios = [...state.scenarios, scenario]
          saveAll(scenarios)
          return { scenarios }
        }),

      update: (scenario) =>
        set((state) => {
          const updated = { ...scenario, updatedAt: new Date().toISOString() }
          const scenarios = state.scenarios.map((s) => (s.id === scenario.id ? updated : s))
          saveAll(scenarios)
          const selected = state.selected?.id === scenario.id ? updated : state.selected
          return { scenarios, selected }
        }),

      remove: (id) =>
        set((state) => {
          const scenarios = state.scenarios.filter((s) => s.id !== id)
          saveAll(scenarios)
          const selected = state.selected?.id === id ? null : state.selected
          return { scenarios, selected }
        }),

      select: (id) =>
        set((state) => ({
          selected: id === null ? null : (state.scenarios.find((s) => s.id === id) ?? null),
        })),

      importScenarios: (incoming) => {
        const existingIds = new Set(get().scenarios.map((s) => s.id))
        const added: Scenario[] = []
        const skipped: string[] = []
        for (const s of incoming) {
          if (existingIds.has(s.id)) {
            skipped.push(s.id)
          } else {
            existingIds.add(s.id)
            added.push(s)
          }
        }
        if (added.length > 0) {
          set((state) => {
            const scenarios = [...state.scenarios, ...added]
            saveAll(scenarios)
            return { scenarios }
          })
        }
        return { added: added.length, skipped }
      },
    }),
    { name: 'ScenarioStore' }
  )
)
