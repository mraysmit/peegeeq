/**
 * Zone B — Delay / Priority / FIFO exerciser controls (design §19.5 — G.5-send).
 *
 * Fully controlled, like RampControls and ProfilePhasesEditor: the page owns
 * the settings and receives every change through onChange. The one store read
 * is the valueListStore, which supplies the per-key list options — the same
 * source the run's assignment draws from.
 *
 * The plan preview is DERIVED here by calling the same `assignmentFor` the run
 * uses, so what the operator is shown is what will run. Random and per-key
 * values are seeded by the RUN id, which does not exist yet, so the preview is
 * computed with a fixed placeholder id and says so — the post-run manifest is
 * the exact record.
 */
import { Alert, Input, InputNumber, Radio, Select, Typography } from 'antd'
import { assignmentFor } from '../../engine/exerciserPlan'
import { useValueListStore } from '../../stores/valueListStore'
import type {
  DelayStrategy,
  ExerciserSettings,
  GroupStrategy,
  PriorityStrategy,
} from '../../types/exerciser'

const { Text } = Typography

/** Rows of the derived plan preview — enough to show every cycle without a table. */
const PREVIEW_ROWS = 6

/** The preview's stand-in run id; the real one exists only once the run starts. */
const PREVIEW_RUN_ID = 'plan-preview'

interface ExerciserControlsProps {
  value: ExerciserSettings
  onChange: (value: ExerciserSettings) => void
  disabled?: boolean
}

