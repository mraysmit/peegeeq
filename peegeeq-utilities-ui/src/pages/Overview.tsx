import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { Row, Col, Card, Statistic, Table, Tag, Alert, Space, Button, Typography } from 'antd'
import {
    InboxOutlined,
    TeamOutlined,
    DatabaseOutlined,
    SendOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    ReloadOutlined,
} from '@ant-design/icons'
import {
    LineChart,
    Line,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    ResponsiveContainer,
    AreaChart,
    Area,
} from 'recharts'
import { useUtilitiesStore, QueueInfo, SetupSummary } from '../stores/utilitiesStore'

const { Title } = Typography

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
            const colors: Record<string, string> = { active: 'green', idle: 'orange', error: 'red' }
            const icons: Record<string, ReactNode> = {
                active: <CheckCircleOutlined />,
                idle: <ExclamationCircleOutlined />,
                error: <ExclamationCircleOutlined />,
            }
            return (
                <Tag color={colors[safeStatus]} icon={icons[safeStatus]}>
                    {safeStatus.toUpperCase()}
                </Tag>
            )
        },
    },
]

export default function Overview() {
    const {
        systemStats: stats,
        setups,
        queues,
        throughputData,
        connectionData,
        loading,
        error,
        lastUpdated,
        fetchSystemData,
        fetchQueues,
        refreshAll,
    } = useUtilitiesStore()

    useEffect(() => {
        fetchSystemData()
        fetchQueues()

        const interval = setInterval(() => {
            fetchSystemData()
            fetchQueues()
        }, 30000)

        return () => clearInterval(interval)
    }, [fetchSystemData, fetchQueues])

    return (
        <div>
            <Title level={1}>System Overview</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>

                {/* System Health Alert */}
                <Alert
                    data-testid="system-status-alert"
                    message={
                        lastUpdated === null
                            ? 'System Status: Checking...'
                            : error
                            ? 'System Status: Backend unreachable'
                            : 'System Status: All services operational'
                    }
                    description={
                        <span data-testid="system-status-info">
                            {error
                                ? error
                                : `Uptime: ${stats.uptime} · ${stats.totalSetups} setup${stats.totalSetups !== 1 ? 's' : ''} · ${stats.totalQueues} queue${stats.totalQueues !== 1 ? 's' : ''} · ${stats.totalConsumerGroups} consumer group${stats.totalConsumerGroups !== 1 ? 's' : ''} · ${stats.totalMessages.toLocaleString()} messages · ${stats.messagesPerSecond.toFixed(1)} msg/s`}
                        </span>
                    }
                    type={
                        lastUpdated === null
                            ? 'info'
                            : error
                            ? 'error'
                            : 'success'
                    }
                    showIcon
                    action={
                        <Button
                            size="small"
                            icon={<ReloadOutlined />}
                            loading={loading}
                            onClick={() => refreshAll()}
                        >
                            Refresh
                        </Button>
                    }
                />

                {/* Key Metrics */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Setups"
                                value={stats.totalSetups}
                                prefix={<DatabaseOutlined style={{ color: '#13c2c2' }} />}
                                valueStyle={{ color: '#13c2c2' }}
                            />
                        </Card>
                    </Col>
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
                                title="Messages/sec"
                                value={stats.messagesPerSecond}
                                prefix={<SendOutlined style={{ color: '#fa8c16' }} />}
                                valueStyle={{ color: '#fa8c16' }}
                                suffix="msg/s"
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Per-Setup Breakdown */}
                <Row gutter={[16, 16]}>
                    <Col xs={24}>
                        <Card title="Setups" loading={loading}>
                            {setups.length === 0 ? (
                                <Typography.Text type="secondary">
                                    No active setups. Use the Queue Message Generator page to create a setup.
                                </Typography.Text>
                            ) : (
                                <Table
                                    dataSource={setups.map(s => ({ ...s, key: s.setupId }))}
                                    pagination={false}
                                    size="small"
                                    expandable={{
                                        expandedRowRender: (setup: SetupSummary) => (
                                            <Table
                                                dataSource={setup.queues}
                                                columns={queueColumns}
                                                pagination={false}
                                                size="small"
                                                rowKey="key"
                                            />
                                        ),
                                        rowExpandable: (setup: SetupSummary) => setup.totalQueues > 0,
                                    }}
                                    columns={[
                                        { title: 'Setup ID', dataIndex: 'setupId', key: 'setupId', render: (v: string) => <strong>{v}</strong> },
                                        { title: 'Status', dataIndex: 'status', key: 'status', render: (v: string) => <Tag color={v === 'ACTIVE' ? 'green' : 'red'}>{v}</Tag> },
                                        { title: 'Queues', dataIndex: 'totalQueues', key: 'totalQueues' },
                                        { title: 'Consumer Groups', dataIndex: 'totalConsumerGroups', key: 'totalConsumerGroups' },
                                        { title: 'Event Stores', dataIndex: 'totalEventStores', key: 'totalEventStores' },
                                        { title: 'Messages', dataIndex: 'totalMessages', key: 'totalMessages', render: (v: number) => v.toLocaleString() },
                                        { title: 'msg/s', dataIndex: 'messagesPerSecond', key: 'messagesPerSecond', render: (v: number) => v.toFixed(1) },
                                    ]}
                                />
                            )}
                        </Card>
                    </Col>
                </Row>

                {/* Charts */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} lg={16}>
                        <Card title="Message Throughput (live)">
                            <div style={{ height: 300 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <AreaChart data={throughputData}>
                                        <CartesianGrid strokeDasharray="3 3" />
                                        <XAxis dataKey="time" tick={{ fontSize: 12 }} interval="preserveStartEnd" />
                                        <YAxis
                                            tick={{ fontSize: 12 }}
                                            label={{ value: 'Messages/sec', angle: -90, position: 'insideLeft' }}
                                        />
                                        <Tooltip
                                            labelFormatter={(v) => `Time: ${v}`}
                                            formatter={(v) => [`${v} msg/s`, 'Throughput']}
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
                                        <XAxis dataKey="time" tick={{ fontSize: 12 }} interval="preserveStartEnd" />
                                        <YAxis
                                            tick={{ fontSize: 12 }}
                                            label={{ value: 'Connections', angle: -90, position: 'insideLeft' }}
                                        />
                                        <Tooltip
                                            labelFormatter={(v) => `Time: ${v}`}
                                            formatter={(v) => [`${v}`, 'Active Connections']}
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

                {/* Queue table */}
                <Row gutter={[16, 16]}>
                    <Col xs={24}>
                        <Card title="Queue Overview">
                            <Table
                                columns={queueColumns}
                                dataSource={queues}
                                pagination={false}
                                size="small"
                                loading={loading}
                                locale={{
                                    emptyText: loading
                                        ? 'Loading...'
                                        : 'No queues found. Check that the backend service is running.',
                                }}
                            />
                        </Card>
                    </Col>
                </Row>

            </Space>
        </div>
    )
}
