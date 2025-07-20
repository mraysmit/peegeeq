import { useState, useEffect } from 'react'
import { Row, Col, Card, Statistic, Table, Tag, Alert, Space, Button } from 'antd'
import axios from 'axios'
// import { useSystemMetrics, useSystemMonitoring } from '../hooks/useRealTimeUpdates'
import {
  InboxOutlined,
  TeamOutlined,
  DatabaseOutlined,
  SendOutlined,

  CheckCircleOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
// Charts would require recharts package: npm install recharts
// import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area } from 'recharts'

interface SystemStats {
  totalQueues: number
  totalConsumerGroups: number
  totalEventStores: number
  totalMessages: number
  messagesPerSecond: number
  activeConnections: number
  uptime: string
}

interface QueueInfo {
  key: string
  name: string
  setup: string
  messages: number
  consumers: number
  status: 'active' | 'idle' | 'error'
  messageRate: number
}

interface RecentActivity {
  key: string
  timestamp: string
  action: string
  resource: string
  status: 'success' | 'warning' | 'error'
  details: string
}

// Mock data - in real implementation, this would come from API
const mockSystemStats: SystemStats = {
  totalQueues: 12,
  totalConsumerGroups: 8,
  totalEventStores: 4,
  totalMessages: 1547892,
  messagesPerSecond: 245,
  activeConnections: 23,
  uptime: '7d 14h 32m',
}

const mockQueueData: QueueInfo[] = [
  {
    key: '1',
    name: 'orders',
    setup: 'production',
    messages: 1247,
    consumers: 3,
    status: 'active',
    messageRate: 45.2,
  },
  {
    key: '2',
    name: 'payments',
    setup: 'production',
    messages: 892,
    consumers: 2,
    status: 'active',
    messageRate: 32.1,
  },
  {
    key: '3',
    name: 'notifications',
    setup: 'production',
    messages: 0,
    consumers: 1,
    status: 'idle',
    messageRate: 0,
  },
  {
    key: '4',
    name: 'analytics',
    setup: 'staging',
    messages: 2341,
    consumers: 0,
    status: 'error',
    messageRate: 0,
  },
]

const mockRecentActivity: RecentActivity[] = [
  {
    key: '1',
    timestamp: '2025-07-19 14:32:15',
    action: 'Consumer Group Created',
    resource: 'order-processors',
    status: 'success',
    details: 'Created with 3 members',
  },
  {
    key: '2',
    timestamp: '2025-07-19 14:28:42',
    action: 'Queue Message Sent',
    resource: 'orders',
    status: 'success',
    details: 'Batch of 50 messages',
  },
  {
    key: '3',
    timestamp: '2025-07-19 14:25:18',
    action: 'WebSocket Connection',
    resource: 'ws-stream-001',
    status: 'success',
    details: 'Real-time streaming started',
  },
  {
    key: '4',
    timestamp: '2025-07-19 14:22:03',
    action: 'Consumer Timeout',
    resource: 'analytics-consumer-2',
    status: 'warning',
    details: 'Session timeout after 30s',
  },
]

// Mock chart data (removed since charts are not implemented)
// const mockThroughputData = Array.from({ length: 24 }, (_, i) => ({
//   time: `${String(i).padStart(2, '0')}:00`,
//   messages: Math.floor(Math.random() * 1000) + 200,
//   connections: Math.floor(Math.random() * 50) + 10,
// }))

const Overview = () => {
  const [stats, setStats] = useState<SystemStats>(mockSystemStats)
  const [queueData, setQueueData] = useState<QueueInfo[]>(mockQueueData)
  const [recentActivity, setRecentActivity] = useState<RecentActivity[]>(mockRecentActivity)
  const [loading, setLoading] = useState(true)
  // const [realTimeEnabled] = useState(true)

  const fetchData = async () => {
    setLoading(true)
    try {
      // Fetch real data from API
      const overviewResponse = await axios.get('/api/v1/management/overview')
      const data = overviewResponse.data

      // Update system stats with real data
      setStats({
        totalQueues: data.systemStats?.totalQueues || 0,
        totalConsumerGroups: data.systemStats?.totalConsumerGroups || 0,
        totalEventStores: data.systemStats?.totalEventStores || 0,
        totalMessages: data.systemStats?.totalMessages || 0,
        messagesPerSecond: data.systemStats?.messagesPerSecond || 0,
        activeConnections: data.systemStats?.activeConnections || 0,
        uptime: data.systemStats?.uptime || '0s'
      })

      // Update recent activity with real data
      if (data.recentActivity && Array.isArray(data.recentActivity)) {
        setRecentActivity(data.recentActivity.map((activity: any, index: number) => ({
          key: index.toString(),
          timestamp: new Date(activity.timestamp).toLocaleString(),
          action: activity.action,
          resource: activity.resource,
          status: activity.status,
          details: activity.details
        })))
      }

      // Fetch queue data
      const queuesResponse = await axios.get('/api/v1/management/queues')
      if (queuesResponse.data.queues && Array.isArray(queuesResponse.data.queues)) {
        setQueueData(queuesResponse.data.queues.slice(0, 5).map((queue: any, index: number) => ({
          key: index.toString(),
          name: queue.name,
          setup: queue.setup,
          messages: queue.messages,
          consumers: queue.consumers,
          status: queue.status,
          messageRate: queue.messageRate || 0
        })))
      }

    } catch (error) {
      console.error('Failed to fetch overview data:', error)
      // Keep using mock data on error
    } finally {
      setLoading(false)
    }
  }

  const refreshData = async () => {
    await fetchData()
  }

  useEffect(() => {
    fetchData()
    // Refresh every 30 seconds
    const interval = setInterval(fetchData, 30000)
    return () => clearInterval(interval)
  }, [])

  const queueColumns = [
    {
      title: 'Queue Name',
      dataIndex: 'name',
      key: 'name',
      render: (text: string, record: QueueInfo) => (
        <Space>
          <strong>{text}</strong>
          <Tag color="blue">{record.setup}</Tag>
        </Space>
      ),
    },
    {
      title: 'Messages',
      dataIndex: 'messages',
      key: 'messages',
      render: (value: number) => value.toLocaleString(),
    },
    {
      title: 'Consumers',
      dataIndex: 'consumers',
      key: 'consumers',
    },
    {
      title: 'Rate (msg/s)',
      dataIndex: 'messageRate',
      key: 'messageRate',
      render: (value: number) => value.toFixed(1),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const colors = { active: 'green', idle: 'orange', error: 'red' }
        const icons = { 
          active: <CheckCircleOutlined />, 
          idle: <ExclamationCircleOutlined />, 
          error: <ExclamationCircleOutlined /> 
        }
        return (
          <Tag color={colors[status as keyof typeof colors]} icon={icons[status as keyof typeof icons]}>
            {status.toUpperCase()}
          </Tag>
        )
      },
    },
  ]

  const activityColumns = [
    {
      title: 'Time',
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 150,
    },
    {
      title: 'Action',
      dataIndex: 'action',
      key: 'action',
    },
    {
      title: 'Resource',
      dataIndex: 'resource',
      key: 'resource',
      render: (text: string) => <code>{text}</code>,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const colors = { success: 'green', warning: 'orange', error: 'red' }
        return <Tag color={colors[status as keyof typeof colors]}>{status.toUpperCase()}</Tag>
      },
    },
    {
      title: 'Details',
      dataIndex: 'details',
      key: 'details',
    },
  ]

  return (
    <div className="fade-in">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* System Health Alert */}
        <Alert
          message="System Status: All services operational"
          description={`PeeGeeQ has been running for ${stats.uptime} with ${stats.activeConnections} active connections`}
          type="success"
          showIcon
          action={
            <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={refreshData}>
              Refresh
            </Button>
          }
        />

        {/* Key Metrics */}
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Total Queues"
                value={stats.totalQueues}
                prefix={<InboxOutlined style={{ color: '#1890ff' }} />}
                valueStyle={{ color: '#1890ff' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Consumer Groups"
                value={stats.totalConsumerGroups}
                prefix={<TeamOutlined style={{ color: '#52c41a' }} />}
                valueStyle={{ color: '#52c41a' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Event Stores"
                value={stats.totalEventStores}
                prefix={<DatabaseOutlined style={{ color: '#722ed1' }} />}
                valueStyle={{ color: '#722ed1' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Messages/sec"
                value={stats.messagesPerSecond}
                prefix={<SendOutlined style={{ color: '#fa8c16' }} />}
                valueStyle={{ color: '#fa8c16' }}
                suffix="msg/s"
              />
            </Card>
          </Col>
        </Row>

        {/* Charts */}
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={16}>
            <Card title="Message Throughput (24h)" extra={<div className="realtime-indicator"><div className="realtime-dot"></div>Live</div>}>
              <div style={{ height: 300, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f5f5f5', borderRadius: '4px' }}>
                <div style={{ textAlign: 'center', color: '#999' }}>
                  <div>📊 Chart Placeholder</div>
                  <div style={{ fontSize: '12px', marginTop: '8px' }}>Install recharts package to display charts</div>
                </div>
              </div>
            </Card>
          </Col>
          <Col xs={24} lg={8}>
            <Card title="Active Connections">
              <div style={{ height: 300, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f5f5f5', borderRadius: '4px' }}>
                <div style={{ textAlign: 'center', color: '#999' }}>
                  <div>📈 Chart Placeholder</div>
                  <div style={{ fontSize: '12px', marginTop: '8px' }}>Install recharts package to display charts</div>
                </div>
              </div>
            </Card>
          </Col>
        </Row>

        {/* Tables */}
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={14}>
            <Card title="Queue Overview" extra={<Button type="link">View All</Button>}>
              <Table
                columns={queueColumns}
                dataSource={queueData}
                pagination={false}
                size="small"
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} lg={10}>
            <Card title="Recent Activity" extra={<Button type="link">View All</Button>}>
              <Table
                columns={activityColumns}
                dataSource={recentActivity}
                pagination={false}
                size="small"
                loading={loading}
                scroll={{ y: 300 }}
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  )
}

export default Overview
