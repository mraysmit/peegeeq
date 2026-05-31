import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Table,
  Card,
  Button,
  Space,
  Tag,
  Typography,
  Alert,
  Popconfirm,
  message,
} from 'antd'
import {
  PlusOutlined,
  DeleteOutlined,
  EyeOutlined,
  ReloadOutlined,
  DatabaseOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons'
import { getSetups, getSetupDetails, deleteSetup } from '../services/setupService'
import type { SetupDetails } from '../types/setup'

const { Title } = Typography

interface SetupRow {
  key: string
  setupId: string
  queues: number
  eventStores: number
  status: string
  details?: SetupDetails
}

export default function SetupsPage() {
  const navigate = useNavigate()
  const [setups, setSetups] = useState<SetupRow[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadSetups = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const ids = await getSetups()
      const rows = await Promise.all(
        ids.map(async (id) => {
          try {
            const details = await getSetupDetails(id)
            return {
              key: id,
              setupId: id,
              queues: details.queueFactories?.length ?? 0,
              eventStores: details.eventStores?.length ?? 0,
              status: details.status ?? 'active',
              details,
            }
          } catch {
            return { key: id, setupId: id, queues: 0, eventStores: 0, status: 'active' }
          }
        })
      )
      setSetups(rows)
    } catch {
      setLoadError('Failed to load setups')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadSetups()
  }, [loadSetups])

  const handleDelete = async (setupId: string) => {
    try {
      await deleteSetup(setupId)
      message.success(`Setup "${setupId}" deleted`)
      await loadSetups()
    } catch {
      message.error(`Failed to delete setup "${setupId}"`)
    }
  }

  const statusColors: Record<string, string> = {
    active: 'green',
    creating: 'orange',
    failed: 'red',
  }
  const statusIcons: Record<string, React.ReactNode> = {
    active: <CheckCircleOutlined />,
    failed: <ExclamationCircleOutlined />,
  }

  const columns = [
    {
      title: 'Setup ID',
      dataIndex: 'setupId',
      key: 'setupId',
      render: (text: string) => (
        <Space>
          <DatabaseOutlined />
          <strong>{text}</strong>
        </Space>
      ),
    },
    {
      title: 'Queues',
      dataIndex: 'queues',
      key: 'queues',
      render: (count: number) => <Tag color="blue">{count}</Tag>,
    },
    {
      title: 'Event Stores',
      dataIndex: 'eventStores',
      key: 'eventStores',
      render: (count: number) => <Tag color="purple">{count}</Tag>,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={statusColors[status] ?? 'default'} icon={statusIcons[status]}>
          {status.toUpperCase()}
        </Tag>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: unknown, record: SetupRow) => (
        <Space>
          <Button
            type="text"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/setups/${record.setupId}`)}
            data-testid={`view-details-${record.setupId}`}
          >
            Details
          </Button>
          <Popconfirm
            title={`Delete setup "${record.setupId}"?`}
            description="This will drop the database and all associated queues. This cannot be undone."
            onConfirm={() => handleDelete(record.setupId)}
            okText="Delete"
            okType="danger"
            cancelText="Cancel"
          >
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              data-testid={`delete-${record.setupId}`}
            >
              Delete
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="setups-page">
      <Title level={2}>Setups</Title>

      {loadError && (
        <Alert
          type="error"
          message={loadError}
          style={{ marginBottom: 16 }}
          showIcon
          closable
          onClose={() => setLoadError(null)}
        />
      )}

      <Card
        title="Database Setups"
        extra={
          <Space>
            <Button
              icon={<ReloadOutlined />}
              onClick={loadSetups}
              loading={loading}
              data-testid="refresh-setups-button"
            >
              Refresh
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/generator/setup/new')}
              data-testid="create-setup-button"
            >
              Create Setup
            </Button>
          </Space>
        }
      >
        {setups.length === 0 && !loading && (
          <Alert
            message="No setups found"
            description="Create your first setup to start using PeeGeeQ. A setup represents a PostgreSQL database configuration with queues and event stores."
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            data-testid="no-setups-alert"
          />
        )}

        <div data-testid="setups-table">
          <Table
            columns={columns}
            dataSource={setups}
            loading={loading}
            pagination={{ pageSize: 10, showTotal: (total) => `${total} setup${total !== 1 ? 's' : ''}` }}
          />
        </div>
      </Card>
    </div>
  )
}
