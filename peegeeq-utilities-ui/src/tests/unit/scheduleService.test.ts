/**
 * Tests for scheduleService.ts (Scheduled Runs design §5 — SCH.1).
 *
 * Contract under test (written FIRST, before the service):
 * - round-trips for the three storage keys (schedules, history, templates)
 * - per-entry Zod validation on load: one corrupt entry is dropped and REPORTED
 *   (named), the valid entries survive — never a blanked list
 * - the history write path enforces the D8 bounds: 200 entries FIFO (oldest
 *   dropped), stored summary errors capped at 20
 * - exportAllSchedules downloads the full ScheduledRun[] as schedules.json
 * - a storage write failure (quota exceeded, storage disabled) is surfaced and
 *   contained — it must not propagate as an uncaught exception into the click
 *   handler or scheduler that triggered the save
 *
 * No mocks: real localStorage. URL.createObjectURL polyfilled for export (jsdom).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { message } from 'antd'
import {
  loadAllSchedules,
  saveAllSchedules,
  loadHistory,
  saveHistory,
  loadTemplates,
  saveTemplates,
  exportAllSchedules,
  importSchedulesFromFile,
} from '../../services/scheduleService'
import type {
  ScheduledRun,
  ScheduleRunRecord,
  ScheduleTemplate,
} from '../../types/schedule'
import type { MessageTemplate, RunConfig, RunSummary, PublishError } from '../../types/generator'

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
    outcome: {
      at: new Date().toISOString(),
      result: 'completed',
      totalSent: 100,
      totalErrors: 0,
    },
    summary: makeSummary(),
    config: makeConfig(),
    ...overrides,
  }
}

function makeScheduleTemplate(overrides: Partial<ScheduleTemplate> = {}): ScheduleTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Reusable nightly',
    config: makeConfig(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

describe('scheduleService', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  // ── Schedules ─────────────────────────────────────────────────────────────

  it('round-trips schedules through localStorage', () => {
    const schedules = [makeSchedule(), makeSchedule({ name: 'Second' })]
    saveAllSchedules(schedules)
    expect(loadAllSchedules()).toEqual(schedules)
  })

  it('drops a corrupt schedule entry with a named report, keeping valid entries', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const good = makeSchedule({ name: 'Good one' })
    localStorage.setItem(
      'peegeeq_generator_schedules',
      JSON.stringify([good, { id: 'bad', name: 'Broken entry' }])
    )
    const loaded = loadAllSchedules()
    expect(loaded).toEqual([good])
    expect(consoleError).toHaveBeenCalled()
    expect(String(consoleError.mock.calls[0])).toContain('Broken entry')
  })

  it('returns [] for absent or non-array schedule storage', () => {
    expect(loadAllSchedules()).toEqual([])
    localStorage.setItem('peegeeq_generator_schedules', '{"not":"an array"}')
    expect(loadAllSchedules()).toEqual([])
  })

  it('reports dropped entries to the USER, not only the console', () => {
    // Dropped entries are user data (their schedules); a console-only report
    // is invisible to them.
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const messageError = vi.spyOn(message, 'error').mockImplementation((() => {}) as never)
    localStorage.setItem(
      'peegeeq_generator_schedules',
      JSON.stringify([makeSchedule({ name: 'Good one' }), { id: 'bad', name: 'Broken entry' }])
    )

    loadAllSchedules()

    expect(messageError).toHaveBeenCalled()
    expect(String(messageError.mock.calls[0][0])).toContain('Broken entry')
  })

  // ── Storage write failures (quota) ────────────────────────────────────────

  it('a storage write failure is surfaced and contained, not thrown into the caller', () => {
    // QuotaExceededError out of a zustand set() reaches the triggering click
    // handler (or the scheduler's terminal callback) as an uncaught exception.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })

    expect(() => saveAllSchedules([makeSchedule()])).not.toThrow()
    expect(() => saveHistory([makeRecord()])).not.toThrow()
    expect(() => saveTemplates([makeScheduleTemplate()])).not.toThrow()
    expect(consoleError).toHaveBeenCalledTimes(3)

    setItem.mockRestore()
    // With storage working again, the next write succeeds normally.
    const schedules = [makeSchedule()]
    saveAllSchedules(schedules)
    expect(loadAllSchedules()).toEqual(schedules)
  })

  // ── History (D8 bounds) ───────────────────────────────────────────────────

  it('round-trips history records', () => {
    const records = [makeRecord(), makeRecord({ outcome: { at: new Date().toISOString(), result: 'missed', totalSent: 0, totalErrors: 0 } })]
    saveHistory(records)
    expect(loadHistory()).toEqual(records)
  })

  it('caps history at 200 entries, dropping the oldest (end of the newest-first array)', () => {
    const records = Array.from({ length: 205 }, (_, i) => makeRecord({ scheduleName: `run-${i}` }))
    saveHistory(records)
    const loaded = loadHistory()
    expect(loaded).toHaveLength(200)
    expect(loaded[0].scheduleName).toBe('run-0')      // newest kept
    expect(loaded[199].scheduleName).toBe('run-199')  // 200th kept
  })

  it('caps stored summary errors at 20 per record', () => {
    saveHistory([makeRecord({ summary: makeSummary(30) })])
    const loaded = loadHistory()
    expect(loaded[0].summary!.errors).toHaveLength(20)
    expect(loaded[0].summary!.totalErrors).toBe(30) // the COUNT stays truthful
  })

  it('drops a corrupt history entry with a named report', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const good = makeRecord({ scheduleName: 'Good record' })
    localStorage.setItem('peegeeq_schedule_run_history', JSON.stringify([good, { id: 'bad' }]))
    expect(loadHistory()).toEqual([good])
    expect(consoleError).toHaveBeenCalled()
  })

  // ── Templates ─────────────────────────────────────────────────────────────

  it('round-trips schedule templates and validates per entry', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const good = makeScheduleTemplate()
    saveTemplates([good])
    expect(loadTemplates()).toEqual([good])

    localStorage.setItem('peegeeq_schedule_templates', JSON.stringify([good, { id: 'bad', name: 'Broken tpl' }]))
    expect(loadTemplates()).toEqual([good])
    expect(consoleError).toHaveBeenCalled()
  })

  // ── Import (the D7 later phase: consumes the R11 export format) ──────────

  it('imports a valid schedules.json array', async () => {
    const schedules = [makeSchedule({ name: 'Imported A' }), makeSchedule({ name: 'Imported B' })]
    const file = new File([JSON.stringify(schedules)], 'schedules.json', { type: 'application/json' })
    const result = await importSchedulesFromFile(file)
    expect(result.schedules).toEqual(schedules)
    expect(result.errors).toEqual([])
  })

  it('accepts a single schedule object not wrapped in an array', async () => {
    const schedule = makeSchedule({ name: 'Single' })
    const file = new File([JSON.stringify(schedule)], 'one.json', { type: 'application/json' })
    const result = await importSchedulesFromFile(file)
    expect(result.schedules).toEqual([schedule])
  })

  it('rejects a non-JSON file, naming it', async () => {
    const file = new File(['not json'], 'broken.json', { type: 'application/json' })
    const result = await importSchedulesFromFile(file)
    expect(result.schedules).toEqual([])
    expect(result.errors[0]).toContain('broken.json')
    expect(result.errors[0]).toMatch(/not valid JSON/i)
  })

  it('keeps valid entries and names each invalid one', async () => {
    const good = makeSchedule({ name: 'Good import' })
    const file = new File(
      [JSON.stringify([good, { id: 'x', name: 'Broken import' }, { no: 'shape' }])],
      'mixed.json',
      { type: 'application/json' }
    )
    const result = await importSchedulesFromFile(file)
    expect(result.schedules).toEqual([good])
    expect(result.errors).toHaveLength(2)
    expect(result.errors[0]).toContain('Broken import')
  })

  it('applies the full schema on import — an out-of-range config is rejected by name', async () => {
    const broken = makeSchedule({ name: 'Bad range' })
    ;(broken.config as { rate: number }).rate = 0
    const file = new File([JSON.stringify([broken])], 'range.json', { type: 'application/json' })
    const result = await importSchedulesFromFile(file)
    expect(result.schedules).toEqual([])
    expect(result.errors[0]).toContain('Bad range')
  })

  // ── Export (R11) ──────────────────────────────────────────────────────────

  it('exportAllSchedules downloads the full array as schedules.json', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })

    const schedules = [makeSchedule({ name: 'Exported' })]
    exportAllSchedules(schedules)

    expect(createObjectURL).toHaveBeenCalledOnce()
    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as string)
      reader.readAsText(blob)
    })
    const parsed = JSON.parse(text) as ScheduledRun[]
    expect(parsed).toHaveLength(1)
    expect(parsed[0].name).toBe('Exported')
  })
})
