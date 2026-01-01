import { useState, useEffect, useRef } from 'react'
import { Row, Col, Card, Statistic, Table, Tag, Alert, Space, Button, message, Typography } from 'antd'
import axios from 'axios'
// import { useSystemMetrics, useSystemMonitoring } from '../hooks/useRealTimeUpdates'
import { useManagementStore, QueueInfo } from '../stores/managementStore'
import { getVersionedApiUrl } from '../services/configService'
import {
    InboxOutlined,
    TeamOutlined,
    DatabaseOutlined,
    SendOutlined,

    CheckCircleOutlined,
    ExclamationCircleOutlined,
    ReloadOutlined,
} from '@ant-design/icons'
// Real-time charts using recharts
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area } from 'recharts'
// WebSocket service for real-time updates
import { createSystemMonitoringService, createSystemMetricsSSE } from '../services/websocketService'

const { Title } = Typography

interface RecentActivity {
    key: string
    timestamp: string
    action: string
    resource: string
    status: 'success' | 'warning' | 'error'
    details: string
}







const Overview = () => {
    // Use Zustand store for centralized state management
    const {
        systemStats: stats,
        queues: queueData,
        throughputData,
        connectionData,
        loading,
        wsConnected,
        sseConnected,
        fetchSystemData,
        fetchQueues,
        updateChartData,
        setWebSocketStatus,
        setSSEStatus,
        refreshAll
    } = useManagementStore()

    // Local state for recent activity (not in global store)
    const [recentActivity, setRecentActivity] = useState<RecentActivity[]>([])

    // WebSocket services for real-time updates
    const wsServiceRef = useRef<any>(null)
    const sseServiceRef = useRef<any>(null)

    const fetchData = async () => {
        try {
            // Use Zustand store actions for data fetching
            await fetchSystemData()
            await fetchQueues()

            // Fetch recent activity (local to Overview component)
            const overviewResponse = await axios.get(getVersionedApiUrl('management/overview'))
            const data = overviewResponse.data

            if (data.recentActivity && Array.isArray(data.recentActivity)) {
                setRecentActivity(data.recentActivity.map((activity: any, index: number) => ({
                    key: index.toString(),
                    timestamp: activity.timestamp ? new Date(activity.timestamp).toLocaleString() : 'N/A',
                    action: activity.action,
                    resource: activity.resource,
                    status: activity.status,
                    details: activity.details
                })))
            }

        } catch (error) {
            console.error('Failed to fetch overview data:', error)
            message.error('Failed to load overview data. Please check if the backend service is running.')
        }
    }



    const refreshData = async () => {
        await refreshAll()
    }

    useEffect(() => {
        fetchData()

        // Initialize real-time services
        initializeRealTimeServices()

        // Listen for backend config changes
        const handleConfigChange = () => {
            console.log('Backend config changed, reinitializing real-time services')
            // Cleanup existing services
            if (wsServiceRef.current) {
                wsServiceRef.current.disconnect()
            }
            if (sseServiceRef.current) {
                sseServiceRef.current.disconnect()
            }
            // Reinitialize with new config
            initializeRealTimeServices()
        }
        window.addEventListener('peegeeq-config-changed', handleConfigChange)

        // Refresh every 30 seconds
        const interval = setInterval(fetchData, 30000)

        return () => {
            clearInterval(interval)
            window.removeEventListener('peegeeq-config-changed', handleConfigChange)
            // Cleanup real-time services
            if (wsServiceRef.current) {
                wsServiceRef.current.disconnect()
            }
            if (sseServiceRef.current) {
                sseServiceRef.current.disconnect()
            }
        }
    }, [])

    const initializeRealTimeServices = () => {
        // Initialize system monitoring WebSocket
        wsServiceRef.current = createSystemMonitoringService(
            (message: any) => {
                // Handle real-time system updates
                if (message.type === 'system_stats') {
                    const stats = message.data
                    updateChartData(stats)
                    // Use the new store action to update stats cards
                    useManagementStore.getState().setSystemStats(stats)
                }
            },
            () => setWebSocketStatus(true),
            () => setWebSocketStatus(false)
        )

        // Initialize metrics SSE
        sseServiceRef.current = createSystemMetricsSSE(
            (metrics: any) => {
                // Handle real-time metrics updates
                updateChartData(metrics)
                // Use the new store action to update stats cards
                useManagementStore.getState().setSystemStats(metrics)
            },
            () => setSSEStatus(true),  // onConnect
            () => setSSEStatus(false)  // onDisconnect
        )
    }

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
            render: (value: number) => (value ?? 0).toLocaleString(),
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
            render: (value: number) => (value ?? 0).toFixed(1),
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
            <Title level={1}>System Overview</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* System Health Alert */}
                <Alert
                    message="System Status: All services operational"
                    description={
                        <Space data-testid="system-status-info">
                            <span>{`PeeGeeQ has been running for ${stats.uptime} with ${stats.activeConnections} active connections`}</span>
                            <Tag
                                color={wsConnected ? 'green' : 'orange'}
                                data-testid="websocket-status"
                            >
                                WebSocket: {wsConnected ? 'Connected' : 'Disconnected'}
                            </Tag>
                            <Tag
                                color={sseConnected ? 'green' : 'orange'}
                                data-testid="sse-status"
                            >
                                SSE: {sseConnected ? 'Connected' : 'Disconnected'}
                            </Tag>
                        </Space>
                    }
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
                            <div style={{ height: 300 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <AreaChart data={throughputData}>
                                        <CartesianGrid strokeDasharray="3 3" />
                                        <XAxis
                                            dataKey="time"
                                            tick={{ fontSize: 12 }}
                                            interval="preserveStartEnd"
                                        />
                                        <YAxis
                                            tick={{ fontSize: 12 }}
                                            label={{ value: 'Messages/sec', angle: -90, position: 'insideLeft' }}
                                        />
                                        <Tooltip
                                            labelFormatter={(value) => `Time: ${value}`}
                                            formatter={(value) => [`${value} msg/s`, 'Throughput']}
                                        />
                                        <Area
                                            type="monotone"
                                            dataKey="messages"
                                            stroke="#1890ff"
                                            fill="#1890ff"
                                            fillOpacity={0.3}
                                        />
                                    </AreaChart>
                                </ResponsiveContainer>
                            </div>
                        </Card>
                    </Col>
                    <Col xs={24} lg={8}>
                        <Card title="Active Connections">
                            <div style={{ height: 300 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <LineChart data={connectionData}>
                                        <CartesianGrid strokeDasharray="3 3" />
                                        <XAxis
                                            dataKey="time"
                                            tick={{ fontSize: 12 }}
                                            interval="preserveStartEnd"
                                        />
                                        <YAxis
                                            tick={{ fontSize: 12 }}
                                            label={{ value: 'Connections', angle: -90, position: 'insideLeft' }}
                                        />
                                        <Tooltip
                                            labelFormatter={(value) => `Time: ${value}`}
                                            formatter={(value) => [`${value}`, 'Active Connections']}
                                        />
                                        <Line
                                            type="monotone"
                                            dataKey="connections"
                                            stroke="#52c41a"
                                            strokeWidth={2}
                                            dot={{ fill: '#52c41a', strokeWidth: 2, r: 4 }}
                                        />
                                    </LineChart>
                                </ResponsiveContainer>
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
