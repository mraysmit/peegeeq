/**
 * Zone C — Template editor (feature §6.1, §5; working-copy contract 2026-07-18).
 *
 * Edits a WORKING COPY of a message template. The page owns the working copy
 * (value/onChange, like RateControls); Preview and Start use it as-is, saved or
 * not. Save is pure persistence back to the templateStore/localStorage; it is
 * never required before a run. Selecting a different template (or New) replaces
 * the working copy after an "unsaved changes" confirm when the copy is dirty.
 *
 * Payload validation on blur resolves the template with a sample context and
 * then parses (§8) — raw JSON.parse would false-error on legal bare tokens
 * such as `"amount": {{random:500}}`.
 */
import { useEffect, useState } from 'react'
import { Alert, Button, Collapse, Input, InputNumber, Modal, Select, Space, message } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useTemplateStore } from '../../stores/templateStore'
import { resolveTemplate } from '../../engine/templateResolver'
import type { MessageTemplate } from '../../types/generator'

const MAX_HEADERS = 20

/** Blank working copy for the New action. */
export function blankTemplate(): MessageTemplate {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    name: 'Untitled',
    messageType: '',
    payloadSchema: '{}',
    headers: {},
    priority: 5,
    delaySeconds: 0,
    createdAt: now,
    updatedAt: now,
  }
}

/** §5.3 placeholder tokens for the reference panel. */
const PLACEHOLDERS: Array<{ token: string; scope: string; description: string }> = [
  { token: '{{messageId}}', scope: 'per-message', description: 'Auto-incrementing integer, zero-padded to 8 digits' },
  { token: '{{sequenceId}}', scope: 'per-message', description: 'Alias for {{messageId}}' },
  { token: '{{uuid}}', scope: 'per-message', description: 'Fresh UUID for each individual message' },
  { token: '{{timestamp}}', scope: 'per-message', description: 'ISO 8601 UTC datetime at publish time' },
  { token: '{{unixMs}}', scope: 'per-message', description: 'Unix epoch milliseconds as integer string' },
  { token: '{{index}}', scope: 'per-message', description: '0-based position of the message within the run' },
  { token: '{{random:N}}', scope: 'per-message', description: 'Random integer in range 0 to N (exclusive)' },
  { token: '{{randomAlpha:N}}', scope: 'per-message', description: 'Random alphanumeric string of length N' },
  { token: '{{list:name}}', scope: 'per-message', description: 'Random element from the named value list (§5.5)' },
  { token: '{{correlationId}}', scope: 'per-run', description: 'Single UUID shared across all messages of the run' },
  { token: '{{runId}}', scope: 'per-run', description: 'UUID of the run, same for all its messages' },
]

interface HeaderRow {
  key: string
  value: string
}

interface TemplateEditorProps {
  value: MessageTemplate
  onChange: (value: MessageTemplate) => void
  disabled?: boolean
}

function toRows(headers: Record<string, string>): HeaderRow[] {
  return Object.entries(headers).map(([key, value]) => ({ key, value }))
}

function toRecord(rows: HeaderRow[]): Record<string, string> {
  const record: Record<string, string> = {}
  for (const row of rows) {
    if (row.key !== '') record[row.key] = row.value
  }
  return record
}

/**
 * Dirty = content differs from the stored counterpart. Timestamps and id are
 * excluded: updatedAt is re-stamped by Save, createdAt carries no user edit,
 * and blankTemplate() generates a new id per call — dirtiness is a statement
 * about content, not about when or with which id objects were constructed.
 * An UNSTORED template compares against a blank: a pristine blank is NOT
 * dirty, so New on it must not raise a spurious discard confirm.
 */
function isDirty(value: MessageTemplate, stored: MessageTemplate | undefined): boolean {
  const content = (t: MessageTemplate) => JSON.stringify({ ...t, id: '', createdAt: '', updatedAt: '' })
  if (!stored) return content(value) !== content(blankTemplate())
  return content(value) !== content(stored)
}

