import { useState, useEffect } from 'react'
import { Row, Col, Card, Statistic, Table, Tag, Alert, Space, Button, message } from 'antd'
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







const Overview = () => {
  const [stats, setStats] = useState<SystemStats>({
    totalQueues: 0,
    totalConsumerGroups: 0,
    totalEventStores: 0,
    totalMessages: 0,
    messagesPerSecond: 0,
    activeConnections: 0,
    uptime: '0s'
  })
  const [queueData, setQueueData] = useState<QueueInfo[]>([])
  const [recentActivity, setRecentActivity] = useState<RecentActivity[]>([])
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
      // Show error state instead of mock data
      message.error('Failed to load overview data. Please check if the backend service is running.')
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
                locale={{
                  emptyText: loading ? 'Loading...' : 'No queues found. Please check if the backend service is running and has active setups.'
                }}
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
                locale={{
                  emptyText: loading ? 'Loading...' : 'No recent activity found.'
                }}
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </div>
  )
}

export default Overview
