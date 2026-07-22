/**
 * Zustand store for scheduled generator runs
 * (PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md Part III §5, §7.3 — SCH.1).
 *
 * Holds the three collections (schedules, run history, schedule templates) and
 * writes through to localStorage via the scheduleService on every mutation,
 * mirroring templateStore/valueListStore.
 *
 * The history IS the run record: recordOutcome appends a denormalised
 * ScheduleRunRecord for every outcome kind (including skipped and missed) and
 * touches the schedule only for SCHEDULING state (nextRunAt advance, one-shot
 * consumption). The schedules table derives its latest-outcome column from
 * history via latestOutcomeFor.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import type { RunConfig, RunSummary } from '../types/generator'
import type {
  ScheduledRun,
  ScheduleOutcome,
  ScheduleRunRecord,
  ScheduleSpec,
  ScheduleTemplate,
} from '../types/schedule'
import {
  loadAllSchedules,
  loadHistory,
  loadTemplates,
  saveAllSchedules,
  saveHistory,
  saveTemplates,
} from '../services/scheduleService'

/**
 * §7.3 — the first slot STRICTLY after `after`, or null when consumed.
 * Pure function: covers both creation (after = now) and advancing after an
 * outcome. Interval schedules never replay missed slots — no catch-up bursts.
 */
export function computeNextRunAt(spec: ScheduleSpec, after: Date): string | null {
  if (spec.kind === 'once') {
    return new Date(spec.runAt).getTime() > after.getTime() ? spec.runAt : null
  }
  const first = new Date(spec.firstRunAt).getTime()
  const stepMs = spec.everyMinutes * 60_000
  if (first > after.getTime()) return spec.firstRunAt
  const k = Math.floor((after.getTime() - first) / stepMs) + 1
  return new Date(first + k * stepMs).toISOString()
}

interface ScheduleState {
  schedules: ScheduledRun[]
  history: ScheduleRunRecord[]
  templates: ScheduleTemplate[]
  loadFromStorage: () => void
  addSchedule: (schedule: ScheduledRun) => void
  updateSchedule: (schedule: ScheduledRun) => void
  removeSchedule: (id: string) => void
  setEnabled: (id: string, enabled: boolean) => void
  /**
   * Scheduling state only, no history record: set nextRunAt (a consumed
   * one-shot — null — is disabled). The scheduler calls this AT FIRE TIME so
   * an in-flight run's schedule is no longer due; without it, a run outlasting
   * one check cycle is found due again and records a false self-skip.
   */
  advanceSchedule: (scheduleId: string, advanceTo: string | null) => void
  /**
   * Record a firing result: appends the history record (the run record).
   * `advanceTo` optionally sets the schedule's nextRunAt (and disables a
   * consumed one-shot when null) in the same write — scheduling state only;
   * the schedule carries no outcome fields. Used by the missed/skipped paths;
   * a fired run advances at fire time via advanceSchedule and records its
   * terminal outcome here WITHOUT advanceTo.
   *
   * `fallback` is the fire-time snapshot (name + config), used when the
   * schedule was deleted while its run executed: the record is then built
   * from the snapshot so it passes the per-entry history validation — a
   * record with an empty config stub fails it and the outcome is lost.
   */
  recordOutcome: (
    scheduleId: string,
    outcome: ScheduleOutcome,
    summary: RunSummary | null,
    advanceTo?: string | null,
    fallback?: { scheduleName: string; config: RunConfig }
  ) => void
  /** History append without a schedule (template run-now). The id is generated here. */
  appendHistoryRecord: (record: Omit<ScheduleRunRecord, 'id'>) => void
  /** Newest history record for a schedule — the schedules table's outcome column. */
  latestOutcomeFor: (scheduleId: string) => ScheduleRunRecord | null
  /**
   * Import validated schedules (D7 later phase). Duplicate ids — against
   * storage or within the batch — are skipped and reported; no overwrites.
   * `nextRunAt` is recomputed at import: a past one-shot arrives consumed
   * (disabled) and a past interval advances to its next future slot — an
   * imported backlog NEVER fires (§7.4 rule).
   */
  importSchedules: (incoming: ScheduledRun[]) => { added: number; skipped: string[] }
  /**
   * Manual-run history (the second later phase): record a Start-button run's
   * terminal outcome as a history entry under the fixed scheduleId "manual".
   */
  recordManualRun: (
    config: RunConfig,
    status: 'completed' | 'stopped' | 'error',
    summary: RunSummary,
    detail?: string
  ) => void
  saveAsTemplate: (name: string, config: RunConfig) => void
  removeTemplate: (id: string) => void
}

