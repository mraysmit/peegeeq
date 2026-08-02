/**
 * Tests for ToolsPage — route /tools (design §19.0, §19.4 — Phase G.4).
 *
 * Contract under test:
 * - the scenario table lists name, DERIVED target and run description, and the
 *   relative updated time
 * - Load (and the Name link) selects the scenario and navigates to /generator
 * - Delete removes after confirmation and persists
 * - Export downloads the row's scenario
 * - Import (.json): valid new scenarios appended; duplicate IDs rejected with a
 *   NAMED warning (no silent overwrite); invalid entries and bad JSON surface
 *   named errors
 * - the empty state points at the generator, which is where scenarios are made
 *
 * No mocks: real scenarioStore + localStorage + real scenarioService import
 * path (FileReader) + real routing. URL.createObjectURL is stubbed for Export.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ToolsPage from '../../pages/tools/ToolsPage'
import { useScenarioStore } from '../../stores/scenarioStore'
import { loadAll } from '../../services/scenarioService'
import type { RunConfig, MessageTemplate } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

function makeConfig(overrides: Partial<RunConfig> = {}): RunConfig {
  const now = new Date().toISOString()
  const template: MessageTemplate = {
    id: 'tpl-1',
    name: 'Order created',
    messageType: 'order.created',
    payloadSchema: '{"id":"{{messageId}}"}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
  return {
    setupId: 'demo',
    queueName: 'orders',
    rate: 100,
    durationSecs: 60,
    maxBatchSize: 10,
    warnThreshold: 500,
    maxConsecErrors: 10,
    template,
    previewIndex: 1,
    ...overrides,
  }
}

function makeScenario(overrides: Partial<Scenario> = {}): Scenario {
  const now = new Date().toISOString()
  return {
    id: 'scn-1',
    name: 'nightly-soak',
    config: makeConfig(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/tools']}>
      <ConfigProvider>
        <Routes>
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/generator" element={<div data-testid="generator-route" />} />
        </Routes>
      </ConfigProvider>
    </MemoryRouter>
  )
}

function importFile(content: string, name = 'import.json'): File {
  return new File([content], name, { type: 'application/json' })
}

describe('ToolsPage', () => {
  beforeEach(() => {
    localStorage.clear()
    useScenarioStore.setState({ scenarios: [], selected: null })
    document.querySelectorAll('.ant-modal-root, .ant-popover').forEach((n) => n.remove())
  })

  it('shows an empty state pointing at the generator when nothing is saved', () => {
    renderPage()
    expect(screen.getByTestId('scenarios-empty')).toBeTruthy()
    expect(screen.queryByTestId('scenario-table')).toBeNull()
  })

  it('lists a scenario with its DERIVED target and run description', () => {
    useScenarioStore.getState().add(makeScenario({ config: makeConfig({ rate: 100, durationSecs: 60 }) }))
    renderPage()

    expect(screen.getByText('nightly-soak')).toBeTruthy()
    expect(screen.getByTestId('scenario-target-scn-1').textContent).toBe('demo / orders')
    // rate × duration is computed at render, never stored.
    expect(screen.getByTestId('scenario-run-scn-1').textContent).toContain('6000')
    expect(screen.getByTestId('scenario-updated-scn-1').textContent).toMatch(/ago|few seconds/i)
  })

  it('Load selects the scenario and navigates to the generator', async () => {
    useScenarioStore.getState().add(makeScenario())
    renderPage()

    await userEvent.click(screen.getByTestId('scenario-load-scn-1'))

    await waitFor(() => {
      expect(screen.getByTestId('generator-route')).toBeTruthy()
    })
    expect(useScenarioStore.getState().selected?.id).toBe('scn-1')
  })

  it('the Name link selects the scenario and navigates to the generator', async () => {
    useScenarioStore.getState().add(makeScenario())
    renderPage()

    await userEvent.click(screen.getByRole('link', { name: 'nightly-soak' }))

    await waitFor(() => {
      expect(screen.getByTestId('generator-route')).toBeTruthy()
    })
    expect(useScenarioStore.getState().selected?.id).toBe('scn-1')
  })

  it('Delete removes the scenario after confirmation and persists', async () => {
    useScenarioStore.getState().add(makeScenario())
    renderPage()

    await userEvent.click(screen.getByTestId('scenario-delete-scn-1'))
    const deleteButtons = await screen.findAllByRole('button', { name: /^Delete$/i })
    const confirmButton = deleteButtons.find((b) => !b.hasAttribute('data-testid'))!
    await userEvent.click(confirmButton)

    await waitFor(() => {
      expect(useScenarioStore.getState().scenarios).toHaveLength(0)
    })
    expect(loadAll()).toHaveLength(0)
  })

  it('Export downloads the row scenario as JSON', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    useScenarioStore.getState().add(makeScenario())
    renderPage()

    await userEvent.click(screen.getByTestId('scenario-export-scn-1'))

    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  it('Import appends valid new scenarios', async () => {
    renderPage()
    const incoming = [makeScenario({ id: 'scn-new', name: 'Imported' })]

    await userEvent.click(screen.getByRole('button', { name: /Import/ }))
    await userEvent.upload(
      screen.getByTestId('scenario-import-input'),
      importFile(JSON.stringify(incoming))
    )

    await waitFor(() => {
      expect(useScenarioStore.getState().scenarios.map((s) => s.id)).toContain('scn-new')
    })
    expect(screen.getByText('Imported')).toBeTruthy()
    expect(loadAll().map((s) => s.id)).toContain('scn-new')
  })

  it('Import rejects duplicate IDs with a named warning and no overwrite', async () => {
    useScenarioStore.getState().add(makeScenario({ name: 'Original' }))
    renderPage()
    const incoming = [makeScenario({ name: 'Clobber attempt' })]

    await userEvent.click(screen.getByRole('button', { name: /Import/ }))
    await userEvent.upload(
      screen.getByTestId('scenario-import-input'),
      importFile(JSON.stringify(incoming))
    )

    await waitFor(() => {
      expect(screen.getByText(/scn-1/)).toBeTruthy()
    })
    expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    expect(useScenarioStore.getState().scenarios[0].name).toBe('Original')
  })

  it('Import surfaces named errors for schema-invalid entries and bad JSON', async () => {
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: /Import/ }))
    await userEvent.upload(
      screen.getByTestId('scenario-import-input'),
      importFile(JSON.stringify([{ id: 'x', name: 'Broken entry' }]))
    )
    await waitFor(() => {
      expect(screen.getByText(/Broken entry/)).toBeTruthy()
    })

    await userEvent.click(screen.getByRole('button', { name: /Import/ }))
    await userEvent.upload(screen.getByTestId('scenario-import-input'), importFile('not json at all'))
    await waitFor(() => {
      expect(screen.getByText(/not valid JSON/i)).toBeTruthy()
    })

    expect(useScenarioStore.getState().scenarios).toHaveLength(0)
  })
})
