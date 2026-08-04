/**
 * Tests for ManifestPanel (Zone E — exerciser mode, design §19.5 — G.5-send).
 *
 * Contract under test (written FIRST, before the component):
 * - no run yet → an explicit empty state, not an empty table
 * - after a run, rows are DERIVED from (settings, runId, attempted count) via
 *   buildManifest — the same function whose assignments the engine applied —
 *   so the panel reports exactly what was sent without storing anything
 * - the display caps at 100 rows and SAYS SO; the download carries the full
 *   manifest ("no silent caps")
 * - a run with batch errors carries a caveat: the manifest lists ATTEMPTED
 *   ids, and per-id delivery is verified downstream, not here
 * - the downstream-verification pointer (management-ui Message Browser) is
 *   always present — auto-verification needs Phase T telemetry
 *
 * No mocks: props in, DOM out. The download itself (a real browser event) is
 * covered by the exerciser e2e.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import ManifestPanel from '../../pages/generator/ManifestPanel'
import type { ManifestRun } from '../../pages/generator/ManifestPanel'
import { assignmentFor } from '../../engine/exerciserPlan'
import type { ExerciserSettings } from '../../types/exerciser'

const SETTINGS: ExerciserSettings = {
  delay: { kind: 'fixed', seconds: 3 },
  priority: { kind: 'round-robin' },
  group: { kind: 'round-robin', groups: 2 },
}

function makeRun(overrides: Partial<ManifestRun> = {}): ManifestRun {
  return {
    settings: SETTINGS,
    runId: 'run-fixed-1',
    attempted: 5,
    errors: 0,
    valueLists: {},
    ...overrides,
  }
}

function renderPanel(run: ManifestRun | null) {
  render(
    <ConfigProvider>
      <ManifestPanel run={run} />
    </ConfigProvider>
  )
}

describe('ManifestPanel', () => {
  it('shows an explicit empty state before any run', () => {
    renderPanel(null)

    expect(screen.getByTestId('manifest-empty')).toBeTruthy()
    expect(screen.queryByTestId('manifest-panel')).toBeNull()
  })

  it('derives one row per attempted message from the run identity', () => {
    renderPanel(makeRun({ attempted: 5 }))

    expect(screen.getAllByTestId(/^manifest-row-/)).toHaveLength(5)
    // Row 1 carries exactly what assignmentFor gave message index 0.
    const first = assignmentFor(SETTINGS, 'run-fixed-1', 0, {})
    const row = screen.getByTestId('manifest-row-1').textContent!
    expect(row).toContain('00000001')
    expect(row).toContain(first.messageGroup)
    expect(row).toContain(`p${first.priority}`)
    expect(row).toContain(`d${first.delaySeconds}s`)
  })

  it('states the attempted count and the run id', () => {
    renderPanel(makeRun({ attempted: 5 }))

    const header = screen.getByTestId('manifest-header').textContent!
    expect(header).toContain('5')
    expect(header).toContain('run-fixed-1')
  })

  it('caps the display at 100 rows and says so', () => {
    renderPanel(makeRun({ attempted: 250 }))

    expect(screen.getAllByTestId(/^manifest-row-/)).toHaveLength(100)
    const note = screen.getByTestId('manifest-truncation-note').textContent!
    expect(note).toContain('100')
    expect(note).toContain('250')
  })

  it('shows no truncation note when every row is displayed', () => {
    renderPanel(makeRun({ attempted: 5 }))

    expect(screen.queryByTestId('manifest-truncation-note')).toBeNull()
  })

  it('carries a delivery caveat when the run had errors', () => {
    renderPanel(makeRun({ errors: 3 }))

    const caveat = screen.getByTestId('manifest-errors-caveat').textContent!
    expect(caveat).toContain('3')
  })

  it('shows no delivery caveat for a clean run', () => {
    renderPanel(makeRun({ errors: 0 }))

    expect(screen.queryByTestId('manifest-errors-caveat')).toBeNull()
  })

  it('always points at management-ui Message Browser for ordering verification', () => {
    renderPanel(makeRun())

    expect(screen.getByTestId('manifest-verify-note').textContent).toMatch(/Message Browser/i)
  })

  it('offers the manifest download', () => {
    renderPanel(makeRun())

    expect(
      (screen.getByTestId('manifest-download') as HTMLButtonElement).disabled
    ).toBe(false)
  })

  it('a run that attempted nothing renders the empty state, not zero rows', () => {
    // e.g. the run failed to start; runStarter's synthetic summary attempts 0.
    renderPanel(makeRun({ attempted: 0 }))

    expect(screen.getByTestId('manifest-empty')).toBeTruthy()
    expect(screen.queryByTestId(/^manifest-row-/)).toBeNull()
  })
})