export const useScheduleStore = create<ScheduleState>()(
  devtools(
    (set, get) => ({
      schedules: [],
      history: [],
      templates: [],

      loadFromStorage: () =>
        set({
          schedules: loadAllSchedules(),
          history: loadHistory(),
          templates: loadTemplates(),
        }),

      addSchedule: (schedule) =>
        set((state) => {
          const schedules = [...state.schedules, schedule]
          saveAllSchedules(schedules)
          return { schedules }
        }),

      updateSchedule: (schedule) =>
        set((state) => {
          const updated = { ...schedule, updatedAt: new Date().toISOString() }
          const schedules = state.schedules.map((s) => (s.id === schedule.id ? updated : s))
          saveAllSchedules(schedules)
          return { schedules }
        }),

      removeSchedule: (id) =>
        set((state) => {
          const schedules = state.schedules.filter((s) => s.id !== id)
          saveAllSchedules(schedules)
          // History deliberately untouched: records survive schedule deletion (R12).
          return { schedules }
        }),

      setEnabled: (id, enabled) =>
        set((state) => {
          const schedules = state.schedules.map((s) =>
            s.id === id ? { ...s, enabled, updatedAt: new Date().toISOString() } : s
          )
          saveAllSchedules(schedules)
          return { schedules }
        }),

      advanceSchedule: (scheduleId, advanceTo) =>
        set((state) => {
          const schedules = state.schedules.map((s) =>
            s.id === scheduleId
              ? {
                  ...s,
                  nextRunAt: advanceTo,
                  enabled: advanceTo === null ? false : s.enabled,
                  updatedAt: new Date().toISOString(),
                }
              : s
          )
          saveAllSchedules(schedules)
          return { schedules }
        }),

      recordOutcome: (scheduleId, outcome, summary, advanceTo, fallback) =>
        set((state) => {
          const schedule = state.schedules.find((s) => s.id === scheduleId)
          if (!schedule && !fallback) {
            // No schedule and no fire-time snapshot: the record below carries
            // an empty config stub, fails the per-entry history validation on
            // the re-read, and the outcome is LOST. Every caller that can
            // outlive its schedule must pass the snapshot.
            console.error(
              `recordOutcome: schedule ${scheduleId} not found and no fire-time snapshot given; the outcome cannot be recorded validly`
            )
          }

          // Scheduling state only: advance nextRunAt; a consumed one-shot
          // (advanceTo === null) is disabled. No outcome fields exist on the schedule.
          const schedules =
            schedule && advanceTo !== undefined
              ? state.schedules.map((s) =>
                  s.id === scheduleId
                    ? {
                        ...s,
                        nextRunAt: advanceTo,
                        enabled: advanceTo === null ? false : s.enabled,
                        updatedAt: new Date().toISOString(),
                      }
                    : s
                )
              : state.schedules
          if (schedule && advanceTo !== undefined) saveAllSchedules(schedules)

          // Deleted-schedule path: build the record from the fire-time snapshot.
          const config = schedule?.config ?? fallback?.config
          const record: ScheduleRunRecord = {
            id: crypto.randomUUID(),
            scheduleId,
            scheduleName: schedule?.name ?? fallback?.scheduleName ?? scheduleId,
            target: config
              ? { setupId: config.setupId, queueName: config.queueName }
              : { setupId: '', queueName: '' },
            outcome,
            summary,
            config: config ?? ({} as RunConfig),
          }
          const history = [record, ...state.history]
          saveHistory(history)
          return { schedules, history: loadHistory() }
        }),

      latestOutcomeFor: (scheduleId) =>
        get().history.find((r) => r.scheduleId === scheduleId) ?? null,

      appendHistoryRecord: (record) =>
        set((state) => {
          const full: ScheduleRunRecord = { ...record, id: crypto.randomUUID() }
          const history = [full, ...state.history]
          saveHistory(history)
          return { history: loadHistory() }
        }),

      importSchedules: (incoming) => {
        const existingIds = new Set(useScheduleStore.getState().schedules.map((s) => s.id))
        const now = new Date()
        const added: ScheduledRun[] = []
        const skipped: string[] = []
        for (const schedule of incoming) {
          if (existingIds.has(schedule.id)) {
            skipped.push(schedule.id)
            continue
          }
          existingIds.add(schedule.id)
          const nextRunAt = computeNextRunAt(schedule.schedule, now)
          added.push({
            ...schedule,
            nextRunAt,
            // A consumed one-shot imports disabled; otherwise the imported flag stands.
            enabled: nextRunAt === null ? false : schedule.enabled,
            updatedAt: now.toISOString(),
          })
        }
        if (added.length > 0) {
          set((state) => {
            const schedules = [...state.schedules, ...added]
            saveAllSchedules(schedules)
            return { schedules }
          })
        }
        return { added: added.length, skipped }
      },

      recordManualRun: (config, status, summary, detail) =>
        set((state) => {
          const record: ScheduleRunRecord = {
            id: crypto.randomUUID(),
            scheduleId: 'manual',
            scheduleName: `Manual run — ${config.template.name} @ ${config.queueName}`,
            target: { setupId: config.setupId, queueName: config.queueName },
            outcome: {
              at: new Date().toISOString(),
              result: status,
              totalSent: summary.totalSent,
              totalErrors: summary.totalErrors,
              detail,
            },
            summary,
            // Frozen copy: the page's working template keeps changing after the run.
            config: { ...config, template: { ...config.template, headers: { ...config.template.headers } } },
          }
          const history = [record, ...state.history]
          saveHistory(history)
          return { history: loadHistory() }
        }),

      saveAsTemplate: (name, config) =>
        set((state) => {
          const now = new Date().toISOString()
          const template: ScheduleTemplate = {
            id: crypto.randomUUID(),
            name,
            config,
            createdAt: now,
            updatedAt: now,
          }
          const templates = [...state.templates, template]
          saveTemplates(templates)
          return { templates }
        }),

      removeTemplate: (id) =>
        set((state) => {
          const templates = state.templates.filter((t) => t.id !== id)
          saveTemplates(templates)
          return { templates }
        }),
    }),
    { name: 'ScheduleStore' }
  )
)
