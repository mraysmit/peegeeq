import { useState, useEffect } from 'react'
import axios from 'axios'
import { useNavigate } from 'react-router-dom'
import { getVersionedApiUrl } from '../services/configService'
import {
    Alert,
    Card,
    Select,
    Row,
    Col,
    Input,
    Button,
    Table,
    Space,
    Tag,
    Typography,
    Empty,
    Drawer,
    Descriptions,
    Badge,
    message
} from 'antd'
import { BranchesOutlined, DatabaseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { peeGeeQClient } from '../api/PeeGeeQClient'
import { AggregateInfo, BiTemporalEvent } from '../api/types'

const { Text, Title } = Typography

const AGGREGATE_PAGE_SIZE = 1000

interface EventStore {
    key: string
    name: string
    setupId: string
}

const AggregateStreamPage = () => {
    const navigate = useNavigate()

    const [setupIds, setSetupIds] = useState<string[]>([])
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [selectedSetupId, setSelectedSetupId] = useState<string>('')
    const [selectedEventStore, setSelectedEventStore] = useState<string>('')
    const [setupsLoading, setSetupsLoading] = useState(false)

    const [aggregates, setAggregates] = useState<AggregateInfo[]>([])
    const [aggregatesTotalCount, setAggregatesTotalCount] = useState(0)
    const [aggregatesTruncated, setAggregatesTruncated] = useState(false)
    const [aggregatesOffset, setAggregatesOffset] = useState(0)
    const [selectedAggregate, setSelectedAggregate] = useState<string | null>(null)
    const [aggregateEvents, setAggregateEvents] = useState<BiTemporalEvent[]>([])
    const [eventsTruncated, setEventsTruncated] = useState(false)
    const [aggregatesLoading, setAggregatesLoading] = useState(false)
    const [aggregateEventsLoading, setAggregateEventsLoading] = useState(false)
    const [eventTypeFilter, setEventTypeFilter] = useState<string>('')

    const [selectedEvent, setSelectedEvent] = useState<BiTemporalEvent | null>(null)
    const [drawerVisible, setDrawerVisible] = useState(false)

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetupIds(response.data.setupIds)
            } else {
                setSetupIds([])
            }
        } catch (error) {
            console.error('Failed to fetch setups:', error)
            setSetupIds([])
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
                })))
            } else {
                setEventStores([])
            }
        } catch (error) {
            console.error('Failed to fetch event stores:', error)
            setEventStores([])
        }
    }

    /** Fetch aggregates. Pass append=true to load the next page and add to the existing list. */
    const fetchAggregates = async (append = false) => {
        if (!selectedSetupId || !selectedEventStore) return
        const nextOffset = append ? aggregatesOffset + AGGREGATE_PAGE_SIZE : 0
        setAggregatesLoading(true)
        try {
            const response = await peeGeeQClient.getUniqueAggregates(
                selectedSetupId, selectedEventStore,
                eventTypeFilter || undefined,
                AGGREGATE_PAGE_SIZE, nextOffset
            )
            if (append) {
                setAggregates(prev => [...prev, ...(response.aggregates || [])])
            } else {
                setAggregates(response.aggregates || [])
                setSelectedAggregate(null)
                setAggregateEvents([])
                setEventsTruncated(false)
            }
            setAggregatesOffset(nextOffset)
            setAggregatesTotalCount(response.totalCount)
            setAggregatesTruncated(response.truncated)
        } catch (error: any) {
            message.error(`Failed to fetch aggregates: ${error.message}`)
        } finally {
            setAggregatesLoading(false)
        }
    }

    const fetchAggregateEvents = async (aggregateId: string) => {
        if (!selectedSetupId || !selectedEventStore) return
        setAggregateEventsLoading(true)
        setSelectedAggregate(aggregateId)
        setEventsTruncated(false)
        try {
            const response = await peeGeeQClient.queryEvents(selectedSetupId, selectedEventStore, {
                aggregateId,
                limit: 1000,
                offset: 0,
                sortOrder: 'VERSION_ASC',
                includeCorrections: true
            })
            setAggregateEvents(response.events || [])
            setEventsTruncated(response.hasMore)
        } catch (error: any) {
            message.error(`Failed to fetch events for aggregate ${aggregateId}: ${error.message}`)
        } finally {
            setAggregateEventsLoading(false)
        }
    }

    useEffect(() => {
        fetchSetups()
        fetchEventStores()
    }, [])

    useEffect(() => {
        setSelectedEventStore('')
        setAggregates([])
        setSelectedAggregate(null)
        setAggregateEvents([])
        setAggregatesTruncated(false)
        setEventsTruncated(false)
    }, [selectedSetupId])

    useEffect(() => {
        setAggregates([])
        setSelectedAggregate(null)
        setAggregateEvents([])
        setAggregatesTruncated(false)
        setEventsTruncated(false)
    }, [selectedEventStore])

    const storesForSetup = eventStores.filter(s => s.setupId === selectedSetupId)

    const aggregateColumns = [
        {
            title: 'Aggregate ID',
            dataIndex: 'aggregateId',
            key: 'aggregateId',
            render: (text: string) => <Text strong>{text}</Text>
        },
        {
            title: 'Events',
            dataIndex: 'eventCount',
            key: 'eventCount',
            width: 70,
            render: (count: number) => <Badge count={count} showZero style={{ backgroundColor: '#722ed1' }} />
        },
        {
            title: 'Last Active',
            dataIndex: 'lastEventTime',
            key: 'lastEventTime',
            width: 160,
            render: (ts: string | undefined) =>
                ts ? <Text type="secondary" style={{ fontSize: '12px' }}>{dayjs(ts).format('YYYY-MM-DD HH:mm')}</Text> : '-'
        },
        {
            title: 'Event Types',
            dataIndex: 'eventTypes',
            key: 'eventTypes',
            render: (types: string[]) => (
                <Space size={2} wrap>
                    {(types || []).map(t => <Tag key={t} color="purple" style={{ fontSize: '10px', margin: 0 }}>{t}</Tag>)}
                </Space>
            )
        }
    ]

    const eventStreamColumns = [
        {
            title: 'Version',
            dataIndex: 'version',
            key: 'version',
            width: 80,
            render: (version: number) => <Badge count={version} style={{ backgroundColor: '#52c41a' }} />
        },
        {
            title: 'Event Type',
            dataIndex: 'eventType',
            key: 'eventType',
            render: (text: string) => <Tag color="purple">{text}</Tag>
        },
        {
            title: 'Valid Time',
            dataIndex: 'validTime',
            key: 'validTime',
            render: (text: string) => dayjs(text).format('YYYY-MM-DD HH:mm:ss')
        },
        {
            title: 'Transaction Time',
            dataIndex: 'transactionTime',
            key: 'transactionTime',
            render: (text: string) => dayjs(text).format('YYYY-MM-DD HH:mm:ss.SSS')
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: any, record: BiTemporalEvent) => (
                <Space>
                    <Button
                        type="link"
                        icon={<SearchOutlined />}
                        onClick={() => { setSelectedEvent(record); setDrawerVisible(true) }}
                    >
                        Details
                    </Button>
                    <Button
                        type="link"
                        icon={<BranchesOutlined />}
                        disabled={!record.correlationId}
                        onClick={() => navigate(
                            `/causation-tree?correlationId=${encodeURIComponent(record.correlationId!)}`
                            + `&setupId=${encodeURIComponent(selectedSetupId)}`
                            + `&eventStore=${encodeURIComponent(selectedEventStore)}`
                        )}
                    >
                        Causation Tree
                    </Button>
                </Space>
            )
        }
    ]

    return (
        <div>
            <Title level={1} style={{ marginBottom: 24 }}>
                <DatabaseOutlined style={{ marginRight: 8 }} />
                Aggregate Stream
            </Title>

            <Card title="Select Event Store" size="small" style={{ marginBottom: 16 }}>
                <Row gutter={16}>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            data-testid="aggregate-setup-select"
                            placeholder="Select setup"
                            style={{ width: '100%' }}
                            value={selectedSetupId || undefined}
                            onChange={(value) => setSelectedSetupId(value ?? '')}
                            loading={setupsLoading}
                            allowClear
                        >
                            {setupIds.map(id => (
                                <Select.Option key={id} value={id}>{id}</Select.Option>
                            ))}
                        </Select>
                    </Col>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            data-testid="aggregate-eventstore-select"
                            placeholder="Select event store"
                            style={{ width: '100%' }}
                            value={selectedEventStore || undefined}
                            onChange={(value) => setSelectedEventStore(value ?? '')}
                            disabled={!selectedSetupId}
                            allowClear
                        >
                            {storesForSetup.map(store => (
                                <Select.Option key={store.key} value={store.name}>
                                    {store.name}
                                </Select.Option>
                            ))}
                        </Select>
                    </Col>
                    <Col xs={24} sm={12} md={8}>
                        <Button
                            type="primary"
                            icon={<ReloadOutlined />}
                            onClick={() => fetchAggregates(false)}
                            loading={aggregatesLoading}
                            disabled={!selectedSetupId || !selectedEventStore}
                        >
                            Load Aggregates
                        </Button>
                    </Col>
                </Row>
            </Card>

            <Card title={<span><DatabaseOutlined /> Aggregate Stream</span>}>
                <div style={{ display: 'flex', gap: 16 }}>
                    <Card title="Aggregates" style={{ width: 560 }} styles={{ body: { padding: 0 } }}>
                        <div style={{ padding: 16, borderBottom: '1px solid #f0f0f0' }}>
                            <Space direction="vertical" style={{ width: '100%' }}>
                                <Input
                                    placeholder="Filter by Event Type"
                                    value={eventTypeFilter}
                                    onChange={e => setEventTypeFilter(e.target.value)}
                                    allowClear
                                />
                                <Button
                                    block
                                    icon={<ReloadOutlined />}
                                    onClick={() => fetchAggregates(false)}
                                    loading={aggregatesLoading}
                                    disabled={!selectedSetupId || !selectedEventStore}
                                >
                                    Refresh List
                                </Button>
                            </Space>
                        </div>

                        {aggregatesTruncated && (
                            <Alert
                                style={{ margin: '8px 16px 0' }}
                                type="warning"
                                showIcon
                                message={`Showing ${aggregates.length.toLocaleString()} of ${aggregatesTotalCount.toLocaleString()} aggregates`}
                                description='Use the Event Type filter to narrow results, or click "Load More" below.'
                            />
                        )}

                        <div style={{ maxHeight: 600, overflowY: 'auto' }}>
                            {aggregates.length === 0 && !aggregatesLoading ? (
                                <Empty
                                    style={{ padding: 24 }}
                                    description={
                                        selectedSetupId && selectedEventStore
                                            ? 'Click "Load Aggregates" to fetch'
                                            : 'Select a setup and event store'
                                    }
                                />
                            ) : (
                                <Table
                                    dataSource={aggregates.map(a => ({ ...a, key: a.aggregateId }))}
                                    columns={aggregateColumns}
                                    pagination={false}
                                    loading={aggregatesLoading}
                                    size="small"
                                    onRow={(record) => ({
                                        onClick: () => fetchAggregateEvents(record.aggregateId),
                                        style: {
                                            cursor: 'pointer',
                                            background: selectedAggregate === record.aggregateId ? '#e6f7ff' : undefined
                                        }
                                    })}
                                />
                            )}
                        </div>

                        {aggregatesTruncated && (
                            <div style={{ padding: 12, borderTop: '1px solid #f0f0f0' }}>
                                <Button
                                    block
                                    onClick={() => fetchAggregates(true)}
                                    loading={aggregatesLoading}
                                >
                                    Load More ({aggregates.length.toLocaleString()} of {aggregatesTotalCount.toLocaleString()})
                                </Button>
                            </div>
                        )}
                    </Card>

                    <Card
                        title={selectedAggregate ? `Stream: ${selectedAggregate}` : 'Select an Aggregate'}
                        style={{ flex: 1 }}
                    >
                        {eventsTruncated && (
                            <Alert
                                style={{ marginBottom: 12 }}
                                type="warning"
                                showIcon
                                message="Showing first 1,000 events — stream may be truncated"
                            />
                        )}
                        {selectedAggregate ? (
                            <Table
                                dataSource={aggregateEvents}
                                columns={eventStreamColumns}
                                rowKey="eventId"
                                loading={aggregateEventsLoading}
                                pagination={{ pageSize: 10 }}
                            />
                        ) : (
                            <Empty description="Select an aggregate from the list to view its event stream" />
                        )}
                    </Card>
                </div>
            </Card>

            <Drawer
                title="Event Details"
                placement="right"
                width={600}
                onClose={() => setDrawerVisible(false)}
                open={drawerVisible}
            >
                {selectedEvent && (
                    <Descriptions column={1} bordered size="small">
                        <Descriptions.Item label="Event ID">
                            <Text copyable>{selectedEvent.eventId}</Text>
                        </Descriptions.Item>
                        <Descriptions.Item label="Event Type">
                            <Tag color="purple">{selectedEvent.eventType}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="Aggregate ID">
                            {selectedEvent.aggregateId ? <Text copyable>{selectedEvent.aggregateId}</Text> : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Correlation ID">
                            {selectedEvent.correlationId ? (
                                <Space>
                                    <Text copyable>{selectedEvent.correlationId}</Text>
                                    <Button
                                        type="link"
                                        size="small"
                                        icon={<BranchesOutlined />}
                                        onClick={() => {
                                            setDrawerVisible(false)
                                            navigate(
                                                `/causation-tree?correlationId=${encodeURIComponent(selectedEvent.correlationId!)}`
                                                + `&setupId=${encodeURIComponent(selectedSetupId)}`
                                                + `&eventStore=${encodeURIComponent(selectedEventStore)}`
                                            )
                                        }}
                                    >
                                        View Causation Tree
                                    </Button>
                                </Space>
                            ) : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Causation ID">
                            {selectedEvent.causationId ? <Text copyable>{selectedEvent.causationId}</Text> : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Valid Time">
                            {dayjs(selectedEvent.validTime).format('YYYY-MM-DD HH:mm:ss')}
                        </Descriptions.Item>
                        <Descriptions.Item label="Transaction Time">
                            {dayjs(selectedEvent.transactionTime).format('YYYY-MM-DD HH:mm:ss.SSS')}
                        </Descriptions.Item>
                        <Descriptions.Item label="Payload">
                            <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: '11px' }}>
                                {JSON.stringify(selectedEvent.payload, null, 2)}
                            </pre>
                        </Descriptions.Item>
                        <Descriptions.Item label="Headers">
                            <pre style={{ maxHeight: 200, overflow: 'auto', fontSize: '11px' }}>
                                {JSON.stringify(selectedEvent.headers, null, 2)}
                            </pre>
                        </Descriptions.Item>
                    </Descriptions>
                )}
            </Drawer>
        </div>
    )
}

export default AggregateStreamPage
