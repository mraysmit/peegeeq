/**
 * Zustand store for message template management (§11 of the feature design).
 *
 * Templates are persisted to localStorage (key `peegeeq_msg_templates`) via the
 * templateService. The store is the single source of truth for the UI; every
 * mutation writes through to storage.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import type { MessageTemplate } from '../types/generator'
import { loadAll, saveAll } from '../services/templateService'

interface TemplateState {
  templates: MessageTemplate[]
  selected: MessageTemplate | null
  loadFromStorage: () => void
  saveToStorage: () => void
  add: (template: MessageTemplate) => void
  update: (template: MessageTemplate) => void
  remove: (id: string) => void
  duplicate: (id: string) => void
  select: (id: string | null) => void
  importTemplates: (incoming: MessageTemplate[]) => { added: number; skipped: string[] }
}

export const useTemplateStore = create<TemplateState>()(
  devtools(
    (set, get) => ({
      templates: [],
      selected: null,

      loadFromStorage: () => set({ templates: loadAll() }),

      saveToStorage: () => saveAll(get().templates),

      add: (template) =>
        set((state) => {
          const templates = [...state.templates, template]
          saveAll(templates)
          return { templates }
        }),

      update: (template) =>
        set((state) => {
          const updated = { ...template, updatedAt: new Date().toISOString() }
          const templates = state.templates.map((t) => (t.id === template.id ? updated : t))
          saveAll(templates)
          const selected = state.selected?.id === template.id ? updated : state.selected
          return { templates, selected }
        }),

      remove: (id) =>
        set((state) => {
          const templates = state.templates.filter((t) => t.id !== id)
          saveAll(templates)
          const selected = state.selected?.id === id ? null : state.selected
          return { templates, selected }
        }),

      duplicate: (id) =>
        set((state) => {
          const source = state.templates.find((t) => t.id === id)
          if (!source) return state
          const now = new Date().toISOString()
          const copy: MessageTemplate = {
            ...source,
            id: crypto.randomUUID(),
            name: `${source.name} (copy)`,
            createdAt: now,
            updatedAt: now,
          }
          const templates = [...state.templates, copy]
          saveAll(templates)
          return { templates }
        }),

      select: (id) =>
        set((state) => ({
          selected: id === null ? null : (state.templates.find((t) => t.id === id) ?? null),
        })),

      importTemplates: (incoming) => {
        const existingIds = new Set(get().templates.map((t) => t.id))
        const added: MessageTemplate[] = []
        const skipped: string[] = []
        for (const t of incoming) {
          if (existingIds.has(t.id)) {
            skipped.push(t.id)
          } else {
            existingIds.add(t.id)
            added.push(t)
          }
        }
        if (added.length > 0) {
          set((state) => {
            const templates = [...state.templates, ...added]
            saveAll(templates)
            return { templates }
          })
        }
        return { added: added.length, skipped }
      },
    }),
    { name: 'TemplateStore' }
  )
)
