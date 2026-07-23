/**
 * Scheduled Runs screen (Scheduled Runs design §8.2 — SCH.5).
 *
 * Three tabs over the real scheduleStore:
 * - Schedules: the schedule table with enable/disable, run-now (through the
 *   shared runStarter; refused with a message while a run is active; never
 *   consumes the scheduled slot), edit-timing, save-as-template, delete, and
 *   Export all (R11).
 * - Run history: every recorded firing outcome, filterable by result and by
 *   schedule name; summary download on fired entries; save-as-template and
 *   re-schedule (the schedule modal prefilled from the FROZEN record config).
 * - Templates: schedule-from (prefilled modal), run-now (recorded in history
 *   under the template name), delete.
 *
 * The outcome column derives from the run history (latestOutcomeFor) — the
 * schedule itself carries scheduling state only.
 */
import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import { startGeneratorRun } from '../../engine/runStarter'
import { fireTimeMissingListsNote, outcomeFromRun } from '../../engine/schedulerRuntime'
import { computeNextRunAt, useScheduleStore } from '../../stores/scheduleStore'
import { useValueListStore } from '../../stores/valueListStore'
import { exportAllSchedules, importSchedulesFromFile } from '../../services/scheduleService'
import ScheduleRunModal from '../generator/ScheduleRunModal'
import ImportFileDialog from '../../components/ImportFileDialog'
import type { RunConfig, RunSummary } from '../../types/generator'
import type {
  ScheduledRun,
  ScheduleOutcome,
  ScheduleRunRecord,
  ScheduleSpec,
  ScheduleTemplate,
} from '../../types/schedule'

dayjs.extend(relativeTime)

const { Title, Text } = Typography

const RESULT_COLORS: Record<ScheduleOutcome['result'], string> = {
  completed: 'green',
  stopped: 'orange',
  error: 'red',
  skipped: 'orange',
  missed: 'default',
}

function describeSpec(spec: ScheduleSpec): string {
  return spec.kind === 'once'
    ? `Once at ${dayjs(spec.runAt).format('YYYY-MM-DD HH:mm')}`
    : `Every ${spec.everyMinutes} min from ${dayjs(spec.firstRunAt).format('YYYY-MM-DD HH:mm')}`
}

function describeRun(config: RunConfig): string {
  return `${config.rate} msg/s × ${config.durationSecs} s = ${config.rate * config.durationSecs} · "${config.template.name}"`
}

