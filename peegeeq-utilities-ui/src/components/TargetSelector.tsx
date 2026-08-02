import { useEffect, useRef, useState, useCallback } from 'react'
import { Alert, Button, Select, Spin, Space, Typography } from 'antd'
import { Link, useNavigate } from 'react-router-dom'
import { getSetups } from '../services/setupService'
import { listQueueDetails } from '../services/queueService'
import type { QueueSummary } from '../types/queue'

const { Text } = Typography

export interface TargetSelectorProps {
  onTargetSelected: (setupId: string, queueName: string) => void
  /**
   * Fired whenever no valid setup+queue pair is selected — on mount, while a
   * setup switch is loading, when a queue load fails, and when a setup has no
   * queues. Required: without it the parent keeps the LAST valid pair and
   * Start publishes to a setup the UI no longer shows.
   */
  onTargetCleared: () => void
  /**
   * Target to pre-select on MOUNT instead of the first setup + first queue
   * (scenario Load, G.4). Consumed once: a later manual setup change is the
   * user's, and re-applying it would fight them. Callers that need to seed a
   * different target after mount remount this component with a new `key`.
   *
   * A requested target that no longer exists is NOT silently replaced by the
   * default one — the mismatch is named in an inline warning, because silently
   * retargeting means publishing load at a queue the user did not choose.
   */
  initialTarget?: { setupId: string; queueName: string }
}

