/**
 * Schedule-a-run modal (Scheduled Runs design §8.1 — SCH.4).
 *
 * Captures the FROZEN run configuration handed in by the caller (the generator
 * page's assembled config, or a schedule template for the prefilled path) and
 * creates a ScheduledRun. The config is deep-copied on save — later edits in
 * the caller never change an existing schedule (§6). The missing-value-list
 * check warns but never blocks, matching the Start pre-flight rule (§5.5 of
 * the main design). Times are entered and validated in the browser's local
 * timezone and stored as ISO 8601.
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Input, InputNumber, Modal, Radio, Typography, message } from 'antd'
import { findMissingLists } from '../../engine/templateResolver'
import { computeNextRunAt, useScheduleStore } from '../../stores/scheduleStore'
import { useValueListStore } from '../../stores/valueListStore'
import type { RunConfig } from '../../types/generator'
import type { ScheduleSpec } from '../../types/schedule'

const { Text } = Typography

interface ScheduleRunModalProps {
  open: boolean
  /** The run configuration to freeze into the schedule. */
  config: RunConfig
  onClose: () => void
}

export default function ScheduleRunModal({ open, config, onClose }: ScheduleRunModalProps) {
  const navigate = useNavigate()
  const [name, setName] = useState(`${config.template.name} @ ${config.queueName}`)
  const [mode, setMode] = useState<'once' | 'interval'>('once')
  const [runAtLocal, setRunAtLocal] = useState('')
  const [everyMinutes, setEveryMinutes] = useState(60)
  const [error, setError] = useState<string | null>(null)

  const missingLists = findMissingLists(
    [config.template.payloadSchema, ...Object.values(config.template.headers)].join('\n'),
    useValueListStore.getState().snapshot()
  )

  function save() {
    const trimmedName = name.trim()
    if (trimmedName === '') {
      setError('A schedule name is required.')
      return
    }
    if (runAtLocal === '') {
      setError(mode === 'once' ? 'A run time is required.' : 'A starting time is required.')
      return
    }
    const runAt = new Date(runAtLocal)
    if (Number.isNaN(runAt.getTime())) {
      setError('The time is not valid.')
      return
    }
    if (runAt.getTime() <= Date.now()) {
      setError('The time is in the past.')
      return
    }
    if (mode === 'interval' && (!Number.isInteger(everyMinutes) || everyMinutes < 1)) {
      setError('The repeat interval must be a whole number of minutes, at least 1.')
      return
    }

    const spec: ScheduleSpec =
      mode === 'once'
        ? { kind: 'once', runAt: runAt.toISOString() }
        : { kind: 'interval', firstRunAt: runAt.toISOString(), everyMinutes }

    const now = new Date()
    // Deep copy: the schedule owns its config; later caller edits change nothing.
    const frozenConfig: RunConfig = {
      ...config,
      template: { ...config.template, headers: { ...config.template.headers } },
    }
    useScheduleStore.getState().addSchedule({
      id: crypto.randomUUID(),
      name: trimmedName,
      config: frozenConfig,
      schedule: spec,
      enabled: true,
      nextRunAt: computeNextRunAt(spec, now),
      createdAt: now.toISOString(),
      updatedAt: now.toISOString(),
    })
    message.success(
      <span>
        Schedule "{trimmedName}" created.{' '}
        <a onClick={() => navigate('/generator/schedules')}>View scheduled runs</a>
      </span>
    )
    setError(null)
    onClose()
  }

  return (
    <Modal
      title="Schedule this run"
      open={open}
      onCancel={onClose}
      footer={[
        <Button key="cancel" onClick={onClose}>
          Cancel
        </Button>,
        <Button key="create" type="primary" onClick={save}>
          Create schedule
        </Button>,
      ]}
      width={560}
    >
      <div data-testid="schedule-run-modal">
        <div data-testid="schedule-capture-summary" style={{ marginBottom: 12 }}>
          <Text>
            {config.setupId} / {config.queueName} · {config.rate} msg/s × {config.durationSecs} s ={' '}
            {config.rate * config.durationSecs} messages · template "{config.template.name}"
          </Text>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
          <label htmlFor="schedule-name">Schedule name</label>
          <Input
            id="schedule-name"
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              setError(null)
            }}
          />
        </div>

        <Radio.Group
          value={mode}
          onChange={(e) => {
            setMode(e.target.value as 'once' | 'interval')
            setError(null)
          }}
          style={{ marginBottom: 12 }}
        >
          <Radio value="once">Run once</Radio>
          <Radio value="interval">Repeat every N minutes</Radio>
        </Radio.Group>

        {mode === 'once' ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
            <label htmlFor="schedule-run-at">Run at</label>
            <input
              id="schedule-run-at"
              type="datetime-local"
              value={runAtLocal}
              onChange={(e) => {
                setRunAtLocal(e.target.value)
                setError(null)
              }}
              style={{ width: 240, padding: 4 }}
            />
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 16, marginBottom: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="schedule-first-run-at">Starting at</label>
              <input
                id="schedule-first-run-at"
                type="datetime-local"
                value={runAtLocal}
                onChange={(e) => {
                  setRunAtLocal(e.target.value)
                  setError(null)
                }}
                style={{ width: 240, padding: 4 }}
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="schedule-every-minutes">Every (minutes)</label>
              <InputNumber
                id="schedule-every-minutes"
                min={1}
                value={everyMinutes}
                onChange={(v) => {
                  if (v !== null) setEveryMinutes(v)
                  setError(null)
                }}
                style={{ width: 120 }}
              />
            </div>
          </div>
        )}

        {missingLists.length > 0 && (
          <div data-testid="schedule-missing-lists" style={{ marginBottom: 12 }}>
            <Alert
              type="warning"
              showIcon
              message={`Missing or empty value lists (will resolve to "" at run time): ${missingLists.join(', ')}`}
            />
          </div>
        )}

        {error && (
          <div data-testid="schedule-modal-error" style={{ marginBottom: 12 }}>
            <Alert type="error" showIcon message={error} />
          </div>
        )}

        <div data-testid="schedule-execution-note">
          <Alert
            type="info"
            showIcon
            message="Scheduled runs fire only while this app is open in a browser tab, and are stored in this browser only."
          />
        </div>
      </div>
    </Modal>
  )
}
