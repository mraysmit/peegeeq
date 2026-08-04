/**
 * Tests for ExerciserControls (Zone B — exerciser mode, design §19.5 — G.5-send).
 *
 * Contract under test (written FIRST, before the component):
 * - the three strategy groups render, fully controlled: every edit reports the
 *   WHOLE settings object
 * - each strategy kind shows ONLY its own parameter inputs
 * - the plan preview is DERIVED from the settings via the same assignmentFor
 *   the run uses — never stored, so it cannot disagree with the run
 * - a per-key group strategy naming a missing/empty value list surfaces a
 *   warning instead of a preview that would throw
 * - the note that these strategies override the template's scalar fields is
 *   always visible — the Preview modal shows template values, and silence here
 *   would let the two read as the same thing
 * - `disabled` disables every control
 *
 * No mocks: the component reads the REAL valueListStore for the per-key list
 * options, seeded through the store's own API.
 */
import { useState } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import ExerciserControls, { EXERCISER_DEFAULTS } from '../../pages/generator/ExerciserControls'
import { useValueListStore } from '../../stores/valueListStore'
import type { ExerciserSettings } from '../../types/exerciser'

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: ExerciserSettings
  onChangeSpy: (value: ExerciserSettings) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <ExerciserControls
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
  overrides: Partial<ExerciserSettings> = {},
  onChangeSpy = vi.fn<(value: ExerciserSettings) => void>(),
  disabled = false
) {
  render(
    <Harness
      initial={{ ...EXERCISER_DEFAULTS, ...overrides }}
      onChangeSpy={onChangeSpy}
      disabled={disabled}
    />
  )
  return { onChangeSpy }
}

