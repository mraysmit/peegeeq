/**
 * Zone E — Compare mode results (design §19.2 — G.2c).
 *
 * The §19.2 side-by-side card: sent, acknowledged, rate, errors and latency for
 * native against outbox, then the verdict line. Extended with the §4A database
 * churn profile, which is the comparison only the DB layer can give — native
 * (`queue_messages`: INSERT + DELETE + LISTEN/NOTIFY) against outbox (`outbox`:
 * INSERT + UPDATE/DELETE + heavy polling seq_scans).
 *
 * **Every figure is derived here, never stored.** The rate comes from the
 * engine's own summary; requested comes from the shared load; the churn figures
 * are baseline-to-final deltas computed by comparePlan. Nothing is recorded
 * beside the run it describes.
 *
 * **Absence is shown as absence.** A latency distribution the backend did not
 * report renders as "not measured", not as 0 ms — the utilities-ui publishes
 * and does not consume, so a queue nothing consumed produces no delivery
 * latency at all, and a zero there would read as an instant delivery. A
 * telemetry read that FAILED renders its reason, not an empty row.
 */
import { Alert, Table, Tag, Typography } from 'antd'
import {
  CHURN_TABLE,
  churnDeltaFor,
  compareVerdict,
  latencySampleDelta,
} from '../../engine/comparePlan'
import type {
  CompareReport,
  CompareSideName,
  CompareSideProgress,
  CompareSideResult,
  TableChurnDelta,
} from '../../types/compare'

const { Text } = Typography

const SIDES: readonly CompareSideName[] = ['native', 'outbox']

/** Shown when a figure cannot be known, so it is never mistaken for a zero. */
const UNKNOWN = '—'

export interface CompareResultsPanelProps {
  /** Live per-side progress while the comparison runs; null when not live. */
  progress: Record<CompareSideName, CompareSideProgress | null>
  /** The finished comparison; null until both sides have settled. */
  report: CompareReport | null
  /** Messages the shared load asked EACH side for — derived by the page. */
  requested: number
  /**
   * How many sides have reached a terminal state (0–2).
   *
   * Required rather than defaulted: the report is published only once the final
   * database sample accounts for the run, so there is a window of seconds in
   * which BOTH sides have stopped and no report exists yet. Without this the
   * panel would keep saying "both sides are running" through that window. A
   * default would let the page silently stop supplying it and put the false
   * note straight back.
   */
  settledSides: number
}

/**
 * What the panel can truthfully say about a comparison that has no report yet.
 *
 * Three distinct states, because "running" stops being true before the report
 * arrives — first for one side, then for both.
 */
function liveNoteFor(settledSides: number): string {
  if (settledSides >= SIDES.length) {
    return 'Both sides have finished. Waiting for the database statistics to account for the run before the churn figures can be reported.'
  }
  if (settledSides > 0) {
    return 'One side has finished; the other is still running. Latency and database churn are sampled once both have finished.'
  }
  return 'Both sides are running. Latency and database churn are sampled when the run finishes.'
}

interface MetricRow {
  key: string
  label: string
  native: string
  outbox: string
}

function resultFor(report: CompareReport, side: CompareSideName): CompareSideResult {
  return side === 'native' ? report.native : report.outbox
}

function churnFor(report: CompareReport, side: CompareSideName): TableChurnDelta | null {
  const setupId = resultFor(report, side).target.setupId
  const pair = report.telemetry.db[setupId]
  if (pair === undefined) return null
  return churnDeltaFor(pair, CHURN_TABLE[side])
}

/** Every telemetry read that failed, with the reason the service recorded. */
function telemetryFailures(report: CompareReport): string[] {
  const reasons: string[] = []
  for (const side of SIDES) {
    const pair = report.telemetry.stats[side]
    if (!pair.baseline.ok) reasons.push(pair.baseline.error)
    if (!pair.final.ok) reasons.push(pair.final.error)
  }
  for (const pair of Object.values(report.telemetry.db)) {
    if (!pair.baseline.ok) reasons.push(pair.baseline.error)
    if (!pair.final.ok) reasons.push(pair.final.error)
  }
  return reasons
}

