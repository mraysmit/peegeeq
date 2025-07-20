import { useState, useEffect } from 'react'
import axios from 'axios'
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
  ApiOutlined
} from '@ant-design/icons'

import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { Text } = Typography
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

// Mock data for demonstration
const mockEventStores: EventStore[] = [
  {
    key: '1',
    name: 'order-events',
    setupId: 'production',
    eventCount: 125847,
    streamCount: 8934,
    aggregateTypes: ['Order', 'Payment', 'Shipment'],
    eventTypes: ['OrderCreated', 'OrderUpdated', 'OrderCancelled', 'PaymentProcessed', 'ShipmentCreated'],
    createdAt: '2025-07-15T09:30:00Z',
    lastEventAt: '2025-07-19T14:30:00Z',
    status: 'active',
    storageSize: 2048576, // 2MB
    correctionCount: 23
  },
  {
    key: '2',
    name: 'user-events',
    setupId: 'production',
    eventCount: 89234,
    streamCount: 12456,
    aggregateTypes: ['User', 'Profile', 'Preferences'],
    eventTypes: ['UserRegistered', 'UserUpdated', 'ProfileChanged', 'PreferencesSet'],
    createdAt: '2025-07-16T10:15:00Z',
    lastEventAt: '2025-07-19T14:25:00Z',
    status: 'active',
    storageSize: 1536000, // 1.5MB
    correctionCount: 12
  },
  {
    key: '3',
    name: 'analytics-events',
    setupId: 'staging',
    eventCount: 45123,
    streamCount: 2341,
    aggregateTypes: ['Session', 'PageView', 'Interaction'],
    eventTypes: ['SessionStarted', 'PageViewed', 'ButtonClicked', 'FormSubmitted'],
    createdAt: '2025-07-17T15:20:00Z',
    lastEventAt: '2025-07-19T13:45:00Z',
    status: 'active',
    storageSize: 768000, // 768KB
    correctionCount: 5
  }
]

const mockEvents: EventStoreEvent[] = [
  {
    key: '1',
    id: 'event-001',
    eventType: 'OrderCreated',
    eventData: {
      orderId: 'ORD-12345',
      customerId: 'CUST-67890',
      items: [
        { productId: 'PROD-001', quantity: 2, price: 29.99 },
        { productId: 'PROD-002', quantity: 1, price: 49.99 }
      ],
      totalAmount: 109.97,
      currency: 'USD',
      orderDate: '2025-07-19T14:30:00Z'
    },
    validFrom: '2025-07-19T14:30:00Z',
    transactionTime: '2025-07-19T14:30:01Z',
    correlationId: 'corr-12345',
    causationId: 'cause-67890',
    version: 1,
    metadata: {
      source: 'order-service',
      userId: 'user-123',
      sessionId: 'session-456'
    },
    aggregateId: 'order-12345',
    aggregateType: 'Order',
    streamId: 'order-stream-12345',
    eventNumber: 1
  },
  {
    key: '2',
    id: 'event-002',
    eventType: 'OrderUpdated',
    eventData: {
      orderId: 'ORD-12345',
      changes: {
        shippingAddress: {
          street: '123 Main St',
          city: 'Anytown',
          state: 'CA',
          zipCode: '12345'
        }
      },
      updatedAt: '2025-07-19T14:35:00Z'
    },
    validFrom: '2025-07-19T14:35:00Z',
    transactionTime: '2025-07-19T14:35:01Z',
    correlationId: 'corr-12345',
    causationId: 'event-001',
    version: 2,
    metadata: {
      source: 'order-service',
      userId: 'user-123',
      reason: 'address_correction'
    },
    aggregateId: 'order-12345',
    aggregateType: 'Order',
    streamId: 'order-stream-12345',
    eventNumber: 2
  },
  {
    key: '3',
    id: 'event-003',
    eventType: 'PaymentProcessed',
    eventData: {
      paymentId: 'PAY-54321',
      orderId: 'ORD-12345',
      amount: 109.97,
      currency: 'USD',
      method: 'credit_card',
      status: 'completed',
      processedAt: '2025-07-19T14:32:00Z'
    },
    validFrom: '2025-07-19T14:32:00Z',
    transactionTime: '2025-07-19T14:32:05Z',
    correlationId: 'corr-12345',
    causationId: 'event-001',
    version: 1,
    metadata: {
      source: 'payment-service',
      gateway: 'stripe',
      transactionId: 'txn-789'
    },
    aggregateId: 'payment-54321',
    aggregateType: 'Payment',
    streamId: 'payment-stream-54321',
    eventNumber: 1
  }
]

