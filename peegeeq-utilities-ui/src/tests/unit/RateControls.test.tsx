/**
 * Tests for RateControls (Zone B — feature §6.1).
 *
 * Contract under test (written FIRST, before the component):
 * - five labelled numeric controls with the §6.1 defaults (10 / 60 / 10 / 500 / 10)
 * - live derived "Total messages = rate × duration"
 * - non-blocking, dismissible rate warning when rate > warnThreshold (> 0);
 *   threshold 0 disables the warning entirely; the warning returns when the
 *   threshold is crossed again after a dismiss
 * - onChange reports the full updated RateSettings
 * - constraint bounds per §6.1 (duration 1–3600, batch 1–100, rate ≥ 1)
 * - disabled prop disables every control
 *
 * No mocks: RateControls is purely presentational (no services, no HTTP). The
 * harness holds RateSettings in useState and feeds onChange back into value —
 * exactly how the MessageGeneratorPage (B.5) will drive it.
 */
import { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import RateControls, { RATE_DEFAULTS } from '../../pages/generator/RateControls'
import type { RateSettings } from '../../types/generator'

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: RateSettings
  onChangeSpy: (value: RateSettings) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <RateControls
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
  overrides: Partial<RateSettings> = {},
  onChangeSpy = vi.fn<(value: RateSettings) => void>(),
  disabled = false
) {
  const utils = render(
    <Harness initial={{ ...RATE_DEFAULTS, ...overrides }} onChangeSpy={onChangeSpy} disabled={disabled} />
  )
  return { ...utils, onChangeSpy }
}

function inputValue(label: RegExp): string {
  return (screen.getByLabelText(label) as HTMLInputElement).value
}

