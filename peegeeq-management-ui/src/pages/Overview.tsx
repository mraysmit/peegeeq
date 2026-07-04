import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Row, Col, Card, Statistic, Table, Tag, Alert, Space, Button, message, Typography, Descriptions, Empty } from 'antd'
import axios from 'axios'
// import { useSystemMetrics, useSystemMonitoring } from '../hooks/useRealTimeUpdates'
import { useManagementStore, QueueInfo } from '../stores/managementStore'
import { getVersionedApiUrl } from '../services/configService'
import SetupScopeBar from '../components/common/SetupScopeBar'
import {
    InboxOutlined,
    TeamOutlined,
    DatabaseOutlined,
    SendOutlined,
    DesktopOutlined,
    ApiOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    ReloadOutlined,
} from '@ant-design/icons'
// Real-time charts using recharts
import { XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area, Legend } from 'recharts'
import ErrorBoundary from '../components/common/ErrorBoundary'
// WebSocket service for real-time updates
import { createSystemMonitoringService, createSystemMetricsSSE } from '../services/websocketService'

const { Title } = Typography

interface SetupDetails {
    setupId: string
    status: string
    host?: string
    port?: number
    databaseName?: string
    schema?: string
    queueFactories: string[]
    eventStores: string[]
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
    const navigate = useNavigate()
    // Use Zustand store for centralized state management
    const {
        systemStats: stats,
        queues: queueData,
        throughputData,
        connectionData,
        loading,
        wsConnected,
        sseConnected,
        wsReconnecting,
        sseReconnecting,
        selectedSetupId,
        fetchSystemData,
        fetchQueues,
        updateChartData,
        setWebSocketStatus,
        setSSEStatus,
        setWsReconnecting,
        setSseReconnecting,
        refreshAll
    } = useManagementStore()

    const filteredQueueData = selectedSetupId
        ? queueData.filter((q: QueueInfo) => q.setup === selectedSetupId)
        : []

    // Local state for recent activity (not in global store)
    const [recentActivity, setRecentActivity] = useState<RecentActivity[]>([])
    const [setupDetails, setSetupDetails] = useState<SetupDetails | null>(null)

