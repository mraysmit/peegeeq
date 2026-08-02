/**
 * Tests for MessageGeneratorPage (B.5 assembly — feature §6.1, §7, §13).
 *
 * Contract under test (written FIRST, before the page):
 * - all five zones render (A target, B rate, C template, D actions, E progress)
 * - the page owns Zone B/C/D state: rate edits update the live total; template
 *   edits reflect in the editor; the preview index flows to Zone D
 * - Start is disabled without a target (Zone A cannot resolve one in jsdom —
 *   no backend — so the page renders Zone A's error state and no target exists)
 * - a running store disables Zone B and Zone C inputs
 *
 * The run flow itself (Start → engine → counters → summary) requires a real
 * backend and is covered by the Phase B generator e2e, not unit tests. No
 * mocks here: Zone A performs its real fetch and surfaces its real error state.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import MessageGeneratorPage from '../../pages/generator/MessageGeneratorPage'
import { useGeneratorStore } from '../../stores/generatorStore'
import { useTemplateStore } from '../../stores/templateStore'
import { useValueListStore } from '../../stores/valueListStore'
import { useScenarioStore } from '../../stores/scenarioStore'
import type { MessageTemplate, RunConfig } from '../../types/generator'
import type { Scenario } from '../../types/scenario'

function makeConfig(): RunConfig {
  const now = new Date().toISOString()
  const template: MessageTemplate = {
    id: 't1', name: 'T', messageType: 'x', payloadSchema: '{}', headers: {},
    priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
  }
  return {
    setupId: 's1', queueName: 'orders', rate: 10, durationSecs: 60,
    maxBatchSize: 10, warnThreshold: 500, maxConsecErrors: 10,
    template, previewIndex: 1,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <ConfigProvider>
        <MessageGeneratorPage />
      </ConfigProvider>
    </MemoryRouter>
  )
}

describe('MessageGeneratorPage', () => {
  beforeEach(() => {
    localStorage.clear()
    // Point Zone A's REAL fetch at a closed port so "no backend" is a fact, not
    // an assumption about the machine. configService defaults to the absolute
    // URL http://127.0.0.1:8088, so these tests reached whatever backend
    // happened to be listening — and `npm run test:e2e` leaves one running, so
    // running e2e before the unit suite made "renders all five zones" and
    // "Start is disabled without a target" fail (Zone A loaded the leftover
    // e2e setup instead of erroring). Set through the app's own config seam:
    // no mocks, no request interception, deterministic on any machine.
    // Must follow localStorage.clear() above — the clear would wipe it.
    localStorage.setItem(
      'peegeeq_utilities_backend_config',
      JSON.stringify({ apiUrl: 'http://127.0.0.1:1' })
    )
    useGeneratorStore.getState().resetRun()
    useGeneratorStore.setState({ config: null })
    useTemplateStore.setState({ templates: [], selected: null })
    useScenarioStore.setState({ scenarios: [], selected: null })
  })

  it('renders all five zones', async () => {
    renderPage()
    expect(screen.getByTestId('zone-a')).toBeTruthy()
    expect(screen.getByTestId('rate-controls')).toBeTruthy()
    expect(screen.getByTestId('template-editor')).toBeTruthy()
    expect(screen.getByTestId('generator-actions')).toBeTruthy()
    expect(screen.getByTestId('progress-panel')).toBeTruthy()
    // Zone A has no backend in jsdom — its real error state surfaces.
    await waitFor(() => {
      expect(screen.getByText(/Failed to load setups/i)).toBeTruthy()
    })
  })

  it('rate edits update the live total through page-owned state', async () => {
    renderPage()
    const rate = screen.getByLabelText(/Rate \(msg\/s\)/i)
    await userEvent.clear(rate)
    await userEvent.type(rate, '20')
    await waitFor(() => {
      // 20 × default 60 s
      expect(screen.getByTestId('total-messages').textContent).toContain('1200')
    })
  })

  it('template edits reflect in the editor through page-owned state', async () => {
    renderPage()
    const name = screen.getByLabelText(/^Name$/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'My template')
    await waitFor(() => {
      expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).value).toBe('My template')
    })
  })

  it('Start is disabled without a target', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/Failed to load setups/i)).toBeTruthy()
    })
    expect((screen.getByRole('button', { name: /^Start$/i }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('opens a template selected in the templateStore as the working copy (Template Manager handoff)', async () => {
    const now = new Date().toISOString()
    const stored: MessageTemplate = {
      id: 'tpl-sel', name: 'From manager', messageType: 'mgr.type', payloadSchema: '{}',
      headers: {}, priority: 5, delaySeconds: 0, createdAt: now, updatedAt: now,
    }
    useTemplateStore.getState().add(stored)
    useTemplateStore.getState().select('tpl-sel')

    renderPage()

    expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).value).toBe('From manager')
    // The selection is consumed: revisiting /generator later starts blank.
    await waitFor(() => {
      expect(useTemplateStore.getState().selected).toBeNull()
    })
  })

  it('loads value lists from localStorage on mount — a fresh page load must not resolve {{list:...}} to ""', async () => {
    // Regression: the page relied on the valueListStore already being populated.
    // After a full page load the store starts empty, so previews AND runs
    // resolved every {{list:...}} token to "" with a false missing-list warning.
    localStorage.setItem('peegeeq_value_lists', JSON.stringify({ persisted_names: ['Val'] }))
    useValueListStore.setState({ lists: [], selected: null })

    renderPage()

    await waitFor(() => {
      expect(useValueListStore.getState().snapshot()['persisted_names']).toEqual(['Val'])
    })
  })

  it('renders the scenario bar', () => {
    renderPage()
    expect(screen.getByTestId('scenario-bar')).toBeTruthy()
  })

  it('applies a scenario selected in the scenarioStore as the working configuration (Tools handoff)', async () => {
    const now = new Date().toISOString()
    const scenario: Scenario = {
      id: 'scn-1',
      name: 'nightly-soak',
      config: {
        ...makeConfig(),
        rate: 250,
        durationSecs: 30,
        previewIndex: 7,
        template: { ...makeConfig().template, name: 'From scenario' },
      },
      createdAt: now,
      updatedAt: now,
    }
    useScenarioStore.getState().add(scenario)
    useScenarioStore.getState().select('scn-1')

    renderPage()

    await waitFor(() => {
      expect((screen.getByLabelText(/Rate \(msg\/s\)/i) as HTMLInputElement).value).toBe('250')
    })
    expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).value).toBe('From scenario')
    // 250 × 30 s — the rate settings arrived as a set, not one field.
    expect(screen.getByTestId('total-messages').textContent).toContain('7500')
    // The selection is consumed: a later plain visit to /generator starts clean.
    await waitFor(() => {
      expect(useScenarioStore.getState().selected).toBeNull()
    })
  })

  it('a loaded scenario does not mutate the stored scenario when the working template is edited', async () => {
    const now = new Date().toISOString()
    const scenario: Scenario = {
      id: 'scn-1',
      name: 'nightly-soak',
      config: { ...makeConfig(), template: { ...makeConfig().template, name: 'Stored name' } },
      createdAt: now,
      updatedAt: now,
    }
    useScenarioStore.getState().add(scenario)
    useScenarioStore.getState().select('scn-1')
    renderPage()

    const name = await screen.findByLabelText(/^Name$/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'Edited copy')

    await waitFor(() => {
      expect((screen.getByLabelText(/^Name$/i) as HTMLInputElement).value).toBe('Edited copy')
    })
    expect(useScenarioStore.getState().scenarios[0].config.template.name).toBe('Stored name')
  })

  it('a running store disables Zone B and Zone C inputs', async () => {
    renderPage()
    act(() => {
      useGeneratorStore.getState().setConfig(makeConfig())
      useGeneratorStore.getState().startRun()
    })
    await waitFor(() => {
      expect((screen.getByLabelText(/Rate \(msg\/s\)/i) as HTMLInputElement).disabled).toBe(true)
    })
    expect((screen.getByLabelText(/Payload/i) as HTMLTextAreaElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /^Save$/i }) as HTMLButtonElement).disabled).toBe(true)
  })
})
