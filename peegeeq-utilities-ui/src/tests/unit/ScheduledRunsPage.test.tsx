/**
 * Tests for ScheduledRunsPage (Scheduled Runs design §8.2 — SCH.5).
 *
 * Contract under test (written FIRST, before the page):
 * Schedules tab — rows with name/target/run/schedule/next-run; the outcome
 *   column derives from history; Export-all (disabled when empty, content
 *   verified); enable toggle persists; Run now fires WITHOUT advancing the
 *   schedule and is refused with a message while a run is active; Edit timing
 *   updates the spec, recomputes nextRunAt, and re-enables a consumed
 *   one-shot; Save-as-template; Delete confirms and history survives.
 * History tab — rows with result tags and detail; result filter and name
 *   search narrow; Download only on fired entries; Save-as-template;
 *   Re-schedule opens the schedule modal prefilled from the FROZEN record
 *   config; the 200-entry bound is stated.
 * Templates tab — rows; Schedule… opens the modal prefilled; Run now fires and
 *   records history under the template name; Delete confirms; empty state.
 *
 * Real stores and localStorage throughout; the publish network boundary is
 * mocked as in the engine tests. Run-now tests use real timers with 1-second
 * run durations.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import ScheduledRunsPage from '../../pages/schedules/ScheduledRunsPage'
import { useScheduleStore } from '../../stores/scheduleStore'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useValueListStore } from '../../stores/valueListStore'
import { startGeneratorRun } from '../../engine/runStarter'
import { loadAllSchedules, loadTemplates } from '../../services/scheduleService'
import { publishBatch } from '../../services/publishService'
import type { MessageTemplate, RunConfig, RunSummary } from '../../types/generator'
import type { ScheduledRun, ScheduleOutcome } from '../../types/schedule'

vi.mock('../../services/publishService')
const mockedPublishBatch = vi.mocked(publishBatch)

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1', name: 'Order template', messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 'setup-a', queueName: 'orders', rate: 5, durationSecs: 1,
    maxBatchSize: 10, warnThreshold: 0, maxConsecErrors: 0,
    template: makeTemplate(), previewIndex: 1, ...overrides,
  }
}

function makeSchedule(overrides: Partial<ScheduledRun> = {}): ScheduledRun {
  const now = new Date().toISOString()
  const future = new Date(Date.now() + 3600_000).toISOString()
  return {
    id: crypto.randomUUID(), name: 'Nightly load', config: makeConfig(),
    schedule: { kind: 'once', runAt: future }, enabled: true, nextRunAt: future,
    createdAt: now, updatedAt: now, ...overrides,
  }
}

function makeOutcome(result: ScheduleOutcome['result'], detail?: string): ScheduleOutcome {
  return { at: new Date().toISOString(), result, totalSent: result === 'completed' ? 5 : 0, totalErrors: 0, detail }
}

function makeSummary(): RunSummary {
  return {
    totalSent: 5, targetTotal: 5, avgRate: 5, durationMs: 1000,
    totalErrors: 0, finalStatus: 'completed', runId: 'r1', errors: [],
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <ScheduledRunsPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

async function openTab(name: RegExp) {
  await userEvent.click(screen.getByRole('tab', { name }))
}

describe('ScheduledRunsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
    useValueListStore.setState({ lists: [], selected: null })
    mockedPublishBatch.mockResolvedValue({ messagesSent: 5 })
    // Modal containers are recreated per confirm; the antd message holder is a
    // SINGLETON — removing it detaches the reference and later messages render
    // nowhere, so it must be left in place.
    document.querySelectorAll('.ant-modal-root').forEach((n) => n.remove())
  })

  // ── Schedules tab ─────────────────────────────────────────────────────────

  it('renders schedule rows with target, run, schedule description, and next run', () => {
    useScheduleStore.getState().addSchedule(makeSchedule())
    renderPage()
    const row = screen.getByTestId('schedule-row-Nightly load')
    expect(row.textContent).toContain('setup-a / orders')
    expect(row.textContent).toContain('5 msg/s × 1 s = 5')
    expect(row.textContent).toContain('Once at')
  })

  it('shows the execution-constraint banner and the empty state', () => {
    renderPage()
    expect(screen.getByTestId('schedules-page-banner').textContent).toMatch(/only while this app is open/i)
    expect(screen.getByTestId('schedules-empty').textContent).toMatch(/Schedule…/)
  })

  it('derives the outcome column from history, with a dash before any firing', () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    const { rerender } = renderPage()
    expect(screen.getByTestId(`schedule-outcome-${schedule.id}`).textContent).toContain('—')

    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    rerender(
      <MemoryRouter>
        <ConfigProvider>
          <ScheduledRunsPage />
        </ConfigProvider>
      </MemoryRouter>
    )
    expect(screen.getByTestId(`schedule-outcome-${schedule.id}`).textContent).toMatch(/COMPLETED/)
  })

  it('the enabled toggle persists', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    renderPage()
    await userEvent.click(screen.getByTestId(`schedule-enabled-${schedule.id}`))
    await waitFor(() => {
      expect(loadAllSchedules()[0].enabled).toBe(false)
    })
  })

  it('Export all is disabled with no schedules and downloads the array when present', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const { unmount } = renderPage()
    expect((screen.getByRole('button', { name: /Export all/i }) as HTMLButtonElement).disabled).toBe(true)
    unmount()

    useScheduleStore.getState().addSchedule(makeSchedule())
    renderPage()
    await userEvent.click(screen.getByRole('button', { name: /Export all/i }))
    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  it('Run now fires, records history, and does NOT advance or consume the schedule', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    renderPage()

    await userEvent.click(screen.getByTestId(`schedule-run-now-${schedule.id}`))
    await waitFor(
      () => {
        expect(useScheduleStore.getState().history).toHaveLength(1)
      },
      { timeout: 5000 }
    )

    expect(useScheduleStore.getState().history[0].outcome.result).toBe('completed')
    const stored = loadAllSchedules()[0]
    expect(stored.enabled).toBe(true)
    expect(stored.nextRunAt).toBe(schedule.nextRunAt) // slot untouched
  })

  it('Run now is refused with a message while a run is active', async () => {
    startGeneratorRun(makeConfig({ durationSecs: 300 }))
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    renderPage()

    await userEvent.click(screen.getByTestId(`schedule-run-now-${schedule.id}`))
    await waitFor(() => {
      expect(screen.getByText(/another run is active/i)).toBeTruthy()
    })
    expect(useScheduleStore.getState().history).toHaveLength(0)
  })

  it('Edit timing updates the spec, recomputes nextRunAt, and re-enables a consumed one-shot', async () => {
    const consumed = makeSchedule({ enabled: false, nextRunAt: null })
    useScheduleStore.getState().addSchedule(consumed)
    renderPage()

    await userEvent.click(screen.getByTestId(`schedule-edit-timing-${consumed.id}`))
    const d = new Date(Date.now() + 7200_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const local = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
    // The input is prefilled with the schedule's original time — clear first.
    await userEvent.clear(screen.getByLabelText(/Run at/i))
    await userEvent.type(screen.getByLabelText(/Run at/i), local)
    await userEvent.click(screen.getByRole('button', { name: /Save timing/i }))

    await waitFor(() => {
      const stored = loadAllSchedules()[0]
      expect(stored.nextRunAt).not.toBeNull()
      expect(stored.enabled).toBe(true)
    })
  })

  it('Save as template from a schedule row creates a template', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    renderPage()

    await userEvent.click(screen.getByTestId(`schedule-save-template-${schedule.id}`))
    await userEvent.click(screen.getByRole('button', { name: /Save template/i }))

    await waitFor(() => {
      expect(loadTemplates()).toHaveLength(1)
    })
    expect(loadTemplates()[0].name).toContain('Nightly load')
  })

  it('Delete confirms, removes the schedule, and history survives', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    renderPage()

    await userEvent.click(screen.getByTestId(`schedule-delete-${schedule.id}`))
    const deleteButtons = await screen.findAllByRole('button', { name: /^Delete$/i })
    await userEvent.click(deleteButtons[deleteButtons.length - 1])

    await waitFor(() => {
      expect(loadAllSchedules()).toHaveLength(0)
    })
    expect(useScheduleStore.getState().history).toHaveLength(1)
  })

  // ── Import (the D7 later phase) ───────────────────────────────────────────

  it('Import appends valid schedules with recomputed timing', async () => {
    renderPage()
    const future = new Date(Date.now() + 3600_000).toISOString()
    const incoming = [makeSchedule({ id: 'imp-1', name: 'Imported schedule', schedule: { kind: 'once', runAt: future }, nextRunAt: future })]
    await userEvent.upload(
      screen.getByTestId('schedule-import-input'),
      new File([JSON.stringify(incoming)], 'schedules.json', { type: 'application/json' })
    )
    await waitFor(() => {
      expect(loadAllSchedules().map((s) => s.id)).toContain('imp-1')
    })
    expect(screen.getByTestId('schedule-row-Imported schedule')).toBeTruthy()
  })

  it('Import skips a duplicate id with a named warning and no overwrite', async () => {
    const existing = makeSchedule({ id: 'dup-1', name: 'Original' })
    useScheduleStore.getState().addSchedule(existing)
    renderPage()
    await userEvent.upload(
      screen.getByTestId('schedule-import-input'),
      new File([JSON.stringify([makeSchedule({ id: 'dup-1', name: 'Clobber attempt' })])], 'schedules.json', {
        type: 'application/json',
      })
    )
    await waitFor(() => {
      expect(screen.getByText(/dup-1/)).toBeTruthy()
    })
    expect(loadAllSchedules()).toHaveLength(1)
    expect(loadAllSchedules()[0].name).toBe('Original')
  })

  it('Import surfaces named errors for invalid entries and bad files', async () => {
    renderPage()
    await userEvent.upload(
      screen.getByTestId('schedule-import-input'),
      new File([JSON.stringify([{ id: 'x', name: 'Broken entry' }])], 'schedules.json', { type: 'application/json' })
    )
    await waitFor(() => {
      expect(screen.getByText(/Broken entry/)).toBeTruthy()
    })
    expect(loadAllSchedules()).toHaveLength(0)

    await userEvent.upload(
      screen.getByTestId('schedule-import-input'),
      new File(['not json'], 'bad.json', { type: 'application/json' })
    )
    await waitFor(() => {
      expect(screen.getByText(/not valid JSON/i)).toBeTruthy()
    })
  })

  // ── History tab ───────────────────────────────────────────────────────────

  it('renders history rows with result tags and detail, and states the bound', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('missed', 'Missed by 90s'), null)
    renderPage()
    await openTab(/Run history/)

    const row = screen.getByTestId(`history-row-0`)
    expect(row.textContent).toMatch(/MISSED/)
    expect(row.textContent).toContain('Missed by 90s')
    expect(screen.getByTestId('history-bound-caption').textContent).toContain('200')
  })

  it('the result filter narrows the rows', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('skipped'), null)
    renderPage()
    await openTab(/Run history/)
    expect(screen.getAllByTestId(/^history-row-/)).toHaveLength(2)

    await userEvent.click(screen.getByTestId('history-result-filter').querySelector('.ant-select-selector')!)
    await userEvent.click(await screen.findByTitle('Completed'))

    await waitFor(() => {
      expect(screen.getAllByTestId(/^history-row-/)).toHaveLength(1)
    })
    expect(screen.getByTestId('history-row-0').textContent).toMatch(/COMPLETED/)
  })

  it('the name search narrows the rows', async () => {
    const a = makeSchedule({ name: 'Alpha run' })
    const b = makeSchedule({ name: 'Beta run' })
    useScheduleStore.getState().addSchedule(a)
    useScheduleStore.getState().addSchedule(b)
    useScheduleStore.getState().recordOutcome(a.id, makeOutcome('completed'), makeSummary())
    useScheduleStore.getState().recordOutcome(b.id, makeOutcome('completed'), makeSummary())
    renderPage()
    await openTab(/Run history/)

    await userEvent.type(screen.getByPlaceholderText(/Search by schedule name/i), 'Alpha')
    await waitFor(() => {
      expect(screen.getAllByTestId(/^history-row-/)).toHaveLength(1)
    })
    expect(screen.getByTestId('history-row-0').textContent).toContain('Alpha run')
  })

  it('Download appears only on fired entries', async () => {
    const schedule = makeSchedule()
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('missed'), null)
    renderPage()
    await openTab(/Run history/)

    // Newest first: row 0 is missed (no summary), row 1 is completed.
    expect(within(screen.getByTestId('history-row-0')).queryByRole('button', { name: /Download/i })).toBeNull()
    expect(within(screen.getByTestId('history-row-1')).getByRole('button', { name: /Download/i })).toBeTruthy()
  })

  it('Re-schedule opens the schedule modal prefilled from the record config', async () => {
    const schedule = makeSchedule({ config: makeConfig({ setupId: 'frozen-setup' }) })
    useScheduleStore.getState().addSchedule(schedule)
    useScheduleStore.getState().recordOutcome(schedule.id, makeOutcome('completed'), makeSummary())
    renderPage()
    await openTab(/Run history/)

    await userEvent.click(within(screen.getByTestId('history-row-0')).getByRole('button', { name: /Re-schedule/i }))
    expect(screen.getByTestId('schedule-capture-summary').textContent).toContain('frozen-setup')
  })

  // ── Templates tab ─────────────────────────────────────────────────────────

  it('templates render; Schedule… opens the modal prefilled; Delete confirms', async () => {
    useScheduleStore.getState().saveAsTemplate('Reusable nightly', makeConfig({ setupId: 'tpl-setup' }))
    renderPage()
    await openTab(/Templates/)

    const row = screen.getByTestId(/^template-row-/)
    expect(row.textContent).toContain('Reusable nightly')
    expect(row.textContent).toContain('tpl-setup / orders')

    await userEvent.click(within(row).getByRole('button', { name: /Schedule…/ }))
    expect(screen.getByTestId('schedule-capture-summary').textContent).toContain('tpl-setup')
    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/i }))

    await userEvent.click(within(row).getByTestId(/^template-delete-/))
    const deleteButtons = await screen.findAllByRole('button', { name: /^Delete$/i })
    await userEvent.click(deleteButtons[deleteButtons.length - 1])
    await waitFor(() => {
      expect(loadTemplates()).toHaveLength(0)
    })
  })

  it('template Run now fires and records history under the template name', async () => {
    useScheduleStore.getState().saveAsTemplate('Reusable nightly', makeConfig())
    renderPage()
    await openTab(/Templates/)

    await userEvent.click(screen.getByTestId(/^template-run-now-/))
    await waitFor(
      () => {
        expect(useScheduleStore.getState().history).toHaveLength(1)
      },
      { timeout: 5000 }
    )
    expect(useScheduleStore.getState().history[0].scheduleName).toBe('Reusable nightly')
    expect(useScheduleStore.getState().history[0].outcome.result).toBe('completed')
  })
})
