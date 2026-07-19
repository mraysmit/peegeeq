/**
 * CRUD edge cases for the four zustand stores, deepening their base suites.
 *
 * Dimensions: operations on non-existent ids/names (no-ops, no state damage),
 * duplicate-id/name behaviour and its persistence consequences, timestamp and
 * selection semantics on update, reference sharing in snapshots, and run-state
 * operations outside the normal lifecycle order.
 *
 * All tests run against the real stores and real localStorage.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useTemplateStore } from '../../stores/templateStore'
import { useValueListStore } from '../../stores/valueListStore'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useScheduleStore } from '../../stores/scheduleStore'
import { loadAll as loadTemplatesRaw } from '../../services/templateService'
import { loadAll as loadListsRaw } from '../../services/valueListService'
import { loadAllSchedules } from '../../services/scheduleService'
import type { MessageTemplate, RunConfig, ValueList } from '../../types/generator'
import type { ScheduledRun } from '../../types/schedule'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(), name: 'T', messageType: 'x', payloadSchema: '{}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now, ...overrides,
  }
}

function makeList(name: string, values: string[]): ValueList {
  const now = new Date().toISOString()
  return { name, values, createdAt: now, updatedAt: now }
}

function makeConfig(): RunConfig {
  return {
    setupId: 's1', queueName: 'q', rate: 10, durationSecs: 60, maxBatchSize: 10,
    warnThreshold: 0, maxConsecErrors: 0, template: makeTemplate(), previewIndex: 1,
  }
}

function makeSchedule(overrides: Partial<ScheduledRun> = {}): ScheduledRun {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(), name: 'S', config: makeConfig(),
    schedule: { kind: 'once', runAt: new Date(Date.now() + 3600_000).toISOString() },
    enabled: true, nextRunAt: new Date(Date.now() + 3600_000).toISOString(),
    createdAt: now, updatedAt: now, ...overrides,
  }
}

beforeEach(() => {
  localStorage.clear()
  useTemplateStore.setState({ templates: [], selected: null })
  useValueListStore.setState({ lists: [], selected: null })
  useGeneratorStore.getState().resetRun()
  useGeneratorStore.setState({ config: null })
  useScheduleStore.setState({ schedules: [], history: [], templates: [] })
})

describe('templateStore — operations on non-existent ids', () => {
  it('update with an unknown id changes nothing', () => {
    const existing = makeTemplate({ id: 'keep', messageType: 'v1' })
    useTemplateStore.getState().add(existing)
    useTemplateStore.getState().update(makeTemplate({ id: 'ghost', messageType: 'v2' }))
    expect(useTemplateStore.getState().templates).toHaveLength(1)
    expect(useTemplateStore.getState().templates[0].messageType).toBe('v1')
  })

  it('remove with an unknown id changes nothing and keeps the selection', () => {
    const existing = makeTemplate({ id: 'keep' })
    useTemplateStore.getState().add(existing)
    useTemplateStore.getState().select('keep')
    useTemplateStore.getState().remove('ghost')
    expect(useTemplateStore.getState().templates).toHaveLength(1)
    expect(useTemplateStore.getState().selected?.id).toBe('keep')
  })

  it('duplicate with an unknown id changes nothing', () => {
    useTemplateStore.getState().add(makeTemplate())
    useTemplateStore.getState().duplicate('ghost')
    expect(useTemplateStore.getState().templates).toHaveLength(1)
  })

  it('select with an unknown id results in a null selection', () => {
    useTemplateStore.getState().add(makeTemplate({ id: 'keep' }))
    useTemplateStore.getState().select('ghost')
    expect(useTemplateStore.getState().selected).toBeNull()
  })
})

describe('templateStore — update and duplicate semantics', () => {
  it('update preserves createdAt, refreshes updatedAt, and syncs the selection', () => {
    const original = makeTemplate({ id: 'a', createdAt: '2026-01-01T00:00:00.000Z', updatedAt: '2026-01-01T00:00:00.000Z' })
    useTemplateStore.getState().add(original)
    useTemplateStore.getState().select('a')

    useTemplateStore.getState().update({ ...original, messageType: 'changed' })

    const stored = useTemplateStore.getState().templates[0]
    expect(stored.createdAt).toBe('2026-01-01T00:00:00.000Z')
    expect(stored.updatedAt).not.toBe('2026-01-01T00:00:00.000Z')
    expect(useTemplateStore.getState().selected?.messageType).toBe('changed')
  })

  it('duplicating a duplicate appends "(copy)" again', () => {
    useTemplateStore.getState().add(makeTemplate({ id: 'a', name: 'Base' }))
    useTemplateStore.getState().duplicate('a')
    const copy = useTemplateStore.getState().templates.find((t) => t.name === 'Base (copy)')!
    useTemplateStore.getState().duplicate(copy.id)
    expect(useTemplateStore.getState().templates.map((t) => t.name)).toContain('Base (copy) (copy)')
  })

  it('add does NOT guard against duplicate ids — callers guard (documented)', () => {
    useTemplateStore.getState().add(makeTemplate({ id: 'dup', messageType: 'v1' }))
    useTemplateStore.getState().add(makeTemplate({ id: 'dup', messageType: 'v2' }))
    // Two in-memory entries share the id; persistence stores both. The editor's
    // Save guards (update-if-exists), and import skips duplicates — the store
    // itself is a plain collection.
    expect(useTemplateStore.getState().templates).toHaveLength(2)
    expect(loadTemplatesRaw()).toHaveLength(2)
  })

  it('importTemplates with an empty array reports zero added and zero skipped', () => {
    expect(useTemplateStore.getState().importTemplates([])).toEqual({ added: 0, skipped: [] })
  })
})

describe('valueListStore — name-keyed edge cases', () => {
  it('update with an unknown name changes nothing', () => {
    useValueListStore.getState().add(makeList('keep', ['a']))
    useValueListStore.getState().update(makeList('ghost', ['b']))
    expect(loadListsRaw()).toEqual({ keep: ['a'] })
  })

  it('remove with an unknown name changes nothing', () => {
    useValueListStore.getState().add(makeList('keep', ['a']))
    useValueListStore.getState().remove('ghost')
    expect(useValueListStore.getState().lists).toHaveLength(1)
  })

  it('select with an unknown name results in a null selection', () => {
    useValueListStore.getState().add(makeList('keep', ['a']))
    useValueListStore.getState().select('ghost')
    expect(useValueListStore.getState().selected).toBeNull()
  })

  it('adding a duplicate name keeps two in memory but persistence collapses to the LAST (documented quirk)', () => {
    useValueListStore.getState().add(makeList('dup', ['first']))
    useValueListStore.getState().add(makeList('dup', ['second']))
    expect(useValueListStore.getState().lists).toHaveLength(2)
    // The persisted form is a name→values map, so the last write wins there.
    expect(loadListsRaw()).toEqual({ dup: ['second'] })
    // A reload from storage resolves the divergence to one list.
    useValueListStore.getState().loadFromStorage()
    expect(useValueListStore.getState().lists).toHaveLength(1)
    // Callers (the manager page) guard name collisions before calling add.
  })

  it('snapshot shares the value arrays by reference — consumers must treat it as read-only (documented)', () => {
    useValueListStore.getState().add(makeList('l', ['a']))
    const snapshot = useValueListStore.getState().snapshot()
    snapshot['l'].push('mutated')
    expect(useValueListStore.getState().lists[0].values).toContain('mutated')
  })

  it('importList with an empty values array creates an empty list (callers pre-validate)', () => {
    const result = useValueListStore.getState().importList('empty', [], 'overwrite')
    expect(result).toEqual({ name: 'empty', added: 0, total: 0 })
    expect(loadListsRaw()['empty']).toEqual([])
  })
})

describe('generatorStore — operations outside the normal lifecycle order', () => {
  it('tickUpdate while idle mutates counters without a status guard (documented: only the engine calls it)', () => {
    useGeneratorStore.getState().tickUpdate(5, [], 0, 1000)
    const { runState } = useGeneratorStore.getState()
    expect(runState.status).toBe('idle')
    expect(runState.sent).toBe(5)
  })

  it('transitionTo works from idle (no state-machine guard at store level)', () => {
    useGeneratorStore.getState().transitionTo('error', 'direct')
    expect(useGeneratorStore.getState().runState.status).toBe('error')
    expect(useGeneratorStore.getState().runState.autoStopReason).toBe('direct')
  })

  it('a second startRun resets counters and issues a fresh runId', () => {
    const store = useGeneratorStore.getState()
    store.setConfig(makeConfig())
    store.startRun()
    const firstRunId = useGeneratorStore.getState().runState.runId
    useGeneratorStore.getState().tickUpdate(50, [], 0, 5000)

    useGeneratorStore.getState().startRun()
    const state = useGeneratorStore.getState().runState
    expect(state.sent).toBe(0)
    expect(state.runId).not.toBe(firstRunId)
  })

  it('resetRun twice is harmless', () => {
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.getState().resetRun()
    expect(useGeneratorStore.getState().runState.status).toBe('idle')
  })
})

describe('scheduleStore — operations on non-existent ids', () => {
  it('updateSchedule with an unknown id changes nothing', () => {
    useScheduleStore.getState().addSchedule(makeSchedule({ name: 'keep' }))
    useScheduleStore.getState().updateSchedule(makeSchedule({ id: 'ghost', name: 'ghost' }))
    expect(loadAllSchedules()).toHaveLength(1)
    expect(loadAllSchedules()[0].name).toBe('keep')
  })

  it('setEnabled with an unknown id changes nothing', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().setEnabled('ghost', false)
    expect(useScheduleStore.getState().schedules[0].enabled).toBe(true)
  })

  it('removeTemplate with an unknown id changes nothing', () => {
    useScheduleStore.getState().saveAsTemplate('keep', makeConfig())
    useScheduleStore.getState().removeTemplate('ghost')
    expect(useScheduleStore.getState().templates).toHaveLength(1)
  })

  it('latestOutcomeFor also finds records appended without a schedule (template run-now)', () => {
    useScheduleStore.getState().appendHistoryRecord({
      scheduleId: 'template:tp-1',
      scheduleName: 'Tpl',
      target: { setupId: 's1', queueName: 'q' },
      outcome: { at: new Date().toISOString(), result: 'completed', totalSent: 5, totalErrors: 0 },
      summary: null,
      config: makeConfig(),
    })
    expect(useScheduleStore.getState().latestOutcomeFor('template:tp-1')?.outcome.totalSent).toBe(5)
  })
})
