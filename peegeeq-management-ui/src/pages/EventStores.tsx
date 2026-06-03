import { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
import { useManagementStore } from '../stores/managementStore'
import {
    Card,
    Table,
    Button,
    Space,
    Tag,
    Modal,
    Form,
    Input,
    Select,
    Row,
    Col,
    Statistic,
    Tooltip,
    Dropdown,
    Typography,
    Descriptions,
    message
} from 'antd'
import {
    DatabaseOutlined,
    PlusOutlined,
    DeleteOutlined,
    EyeOutlined,
    MoreOutlined,
    SearchOutlined,
    ReloadOutlined,
    HistoryOutlined,
    BranchesOutlined,
    ClockCircleOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    FileTextOutlined,
    ApiOutlined
} from '@ant-design/icons'

import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

import SetupScopeBar from '../components/common/SetupScopeBar'

const { Text, Title } = Typography

interface EventStore {
    key: string
    name: string
    setupId: string
    eventCount: number
    streamCount: number
    aggregateTypes: string[]
    eventTypes: string[]
    createdAt: string
    lastEventAt?: string
    status: 'active' | 'inactive' | 'error'
    storageSize: number
    correctionCount: number
}







interface DatabaseSetup {
    setupId: string
    host: string
    port: number
    database: string
    schema: string
}

const EventStores = () => {
    const { selectedSetupId } = useManagementStore()
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [setups, setSetups] = useState<DatabaseSetup[]>([])
    const [selectedEventStore, setSelectedEventStore] = useState<EventStore | null>(null)
    const [isCreateModalVisible, setIsCreateModalVisible] = useState(false)
    const [isEventStoreDetailsModalVisible, setIsEventStoreDetailsModalVisible] = useState(false)
    const [loading, setLoading] = useState(true)
    const [setupsLoading, setSetupsLoading] = useState(false)

    const [form] = Form.useForm()

    const fetchEventStores = async () => {
        setLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('management/event-stores'))
            if (response.data.eventStores && Array.isArray(response.data.eventStores)) {
                setEventStores(response.data.eventStores.map((store: any) => ({
                    key: `${store.setup}-${store.name}`,
                    name: store.name,
                    setupId: store.setup,
                    eventCount: store.events || 0,
                    streamCount: store.aggregates || 0,
                    aggregateTypes: [], // Will be fetched from stats API if needed
                    eventTypes: [], // Will be fetched from stats API if needed
                    createdAt: store.createdAt,
                    lastEventAt: store.lastEvent,
                    status: store.status || 'active',
                    storageSize: store.events * 1024, // Estimate: 1KB per event
                    correctionCount: store.corrections || 0
                })))
            }
        } catch (error) {
            console.error('Failed to fetch event stores:', error)
            message.error('Failed to load event stores. Please check if the backend service is running.')
        } finally {
            setLoading(false)
        }
    }

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetups(response.data.setupIds.map((setupId: string) => ({
                    setupId,
                    host: 'localhost',
                    port: 5432,
                    database: 'postgres',
                    schema: setupId
                })))
            } else {
                setSetups([])
            }
        } catch (error) {
            console.error('Failed to fetch database setups:', error)
            message.error('Failed to load database setups')
            setSetups([])
        } finally {
            setSetupsLoading(false)
        }
    }

    useEffect(() => {
        fetchEventStores()
        // Refresh every 30 seconds
        const interval = setInterval(fetchEventStores, 30000)
        return () => clearInterval(interval)
    }, [])

    useEffect(() => {
        fetchSetups()
    }, [])

    const handleCreateEventStore = () => {
        form.resetFields()
        fetchSetups() // Refresh setups when opening modal
        setIsCreateModalVisible(true)
    }

    const handleViewEventStoreDetails = (eventStore: EventStore) => {
        setSelectedEventStore(eventStore)
        setIsEventStoreDetailsModalVisible(true)
    }

    const handleDeleteEventStore = (eventStore: EventStore) => {
        Modal.confirm({
            title: 'Delete Event Store',
            content: `Are you sure you want to delete event store "${eventStore.name}"? This will permanently delete all events.`,
            okText: 'Delete',
            okType: 'danger',
            onOk: async () => {
                try {
                    const storeId = `${eventStore.setupId}-${eventStore.name}`
                    await axios.delete(getVersionedApiUrl(`management/event-stores/${storeId}`))
                    message.success(`Event store "${eventStore.name}" deleted successfully`)
                    useManagementStore.getState().addNotification({ resource: eventStore.name, action: 'event store deleted' })
                    await fetchEventStores()
                } catch (error: any) {
                    console.error('Error deleting event store:', error)
                    message.error(`Failed to delete event store: ${error.response?.data?.error || error.message}`)
                }
            }
        })
    }

    const getStatusColor = (status: string) => {
        const colors = {
            active: 'green',
            inactive: 'orange',
            error: 'red'
        }
        return colors[status as keyof typeof colors] || 'default'
    }

    const getStatusIcon = (status: string) => {
        const icons = {
            active: <CheckCircleOutlined />,
            inactive: <ClockCircleOutlined />,
            error: <ExclamationCircleOutlined />
        }
        return icons[status as keyof typeof icons]
    }

    const getActionMenu = (eventStore: EventStore) => ({
        items: [
            {
                key: 'view',
                icon: <EyeOutlined />,
                label: 'View Details',
                onClick: () => handleViewEventStoreDetails(eventStore),
            },
            {
                key: 'query',
                icon: <SearchOutlined />,
                label: 'Query Events',
                onClick: () => handleViewEventStoreDetails(eventStore),
            },
            {
                type: 'divider' as const,
            },
            {
                key: 'delete',
                icon: <DeleteOutlined />,
                label: 'Delete Store',
                danger: true,
                onClick: () => handleDeleteEventStore(eventStore),
            },
        ],
    })

    const eventStoreColumns = [
        {
            title: 'Event Store Name',
            dataIndex: 'name',
            key: 'name',
            render: (text: string, record: EventStore) => (
                <Space>
                    <strong>{text}</strong>
                    <Tag color="blue">{record.setupId}</Tag>
                </Space>
            ),
        },
        {
            title: 'Events',
            key: 'events',
            render: (record: EventStore) => (
                <Space direction="vertical" size="small">
                    <div>
                        <FileTextOutlined style={{ color: '#1890ff', marginRight: 4 }} />
                        <Text>{record.eventCount.toLocaleString()}</Text>
                    </div>
                    <div>
                        <BranchesOutlined style={{ color: '#52c41a', marginRight: 4 }} />
                        <Text>{record.streamCount.toLocaleString()} streams</Text>
                    </div>
                </Space>
            ),
        },
        {
            title: 'Storage',
            key: 'storage',
            render: (record: EventStore) => (
                <Space direction="vertical" size="small">
                    <div>
                        <DatabaseOutlined style={{ color: '#722ed1', marginRight: 4 }} />
                        <Text>{(record.storageSize / 1024 / 1024).toFixed(1)} MB</Text>
                    </div>
                    <div>
                        <HistoryOutlined style={{ color: '#fa8c16', marginRight: 4 }} />
                        <Text>{record.correctionCount} corrections</Text>
                    </div>
                </Space>
            ),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: string, record: EventStore) => (
                <Space direction="vertical" size="small">
                    <Tag color={getStatusColor(status)} icon={getStatusIcon(status)}>
                        {status.toUpperCase()}
                    </Tag>
                    {record.lastEventAt && (
                        <Tooltip title={`Last event: ${dayjs(record.lastEventAt).format('YYYY-MM-DD HH:mm:ss')}`}>
                            <Text type="secondary" style={{ fontSize: '11px' }}>
                                <ClockCircleOutlined /> {dayjs(record.lastEventAt).fromNow()}
                            </Text>
                        </Tooltip>
                    )}
                </Space>
            ),
        },
        {
            title: 'Created',
            dataIndex: 'createdAt',
            key: 'createdAt',
            render: (text: string) => (
                <Tooltip title={text}>
                    <Text>{dayjs(text).format('MMM DD, YYYY')}</Text>
                </Tooltip>
            ),
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (record: EventStore) => (
                <Dropdown menu={getActionMenu(record)} trigger={['click']}>
                    <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            ),
        },
    ]

    // Calculate summary statistics
    const filteredEventStores = selectedSetupId
        ? eventStores.filter(es => es.setupId === selectedSetupId)
        : eventStores
    const totalEventStores = filteredEventStores.length
    const activeEventStores = filteredEventStores.filter(es => es.status === 'active').length
    const totalEvents = filteredEventStores.reduce((sum, es) => sum + es.eventCount, 0)
    const totalStorage = filteredEventStores.reduce((sum, es) => sum + es.storageSize, 0)

    return (
        <div className="fade-in">
            <Title level={1}>Event Stores</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* Setup scope selector */}
                <SetupScopeBar />

                {/* Summary Cards */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Event Stores"
                                value={totalEventStores}
                                prefix={<DatabaseOutlined style={{ color: '#1890ff' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Active Stores"
                                value={activeEventStores}
                                prefix={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Total Events"
                                value={totalEvents}
                                prefix={<FileTextOutlined style={{ color: '#722ed1' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Storage Used"
                                value={(totalStorage / 1024 / 1024).toFixed(1)}
                                suffix="MB"
                                prefix={<DatabaseOutlined style={{ color: '#fa8c16' }} />}
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Event Stores Table */}
                <Card
                    title={`Event Stores (${filteredEventStores.length})`}
                    extra={
                        <Space>
                            <Button
                                icon={<ReloadOutlined />}
                                loading={loading}
                                onClick={() => fetchEventStores()}
                            >
                                Refresh
                            </Button>
                            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateEventStore}>
                                Create Event Store
                            </Button>
                        </Space>
                    }
                >
                    <Table
                        columns={eventStoreColumns}
                        dataSource={filteredEventStores}
                        pagination={{
                            pageSize: 10,
                            showSizeChanger: true,
                            showQuickJumper: true,
                            showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} event stores`,
                        }}
                        loading={loading}
                        locale={{
                            emptyText: loading ? 'Loading...' : 'No event stores found. Please check if the backend service is running and has active setups.'
                        }}
                    />
                </Card>

                {/* Create Event Store Modal */}
                <Modal
                    title="Create Event Store"
                    open={isCreateModalVisible}
                    onOk={async () => {
                        try {
                            const values = await form.validateFields()

                            // Call Management API to create event store with bitemporal configuration
                            const requestBody = {
                                name: values.name,
                                setup: values.setupId,
                                biTemporalEnabled: true,  // Enable bitemporal support for event posting
                                retentionDays: 365        // Default retention policy
                            }

                            const response = await axios.post(getVersionedApiUrl('management/event-stores'), requestBody)
                            message.success(response.data.message || `Event store "${values.name}" created successfully`)
                            useManagementStore.getState().addNotification({ resource: values.name, action: 'event store created' })

                            // Refresh the event stores list
                            await fetchEventStores()
                            setIsCreateModalVisible(false)
                            form.resetFields()
                        } catch (error: any) {
                            console.error('Failed to create event store:', error)
                            
                            // Extract error message from various possible backend response formats
                            let errorMessage = 'Failed to create event store'
                            
                            if (error.response?.data) {
                                // Try different backend error formats
                                if (typeof error.response.data === 'string') {
                                    errorMessage = error.response.data
                                } else if (error.response.data.message) {
                                    errorMessage = error.response.data.message
                                } else if (error.response.data.error) {
                                    errorMessage = error.response.data.error
                                } else if (error.response.data.detail) {
                                    errorMessage = error.response.data.detail
                                } else {
                                    // If data is an object but no standard field, stringify it
                                    errorMessage = JSON.stringify(error.response.data)
                                }
                            } else if (error.message) {
                                errorMessage = error.message
                            }
                            
                            console.error('Error response:', error.response)
                            console.error('Error response data:', error.response?.data)
                            message.error(errorMessage)
                            // Keep modal open on error so user can retry
                        }
                    }}
                    onCancel={() => setIsCreateModalVisible(false)}
                    width={600}
                >
                    <Form form={form} layout="vertical">
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item
                                    name="name"
                                    label="Event Store Name"
                                    rules={[{ required: true, message: 'Please enter event store name' }]}
                                >
                                    <Input placeholder="e.g., order-events" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="setupId"
                                    label={
                                        <Space>
                                            <span>Setup</span>
                                            <Button
                                                type="link"
                                                size="small"
                                                icon={<ReloadOutlined />}
                                                onClick={fetchSetups}
                                                loading={setupsLoading}
                                                data-testid="refresh-setups-btn"
                                            >
                                                Refresh
                                            </Button>
                                        </Space>
                                    }
                                    rules={[{ required: true, message: 'Please select setup' }]}
                                >
                                    <Select
                                        placeholder="Select setup"
                                        loading={setupsLoading}
                                        notFoundContent={setupsLoading ? 'Loading...' : 'No setups found'}
                                    >
                                        {setups.map(setup => (
                                            <Select.Option key={setup.setupId} value={setup.setupId}>
                                                {setup.setupId} ({setup.schema})
                                            </Select.Option>
                                        ))}
                                    </Select>
                                </Form.Item>
                            </Col>
                        </Row>
                    </Form>
                </Modal>

                {/* Event Store Details Modal */}
                <Modal
                    title={
                        <Space>
                            <DatabaseOutlined />
                            <span>Event Store Details</span>
                            {selectedEventStore && (
                                <Tag color={getStatusColor(selectedEventStore.status)}>
                                    {selectedEventStore.status.toUpperCase()}
                                </Tag>
                            )}
                        </Space>
                    }
                    open={isEventStoreDetailsModalVisible}
                    onCancel={() => setIsEventStoreDetailsModalVisible(false)}
                    width={800}
                    footer={[
                        <Button key="close" onClick={() => setIsEventStoreDetailsModalVisible(false)}>
                            Close
                        </Button>
                    ]}
                >
                    {selectedEventStore && (
                        <Space direction="vertical" size="large" style={{ width: '100%' }}>
                            {/* Event Store Information */}
                            <Card size="small" title="Event Store Information">
                                <Descriptions column={2} size="small">
                                    <Descriptions.Item label="Name">
                                        <Text strong>{selectedEventStore.name}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Setup">
                                        <Tag color="blue">{selectedEventStore.setupId}</Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Status">
                                        <Tag color={getStatusColor(selectedEventStore.status)} icon={getStatusIcon(selectedEventStore.status)}>
                                            {selectedEventStore.status.toUpperCase()}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Created">
                                        <Text>{dayjs(selectedEventStore.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Last Event">
                                        <Text>
                                            {selectedEventStore.lastEventAt
                                                ? dayjs(selectedEventStore.lastEventAt).format('YYYY-MM-DD HH:mm:ss')
                                                : 'No events yet'
                                            }
                                        </Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Storage Size">
                                        <Text>{(selectedEventStore.storageSize / 1024 / 1024).toFixed(2)} MB</Text>
                                    </Descriptions.Item>
                                </Descriptions>
                            </Card>

                            {/* Statistics */}
                            <Row gutter={[16, 16]}>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Events"
                                            value={selectedEventStore.eventCount}
                                            prefix={<FileTextOutlined />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Streams"
                                            value={selectedEventStore.streamCount}
                                            prefix={<BranchesOutlined />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Corrections"
                                            value={selectedEventStore.correctionCount}
                                            prefix={<HistoryOutlined />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Event Types"
                                            value={selectedEventStore.eventTypes.length}
                                            prefix={<ApiOutlined />}
                                        />
                                    </Card>
                                </Col>
                            </Row>

                            {/* Aggregate Types */}
                            <Card size="small" title="Aggregate Types">
                                <Space wrap>
                                    {selectedEventStore.aggregateTypes.map(type => (
                                        <Tag key={type} color="purple">{type}</Tag>
                                    ))}
                                    {selectedEventStore.aggregateTypes.length === 0 && (
                                        <Text type="secondary">No aggregate types yet</Text>
                                    )}
                                </Space>
                            </Card>

                            {/* Event Types */}
                            <Card size="small" title="Event Types">
                                <Space wrap>
                                    {selectedEventStore.eventTypes.map(type => (
                                        <Tag key={type} color="cyan">{type}</Tag>
                                    ))}
                                    {selectedEventStore.eventTypes.length === 0 && (
                                        <Text type="secondary">No event types yet</Text>
                                    )}
                                </Space>
                            </Card>
                        </Space>
                    )}
                </Modal>
            </Space>
        </div>
    )
}

export default EventStores
