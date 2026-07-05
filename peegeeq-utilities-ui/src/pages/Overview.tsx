import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
    Card,
    Table,
    Button,
    Space,
    Typography,
    Alert,
    Empty,
    List,
} from 'antd'
import { ReloadOutlined, PlusOutlined, DatabaseOutlined } from '@ant-design/icons'
import { useUtilitiesStore, SetupSummary } from '../stores/utilitiesStore'

const { Title, Text } = Typography

export default function Overview() {
    const navigate = useNavigate()
    const {
        setups,
        loading,
        error,
        fetchSystemData,
    } = useUtilitiesStore()

    const [selectedSetupId, setSelectedSetupId] = useState<string | null>(null)

    useEffect(() => {
        fetchSystemData()
        const interval = setInterval(fetchSystemData, 30000)
        return () => clearInterval(interval)
    }, [fetchSystemData])

    // Keep a valid selection: default to the first setup; drop it if it disappears.
    useEffect(() => {
        if (setups.length === 0) {
            setSelectedSetupId(null)
            return
        }
        setSelectedSetupId((current) =>
            current && setups.some((s) => s.setupId === current) ? current : setups[0].setupId
        )
    }, [setups])

    const selectedSetup = setups.find((s) => s.setupId === selectedSetupId) ?? null

    const columns = [
        {
            title: 'Setup ID',
            dataIndex: 'setupId',
            key: 'setupId',
            render: (id: string) => (
                <Space>
                    <DatabaseOutlined />
                    <strong>{id}</strong>
                </Space>
            ),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: string) => <Text type="secondary">{String(status ?? 'active').toUpperCase()}</Text>,
        },
        {
            title: 'Queues',
            dataIndex: 'totalQueues',
            key: 'totalQueues',
        },
    ]

    return (
        <div data-testid="overview-page">
            <Title level={2}>System Overview</Title>

            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {error && (
                    <Alert
                        data-testid="overview-error"
                        type="error"
                        message="Backend unreachable"
                        description={error}
                        showIcon
                    />
                )}

                <Card
                    title="Setups"
                    extra={
                        <Space>
                            <Button
                                icon={<ReloadOutlined />}
                                onClick={fetchSystemData}
                                loading={loading}
                                data-testid="refresh-button"
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
                    {setups.length === 0 && !loading ? (
                        <Alert
                            data-testid="no-setups"
                            type="info"
                            message="No setups found"
                            description="Create a setup to get started, then add queues to it."
                            showIcon
                        />
                    ) : (
                        <div data-testid="setups-list">
                            <Table
                                columns={columns}
                                dataSource={setups}
                                rowKey="setupId"
                                loading={loading && setups.length === 0}
                                pagination={false}
                                size="small"
                                onRow={(record) => ({
                                    onClick: () => setSelectedSetupId(record.setupId),
                                    style: {
                                        cursor: 'pointer',
                                        background:
                                            record.setupId === selectedSetupId ? '#e6f4ff' : undefined,
                                    },
                                })}
                            />
                        </div>
                    )}
                </Card>

                {selectedSetup && (
                    <Card
                        data-testid="setup-detail"
                        title={
                            <Space>
                                <DatabaseOutlined />
                                {selectedSetup.setupId}
                            </Space>
                        }
                    >
                        <Space direction="vertical" size="large" style={{ width: '100%' }}>
                            <SetupQueues setup={selectedSetup} />

                            <div data-testid="setup-detail-event-stores">
                                <Title level={5}>Event stores</Title>
                                {selectedSetup.eventStores && selectedSetup.eventStores.length > 0 ? (
                                    <List
                                        size="small"
                                        bordered
                                        dataSource={selectedSetup.eventStores}
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
                    </Card>
                )}
            </Space>
        </div>
    )
}

/** Queues of a setup, each with its own message stats and consumer groups. */
function SetupQueues({ setup }: { setup: SetupSummary }) {
    return (
        <div data-testid="setup-detail-queues">
            <Title level={5}>Queues ({setup.queues.length})</Title>
            {setup.queues.length > 0 ? (
                <List
                    size="small"
                    bordered
                    dataSource={setup.queues}
                    renderItem={(queue) => {
                        const groups = setup.consumerGroups.filter((g) => g.queueName === queue.name)
                        return (
                            <List.Item>
                                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                                    <Space>
                                        <strong>{queue.name}</strong>
                                        {queue.type && <Text type="secondary">{queue.type}</Text>}
                                        <Text type="secondary">
                                            {(queue.messages ?? 0).toLocaleString()} msgs ·{' '}
                                            {(queue.messageRate ?? 0).toFixed(1)} msg/s
                                        </Text>
                                    </Space>
                                    {groups.length > 0 && (
                                        <Text type="secondary" style={{ fontSize: 12 }}>
                                            Consumer groups:{' '}
                                            {groups.map((g) => `${g.groupName} (${g.status})`).join(' · ')}
                                        </Text>
                                    )}
                                </Space>
                            </List.Item>
                        )
                    }}
                />
            ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No queues in this setup" />
            )}
        </div>
    )
}
