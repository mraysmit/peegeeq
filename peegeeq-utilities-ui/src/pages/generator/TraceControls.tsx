/**
 * Zone B — Correlation / trace seed controls (design §19.6 — G.6).
 *
 * Fully controlled, like ExerciserControls: the page owns the settings and
 * receives every change through onChange.
 *
 * The scheme summary is DERIVED from the settings on every render. Unlike the
 * exerciser's plan preview there are no example ids at all: every minted id
 * derives from the RUN id, which does not exist until Start, so showing ids
 * here would show values that can never occur. The emitted-ids panel after
 * the run is the exact record.
 */
import { Checkbox, InputNumber, Radio, Typography } from 'antd'
import type {
  CorrelationStrategy,
  TraceSettings,
} from '../../types/trace'

const { Text } = Typography

interface TraceControlsProps {
  value: TraceSettings
  onChange: (value: TraceSettings) => void
  disabled?: boolean
}

export default function TraceControls({ value, onChange, disabled = false }: TraceControlsProps) {
  const { correlation, causation } = value

  function update(patch: Partial<TraceSettings>): void {
    onChange({ ...value, ...patch })
  }

  const strategyLabel =
    correlation.kind === 'per-run'
      ? 'one correlation id for the whole run'
      : correlation.kind === 'per-batch'
        ? 'a new correlation id for every publish batch'
        : `a new correlation id every ${correlation.n} messages`
  const chainLabel = causation.enabled
    ? ` · ids organized into chains of 1 root + ${causation.childrenPerParent} child${causation.childrenPerParent === 1 ? '' : 'ren'}`
    : ''

  return (
    <div data-testid="trace-controls">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span>Correlation id</span>
        <Radio.Group
          value={correlation.kind}
          onChange={(e) => {
            const kind = e.target.value as CorrelationStrategy['kind']
            const next: CorrelationStrategy =
              kind === 'every-n' ? { kind, n: 100 } : { kind }
            update({ correlation: next })
          }}
          disabled={disabled}
          options={[
            { label: 'One per run', value: 'per-run' },
            { label: 'One per batch', value: 'per-batch' },
            { label: 'Every N messages', value: 'every-n' },
          ]}
        />
      </div>
      {correlation.kind === 'every-n' && (
        <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="trace-every-n">New id every (messages)</label>
          <InputNumber
            id="trace-every-n"
            min={1}
            value={correlation.n}
            // A cleared field emits null mid-edit; ignore it rather than
            // snapping a fallback under the user's cursor (the RateControls rule).
            onChange={(v) => {
              if (v !== null) update({ correlation: { kind: 'every-n', n: v } })
            }}
            disabled={disabled}
            style={{ width: 190 }}
          />
        </div>
      )}

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <Checkbox
          id="trace-causation"
          checked={causation.enabled}
          onChange={(e) => update({ causation: { ...causation, enabled: e.target.checked } })}
          disabled={disabled}
        >
          Seed causation chains (parent → child)
        </Checkbox>
        {causation.enabled && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label htmlFor="trace-children">Children per parent</label>
            <InputNumber
              id="trace-children"
              min={1}
              value={causation.childrenPerParent}
              onChange={(v) => {
                if (v !== null) update({ causation: { ...causation, childrenPerParent: v } })
              }}
              disabled={disabled}
              style={{ width: 190 }}
            />
          </div>
        )}
      </div>

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <Text data-testid="trace-scheme-summary">
          Id scheme: {strategyLabel}
          {chainLabel}.
        </Text>
        <Text type="secondary" data-testid="trace-preview-caveat">
          Ids are minted from the run id at Start — chains are an id scheme in the emitted-ids
          report; the queue messages carry only their own correlation id, and the
          {' {{correlationId}} '}token resolves to it per message.
        </Text>
      </div>
    </div>
  )
}