function downloadSummary(record: ScheduleRunRecord): void {
  const blob = new Blob([JSON.stringify(record.summary, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `run-${record.id}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

/** datetime-local value for an ISO string, or empty. */
function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

interface TimingDraft {
  scheduleId: string
  mode: 'once' | 'interval'
  runAtLocal: string
  everyMinutes: number
  error: string | null
}

interface TemplateDraft {
  name: string
  config: RunConfig
  error?: string
}

export default function ScheduledRunsPage() {
  const schedules = useScheduleStore((s) => s.schedules)
  const history = useScheduleStore((s) => s.history)
  const templates = useScheduleStore((s) => s.templates)
  const loadFromStorage = useScheduleStore((s) => s.loadFromStorage)

  const [resultFilter, setResultFilter] = useState<'all' | ScheduleOutcome['result']>('all')
  const [nameSearch, setNameSearch] = useState('')
  const [timingDraft, setTimingDraft] = useState<TimingDraft | null>(null)
  const [templateDraft, setTemplateDraft] = useState<TemplateDraft | null>(null)
  const [scheduleFromConfig, setScheduleFromConfig] = useState<RunConfig | null>(null)
  const [importOpen, setImportOpen] = useState(false)

  useEffect(() => {
    loadFromStorage()
    // Run-now snapshots the value lists exactly like a manual Start.
    useValueListStore.getState().loadFromStorage()
  }, [loadFromStorage])

  /** Shared run-now: fires through the runStarter, records via `record`. */
  function runNow(config: RunConfig, record: (outcome: ScheduleOutcome, summary: RunSummary) => void): void {
    const note = fireTimeMissingListsNote(config)
    const handle = startGeneratorRun(config, {
      onTerminal: (summary, status, reason) => {
        const detail = [note, reason].filter(Boolean).join('; ') || undefined
        record(outcomeFromRun(status as 'completed' | 'stopped' | 'error', summary, detail), summary)
      },
    })
    if (handle === null) {
      message.error('Cannot run now — another run is active.')
      return
    }
    // The handle is deliberately not kept: stopping goes through the global
    // stopActiveRun path (the Stop button on the Message Generator screen).
    message.success('Run started — stop and progress are on the Message Generator screen.')
  }

  function saveTiming(): void {
    if (!timingDraft) return
    const schedule = schedules.find((s) => s.id === timingDraft.scheduleId)
    if (!schedule) {
      // A silent return left the modal open with a dead Save button.
      message.error('The schedule no longer exists — it may have been deleted in another tab.')
      setTimingDraft(null)
      return
    }
    if (timingDraft.runAtLocal === '') {
      setTimingDraft({ ...timingDraft, error: 'A time is required.' })
      return
    }
    const runAt = new Date(timingDraft.runAtLocal)
    if (Number.isNaN(runAt.getTime()) || runAt.getTime() <= Date.now()) {
      setTimingDraft({ ...timingDraft, error: 'The time must be in the future.' })
      return
    }
    const spec: ScheduleSpec =
      timingDraft.mode === 'once'
        ? { kind: 'once', runAt: runAt.toISOString() }
        : { kind: 'interval', firstRunAt: runAt.toISOString(), everyMinutes: timingDraft.everyMinutes }
    const nextRunAt = computeNextRunAt(spec, new Date())
    useScheduleStore.getState().updateSchedule({
      ...schedule,
      schedule: spec,
      nextRunAt,
      // New timing revives a consumed one-shot; a null next slot cannot happen
      // for a validated future time.
      enabled: nextRunAt !== null,
    })
    setTimingDraft(null)
  }

  /** D7 later phase: import a schedules.json file (the R11 export format). */
  async function handleImport(file: File): Promise<void> {
    const { schedules: valid, errors } = await importSchedulesFromFile(file)
    for (const error of errors) {
      message.error(`Import rejected — ${error}`)
    }
    if (valid.length === 0) return
    const { added, skipped } = useScheduleStore.getState().importSchedules(valid)
    if (skipped.length > 0) {
      message.warning(`Skipped ${skipped.length} schedule(s) with existing ids (no overwrite): ${skipped.join(', ')}`)
    }
    if (added > 0) {
      message.success(`Imported ${added} schedule(s). Past one-shots arrive consumed; past interval slots are not replayed.`)
    }
  }

  function saveTemplateDraft(): void {
    if (!templateDraft) return
    const name = templateDraft.name.trim()
    if (name === '') {
      // A silent return made the Save button appear dead.
      setTemplateDraft({ ...templateDraft, error: 'A template name is required.' })
      return
    }
    useScheduleStore.getState().saveAsTemplate(name, templateDraft.config)
    message.success(`Template "${name}" saved.`)
    setTemplateDraft(null)
  }

  const filteredHistory = history.filter(
    (r) =>
      (resultFilter === 'all' || r.outcome.result === resultFilter) &&
      r.scheduleName.toLowerCase().includes(nameSearch.trim().toLowerCase())
  )

  const scheduleColumns = [
    { title: 'Name', dataIndex: 'name', key: 'name' },
    {
      title: 'Target',
      key: 'target',
      render: (s: ScheduledRun) => `${s.config.setupId} / ${s.config.queueName}`,
    },
    { title: 'Run', key: 'run', render: (s: ScheduledRun) => describeRun(s.config) },
    { title: 'Schedule', key: 'spec', render: (s: ScheduledRun) => describeSpec(s.schedule) },
    {
      title: 'Next run',
      key: 'next',
      render: (s: ScheduledRun) =>
        s.nextRunAt === null || !s.enabled
          ? '—'
          : `${dayjs(s.nextRunAt).format('YYYY-MM-DD HH:mm')} (${dayjs(s.nextRunAt).fromNow()})`,
    },
    {
      title: 'Enabled',
      key: 'enabled',
      render: (s: ScheduledRun) => (
        <Switch
          checked={s.enabled}
          data-testid={`schedule-enabled-${s.id}`}
          onChange={(checked) => useScheduleStore.getState().setEnabled(s.id, checked)}
        />
      ),
    },
    {
      title: 'Last outcome',
      key: 'outcome',
      render: (s: ScheduledRun) => {
        const latest = useScheduleStore.getState().latestOutcomeFor(s.id)
        return (
          <span data-testid={`schedule-outcome-${s.id}`}>
            {latest === null ? (
              '—'
            ) : (
              <>
                <Tag color={RESULT_COLORS[latest.outcome.result]}>
                  {latest.outcome.result.toUpperCase()}
                </Tag>
                {latest.outcome.totalSent} sent · {dayjs(latest.outcome.at).fromNow()}
              </>
            )}
          </span>
        )
      },
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (s: ScheduledRun) => (
        <Space wrap>
          <Button
            size="small"
            data-testid={`schedule-run-now-${s.id}`}
            onClick={() =>
              runNow(s.config, (outcome, summary) =>
                // No advance argument: run-now never consumes the scheduled slot.
                useScheduleStore.getState().recordOutcome(s.id, outcome, summary)
              )
            }
          >
            Run now
          </Button>
          <Button
            size="small"
            data-testid={`schedule-edit-timing-${s.id}`}
            onClick={() =>
              setTimingDraft({
                scheduleId: s.id,
                mode: s.schedule.kind,
                runAtLocal: toLocalInput(
                  s.schedule.kind === 'once' ? s.schedule.runAt : s.schedule.firstRunAt
                ),
                everyMinutes: s.schedule.kind === 'interval' ? s.schedule.everyMinutes : 60,
                error: null,
              })
            }
          >
            Edit timing
          </Button>
          <Button
            size="small"
            data-testid={`schedule-save-template-${s.id}`}
            onClick={() => setTemplateDraft({ name: `${s.name} template`, config: s.config })}
          >
            Save as template
          </Button>
          <Button
            size="small"
            danger
            data-testid={`schedule-delete-${s.id}`}
            onClick={() =>
              Modal.confirm({
                title: `Delete schedule "${s.name}"?`,
                content: 'The run history is kept.',
                okText: 'Delete',
                okType: 'danger',
                onOk: () => useScheduleStore.getState().removeSchedule(s.id),
              })
            }
          >
            Delete
          </Button>
        </Space>
      ),
    },
  ]

  const historyColumns = [
    {
      title: 'Time',
      key: 'time',
      render: (r: ScheduleRunRecord) =>
        `${dayjs(r.outcome.at).format('YYYY-MM-DD HH:mm:ss')} (${dayjs(r.outcome.at).fromNow()})`,
    },
    { title: 'Schedule', dataIndex: 'scheduleName', key: 'name' },
    {
      title: 'Target',
      key: 'target',
      render: (r: ScheduleRunRecord) => `${r.target.setupId} / ${r.target.queueName}`,
    },
    {
      title: 'Result',
      key: 'result',
      render: (r: ScheduleRunRecord) => (
        <Tag color={RESULT_COLORS[r.outcome.result]}>{r.outcome.result.toUpperCase()}</Tag>
      ),
    },
    {
      title: 'Sent / errors',
      key: 'counts',
      render: (r: ScheduleRunRecord) => `${r.outcome.totalSent} / ${r.outcome.totalErrors}`,
    },
    { title: 'Detail', key: 'detail', render: (r: ScheduleRunRecord) => r.outcome.detail ?? '' },
    {
      title: 'Actions',
      key: 'actions',
      render: (r: ScheduleRunRecord) => (
        <Space wrap>
          {r.summary !== null && (
            <Button size="small" onClick={() => downloadSummary(r)}>
              Download
            </Button>
          )}
          <Button
            size="small"
            onClick={() => setTemplateDraft({ name: `${r.scheduleName} template`, config: r.config })}
          >
            Save as template
          </Button>
          <Button size="small" onClick={() => setScheduleFromConfig(r.config)}>
            Re-schedule
          </Button>
        </Space>
      ),
    },
  ]

  const templateColumns = [
    { title: 'Name', dataIndex: 'name', key: 'name' },
    {
      title: 'Target',
      key: 'target',
      render: (t: ScheduleTemplate) => `${t.config.setupId} / ${t.config.queueName}`,
    },
    { title: 'Run', key: 'run', render: (t: ScheduleTemplate) => describeRun(t.config) },
    {
      title: 'Updated',
      key: 'updated',
      render: (t: ScheduleTemplate) => dayjs(t.updatedAt).fromNow(),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (t: ScheduleTemplate) => (
        <Space wrap>
          <Button size="small" onClick={() => setScheduleFromConfig(t.config)}>
            Schedule…
          </Button>
          <Button
            size="small"
            data-testid={`template-run-now-${t.id}`}
            onClick={() =>
              runNow(t.config, (outcome, summary) =>
                useScheduleStore.getState().appendHistoryRecord({
                  scheduleId: `template:${t.id}`,
                  scheduleName: t.name,
                  target: { setupId: t.config.setupId, queueName: t.config.queueName },
                  outcome,
                  summary,
                  config: t.config,
                })
              )
            }
          >
            Run now
          </Button>
          <Button
            size="small"
            danger
            data-testid={`template-delete-${t.id}`}
            onClick={() =>
              Modal.confirm({
                title: `Delete template "${t.name}"?`,
                okText: 'Delete',
                okType: 'danger',
                onOk: () => useScheduleStore.getState().removeTemplate(t.id),
              })
            }
          >
            Delete
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="scheduled-runs-page">
      <Title level={3}>Scheduled Runs</Title>
      <div data-testid="schedules-page-banner" style={{ marginBottom: 12 }}>
        <Alert
          type="info"
          showIcon
          message="Scheduled runs fire only while this app is open in a browser tab, and are stored in this browser only."
        />
      </div>

      <Tabs
        items={[
          {
            key: 'schedules',
            label: 'Schedules',
            children: (
              <>
                <Space style={{ marginBottom: 12 }}>
                  <Button
                    onClick={() => exportAllSchedules(schedules)}
                    disabled={schedules.length === 0}
                  >
                    Export all
                  </Button>
                  <Button onClick={() => setImportOpen(true)}>Import</Button>
                </Space>
                <ImportFileDialog
                  open={importOpen}
                  title="Import schedules"
                  hint={
                    <>
                      A <code>schedules.json</code> export: one schedule or the full array.
                      Entries are validated individually and existing IDs are skipped; past
                      one-shots arrive consumed and past interval slots are not replayed — an
                      imported backlog never fires.
                    </>
                  }
                  inputTestId="schedule-import-input"
                  onFile={(file) => {
                    setImportOpen(false)
                    // Never fire-and-forget: anything escaping handleImport surfaces.
                    handleImport(file).catch((error: unknown) =>
                      message.error(`Import failed: ${error instanceof Error ? error.message : String(error)}`)
                    )
                  }}
                  onClose={() => setImportOpen(false)}
                />
                {schedules.length === 0 && (
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    data-testid="schedules-empty"
                    message="No schedules yet"
                    description='Create one from the Message Generator screen with the "Schedule…" button, or from a template on the Templates tab.'
                  />
                )}
                <Table
                  rowKey="id"
                  columns={scheduleColumns}
                  dataSource={schedules}
                  pagination={{ pageSize: 10 }}
                  onRow={(record) => ({ 'data-testid': `schedule-row-${record.name}` }) as never}
                />
              </>
            ),
          },
          {
            key: 'history',
            label: 'Run history',
            children: (
              <>
                <Space style={{ marginBottom: 12 }} wrap>
                  <span data-testid="history-result-filter">
                    <Select
                      value={resultFilter}
                      onChange={setResultFilter}
                      style={{ width: 160 }}
                      options={[
                        { value: 'all', label: 'All results' },
                        { value: 'completed', label: 'Completed' },
                        { value: 'stopped', label: 'Stopped' },
                        { value: 'error', label: 'Error' },
                        { value: 'skipped', label: 'Skipped' },
                        { value: 'missed', label: 'Missed' },
                      ]}
                    />
                  </span>
                  <Input
                    placeholder="Search by schedule name"
                    value={nameSearch}
                    onChange={(e) => setNameSearch(e.target.value)}
                    style={{ width: 240 }}
                    allowClear
                  />
                </Space>
                <Table
                  rowKey="id"
                  columns={historyColumns}
                  dataSource={filteredHistory}
                  pagination={{ pageSize: 20 }}
                  onRow={(_record, index) => ({ 'data-testid': `history-row-${index}` }) as never}
                />
                <Text type="secondary" data-testid="history-bound-caption">
                  The history keeps the most recent 200 entries; older entries are dropped.
                </Text>
              </>
            ),
          },
          {
            key: 'templates',
            label: 'Templates',
            children: (
              <>
                {templates.length === 0 && (
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    data-testid="templates-empty"
                    message="No templates yet"
                    description='Use "Save as template" on a schedule or a history entry.'
                  />
                )}
                <Table
                  rowKey="id"
                  columns={templateColumns}
                  dataSource={templates}
                  pagination={{ pageSize: 10 }}
                  onRow={(record) => ({ 'data-testid': `template-row-${record.id}` }) as never}
                />
              </>
            ),
          },
        ]}
      />

      {/* Edit timing */}
      <Modal
        title="Edit timing"
        open={timingDraft !== null}
        onCancel={() => setTimingDraft(null)}
        footer={[
          <Button key="cancel" onClick={() => setTimingDraft(null)}>
            Cancel
          </Button>,
          <Button key="save" type="primary" onClick={saveTiming}>
            Save timing
          </Button>,
        ]}
      >
        {timingDraft && (
          <div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
              <label htmlFor="edit-timing-run-at">Run at</label>
              <input
                id="edit-timing-run-at"
                type="datetime-local"
                value={timingDraft.runAtLocal}
                onChange={(e) =>
                  setTimingDraft({ ...timingDraft, runAtLocal: e.target.value, error: null })
                }
                style={{ width: 240, padding: 4 }}
              />
            </div>
            {timingDraft.mode === 'interval' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
                <label htmlFor="edit-timing-every">Every (minutes)</label>
                <Input
                  id="edit-timing-every"
                  type="number"
                  min={1}
                  value={timingDraft.everyMinutes}
                  onChange={(e) =>
                    setTimingDraft({
                      ...timingDraft,
                      everyMinutes: Math.max(1, Math.floor(Number(e.target.value) || 1)),
                      error: null,
                    })
                  }
                  style={{ width: 120 }}
                />
              </div>
            )}
            {timingDraft.error && <Alert type="error" showIcon message={timingDraft.error} />}
          </div>
        )}
      </Modal>

      {/* Save as template */}
      <Modal
        title="Save as template"
        open={templateDraft !== null}
        onCancel={() => setTemplateDraft(null)}
        footer={[
          <Button key="cancel" onClick={() => setTemplateDraft(null)}>
            Cancel
          </Button>,
          <Button key="save" type="primary" onClick={saveTemplateDraft}>
            Save template
          </Button>,
        ]}
      >
        {templateDraft && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label htmlFor="template-draft-name">Template name</label>
            <Input
              id="template-draft-name"
              value={templateDraft.name}
              onChange={(e) => setTemplateDraft({ ...templateDraft, name: e.target.value, error: undefined })}
            />
            {templateDraft.error && <Alert type="error" showIcon message={templateDraft.error} />}
          </div>
        )}
      </Modal>

      {/* Schedule from a frozen config (template / history re-schedule) */}
      {scheduleFromConfig && (
        <ScheduleRunModal
          open={true}
          config={scheduleFromConfig}
          onClose={() => setScheduleFromConfig(null)}
        />
      )}
    </div>
  )
}