export default function TargetSelector({
  onTargetSelected,
  onTargetCleared,
  initialTarget,
}: TargetSelectorProps) {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [setups, setSetups] = useState<string[]>([])
  const [selectedSetup, setSelectedSetup] = useState<string | null>(null)
  const [queues, setQueues] = useState<QueueSummary[]>([])
  const [selectedQueue, setSelectedQueue] = useState<string | null>(null)
  const [queueLoadError, setQueueLoadError] = useState<string | null>(null)
  const [unavailableTarget, setUnavailableTarget] = useState<string | null>(null)

  // Held in a ref, not state: consuming it must not re-run the load callbacks.
  const pendingInitialTarget = useRef(initialTarget ?? null)

  const loadSetups = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const ids = await getSetups()
      setSetups(ids)
      if (ids.length === 0) {
        setSelectedSetup(null)
        return
      }
      const wanted = pendingInitialTarget.current
      if (wanted && !ids.includes(wanted.setupId)) {
        pendingInitialTarget.current = null
        setUnavailableTarget(`${wanted.setupId} / ${wanted.queueName}`)
        setSelectedSetup(ids[0])
        return
      }
      setSelectedSetup(wanted ? wanted.setupId : ids[0])
    } catch (error) {
      // The alert carries the actual cause (HTTP status, network refusal) —
      // a generic line alone hides what went wrong.
      console.error('Failed to load setups:', error)
      setLoadError(
        `Failed to load setups: ${error instanceof Error ? error.message : String(error)}. Check your connection.`
      )
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadSetups()
  }, [loadSetups])

  const loadQueues = useCallback(async (setupId: string) => {
    setQueueLoadError(null)
    try {
      const summaries = await listQueueDetails(setupId)
      setQueues(summaries)
      if (summaries.length === 0) {
        setSelectedQueue(null)
        return
      }
      const wanted = pendingInitialTarget.current
      if (wanted && wanted.setupId === setupId) {
        pendingInitialTarget.current = null
        const match = summaries.find((q) => q.name === wanted.queueName)
        if (match) {
          setSelectedQueue(match.name)
          return
        }
        // The setup exists but the queue does not — name the gap rather than
        // quietly falling through to the first queue.
        setUnavailableTarget(`${wanted.setupId} / ${wanted.queueName}`)
      }
      setSelectedQueue(summaries[0].name)
    } catch (error) {
      // Surface the failure — an unreachable backend must not masquerade as an
      // empty setup (no-error-swallowing rule). The cause is shown, not hidden.
      console.error('Failed to load queues:', error)
      setQueues([])
      setSelectedQueue(null)
      setQueueLoadError(
        `Failed to load queues for this setup: ${error instanceof Error ? error.message : String(error)}. Check your connection.`
      )
    }
  }, [])

  useEffect(() => {
    if (selectedSetup) {
      loadQueues(selectedSetup)
    } else {
      setQueues([])
      setSelectedQueue(null)
      setQueueLoadError(null)
    }
  }, [selectedSetup, loadQueues])

  // Notify the parent on every selection change: a valid pair selects, anything
  // else CLEARS — the parent must never keep a stale pair the UI no longer shows.
  useEffect(() => {
    if (selectedSetup && selectedQueue) {
      onTargetSelected(selectedSetup, selectedQueue)
    } else {
      onTargetCleared()
    }
  }, [selectedSetup, selectedQueue, onTargetSelected, onTargetCleared])

  function handleSetupChange(value: string) {
    // A manual choice answers the warning: the user has now picked the target.
    setUnavailableTarget(null)
    pendingInitialTarget.current = null
    setSelectedSetup(value)
    setSelectedQueue(null)
  }

  function handleQueueChange(value: string) {
    setUnavailableTarget(null)
    setSelectedQueue(value)
  }

  if (loading) {
    return (
      <Spin spinning={true}>
        <div data-testid="loading-state" style={{ padding: 24 }} />
      </Spin>
    )
  }

  if (loadError) {
    return (
      <Alert
        type="error"
        message={loadError}
        showIcon
        action={
          <Button size="small" onClick={loadSetups}>
            Retry
          </Button>
        }
      />
    )
  }

  // Empty state — no setups exist
  if (setups.length === 0) {
    return (
      <Alert
        type="info"
        message="No PeeGeeQ setup connected"
        description="Connect to an existing setup to publish messages to its queues."
        showIcon
        action={
          <Button type="primary" size="small" onClick={() => navigate('/setups/connect')}>
            Connect setup
          </Button>
        }
      />
    )
  }

  // The setup dropdown renders in EVERY post-load state (normal, no-queues,
  // queue-load-error): a queue-less or failing first setup must never strand
  // the user — switching setups is the only way to reach another setup's queues.
  const setupRow = (
    <Space align="center" style={{ width: '100%' }}>
      <label htmlFor="target-setup-select">Setup</label>
      <Select
        id="target-setup-select"
        aria-label="Setup"
        value={selectedSetup ?? undefined}
        onChange={handleSetupChange}
        options={setups.map((s) => ({ value: s, label: s }))}
        style={{ minWidth: 160 }}
      />
    </Space>
  )

  // A requested target (scenario Load) that no longer exists. Shown alongside
  // the dropdowns so the substituted target is never mistaken for the saved one.
  const unavailableAlert = unavailableTarget && (
    <Alert
      type="warning"
      showIcon
      data-testid="target-unavailable"
      message={`Saved target ${unavailableTarget} is not available`}
      description="Select the target to publish to — the selection below is a default, not the saved one."
    />
  )

  // Queue-load failure — distinct from a legitimately empty setup
  if (queueLoadError) {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        {setupRow}
        <Alert
          type="error"
          message={queueLoadError}
          showIcon
          data-testid="queue-load-error"
          action={
            <Button size="small" onClick={() => selectedSetup && loadQueues(selectedSetup)}>
              Retry
            </Button>
          }
        />
      </Space>
    )
  }

  // No-queues state — setup selected but no queues yet
  if (queues.length === 0) {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        {setupRow}
        {unavailableAlert}
        <Alert
          type="info"
          message="No queues found for this setup"
          description={
            <>
              <Text>
                Queues are managed per setup on the{' '}
                <Link to={selectedSetup ? `/setups/${selectedSetup}` : '/setups'}>Setups page</Link>.
                Add at least one queue, then return here to publish messages.
              </Text>
            </>
          }
          showIcon
        />
      </Space>
    )
  }

  // Normal state — setup and queues available
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {setupRow}
      {unavailableAlert}
      <Space align="center" style={{ width: '100%' }}>
        <label htmlFor="target-queue-select">Queue</label>
        <Select
          id="target-queue-select"
          aria-label="Queue"
          value={selectedQueue ?? undefined}
          onChange={handleQueueChange}
          options={queues.map((q) => ({
            value: q.name,
            label: q.implementationType ? `${q.name} (${q.implementationType})` : q.name,
          }))}
          style={{ minWidth: 160 }}
        />
        <Link to={selectedSetup ? `/setups/${selectedSetup}` : '/setups'}>Manage queues →</Link>
      </Space>
    </Space>
  )
}
