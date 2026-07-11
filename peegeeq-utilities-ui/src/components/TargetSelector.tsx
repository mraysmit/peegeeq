import { useEffect, useState, useCallback } from 'react'
import { Alert, Button, Select, Spin, Space, Typography } from 'antd'
import { Link, useNavigate } from 'react-router-dom'
import { getSetups } from '../services/setupService'
import { listQueueDetails } from '../services/queueService'
import type { QueueSummary } from '../types/queue'

const { Text } = Typography

export interface TargetSelectorProps {
  onTargetSelected: (setupId: string, queueName: string) => void
}

export default function TargetSelector({ onTargetSelected }: TargetSelectorProps) {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [setups, setSetups] = useState<string[]>([])
  const [selectedSetup, setSelectedSetup] = useState<string | null>(null)
  const [queues, setQueues] = useState<QueueSummary[]>([])
  const [selectedQueue, setSelectedQueue] = useState<string | null>(null)
  const [queueLoadError, setQueueLoadError] = useState<string | null>(null)

  const loadSetups = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const ids = await getSetups()
      setSetups(ids)
      if (ids.length > 0) {
        setSelectedSetup(ids[0])
      } else {
        setSelectedSetup(null)
      }
    } catch {
      setLoadError('Failed to load setups. Please check your connection.')
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
      if (summaries.length > 0) {
        setSelectedQueue(summaries[0].name)
      } else {
        setSelectedQueue(null)
      }
    } catch {
      // Surface the failure — an unreachable backend must not masquerade as an
      // empty setup (no-error-swallowing rule).
      setQueues([])
      setSelectedQueue(null)
      setQueueLoadError('Failed to load queues for this setup. Please check your connection.')
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

  // Notify parent whenever both setup and queue are selected
  useEffect(() => {
    if (selectedSetup && selectedQueue) {
      onTargetSelected(selectedSetup, selectedQueue)
    }
  }, [selectedSetup, selectedQueue, onTargetSelected])

  function handleSetupChange(value: string) {
    setSelectedSetup(value)
    setSelectedQueue(null)
  }

  function handleQueueChange(value: string) {
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
        message="No PeeGeeQ setup found"
        description="Create a setup here, then add queues to it on the Setups page before publishing messages."
        showIcon
        action={
          <Button type="primary" size="small" onClick={() => navigate('/generator/setup/new')}>
            Create Setup
          </Button>
        }
      />
    )
  }

  // Queue-load failure — distinct from a legitimately empty setup
  if (queueLoadError) {
    return (
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
    )
  }

  // No-queues state — setup selected but no queues yet
  if (queues.length === 0) {
    return (
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
    )
  }

  // Normal state — setup and queues available
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
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
