/**
 * Systematic load-validation and export boundary tests for scheduleService
 * (design R11/D8 — the schedules export file is the future import format, so
 * its validation must already be exact; storage content is untrusted after
 * manual edits or version drift).
 *
 * Dimensions: per-entity field validation for all three storage keys, the
 * schedule-spec discriminated union, RANGE rules on the embedded RunConfig
 * (added test-first — the UI bounds these values, so storage must enforce the
 * same), the D8 caps at their exact boundaries, and export round-trips.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  loadAllSchedules,
  saveAllSchedules,
  loadHistory,
  saveHistory,
  loadTemplates,
  saveTemplates,
  exportAllSchedules,
  HISTORY_MAX_ENTRIES,
  HISTORY_MAX_ERRORS_PER_ENTRY,
} from '../../services/scheduleService'
import type { ScheduledRun, ScheduleRunRecord, ScheduleTemplate } from '../../types/schedule'
import type { MessageTemplate, RunConfig, RunSummary, PublishError } from '../../types/generator'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1', name: 'T', messageType: 'x', payloadSchema: '{}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1', queueName: 'orders', rate: 10, durationSecs: 60,
    maxBatchSize: 10, warnThreshold: 500, maxConsecErrors: 10,
    template: makeTemplate(), previewIndex: 1, ...overrides,
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

function makeError(i: number): PublishError {
  return { messageIndex: i, httpStatus: 500, message: `boom ${i}`, timestamp: new Date().toISOString() }
}

function makeSummary(errorCount = 0): RunSummary {
  return {
    totalSent: 100, targetTotal: 100, avgRate: 10, durationMs: 10000,
    totalErrors: errorCount, finalStatus: 'completed', runId: 'r1',
    errors: Array.from({ length: errorCount }, (_, i) => makeError(i)),
  }
}

function makeRecord(overrides: Partial<ScheduleRunRecord> = {}): ScheduleRunRecord {
  return {
    id: crypto.randomUUID(),
    scheduleId: 'sched-1',
    scheduleName: 'Nightly load',
    target: { setupId: 's1', queueName: 'orders' },
    outcome: { at: new Date().toISOString(), result: 'completed', totalSent: 100, totalErrors: 0 },
    summary: makeSummary(),
    config: makeConfig(),
    ...overrides,
  }
}

function makeScheduleTemplate(overrides: Partial<ScheduleTemplate> = {}): ScheduleTemplate {
  const now = new Date().toISOString()
  return { id: crypto.randomUUID(), name: 'Reusable', config: makeConfig(), createdAt: now, updatedAt: now, ...overrides }
}

/** Write raw (unvalidated) content into a storage key. */
function writeRaw(key: string, value: unknown) {
  localStorage.setItem(key, JSON.stringify(value))
}

describe('scheduleService load — schedule entry validation', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it.each([
    ['id missing', (s: ScheduledRun) => ({ ...s, id: undefined })],
    ['name empty', (s: ScheduledRun) => ({ ...s, name: '' })],
    ['enabled as string', (s: ScheduledRun) => ({ ...s, enabled: 'yes' })],
    ['nextRunAt as number', (s: ScheduledRun) => ({ ...s, nextRunAt: 12345 })],
    ['config missing', (s: ScheduledRun) => ({ ...s, config: undefined })],
    ['embedded template broken', (s: ScheduledRun) => ({ ...s, config: { ...s.config, template: { id: 'x' } } })],
  ])('drops a schedule with %s and keeps the valid one', (_label, mutate) => {
    const good = makeSchedule({ name: 'Valid schedule' })
    writeRaw('peegeeq_generator_schedules', [good, mutate(makeSchedule())])
    expect(loadAllSchedules()).toEqual([good])
  })

  it('accepts nextRunAt null (a consumed one-shot)', () => {
    const consumed = makeSchedule({ nextRunAt: null, enabled: false })
    saveAllSchedules([consumed])
    expect(loadAllSchedules()).toEqual([consumed])
  })
})

