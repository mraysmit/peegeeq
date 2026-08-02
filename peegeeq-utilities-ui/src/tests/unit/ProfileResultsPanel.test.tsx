/**
 * Tests for ProfileResultsPanel (Zone E — Profile mode, design §19.3 — G.3c).
 *
 * Contract under test (written FIRST, before the component):
 * - one row per phase, showing REQUESTED as rate × duration derived at render
 * - a settled phase shows its acknowledged sent, error count and status
 * - a phase that has not run yet is explicitly pending, not "0 sent" — those
 *   are different facts and conflating them is the reporting defect this panel
 *   exists to avoid
 * - the live phase is marked running
 * - a shortfall (sent < requested) is called out; that is the whole point of an
 *   achieved-vs-requested panel
 * - totals row sums requested and sent across phases
 * - an idle phase requests 0 and is not a shortfall at 0 sent
 *
 * No mocks: the panel is purely presentational.
 */
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import ProfileResultsPanel from '../../pages/generator/ProfileResultsPanel'
import type { ProfilePhase, ProfilePhaseResult } from '../../types/profile'

function phase(overrides: Partial<ProfilePhase> = {}): ProfilePhase {
  return { id: crypto.randomUUID(), label: 'steady', rate: 100, durationSecs: 10, ...overrides }
}

function result(p: ProfilePhase, overrides: Partial<ProfilePhaseResult> = {}): ProfilePhaseResult {
  return {
    phaseId: p.id,
    label: p.label,
    sent: p.rate * p.durationSecs,
    errors: 0,
    status: 'completed',
    durationMs: p.durationSecs * 1000,
    ...overrides,
  }
}

function renderPanel(
  phases: ProfilePhase[],
  results: ProfilePhaseResult[] = [],
  activeIndex: number | null = null
) {
  return render(
    <ConfigProvider>
      <ProfileResultsPanel phases={phases} results={results} activeIndex={activeIndex} />
    </ConfigProvider>
  )
}

describe('ProfileResultsPanel', () => {
  it('shows one row per phase with REQUESTED derived from rate × duration', () => {
    const phases = [phase({ label: 'burst', rate: 500, durationSecs: 10 }), phase({ rate: 100, durationSecs: 60 })]
    renderPanel(phases)

    expect(screen.getAllByTestId(/^profile-result-row-/)).toHaveLength(2)
    expect(within(screen.getByTestId(`profile-result-row-${phases[0].id}`)).getByTestId('requested').textContent).toContain('5000')
    expect(within(screen.getByTestId(`profile-result-row-${phases[1].id}`)).getByTestId('requested').textContent).toContain('6000')
  })

  it('shows acknowledged sent, errors and status for a settled phase', () => {
    const p = phase({ rate: 100, durationSecs: 10 })
    renderPanel([p], [result(p, { sent: 970, errors: 3, status: 'completed' })])

    const row = screen.getByTestId(`profile-result-row-${p.id}`)
    expect(within(row).getByTestId('sent').textContent).toContain('970')
    expect(within(row).getByTestId('errors').textContent).toContain('3')
    expect(within(row).getByTestId('status').textContent).toMatch(/completed/i)
  })

  it('marks a phase that has NOT run as pending, not as zero sent', () => {
    const p = phase()
    renderPanel([p], [])

    const row = screen.getByTestId(`profile-result-row-${p.id}`)
    expect(within(row).getByTestId('status').textContent).toMatch(/pending/i)
    // "not run" must not read as "ran and sent nothing".
    expect(within(row).getByTestId('sent').textContent).not.toMatch(/\b0\b/)
  })

  it('marks the live phase as running', () => {
    const phases = [phase({ label: 'burst' }), phase({ label: 'steady' })]
    renderPanel(phases, [result(phases[0])], 1)

    const row = screen.getByTestId(`profile-result-row-${phases[1].id}`)
    expect(within(row).getByTestId('status').textContent).toMatch(/running/i)
  })

  it('calls out a shortfall when a phase sent fewer than it requested', () => {
    const p = phase({ rate: 100, durationSecs: 10 }) // requested 1000
    renderPanel([p], [result(p, { sent: 640 })])

    expect(screen.getByTestId(`profile-shortfall-${p.id}`)).toBeTruthy()
  })

  it('does not call a fully delivered phase a shortfall', () => {
    const p = phase({ rate: 100, durationSecs: 10 })
    renderPanel([p], [result(p, { sent: 1000 })])

    expect(screen.queryByTestId(`profile-shortfall-${p.id}`)).toBeNull()
  })

  it('an IDLE phase requests nothing and is not a shortfall at zero sent', () => {
    const p = phase({ label: 'idle', rate: 0, durationSecs: 15 })
    renderPanel([p], [result(p, { sent: 0 })])

    const row = screen.getByTestId(`profile-result-row-${p.id}`)
    expect(within(row).getByTestId('requested').textContent).toContain('0')
    expect(screen.queryByTestId(`profile-shortfall-${p.id}`)).toBeNull()
  })

  it('totals requested and sent across all phases', () => {
    const phases = [phase({ rate: 500, durationSecs: 10 }), phase({ rate: 100, durationSecs: 60 })]
    renderPanel(phases, [result(phases[0], { sent: 4800 }), result(phases[1], { sent: 6000 })])

    expect(screen.getByTestId('profile-total-requested').textContent).toContain('11000')
    expect(screen.getByTestId('profile-total-sent').textContent).toContain('10800')
  })
})
