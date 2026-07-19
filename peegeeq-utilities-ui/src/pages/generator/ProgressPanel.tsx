/**
 * Zone E — Progress and Results (feature §6.1, §7.2, §11).
 *
 * Reads the generatorStore directly: Zone E is pure display of run state, and
 * the store is the single state owner. Cadences (§6.1, clarified 2026-07-18):
 * Sent / rate / errors update at the engine tick cadence via the store;
 * Elapsed advances on this panel's OWN 500 ms interval derived from
 * runState.startedAt, so it never stalls between ticks.
 *
 * On a terminal state the summary card REPLACES the progress bar and renders
 * from the stored RunSummary alone (final acknowledged counts — no
 * reconciliation against tick counters). The card carries Download results and
 * the New run button — the only exit from terminal states (§7.2): it clears
 * the summary and calls resetRun().
 */
import { useEffect, useState } from 'react'
import { Alert, Button, Card, Descriptions, Progress, Space, Tag, Typography } from 'antd'
import { useGeneratorStore } from '../../stores/generatorStore'

const { Text } = Typography

const ERROR_LIST_LIMIT = 20

const STATUS_COLORS: Record<string, string> = {
  idle: 'default',
  running: 'blue',
  completed: 'green',
  stopped: 'orange',
  error: 'red',
}

export default function ProgressPanel() {
  const runState = useGeneratorStore((s) => s.runState)
  const config = useGeneratorStore((s) => s.config)
  const summary = useGeneratorStore((s) => s.summary)
  const resetRun = useGeneratorStore((s) => s.resetRun)

  // Elapsed display: own 500 ms interval from startedAt while running.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (runState.status !== 'running') return
    const interval = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(interval)
  }, [runState.status])

  const elapsedSecs =
    runState.status === 'running' && runState.startedAt !== null
      ? Math.floor((now - runState.startedAt) / 1000)
      : Math.floor(runState.elapsedMs / 1000)
  const durationSecs = config?.durationSecs ?? 0
  const percent =
    runState.totalToSend > 0 ? Math.round((runState.sent / runState.totalToSend) * 100) : 0

  const statusLabel =
    runState.status === 'error' && runState.autoStopReason
      ? `ERROR (${runState.autoStopReason})`
      : runState.status.toUpperCase()

  const recentErrors = runState.errors.slice(-ERROR_LIST_LIMIT)
  const terminal = summary !== null && runState.status !== 'running' && runState.status !== 'idle'

  function downloadResults() {
    if (!summary) return
    const blob = new Blob([JSON.stringify(summary, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `run-${summary.runId}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div data-testid="progress-panel">
      <div data-testid="run-status" style={{ marginBottom: 12 }}>
        <Tag color={STATUS_COLORS[runState.status]}>{statusLabel}</Tag>
      </div>

      {terminal ? (
        <Card title="Run summary" data-testid="summary-card" style={{ maxWidth: 480 }}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Total sent">{summary.totalSent}</Descriptions.Item>
            <Descriptions.Item label="Target total">{summary.targetTotal}</Descriptions.Item>
            <Descriptions.Item label="Actual average rate">
              {summary.avgRate.toFixed(1)} msg/s
            </Descriptions.Item>
            <Descriptions.Item label="Run duration">
              {(summary.durationMs / 1000).toFixed(1)} s
            </Descriptions.Item>
            <Descriptions.Item label="Total errors">{summary.totalErrors}</Descriptions.Item>
            <Descriptions.Item label="Final status">
              {summary.finalStatus.toUpperCase()}
            </Descriptions.Item>
          </Descriptions>
          <Space style={{ marginTop: 12 }}>
            <Button onClick={downloadResults}>Download results</Button>
            <Button type="primary" onClick={resetRun}>
              New run
            </Button>
          </Space>
        </Card>
      ) : (
        <>
          <div data-testid="run-progress" style={{ maxWidth: 480, marginBottom: 12 }}>
            <Progress percent={percent} />
          </div>
          <Space size="large" style={{ marginBottom: 12 }}>
            <Text data-testid="sent-counter">
              Sent: {runState.sent} / {runState.totalToSend}
            </Text>
            <Text data-testid="elapsed-counter">
              Elapsed: {elapsedSecs}s / {durationSecs}s
            </Text>
            <Text data-testid="rate-counter">
              Rate: {runState.currentRate.toFixed(1)} msg/s
            </Text>
            <Text data-testid="error-counter">Errors: {runState.errors.length}</Text>
          </Space>
        </>
      )}

      {!terminal && recentErrors.length > 0 && (
        <div data-testid="error-list" style={{ maxWidth: 640 }}>
          <Alert
            type="warning"
            showIcon
            message={`Recent errors (last ${recentErrors.length})`}
            description={
              <div style={{ maxHeight: 200, overflow: 'auto' }}>
                {recentErrors.map((error, i) => (
                  <div key={`${error.messageIndex}-${i}`} data-testid={`error-item-${i}`}>
                    <Text type="danger">
                      #{error.messageIndex}
                      {error.httpStatus !== undefined ? ` [${error.httpStatus}]` : ''} — {error.message}
                    </Text>
                  </div>
                ))}
              </div>
            }
          />
        </div>
      )}
    </div>
  )
}
