/**
 * Tests for ScenarioBar (design §19.4 — Phase G.4).
 *
 * Contract under test:
 * - the select lists saved scenarios and is empty-stated when there are none
 * - Load hands the CHOSEN scenario to the page (the bar applies nothing itself)
 * - "Save as…" persists the page's live config under the typed name; a blank
 *   name is refused with a visible inline error and saves nothing
 * - Save is unavailable with no config (no target selected)
 * - a running run disables Load and Save
 * - Export downloads the selected scenario
 *
 * No mocks: real scenarioStore + real localStorage. Only URL.createObjectURL is
 * stubbed (jsdom does not implement it).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import ScenarioBar from '../../components/ScenarioBar'
import { useScenarioStore } from '../../stores/scenarioStore'
import { loadAll } from '../../services/scenarioService'
import type { RunConfig, MessageTemplate } from '../../types/generator'
import type { Scenario } from '../../types/scenario'
import type { ProfilePhase } from '../../types/profile'

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

function renderBar(
  props: Partial<{
    config: RunConfig | null
    onLoad: (s: Scenario) => void
    disabled: boolean
    mode: 'flat' | 'profile' | 'ramp'
    phases: ProfilePhase[]
  }> = {}
) {
  const onLoad = props.onLoad ?? vi.fn<(s: Scenario) => void>()
  render(
    <ConfigProvider>
      <ScenarioBar
        config={props.config === undefined ? makeConfig() : props.config}
        onLoad={onLoad}
        disabled={props.disabled ?? false}
        mode={props.mode ?? 'flat'}
        phases={props.phases ?? []}
      />
    </ConfigProvider>
  )
  return { onLoad }
}

describe('ScenarioBar', () => {
  beforeEach(() => {
    localStorage.clear()
    useScenarioStore.setState({ scenarios: [], selected: null })
    document.querySelectorAll('.ant-modal-root').forEach((n) => n.remove())
  })

  it('shows an empty-state placeholder and a disabled select when nothing is saved', () => {
    renderBar()
    expect(screen.getByText(/No saved scenarios/i)).toBeTruthy()
    expect((screen.getByTestId('scenario-load') as HTMLButtonElement).disabled).toBe(true)
  })

  it('lists saved scenarios and loads the chosen one', async () => {
    useScenarioStore.getState().add(makeScenario())
    useScenarioStore.getState().add(makeScenario({ id: 'scn-2', name: 'spike-repro' }))
    const { onLoad } = renderBar()

    await userEvent.click(screen.getByRole('combobox'))
    await userEvent.click(await screen.findByTitle('spike-repro'))
    await userEvent.click(screen.getByTestId('scenario-load'))

    expect(onLoad).toHaveBeenCalledTimes(1)
    expect(onLoad.mock.calls[0][0].id).toBe('scn-2')
  })

  it('Save as… persists the page\'s live config under the typed name', async () => {
    const config = makeConfig({ rate: 250, queueName: 'events' })
    renderBar({ config })

    await userEvent.click(screen.getByTestId('scenario-save-as'))
    await userEvent.type(screen.getByTestId('scenario-name-input'), 'nightly-soak')
    await userEvent.click(screen.getByTestId('scenario-save-confirm'))

    await waitFor(() => {
      expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    })
    const saved = useScenarioStore.getState().scenarios[0]
    expect(saved.name).toBe('nightly-soak')
    expect(saved.config.rate).toBe(250)
    expect(saved.config.queueName).toBe('events')
    // Persisted, not only in memory.
    expect(loadAll().map((s) => s.name)).toContain('nightly-soak')
  })

  it('refuses a blank scenario name with a visible error and saves nothing', async () => {
    renderBar()

    await userEvent.click(screen.getByTestId('scenario-save-as'))
    await userEvent.type(screen.getByTestId('scenario-name-input'), '   ')
    await userEvent.click(screen.getByTestId('scenario-save-confirm'))

    expect(screen.getByTestId('scenario-save-error').textContent).toMatch(/name is required/i)
    expect(useScenarioStore.getState().scenarios).toHaveLength(0)
  })

  it('disables Save as… when there is no config (no target selected)', () => {
    renderBar({ config: null })
    expect((screen.getByTestId('scenario-save-as') as HTMLButtonElement).disabled).toBe(true)
  })

  it('disables Load and Save while a run is active', () => {
    useScenarioStore.getState().add(makeScenario())
    renderBar({ disabled: true })

    expect((screen.getByTestId('scenario-load') as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByTestId('scenario-save-as') as HTMLButtonElement).disabled).toBe(true)
  })

  it('Export downloads the selected scenario', async () => {
    const createObjectURL = vi.fn(() => 'blob:fake')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    useScenarioStore.getState().add(makeScenario())
    renderBar()

    await userEvent.click(screen.getByRole('combobox'))
    await userEvent.click(await screen.findByTitle('nightly-soak'))
    await userEvent.click(screen.getByTestId('scenario-export'))

    expect(createObjectURL).toHaveBeenCalledOnce()
  })

  // ── Profile scenarios (G.3d) ────────────────────────────────────────────

  it('saves a FLAT scenario with mode flat and no phases', async () => {
    renderBar()

    await userEvent.click(screen.getByTestId('scenario-save-as'))
    await userEvent.type(screen.getByTestId('scenario-name-input'), 'flat-one')
    await userEvent.click(screen.getByTestId('scenario-save-confirm'))

    await waitFor(() => expect(useScenarioStore.getState().scenarios).toHaveLength(1))
    const saved = useScenarioStore.getState().scenarios[0]
    expect(saved.mode).toBe('flat')
    expect(saved.phases).toBeUndefined()
  })

  it('saves a PROFILE scenario with its phases', async () => {
    const phases = [
      { id: 'p1', label: 'burst', rate: 500, durationSecs: 10 },
      { id: 'p2', label: 'idle', rate: 0, durationSecs: 15 },
    ]
    renderBar({ mode: 'profile', phases })

    await userEvent.click(screen.getByTestId('scenario-save-as'))
    await userEvent.type(screen.getByTestId('scenario-name-input'), 'spike-repro')
    await userEvent.click(screen.getByTestId('scenario-save-confirm'))

    await waitFor(() => expect(useScenarioStore.getState().scenarios).toHaveLength(1))
    const saved = useScenarioStore.getState().scenarios[0]
    expect(saved.mode).toBe('profile')
    expect(saved.phases).toHaveLength(2)
    expect(saved.phases![1].rate).toBe(0)
    // Persisted, and it survives the validating reload.
    expect(loadAll()[0].phases).toHaveLength(2)
  })

  it('refuses to save a PROFILE scenario with no phases', async () => {
    renderBar({ mode: 'profile', phases: [] })

    await userEvent.click(screen.getByTestId('scenario-save-as'))
    await userEvent.type(screen.getByTestId('scenario-name-input'), 'empty-profile')
    await userEvent.click(screen.getByTestId('scenario-save-confirm'))

    expect(screen.getByTestId('scenario-save-error').textContent).toMatch(/phase/i)
    expect(useScenarioStore.getState().scenarios).toHaveLength(0)
  })

  it('BLOCKS saving in Ramp mode — a scenario has no ramp kind, so it would be stored as flat', () => {
    renderBar({ mode: 'ramp' })

    // Disabled, not silently saving something that replays as a different run.
    expect((screen.getByTestId('scenario-save-as') as HTMLButtonElement).disabled).toBe(true)
  })

  it('hydrates the select from localStorage on mount', async () => {
    localStorage.setItem('peegeeq_scenarios', JSON.stringify([makeScenario({ name: 'from-storage' })]))
    renderBar()

    await waitFor(() => {
      expect(useScenarioStore.getState().scenarios).toHaveLength(1)
    })
    await userEvent.click(screen.getByRole('combobox'))
    expect(await screen.findByTitle('from-storage')).toBeTruthy()
  })
})
