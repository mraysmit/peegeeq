import { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
import SetupScopeBar from '../components/common/SetupScopeBar'
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
    Tooltip,
    Badge,
    Typography,
    DatePicker,
    message
} from 'antd'
import {
    FileTextOutlined,
    PlusOutlined,
    SearchOutlined,
    ReloadOutlined,
    BranchesOutlined,
    LinkOutlined,
    DownloadOutlined,
    EyeOutlined,
    UpOutlined,
    DownOutlined
} from '@ant-design/icons'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { Text, Title } = Typography
const { RangePicker } = DatePicker

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
}

interface DatabaseSetup {
    setupId: string
    schema: string
}

const EventsPage = () => {
    const [events, setEvents] = useState<EventStoreEvent[]>([])
    const [filteredEvents, setFilteredEvents] = useState<EventStoreEvent[]>([])
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [setups, setSetups] = useState<DatabaseSetup[]>([])
    const [eventsLoading, setEventsLoading] = useState(false)
    const [setupsLoading, setSetupsLoading] = useState(false)
    const { selectedSetupId, setSelectedSetup } = useManagementStore()
    const [selectedEventStore, setSelectedEventStore] = useState<string>('')
    const [selectedEvent, setSelectedEvent] = useState<EventStoreEvent | null>(null)
    const [isEventDetailsModalVisible, setIsEventDetailsModalVisible] = useState(false)
    const [postEventForm] = Form.useForm()
    const [showAdvanced, setShowAdvanced] = useState(false)
    const [postingEvent, setPostingEvent] = useState(false)

    // Filter state
    const [eventTypeFilter, setEventTypeFilter] = useState<string>('')
    const [aggregateTypeFilter, setAggregateTypeFilter] = useState<string>('')
    const [correlationIdFilter, setCorrelationIdFilter] = useState<string>('')
    const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetups(response.data.setupIds.map((setupId: string) => ({
                    setupId,
                    schema: setupId
                })))
            } else {
                setSetups([])
            }
        } catch (error) {
            console.error('Failed to fetch setups:', error)
            message.error('Failed to load database setups')
            setSetups([])
        } finally {
            setSetupsLoading(false)
        }
    }

    const fetchEventStores = async () => {
        try {
            const response = await axios.get(getVersionedApiUrl('management/event-stores'))
            if (response.data.eventStores && Array.isArray(response.data.eventStores)) {
                setEventStores(response.data.eventStores.map((store: any) => ({
                    key: `${store.setup}-${store.name}`,
                    name: store.name,
                    setupId: store.setup,
                    eventCount: store.events || 0,
                })))
            } else {
                setEventStores([])
            }
        } catch (error) {
            console.error('Failed to fetch event stores:', error)
            setEventStores([])
        }
    }

    const fetchEvents = async (setupId: string, eventStoreName: string) => {
        setEventsLoading(true)
        try {
            const response = await axios.get(
                getVersionedApiUrl(`eventstores/${setupId}/${eventStoreName}/events?limit=1000`)
            )
            if (response.data && response.data.events && Array.isArray(response.data.events)) {
                const fetched: EventStoreEvent[] = response.data.events.map((event: any, index: number) => ({
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
                setEvents(fetched)
                message.success(`Loaded ${fetched.length} events from ${eventStoreName}`)
            } else {
                setEvents([])
                message.info('No events found')
            }
        } catch (error: any) {
            console.error('Failed to fetch events:', error)
            message.error(`Failed to load events: ${error.response?.data?.error || error.message}`)
            setEvents([])
        } finally {
            setEventsLoading(false)
        }
    }

    const handlePostEvent = async () => {
        try {
            const values = await postEventForm.validateFields()
            setPostingEvent(true)

            let eventData
            try {
                eventData = JSON.parse(values.eventData)
            } catch {
                message.error('Event Data must be valid JSON')
                setPostingEvent(false)
                return
            }

            let metadata
            if (values.metadata) {
                try {
                    metadata = JSON.parse(values.metadata)
                } catch {
                    message.error('Metadata must be valid JSON')
                    setPostingEvent(false)
                    return
                }
            }

            const payload: any = { eventType: values.eventType, eventData }
            if (values.validTime)    payload.validFrom     = values.validTime
            if (values.aggregateId)  payload.aggregateId   = values.aggregateId
            if (values.correlationId) payload.correlationId = values.correlationId
            if (values.causationId)  payload.causationId   = values.causationId
            if (metadata)            payload.metadata      = metadata

            const response = await axios.post(
                getVersionedApiUrl(`eventstores/${values.setupId}/${values.eventStoreName}/events`),
                payload
            )
            message.success(
                `Event '${values.eventType}' posted successfully to '${values.eventStoreName}' (ID: ${response.data.eventId})`
            )
            postEventForm.resetFields()
            setShowAdvanced(false)
        } catch (error: any) {
            console.error('Error posting event:', error)
            message.error(`Failed to post event: ${error.response?.data?.error || error.response?.data?.message || error.message}`)
        } finally {
            setPostingEvent(false)
        }
    }

    // Filter effect
    useEffect(() => {
        let filtered = events
        if (eventTypeFilter)
            filtered = filtered.filter(e => e.eventType.toLowerCase().includes(eventTypeFilter.toLowerCase()))
        if (aggregateTypeFilter)
            filtered = filtered.filter(e => e.aggregateType?.toLowerCase().includes(aggregateTypeFilter.toLowerCase()))
        if (correlationIdFilter)
            filtered = filtered.filter(e =>
                e.correlationId?.toLowerCase().includes(correlationIdFilter.toLowerCase()) ||
                e.causationId?.toLowerCase().includes(correlationIdFilter.toLowerCase())
            )
        if (dateRange)
            filtered = filtered.filter(e => {
                const d = dayjs(e.transactionTime)
                return d.isAfter(dateRange[0]) && d.isBefore(dateRange[1])
            })
        setFilteredEvents(filtered)
    }, [events, eventTypeFilter, aggregateTypeFilter, correlationIdFilter, dateRange])

    useEffect(() => {
        setSelectedEventStore('')
        setEvents([])
    }, [selectedSetupId])

    useEffect(() => {
        fetchSetups()
        fetchEventStores()
    }, [])

    const eventColumns = [
        {
            title: 'Event ID', dataIndex: 'id', key: 'id', width: 120,
            render: (text: string) => <Text code>{text}</Text>
        },
        {
            title: 'Event Type', dataIndex: 'eventType', key: 'eventType', width: 150,
            render: (text: string) => <Tag color="purple">{text}</Tag>
        },
        {
            title: 'Aggregate', key: 'aggregate', width: 150,
            render: (record: EventStoreEvent) => (
                <Space direction="vertical" size="small">
                    <Tooltip title={`Filter by Aggregate Type: ${record.aggregateType}`}>
                        <Tag color="cyan" style={{ cursor: 'pointer' }} onClick={() => setAggregateTypeFilter(record.aggregateType || '')}>
                            {record.aggregateType}
                        </Tag>
                    </Tooltip>
                    <Text code style={{ fontSize: '11px' }}>{record.aggregateId}</Text>
                </Space>
            )
        },
        {
            title: 'Version', dataIndex: 'version', key: 'version', width: 80,
            render: (version: number) => <Badge count={version} style={{ backgroundColor: '#52c41a' }} />
        },
        {
            title: 'Bi-temporal', key: 'temporal', width: 180,
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
            title: 'Correlation', key: 'correlation', width: 120,
            render: (record: EventStoreEvent) => (
                <Space direction="vertical" size="small">
                    {record.correlationId && (
                        <Tooltip title={`Filter by Correlation ID: ${record.correlationId}`}>
                            <Text code style={{ fontSize: '10px', cursor: 'pointer', color: '#1890ff' }}
                                onClick={() => setCorrelationIdFilter(record.correlationId!)}>
                                <LinkOutlined /> {record.correlationId.slice(-8)}
                            </Text>
                        </Tooltip>
                    )}
                    {record.causationId && (
                        <Tooltip title={`Filter by Causation ID: ${record.causationId}`}>
                            <Text code style={{ fontSize: '10px', cursor: 'pointer', color: '#1890ff' }}
                                onClick={() => setCorrelationIdFilter(record.causationId!)}>
                                <BranchesOutlined /> {record.causationId.slice(-8)}
                            </Text>
                        </Tooltip>
                    )}
                </Space>
            )
        },
        {
            title: 'Actions', key: 'actions', width: 100,
            render: (record: EventStoreEvent) => (
                <Space>
                    <Tooltip title="View Details">
                        <Button type="text" icon={<EyeOutlined />}
                            onClick={() => { setSelectedEvent(record); setIsEventDetailsModalVisible(true) }} />
                    </Tooltip>
                </Space>
            )
        }
    ]

    return (
        <div>
            <Title level={1}>Events</Title>
            <SetupScopeBar />
            <Space direction="vertical" size="large" style={{ width: '100%' }}>

                {/* Post Event */}
                <Card
                    title="Post Event"
                    size="small"
                    extra={
                        <Button
                            icon={showAdvanced ? <UpOutlined /> : <DownOutlined />}
                            onClick={() => setShowAdvanced(!showAdvanced)}
                            size="small"
                        >
                            {showAdvanced ? 'Hide' : 'Show'} Advanced
                        </Button>
                    }
                >
                    <Form form={postEventForm} layout="vertical">
                        <Row gutter={16}>
                            <Col xs={24} sm={12} md={8}>
                                <Form.Item name="setupId" label="Setup" rules={[{ required: true, message: 'Please select setup' }]}>
                                    <Select placeholder="Select setup" loading={setupsLoading}>
                                        {setups.map(s => (
                                            <Select.Option key={s.setupId} value={s.setupId}>{s.setupId}</Select.Option>
                                        ))}
                                    </Select>
                                </Form.Item>
                            </Col>
                            <Col xs={24} sm={12} md={8}>
                                <Form.Item name="eventStoreName" label="Event Store" rules={[{ required: true, message: 'Please select event store' }]}>
                                    <Select placeholder="Select event store">
                                        {eventStores.map(store => (
                                            <Select.Option key={store.key} value={store.name}>
                                                {store.name} ({store.setupId})
                                            </Select.Option>
                                        ))}
                                    </Select>
                                </Form.Item>
                            </Col>
                            <Col xs={24} sm={12} md={8}>
                                <Form.Item name="eventType" label="Event Type" rules={[{ required: true, message: 'Please enter event type' }]}>
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
                                        if (value) { try { JSON.parse(value) } catch { throw new Error('Must be valid JSON') } }
                                    }
                                }
                            ]}
                        >
                            <Input.TextArea rows={4} placeholder='{"orderId": "ORD-12345", "customerId": "CUST-001", "amount": 99.99}' />
                        </Form.Item>

                        {showAdvanced && (
                            <>
                                <Card type="inner" title="Temporal (Optional)" size="small" style={{ marginBottom: 16 }}>
                                    <Form.Item name="validTime" label="Valid Time"
                                        tooltip="Business time - when the event actually happened (defaults to now)">
                                        <DatePicker showTime style={{ width: '100%' }} placeholder="Select valid time" format="YYYY-MM-DD HH:mm:ss" />
                                    </Form.Item>
                                </Card>
                                <Card type="inner" title="Event Sourcing (Optional)" size="small" style={{ marginBottom: 16 }}>
                                    <Row gutter={16}>
                                        <Col xs={24} sm={8}>
                                            <Form.Item name="aggregateId" label="Aggregate ID" tooltip="Groups related events together">
                                                <Input placeholder="e.g., order-12345" />
                                            </Form.Item>
                                        </Col>
                                        <Col xs={24} sm={8}>
                                            <Form.Item name="correlationId" label="Correlation ID" tooltip="Tracks event flow across services">
                                                <Input placeholder="e.g., corr-workflow-567" />
                                            </Form.Item>
                                        </Col>
                                        <Col xs={24} sm={8}>
                                            <Form.Item name="causationId" label="Causation ID" tooltip="What caused this event">
                                                <Input placeholder="e.g., evt-user-action-123" />
                                            </Form.Item>
                                        </Col>
                                    </Row>
                                </Card>
                                <Card type="inner" title="Metadata (Optional)" size="small" style={{ marginBottom: 16 }}>
                                    <Form.Item
                                        name="metadata"
                                        label="Headers (JSON)"
                                        rules={[{
                                            validator: async (_, value) => {
                                                if (value) { try { JSON.parse(value) } catch { throw new Error('Must be valid JSON') } }
                                            }
                                        }]}
                                    >
                                        <Input.TextArea rows={3} placeholder='{"userId": "user-123", "source": "web-ui"}' />
                                    </Form.Item>
                                </Card>
                            </>
                        )}

                        <Form.Item style={{ marginBottom: 0 }}>
                            <Space>
                                <Button onClick={() => { postEventForm.resetFields(); setShowAdvanced(false) }}>
                                    Clear Form
                                </Button>
                                <Button type="primary" icon={<PlusOutlined />} onClick={handlePostEvent} loading={postingEvent}>
                                    Post Event
                                </Button>
                            </Space>
                        </Form.Item>
                    </Form>
                </Card>

                {/* Query Events */}
                <Card size="small" title="Query Events">
                    <Row gutter={[16, 16]}>
                        <Col xs={24} sm={12} md={8}>
                            <Select
                                data-testid="query-setup-select"
                                placeholder="Select setup"
                                style={{ width: '100%' }}
                                value={selectedSetupId || undefined}
                                onChange={(value) => setSelectedSetup(value)}
                                loading={setupsLoading}
                                allowClear
                            >
                                {setups.map(s => (
                                    <Select.Option key={s.setupId} value={s.setupId}>{s.setupId}</Select.Option>
                                ))}
                            </Select>
                        </Col>
                        <Col xs={24} sm={12} md={8}>
                            <Select
                                data-testid="query-eventstore-select"
                                placeholder="Select event store"
                                style={{ width: '100%' }}
                                value={selectedEventStore || undefined}
                                onChange={(value) => setSelectedEventStore(value)}
                                disabled={!selectedSetupId}
                                allowClear
                            >
                                {eventStores
                                    .filter(store => store.setupId === selectedSetupId)
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
                                        if (selectedSetupId && selectedEventStore) {
                                            fetchEvents(selectedSetupId, selectedEventStore)
                                        } else {
                                            message.warning('Please select both setup and event store')
                                        }
                                    }}
                                    loading={eventsLoading}
                                    disabled={!selectedSetupId || !selectedEventStore}
                                >
                                    Load Events
                                </Button>
                                <Button
                                    icon={<ReloadOutlined />}
                                    onClick={() => { if (selectedSetupId && selectedEventStore) fetchEvents(selectedSetupId, selectedEventStore) }}
                                    disabled={!selectedSetupId || !selectedEventStore}
                                >
                                    Refresh
                                </Button>
                            </Space>
                        </Col>
                    </Row>
                </Card>

                {/* Filter Loaded Events */}
                <Card size="small" title="Filter Loaded Events">
                    <Row gutter={[16, 8]}>
                        <Col xs={24} sm={12} md={6}>
                            <Input placeholder="Event Type" value={eventTypeFilter}
                                onChange={e => setEventTypeFilter(e.target.value)}
                                prefix={<SearchOutlined />} allowClear />
                        </Col>
                        <Col xs={24} sm={12} md={6}>
                            <Input placeholder="Aggregate Type" value={aggregateTypeFilter}
                                onChange={e => setAggregateTypeFilter(e.target.value)}
                                prefix={<BranchesOutlined />} allowClear />
                        </Col>
                        <Col xs={24} sm={12} md={6}>
                            <Input placeholder="Correlation/Causation ID" value={correlationIdFilter}
                                onChange={e => setCorrelationIdFilter(e.target.value)}
                                prefix={<LinkOutlined />} allowClear />
                        </Col>
                        <Col xs={24} sm={12} md={6}>
                            <RangePicker
                                value={dateRange}
                                onChange={dates => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
                                showTime
                                style={{ width: '100%' }}
                                placeholder={['Valid From', 'Valid To']}
                            />
                        </Col>
                    </Row>
                </Card>

                {/* Events Table */}
                <Card title={`Events (${filteredEvents.length})`}>
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
                            emptyText: selectedSetupId && selectedEventStore
                                ? 'No events found. Click "Load Events" to fetch events.'
                                : 'Select setup and event store, then click "Load Events".'
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
                </Card>
            </Space>

            {/* Event Details Modal */}
            <Modal
                title={
                    <Space>
                        <FileTextOutlined />
                        <span>Event Details</span>
                        {selectedEvent && <Tag color="purple">{selectedEvent.eventType}</Tag>}
                    </Space>
                }
                open={isEventDetailsModalVisible}
                onCancel={() => setIsEventDetailsModalVisible(false)}
                width={900}
                footer={[
                    <Button key="download" icon={<DownloadOutlined />}>Export</Button>,
                    <Button key="close" onClick={() => setIsEventDetailsModalVisible(false)}>Close</Button>
                ]}
            >
                {selectedEvent && (
                    <Space direction="vertical" size="large" style={{ width: '100%' }}>
                        <Card size="small" title="Event Information">
                            <Row gutter={[16, 8]}>
                                <Col span={12}>
                                    <Text strong>Event ID:</Text><br />
                                    <Text code>{selectedEvent.id}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Event Type:</Text><br />
                                    <Tag color="purple">{selectedEvent.eventType}</Tag>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Aggregate:</Text><br />
                                    <Space>
                                        <Tag color="cyan">{selectedEvent.aggregateType}</Tag>
                                        <Text code style={{ fontSize: '11px' }}>{selectedEvent.aggregateId}</Text>
                                    </Space>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Version:</Text><br />
                                    <Badge count={selectedEvent.version} style={{ backgroundColor: '#52c41a' }} />
                                </Col>
                                <Col span={12}>
                                    <Text strong>Stream:</Text><br />
                                    <Text code>{selectedEvent.streamId}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Event Number:</Text><br />
                                    <Text>{selectedEvent.eventNumber}</Text>
                                </Col>
                            </Row>
                        </Card>
                        <Card size="small" title="Bi-temporal Data">
                            <Row gutter={[16, 8]}>
                                <Col span={12}>
                                    <Text strong>Valid From:</Text><br />
                                    <Text>{dayjs(selectedEvent.validFrom).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Valid To:</Text><br />
                                    <Text>{selectedEvent.validTo ? dayjs(selectedEvent.validTo).format('YYYY-MM-DD HH:mm:ss') : 'Current'}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Transaction Time:</Text><br />
                                    <Text>{dayjs(selectedEvent.transactionTime).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Version:</Text><br />
                                    <Badge count={selectedEvent.version} style={{ backgroundColor: '#52c41a' }} />
                                </Col>
                            </Row>
                        </Card>
                        <Card size="small" title="Event Correlation">
                            <Row gutter={[16, 8]}>
                                <Col span={12}>
                                    <Text strong>Correlation ID:</Text><br />
                                    <Text code>{selectedEvent.correlationId || 'None'}</Text>
                                </Col>
                                <Col span={12}>
                                    <Text strong>Causation ID:</Text><br />
                                    <Text code>{selectedEvent.causationId || 'None'}</Text>
                                </Col>
                            </Row>
                        </Card>
                        <Card size="small" title="Metadata">
                            <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                                {JSON.stringify(selectedEvent.metadata, null, 2)}
                            </pre>
                        </Card>
                        <Card size="small" title="Event Data">
                            <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                                {JSON.stringify(selectedEvent.eventData, null, 2)}
                            </pre>
                        </Card>
                    </Space>
                )}
            </Modal>
        </div>
    )
}

export default EventsPage
