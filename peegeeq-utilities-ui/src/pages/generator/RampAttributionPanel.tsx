/** Zone E rich saturation attribution for ramp mode (design §19.1 — G.1b). */
import { Alert, Space, Table, Tag, Typography } from 'antd'
import {
  attributionFindings,
  databaseAttribution,
  summarizeRampPhases,
} from '../../engine/rampAttribution'
import type { ProfilePhase } from '../../types/profile'
import type {
  RampPhaseTelemetrySummary,
  RampTelemetryLive,
  RampTelemetryReport,
} from '../../types/rampTelemetry'

const { Text } = Typography
const UNKNOWN = '—'

export interface RampAttributionPanelProps {
  live: RampTelemetryLive | null
  report: RampTelemetryReport | null
  phases: ProfilePhase[]
}

function measured(value: number | undefined, suffix = ''): string {
  return value === undefined ? UNKNOWN : `${Math.round(value * 10) / 10}${suffix}`
}

function reportErrors(report: RampTelemetryReport): string[] {
  const errors = [...report.streamErrors]
  if (!report.database.baseline.ok) errors.push(report.database.baseline.error)
  if (!report.database.final.ok) errors.push(report.database.final.error)
  return [...new Set(errors)]
}

export default function RampAttributionPanel({
  live,
  report,
  phases,
}: RampAttributionPanelProps) {
  if (report === null && live === null) {
    return (
      <Text type="secondary" data-testid="ramp-attribution-empty">
        Saturation telemetry will start with the ramp: pushed queue and system samples during each
        step, plus database snapshots at the boundaries.
      </Text>
    )
  }

  if (report === null && live !== null) {
    const setupSaturation = live.latestSystem?.saturation?.find(
      (entry) => entry.setupId === live.latestQueue?.setupId
    )
    return (
      <Space direction="vertical" style={{ width: '100%' }} data-testid="ramp-attribution-live">
        <Text>
          Collecting pushed telemetry: {live.queueSampleCount} queue sample(s),{' '}
          {live.systemSampleCount} system sample(s).
        </Text>
        <Space wrap>
          <Tag>Pending {measured(live.latestQueue?.pendingMessages)}</Tag>
          <Tag data-testid="ramp-live-event-loop">
            Event-loop max {measured(setupSaturation?.eventLoopLagMaxMs, ' ms')}
          </Tag>
          <Tag>Pool-acquire max {measured(setupSaturation?.poolAcquireWaitMaxMs, ' ms')}</Tag>
        </Space>
        {live.streamErrors.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message="A telemetry stream reported an error"
            description={live.streamErrors[live.streamErrors.length - 1]}
          />
        )}
      </Space>
    )
  }

  const finished = report as RampTelemetryReport
  const summaries = summarizeRampPhases(finished, phases.length)
  const findings = attributionFindings(finished)
  const database = databaseAttribution(finished)
  const errors = reportErrors(finished)
  const rows = summaries.map((summary) => ({
    ...summary,
    key: summary.phaseIndex,
    phase: phases[summary.phaseIndex],
  }))

  const columns = [
    {
      title: 'Step',
      key: 'step',
      render: (row: RampPhaseTelemetrySummary & { phase: ProfilePhase }) => (
        <span data-testid={`ramp-attribution-phase-${row.phaseIndex}`}>
          {row.phase.label} · {row.phase.rate} msg/s
        </span>
      ),
    },
    {
      title: 'Samples',
      key: 'samples',
      render: (row: RampPhaseTelemetrySummary) =>
        `${row.queueSampleCount} queue / ${row.systemSampleCount} system`,
    },
    {
      title: 'Max pending',
      key: 'pending',
      render: (row: RampPhaseTelemetrySummary) => measured(row.maxPendingMessages),
    },
    {
      title: 'Backend rate',
      key: 'rate',
      render: (row: RampPhaseTelemetrySummary) => measured(row.maxMessagesPerSecond, ' msg/s'),
    },
    {
      title: 'Event-loop max',
      key: 'loop',
      render: (row: RampPhaseTelemetrySummary) => measured(row.maxEventLoopLagMs, ' ms'),
    },
    {
      title: 'Pool-acquire max',
      key: 'poolWait',
      render: (row: RampPhaseTelemetrySummary) => measured(row.maxPoolAcquireWaitMs, ' ms'),
    },
    {
      title: 'DB active / pending',
      key: 'pool',
      render: (row: RampPhaseTelemetrySummary) =>
        row.maxDbPoolActive === undefined || row.maxDbPoolPending === undefined
          ? UNKNOWN
          : `${row.maxDbPoolActive} / ${row.maxDbPoolPending}`,
    },
  ]

  return (
    <Space direction="vertical" style={{ width: '100%' }} data-testid="ramp-attribution-report">
      <Table rowKey="key" size="small" pagination={false} columns={columns} dataSource={rows} />

      <Alert
        type={findings.length > 0 ? 'warning' : 'info'}
        showIcon
        data-testid="ramp-attribution-findings"
        message={
          findings.length > 0
            ? `${findings.length} pressure indicator(s) observed`
            : 'No discrete pressure indicator crossed the reporting rules'
        }
        description={
          findings.length > 0 ? (
            <ul style={{ marginBottom: 0 }}>
              {findings.map((finding) => <li key={finding.kind}>{finding.text}</li>)}
            </ul>
          ) : (
            'The per-step measurements above remain available; absence of a flagged indicator is not proof that the target had spare capacity.'
          )
        }
      />

      {database !== null && (
        <Text data-testid="ramp-database-attribution">
          Database window{database.tableName ? ` · ${database.tableName}` : ''}:{' '}
          {database.churn === null
            ? 'queue-table churn unavailable'
            : `${database.churn.insertedTuples} inserts, ${database.churn.updatedTuples} updates, ${database.churn.deletedTuples} deletes, dead-tuple change ${database.churn.deadTupleChange}`}
          ; waiting locks {database.locksWaitingBaseline} → {database.locksWaitingFinal}; NOTIFY
          queue {(database.notifyQueueUsageBaseline * 100).toFixed(2)}% →{' '}
          {(database.notifyQueueUsageFinal * 100).toFixed(2)}%.
        </Text>
      )}

      {errors.length > 0 && (
        <Alert
          type="warning"
          showIcon
          data-testid="ramp-telemetry-errors"
          message="Some attribution telemetry could not be read"
          description={errors.map((error, index) => <div key={index}>{error}</div>)}
        />
      )}

      <Text type="secondary" data-testid="ramp-attribution-scope">
        These signals are correlated evidence, not proof of what caused the knee. Queue counters
        and lifetime latency distributions are not run-scoped; use a dedicated queue when the ramp
        must be isolated. Database figures are baseline-to-final deltas over shared implementation
        tables, and absent measurements are shown as {UNKNOWN}, never as zero.
      </Text>
    </Space>
  )
}