    useEffect(() => {
        if (!selectedSetupId) {
            setSetupDetails(null)
            return
        }
        axios.get(getVersionedApiUrl(`setups/${selectedSetupId}`))
            .then(res => setSetupDetails({
                setupId: res.data.setupId,
                status: res.data.status || 'UNKNOWN',
                host: res.data.host,
                port: res.data.port,
                databaseName: res.data.databaseName,
                schema: res.data.schema,
                queueFactories: Array.isArray(res.data.queueFactories) ? res.data.queueFactories : [],
                eventStores: Array.isArray(res.data.eventStores) ? res.data.eventStores : []
            }))
            .catch(() => setSetupDetails({
                setupId: selectedSetupId,
                status: 'UNKNOWN',
                queueFactories: [],
                eventStores: []
            }))
    }, [selectedSetupId])

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
                    useManagementStore.getState().setSystemStats(stats)
                } else if (message.type === 'management_event') {
                    // Catalogue-aligned backend event ({entity}.{action}.{state} eventName plus
                    // discrete entity/action/state/name/setupId fields). Compose the display label
                    // here so the bell reads like the client-side feed (e.g. "queue created — orders").
                    // The structured fields stay available for richer UI/filtering later.
                    const d = message.data || {}
                    const entityLabel = (d.entity || 'resource').replace(/-/g, ' ')
                    const actionPast: Record<string, string> = { creation: 'created', deletion: 'deleted' }
                    const verb = actionPast[d.action] || d.action || d.state || 'updated'
                    const name = d.name || 'system'
                    const capitalized = entityLabel.charAt(0).toUpperCase() + entityLabel.slice(1)
                    const description = d.setupId
                        ? `${capitalized} '${name}' ${verb} in setup '${d.setupId}'`
                        : `${capitalized} '${name}' ${verb}`
                    useManagementStore.getState().addNotification({
                        resource: name,
                        action: `${entityLabel} ${verb}`,
                        description,
                    })
                }
            },
            () => setWebSocketStatus(true),
            () => setWebSocketStatus(false),
            () => setWsReconnecting(true)
        )

        // Initialize metrics SSE
        sseServiceRef.current = createSystemMetricsSSE(
            (metrics: any) => {
                // Handle real-time metrics updates
                updateChartData(metrics)
                // Use the new store action to update stats cards
                useManagementStore.getState().setSystemStats(metrics)
            },
            () => setSSEStatus(true),         // onConnect
            () => setSSEStatus(false),        // onDisconnect
            () => setSseReconnecting(true)    // onReconnecting — mirrors the WebSocket path
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
                const safeStatus = String(status || 'unknown')
                const colors = { active: 'green', idle: 'orange', error: 'red' }
                const icons = {
                    active: <CheckCircleOutlined />,
                    idle: <ExclamationCircleOutlined />,
                    error: <ExclamationCircleOutlined />
                }
                return (
                    <Tag color={colors[safeStatus as keyof typeof colors]} icon={icons[safeStatus as keyof typeof icons]}>
                        {safeStatus.toUpperCase()}
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
                const safeStatus = String(status || 'unknown')
                const colors = { success: 'green', warning: 'orange', error: 'red' }
                return <Tag color={colors[safeStatus as keyof typeof colors]}>{safeStatus.toUpperCase()}</Tag>
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
            <SetupScopeBar />
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* System Health Alert */}
                <Alert
                    message="System Status: All services operational"
                    description={
                        <Space direction="vertical" size={4} data-testid="system-status-info">
                            <Space wrap>
                                <span>{`PeeGeeQ has been running for ${stats.uptime} with ${stats.dbPool?.active ?? 0} active DB connections`}</span>
                                <Tag
                                    color={wsConnected ? 'green' : wsReconnecting ? 'gold' : 'orange'}
                                    data-testid="websocket-status"
                                >
                                    WebSocket: {wsConnected ? 'Connected' : wsReconnecting ? 'Reconnecting…' : 'Disconnected'}
                                </Tag>
                                <Tag
                                    color={sseConnected ? 'green' : sseReconnecting ? 'gold' : 'orange'}
                                    data-testid="sse-status"
                                >
                                    SSE: {sseConnected ? 'Connected' : sseReconnecting ? 'Reconnecting…' : 'Disconnected'}
                                </Tag>
                            </Space>
                            {selectedSetupId ? (
                                <Card
                                    title={
                                        <Space>
                                            <span data-testid="selected-setup-tag">{selectedSetupId}</span>
                                            {setupDetails && (
                                                <Tag color={setupDetails.status === 'ACTIVE' ? 'green' : setupDetails.status === 'CREATING' ? 'orange' : 'red'}>
                                                    {setupDetails.status}
                                                </Tag>
                                            )}
                                        </Space>
                                    }
                                    size="small"
                                    style={{ marginTop: 4 }}
                                    data-testid="setup-details-panel"
                                >
                                    {setupDetails ? (
                                        <Descriptions column={{ xs: 1, sm: 3 }} size="small">
                                            <Descriptions.Item label="Setup ID">{setupDetails.setupId}</Descriptions.Item>
                                            <Descriptions.Item label="Host">{setupDetails.host}</Descriptions.Item>
                                            <Descriptions.Item label="Port">{setupDetails.port}</Descriptions.Item>
                                            <Descriptions.Item label="Database Name">{setupDetails.databaseName}</Descriptions.Item>
                                            <Descriptions.Item label="Schema">{setupDetails.schema}</Descriptions.Item>
                                        </Descriptions>
                                    ) : (
                                        <span style={{ color: '#8c8c8c' }}>Loading setup details</span>
                                    )}
                                </Card>
                            ) : (
                                <span data-testid="no-setup-info" style={{ color: '#8c8c8c' }}>Select a setup above to view data</span>
                            )}
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
                                value={Math.round(stats.messagesPerSecond)}
                                prefix={<SendOutlined style={{ color: '#fa8c16' }} />}
                                valueStyle={{ color: '#fa8c16' }}
                                suffix="msg/s"
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Connection metrics — Phase 11: the old meaningless `activeConnections` composite
                    split into three distinct, meaningful dimensions. */}
                <Row gutter={[16, 16]} data-testid="connection-metrics">
                    <Col xs={24} sm={12} lg={8}>
                        <Card>
                            <Statistic
                                title="Monitoring Sessions"
                                value={stats.monitoringSessions}
                                prefix={<DesktopOutlined style={{ color: '#13c2c2' }} />}
                                valueStyle={{ color: '#13c2c2' }}
                                data-testid="metric-monitoring-sessions"
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={8}>
                        <Card>
                            <Statistic
                                title="Active Subscriptions"
                                value={stats.activeSubscriptions}
                                prefix={<ApiOutlined style={{ color: '#52c41a' }} />}
                                valueStyle={{ color: '#52c41a' }}
                                data-testid="metric-active-subscriptions"
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={8}>
                        <Card>
                            <Statistic
                                title="DB Connections"
                                value={stats.dbPool?.active ?? 0}
                                prefix={<DatabaseOutlined style={{ color: '#fa8c16' }} />}
                                valueStyle={{ color: '#fa8c16' }}
                                suffix={`/ ${stats.dbPool?.total ?? 0} total`}
                                data-testid="metric-db-connections"
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Charts */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} lg={16}>
                        <Card title="Message Throughput (24h)" extra={<div className="realtime-indicator"><div className="realtime-dot"></div>Live</div>}>
                            <div style={{ height: 300 }}>
                                {throughputData.length > 1 ? (
                                <ErrorBoundary fallback={<div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Waiting for live data" /></div>}>
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
                                            isAnimationActive={false}
                                            dataKey="messages"
                                            stroke="#1890ff"
                                            fill="#1890ff"
                                            fillOpacity={0.3}
                                        />
                                    </AreaChart>
                                </ResponsiveContainer>
                                </ErrorBoundary>
                                ) : (
                                    <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Waiting for live data" />
                                    </div>
                                )}
                            </div>
                        </Card>
                    </Col>
                    <Col xs={24} lg={8}>
                        <Card title="DB Pool Connections" extra={<div className="realtime-indicator"><div className="realtime-dot"></div>Live</div>}>
                            <div style={{ height: 300 }}>
                                {connectionData.length > 1 ? (
                                <ErrorBoundary fallback={<div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Waiting for live data" /></div>}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <AreaChart data={connectionData}>
                                        <CartesianGrid strokeDasharray="3 3" />
                                        <XAxis
                                            dataKey="time"
                                            tick={{ fontSize: 12 }}
                                            interval="preserveStartEnd"
                                        />
                                        <YAxis
                                            tick={{ fontSize: 12 }}
                                            allowDecimals={false}
                                            label={{ value: 'Connections', angle: -90, position: 'insideLeft' }}
                                        />
                                        <Tooltip labelFormatter={(value) => `Time: ${value}`} />
                                        <Legend />
                                        <Area
                                            type="monotone"
                                            isAnimationActive={false}
                                            dataKey="active"
                                            name="Active"
                                            stackId="conn"
                                            stroke="#fa8c16"
                                            fill="#fa8c16"
                                            fillOpacity={0.4}
                                        />
                                        <Area
                                            type="monotone"
                                            isAnimationActive={false}
                                            dataKey="idle"
                                            name="Idle"
                                            stackId="conn"
                                            stroke="#1890ff"
                                            fill="#1890ff"
                                            fillOpacity={0.4}
                                        />
                                        <Area
                                            type="monotone"
                                            isAnimationActive={false}
                                            dataKey="pending"
                                            name="Pending"
                                            stackId="conn"
                                            stroke="#ff4d4f"
                                            fill="#ff4d4f"
                                            fillOpacity={0.4}
                                        />
                                    </AreaChart>
                                </ResponsiveContainer>
                                </ErrorBoundary>
                                ) : (
                                    <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Waiting for live data" />
                                    </div>
                                )}
                            </div>
                        </Card>
                    </Col>
                </Row>

                {/* Tables */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} lg={14}>
                        <Card title="Queue Overview" extra={<Button type="link" onClick={() => navigate('/queues')}>View All</Button>}>
                            <Table
                                data-testid="queue-overview-table"
                                columns={queueColumns}
                                dataSource={filteredQueueData}
                                pagination={false}
                                size="small"
                                loading={loading}
                                locale={{
                                    emptyText: loading ? 'Loading...' : !selectedSetupId ? 'Select a setup to view queues.' : 'No queues found for this setup.'
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
