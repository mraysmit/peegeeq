/**
 * Zone E — Emitted ids (design §19.6 — G.6).
 *
 * The correlation ids a trace-seed run emitted, DERIVED on render from
 * (settings, runId, attempted, rate, maxBatchSize) via the same
 * buildTraceReport/traceFor derivation the engine's per-message ids came from
 * — nothing per-message is stored, so the report cannot drift from the run.
 *
 * Chains are an ID SCHEME in this report: `causationId` is a bi-temporal
 * event-store attribute, and the queue messages carry only their own
 * correlationId. The ids here are ready for downstream use in management-ui's
 * CausationTree / Events.
 */
import type { HTMLAttributes } from 'react'
import { Alert, Button, Table, Typography, message } from 'antd'
import { CopyOutlined, DownloadOutlined } from '@ant-design/icons'
import { buildTraceReport } from '../../engine/tracePlan'
import { triggerDownload } from '../../services/templateService'
import type { TraceReportEntry, TraceSettings } from '../../types/trace'

const { Text } = Typography

/** Entries shown inline; Copy and Download always carry the full report. */
const DISPLAY_ENTRY_CAP = 100

/**
 * The inputs the report is derived from — captured by the page when the run
 * starts (settings, rate, maxBatchSize) and when it settles (runId,
 * attempted, errors from the summary).
 */
export interface TraceRun {
  settings: TraceSettings
  runId: string
  /** Messages the engine built and handed to publish (RunSummary.totalAttempted). */
  attempted: number
  /** Batch errors from the summary — drives the delivery caveat. */
  errors: number
  /** The run's rate and batch size; per-batch grouping derives from them. */
  rate: number
  maxBatchSize: number
}

interface TraceSeedPanelProps {
  run: TraceRun | null
}

export default function TraceSeedPanel({ run }: TraceSeedPanelProps) {
  if (run === null || run.attempted === 0) {
    return (
      <Text type="secondary" data-testid="trace-empty">
        No ids yet — start a trace-seed run to mint correlation ids.
      </Text>
    )
  }

  const { settings, runId, attempted, errors, rate, maxBatchSize } = run
  const report = buildTraceReport(settings, runId, attempted, rate, maxBatchSize)
  const rows = report.entries.slice(0, DISPLAY_ENTRY_CAP)

  function copyIds(): void {
    const ids = report.entries.map((e) => e.correlationId).join('\n')
    if (!navigator.clipboard) {
      message.error('Clipboard is not available in this browser context — use Download instead.')
      return
    }
    navigator.clipboard
      .writeText(ids)
      .then(() => message.success(`Copied ${report.entries.length} correlation ids.`))
      .catch((error: unknown) =>
        message.error(
          `Copy failed: ${error instanceof Error ? error.message : String(error)}`
        )
      )
  }

  function downloadReport(): void {
    triggerDownload(JSON.stringify(report, null, 2), `trace-ids-${runId}.json`)
  }

  const columns = [
    {
      title: 'Correlation id',
      key: 'id',
      render: (entry: TraceReportEntry) => entry.correlationId,
    },
    {
      title: 'Role',
      key: 'role',
      render: (entry: TraceReportEntry) => entry.role,
    },
    {
      title: 'Chain root',
      key: 'parent',
      render: (entry: TraceReportEntry) => entry.parentCorrelationId ?? '—',
    },
    {
      title: 'First message',
      key: 'first',
      render: (entry: TraceReportEntry) => String(entry.firstMessageId).padStart(8, '0'),
    },
    {
      title: 'Messages',
      key: 'count',
      render: (entry: TraceReportEntry) => entry.messageCount,
    },
  ]

  return (
    <div data-testid="trace-panel">
      <div
        style={{
          display: 'flex',
          gap: 16,
          alignItems: 'center',
          flexWrap: 'wrap',
          marginBottom: 8,
        }}
      >
        <Text data-testid="trace-header">run {runId}</Text>
        <Button size="small" icon={<CopyOutlined />} data-testid="trace-copy" onClick={copyIds}>
          Copy ids
        </Button>
        <Button
          size="small"
          icon={<DownloadOutlined />}
          data-testid="trace-download"
          onClick={downloadReport}
        >
          Download
        </Button>
      </div>

      {errors > 0 && (
        <div style={{ marginBottom: 8 }}>
          <Alert
            type="warning"
            showIcon
            data-testid="trace-errors-caveat"
            message={`${errors} batch error${errors === 1 ? '' : 's'} occurred — the ids cover attempted messages; some may not have been delivered. See the run summary errors.`}
          />
        </div>
      )}

      <Table
        rowKey="firstMessageId"
        size="small"
        pagination={false}
        columns={columns}
        dataSource={rows}
        onRow={(_entry, index) =>
          // antd types onRow's return as HTMLAttributes, which does not admit
          // data-* keys in an object position (the ProfileResultsPanel note).
          ({
            'data-testid': `trace-row-${(index ?? 0) + 1}`,
          }) as HTMLAttributes<HTMLTableRowElement>
        }
      />

      <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <Text data-testid="trace-totals">
          {report.totalMessages} messages under {report.entries.length} correlation ids
          {/* Without chains, chainCount is just the id count — stating it as
              "causation chains" would claim a structure that was not seeded. */}
          {settings.causation.enabled
            ? ` / ${report.chainCount} causation chain${report.chainCount === 1 ? '' : 's'}`
            : ''}
        </Text>
        {report.entries.length > DISPLAY_ENTRY_CAP && (
          <Text type="secondary" data-testid="trace-truncation-note">
            Showing the first {DISPLAY_ENTRY_CAP} of {report.entries.length} ids — Copy and
            Download carry all of them.
          </Text>
        )}
        <Text type="secondary" data-testid="trace-verify-note">
          Use the ids downstream in management-ui → CausationTree / Events.
        </Text>
      </div>
    </div>
  )
}