describe('scheduleService load — schedule-spec discriminated union', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('accepts both spec kinds', () => {
    const once = makeSchedule({ schedule: { kind: 'once', runAt: '2026-08-01T00:00:00.000Z' } })
    const interval = makeSchedule({
      schedule: { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z', everyMinutes: 30 },
    })
    saveAllSchedules([once, interval])
    expect(loadAllSchedules()).toHaveLength(2)
  })

  it.each([
    ['an unknown kind', { kind: 'cron', expression: '* * * * *' }],
    ['once without runAt', { kind: 'once' }],
    ['interval without everyMinutes', { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z' }],
    ['interval with everyMinutes 0', { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z', everyMinutes: 0 }],
    ['interval with negative everyMinutes', { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z', everyMinutes: -5 }],
    ['interval with fractional everyMinutes', { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z', everyMinutes: 1.5 }],
  ])('drops a schedule whose spec is %s', (_label, spec) => {
    writeRaw('peegeeq_generator_schedules', [{ ...makeSchedule(), schedule: spec }])
    expect(loadAllSchedules()).toEqual([])
  })
})

describe('scheduleService load — RunConfig RANGE validation (storage must enforce the UI bounds)', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it.each([
    ['rate 0', { rate: 0 }],
    ['rate negative', { rate: -10 }],
    ['durationSecs 0', { durationSecs: 0 }],
    ['durationSecs above 3600', { durationSecs: 3601 }],
    ['maxBatchSize 0', { maxBatchSize: 0 }],
    ['maxBatchSize above 100', { maxBatchSize: 101 }],
    ['warnThreshold negative', { warnThreshold: -1 }],
    ['maxConsecErrors negative', { maxConsecErrors: -1 }],
  ])('drops a schedule whose config has %s', (_label, override) => {
    writeRaw('peegeeq_generator_schedules', [makeSchedule({ config: makeConfig(override as Partial<RunConfig>) })])
    expect(loadAllSchedules()).toEqual([])
  })

  it('accepts the boundary values (rate 1, duration 1 and 3600, batch 1 and 100, thresholds 0)', () => {
    const schedules = [
      makeSchedule({ name: 'Low', config: makeConfig({ rate: 1, durationSecs: 1, maxBatchSize: 1, warnThreshold: 0, maxConsecErrors: 0 }) }),
      makeSchedule({ name: 'High', config: makeConfig({ durationSecs: 3600, maxBatchSize: 100 }) }),
    ]
    saveAllSchedules(schedules)
    expect(loadAllSchedules()).toHaveLength(2)
  })
})

describe('scheduleService load — history and template validation', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it.each([
    ['an unknown outcome result', (r: ScheduleRunRecord) => ({ ...r, outcome: { ...r.outcome, result: 'exploded' } })],
    ['a missing target', (r: ScheduleRunRecord) => ({ ...r, target: undefined })],
    ['totalSent as string', (r: ScheduleRunRecord) => ({ ...r, outcome: { ...r.outcome, totalSent: '5' } })],
  ])('drops a history record with %s', (_label, mutate) => {
    const good = makeRecord({ scheduleName: 'Valid record' })
    writeRaw('peegeeq_schedule_run_history', [good, mutate(makeRecord())])
    expect(loadHistory()).toEqual([good])
  })

  it('accepts all five outcome results', () => {
    const records = (['completed', 'stopped', 'error', 'skipped', 'missed'] as const).map((result) =>
      makeRecord({ outcome: { at: new Date().toISOString(), result, totalSent: 0, totalErrors: 0 } })
    )
    saveHistory(records)
    expect(loadHistory()).toHaveLength(5)
  })

  it('accepts a null summary (skipped/missed records)', () => {
    saveHistory([makeRecord({ summary: null })])
    expect(loadHistory()[0].summary).toBeNull()
  })

  it('drops a template with a broken embedded config, keeping the valid one', () => {
    const good = makeScheduleTemplate({ name: 'Valid tpl' })
    writeRaw('peegeeq_schedule_templates', [good, { ...makeScheduleTemplate(), config: { setupId: 's1' } }])
    expect(loadTemplates()).toEqual([good])
  })
})

describe('scheduleService — D8 caps at exact boundaries', () => {
  beforeEach(() => localStorage.clear())

  it('stores exactly HISTORY_MAX_ENTRIES when given exactly that many', () => {
    saveHistory(Array.from({ length: HISTORY_MAX_ENTRIES }, () => makeRecord()))
    expect(loadHistory()).toHaveLength(HISTORY_MAX_ENTRIES)
  })

  it('drops exactly one when given one over the cap', () => {
    const records = Array.from({ length: HISTORY_MAX_ENTRIES + 1 }, (_, i) => makeRecord({ scheduleName: `r${i}` }))
    saveHistory(records)
    const loaded = loadHistory()
    expect(loaded).toHaveLength(HISTORY_MAX_ENTRIES)
    expect(loaded[0].scheduleName).toBe('r0')
    expect(loaded.at(-1)!.scheduleName).toBe(`r${HISTORY_MAX_ENTRIES - 1}`)
  })

  it('keeps exactly HISTORY_MAX_ERRORS_PER_ENTRY errors and the truthful total', () => {
    saveHistory([makeRecord({ summary: makeSummary(HISTORY_MAX_ERRORS_PER_ENTRY + 5) })])
    const loaded = loadHistory()[0]
    expect(loaded.summary!.errors).toHaveLength(HISTORY_MAX_ERRORS_PER_ENTRY)
    expect(loaded.summary!.totalErrors).toBe(HISTORY_MAX_ERRORS_PER_ENTRY + 5)
  })

  it('leaves a summary at exactly the error cap untouched', () => {
    const summary = makeSummary(HISTORY_MAX_ERRORS_PER_ENTRY)
    saveHistory([makeRecord({ summary })])
    expect(loadHistory()[0].summary!.errors).toHaveLength(HISTORY_MAX_ERRORS_PER_ENTRY)
  })
})

describe('scheduleService export (R11)', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('the exported file parses back to the exact schedule array (the future import contract)', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const schedules = [
      makeSchedule({ name: 'Once — ünïcode' }),
      makeSchedule({ schedule: { kind: 'interval', firstRunAt: '2026-08-01T00:00:00.000Z', everyMinutes: 60 }, name: 'Interval' }),
    ]

    exportAllSchedules(schedules)

    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as string)
      reader.readAsText(blob)
    })
    expect(JSON.parse(text)).toEqual(schedules)
  })

  it('downloads under the fixed name schedules.json', () => {
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:fake'), revokeObjectURL: vi.fn() })
    const downloads: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.download)
    })
    exportAllSchedules([makeSchedule()])
    expect(downloads).toEqual(['schedules.json'])
  })

  it('an exported array survives a save/load cycle through validation unchanged', () => {
    const schedules = [makeSchedule({ name: 'Survivor' })]
    saveAllSchedules(schedules)
    expect(loadAllSchedules()).toEqual(schedules)
  })
})
