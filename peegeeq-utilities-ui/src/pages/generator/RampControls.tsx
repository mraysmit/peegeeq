/**
 * Zone B — Ramp mode controls (design §19.1 — G.1a).
 *
 * Presentational and fully controlled, like RateControls and
 * ProfilePhasesEditor: the page owns the settings and receives every change
 * through onChange.
 *
 * The plan preview is DERIVED here by calling the same `buildRampPhases` the
 * run uses, so what the operator is shown is exactly what will run — a preview
 * computed a second way could disagree with the ramp.
 */
import { Alert, InputNumber, Radio, Typography } from 'antd'
import { buildRampPhases } from '../../engine/rampPlan'
import type { RampSettings } from '../../types/ramp'

const { Text } = Typography

interface RampControlsProps {
  value: RampSettings
  onChange: (value: RampSettings) => void
  disabled?: boolean
}

export default function RampControls({ value, onChange, disabled = false }: RampControlsProps) {
  // The same builder the run uses — a preview computed another way could
  // describe a ramp that never happens.
  const phases = buildRampPhases(value)
  const topRate = phases.length > 0 ? phases[phases.length - 1].rate : 0
  const totalSecs = phases.reduce((sum, p) => sum + p.durationSecs, 0)

  function update(patch: Partial<RampSettings>): void {
    onChange({ ...value, ...patch })
  }

  const numberField = (
    key: 'startRate' | 'stepRate' | 'stepSecs' | 'errorRatePercent',
    label: string,
    min: number,
    fieldDisabled = false
  ) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label htmlFor={`ramp-${key}`}>{label}</label>
      <InputNumber
        id={`ramp-${key}`}
        min={min}
        value={value[key]}
        // A cleared field emits null mid-edit; ignore it rather than snapping a
        // fallback under the user's cursor (the RateControls rule).
        onChange={(v) => {
          if (v !== null) update({ [key]: v } as Partial<RampSettings>)
        }}
        disabled={disabled || fieldDisabled}
        style={{ width: 170 }}
      />
    </div>
  )

  return (
    <div data-testid="ramp-controls">
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {numberField('startRate', 'Start rate (msg/s)', 1)}
        {numberField('stepRate', 'Step size (msg/s)', 1)}
        {numberField('stepSecs', 'Step every (seconds)', 1)}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="ramp-maxRate">Max rate (msg/s, optional)</label>
          <InputNumber
            id="ramp-maxRate"
            min={1}
            value={value.maxRate ?? undefined}
            placeholder="no cap"
            // Clearing this field is MEANINGFUL here — it means "no cap" —
            // unlike the other numbers, so null is stored rather than ignored.
            onChange={(v) => update({ maxRate: v === null ? null : v })}
            disabled={disabled}
            style={{ width: 190 }}
          />
        </div>
      </div>

      <div style={{ marginTop: 12, display: 'flex', gap: 16, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span>Stop when</span>
          <Radio.Group
            value={value.stopOn}
            onChange={(e) => update({ stopOn: e.target.value })}
            disabled={disabled}
            options={[
              { label: 'Error rate exceeds threshold', value: 'error-rate' },
              { label: 'Acked-rate plateau', value: 'plateau' },
            ]}
          />
        </div>
        {/* Disabled under the plateau rule: a threshold with no effect must not
            look like it has one. */}
        {numberField('errorRatePercent', 'Error rate threshold (%)', 0, value.stopOn === 'plateau')}
      </div>

      {phases.length === 0 ? (
        <div style={{ marginTop: 12 }}>
          <Alert
            type="info"
            showIcon
            data-testid="ramp-empty-advisory"
            message="This ramp has no steps"
            description="The start rate is above the max rate, so there is nothing to run. Lower the start rate or raise the cap."
          />
        </div>
      ) : (
        <div style={{ marginTop: 12 }}>
          <Text data-testid="ramp-plan-preview">
            {phases.length} step{phases.length === 1 ? '' : 's'} · {value.startRate} → {topRate} msg/s
            · up to {totalSecs} s
          </Text>
        </div>
      )}
    </div>
  )
}
