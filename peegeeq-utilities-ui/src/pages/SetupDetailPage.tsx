import { useState, useEffect, useCallback } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Card,
  Button,
  Space,
  Tag,
  Typography,
  Alert,
  Spin,
  Descriptions,
  List,
  Empty,
} from 'antd'
import {
  ArrowLeftOutlined,
  ReloadOutlined,
  DatabaseOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons'
import { getSetupDetails } from '../services/setupService'
import { listQueueDetails } from '../services/queueService'
import type { SetupDetails } from '../types/setup'
import type { QueueSummary } from '../types/queue'

const { Title } = Typography

const statusColors: Record<string, string> = {
  active: 'green',
  creating: 'orange',
  failed: 'red',
}
const statusIcons: Record<string, React.ReactNode> = {
  active: <CheckCircleOutlined />,
  failed: <ExclamationCircleOutlined />,
}

const queueTypeColors: Record<string, string> = {
  native: 'green',
  outbox: 'orange',
}

export default function SetupDetailPage() {
  const navigate = useNavigate()
  const { setupId = '' } = useParams<{ setupId: string }>()
  const [details, setDetails] = useState<SetupDetails | null>(null)
  const [queueSummaries, setQueueSummaries] = useState<QueueSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadDetails = useCallback(async () => {
    if (!setupId) return
    setLoading(true)
    setLoadError(null)
    try {
      const [result, queues] = await Promise.all([
        getSetupDetails(setupId),
        listQueueDetails(setupId),
      ])
      setDetails(result)
      setQueueSummaries(queues)
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status
      setLoadError(status === 404 ? `Setup "${setupId}" not found` : `Failed to load setup "${setupId}"`)
    } finally {
      setLoading(false)
    }
  }, [setupId])

  useEffect(() => {
    loadDetails()
  }, [loadDetails])

  const status = details?.status ?? 'active'
  const queues = queueSummaries
  const eventStores = details?.eventStores ?? []

  return (
    <div data-testid="setup-detail-page" style={{ maxWidth: 720 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/setups')}
          data-testid="back-button"
        >
          Back to setups
        </Button>
      </Space>

      <Title level={2}>
        <Space>
          <DatabaseOutlined />
          {setupId}
        </Space>
      </Title>

      {loadError && (
        <Alert
          type="error"
          message={loadError}
          style={{ marginBottom: 16 }}
          showIcon
          data-testid="setup-detail-error"
        />
      )}

      <Card
        title="Setup details"
        extra={
          <Space>
            <Button
              icon={<ReloadOutlined />}
              onClick={loadDetails}
              loading={loading}
              data-testid="refresh-detail-button"
            >
              Refresh
            </Button>
          </Space>
        }
      >
        {loading && !details ? (
          <div style={{ textAlign: 'center', padding: 32 }}>
            <Spin data-testid="setup-detail-spinner" />
          </div>
        ) : details ? (
          <Space direction="vertical" style={{ width: '100%' }} size="large">
            <Descriptions column={1} bordered data-testid="setup-detail-descriptions">
              <Descriptions.Item label="Setup ID">{setupId}</Descriptions.Item>
              <Descriptions.Item label="Status">
                <Tag color={statusColors[status] ?? 'default'} icon={statusIcons[status]}>
                  {status.toUpperCase()}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Queue count">{queues.length}</Descriptions.Item>
              <Descriptions.Item label="Event store count">{eventStores.length}</Descriptions.Item>
            </Descriptions>

            <div data-testid="setup-detail-queues">
              <Title level={5} style={{ marginBottom: 8 }}>
                Queues
              </Title>
              {queues.length > 0 ? (
                <List
                  size="small"
                  bordered
                  dataSource={queues}
                  // Read-only listing: queue deletion was removed from this UI
                  // (2026-07-21, user decision) — destructive queue operations
                  // go through the admin REST path only.
                  renderItem={(queue) => (
                    <List.Item>
                      <Space>
                        {queue.name}
                        {queue.implementationType && (
                          <Tag color={queueTypeColors[queue.implementationType] ?? 'default'}>
                            {queue.implementationType}
                          </Tag>
                        )}
                      </Space>
                    </List.Item>
                  )}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="No queues in this setup"
                />
              )}
            </div>

            <div data-testid="setup-detail-event-stores">
              <Title level={5}>Event stores</Title>
              {eventStores.length > 0 ? (
                <List
                  size="small"
                  bordered
                  dataSource={eventStores}
                  renderItem={(name) => <List.Item>{name}</List.Item>}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="No event stores in this setup"
                />
              )}
            </div>
          </Space>
        ) : null}
      </Card>
    </div>
  )
}
