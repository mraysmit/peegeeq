/**
 * Zone E — Profile mode results (design §19.3 — G.3c).
 *
 * Achieved against requested, per phase. REQUESTED is derived here from the
 * phase (`rate × durationSecs`) and never stored beside the achieved figure —
 * that is where the two drift apart.
 *
 * A phase that has not run yet reports PENDING, not "0 sent". "Did not run" and
 * "ran and delivered nothing" are different facts, and a panel that renders
 * both as 0 is the reporting defect this exists to avoid.
 */
import type { HTMLAttributes } from 'react'
import { Table, Tag, Typography } from 'antd'
import type { ProfilePhase, ProfilePhaseResult } from '../../types/profile'

const { Text } = Typography

interface ProfileResultsPanelProps {
  phases: ProfilePhase[]
  results: ProfilePhaseResult[]
  /** Index of the phase currently running, or null when nothing is live. */
  activeIndex: number | null
}

/** Requested message count for a phase — derived, never stored. */
function requestedFor(phase: ProfilePhase): number {
  return phase.rate * phase.durationSecs
}

export default function ProfileResultsPanel({
  phases,
  results,
  activeIndex,
}: ProfileResultsPanelProps) {
  const byPhaseId = new Map(results.map((r) => [r.phaseId, r]))

  const totalRequested = phases.reduce((sum, p) => sum + requestedFor(p), 0)
  const totalSent = results.reduce((sum, r) => sum + r.sent, 0)

  const columns = [
    {
      title: '#',
      key: 'index',
      render: (_: unknown, __: ProfilePhase, index: number) => index + 1,
    },
    {
      title: 'Phase',
      key: 'label',
      render: (phase: ProfilePhase) => phase.label,
    },
    {
      title: 'Requested',
      key: 'requested',
      render: (phase: ProfilePhase) => (
        <span data-testid="requested">{requestedFor(phase)}</span>
      ),
    },
    {
      title: 'Sent',
      key: 'sent',
      render: (phase: ProfilePhase) => {
        const result = byPhaseId.get(phase.id)
        // An em dash, not 0: this phase has not run.
        return <span data-testid="sent">{result ? result.sent : '—'}</span>
      },
    },
    {
      title: 'Errors',
      key: 'errors',
      render: (phase: ProfilePhase) => {
        const result = byPhaseId.get(phase.id)
        return <span data-testid="errors">{result ? result.errors : '—'}</span>
      },
    },
    {
      title: 'Status',
      key: 'status',
      render: (phase: ProfilePhase, _row: ProfilePhase, index: number) => {
        const result = byPhaseId.get(phase.id)
        if (result) {
          return <span data-testid="status">{result.status}</span>
        }
        return (
          <span data-testid="status">{index === activeIndex ? 'running' : 'pending'}</span>
        )
      },
    },
    {
      title: '',
      key: 'shortfall',
      render: (phase: ProfilePhase) => {
        const result = byPhaseId.get(phase.id)
        if (!result || result.sent >= requestedFor(phase)) return null
        return (
          <Tag color="warning" data-testid={`profile-shortfall-${phase.id}`}>
            short by {requestedFor(phase) - result.sent}
          </Tag>
        )
      },
    },
  ]

  return (
    <div data-testid="profile-results-panel">
      <Table
        rowKey="id"
        size="small"
        pagination={false}
        columns={columns}
        dataSource={phases}
        // antd types onRow's return as HTMLAttributes, which does not admit
        // data-* keys in an object position (they are only special-cased in
        // JSX). The attribute is valid at runtime; the cast is the type-level
        // accommodation, not a behaviour change.
        onRow={(phase) =>
          ({ 'data-testid': `profile-result-row-${phase.id}` }) as HTMLAttributes<HTMLTableRowElement>
        }
      />
      <div style={{ marginTop: 8, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <Text data-testid="profile-total-requested">Requested total = {totalRequested}</Text>
        <Text data-testid="profile-total-sent">Sent total = {totalSent}</Text>
      </div>
    </div>
  )
}
