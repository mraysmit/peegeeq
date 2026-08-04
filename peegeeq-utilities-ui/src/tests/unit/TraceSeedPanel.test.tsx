/**
 * Tests for TraceSeedPanel (Zone E — trace-seed mode, design §19.6 — G.6).
 *
 * Contract under test (written FIRST, before the component):
 * - no run yet (or a run that attempted nothing) → an explicit empty state
 * - after a run, entries are DERIVED from (settings, runId, attempted, rate,
 *   maxBatchSize) via buildTraceReport — the same derivation the engine's
 *   per-message ids came from — so the report states exactly what was emitted
 * - the totals line follows the §19.6 mock: "N messages under M correlation
 *   ids / K causation chains"
 * - children name their chain root; roots carry no parent
 * - the display caps at 100 entries and SAYS SO; the download carries all
 * - a run with errors carries a delivery caveat
 * - Copy ids and Download are offered; the downstream pointer (CausationTree /
 *   Events) is always present
 *
 * No mocks: props in, DOM out. The real clipboard/download events are covered
 * by the trace e2e.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import TraceSeedPanel from '../../pages/generator/TraceSeedPanel'
import type { TraceRun } from '../../pages/generator/TraceSeedPanel'
import { buildTraceReport } from '../../engine/tracePlan'
import type { TraceSettings } from '../../types/trace'

const SETTINGS: TraceSettings = {
  correlation: { kind: 'every-n', n: 2 },
  causation: { enabled: true, childrenPerParent: 1 },
}

function makeRun(overrides: Partial<TraceRun> = {}): TraceRun {
  return {
    settings: SETTINGS,
    runId: 'run-fixed-1',
    attempted: 8,
    errors: 0,
    rate: 50,
    maxBatchSize: 10,
    ...overrides,
  }
}

function renderPanel(run: TraceRun | null) {
  render(
    <ConfigProvider>
      <TraceSeedPanel run={run} />
    </ConfigProvider>
  )
}

describe('TraceSeedPanel', () => {
  it('shows an explicit empty state before any run', () => {
    renderPanel(null)

    expect(screen.getByTestId('trace-empty')).toBeTruthy()
    expect(screen.queryByTestId('trace-panel')).toBeNull()
  })

  it('a run that attempted nothing renders the empty state, not zero rows', () => {
    renderPanel(makeRun({ attempted: 0 }))

    expect(screen.getByTestId('trace-empty')).toBeTruthy()
  })

  it('derives one entry per minted id and states the mock-shaped totals', () => {
    // 8 messages, id every 2 → 4 ids; chains of 1+1 → 2 chains.
    renderPanel(makeRun({ attempted: 8 }))

    expect(screen.getAllByTestId(/^trace-row-/)).toHaveLength(4)
    const totals = screen.getByTestId('trace-totals').textContent!
    expect(totals).toContain('8 messages')
    expect(totals).toContain('4 correlation ids')
    expect(totals).toContain('2 causation chains')
  })

  it('omits the chain figure when causation was not seeded — no claimed structure', () => {
    renderPanel(
      makeRun({
        settings: { correlation: { kind: 'every-n', n: 2 }, causation: { enabled: false, childrenPerParent: 1 } },
        attempted: 8,
      })
    )

    const totals = screen.getByTestId('trace-totals').textContent!
    expect(totals).toContain('4 correlation ids')
    expect(totals).not.toMatch(/causation chains/i)
  })

  it('rows carry the derived ids, roles and chain roots', () => {
    renderPanel(makeRun({ attempted: 8 }))

    const report = buildTraceReport(SETTINGS, 'run-fixed-1', 8, 50, 10)
    const rootRow = screen.getByTestId('trace-row-1').textContent!
    const childRow = screen.getByTestId('trace-row-2').textContent!
    expect(rootRow).toContain(report.entries[0].correlationId)
    expect(rootRow).toContain('root')
    expect(childRow).toContain(report.entries[1].correlationId)
    expect(childRow).toContain('child')
    expect(childRow).toContain(report.entries[0].correlationId) // its chain root
  })

  it('states the run id', () => {
    renderPanel(makeRun())

    expect(screen.getByTestId('trace-header').textContent).toContain('run-fixed-1')
  })

  it('caps the display at 100 entries and says so', () => {
    // 300 messages, id every 1 → 300 entries.
    renderPanel(
      makeRun({
        settings: { correlation: { kind: 'every-n', n: 1 }, causation: { enabled: false, childrenPerParent: 1 } },
        attempted: 300,
      })
    )

    expect(screen.getAllByTestId(/^trace-row-/)).toHaveLength(100)
    const note = screen.getByTestId('trace-truncation-note').textContent!
    expect(note).toContain('100')
    expect(note).toContain('300')
  })

  it('shows no truncation note when every entry is displayed', () => {
    renderPanel(makeRun({ attempted: 8 }))

    expect(screen.queryByTestId('trace-truncation-note')).toBeNull()
  })

  it('carries a delivery caveat when the run had errors', () => {
    renderPanel(makeRun({ errors: 2 }))

    expect(screen.getByTestId('trace-errors-caveat').textContent).toContain('2')
  })

  it('shows no delivery caveat for a clean run', () => {
    renderPanel(makeRun({ errors: 0 }))

    expect(screen.queryByTestId('trace-errors-caveat')).toBeNull()
  })

  it('offers Copy ids and Download, and points at CausationTree / Events', () => {
    renderPanel(makeRun())

    expect((screen.getByTestId('trace-copy') as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByTestId('trace-download') as HTMLButtonElement).disabled).toBe(false)
    expect(screen.getByTestId('trace-verify-note').textContent).toMatch(/CausationTree/i)
  })
})
