/**
 * Tests for scheduleStore.ts + computeNextRunAt (Scheduled Runs design §5, §7.3 — SCH.1).
 *
 * Contract under test (written FIRST, before the store):
 * - loadFromStorage populates schedules, history, and templates
 * - schedule CRUD + setEnabled persist through the real service/localStorage
 * - recordOutcome appends the denormalised history record for every outcome
 *   kind (including skipped and missed) and advances the schedule's SCHEDULING
 *   state only (nextRunAt/enabled) — the schedule carries no run-outcome state;
 *   the history IS the run record, and the schedules table derives its latest
 *   outcome via latestOutcomeFor
 * - history records survive deletion of their schedule
 * - appendHistoryRecord supports the template run-now path (no schedule)
 * - saveAsTemplate / removeTemplate persist
 * - computeNextRunAt (§7.3, pure): once = consumed after its time; interval =
 *   smallest future slot from firstRunAt, skipping past slots with NO catch-up
 *
 * No mocks: real localStorage behind the real service.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useScheduleStore, computeNextRunAt } from '../../stores/scheduleStore'
import { loadAllSchedules, loadHistory, loadTemplates } from '../../services/scheduleService'
import type { ScheduledRun, ScheduleOutcome } from '../../types/schedule'
import type { MessageTemplate, RunConfig, RunSummary } from '../../types/generator'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1', name: 'T', messageType: 'x', payloadSchema: '{}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(): RunConfig {
  return {
    setupId: 's1', queueName: 'orders', rate: 10, durationSecs: 60,
    maxBatchSize: 10, warnThreshold: 500, maxConsecErrors: 10,
    template: makeTemplate(), previewIndex: 1,
  }
}

function makeSchedule(overrides: Partial<ScheduledRun> = {}): ScheduledRun {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Nightly load',
    config: makeConfig(),
    schedule: { kind: 'once', runAt: new Date(Date.now() + 3600_000).toISOString() },
    enabled: true,
    nextRunAt: new Date(Date.now() + 3600_000).toISOString(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function makeOutcome(result: ScheduleOutcome['result']): ScheduleOutcome {
  return { at: new Date().toISOString(), result, totalSent: result === 'completed' ? 50 : 0, totalErrors: 0 }
}

function makeSummary(): RunSummary {
  return {
    totalSent: 50, targetTotal: 50, avgRate: 10, durationMs: 5000,
    totalErrors: 0, finalStatus: 'completed', runId: 'r1', errors: [],
  }
}

function reset() {
  localStorage.clear()
  useScheduleStore.setState({ schedules: [], history: [], templates: [] })
}

describe('scheduleStore', () => {
  beforeEach(reset)

  it('loadFromStorage populates schedules, history, and templates', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    useScheduleStore.getState().saveAsTemplate('Reusable', schedule.config)

    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
    useScheduleStore.getState().loadFromStorage()

    const state = useScheduleStore.getState()
    expect(state.schedules).toHaveLength(1)
    expect(state.history).toHaveLength(1)
    expect(state.templates).toHaveLength(1)
  })

  it('addSchedule / updateSchedule / removeSchedule persist', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    expect(loadAllSchedules()).toHaveLength(1)

    useScheduleStore.getState().updateSchedule({ ...schedule, name: 'Renamed' })
    expect(loadAllSchedules()[0].name).toBe('Renamed')

    useScheduleStore.getState().removeSchedule(schedule.id)
    expect(loadAllSchedules()).toHaveLength(0)
  })

  it('setEnabled toggles and persists', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().setEnabled(schedule.id, false)
    expect(useScheduleStore.getState().schedules[0].enabled).toBe(false)
    expect(loadAllSchedules()[0].enabled).toBe(false)
  })

  it('recordOutcome appends the denormalised history record — the history IS the run record', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    const summary = makeSummary()

    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), summary)

    const state = useScheduleStore.getState()
    expect(state.history).toHaveLength(1)
    const record = state.history[0]
    expect(record.scheduleId).toBe(schedule.id)
    expect(record.scheduleName).toBe('Nightly load')
    expect(record.target).toEqual({ setupId: 's1', queueName: 'orders' })
    expect(record.config.rate).toBe(10)
    expect(record.summary).toEqual(summary)
    expect(loadHistory()).toHaveLength(1)
    // The schedule carries NO run-outcome state — only scheduling state.
    expect('lastOutcome' in state.schedules[0]).toBe(false)
    expect('lastSummary' in state.schedules[0]).toBe(false)
  })

  it('recordOutcome advances scheduling state on the schedule (nextRunAt, one-shot consumption)', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)

    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary(), null)

    const stored = useScheduleStore.getState().schedules[0]
    expect(stored.nextRunAt).toBeNull()
    expect(stored.enabled).toBe(false) // consumed one-shot disables
  })

  it('latest outcome for the schedules table derives from history', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('skipped'), null)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())

    const latest = useScheduleStore.getState().latestOutcomeFor(schedule.id)
    expect(latest!.outcome.result).toBe('completed') // newest first
    expect(useScheduleStore.getState().latestOutcomeFor('unknown-id')).toBeNull()
  })

  it('records skipped and missed outcomes in history too', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('skipped'), null)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('missed'), null)

    const results = useScheduleStore.getState().history.map((r) => r.outcome.result)
    expect(results).toEqual(['missed', 'skipped']) // newest first
  })

  it('history records survive deletion of their schedule', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    useScheduleStore.getState().removeSchedule(schedule.id)

    expect(useScheduleStore.getState().history).toHaveLength(1)
    expect(loadHistory()).toHaveLength(1)
    expect(loadHistory()[0].scheduleName).toBe('Nightly load')
  })

  it('appendHistoryRecord supports schedule-less firings (template run-now)', () => {
    useScheduleStore.getState().appendHistoryRecord({
      scheduleId: 'template:tp-1',
      scheduleName: 'Reusable nightly',
      target: { setupId: 's1', queueName: 'orders' },
      outcome: makeOutcome('completed'),
      summary: makeSummary(),
      config: makeConfig(),
    })
    expect(useScheduleStore.getState().history).toHaveLength(1)
    expect(loadHistory()[0].scheduleName).toBe('Reusable nightly')
    expect(loadHistory()[0].id).toBeTruthy() // id generated by the store
  })

  // ── importSchedules (the D7 later phase) ──────────────────────────────────

  it('imports schedules with nextRunAt recomputed: future once kept, past once consumed, past interval advanced', () => {
    const future = new Date(Date.now() + 3600_000).toISOString()
    const past = new Date(Date.now() - 3600_000).toISOString()
    const incoming: ScheduledRun[] = [
      makeSchedule({ id: 'f', name: 'Future once', schedule: { kind: 'once', runAt: future }, nextRunAt: future }),
      makeSchedule({ id: 'p', name: 'Past once', schedule: { kind: 'once', runAt: past }, nextRunAt: past }),
      makeSchedule({
        id: 'i',
        name: 'Past interval',
        schedule: { kind: 'interval', firstRunAt: past, everyMinutes: 120 },
        nextRunAt: past,
      }),
    ]

    const result = useScheduleStore.getState().importSchedules(incoming)
    expect(result).toEqual({ added: 3, skipped: [] })

    const byId = Object.fromEntries(loadAllSchedules().map((s) => [s.id, s]))
    // Future one-shot: slot kept, enabled.
    expect(byId['f'].nextRunAt).toBe(future)
    expect(byId['f'].enabled).toBe(true)
    // Past one-shot: consumed on import — NEVER fires a backlog (§7.4 rule).
    expect(byId['p'].nextRunAt).toBeNull()
    expect(byId['p'].enabled).toBe(false)
    // Past interval: advanced to its next FUTURE slot, stays enabled.
    expect(new Date(byId['i'].nextRunAt!).getTime()).toBeGreaterThan(Date.now())
    expect(byId['i'].enabled).toBe(true)
  })

  it('skips duplicate ids against storage and within the batch, reporting them', () => {
    useScheduleStore.getState().addSchedule(makeSchedule({ id: 'existing', name: 'Already here' }))
    const result = useScheduleStore.getState().importSchedules([
      makeSchedule({ id: 'existing', name: 'Clobber attempt' }),
      makeSchedule({ id: 'fresh', name: 'Fresh' }),
      makeSchedule({ id: 'fresh', name: 'Fresh again' }),
    ])
    expect(result.added).toBe(1)
    expect(result.skipped).toEqual(['existing', 'fresh'])
    expect(loadAllSchedules().find((s) => s.id === 'existing')!.name).toBe('Already here')
  })

  // ── recordManualRun (manual-run history, the second later phase) ──────────

  it('recordManualRun appends a history record named for the manual run', () => {
    const config = makeConfig()
    useScheduleStore.getState().recordManualRun(config, 'completed', makeSummary())

    const record = useScheduleStore.getState().history[0]
    expect(record.scheduleId).toBe('manual')
    expect(record.scheduleName).toBe('Manual run — T @ orders')
    expect(record.outcome.result).toBe('completed')
    expect(record.outcome.totalSent).toBe(50)
    expect(record.target).toEqual({ setupId: 's1', queueName: 'orders' })
    // The record's config is a frozen copy, not the caller's reference.
    expect(record.config).toEqual(config)
    expect(record.config).not.toBe(config)
    expect(loadHistory()).toHaveLength(1)
  })

  it('recordManualRun records error terminals with the reason', () => {
    useScheduleStore.getState().recordManualRun(
      makeConfig(),
      'error',
      { ...makeSummary(), totalSent: 0, finalStatus: 'error' },
      'Template failed to resolve'
    )
    const record = useScheduleStore.getState().history[0]
    expect(record.outcome.result).toBe('error')
    expect(record.outcome.detail).toBe('Template failed to resolve')
  })

  it('saveAsTemplate and removeTemplate persist', () => {
    useScheduleStore.getState().saveAsTemplate('Reusable', makeConfig())
    expect(loadTemplates()).toHaveLength(1)
    expect(loadTemplates()[0].name).toBe('Reusable')

    const id = useScheduleStore.getState().templates[0].id
    useScheduleStore.getState().removeTemplate(id)
    expect(loadTemplates()).toHaveLength(0)
  })
})

describe('computeNextRunAt (§7.3)', () => {
  const now = new Date('2026-07-19T12:00:00.000Z')

  it('once: returns runAt while it is still in the future', () => {
    const runAt = '2026-07-19T13:00:00.000Z'
    expect(computeNextRunAt({ kind: 'once', runAt }, now)).toBe(runAt)
  })

  it('once: consumed (null) when its time has passed', () => {
    expect(computeNextRunAt({ kind: 'once', runAt: '2026-07-19T11:00:00.000Z' }, now)).toBeNull()
  })

  it('interval: returns firstRunAt while it is still in the future', () => {
    const firstRunAt = '2026-07-19T14:00:00.000Z'
    expect(computeNextRunAt({ kind: 'interval', firstRunAt, everyMinutes: 30 }, now)).toBe(firstRunAt)
  })

  it('interval: advances to the smallest slot strictly after now — no catch-up bursts', () => {
    // firstRunAt 09:00, every 60 min → slots 09,10,11,12,13… now = 12:00 exactly.
    // 12:00 is NOT strictly after now → 13:00.
    const next = computeNextRunAt(
      { kind: 'interval', firstRunAt: '2026-07-19T09:00:00.000Z', everyMinutes: 60 },
      now
    )
    expect(next).toBe('2026-07-19T13:00:00.000Z')
  })

  it('interval: skips many missed slots at once', () => {
    const next = computeNextRunAt(
      { kind: 'interval', firstRunAt: '2026-07-01T00:00:00.000Z', everyMinutes: 1440 }, // daily
      now
    )
    expect(next).toBe('2026-07-20T00:00:00.000Z')
  })
})
