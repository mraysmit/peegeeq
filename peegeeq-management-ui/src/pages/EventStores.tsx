import { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
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
    Badge,
    Typography,
    Descriptions,
    DatePicker,
    message,
    Tabs
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
    LinkOutlined,
    ClockCircleOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    FileTextOutlined,
    DownloadOutlined,
    ApiOutlined,
    UpOutlined,
    DownOutlined
} from '@ant-design/icons'

import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { Text, Title } = Typography
const { RangePicker } = DatePicker

const { TabPane } = Tabs

interface EventStoreEvent {
    key: string
    id: string
    eventType: string
    eventData: any
    validFrom: string
    validTo?: string
    transactionTime: string
    correlationId?: string
    causationId?: string
    version: number
    metadata: Record<string, any>
    aggregateId?: string
    aggregateType?: string
    streamId?: string
    eventNumber: number
}

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
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [setups, setSetups] = useState<DatabaseSetup[]>([])
    const [events, setEvents] = useState<EventStoreEvent[]>([])
    const [filteredEvents, setFilteredEvents] = useState<EventStoreEvent[]>([])
    const [selectedEventStore, setSelectedEventStore] = useState<EventStore | null>(null)
    const [selectedEvent, setSelectedEvent] = useState<EventStoreEvent | null>(null)
    const [isCreateModalVisible, setIsCreateModalVisible] = useState(false)
    const [isEventDetailsModalVisible, setIsEventDetailsModalVisible] = useState(false)
    const [isEventStoreDetailsModalVisible, setIsEventStoreDetailsModalVisible] = useState(false)
    const [loading, setLoading] = useState(true)
    const [setupsLoading, setSetupsLoading] = useState(false)
    const [eventsLoading, setEventsLoading] = useState(false)
    const [activeTab, setActiveTab] = useState('stores')
    const [form] = Form.useForm()
    const [postEventForm] = Form.useForm()
    const [showAdvanced, setShowAdvanced] = useState(false)
    const [postingEvent, setPostingEvent] = useState(false)
    const [selectedSetupForEvents, setSelectedSetupForEvents] = useState<string>('')
    const [selectedEventStoreForQuery, setSelectedEventStoreForQuery] = useState<string>('')

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

    const fetchEvents = async (setupId: string, eventStoreName: string) => {
        setEventsLoading(true)
        try {
            const response = await axios.get(
                getVersionedApiUrl(`eventstores/${setupId}/${eventStoreName}/events?limit=1000`)
            )

            if (response.data && response.data.events && Array.isArray(response.data.events)) {
                const fetchedEvents: EventStoreEvent[] = response.data.events.map((event: any, index: number) => ({
                    key: event.eventId || `event-${index}`,
                    id: event.eventId,
                    eventType: event.eventType,
                    eventData: event.eventData,
                    validFrom: event.validTime || event.validFrom,
                    validTo: event.validTo,
                    transactionTime: event.transactionTime,
                    correlationId: event.correlationId,
                    causationId: event.causationId,
                    version: event.version || 1,
                    metadata: event.metadata || event.headers || {},
                    aggregateId: event.aggregateId,
                    aggregateType: event.aggregateType,
                    streamId: event.streamId,
                    eventNumber: index + 1
                }))

                setEvents(fetchedEvents)
                message.success(`Loaded ${fetchedEvents.length} events from ${eventStoreName}`)
            } else {
                setEvents([])
                message.info('No events found')
            }
        } catch (error: any) {
            console.error('Failed to fetch events:', error)
            const errorMessage = error.response?.data?.error || error.response?.data?.message || error.message
            message.error(`Failed to load events: ${errorMessage}`)
            setEvents([])
        } finally {
            setEventsLoading(false)
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

    // Filters for events
    const [eventTypeFilter, setEventTypeFilter] = useState<string>('')
    const [aggregateTypeFilter, setAggregateTypeFilter] = useState<string>('')
    const [correlationIdFilter, setCorrelationIdFilter] = useState<string>('')
    const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)

    // Filter events based on current filters
    useEffect(() => {
        let filtered = events

        if (eventTypeFilter) {
            filtered = filtered.filter(event =>
                event.eventType.toLowerCase().includes(eventTypeFilter.toLowerCase())
            )
        }

        if (aggregateTypeFilter) {
            filtered = filtered.filter(event =>
                event.aggregateType?.toLowerCase().includes(aggregateTypeFilter.toLowerCase())
            )
        }

        if (correlationIdFilter) {
            filtered = filtered.filter(event =>
                event.correlationId?.toLowerCase().includes(correlationIdFilter.toLowerCase()) ||
                event.causationId?.toLowerCase().includes(correlationIdFilter.toLowerCase())
            )
        }

        if (dateRange) {
            filtered = filtered.filter(event => {
                const eventDate = dayjs(event.transactionTime)
                return eventDate.isAfter(dateRange[0]) && eventDate.isBefore(dateRange[1])
            })
        }

        setFilteredEvents(filtered)
    }, [events, eventTypeFilter, aggregateTypeFilter, correlationIdFilter, dateRange])

    const handleCreateEventStore = () => {
        form.resetFields()
        fetchSetups() // Refresh setups when opening modal
        setIsCreateModalVisible(true)
    }

    const handleViewEventStoreDetails = (eventStore: EventStore) => {
        setSelectedEventStore(eventStore)
        setIsEventStoreDetailsModalVisible(true)
    }

    const handleViewEventDetails = (event: EventStoreEvent) => {
        setSelectedEvent(event)
        setIsEventDetailsModalVisible(true)
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
                    await fetchEventStores()
                } catch (error: any) {
                    console.error('Error deleting event store:', error)
                    message.error(`Failed to delete event store: ${error.response?.data?.error || error.message}`)
                }
            }
        })
    }

    const handlePostEvent = async () => {
        try {
            const values = await postEventForm.validateFields()
            setPostingEvent(true)

            // Parse JSON fields
            let eventData
            try {
                eventData = JSON.parse(values.eventData)
            } catch (e) {
                message.error('Event Data must be valid JSON')
                setPostingEvent(false)
                return
            }

            let metadata
            if (values.metadata) {
                try {
                    metadata = JSON.parse(values.metadata)
                } catch (e) {
                    message.error('Metadata must be valid JSON')
                    setPostingEvent(false)
                    return
                }
            }

            // Prepare request payload
            const payload: any = {
                eventType: values.eventType,
                eventData: eventData
            }

            if (values.validTime) {
                payload.validFrom = values.validTime
            }

            if (values.aggregateId) {
                payload.aggregateId = values.aggregateId
            }

            if (values.correlationId) {
                payload.correlationId = values.correlationId
            }

            if (values.causationId) {
                payload.causationId = values.causationId
            }

            if (metadata) {
                payload.metadata = metadata
            }

            // Post to API
            const response = await axios.post(
                getVersionedApiUrl(`eventstores/${values.setupId}/${values.eventStoreName}/events`),
                payload
            )

            message.success(
                `Event '${values.eventType}' posted successfully to '${values.eventStoreName}' (ID: ${response.data.eventId})`
            )

            // Clear form
            postEventForm.resetFields()
            setShowAdvanced(false)

            // Refresh events table (if we implement event fetching)
            // await fetchEvents()

        } catch (error: any) {
            console.error('Error posting event:', error)
            const errorMessage = error.response?.data?.error || error.response?.data?.message || error.message
            message.error(`Failed to post event: ${errorMessage}`)
        } finally {
            setPostingEvent(false)
        }
    }

    const handleClearEventForm = () => {
        postEventForm.resetFields()
        setShowAdvanced(false)
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
                onClick: () => {
                    setActiveTab('events')
                    // Could add filtering by event store
                },
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

    const eventColumns = [
        {
            title: 'Event ID',
            dataIndex: 'id',
            key: 'id',
            width: 120,
            render: (text: string) => <Text code>{text}</Text>
        },
        {
            title: 'Event Type',
            dataIndex: 'eventType',
            key: 'eventType',
            width: 150,
            render: (text: string) => <Tag color="purple">{text}</Tag>
        },
        {
            title: 'Aggregate',
            key: 'aggregate',
            width: 150,
            render: (record: EventStoreEvent) => (
                <Space direction="vertical" size="small">
                    <Tooltip title={`Filter by Aggregate Type: ${record.aggregateType}`}>
                        <Tag 
                            color="cyan" 
                            style={{ cursor: 'pointer' }}
                            onClick={() => setAggregateTypeFilter(record.aggregateType || '')}
                        >
                            {record.aggregateType}
                        </Tag>
                    </Tooltip>
                    <Text code style={{ fontSize: '11px' }}>{record.aggregateId}</Text>
                </Space>
            )
        },
        {
            title: 'Version',
            dataIndex: 'version',
            key: 'version',
            width: 80,
            render: (version: number) => <Badge count={version} style={{ backgroundColor: '#52c41a' }} />
        },
        {
            title: 'Bi-temporal',
            key: 'temporal',
            width: 180,
            render: (record: EventStoreEvent) => (
                <Space direction="vertical" size="small">
                    <div>
                        <Text strong style={{ fontSize: '11px' }}>Valid:</Text>
                        <Text style={{ fontSize: '11px', marginLeft: 4 }}>
                            {dayjs(record.validFrom).format('MM/DD HH:mm')}
                            {record.validTo && ` - ${dayjs(record.validTo).format('MM/DD HH:mm')}`}
                        </Text>
                    </div>
                    <div>
                        <Text strong style={{ fontSize: '11px' }}>Transaction:</Text>
                        <Text style={{ fontSize: '11px', marginLeft: 4 }}>
                            {dayjs(record.transactionTime).format('MM/DD HH:mm:ss')}
                        </Text>
                    </div>
                </Space>
            )
        },
        {
            title: 'Correlation',
            key: 'correlation',
            width: 120,
            render: (record: EventStoreEvent) => (
                <Space direction="vertical" size="small">
                    {record.correlationId && (
                        <Tooltip title={`Filter by Correlation ID: ${record.correlationId}`}>
                            <Text 
                                code 
                                style={{ fontSize: '10px', cursor: 'pointer', color: '#1890ff' }}
                                onClick={() => setCorrelationIdFilter(record.correlationId!)}
                            >
                                <LinkOutlined /> {record.correlationId.slice(-8)}
                            </Text>
                        </Tooltip>
                    )}
                    {record.causationId && (
                        <Tooltip title={`Filter by Causation ID: ${record.causationId}`}>
                            <Text 
                                code 
                                style={{ fontSize: '10px', cursor: 'pointer', color: '#1890ff' }}
                                onClick={() => setCorrelationIdFilter(record.causationId!)}
                            >
                                <BranchesOutlined /> {record.causationId.slice(-8)}
                            </Text>
                        </Tooltip>
                    )}
                </Space>
            )
        },
        {
            title: 'Actions',
            key: 'actions',
            width: 100,
            render: (record: EventStoreEvent) => (
                <Space>
                    <Tooltip title="View Details">
                        <Button
                            type="text"
                            icon={<EyeOutlined />}
                            onClick={() => handleViewEventDetails(record)}
                        />
                    </Tooltip>
                </Space>
            )
        }
    ]

    // Calculate summary statistics
    const totalEventStores = eventStores.length
    const activeEventStores = eventStores.filter(es => es.status === 'active').length
    const totalEvents = eventStores.reduce((sum, es) => sum + es.eventCount, 0)
    const totalStorage = eventStores.reduce((sum, es) => sum + es.storageSize, 0)

    // Calculate unique aggregates from loaded events
    const uniqueAggregates = new Set(
        events
            .filter(event => event.aggregateId)
            .map(event => event.aggregateId)
    ).size

    return (
        <div className="fade-in">
            <Title level={1}>Event Stores</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
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
                                title="Unique Aggregates"
                                value={uniqueAggregates}
                                prefix={<BranchesOutlined style={{ color: '#13c2c2' }} />}
                                valueStyle={{ color: uniqueAggregates > 0 ? '#13c2c2' : '#8c8c8c' }}
                            />
                        </Card>
                    </Col>
                </Row>

                <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
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

                {/* Main Content Tabs */}
                <Card>
                    <Tabs
                        activeKey={activeTab}
                        onChange={setActiveTab}
                        tabBarExtraContent={
                            <Space>
                                <Button
                                    icon={<ReloadOutlined />}
                                    loading={loading}
                                    onClick={() => fetchEventStores()}
                                >
                                    Refresh
                                </Button>
                                {activeTab === 'stores' && (
                                    <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateEventStore}>
                                        Create Event Store
                                    </Button>
                                )}
                            </Space>
                        }
                    >
                        <TabPane tab={`Event Stores (${eventStores.length})`} key="stores">
                            <Table
                                columns={eventStoreColumns}
                                dataSource={eventStores}
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
                        </TabPane>

                        <TabPane tab={`Events (${filteredEvents.length})`} key="events">
                            {/* Post Event Form */}
                            <Card
                                title="Post Event"
                                size="small"
                                style={{ marginBottom: 16 }}
                                extra={
                                    <Space>
                                        <Button
                                            icon={showAdvanced ? <UpOutlined /> : <DownOutlined />}
                                            onClick={() => setShowAdvanced(!showAdvanced)}
                                            size="small"
                                        >
                                            {showAdvanced ? 'Hide' : 'Show'} Advanced
                                        </Button>
                                    </Space>
                                }
                            >
                                <Form form={postEventForm} layout="vertical">
                                    <Row gutter={16}>
                                        <Col xs={24} sm={12} md={8}>
                                            <Form.Item
                                                name="setupId"
                                                label="Setup"
                                                rules={[{ required: true, message: 'Please select setup' }]}
                                            >
                                                <Select placeholder="Select setup" loading={setupsLoading}>
                                                    {setups.map(setup => (
                                                        <Select.Option key={setup.setupId} value={setup.setupId}>
                                                            {setup.setupId}
                                                        </Select.Option>
                                                    ))}
                                                </Select>
                                            </Form.Item>
                                        </Col>
                                        <Col xs={24} sm={12} md={8}>
                                            <Form.Item
                                                name="eventStoreName"
                                                label="Event Store"
                                                rules={[{ required: true, message: 'Please select event store' }]}
                                            >
                                                <Select placeholder="Select event store" loading={loading}>
                                                    {eventStores.map(store => (
                                                        <Select.Option key={store.key} value={store.name}>
                                                            {store.name} ({store.setupId})
                                                        </Select.Option>
                                                    ))}
                                                </Select>
                                            </Form.Item>
                                        </Col>
                                        <Col xs={24} sm={12} md={8}>
                                            <Form.Item
                                                name="eventType"
                                                label="Event Type"
                                                rules={[{ required: true, message: 'Please enter event type' }]}
                                            >
                                                <Input placeholder="e.g., OrderCreated" />
                                            </Form.Item>
                                        </Col>
                                    </Row>

                                    <Form.Item
                                        name="eventData"
                                        label="Event Data (JSON)"
                                        rules={[
                                            { required: true, message: 'Please enter event data' },
                                            {
                                                validator: async (_, value) => {
                                                    if (value) {
                                                        try {
                                                            JSON.parse(value)
                                                        } catch (e) {
                                                            throw new Error('Must be valid JSON')
                                                        }
                                                    }
                                                }
                                            }
                                        ]}
                                    >
                                        <Input.TextArea
                                            rows={4}
                                            placeholder='{"orderId": "ORD-12345", "customerId": "CUST-001", "amount": 99.99}'
                                        />
                                    </Form.Item>

                                    {showAdvanced && (
                                        <>
                                            <Card type="inner" title="Temporal (Optional)" size="small" style={{ marginBottom: 16 }}>
                                                <Form.Item
                                                    name="validTime"
                                                    label="Valid Time"
                                                    tooltip="Business time - when the event actually happened (defaults to now)"
                                                >
                                                    <DatePicker
                                                        showTime
                                                        style={{ width: '100%' }}
                                                        placeholder="Select valid time"
                                                        format="YYYY-MM-DD HH:mm:ss"
                                                    />
                                                </Form.Item>
                                            </Card>

                                            <Card type="inner" title="Event Sourcing (Optional)" size="small" style={{ marginBottom: 16 }}>
                                                <Row gutter={16}>
                                                    <Col xs={24} sm={8}>
                                                        <Form.Item
                                                            name="aggregateId"
                                                            label="Aggregate ID"
                                                            tooltip="Groups related events together"
                                                        >
                                                            <Input placeholder="e.g., order-12345" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} sm={8}>
                                                        <Form.Item
                                                            name="correlationId"
                                                            label="Correlation ID"
                                                            tooltip="Tracks event flow across services"
                                                        >
                                                            <Input placeholder="e.g., corr-workflow-567" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} sm={8}>
                                                        <Form.Item
                                                            name="causationId"
                                                            label="Causation ID"
                                                            tooltip="What caused this event"
                                                        >
                                                            <Input placeholder="e.g., evt-user-action-123" />
                                                        </Form.Item>
                                                    </Col>
                                                </Row>
                                            </Card>

                                            <Card type="inner" title="Metadata (Optional)" size="small" style={{ marginBottom: 16 }}>
                                                <Form.Item
                                                    name="metadata"
                                                    label="Headers (JSON)"
                                                    rules={[
                                                        {
                                                            validator: async (_, value) => {
                                                                if (value) {
                                                                    try {
                                                                        JSON.parse(value)
                                                                    } catch (e) {
                                                                        throw new Error('Must be valid JSON')
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    ]}
                                                >
                                                    <Input.TextArea
                                                        rows={3}
                                                        placeholder='{"userId": "user-123", "source": "web-ui"}'
                                                    />
                                                </Form.Item>
                                            </Card>
                                        </>
                                    )}

                                    <Form.Item style={{ marginBottom: 0 }}>
                                        <Space>
                                            <Button onClick={handleClearEventForm}>
                                                Clear Form
                                            </Button>
                                            <Button
                                                type="primary"
                                                icon={<PlusOutlined />}
                                                onClick={handlePostEvent}
                                                loading={postingEvent}
                                            >
                                                Post Event
                                            </Button>
                                        </Space>
                                    </Form.Item>
                                </Form>
                            </Card>

                            {/* Event Filters */}
                            <Card size="small" style={{ marginBottom: 16 }} title="Query Events">
                                <Row gutter={[16, 16]}>
                                    <Col xs={24} sm={12} md={8}>
                                        <Select
                                            data-testid="query-setup-select"
                                            placeholder="Select setup to view events"
                                            style={{ width: '100%' }}
                                            value={selectedSetupForEvents}
                                            onChange={(value) => {
                                                setSelectedSetupForEvents(value)
                                                setSelectedEventStoreForQuery('')
                                                setEvents([])
                                            }}
                                            allowClear
                                        >
                                            {setups.map(setup => (
                                                <Select.Option key={setup.setupId} value={setup.setupId}>
                                                    {setup.setupId}
                                                </Select.Option>
                                            ))}
                                        </Select>
                                    </Col>
                                    <Col xs={24} sm={12} md={8}>
                                        <Select
                                            data-testid="query-eventstore-select"
                                            placeholder="Select event store"
                                            style={{ width: '100%' }}
                                            value={selectedEventStoreForQuery}
                                            onChange={(value) => setSelectedEventStoreForQuery(value)}
                                            disabled={!selectedSetupForEvents}
                                            allowClear
                                        >
                                            {eventStores
                                                .filter(store => store.setupId === selectedSetupForEvents)
                                                .map(store => (
                                                    <Select.Option key={store.key} value={store.name}>
                                                        {store.name} ({store.eventCount} events)
                                                    </Select.Option>
                                                ))}
                                        </Select>
                                    </Col>
                                    <Col xs={24} sm={24} md={8}>
                                        <Space>
                                            <Button
                                                type="primary"
                                                icon={<SearchOutlined />}
                                                onClick={() => {
                                                    if (selectedSetupForEvents && selectedEventStoreForQuery) {
                                                        fetchEvents(selectedSetupForEvents, selectedEventStoreForQuery)
                                                    } else {
                                                        message.warning('Please select both setup and event store')
                                                    }
                                                }}
                                                loading={eventsLoading}
                                                disabled={!selectedSetupForEvents || !selectedEventStoreForQuery}
                                            >
                                                Load Events
                                            </Button>
                                            <Button
                                                icon={<ReloadOutlined />}
                                                onClick={() => {
                                                    if (selectedSetupForEvents && selectedEventStoreForQuery) {
                                                        fetchEvents(selectedSetupForEvents, selectedEventStoreForQuery)
                                                    }
                                                }}
                                                disabled={!selectedSetupForEvents || !selectedEventStoreForQuery}
                                            >
                                                Refresh
                                            </Button>
                                        </Space>
                                    </Col>
                                </Row>
                            </Card>

                            {/* Event Filters */}
                            <Card size="small" style={{ marginBottom: 16 }} title="Filter Loaded Events">
                                <Row gutter={[16, 8]}>
                                    <Col xs={24} sm={12} md={6}>
                                        <Input
                                            placeholder="Event Type"
                                            value={eventTypeFilter}
                                            onChange={(e) => setEventTypeFilter(e.target.value)}
                                            prefix={<SearchOutlined />}
                                            allowClear
                                        />
                                    </Col>
                                    <Col xs={24} sm={12} md={6}>
                                        <Input
                                            placeholder="Aggregate Type"
                                            value={aggregateTypeFilter}
                                            onChange={(e) => setAggregateTypeFilter(e.target.value)}
                                            prefix={<BranchesOutlined />}
                                            allowClear
                                        />
                                    </Col>
                                    <Col xs={24} sm={12} md={6}>
                                        <Input
                                            placeholder="Correlation/Causation ID"
                                            value={correlationIdFilter}
                                            onChange={(e) => setCorrelationIdFilter(e.target.value)}
                                            prefix={<LinkOutlined />}
                                            allowClear
                                        />
                                    </Col>
                                    <Col xs={24} sm={12} md={6}>
                                        <RangePicker
                                            value={dateRange}
                                            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
                                            showTime
                                            style={{ width: '100%' }}
                                            placeholder={['Valid From', 'Valid To']}
                                        />
                                    </Col>
                                </Row>
                            </Card>

                            <Table
                                columns={eventColumns}
                                dataSource={filteredEvents}
                                pagination={{
                                    pageSize: 20,
                                    showSizeChanger: true,
                                    showQuickJumper: true,
                                    showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} events`,
                                }}
                                scroll={{ x: 1200 }}
                                size="small"
                                loading={eventsLoading}
                                locale={{
                                    emptyText: selectedSetupForEvents && selectedEventStoreForQuery
                                        ? 'No events found for the selected criteria. Click "Load Events" to fetch events.'
                                        : 'Please select setup and event store, then click "Load Events" to view events.'
                                }}
                                footer={() => (
                                    <div>
                                        <strong>Total Events: {events.length}</strong>
                                        {filteredEvents.length < events.length && (
                                            <span> (Showing {filteredEvents.length} filtered)</span>
                                        )}
                                    </div>
                                )}
                            />
                        </TabPane>
                    </Tabs>
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

                {/* Event Details Modal */}
                <Modal
                    title={
                        <Space>
                            <FileTextOutlined />
                            <span>Event Details</span>
                            {selectedEvent && (
                                <Tag color="purple">{selectedEvent.eventType}</Tag>
                            )}
                        </Space>
                    }
                    open={isEventDetailsModalVisible}
                    onCancel={() => setIsEventDetailsModalVisible(false)}
                    width={900}
                    footer={[
                        <Button key="download" icon={<DownloadOutlined />}>
                            Export
                        </Button>,
                        <Button key="close" onClick={() => setIsEventDetailsModalVisible(false)}>
                            Close
                        </Button>
                    ]}
                >
                    {selectedEvent && (
                        <Space direction="vertical" size="large" style={{ width: '100%' }}>
                            {/* Event Metadata */}
                            <Card size="small" title="Event Information">
                                <Row gutter={[16, 8]}>
                                    <Col span={12}>
                                        <Text strong>Event ID:</Text>
                                        <br />
                                        <Text code>{selectedEvent.id}</Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Event Type:</Text>
                                        <br />
                                        <Tag color="purple">{selectedEvent.eventType}</Tag>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Aggregate:</Text>
                                        <br />
                                        <Space>
                                            <Tag color="cyan">{selectedEvent.aggregateType}</Tag>
                                            <Text code style={{ fontSize: '11px' }}>{selectedEvent.aggregateId}</Text>
                                        </Space>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Version:</Text>
                                        <br />
                                        <Badge count={selectedEvent.version} style={{ backgroundColor: '#52c41a' }} />
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Stream:</Text>
                                        <br />
                                        <Text code>{selectedEvent.streamId}</Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Event Number:</Text>
                                        <br />
                                        <Text>{selectedEvent.eventNumber}</Text>
                                    </Col>
                                </Row>
                            </Card>

                            {/* Bi-temporal Information */}
                            <Card size="small" title="Bi-temporal Data">
                                <Row gutter={[16, 8]}>
                                    <Col span={12}>
                                        <Text strong>Valid From:</Text>
                                        <br />
                                        <Text>{dayjs(selectedEvent.validFrom).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Valid To:</Text>
                                        <br />
                                        <Text>
                                            {selectedEvent.validTo
                                                ? dayjs(selectedEvent.validTo).format('YYYY-MM-DD HH:mm:ss')
                                                : 'Current'
                                            }
                                        </Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Transaction Time:</Text>
                                        <br />
                                        <Text>{dayjs(selectedEvent.transactionTime).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Version:</Text>
                                        <br />
                                        <Badge count={selectedEvent.version} style={{ backgroundColor: '#52c41a' }} />
                                    </Col>
                                </Row>
                            </Card>

                            {/* Correlation Information */}
                            <Card size="small" title="Event Correlation">
                                <Row gutter={[16, 8]}>
                                    <Col span={12}>
                                        <Text strong>Correlation ID:</Text>
                                        <br />
                                        <Text code>{selectedEvent.correlationId || 'None'}</Text>
                                    </Col>
                                    <Col span={12}>
                                        <Text strong>Causation ID:</Text>
                                        <br />
                                        <Text code>{selectedEvent.causationId || 'None'}</Text>
                                    </Col>
                                </Row>
                            </Card>

                            {/* Event Metadata */}
                            <Card size="small" title="Metadata">
                                <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                                    {JSON.stringify(selectedEvent.metadata, null, 2)}
                                </pre>
                            </Card>

                            {/* Event Data */}
                            <Card size="small" title="Event Data">
                                <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                                    {JSON.stringify(selectedEvent.eventData, null, 2)}
                                </pre>
                            </Card>
                        </Space>
                    )}
                </Modal>
            </Space>
        </div>
    )
}

export default EventStores