export default function TemplateEditor({ value, onChange, disabled = false }: TemplateEditorProps) {
  const templates = useTemplateStore((s) => s.templates)
  const loadFromStorage = useTemplateStore((s) => s.loadFromStorage)

  const [headerRows, setHeaderRows] = useState<HeaderRow[]>(() => toRows(value.headers))
  const [payloadError, setPayloadError] = useState<string | null>(null)

  useEffect(() => {
    loadFromStorage()
  }, [loadFromStorage])

  // A different working copy (select/New) resets header editing and the error.
  useEffect(() => {
    setHeaderRows(toRows(value.headers))
    setPayloadError(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.id])

  const storedCounterpart = templates.find((t) => t.id === value.id)
  const dirty = isDirty(value, storedCounterpart)

  /** Runs the action directly, or after an unsaved-changes confirm when dirty. */
  function guardDirty(action: () => void) {
    if (!dirty) {
      action()
      return
    }
    Modal.confirm({
      title: 'Unsaved changes',
      content: 'Discard the current edits?',
      onOk: action,
    })
  }

  function selectTemplate(id: string) {
    const stored = templates.find((t) => t.id === id)
    if (!stored) return
    guardDirty(() => onChange({ ...stored, headers: { ...stored.headers } }))
  }

  function saveWorkingCopy() {
    const store = useTemplateStore.getState()
    if (store.templates.some((t) => t.id === value.id)) {
      store.update(value)
    } else {
      store.add(value)
    }
    message.success(`Template "${value.name}" saved.`)
  }

  function exportWorkingCopy() {
    const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${value.name}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  function validatePayload() {
    try {
      resolveTemplate(value.payloadSchema, {
        messageId: 1,
        index: 0,
        runId: 'validate',
        correlationId: 'validate',
        now: new Date(),
        valueLists: {},
      })
      setPayloadError(null)
    } catch (error) {
      setPayloadError(error instanceof Error ? error.message : String(error))
    }
  }

  function updateHeaderRows(rows: HeaderRow[]) {
    setHeaderRows(rows)
    onChange({ ...value, headers: toRecord(rows) })
  }

  // toRecord collapses duplicate keys to the LAST row's value while every row
  // stays visible in the editor — without a warning, one value is silently lost
  // from what Start/Save/Preview use.
  const keyCounts = new Map<string, number>()
  for (const row of headerRows) {
    if (row.key !== '') keyCounts.set(row.key, (keyCounts.get(row.key) ?? 0) + 1)
  }
  const duplicateHeaderKeys = [...keyCounts.entries()].filter(([, n]) => n > 1).map(([k]) => k)

  return (
    <div data-testid="template-editor">
      <Space wrap style={{ marginBottom: 12 }}>
        <label htmlFor="template-editor-select">Template</label>
        <Select
          id="template-editor-select"
          value={storedCounterpart ? value.id : undefined}
          onChange={selectTemplate}
          options={templates.map((t) => ({ value: t.id, label: t.name }))}
          placeholder="Select a template"
          disabled={disabled}
          style={{ minWidth: 220 }}
        />
        <Button onClick={() => guardDirty(() => onChange(blankTemplate()))} disabled={disabled}>
          New
        </Button>
        <Button onClick={saveWorkingCopy} disabled={disabled}>
          Save
        </Button>
        <Button onClick={exportWorkingCopy} disabled={disabled}>
          Export
        </Button>
      </Space>

      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="template-editor-name">Name</label>
          <Input
            id="template-editor-name"
            value={value.name}
            onChange={(e) => onChange({ ...value, name: e.target.value })}
            disabled={disabled}
            style={{ width: 220 }}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="template-editor-message-type">Message type</label>
          <Input
            id="template-editor-message-type"
            value={value.messageType}
            onChange={(e) => onChange({ ...value, messageType: e.target.value })}
            disabled={disabled}
            style={{ width: 220 }}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="template-editor-priority">Priority</label>
          <InputNumber
            id="template-editor-priority"
            min={1}
            max={10}
            value={value.priority}
            onChange={(v) => {
              if (v !== null) onChange({ ...value, priority: v })
            }}
            disabled={disabled}
            style={{ width: 120 }}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="template-editor-delay">Delay (seconds)</label>
          <InputNumber
            id="template-editor-delay"
            min={0}
            value={value.delaySeconds}
            onChange={(v) => {
              if (v !== null) onChange({ ...value, delaySeconds: v })
            }}
            disabled={disabled}
            style={{ width: 120 }}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label htmlFor="template-editor-group">Group (optional)</label>
          <Input
            id="template-editor-group"
            value={value.messageGroup ?? ''}
            onChange={(e) =>
              onChange({ ...value, messageGroup: e.target.value === '' ? undefined : e.target.value })
            }
            disabled={disabled}
            style={{ width: 180 }}
          />
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
        <label htmlFor="template-editor-payload">Payload (JSON with placeholders)</label>
        <Input.TextArea
          id="template-editor-payload"
          value={value.payloadSchema}
          onChange={(e) => onChange({ ...value, payloadSchema: e.target.value })}
          onBlur={validatePayload}
          rows={15}
          disabled={disabled}
          style={{ fontFamily: 'monospace' }}
        />
        {payloadError && (
          <div data-testid="payload-error">
            <Alert type="error" showIcon message={`Invalid template JSON: ${payloadError}`} />
          </div>
        )}
      </div>

      <div style={{ marginBottom: 12 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          {headerRows.map((row, i) => (
            <Space key={i}>
              <Input
                data-testid={`header-key-${i}`}
                placeholder="Header key"
                value={row.key}
                onChange={(e) =>
                  updateHeaderRows(headerRows.map((r, j) => (j === i ? { ...r, key: e.target.value } : r)))
                }
                disabled={disabled}
                style={{ width: 200 }}
              />
              <Input
                data-testid={`header-value-${i}`}
                placeholder="Header value (placeholders allowed)"
                value={row.value}
                onChange={(e) =>
                  updateHeaderRows(headerRows.map((r, j) => (j === i ? { ...r, value: e.target.value } : r)))
                }
                disabled={disabled}
                style={{ width: 260 }}
              />
              <Button
                data-testid={`header-remove-${i}`}
                icon={<DeleteOutlined />}
                onClick={() => updateHeaderRows(headerRows.filter((_, j) => j !== i))}
                disabled={disabled}
              />
            </Space>
          ))}
          {duplicateHeaderKeys.length > 0 && (
            <div data-testid="duplicate-header-warning">
              <Alert
                type="warning"
                showIcon
                message={`Duplicate header keys: ${duplicateHeaderKeys.join(', ')} — only the last row's value is used.`}
              />
            </div>
          )}
          <Button
            icon={<PlusOutlined />}
            onClick={() => updateHeaderRows([...headerRows, { key: '', value: '' }])}
            disabled={disabled || headerRows.length >= MAX_HEADERS}
          >
            Add header
          </Button>
        </Space>
      </div>

      <Collapse
        items={[
          {
            key: 'placeholders',
            label: 'Placeholder reference',
            children: (
              <table>
                <tbody>
                  {PLACEHOLDERS.map((p) => (
                    <tr key={p.token}>
                      <td style={{ paddingRight: 12 }}>
                        <code>{p.token}</code>
                      </td>
                      <td style={{ paddingRight: 12 }}>{p.scope}</td>
                      <td>{p.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ),
          },
        ]}
      />
    </div>
  )
}
