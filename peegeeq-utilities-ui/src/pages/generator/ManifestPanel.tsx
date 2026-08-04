/**
 * Zone E — Sent manifest (design §19.5 — G.5-send).
 *
 * "id → group · priority · delay" for every message the run ATTEMPTED. The
 * rows are DERIVED on render from (settings, runId, attempted count) via the
 * same buildManifest/assignmentFor the engine applied per message — nothing
 * per-message is stored, so the report cannot drift from what was sent.
 *
 * The manifest states assignments, not delivery: per-id delivery attribution
 * does not exist client-side (errors are batch-level), so a run with errors
 * carries a caveat and ordering is verified downstream in management-ui's
 * Message Browser — exactly the §19.5 contract. Auto-verification needs the
 * Phase T telemetry (G6) and is out of scope here.
 */
import type { HTMLAttributes } from 'react'
import { Alert, Button, Table, Typography } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import { buildManifest } from '../../engine/exerciserPlan'
import { triggerDownload } from '../../services/templateService'
import type { ExerciserSettings, ManifestEntry } from '../../types/exerciser'

const { Text } = Typography

/** Rows shown inline; the download always carries the full manifest. */
const DISPLAY_ROW_CAP = 100

/**
 * The inputs the manifest is derived from — captured by the page when the run
 * starts (settings, the value-list snapshot the engine used) and when it
 * settles (runId, attempted, errors from the summary).
 */
export interface ManifestRun {
  settings: ExerciserSettings
  runId: string
  /** Messages the engine built and handed to publish (RunSummary.totalAttempted). */
  attempted: number
  /** Batch errors from the summary — drives the delivery caveat. */
  errors: number
  /** The value-list snapshot the run used; per-key groups derive from it. */
  valueLists: Record<string, string[]>
}

interface ManifestPanelProps {
  run: ManifestRun | null
}

export default function ManifestPanel({ run }: ManifestPanelProps) {
  if (run === null || run.attempted === 0) {
    // "Did not run" is a different fact from "ran and sent nothing" — but with
    // attempted = 0 there are no assignments to report either way.
    return (
      <Text type="secondary" data-testid="manifest-empty">
        No manifest yet — start an exerciser run to record what it sends.
      </Text>
    )
  }

  const { settings, runId, attempted, errors, valueLists } = run
  const displayCount = Math.min(attempted, DISPLAY_ROW_CAP)
  const rows = buildManifest(settings, runId, displayCount, valueLists)

  function downloadFull(): void {
    const full = buildManifest(settings, runId, attempted, valueLists)
    triggerDownload(
      JSON.stringify({ runId, attempted, errors, entries: full }, null, 2),
      `manifest-${runId}.json`
    )
  }

  const columns = [
    {
      title: 'Message id',
      key: 'id',
      render: (entry: ManifestEntry) => String(entry.messageId).padStart(8, '0'),
    },
    {
      title: 'Group',
      key: 'group',
      render: (entry: ManifestEntry) => entry.messageGroup,
    },
    {
      title: 'Priority',
      key: 'priority',
      render: (entry: ManifestEntry) => `p${entry.priority}`,
    },
    {
      title: 'Delay',
      key: 'delay',
      render: (entry: ManifestEntry) => `d${entry.delaySeconds}s`,
    },
  ]

  return (
    <div data-testid="manifest-panel">
      <div
        style={{
          display: 'flex',
          gap: 16,
          alignItems: 'center',
          flexWrap: 'wrap',
          marginBottom: 8,
        }}
      >
        <Text data-testid="manifest-header">
          {attempted} messages attempted · run {runId}
        </Text>
        <Button
          size="small"
          icon={<DownloadOutlined />}
          data-testid="manifest-download"
          onClick={downloadFull}
        >
          Download manifest
        </Button>
      </div>

      {errors > 0 && (
        <div style={{ marginBottom: 8 }}>
          <Alert
            type="warning"
            showIcon
            data-testid="manifest-errors-caveat"
            message={`${errors} batch error${errors === 1 ? '' : 's'} occurred — the manifest lists attempted assignments; some of these ids were not delivered. See the run summary errors.`}
          />
        </div>
      )}

      <Table
        rowKey="messageId"
        size="small"
        pagination={false}
        columns={columns}
        dataSource={rows}
        onRow={(entry) =>
          // antd types onRow's return as HTMLAttributes, which does not admit
          // data-* keys in an object position (the ProfileResultsPanel note).
          ({
            'data-testid': `manifest-row-${entry.messageId}`,
          }) as HTMLAttributes<HTMLTableRowElement>
        }
      />

      <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
        {attempted > DISPLAY_ROW_CAP && (
          <Text type="secondary" data-testid="manifest-truncation-note">
            Showing the first {DISPLAY_ROW_CAP} of {attempted} messages — the download carries all
            of them.
          </Text>
        )}
        <Text type="secondary" data-testid="manifest-verify-note">
          Verify ordering downstream in management-ui → Message Browser.
        </Text>
      </div>
    </div>
  )
}
