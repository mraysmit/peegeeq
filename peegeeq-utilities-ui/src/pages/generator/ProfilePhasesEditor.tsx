/**
 * Zone B — Profile mode phases editor (design §19.3 — G.3b).
 *
 * Presentational and fully controlled, exactly like RateControls: the page owns
 * the phase list and receives every change through onChange. Controls use
 * explicit label/id pairs (not Form.Item name-binding) because the values are
 * controlled from props, not from antd Form state.
 *
 * The requested total and the profile duration are DERIVED here on every
 * render. Neither is stored — the same rule the profile results follow, so an
 * achieved figure can never sit beside a stale requested one.
 */
import { Alert, Button, InputNumber, Input, Tag, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type { ProfilePhase } from '../../types/profile'

const { Text } = Typography

/** A new phase: modest, immediately runnable defaults with a fresh id. */
export function makeDefaultPhase(): ProfilePhase {
  return { id: crypto.randomUUID(), label: 'phase', rate: 100, durationSecs: 30 }
}

interface ProfilePhasesEditorProps {
  value: ProfilePhase[]
  onChange: (value: ProfilePhase[]) => void
  disabled?: boolean
}

export default function ProfilePhasesEditor({
  value,
  onChange,
  disabled = false,
}: ProfilePhasesEditorProps) {
  // Derived, never stored. An idle phase (rate 0) contributes no messages but
  // its duration is still part of the shape's length.
  const totalMessages = value.reduce((sum, p) => sum + p.rate * p.durationSecs, 0)
  const totalDuration = value.reduce((sum, p) => sum + p.durationSecs, 0)

  function updatePhase(id: string, patch: Partial<ProfilePhase>): void {
    onChange(value.map((p) => (p.id === id ? { ...p, ...patch } : p)))
  }

  return (
    <div data-testid="profile-phases-editor">
      {value.length === 0 ? (
        <Alert
          type="info"
          showIcon
          data-testid="profile-empty-advisory"
          message="No phases yet"
          description="A profile needs at least one phase. Add one to describe the traffic shape — for example burst, steady, spike, idle."
        />
      ) : (
        value.map((p, index) => (
          <div
            key={p.id}
            data-testid={`phase-row-${p.id}`}
            style={{ display: 'flex', gap: 16, alignItems: 'flex-end', marginBottom: 12, flexWrap: 'wrap' }}
          >
            <Text type="secondary" style={{ width: 20 }}>
              {index + 1}
            </Text>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor={`phase-label-${p.id}`}>Label</label>
              <Input
                id={`phase-label-${p.id}`}
                value={p.label}
                onChange={(e) => updatePhase(p.id, { label: e.target.value })}
                disabled={disabled}
                style={{ width: 160 }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor={`phase-rate-${p.id}`}>Rate (msg/s)</label>
              <InputNumber
                id={`phase-rate-${p.id}`}
                min={0}
                value={p.rate}
                // A cleared field emits null mid-edit; ignore it rather than
                // snapping a fallback under the user's cursor (RateControls rule).
                onChange={(v) => {
                  if (v !== null) updatePhase(p.id, { rate: v })
                }}
                disabled={disabled}
                style={{ width: 140 }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor={`phase-duration-${p.id}`}>Duration (seconds)</label>
              <InputNumber
                id={`phase-duration-${p.id}`}
                min={1}
                max={3600}
                value={p.durationSecs}
                onChange={(v) => {
                  if (v !== null) updatePhase(p.id, { durationSecs: v })
                }}
                disabled={disabled}
                style={{ width: 160 }}
              />
            </div>

            {/* Rate 0 is a deliberate shape, not a mistake: the runner waits it
                out and publishes nothing. Naming it stops it reading as unset. */}
            {p.rate === 0 && (
              <Tag data-testid={`phase-idle-badge-${index}`} color="default">
                idle
              </Tag>
            )}

            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              data-testid={`phase-remove-${p.id}`}
              aria-label={`Remove phase ${index + 1}`}
              onClick={() => onChange(value.filter((other) => other.id !== p.id))}
              disabled={disabled}
            />
          </div>
        ))
      )}

      <div style={{ marginTop: 8, display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        <Button
          icon={<PlusOutlined />}
          onClick={() => onChange([...value, makeDefaultPhase()])}
          disabled={disabled}
        >
          Add phase
        </Button>
        <Text data-testid="profile-total-messages">Total messages = {totalMessages}</Text>
        <Text data-testid="profile-total-duration">Profile duration = {totalDuration} s</Text>
      </div>
    </div>
  )
}
