/**
 * Tests for ProfilePhasesEditor (Zone B — Profile mode, design §19.3 — G.3b).
 *
 * Contract under test (written FIRST, before the component):
 * - one editable row per phase: label, rate, duration, and a remove action
 * - fully controlled: every edit reports the WHOLE updated phase list, and only
 *   the edited phase differs (no in-place mutation of the caller's array)
 * - "Add phase" appends a phase with a fresh id — ids must never collide, they
 *   key the rows and the per-phase results
 * - derived totals: requested messages = Σ(rate × duration), and total duration.
 *   Both are computed here and never stored (the G.4/G.3a rule).
 * - a rate-0 phase is shown as IDLE and contributes 0 messages while its
 *   duration still counts toward the profile length
 * - an empty phase list is surfaced as a non-blocking advisory rather than
 *   silently leaving an unrunnable profile
 * - `disabled` disables every control (a profile is not editable mid-run)
 *
 * No mocks: the editor is purely presentational. The harness holds the phase
 * list in useState and feeds onChange back into value — exactly how the
 * generator page will drive it.
 */
import { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import ProfilePhasesEditor from '../../pages/generator/ProfilePhasesEditor'
import { makeDefaultPhase } from '../../pages/generator/generatorDefaults'
import type { ProfilePhase } from '../../types/profile'

function phase(overrides: Partial<ProfilePhase> = {}): ProfilePhase {
  return { id: crypto.randomUUID(), label: 'steady', rate: 100, durationSecs: 60, ...overrides }
}

function Harness({
  initial,
  onChangeSpy,
  disabled,
}: {
  initial: ProfilePhase[]
  onChangeSpy: (value: ProfilePhase[]) => void
  disabled?: boolean
}) {
  const [value, setValue] = useState(initial)
  return (
    <ConfigProvider>
      <ProfilePhasesEditor
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

function renderEditor(
  initial: ProfilePhase[] = [phase()],
  onChangeSpy = vi.fn<(value: ProfilePhase[]) => void>(),
  disabled = false
) {
  const utils = render(<Harness initial={initial} onChangeSpy={onChangeSpy} disabled={disabled} />)
  return { ...utils, onChangeSpy }
}

describe('ProfilePhasesEditor', () => {
  it('renders one row per phase with its label, rate and duration', () => {
    const phases = [
      phase({ label: 'burst', rate: 500, durationSecs: 10 }),
      phase({ label: 'steady', rate: 100, durationSecs: 60 }),
    ]
    renderEditor(phases)

    expect(screen.getAllByTestId(/^phase-row-/)).toHaveLength(2)
    const first = screen.getByTestId(`phase-row-${phases[0].id}`)
    expect((within(first).getByLabelText(/Label/i) as HTMLInputElement).value).toBe('burst')
    expect((within(first).getByLabelText(/Rate/i) as HTMLInputElement).value).toBe('500')
    expect((within(first).getByLabelText(/Duration/i) as HTMLInputElement).value).toBe('10')
  })

  it('reports the whole updated list on edit, changing only the edited phase', async () => {
    const phases = [phase({ label: 'burst', rate: 500 }), phase({ label: 'steady', rate: 100 })]
    const { onChangeSpy } = renderEditor(phases)

    const second = screen.getByTestId(`phase-row-${phases[1].id}`)
    const rate = within(second).getByLabelText(/Rate/i)
    await userEvent.clear(rate)
    await userEvent.type(rate, '250')

    await waitFor(() => {
      const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
      expect(last[1].rate).toBe(250)
    })
    const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
    expect(last[0].rate).toBe(500) // untouched
    expect(last).toHaveLength(2)
    // The caller's array is never mutated in place.
    expect(phases[1].rate).toBe(100)
  })

  it('Add phase appends a phase with a FRESH id', async () => {
    const phases = [phase()]
    const { onChangeSpy } = renderEditor(phases)

    await userEvent.click(screen.getByRole('button', { name: /Add phase/i }))

    const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
    expect(last).toHaveLength(2)
    expect(last[1].id).not.toBe(phases[0].id)
    expect(last[1].id.length).toBeGreaterThan(0)
  })

  it('Add phase twice produces two DISTINCT ids', async () => {
    const { onChangeSpy } = renderEditor([phase()])

    await userEvent.click(screen.getByRole('button', { name: /Add phase/i }))
    await userEvent.click(screen.getByRole('button', { name: /Add phase/i }))

    const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
    expect(last).toHaveLength(3)
    expect(new Set(last.map((p: ProfilePhase) => p.id)).size).toBe(3)
  })

  it('removes the chosen phase', async () => {
    const phases = [phase({ label: 'burst' }), phase({ label: 'steady' })]
    const { onChangeSpy } = renderEditor(phases)

    await userEvent.click(screen.getByTestId(`phase-remove-${phases[0].id}`))

    const last = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1][0]
    expect(last).toHaveLength(1)
    expect(last[0].id).toBe(phases[1].id)
  })

  it('derives the requested total and the profile duration from the phases', () => {
    renderEditor([
      phase({ rate: 500, durationSecs: 10 }), // 5000
      phase({ rate: 100, durationSecs: 60 }), // 6000
    ])

    expect(screen.getByTestId('profile-total-messages').textContent).toContain('11000')
    expect(screen.getByTestId('profile-total-duration').textContent).toContain('70')
  })

  it('a rate-0 phase counts as IDLE: no messages, but its duration still counts', () => {
    renderEditor([
      phase({ label: 'spike', rate: 800, durationSecs: 5 }), // 4000
      phase({ label: 'idle', rate: 0, durationSecs: 15 }), // 0 messages, 15 s
    ])

    expect(screen.getByTestId('profile-total-messages').textContent).toContain('4000')
    expect(screen.getByTestId('profile-total-duration').textContent).toContain('20')
    expect(screen.getByTestId('phase-idle-badge-1')).toBeTruthy()
  })

  it('surfaces an advisory when the profile has no phases', async () => {
    const phases = [phase()]
    renderEditor(phases)

    await userEvent.click(screen.getByTestId(`phase-remove-${phases[0].id}`))

    await waitFor(() => {
      expect(screen.getByTestId('profile-empty-advisory')).toBeTruthy()
    })
    expect(screen.queryByTestId(/^phase-row-/)).toBeNull()
  })

  it('disabled disables every input and both actions', () => {
    const phases = [phase()]
    renderEditor(phases, vi.fn(), true)

    const row = screen.getByTestId(`phase-row-${phases[0].id}`)
    expect((within(row).getByLabelText(/Label/i) as HTMLInputElement).disabled).toBe(true)
    expect((within(row).getByLabelText(/Rate/i) as HTMLInputElement).disabled).toBe(true)
    expect((within(row).getByLabelText(/Duration/i) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByTestId(`phase-remove-${phases[0].id}`) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /Add phase/i }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('makeDefaultPhase produces a runnable phase with a unique id', () => {
    const a = makeDefaultPhase()
    const b = makeDefaultPhase()

    expect(a.id).not.toBe(b.id)
    expect(a.rate).toBeGreaterThan(0)
    expect(a.durationSecs).toBeGreaterThan(0)
    expect(a.label.length).toBeGreaterThan(0)
  })
})