describe('ExerciserControls', () => {
  beforeEach(() => {
    localStorage.clear()
    useValueListStore.setState({ lists: [], selected: null })
  })

  it('exports defaults that describe a runnable exercise', () => {
    expect(['fixed', 'random', 'per-index-ramp']).toContain(EXERCISER_DEFAULTS.delay.kind)
    expect(['fixed', 'round-robin']).toContain(EXERCISER_DEFAULTS.priority.kind)
    // The default group strategy must not depend on a value list existing.
    expect(EXERCISER_DEFAULTS.group.kind).not.toBe('per-key')
  })

  it('renders the three strategy groups with the supplied values', () => {
    renderControls({
      delay: { kind: 'fixed', seconds: 7 },
      priority: { kind: 'fixed', priority: 3 },
      group: { kind: 'round-robin', groups: 4 },
    })

    expect(screen.getByTestId('exerciser-controls')).toBeTruthy()
    expect((screen.getByLabelText(/Fixed delay/i) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText(/^Delay \(seconds\)$/i) as HTMLInputElement).value).toBe('7')
    expect((screen.getByLabelText(/Fixed priority/i) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText(/Priority \(0–10\)/i) as HTMLInputElement).value).toBe('3')
    expect((screen.getByLabelText(/Round-robin groups/i) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText(/Number of groups/i) as HTMLInputElement).value).toBe('4')
  })

  it('each delay kind shows only its own parameters', async () => {
    renderControls({ delay: { kind: 'fixed', seconds: 5 } })
    expect(screen.getByLabelText(/^Delay \(seconds\)$/i)).toBeTruthy()
    expect(screen.queryByLabelText(/Max delay/i)).toBeNull()

    await userEvent.click(screen.getByLabelText(/Random delay/i))

    await waitFor(() => {
      expect(screen.getByLabelText(/Max delay \(seconds\)/i)).toBeTruthy()
    })
    expect(screen.queryByLabelText(/^Delay \(seconds\)$/i)).toBeNull()

    await userEvent.click(screen.getByLabelText(/Per-index ramp/i))

    await waitFor(() => {
      expect(screen.getByLabelText(/Ramp step \(seconds\)/i)).toBeTruthy()
    })
    expect(screen.getByLabelText(/Ramp cap \(seconds\)/i)).toBeTruthy()
  })

  it('reports the whole updated settings on edit', async () => {
    const { onChangeSpy } = renderControls({
      delay: { kind: 'fixed', seconds: 5 },
      priority: { kind: 'fixed', priority: 5 },
    })

    const seconds = screen.getByLabelText(/^Delay \(seconds\)$/i)
    await userEvent.clear(seconds)
    await userEvent.type(seconds, '9')

    await waitFor(() => {
      const last = onChangeSpy.mock.calls.at(-1)![0]
      expect(last.delay).toEqual({ kind: 'fixed', seconds: 9 })
    })
    const last = onChangeSpy.mock.calls.at(-1)![0]
    expect(last.priority).toEqual({ kind: 'fixed', priority: 5 }) // untouched
  })

  it('switching a strategy kind reports it with that kind defaults', async () => {
    const { onChangeSpy } = renderControls({ priority: { kind: 'fixed', priority: 5 } })

    await userEvent.click(screen.getByLabelText(/Round-robin 1–10/i))

    await waitFor(() => {
      const last = onChangeSpy.mock.calls.at(-1)![0]
      expect(last.priority).toEqual({ kind: 'round-robin' })
    })
  })

  it('derives the plan preview from the settings — first rows of the real assignment', () => {
    renderControls({
      delay: { kind: 'fixed', seconds: 3 },
      priority: { kind: 'round-robin' },
      group: { kind: 'round-robin', groups: 2 },
    })

    const preview = screen.getByTestId('exerciser-plan-preview').textContent!
    // Index 0: priority 1, grp-0, delay 3 — the same values assignmentFor gives.
    expect(preview).toContain('grp-0')
    expect(preview).toContain('grp-1')
    expect(preview).toContain('p1')
    expect(preview).toContain('d3s')
  })

  it('the preview follows an edit rather than going stale', async () => {
    renderControls({ delay: { kind: 'fixed', seconds: 3 } })
    expect(screen.getByTestId('exerciser-plan-preview').textContent).toContain('d3s')

    const seconds = screen.getByLabelText(/^Delay \(seconds\)$/i)
    await userEvent.clear(seconds)
    await userEvent.type(seconds, '8')

    await waitFor(() => {
      expect(screen.getByTestId('exerciser-plan-preview').textContent).toContain('d8s')
    })
  })

  it('flags an illustrative preview when values depend on the run id', () => {
    renderControls({ delay: { kind: 'random', maxSeconds: 10 } })

    expect(screen.getByTestId('exerciser-preview-caveat')).toBeTruthy()
  })

  it('shows no illustrative caveat when every strategy is exact', () => {
    renderControls({
      delay: { kind: 'fixed', seconds: 3 },
      priority: { kind: 'round-robin' },
      group: { kind: 'round-robin', groups: 2 },
    })

    expect(screen.queryByTestId('exerciser-preview-caveat')).toBeNull()
  })

  it('per-key with a missing list warns instead of previewing', () => {
    renderControls({ group: { kind: 'per-key', listName: 'customers' } })

    expect(screen.getByTestId('exerciser-per-key-warning')).toBeTruthy()
    expect(screen.queryByTestId('exerciser-plan-preview')).toBeNull()
  })

  it('per-key with a populated list previews its real group values', () => {
    const now = new Date().toISOString()
    useValueListStore.setState({
      lists: [{ name: 'customers', values: ['cust-a', 'cust-b'], createdAt: now, updatedAt: now }],
      selected: null,
    })
    renderControls({ group: { kind: 'per-key', listName: 'customers' } })

    expect(screen.queryByTestId('exerciser-per-key-warning')).toBeNull()
    expect(screen.getByTestId('exerciser-plan-preview').textContent).toMatch(/cust-[ab]/)
  })

  it('always states that the strategies override the template scalars', () => {
    renderControls()

    expect(screen.getByTestId('exerciser-override-note')).toBeTruthy()
  })

  it('disabled disables every control', () => {
    renderControls(
      {
        delay: { kind: 'fixed', seconds: 5 },
        priority: { kind: 'fixed', priority: 5 },
        group: { kind: 'single', group: 'orders' },
      },
      vi.fn(),
      true
    )

    expect((screen.getByLabelText(/Fixed delay/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/^Delay \(seconds\)$/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Fixed priority/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Priority \(0–10\)/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Single group/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByLabelText(/Group name/i) as HTMLInputElement).disabled).toBe(true)
  })
})