export default function CompareResultsPanel({
  progress,
  report,
  requested,
  settledSides,
}: CompareResultsPanelProps) {
  if (report === null) {
    const live = SIDES.some((side) => progress[side] !== null)
    if (!live) {
      return (
        <Text type="secondary" data-testid="compare-results-empty">
          No comparison yet — choose a native and an outbox queue, then start a comparison.
        </Text>
      )
    }
    const liveRows: MetricRow[] = [
      { key: 'requested', label: 'Requested', native: String(requested), outbox: String(requested) },
      ...(['acked', 'errors'] as const).map((key) => ({
        key,
        label: key === 'acked' ? 'Acknowledged' : 'Errors',
        native: liveValue(progress.native, key),
        outbox: liveValue(progress.outbox, key),
      })),
    ]
    return (
      <div data-testid="compare-results-panel">
        <MetricTable rows={liveRows} />
        <Text type="secondary" data-testid="compare-live-note">
          {liveNoteFor(settledSides)}
        </Text>
      </div>
    )
  }

  const rows: MetricRow[] = [
    { key: 'requested', label: 'Requested', native: String(requested), outbox: String(requested) },
    ...metricRow('attempted', 'Sent (attempted)', report, (r) =>
      r.summary.totalAttempted === undefined ? UNKNOWN : String(r.summary.totalAttempted)
    ),
    ...metricRow('acked', 'Acknowledged', report, (r) => String(r.summary.totalSent)),
    ...metricRow('rate', 'Acknowledged rate', report, (r) => `${r.summary.avgRate.toFixed(1)} msg/s`),
    ...metricRow('errors', 'Batch errors', report, (r) => String(r.summary.totalErrors)),
    ...metricRow('status', 'Final status', report, (r) => r.summary.finalStatus.toUpperCase()),
    ...latencyRow('deliveryP95', 'Delivery latency p95', report, 'deliveryLatency'),
    ...latencyRow('processingP95', 'Processing time p95', report, 'processingTime'),
    ...sampleRow('deliverySamples', 'Delivery samples this run', report, 'deliveryLatency'),
    ...churnRow('churnInserted', 'Rows inserted', report, (d) => d.insertedTuples),
    ...churnRow('churnDeleted', 'Rows deleted', report, (d) => d.deletedTuples),
    ...churnRow('churnUpdated', 'Rows updated', report, (d) => d.updatedTuples),
    ...churnRow('churnDeadTuples', 'Dead-tuple change', report, (d) => d.deadTupleChange),
    ...churnRow('churnSeqScans', 'Sequential scans', report, (d) => d.seqScans),
  ]

  const failures = telemetryFailures(report)

  return (
    <div data-testid="compare-results-panel">
      <MetricTable rows={rows} />

      <div style={{ marginTop: 12 }}>
        <Alert type="info" showIcon data-testid="compare-verdict" message={compareVerdict(report)} />
      </div>

      {SIDES.map((side) => {
        const result = resultFor(report, side)
        return result.errorReason === undefined ? null : (
          <div key={side} style={{ marginTop: 8 }}>
            <Alert
              type="error"
              showIcon
              data-testid={`compare-error-${side}`}
              message={`The ${side} run ended in error: ${result.errorReason}`}
            />
          </div>
        )
      })}

      {failures.length > 0 && (
        <div style={{ marginTop: 8 }}>
          <Alert
            type="warning"
            showIcon
            data-testid="compare-telemetry-errors"
            message="Some telemetry could not be read"
            description={
              <div>
                {failures.map((reason, index) => (
                  <div key={index} data-testid={`compare-telemetry-error-${index}`}>
                    <Text type="danger">{reason}</Text>
                  </div>
                ))}
              </div>
            }
          />
        </div>
      )}

      <div style={{ marginTop: 8 }}>
        <Text type="secondary" data-testid="compare-scope-note">
          Latency percentiles cover everything this backend instance has measured for the queue
          since it started, not only this run — per-run scoping is not available. The samples row
          shows how much of the distribution this run contributed. Database figures are the change
          over the run window.
        </Text>
      </div>
    </div>
  )
}

/** Live counters carry only acknowledged sends and error counts. */
function liveValue(progress: CompareSideProgress | null, key: 'acked' | 'errors'): string {
  if (progress === null) return UNKNOWN
  return String(key === 'acked' ? progress.sent : progress.errors)
}

function metricRow(
  key: string,
  label: string,
  report: CompareReport,
  render: (result: CompareSideResult) => string
): MetricRow[] {
  return [
    {
      key,
      label,
      native: render(report.native),
      outbox: render(report.outbox),
    },
  ]
}

function latencyRow(
  key: string,
  label: string,
  report: CompareReport,
  which: 'deliveryLatency' | 'processingTime'
): MetricRow[] {
  const read = (side: CompareSideName): string => {
    const pair = report.telemetry.stats[side]
    if (!pair.final.ok) return 'read failed'
    const distribution = pair.final.snapshot[which]
    // "not measured" rather than 0 ms: nothing consumed the queue, so no
    // enqueue-to-claim time exists to report.
    return distribution === undefined ? 'not measured' : `${distribution.p95Ms.toFixed(1)} ms`
  }
  return [{ key, label, native: read('native'), outbox: read('outbox') }]
}

function sampleRow(
  key: string,
  label: string,
  report: CompareReport,
  which: 'deliveryLatency' | 'processingTime'
): MetricRow[] {
  const read = (side: CompareSideName): string => {
    const delta = latencySampleDelta(report.telemetry.stats[side], which)
    return delta === null ? UNKNOWN : String(delta)
  }
  return [{ key, label, native: read('native'), outbox: read('outbox') }]
}

function churnRow(
  key: string,
  label: string,
  report: CompareReport,
  pick: (delta: TableChurnDelta) => number
): MetricRow[] {
  const read = (side: CompareSideName): string => {
    const delta = churnFor(report, side)
    return delta === null ? UNKNOWN : String(pick(delta))
  }
  return [{ key, label, native: read('native'), outbox: read('outbox') }]
}

function MetricTable({ rows }: { rows: MetricRow[] }) {
  const columns = [
    { title: '', key: 'label', render: (row: MetricRow) => row.label },
    {
      title: (
        <span>
          native <Tag color="blue">queue_messages</Tag>
        </span>
      ),
      key: 'native',
      render: (row: MetricRow) => <span data-testid={`compare-native-${row.key}`}>{row.native}</span>,
    },
    {
      title: (
        <span>
          outbox <Tag color="purple">outbox</Tag>
        </span>
      ),
      key: 'outbox',
      render: (row: MetricRow) => <span data-testid={`compare-outbox-${row.key}`}>{row.outbox}</span>,
    },
  ]
  return <Table rowKey="key" size="small" pagination={false} columns={columns} dataSource={rows} />
}
