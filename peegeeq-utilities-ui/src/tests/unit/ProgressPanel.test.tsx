/**
 * Tests for ProgressPanel (Zone E — feature §6.1, §7.2, §11).
 *
 * Contract under test (written FIRST, before the component):
 * - idle: IDLE status label, no summary card
 * - running: Sent/Total, progress percent, current rate, error count from the
 *   real generatorStore; Elapsed advances on the panel's own 500 ms interval
 *   derived from startedAt (not from tick-cadence elapsedMs)
 * - error list: at most the 20 most recent errors; hidden at zero errors
 * - ERROR status shows the autoStopReason
 * - terminal + stored summary: the summary card REPLACES the progress bar and
 *   renders from the RunSummary alone; Download exports summary+errors JSON;
 *   New run clears the summary and returns the store to idle
 *
 * No mocks: the component reads the real generatorStore, driven through its
 * public actions. URL.createObjectURL is polyfilled for Download (jsdom).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import ProgressPanel from '../../pages/generator/ProgressPanel'
import { useGeneratorStore } from '../../stores/generatorStore'
import type { MessageTemplate, PublishError, RunConfig, RunSummary } from '../../types/generator'

function makeTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: 't1', name: 'T', messageType: 'x', payloadSchema: '{}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
}

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  return {
    setupId: 's1', queueName: 'orders', rate: 10, durationSecs: 60,
    maxBatchSize: 100, warnThreshold: 0, maxConsecErrors: 0,
    template: makeTemplate(), previewIndex: 1, ...overrides,
  }
}

function makeError(i: number): PublishError {
  return { messageIndex: i, httpStatus: 500, message: `boom ${i}`, timestamp: new Date().toISOString() }
}

function makeSummary(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    totalSent: 550, targetTotal: 600, avgRate: 9.2, durationMs: 60000,
    totalErrors: 5, finalStatus: 'completed', runId: 'run-1', errors: [makeError(1)],
    ...overrides,
  }
}

function renderPanel() {
  return render(
    <ConfigProvider>
      <ProgressPanel />
    </ConfigProvider>
  )
}

function startRun(config: RunConfig = makeConfig()) {
  act(() => {
    useGeneratorStore.getState().setConfig(config)
    useGeneratorStore.getState().startRun()
  })
}

describe('ProgressPanel', () => {
  beforeEach(() => {
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows IDLE and no summary card before a run', () => {
    renderPanel()
    expect(screen.getByTestId('run-status').textContent).toContain('IDLE')
    expect(screen.queryByTestId('summary-card')).toBeNull()
  })

  it('shows Sent/Total, progress percent, rate, and error count while running', () => {
    renderPanel()
    startRun(makeConfig({ rate: 10, durationSecs: 60 })) // total 600
    act(() => {
      useGeneratorStore.getState().tickUpdate(150, [makeError(1)], 1, 15000)
    })

    expect(screen.getByTestId('run-status').textContent).toContain('RUNNING')
    expect(screen.getByTestId('sent-counter').textContent).toContain('150 / 600')
    expect(screen.getByTestId('error-counter').textContent).toContain('1')
    // 150/600 = 25%
    expect(screen.getByTestId('run-progress').textContent).toContain('25%')
  })

  it('advances Elapsed on its own 500 ms interval, independent of ticks', async () => {
    vi.useFakeTimers()
    renderPanel()
    startRun(makeConfig({ durationSecs: 60 }))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })
    // No tickUpdate happened — Elapsed still advanced from startedAt.
    expect(screen.getByTestId('elapsed-counter').textContent).toContain('2s / 60s')
  })

  it('lists at most the 20 most recent errors and hides the list at zero', () => {
    renderPanel()
    startRun()
    expect(screen.queryByTestId('error-list')).toBeNull()

    const errors = Array.from({ length: 25 }, (_, i) => makeError(i + 1))
    act(() => {
      useGeneratorStore.getState().tickUpdate(10, errors, 0, 1000)
    })

    const list = screen.getByTestId('error-list')
    const items = list.querySelectorAll('[data-testid^="error-item-"]')
    expect(items.length).toBe(20)
    // Most recent errors: 6..25 (the last 20).
    expect(list.textContent).toContain('boom 25')
    expect(list.textContent).not.toContain('boom 5')
  })

  it('shows the auto-stop reason in the ERROR state', () => {
    renderPanel()
    startRun()
    act(() => {
      useGeneratorStore.getState().transitionTo('error', 'Auto-stopped: 3 consecutive errors. Last: boom')
    })
    expect(screen.getByTestId('run-status').textContent).toContain('ERROR')
    expect(screen.getByTestId('run-status').textContent).toContain('Auto-stopped: 3 consecutive errors')
  })

  it('replaces the progress bar with the summary card on a terminal state', () => {
    renderPanel()
    startRun()
    act(() => {
      useGeneratorStore.getState().transitionTo('completed')
      useGeneratorStore.getState().setSummary(makeSummary())
    })

    const card = screen.getByTestId('summary-card')
    expect(screen.queryByTestId('run-progress')).toBeNull()
    expect(card.textContent).toContain('550')      // total sent
    expect(card.textContent).toContain('600')      // target total
    expect(card.textContent).toContain('9.2')      // avg rate
    expect(card.textContent).toContain('60')       // duration seconds
    expect(card.textContent).toContain('COMPLETED')
  })

  it('Download exports the summary and its errors as JSON', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })

    renderPanel()
    startRun()
    act(() => {
      useGeneratorStore.getState().transitionTo('completed')
      useGeneratorStore.getState().setSummary(makeSummary())
    })

    await userEvent.click(screen.getByRole('button', { name: /Download results/i }))

    expect(createObjectURL).toHaveBeenCalledOnce()
    const blob = createObjectURL.mock.calls[0][0] as unknown as Blob
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as string)
      reader.readAsText(blob)
    })
    const exported = JSON.parse(text)
    expect(exported.totalSent).toBe(550)
    expect(exported.errors).toHaveLength(1)
  })

  it('New run clears the summary and returns the store to idle', async () => {
    renderPanel()
    startRun()
    act(() => {
      useGeneratorStore.getState().transitionTo('stopped')
      useGeneratorStore.getState().setSummary(makeSummary({ finalStatus: 'stopped' }))
    })

    await userEvent.click(screen.getByRole('button', { name: /New run/i }))

    await waitFor(() => {
      expect(screen.queryByTestId('summary-card')).toBeNull()
    })
    expect(useGeneratorStore.getState().runState.status).toBe('idle')
    expect(useGeneratorStore.getState().summary).toBeNull()
    expect(screen.getByTestId('run-status').textContent).toContain('IDLE')
  })
})
