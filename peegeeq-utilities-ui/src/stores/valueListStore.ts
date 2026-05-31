/**
 * Zustand store for value list management (§11 of the feature design).
 *
 * Value lists back the {{list:name}} placeholder tokens. The store holds
 * {@link ValueList} records for the UI but persists a flat name→values map
 * (key `peegeeq_value_lists`) via the valueListService.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import type { ValueList } from '../types/generator'
import { loadAll, saveAll } from '../services/valueListService'

interface ValueListState {
  lists: ValueList[]
  selected: ValueList | null
  loadFromStorage: () => void
  saveToStorage: () => void
  add: (list: ValueList) => void
  update: (list: ValueList) => void
  remove: (name: string) => void
  select: (name: string | null) => void
  snapshot: () => Record<string, string[]>
  importList: (
    name: string,
    values: string[],
    mode: 'overwrite' | 'merge'
  ) => { name: string; added: number; total: number }
}

/** Persist the current lists as a flat name→values map. */
function persist(lists: ValueList[]): void {
  const map: Record<string, string[]> = {}
  for (const l of lists) map[l.name] = l.values
  saveAll(map)
}

export const useValueListStore = create<ValueListState>()(
  devtools(
    (set, get) => ({
      lists: [],
      selected: null,

      loadFromStorage: () => {
        const map = loadAll()
        const now = new Date().toISOString()
        const lists: ValueList[] = Object.entries(map).map(([name, values]) => ({
          name,
          values,
          createdAt: now,
          updatedAt: now,
        }))
        set({ lists })
      },

      saveToStorage: () => persist(get().lists),

      add: (list) =>
        set((state) => {
          const lists = [...state.lists, list]
          persist(lists)
          return { lists }
        }),

      update: (list) =>
        set((state) => {
          const updated = { ...list, updatedAt: new Date().toISOString() }
          const lists = state.lists.map((l) => (l.name === list.name ? updated : l))
          persist(lists)
          const selected = state.selected?.name === list.name ? updated : state.selected
          return { lists, selected }
        }),

      remove: (name) =>
        set((state) => {
          const lists = state.lists.filter((l) => l.name !== name)
          persist(lists)
          const selected = state.selected?.name === name ? null : state.selected
          return { lists, selected }
        }),

      select: (name) =>
        set((state) => ({
          selected: name === null ? null : (state.lists.find((l) => l.name === name) ?? null),
        })),

      snapshot: () => {
        const map: Record<string, string[]> = {}
        for (const l of get().lists) map[l.name] = l.values
        return map
      },

      importList: (name, values, mode) => {
        const now = new Date().toISOString()
        const existing = get().lists.find((l) => l.name === name)
        let nextValues: string[]
        let added: number

        if (!existing || mode === 'overwrite') {
          nextValues = [...values]
          added = values.length
        } else {
          const present = new Set(existing.values)
          const fresh = values.filter((v) => !present.has(v))
          nextValues = [...existing.values, ...fresh]
          added = fresh.length
        }

        set((state) => {
          const lists = existing
            ? state.lists.map((l) =>
                l.name === name ? { ...l, values: nextValues, updatedAt: now } : l
              )
            : [...state.lists, { name, values: nextValues, createdAt: now, updatedAt: now }]
          persist(lists)
          return { lists }
        })

        return { name, added, total: nextValues.length }
      },
    }),
    { name: 'ValueListStore' }
  )
)