const EventStores = () => {
  const [eventStores, setEventStores] = useState<EventStore[]>([])
  const [events] = useState<EventStoreEvent[]>(mockEvents)
  const [filteredEvents, setFilteredEvents] = useState<EventStoreEvent[]>(mockEvents)
  const [selectedEventStore, setSelectedEventStore] = useState<EventStore | null>(null)
  const [selectedEvent, setSelectedEvent] = useState<EventStoreEvent | null>(null)
  const [isCreateModalVisible, setIsCreateModalVisible] = useState(false)
  const [isEventDetailsModalVisible, setIsEventDetailsModalVisible] = useState(false)
  const [isEventStoreDetailsModalVisible, setIsEventStoreDetailsModalVisible] = useState(false)
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState('stores')
  const [form] = Form.useForm()

  const fetchEventStores = async () => {
    setLoading(true)
    try {
      const response = await axios.get('/api/v1/management/event-stores')
      if (response.data.eventStores && Array.isArray(response.data.eventStores)) {
        setEventStores(response.data.eventStores.map((store: any, index: number) => ({
          key: index.toString(),
          name: store.name,
          setupId: store.setup,
          eventCount: store.events,
          streamCount: Math.floor(store.events / 10), // Estimate streams
          aggregateTypes: ['Order', 'Payment', 'User'], // Mock for now
          eventTypes: ['Created', 'Updated', 'Deleted'], // Mock for now
          createdAt: store.createdAt,
          lastEventAt: store.lastEvent,
          status: 'active',
          retention: store.retention || '365d',
          biTemporal: store.biTemporal || true,
          storageSize: store.events * 1024, // Estimate storage
          compressionRatio: 0.3,
          avgEventSize: 1024,
          corrections: store.corrections || 0
        })))
      }
    } catch (error) {
      console.error('Failed to fetch event stores:', error)
      // Fallback to mock data on error
      setEventStores(mockEventStores)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchEventStores()
    // Refresh every 30 seconds
    const interval = setInterval(fetchEventStores, 30000)
    return () => clearInterval(interval)
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
      onOk: () => {
        setEventStores(prev => prev.filter(es => es.key !== eventStore.key))
      },
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
        <Space direction="vertical" size="small">
          <Space>
            <strong>{text}</strong>
            <Tag color="blue">{record.setupId}</Tag>
          </Space>
          <Space size="small">
            {record.aggregateTypes.slice(0, 3).map(type => (
              <Tag key={type} color="purple">{type}</Tag>
            ))}
            {record.aggregateTypes.length > 3 && (
              <Tag color="purple">+{record.aggregateTypes.length - 3}</Tag>
            )}
          </Space>
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
          <Tag color="cyan">{record.aggregateType}</Tag>
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
            <Tooltip title={`Correlation ID: ${record.correlationId}`}>
              <Text code style={{ fontSize: '10px' }}>
                <LinkOutlined /> {record.correlationId.slice(-8)}
              </Text>
            </Tooltip>
          )}
          {record.causationId && (
            <Tooltip title={`Causation ID: ${record.causationId}`}>
              <Text code style={{ fontSize: '10px' }}>
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

  return (
    <div className="fade-in">
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
                  onClick={() => {
                    setLoading(true)
                    setTimeout(() => setLoading(false), 1000)
                  }}
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
              />
            </TabPane>

            <TabPane tab={`Events (${filteredEvents.length})`} key="events">
              {/* Event Filters */}
              <Card size="small" style={{ marginBottom: 16 }}>
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
                loading={loading}
              />
            </TabPane>
          </Tabs>
        </Card>

        {/* Create Event Store Modal */}
        <Modal
          title="Create Event Store"
          open={isCreateModalVisible}
          onOk={() => {
            form.validateFields().then(values => {
              const newEventStore: EventStore = {
                key: Date.now().toString(),
                ...values,
                eventCount: 0,
                streamCount: 0,
                aggregateTypes: [],
                eventTypes: [],
                createdAt: new Date().toISOString(),
                status: 'active' as const,
                storageSize: 0,
                correctionCount: 0
              }
              setEventStores(prev => [...prev, newEventStore])
              setIsCreateModalVisible(false)
            })
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
                  label="Setup"
                  rules={[{ required: true, message: 'Please select setup' }]}
                >
                  <Select placeholder="Select setup">
                    <Select.Option value="production">Production</Select.Option>
                    <Select.Option value="staging">Staging</Select.Option>
                    <Select.Option value="development">Development</Select.Option>
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
