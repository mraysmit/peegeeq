/**
 * Tests for RampControls (Zone B — Ramp mode, design §19.1 — G.1a).
 *
 * Contract under test (written FIRST, before the component):
 * - the six controls, fully controlled: every edit reports the WHOLE settings
 * - the plan preview is DERIVED from the settings (step count and the rate the
 *   ramp climbs to) — never stored, so it cannot disagree with the controls
 * - a start rate above the cap yields NO steps, and that is surfaced as an
 *   advisory rather than left as a silently unrunnable ramp
 * - the error-rate threshold is disabled under the plateau rule, so a number
 *   that has no effect cannot look like it does
 * - `disabled` disables every control
 *
 * No mocks: the component is purely presentational.
 */
import { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import RampControls from '../../pages/generator/RampControls'
import { RAMP_DEFAULTS } from '../../pages/generator/generatorDefaults'
import type { RampSettings } from '../../types/ramp'

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: RampSettings
  onChangeSpy: (value: RampSettings) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <RampControls
        value={value}
        onChange={(next) => {
          setValue(next)
          onChangeSpy(next)
        }}
        disabled={disabled}
      />
    </ConfigProvider>
  )
}

function renderControls(
  overrides: Partial<RampSettings> = {},
  onChangeSpy = vi.fn<(value: RampSettings) => void>(),
  disabled = false
) {
  render(
    <Harness initial={{ ...RAMP_DEFAULTS, ...overrides }} onChangeSpy={onChangeSpy} disabled={disabled} />
  )
  return { onChangeSpy }
}

describe('RampControls', () => {
  it('exports defaults that describe a runnable ramp', () => {
    expect(RAMP_DEFAULTS.startRate).toBeGreaterThan(0)
    expect(RAMP_DEFAULTS.stepRate).toBeGreaterThan(0)
    expect(RAMP_DEFAULTS.stepSecs).toBeGreaterThan(0)
    expect(['error-rate', 'plateau']).toContain(RAMP_DEFAULTS.stopOn)
  })

  it('renders the ramp controls with the supplied values', () => {
    renderControls({ startRate: 10, stepRate: 50, stepSecs: 10, maxRate: 210 })

    expect((screen.getByLabelText(/Start rate/i) as HTMLInputElement).value).toBe('10')
    expect((screen.getByLabelText(/Step size/i) as HTMLInputElement).value).toBe('50')
    expect((screen.getByLabelText(/Step every/i) as HTMLInputElement).value).toBe('10')
    expect((screen.getByLabelText(/Max rate/i) as HTMLInputElement).value).toBe('210')
  })

  it('reports the whole updated settings on edit', async () => {
    const { onChangeSpy } = renderControls({ startRate: 10 })

    const start = screen.getByLabelText(/Start rate/i)
    await userEvent.clear(start)
    await userEvent.type(start, '25')

    await waitFor(() => {
      const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
      expect(last.startRate).toBe(25)
    })
    const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
    expect(last.stepRate).toBe(RAMP_DEFAULTS.stepRate) // untouched
  })

  it('derives the plan preview from the settings', () => {
    // 10, 60, 110, 160, 210 — five steps climbing to 210.
    renderControls({ startRate: 10, stepRate: 50, stepSecs: 10, maxRate: 210 })

    const preview = screen.getByTestId('ramp-plan-preview').textContent!
    expect(preview).toContain('5')
    expect(preview).toContain('210')
  })

  it('the preview follows an edit rather than going stale', async () => {
    const { onChangeSpy } = renderControls({ startRate: 10, stepRate: 50, maxRate: 110 })
    expect(screen.getByTestId('ramp-plan-preview').textContent).toContain('3')

    const max = screen.getByLabelText(/Max rate/i)
    await userEvent.clear(max)
    await userEvent.type(max, '210')

    await waitFor(() => {
      expect(onChangeSpy).toHaveBeenCalled()
      expect(screen.getByTestId('ramp-plan-preview').textContent).toContain('5')
    })
  })

  it('surfaces an advisory when the start rate is above the cap — no steps to run', () => {
    renderControls({ startRate: 500, maxRate: 100 })

    expect(screen.getByTestId('ramp-empty-advisory')).toBeTruthy()
  })

  it('disables the error-rate threshold under the plateau rule', () => {
    renderControls({ stopOn: 'plateau' })

    expect((screen.getByLabelText(/Error rate threshold/i) as HTMLInputElement).disabled).toBe(true)
  })

  it('enables the error-rate threshold under the error-rate rule', () => {
    renderControls({ stopOn: 'error-rate' })

    expect((screen.getByLabelText(/Error rate threshold/i) as HTMLInputElement).disabled).toBe(false)
  })

  it('switching the stop rule reports it', async () => {
    const { onChangeSpy } = renderControls({ stopOn: 'error-rate' })

    await userEvent.click(screen.getByLabelText(/Acked-rate plateau/i))

    await waitFor(() => {
      const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
      expect(last.stopOn).toBe('plateau')
    })
  })

  it('disabled disables every control', () => {
    renderControls({}, vi.fn(), true)

    expect((screen.getByLabelText(/Start rate/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Step size/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Step every/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Max rate/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Error rate threshold/i) as HTMLInputElement).disabled).toBe(true)
  })
})
