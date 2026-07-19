/**
 * Zone B — Rate, Duration & Guards (feature §6.1).
 *
 * Presentational and fully controlled: the page owns the {@link RateSettings}
 * state (per the B.5 assembly contract) and receives every change through
 * onChange. The rate warning is a non-blocking advisory only — it never
 * disables anything; it is dismissible and re-appears when the threshold is
 * crossed again.
 *
 * Controls use explicit label/id pairs (not Form.Item name-binding) because the
 * values are controlled from props, not from antd Form state.
 */
import { useEffect, useState } from 'react'
import { Alert, InputNumber, Typography } from 'antd'
import type { RateSettings } from '../../types/generator'

const { Text } = Typography

/** §6.1 Zone B defaults. */
export const RATE_DEFAULTS: RateSettings = {
  rate: 10,
  durationSecs: 60,
  maxBatchSize: 10,
  warnThreshold: 500,
  maxConsecErrors: 10,
}

interface RateControlsProps {
  value: RateSettings
  onChange: (value: RateSettings) => void
  disabled?: boolean
}

interface FieldSpec {
  key: keyof RateSettings
  label: string
  min: number
  max?: number
}

const FIELDS: FieldSpec[] = [
  { key: 'rate', label: 'Rate (msg/s)', min: 1 },
  { key: 'durationSecs', label: 'Duration (seconds)', min: 1, max: 3600 },
  { key: 'maxBatchSize', label: 'Max batch size', min: 1, max: 100 },
  { key: 'warnThreshold', label: 'Warn above (msg/s)', min: 0 },
  { key: 'maxConsecErrors', label: 'Auto-stop after N consecutive errors', min: 0 },
]

export default function RateControls({ value, onChange, disabled = false }: RateControlsProps) {
  const [warningDismissed, setWarningDismissed] = useState(false)

  const overThreshold = value.warnThreshold > 0 && value.rate > value.warnThreshold

  // Re-arm the advisory once the rate is back at/below the threshold, so the
  // next crossing warns again instead of staying silently dismissed.
  useEffect(() => {
    if (!overThreshold) {
      setWarningDismissed(false)
    }
  }, [overThreshold])

  return (
    <div data-testid="rate-controls">
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {FIELDS.map((field) => (
          <div key={field.key} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label htmlFor={`rate-controls-${field.key}`}>{field.label}</label>
            <InputNumber
              id={`rate-controls-${field.key}`}
              min={field.min}
              max={field.max}
              value={value[field.key]}
              // A cleared field emits null mid-edit; ignore it (the input stays
              // empty while focused, and blur restores the controlled value)
              // rather than snapping a fallback under the user's cursor.
              onChange={(v) => {
                if (v !== null) onChange({ ...value, [field.key]: v })
              }}
              disabled={disabled}
              style={{ width: 160 }}
            />
          </div>
        ))}
      </div>

      <div style={{ marginTop: 8 }}>
        <Text data-testid="total-messages">
          Total messages = {value.rate * value.durationSecs}
        </Text>
      </div>

      {overThreshold && !warningDismissed && (
        <div data-testid="rate-warning" style={{ marginTop: 12 }}>
          <Alert
            type="warning"
            showIcon
            closable
            onClose={() => setWarningDismissed(true)}
            message={`Rate exceeds the warning threshold of ${value.warnThreshold} msg/s. This may stress the target queue or saturate the local browser tab. The run will proceed — this is advisory only.`}
          />
        </div>
      )}
    </div>
  )
}
