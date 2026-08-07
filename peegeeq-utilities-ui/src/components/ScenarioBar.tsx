/**
 * Scenario bar (design §19.4 — Phase G.4).
 *
 * Sits at the top of the Message Generator: pick a saved scenario, Load it into
 * the generator, Save the current configuration as a new scenario, or Export the
 * selected one. Import lives on the Tools page, which owns the scenario table —
 * one import code path per collection, as templates, value lists and schedules
 * each have.
 *
 * The bar owns no run configuration of its own. `config` is the page's live
 * assembled RunConfig (null when no target is selected, which is exactly when
 * there is nothing worth saving), and `onLoad` hands a chosen scenario back to
 * the page, which is the single owner of the working state.
 */
import { useEffect, useState } from 'react'
import { Button, Input, Modal, Select, Space, Tooltip, Typography, message } from 'antd'
import { ExportOutlined, SaveOutlined } from '@ant-design/icons'
import { useScenarioStore } from '../stores/scenarioStore'
import { exportScenario } from '../services/scenarioService'
import type { RunConfig } from '../types/generator'
import type { Scenario } from '../types/scenario'
import type { ProfilePhase } from '../types/profile'

const { Text } = Typography

export interface ScenarioBarProps {
  /** The generator's live configuration; null while no target is selected. */
  config: RunConfig | null
  /** Hands the chosen scenario to the page, which applies it to its own state. */
  onLoad: (scenario: Scenario) => void
  /** True while a run is active — loading or saving mid-run is refused. */
  disabled?: boolean
  /**
   * The generator's live mode — saved so Load can restore it (G.3d).
   *
   * `ramp`, `exerciser`, `trace` and `compare` are accepted but NOT saveable:
   * `Scenario` has none of those kinds, so saving one would store it as a flat
   * scenario that replays as a completely different run. `compare` is the
   * starkest case — it has TWO targets and `RunConfig` holds one. Saving is
   * blocked instead (G.1a, G.5, G.6, G.2c).
   */
  mode: 'flat' | 'profile' | 'ramp' | 'exerciser' | 'trace' | 'compare'
  /** The live traffic shape; saved only when mode is 'profile'. */
  phases: ProfilePhase[]
}

export default function ScenarioBar({
  config,
  onLoad,
  disabled = false,
  mode,
  phases,
}: ScenarioBarProps) {
  const scenarios = useScenarioStore((s) => s.scenarios)
  const loadFromStorage = useScenarioStore((s) => s.loadFromStorage)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [saveName, setSaveName] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | undefined>(undefined)

  useEffect(() => {
    loadFromStorage()
  }, [loadFromStorage])

  const selected = scenarios.find((s) => s.id === selectedId) ?? null
  // A ramp or an exerciser run cannot be represented as a Scenario yet; refuse
  // rather than store something that would replay as a different run.
  const saveBlockedReason =
    mode === 'ramp'
      ? 'Ramp runs cannot be saved as scenarios yet.'
      : mode === 'exerciser'
        ? 'Exerciser runs cannot be saved as scenarios yet.'
        : mode === 'trace'
          ? 'Trace-seed runs cannot be saved as scenarios yet.'
          : mode === 'compare'
            ? 'Comparison runs cannot be saved as scenarios: a scenario holds one target and a comparison has two.'
            : undefined

  function handleLoad() {
    if (!selected) return
    onLoad(selected)
    message.success(`Scenario "${selected.name}" loaded.`)
  }

  function handleSave() {
    const name = (saveName ?? '').trim()
    if (name.length === 0) {
      setSaveError('A scenario name is required.')
      return
    }
    if (!config) {
      // The button is disabled without a config; this covers a target cleared
      // between opening the dialog and confirming it.
      setSaveError('Select a target queue before saving a scenario.')
      return
    }
    if (mode === 'ramp' || mode === 'exerciser' || mode === 'trace' || mode === 'compare') {
      // Checked as MODES, not via saveBlockedReason: this also narrows `mode`
      // to the two kinds a Scenario can actually hold, so the object below
      // cannot be built with a mode the model does not support.
      setSaveError(saveBlockedReason)
      return
    }
    if (mode === 'profile' && phases.length === 0) {
      // A profile scenario with no phases could never replay — refuse at the
      // point of saving rather than storing something inert.
      setSaveError('Add at least one phase before saving a profile scenario.')
      return
    }
    const now = new Date().toISOString()
    const scenario: Scenario = {
      id: crypto.randomUUID(),
      name,
      config,
      mode,
      // Phases belong to a profile scenario only — a flat one carries none.
      ...(mode === 'profile' ? { phases } : {}),
      createdAt: now,
      updatedAt: now,
    }
    useScenarioStore.getState().add(scenario)
    setSelectedId(scenario.id)
    setSaveName(null)
    setSaveError(undefined)
    message.success(`Scenario "${name}" saved.`)
  }

  return (
    <Space wrap align="center" data-testid="scenario-bar">
      <label htmlFor="scenario-select">Scenario</label>
      <Select
        id="scenario-select"
        aria-label="Scenario"
        data-testid="scenario-select"
        placeholder={scenarios.length === 0 ? 'No saved scenarios' : 'Select a scenario'}
        value={selectedId ?? undefined}
        onChange={setSelectedId}
        options={scenarios.map((s) => ({ value: s.id, label: s.name }))}
        style={{ minWidth: 220 }}
        disabled={disabled || scenarios.length === 0}
      />
      <Button data-testid="scenario-load" onClick={handleLoad} disabled={disabled || selected === null}>
        Load
      </Button>
      <Tooltip title={saveBlockedReason}>
        <Button
          icon={<SaveOutlined />}
          data-testid="scenario-save-as"
          onClick={() => {
            setSaveError(undefined)
            setSaveName('')
          }}
          disabled={disabled || config === null || saveBlockedReason !== undefined}
        >
          Save as…
        </Button>
      </Tooltip>
      <Button
        icon={<ExportOutlined />}
        data-testid="scenario-export"
        onClick={() => selected && exportScenario(selected)}
        disabled={selected === null}
      >
        Export
      </Button>

      <Modal
        title="Save scenario"
        open={saveName !== null}
        onCancel={() => {
          setSaveName(null)
          setSaveError(undefined)
        }}
        destroyOnHidden
        footer={[
          <Button
            key="cancel"
            onClick={() => {
              setSaveName(null)
              setSaveError(undefined)
            }}
          >
            Cancel
          </Button>,
          <Button key="save" type="primary" data-testid="scenario-save-confirm" onClick={handleSave}>
            Save
          </Button>,
        ]}
      >
        <label htmlFor="scenario-name">Scenario name</label>
        <Input
          id="scenario-name"
          data-testid="scenario-name-input"
          value={saveName ?? ''}
          onChange={(e) => {
            setSaveName(e.target.value)
            setSaveError(undefined)
          }}
          placeholder="nightly-soak"
        />
        {saveError && (
          <div style={{ marginTop: 8 }}>
            <Text type="danger" data-testid="scenario-save-error">
              {saveError}
            </Text>
          </div>
        )}
        <div style={{ marginTop: 12 }}>
          <Text type="secondary">
            Saves the current target, rate, duration, guards and template as a replayable
            scenario.
          </Text>
        </div>
      </Modal>
    </Space>
  )
}
