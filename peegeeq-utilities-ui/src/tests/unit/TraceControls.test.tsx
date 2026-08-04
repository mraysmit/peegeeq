/**
 * Tests for TraceControls (Zone B — trace-seed mode, design §19.6 — G.6).
 *
 * Contract under test (written FIRST, before the component):
 * - the correlation strategy radio and the causation controls render, fully
 *   controlled: every edit reports the WHOLE settings object
 * - the every-n count input shows only under the every-n strategy; the
 *   children-per-parent input only while chains are enabled
 * - the scheme summary is DERIVED from the settings and follows edits
 * - the run-id caveat is always visible: every minted id derives from the run
 *   id, which does not exist until Start — the emitted-ids panel is exact
 * - `disabled` disables every control
 *
 * No mocks: props in, DOM out.
 */
import { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import TraceControls from '../../pages/generator/TraceControls'
import { TRACE_DEFAULTS } from '../../pages/generator/generatorDefaults'
import type { TraceSettings } from '../../types/trace'

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: TraceSettings
  onChangeSpy: (value: TraceSettings) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <TraceControls
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
  overrides: Partial<TraceSettings> = {},
  onChangeSpy = vi.fn<(value: TraceSettings) => void>(),
  disabled = false
) {
  render(
    <Harness
      initial={{ ...TRACE_DEFAULTS, ...overrides }}
      onChangeSpy={onChangeSpy}
      disabled={disabled}
    />
  )
  return { onChangeSpy }
}

describe('TraceControls', () => {
  it('exports defaults matching the §19.6 mock arithmetic', () => {
    expect(TRACE_DEFAULTS.correlation).toEqual({ kind: 'every-n', n: 100 })
    expect(TRACE_DEFAULTS.causation).toEqual({ enabled: true, childrenPerParent: 3 })
  })

  it('renders the strategy controls with the supplied values', () => {
    renderControls({
      correlation: { kind: 'every-n', n: 50 },
      causation: { enabled: true, childrenPerParent: 2 },
    })

    expect(screen.getByTestId('trace-controls')).toBeTruthy()
    expect((screen.getByLabelText(/Every N messages/i) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText(/New id every \(messages\)/i) as HTMLInputElement).value).toBe('50')
    expect((screen.getByLabelText(/Seed causation chains/i) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText(/Children per parent/i) as HTMLInputElement).value).toBe('2')
  })

  it('shows the every-n count only under the every-n strategy', async () => {
    renderControls({ correlation: { kind: 'every-n', n: 100 } })
    expect(screen.getByLabelText(/New id every \(messages\)/i)).toBeTruthy()

    await userEvent.click(screen.getByLabelText(/One per run/i))

    await waitFor(() => {
      expect(screen.queryByLabelText(/New id every \(messages\)/i)).toBeNull()
    })
  })

  it('shows children-per-parent only while chains are enabled', async () => {
    renderControls({ causation: { enabled: true, childrenPerParent: 3 } })
    expect(screen.getByLabelText(/Children per parent/i)).toBeTruthy()

    await userEvent.click(screen.getByLabelText(/Seed causation chains/i))

    await waitFor(() => {
      expect(screen.queryByLabelText(/Children per parent/i)).toBeNull()
    })
  })

  it('reports the whole updated settings on edit', async () => {
    const { onChangeSpy } = renderControls({
      correlation: { kind: 'every-n', n: 100 },
      causation: { enabled: true, childrenPerParent: 3 },
    })

    const count = screen.getByLabelText(/New id every \(messages\)/i)
    await userEvent.clear(count)
    await userEvent.type(count, '25')

    await waitFor(() => {
      const last = onChangeSpy.mock.calls.at(-1)![0]
      expect(last.correlation).toEqual({ kind: 'every-n', n: 25 })
    })
    const last = onChangeSpy.mock.calls.at(-1)![0]
    expect(last.causation).toEqual({ enabled: true, childrenPerParent: 3 }) // untouched
  })

  it('switching the correlation strategy reports it', async () => {
    const { onChangeSpy } = renderControls({ correlation: { kind: 'every-n', n: 100 } })

    await userEvent.click(screen.getByLabelText(/One per batch/i))

    await waitFor(() => {
      const last = onChangeSpy.mock.calls.at(-1)![0]
      expect(last.correlation).toEqual({ kind: 'per-batch' })
    })
  })

  it('derives the scheme summary from the settings and follows edits', async () => {
    renderControls({
      correlation: { kind: 'every-n', n: 100 },
      causation: { enabled: true, childrenPerParent: 3 },
    })

    const summary = () => screen.getByTestId('trace-scheme-summary').textContent!
    expect(summary()).toContain('100')
    expect(summary()).toContain('3')

    const count = screen.getByLabelText(/New id every \(messages\)/i)
    await userEvent.clear(count)
    await userEvent.type(count, '40')

    await waitFor(() => {
      expect(summary()).toContain('40')
    })
  })

  it('always carries the run-id caveat — ids exist only once the run starts', () => {
    renderControls()

    expect(screen.getByTestId('trace-preview-caveat')).toBeTruthy()
  })

  it('disabled disables every control', () => {
    renderControls(
      {
        correlation: { kind: 'every-n', n: 100 },
        causation: { enabled: true, childrenPerParent: 3 },
      },
      vi.fn(),
      true
    )

    expect((screen.getByLabelText(/One per run/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/New id every \(messages\)/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Seed causation chains/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Children per parent/i) as HTMLInputElement).disabled).toBe(true)
  })
})