describe('RateControls', () => {
  // ── Defaults ──────────────────────────────────────────────────────────────

  it('exports the §6.1 defaults', () => {
    expect(RATE_DEFAULTS).toEqual({
      rate: 10,
      durationSecs: 60,
      maxBatchSize: 10,
      warnThreshold: 500,
      maxConsecErrors: 10,
    })
  })

  it('renders the five labelled controls with the supplied values', () => {
    renderControls()
    expect(inputValue(/Rate \(msg\/s\)/i)).toBe('10')
    expect(inputValue(/Duration \(seconds\)/i)).toBe('60')
    expect(inputValue(/Max batch size/i)).toBe('10')
    expect(inputValue(/Warn above \(msg\/s\)/i)).toBe('500')
    expect(inputValue(/Auto-stop after N consecutive errors/i)).toBe('10')
  })

  // ── Derived total ─────────────────────────────────────────────────────────

  it('shows Total messages = rate × duration', () => {
    renderControls()
    expect(screen.getByTestId('total-messages').textContent).toContain('600')
  })

  it('updates the total live when the rate changes', async () => {
    renderControls()
    const rate = screen.getByLabelText(/Rate \(msg\/s\)/i)
    await userEvent.clear(rate)
    await userEvent.type(rate, '20')
    await waitFor(() => {
      expect(screen.getByTestId('total-messages').textContent).toContain('1200')
    })
  })

  // ── onChange contract ─────────────────────────────────────────────────────

  it('reports the full updated settings through onChange', async () => {
    const { onChangeSpy } = renderControls()
    const duration = screen.getByLabelText(/Duration \(seconds\)/i)
    await userEvent.clear(duration)
    await userEvent.type(duration, '120')
    await waitFor(() => {
      expect(onChangeSpy).toHaveBeenLastCalledWith({
        rate: 10,
        durationSecs: 120,
        maxBatchSize: 10,
        warnThreshold: 500,
        maxConsecErrors: 10,
      })
    })
  })

  it('does not call onChange while a field is cleared (transient null is ignored)', async () => {
    const { onChangeSpy } = renderControls()
    const rate = screen.getByLabelText(/Rate \(msg\/s\)/i)
    await userEvent.clear(rate)
    expect(onChangeSpy).not.toHaveBeenCalled()
    // The authoritative value is untouched — the total still reflects rate 10.
    expect(screen.getByTestId('total-messages').textContent).toContain('600')
  })

  it('clamps an out-of-range value to the bound on blur', async () => {
    const { onChangeSpy } = renderControls()
    const duration = screen.getByLabelText(/Duration \(seconds\)/i)
    await userEvent.clear(duration)
    await userEvent.type(duration, '9999')
    await userEvent.tab()
    await waitFor(() => {
      expect(onChangeSpy).toHaveBeenLastCalledWith({
        rate: 10,
        durationSecs: 3600,
        maxBatchSize: 10,
        warnThreshold: 500,
        maxConsecErrors: 10,
      })
    })
  })

  // ── Rate warning (non-blocking advisory) ──────────────────────────────────

  it('shows no warning when rate is at or below the threshold', () => {
    renderControls({ rate: 500, warnThreshold: 500 })
    expect(screen.queryByTestId('rate-warning')).toBeNull()
  })

  it('shows the advisory warning when rate exceeds the threshold', () => {
    renderControls({ rate: 501, warnThreshold: 500 })
    const warning = screen.getByTestId('rate-warning')
    expect(warning.textContent).toMatch(/500 msg\/s/)
    expect(warning.textContent).toMatch(/advisory only/i)
  })

  it('never warns when the threshold is 0 (disabled)', () => {
    renderControls({ rate: 100000, warnThreshold: 0 })
    expect(screen.queryByTestId('rate-warning')).toBeNull()
  })

  it('warns when the threshold is lowered below the current rate', async () => {
    renderControls({ rate: 300, warnThreshold: 500 })
    expect(screen.queryByTestId('rate-warning')).toBeNull()
    const threshold = screen.getByLabelText(/Warn above \(msg\/s\)/i)
    await userEvent.clear(threshold)
    await userEvent.type(threshold, '200')
    await waitFor(() => {
      expect(screen.getByTestId('rate-warning')).toBeTruthy()
    })
    expect(screen.getByTestId('rate-warning').textContent).toMatch(/200 msg\/s/)
  })

  it('warning is dismissible', async () => {
    renderControls({ rate: 501, warnThreshold: 500 })
    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    await waitFor(() => {
      expect(screen.queryByTestId('rate-warning')).toBeNull()
    })
  })

  it('warning returns when the threshold is crossed again after dismissal', async () => {
    renderControls({ rate: 501, warnThreshold: 500 })
    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    await waitFor(() => expect(screen.queryByTestId('rate-warning')).toBeNull())

    // Drop back under the threshold, then cross it again — the advisory must reappear.
    const rate = screen.getByLabelText(/Rate \(msg\/s\)/i)
    await userEvent.clear(rate)
    await userEvent.type(rate, '400')
    await waitFor(() => expect(screen.queryByTestId('rate-warning')).toBeNull())
    await userEvent.clear(rate)
    await userEvent.type(rate, '600')
    await waitFor(() => {
      expect(screen.getByTestId('rate-warning')).toBeTruthy()
    })
  })

  // ── Constraint bounds (§6.1) ──────────────────────────────────────────────

  it('applies the §6.1 numeric bounds', () => {
    renderControls()
    expect(screen.getByLabelText(/Rate \(msg\/s\)/i).getAttribute('aria-valuemin')).toBe('1')
    const duration = screen.getByLabelText(/Duration \(seconds\)/i)
    expect(duration.getAttribute('aria-valuemin')).toBe('1')
    expect(duration.getAttribute('aria-valuemax')).toBe('3600')
    const batch = screen.getByLabelText(/Max batch size/i)
    expect(batch.getAttribute('aria-valuemin')).toBe('1')
    expect(batch.getAttribute('aria-valuemax')).toBe('100')
    expect(screen.getByLabelText(/Warn above \(msg\/s\)/i).getAttribute('aria-valuemin')).toBe('0')
    expect(
      screen.getByLabelText(/Auto-stop after N consecutive errors/i).getAttribute('aria-valuemin')
    ).toBe('0')
  })

  // ── Disabled state ────────────────────────────────────────────────────────

  it('disables every control when disabled is set', () => {
    renderControls({}, vi.fn(), true)
    for (const label of [
      /Rate \(msg\/s\)/i,
      /Duration \(seconds\)/i,
      /Max batch size/i,
      /Warn above \(msg\/s\)/i,
      /Auto-stop after N consecutive errors/i,
    ]) {
      expect((screen.getByLabelText(label) as HTMLInputElement).disabled).toBe(true)
    }
  })
})
