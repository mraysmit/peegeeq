/**
 * Tests for ScheduleRunModal (Scheduled Runs design §8.1 — SCH.4).
 *
 * Contract under test (written FIRST, before the component):
 * - shows the capture summary (target, rate × duration = total, template name),
 *   the client-side-execution note, and a prefilled schedule name
 * - validation: empty name, absent datetime, past datetime, and an interval
 *   below one minute are all rejected with a visible error and no schedule
 * - a valid once-schedule is stored with the FROZEN config (a deep copy, not a
 *   reference to the caller's object) and the correct nextRunAt
 * - a valid interval schedule stores the interval spec
 * - a missing value list shows a non-blocking warning; saving still works
 * - Cancel stores nothing
 *
 * No mocks: the real scheduleStore and valueListStore back everything.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import ScheduleRunModal from '../../pages/generator/ScheduleRunModal'
import { useScheduleStore } from '../../stores/scheduleStore'
import { useValueListStore } from '../../stores/valueListStore'
import { loadAllSchedules } from '../../services/scheduleService'
import type { MessageTemplate, RunConfig } from '../../types/generator'

function makeTemplate(overrides: Partial<MessageTemplate> = {}): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 'tpl-1', name: 'Order template', messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now, ...overrides,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 'setup-a', queueName: 'orders', rate: 10, durationSecs: 60,
    maxBatchSize: 10, warnThreshold: 500, maxConsecErrors: 10,
    template: makeTemplate(), previewIndex: 1, ...overrides,
  }
}

/** A datetime-local input value one hour ahead, in local time. */
function futureLocalDatetime(offsetMinutes = 60): string {
  const d = new Date(Date.now() + offsetMinutes * 60_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function renderModal(config: RunConfig = makeConfig(), onClose = vi.fn()) {
  render(
    <MemoryRouter>
      <ConfigProvider>
        <ScheduleRunModal open={true} config={config} onClose={onClose} />
      </ConfigProvider>
    </MemoryRouter>
  )
  return { onClose }
}

async function save() {
  await userEvent.click(screen.getByRole('button', { name: /Create schedule/i }))
}

describe('ScheduleRunModal', () => {
  beforeEach(() => {
    localStorage.clear()
    useScheduleStore.setState({ schedules: [], history: [], templates: [] })
    useValueListStore.setState({ lists: [], selected: null })
    document.querySelectorAll('.ant-modal-root').forEach((n) => n.remove())
  })

  // ── Presentation ──────────────────────────────────────────────────────────

  it('shows the capture summary: target, rate × duration = total, template name', () => {
    renderModal()
    const summary = screen.getByTestId('schedule-capture-summary')
    expect(summary.textContent).toContain('setup-a / orders')
    expect(summary.textContent).toContain('10 msg/s × 60 s = 600')
    expect(summary.textContent).toContain('Order template')
  })

  it('states the client-side execution constraint', () => {
    renderModal()
    expect(screen.getByTestId('schedule-execution-note').textContent).toMatch(
      /only while this app is open/i
    )
  })

  it('prefills the name as "{template name} @ {queue}"', () => {
    renderModal()
    expect((screen.getByLabelText(/Schedule name/i) as HTMLInputElement).value).toBe(
      'Order template @ orders'
    )
  })

  // ── Validation ────────────────────────────────────────────────────────────

  it('rejects an empty name', async () => {
    renderModal()
    await userEvent.clear(screen.getByLabelText(/Schedule name/i))
    await userEvent.type(screen.getByLabelText(/Run at/i), futureLocalDatetime())
    await save()
    expect(screen.getByTestId('schedule-modal-error').textContent).toMatch(/name/i)
    expect(loadAllSchedules()).toHaveLength(0)
  })

  it('rejects a missing datetime', async () => {
    renderModal()
    await save()
    expect(screen.getByTestId('schedule-modal-error').textContent).toMatch(/time/i)
    expect(loadAllSchedules()).toHaveLength(0)
  })

  it('rejects a past datetime', async () => {
    renderModal()
    await userEvent.type(screen.getByLabelText(/Run at/i), futureLocalDatetime(-120))
    await save()
    expect(screen.getByTestId('schedule-modal-error').textContent).toMatch(/past/i)
    expect(loadAllSchedules()).toHaveLength(0)
  })

  it('an interval below one minute is unreachable: the input clamps to the minimum', async () => {
    // The input's min bound is the UI-layer enforcement (storage-level Zod
    // guards non-UI paths — covered in scheduleImportExport tests). Typing 0
    // clamps to 1 on blur; the saved schedule carries the clamped value.
    renderModal()
    await userEvent.click(screen.getByLabelText(/Repeat every/i))
    await userEvent.type(screen.getByLabelText(/Starting at/i), futureLocalDatetime())
    const every = screen.getByLabelText(/Every \(minutes\)/i)
    expect(every.getAttribute('aria-valuemin')).toBe('1')
    await userEvent.clear(every)
    await userEvent.type(every, '0')
    await userEvent.tab()
    await save()
    await waitFor(() => {
      expect(loadAllSchedules()).toHaveLength(1)
    })
    expect(loadAllSchedules()[0].schedule).toMatchObject({ kind: 'interval', everyMinutes: 1 })
  })

  // ── Saving ────────────────────────────────────────────────────────────────

  it('stores a valid once-schedule with a frozen deep-copied config and closes', async () => {
    const config = makeConfig()
    const { onClose } = renderModal(config)
    await userEvent.type(screen.getByLabelText(/Run at/i), futureLocalDatetime())
    await save()

    await waitFor(() => {
      expect(loadAllSchedules()).toHaveLength(1)
    })
    const stored = loadAllSchedules()[0]
    expect(stored.name).toBe('Order template @ orders')
    expect(stored.enabled).toBe(true)
    expect(stored.schedule.kind).toBe('once')
    expect(stored.nextRunAt).not.toBeNull()
    expect(new Date(stored.nextRunAt!).getTime()).toBeGreaterThan(Date.now())
    // Frozen snapshot: equal content, not the caller's references.
    expect(stored.config).toEqual(config)
    const inStore = useScheduleStore.getState().schedules[0]
    expect(inStore.config).not.toBe(config)
    expect(inStore.config.template).not.toBe(config.template)
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('stores a valid interval schedule', async () => {
    renderModal()
    await userEvent.click(screen.getByLabelText(/Repeat every/i))
    await userEvent.type(screen.getByLabelText(/Starting at/i), futureLocalDatetime())
    await save()

    await waitFor(() => {
      expect(loadAllSchedules()).toHaveLength(1)
    })
    const stored = loadAllSchedules()[0]
    expect(stored.schedule).toMatchObject({ kind: 'interval', everyMinutes: 60 })
  })

  // ── Missing-list warning (non-blocking) ───────────────────────────────────

  it('warns about a missing value list but still saves', async () => {
    renderModal(makeConfig({ template: makeTemplate({ payloadSchema: '{"n":"{{list:absent}}"}' }) }))
    expect(screen.getByTestId('schedule-missing-lists').textContent).toContain('absent')

    await userEvent.type(screen.getByLabelText(/Run at/i), futureLocalDatetime())
    await save()
    await waitFor(() => {
      expect(loadAllSchedules()).toHaveLength(1)
    })
  })

  it('shows no warning when the referenced list exists', () => {
    const now = new Date().toISOString()
    useValueListStore.getState().add({ name: 'present', values: ['x'], createdAt: now, updatedAt: now })
    renderModal(makeConfig({ template: makeTemplate({ payloadSchema: '{"n":"{{list:present}}"}' }) }))
    expect(screen.queryByTestId('schedule-missing-lists')).toBeNull()
  })

  // ── Cancel ────────────────────────────────────────────────────────────────

  it('Cancel stores nothing', async () => {
    const { onClose } = renderModal()
    await userEvent.type(screen.getByLabelText(/Run at/i), futureLocalDatetime())
    await userEvent.click(screen.getByRole('button', { name: /^Cancel$/i }))
    expect(loadAllSchedules()).toHaveLength(0)
    expect(onClose).toHaveBeenCalledOnce()
  })
})
