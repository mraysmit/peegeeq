/**
 * Tests for schedulerRuntime.ts (Scheduled Runs design §7 — SCH.3).
 *
 * Contract under test (written FIRST, before the module):
 * - app start NEVER auto-fires: every overdue enabled schedule records a
 *   `missed` outcome with the overdue duration and advances/disables (D3)
 * - while the app runs, a due schedule fires through runStarter at the next
 *   15-second check; the outcome and summary land in the run history and the
 *   schedule advances (a one-shot is consumed and disabled)
 * - an interval schedule advances to its next future slot and stays enabled
 * - a schedule due while ANY run is active records `skipped` and advances (D4);
 *   with two schedules due in one check, the earliest fires and the second
 *   records `skipped`
 * - fire-time missing value lists are recorded in the outcome detail (§6)
 * - the localStorage lease: a non-holding tab never fires; an expired lease is
 *   taken over (§7.5)
 * - a corrupt schedule cannot break the check: it records an `error` outcome,
 *   is disabled, and later schedules still process (§10)
 *
 * Fake timers drive the 15 s checks; the publish network boundary is mocked as
 * in the engine tests; everything else is the real stores, runStarter, engine.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createSchedulerRuntime, CHECK_INTERVAL_MS } from '../../engine/schedulerRuntime'
import { useScheduleStore } from '../../stores/scheduleStore'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useValueListStore } from '../../stores/valueListStore'
import { startGeneratorRun } from '../../engine/runStarter'
import { publishBatch } from '../../services/publishService'
import type { MessageTemplate, RunConfig } from '../../types/generator'
import type { ScheduledRun, ScheduleSpec } from '../../types/schedule'

vi.mock('../../services/publishService')
const mockedPublishBatch = vi.mocked(publishBatch)

const BASE_TIME = new Date('2026-07-19T12:00:00.000Z')

function makeTemplate(payloadSchema = '{"id":"{{messageId}}"}'): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1', name: 'T', messageType: 'x', payloadSchema, headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1', queueName: 'orders', rate: 5, durationSecs: 1,
    maxBatchSize: 10, warnThreshold: 0, maxConsecErrors: 0,
    template: makeTemplate(), previewIndex: 1, ...overrides,
  }
}

/** A schedule due `dueInMs` from BASE_TIME (negative = overdue at start). */
function makeSchedule(dueInMs: number, overrides: Partial<ScheduledRun> = {}): ScheduledRun {
  const runAt = new Date(BASE_TIME.getTime() + dueInMs).toISOString()
  const now = BASE_TIME.toISOString()
  return {
    id: crypto.randomUUID(),
    name: `S@${dueInMs}`,
    config: makeConfig(),
    schedule: { kind: 'once', runAt },
    enabled: true,
    nextRunAt: runAt,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function history() {
  return useScheduleStore.getState().history
}

describe('schedulerRuntime', () => {
  let runtime: ReturnType<typeof createSchedulerRuntime>

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(BASE_TIME)
    localStorage.clear()
    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
    useValueListStore.setState({ lists: [], selected: null })
    mockedPublishBatch.mockResolvedValue({ messagesSent: 5 })
    runtime = createSchedulerRuntime('tab-under-test')
  })

  afterEach(() => {
    runtime.stop()
    vi.useRealTimers()
  })

  // ── D3: app start never auto-fires ───────────────────────────────────────

  it('marks every overdue schedule missed on start and fires NOTHING', async () => {
    useScheduleStore.getState().addSchedule(makeSchedule(-600_000, { name: 'Overdue 10m' }))
    useScheduleStore.getState().addSchedule(makeSchedule(-5_000, { name: 'Overdue 5s' }))

    runtime.start()
    await vi.advanceTimersByTimeAsync(0)

    expect(mockedPublishBatch).not.toHaveBeenCalled()
    expect(history()).toHaveLength(2)
    for (const record of history()) {
      expect(record.outcome.result).toBe('missed')
      expect(record.outcome.detail).toMatch(/app was not open/i)
    }
    // Consumed one-shots are disabled.
    for (const s of useScheduleStore.getState().schedules) {
      expect(s.enabled).toBe(false)
      expect(s.nextRunAt).toBeNull()
    }
  })

  it('records the overdue duration in the missed detail', () => {
    useScheduleStore.getState().addSchedule(makeSchedule(-90_000))
    runtime.start()
    expect(history()[0].outcome.detail).toContain('90s')
  })

  it('a future schedule is untouched by start', () => {
    useScheduleStore.getState().addSchedule(makeSchedule(60_000))
    runtime.start()
    expect(history()).toHaveLength(0)
    expect(useScheduleStore.getState().schedules[0].enabled).toBe(true)
  })

  // ── Firing while the app runs ─────────────────────────────────────────────

  it('fires a due one-shot at the next check, records the outcome, consumes the schedule', async () => {
    const schedule = makeSchedule(20_000)
    useScheduleStore.getState().addSchedule(schedule)
    runtime.start()

    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS) // t=15s: not yet due
    expect(mockedPublishBatch).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS) // t=30s: due → fires
    expect(mockedPublishBatch).toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1500) // run duration 1s → completes
    expect(history()).toHaveLength(1)
    const record = history()[0]
    expect(record.scheduleId).toBe(schedule.id)
    expect(record.outcome.result).toBe('completed')
    expect(record.outcome.totalSent).toBe(5)
    expect(record.summary!.finalStatus).toBe('completed')

    const stored = useScheduleStore.getState().schedules[0]
    expect(stored.nextRunAt).toBeNull()
    expect(stored.enabled).toBe(false)
  })

  it('an interval schedule advances to its next future slot and stays enabled', async () => {
    const spec: ScheduleSpec = {
      kind: 'interval',
      firstRunAt: new Date(BASE_TIME.getTime() + 20_000).toISOString(),
      everyMinutes: 10,
    }
    useScheduleStore.getState().addSchedule(makeSchedule(20_000, { schedule: spec }))
    runtime.start()

    await vi.advanceTimersByTimeAsync(2 * CHECK_INTERVAL_MS) // fires at t=30s
    await vi.advanceTimersByTimeAsync(1500) // completes

    const stored = useScheduleStore.getState().schedules[0]
    expect(stored.enabled).toBe(true)
    expect(new Date(stored.nextRunAt!).getTime()).toBeGreaterThan(Date.now())
    expect(stored.nextRunAt).toBe(new Date(BASE_TIME.getTime() + 20_000 + 600_000).toISOString())
  })

  it('records fire-time missing value lists in the outcome detail', async () => {
    useScheduleStore.getState().addSchedule(
      makeSchedule(20_000, {
        config: makeConfig({ template: makeTemplate('{"n":"{{list:absent_list}}"}') }),
      })
    )
    runtime.start()
    await vi.advanceTimersByTimeAsync(2 * CHECK_INTERVAL_MS)
    await vi.advanceTimersByTimeAsync(1500)

    expect(history()[0].outcome.result).toBe('completed')
    expect(history()[0].outcome.detail).toContain('absent_list')
  })

  // ── D4: collision handling ────────────────────────────────────────────────

  it('a schedule due while a manual run is active records skipped and advances', async () => {
    // A long manual run occupies the engine.
    startGeneratorRun(makeConfig({ durationSecs: 300 }))
    expect(useGeneratorStore.getState().runState.status).toBe('running')

    useScheduleStore.getState().addSchedule(makeSchedule(10_000, { name: 'Collides' }))
    runtime.start()
    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS) // t=15s: due, but engine busy

    const skipped = history().find((r) => r.scheduleName === 'Collides')!
    expect(skipped.outcome.result).toBe('skipped')
    expect(skipped.outcome.detail).toMatch(/another run/i)
    expect(useScheduleStore.getState().schedules[0].enabled).toBe(false) // one-shot consumed
  })

  it('with two schedules due in one check, the earliest fires and the second is skipped', async () => {
    useScheduleStore.getState().addSchedule(makeSchedule(10_000, { name: 'First due' }))
    useScheduleStore.getState().addSchedule(makeSchedule(12_000, { name: 'Second due' }))
    runtime.start()

    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS) // both due at t=15s
    await vi.advanceTimersByTimeAsync(1500) // first run completes

    const results = Object.fromEntries(history().map((r) => [r.scheduleName, r.outcome.result]))
    expect(results['First due']).toBe('completed')
    expect(results['Second due']).toBe('skipped')
  })

  // ── §7.5: the lease ───────────────────────────────────────────────────────

  it('a tab that does not hold the lease never fires and never records', async () => {
    const holder = createSchedulerRuntime('tab-holder')
    const bystander = createSchedulerRuntime('tab-bystander')
    holder.start() // takes the lease
    bystander.start()

    useScheduleStore.getState().addSchedule(makeSchedule(10_000))
    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS)
    await vi.advanceTimersByTimeAsync(1500)

    // Exactly one firing, one history record — not two.
    expect(history()).toHaveLength(1)
    holder.stop()
    bystander.stop()
  })

  it('takes over an expired lease and fires', async () => {
    localStorage.setItem(
      'peegeeq_scheduler_lease',
      JSON.stringify({ tabId: 'dead-tab', heartbeat: BASE_TIME.getTime() - 120_000 })
    )
    useScheduleStore.getState().addSchedule(makeSchedule(10_000))
    runtime.start()

    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS)
    await vi.advanceTimersByTimeAsync(1500)

    expect(history()[0].outcome.result).toBe('completed')
    const lease = JSON.parse(localStorage.getItem('peegeeq_scheduler_lease')!)
    expect(lease.tabId).toBe('tab-under-test')
  })

  // ── §10: a corrupt schedule cannot break the check ────────────────────────

  it('storage validation removes a corrupt schedule at start, before the scheduler sees it', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const corrupt = makeSchedule(10_000, { name: 'Corrupt on disk' })
    ;(corrupt as { schedule: unknown }).schedule = { kind: 'weird' }
    useScheduleStore.getState().addSchedule(corrupt) // persisted raw; load validates

    runtime.start() // loadFromStorage drops the invalid entry with a named report

    expect(useScheduleStore.getState().schedules).toHaveLength(0)
    expect(consoleError).toHaveBeenCalled()
  })

  it('an in-memory corrupt schedule records an error outcome, is disabled, and later schedules still fire', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    runtime.start() // load happens first; corruption is injected AFTER it

    const corrupt = makeSchedule(10_000, { name: 'Corrupt' })
    ;(corrupt as { schedule: unknown }).schedule = { kind: 'weird' } // computeNextRunAt throws on this
    useScheduleStore.getState().addSchedule(corrupt)
    useScheduleStore.getState().addSchedule(makeSchedule(12_000, { name: 'Healthy' }))

    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS)
    await vi.advanceTimersByTimeAsync(1500)
    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS) // next check fires the healthy one if it was skipped
    await vi.advanceTimersByTimeAsync(1500)

    const results = Object.fromEntries(history().map((r) => [r.scheduleName, r.outcome.result]))
    expect(results['Corrupt']).toBe('error')
    expect(results['Healthy']).toMatch(/completed|skipped/)
    expect(useScheduleStore.getState().schedules.find((s) => s.name === 'Corrupt')!.enabled).toBe(false)
    expect(consoleError).toHaveBeenCalled()
  })

  // ── D3 under lease contention (the reload case) ───────────────────────────

  it('an overdue-at-start schedule is marked missed at FIRST lease acquisition — never fired', async () => {
    // A previous page's lease is still alive (reload within the TTL). The
    // missed sweep must wait for the lease and still apply, with app start as
    // the cutoff — the overdue schedule must never fire.
    localStorage.setItem(
      'peegeeq_scheduler_lease',
      JSON.stringify({ tabId: 'previous-page', heartbeat: BASE_TIME.getTime() - 5_000 })
    )
    useScheduleStore.getState().addSchedule(makeSchedule(-60_000, { name: 'Overdue at start' }))
    runtime.start()

    // No lease yet: nothing recorded, nothing fired.
    expect(history()).toHaveLength(0)

    // The foreign lease expires 25 s in; the check at 30 s acquires and sweeps.
    await vi.advanceTimersByTimeAsync(3 * CHECK_INTERVAL_MS)
    expect(mockedPublishBatch).not.toHaveBeenCalled()
    expect(history()).toHaveLength(1)
    expect(history()[0].outcome.result).toBe('missed')
  })

  it('a schedule becoming due AFTER start fires normally once the lease is acquired', async () => {
    localStorage.setItem(
      'peegeeq_scheduler_lease',
      JSON.stringify({ tabId: 'previous-page', heartbeat: BASE_TIME.getTime() - 5_000 })
    )
    useScheduleStore.getState().addSchedule(makeSchedule(20_000, { name: 'Due after start' }))
    runtime.start()

    await vi.advanceTimersByTimeAsync(3 * CHECK_INTERVAL_MS) // lease acquired at ~30 s; due since 20 s
    await vi.advanceTimersByTimeAsync(1500)

    expect(history()[0].outcome.result).toBe('completed')
  })

  it('releases the lease on pagehide so a reload recovers immediately', () => {
    runtime.start()
    expect(localStorage.getItem('peegeeq_scheduler_lease')).not.toBeNull()
    window.dispatchEvent(new Event('pagehide'))
    expect(localStorage.getItem('peegeeq_scheduler_lease')).toBeNull()
  })

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  it('stop() halts the checks', async () => {
    useScheduleStore.getState().addSchedule(makeSchedule(20_000))
    runtime.start()
    runtime.stop()
    await vi.advanceTimersByTimeAsync(10 * CHECK_INTERVAL_MS)
    expect(mockedPublishBatch).not.toHaveBeenCalled()
    expect(history()).toHaveLength(0)
  })

  it('start() twice does not double the checks', async () => {
    useScheduleStore.getState().addSchedule(makeSchedule(10_000))
    runtime.start()
    runtime.start()
    await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS)
    await vi.advanceTimersByTimeAsync(1500)
    expect(history()).toHaveLength(1)
  })
})