export default function ExerciserControls({
  value,
  onChange,
  disabled = false,
}: ExerciserControlsProps) {
  const lists = useValueListStore((s) => s.lists)

  function update(patch: Partial<ExerciserSettings>): void {
    onChange({ ...value, ...patch })
  }

  const { delay, priority, group } = value

  const perKeyList =
    group.kind === 'per-key' ? (lists.find((l) => l.name === group.listName) ?? null) : null
  const perKeyMissing = group.kind === 'per-key' && (!perKeyList || perKeyList.values.length === 0)

  // The preview depends on the run id for these two strategies; everything
  // else is exact regardless of which run executes it.
  const previewIsIllustrative = delay.kind === 'random' || group.kind === 'per-key'

  const valueLists = useValueListStore.getState().snapshot()
  const previewRows = perKeyMissing
    ? []
    : Array.from({ length: PREVIEW_ROWS }, (_, index) => {
        const a = assignmentFor(value, PREVIEW_RUN_ID, index, valueLists)
        return `#${String(index + 1).padStart(8, '0')} · ${a.messageGroup} · p${a.priority} · d${a.delaySeconds}s`
      })

  const numberInput = (
    id: string,
    label: string,
    inputValue: number,
    min: number,
    apply: (v: number) => void,
    max?: number
  ) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label htmlFor={id}>{label}</label>
      <InputNumber
        id={id}
        min={min}
        max={max}
        value={inputValue}
        // A cleared field emits null mid-edit; ignore it rather than snapping a
        // fallback under the user's cursor (the RateControls rule).
        onChange={(v) => {
          if (v !== null) apply(v)
        }}
        disabled={disabled}
        style={{ width: 170 }}
      />
    </div>
  )

  return (
    <div data-testid="exerciser-controls">
      <div data-testid="exerciser-override-note" style={{ marginBottom: 12 }}>
        <Alert
          type="info"
          showIcon
          message="These strategies assign delay, priority and group per message, overriding the template's priority, delay and group fields (which the Preview modal still shows)."
        />
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span>Delay</span>
        <Radio.Group
          value={delay.kind}
          onChange={(e) => {
            const kind = e.target.value as DelayStrategy['kind']
            const next: DelayStrategy =
              kind === 'fixed'
                ? { kind, seconds: 5 }
                : kind === 'random'
                  ? { kind, maxSeconds: 10 }
                  : { kind, stepSeconds: 1, maxSeconds: 30 }
            update({ delay: next })
          }}
          disabled={disabled}
          options={[
            { label: 'Fixed delay', value: 'fixed' },
            { label: 'Random delay', value: 'random' },
            { label: 'Per-index ramp', value: 'per-index-ramp' },
          ]}
        />
      </div>
      <div style={{ marginTop: 8, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {delay.kind === 'fixed' &&
          numberInput('exerciser-delay-seconds', 'Delay (seconds)', delay.seconds, 0, (v) =>
            update({ delay: { kind: 'fixed', seconds: v } })
          )}
        {delay.kind === 'random' &&
          numberInput('exerciser-delay-max', 'Max delay (seconds)', delay.maxSeconds, 0, (v) =>
            update({ delay: { kind: 'random', maxSeconds: v } })
          )}
        {delay.kind === 'per-index-ramp' && (
          <>
            {numberInput('exerciser-ramp-step', 'Ramp step (seconds)', delay.stepSeconds, 0, (v) =>
              update({ delay: { ...delay, stepSeconds: v } })
            )}
            {numberInput('exerciser-ramp-cap', 'Ramp cap (seconds)', delay.maxSeconds, 0, (v) =>
              update({ delay: { ...delay, maxSeconds: v } })
            )}
          </>
        )}
      </div>

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span>Priority</span>
        <Radio.Group
          value={priority.kind}
          onChange={(e) => {
            const kind = e.target.value as PriorityStrategy['kind']
            update({ priority: kind === 'fixed' ? { kind, priority: 5 } : { kind } })
          }}
          disabled={disabled}
          options={[
            { label: 'Fixed priority', value: 'fixed' },
            { label: 'Round-robin 1–10', value: 'round-robin' },
          ]}
        />
      </div>
      {priority.kind === 'fixed' && (
        <div style={{ marginTop: 8 }}>
          {numberInput(
            'exerciser-priority',
            'Priority (0–10)',
            priority.priority,
            0,
            (v) => update({ priority: { kind: 'fixed', priority: v } }),
            10
          )}
        </div>
      )}

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span>Group (FIFO ordering)</span>
        <Radio.Group
          value={group.kind}
          onChange={(e) => {
            const kind = e.target.value as GroupStrategy['kind']
            const next: GroupStrategy =
              kind === 'single'
                ? { kind, group: 'group-1' }
                : kind === 'round-robin'
                  ? { kind, groups: 4 }
                  : { kind, listName: lists[0]?.name ?? '' }
            update({ group: next })
          }}
          disabled={disabled}
          options={[
            { label: 'Single group', value: 'single' },
            { label: 'Round-robin groups', value: 'round-robin' },
            { label: 'Per-key from value list', value: 'per-key' },
          ]}
        />
      </div>
      <div style={{ marginTop: 8, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        {group.kind === 'single' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label htmlFor="exerciser-group-name">Group name</label>
            <Input
              id="exerciser-group-name"
              value={group.group}
              onChange={(e) => update({ group: { kind: 'single', group: e.target.value } })}
              disabled={disabled}
              style={{ width: 220 }}
            />
          </div>
        )}
        {group.kind === 'round-robin' &&
          numberInput('exerciser-group-count', 'Number of groups', group.groups, 1, (v) =>
            update({ group: { kind: 'round-robin', groups: v } })
          )}
        {group.kind === 'per-key' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label htmlFor="exerciser-group-list">Value list</label>
            <Select
              id="exerciser-group-list"
              value={group.listName === '' ? undefined : group.listName}
              placeholder={lists.length === 0 ? 'No value lists exist' : 'Select a value list'}
              onChange={(name) => update({ group: { kind: 'per-key', listName: name } })}
              options={lists.map((l) => ({ value: l.name, label: l.name }))}
              disabled={disabled}
              style={{ width: 220 }}
            />
          </div>
        )}
      </div>

      {perKeyMissing ? (
        <div style={{ marginTop: 12 }}>
          <Alert
            type="warning"
            showIcon
            data-testid="exerciser-per-key-warning"
            message={
              group.kind === 'per-key' && group.listName !== ''
                ? `Value list "${group.listName}" is missing or empty — the run cannot assign per-key groups until it has values.`
                : 'Choose a value list for the per-key group strategy.'
            }
          />
        </div>
      ) : (
        <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <Text type="secondary">Plan preview (first {PREVIEW_ROWS} messages)</Text>
          <pre data-testid="exerciser-plan-preview" style={{ margin: 0 }}>
            {previewRows.join('\n')}
          </pre>
          {previewIsIllustrative && (
            <Text type="secondary" data-testid="exerciser-preview-caveat">
              Random and per-key values are derived from the run id, which does not exist yet —
              this preview is illustrative; the post-run manifest is exact.
            </Text>
          )}
        </div>
      )}
    </div>
  )
}
