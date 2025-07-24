import { useState, useEffect } from 'react'
import axios from 'axios'
// import { useMessageStream } from '../hooks/useRealTimeUpdates'
import {
  Card,
  Table,
  Button,
  Space,
  Select,
  Input,
  Row,
  Col,
  Tag,
  Modal,
  Drawer,
  Typography,
  Badge,
  Tooltip,
  DatePicker,
  Switch,
  Alert,
  Divider,
  Spin,
  message
} from 'antd'
import {
  SearchOutlined,
  EyeOutlined,
  ReloadOutlined,
  FilterOutlined,
  DownloadOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  ClearOutlined,
  BugOutlined,
  ClockCircleOutlined,
  MessageOutlined,

  CheckCircleOutlined,
  ExclamationCircleOutlined
} from '@ant-design/icons'
import * as dayjs from 'dayjs'
import * as relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { Text, Title } = Typography
const { RangePicker } = DatePicker
const { TextArea } = Input

interface Message {
  key: string
  id: string
  queueName: string
  setup: string
  messageType: string
  payload: any
  headers: Record<string, any>
  timestamp: string
  size: number
  status: 'pending' | 'processing' | 'completed' | 'failed'
  consumerInfo?: {
    consumerId: string
    consumerGroup: string
    processedAt?: string
  }
  correlationId?: string
  causationId?: string
}

interface QueueInfo {
  setup: string
  name: string
  messageCount: number
  consumerCount: number
}






const MessageBrowser = () => {
  const [messages, setMessages] = useState<Message[]>([])
  const [filteredMessages, setFilteredMessages] = useState<Message[]>([])
  const [queues, setQueues] = useState<QueueInfo[]>([])
  const [selectedQueue, setSelectedQueue] = useState<string>('')
  const [selectedSetup, setSelectedSetup] = useState<string>('')
  const [messageTypeFilter, setMessageTypeFilter] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [searchText, setSearchText] = useState<string>('')
  const [selectedMessage, setSelectedMessage] = useState<Message | null>(null)
  const [isMessageModalVisible, setIsMessageModalVisible] = useState(false)
  const [isFilterDrawerVisible, setIsFilterDrawerVisible] = useState(false)
  const [loading, setLoading] = useState(true)

  const fetchQueues = async () => {
    try {
      const response = await axios.get('/api/v1/management/queues')
      if (response.data.queues && Array.isArray(response.data.queues)) {
        const fetchedQueues = response.data.queues.map((queue: any) => ({
          setup: queue.setup,
          name: queue.name,
          messageCount: queue.messages || 0,
          consumerCount: queue.consumers || 0
        }))
        setQueues(fetchedQueues)
      }
    } catch (error) {
      console.error('Failed to fetch queues:', error)
      setQueues([])
    }
  }

  const fetchMessages = async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams()
      if (selectedSetup) params.append('setup', selectedSetup)
      if (selectedQueue) params.append('queue', selectedQueue)
      params.append('limit', '50')
      params.append('offset', '0')

      const response = await axios.get(`/api/v1/management/messages?${params.toString()}`)
      if (response.data.messages && Array.isArray(response.data.messages)) {
        const fetchedMessages = response.data.messages.map((msg: any, index: number) => ({
          key: msg.id || index.toString(),
          id: msg.id,
          type: msg.type,
          status: msg.status,
          priority: msg.priority,
          retries: msg.retries,
          createdAt: msg.createdAt,
          processedAt: msg.processedAt,
          payload: msg.payload,
          headers: msg.headers,
          queue: selectedQueue || 'unknown',
          setup: selectedSetup || 'unknown',
          correlationId: msg.headers?.correlationId || 'unknown'
        }))
        setMessages(fetchedMessages)
        setFilteredMessages(fetchedMessages)
      }
    } catch (error) {
      console.error('Failed to fetch messages:', error)
      // Show error message instead of mock data
      message.error('Failed to load messages. Please check if the backend service is running.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchQueues()
    fetchMessages()
    // Refresh every 30 seconds
    const interval = setInterval(() => {
      fetchQueues()
      fetchMessages()
    }, 30000)
    return () => clearInterval(interval)
  }, [selectedSetup, selectedQueue])
  const [isRealTimeEnabled, setIsRealTimeEnabled] = useState(false)
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)

  // Real-time message streaming (disabled for now)
  // const {
  //   messages: liveMessages,
  //   messageCount: liveMessageCount,
  //   isConnected: streamConnected,
  //   clearMessages: clearLiveMessages
  // } = useMessageStream(selectedSetup, selectedQueue, isRealTimeEnabled)

  // Merge live messages with fetched messages when real-time is enabled (disabled for now)
  // useEffect(() => {
  //   if (isRealTimeEnabled && liveMessages.length > 0) {
  //     const mergedMessages = [...liveMessages, ...messages].slice(0, 100) // Keep latest 100
  //     setFilteredMessages(mergedMessages)
  //   }
  // }, [liveMessages, isRealTimeEnabled, messages])

  // Real-time message simulation
  useEffect(() => {
    if (!isRealTimeEnabled) return

    const interval = setInterval(() => {
      // Simulate new messages arriving
      if (Math.random() > 0.7) {
        const newMessage: Message = {
          key: `msg-${Date.now()}`,
          id: `msg-${Date.now()}`,
          queueName: queues.length > 0 ? queues[Math.floor(Math.random() * queues.length)].name : 'unknown',
          setup: 'production',
          messageType: ['OrderCreated', 'PaymentProcessed', 'EmailNotification'][Math.floor(Math.random() * 3)],
          payload: { data: 'Real-time message', timestamp: new Date().toISOString() },
          headers: { source: 'real-time-service', version: '1.0' },
          timestamp: new Date().toISOString(),
          size: Math.floor(Math.random() * 2048) + 256,
          status: ['pending', 'processing', 'completed'][Math.floor(Math.random() * 3)] as any,
          correlationId: `corr-${Date.now()}`
        }

        setMessages(prev => [newMessage, ...prev.slice(0, 49)]) // Keep last 50 messages
      }
    }, 3000)

    return () => clearInterval(interval)
  }, [isRealTimeEnabled])

  // Filter messages based on current filters
  useEffect(() => {
    let filtered = messages

    if (selectedSetup) {
      filtered = filtered.filter(msg => msg.setup === selectedSetup)
    }

    if (selectedQueue) {
      filtered = filtered.filter(msg => msg.queueName === selectedQueue)
    }

    if (messageTypeFilter) {
      filtered = filtered.filter(msg => msg.messageType.toLowerCase().includes(messageTypeFilter.toLowerCase()))
    }

    if (statusFilter) {
      filtered = filtered.filter(msg => msg.status === statusFilter)
    }

    if (searchText) {
      filtered = filtered.filter(msg =>
        JSON.stringify(msg.payload).toLowerCase().includes(searchText.toLowerCase()) ||
        msg.id.toLowerCase().includes(searchText.toLowerCase()) ||
        msg.correlationId?.toLowerCase().includes(searchText.toLowerCase())
      )
    }

    if (dateRange) {
      filtered = filtered.filter(msg => {
        const msgDate = dayjs(msg.timestamp)
        return msgDate.isAfter(dateRange[0]) && msgDate.isBefore(dateRange[1])
      })
    }

    setFilteredMessages(filtered)
  }, [messages, selectedSetup, selectedQueue, messageTypeFilter, statusFilter, searchText, dateRange])

  const handleViewMessage = (message: Message) => {
    setSelectedMessage(message)
    setIsMessageModalVisible(true)
  }

  const handleRefresh = () => {
    fetchQueues()
    fetchMessages()
  }

  const handleClearFilters = () => {
    setSelectedSetup('')
    setSelectedQueue('')
    setMessageTypeFilter('')
    setStatusFilter('')
    setSearchText('')
    setDateRange(null)
  }

  const getStatusColor = (status: string) => {
    const colors = {
      pending: 'orange',
      processing: 'blue',
      completed: 'green',
      failed: 'red'
    }
    return colors[status as keyof typeof colors] || 'default'
  }

  const getStatusIcon = (status: string) => {
    const icons = {
      pending: <ClockCircleOutlined />,
      processing: <PlayCircleOutlined />,
      completed: <CheckCircleOutlined />,
      failed: <ExclamationCircleOutlined />
    }
    return icons[status as keyof typeof icons]
  }

  return (
    <div className="fade-in">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* Header with Controls */}
        <Card>
          <Row gutter={[16, 16]} align="middle">
            <Col xs={24} sm={12} md={8} lg={6}>
              <Space>
                <Title level={4} style={{ margin: 0 }}>
                  <MessageOutlined /> Message Browser
                </Title>
                <Badge
                  count={filteredMessages.length}
                  style={{ backgroundColor: '#52c41a' }}
                  title={`${filteredMessages.length} messages`}
                />
              </Space>
            </Col>

            <Col xs={24} sm={12} md={16} lg={18}>
              <Row gutter={[8, 8]} justify="end">
                <Col>
                  <Tooltip title="Real-time updates">
                    <Space>
                      <Text>Live</Text>
                      <Switch
                        checked={isRealTimeEnabled}
                        onChange={setIsRealTimeEnabled}
                        checkedChildren={<PlayCircleOutlined />}
                        unCheckedChildren={<PauseCircleOutlined />}
                      />
                    </Space>
                  </Tooltip>
                </Col>
                <Col>
                  <Button
                    icon={<FilterOutlined />}
                    onClick={() => setIsFilterDrawerVisible(true)}
                  >
                    Filters
                  </Button>
                </Col>
                <Col>
                  <Button
                    icon={<ClearOutlined />}
                    onClick={handleClearFilters}
                  >
                    Clear
                  </Button>
                </Col>
                <Col>
                  <Button
                    icon={<ReloadOutlined />}
                    loading={loading}
                    onClick={handleRefresh}
                  >
                    Refresh
                  </Button>
                </Col>
              </Row>
            </Col>
          </Row>
        </Card>

        {/* Real-time Status Alert */}
        {isRealTimeEnabled && (
          <Alert
            message="Real-time Mode Active"
            description="New messages will appear automatically. Disable to pause updates."
            type="info"
            showIcon
            icon={<div className="realtime-indicator"><div className="realtime-dot"></div></div>}
            closable
            onClose={() => setIsRealTimeEnabled(false)}
          />
        )}

        {/* Quick Filters */}
        <Card size="small">
          <Row gutter={[16, 8]}>
            <Col xs={24} sm={12} md={6}>
              <Select
                placeholder="Select Setup"
                value={selectedSetup}
                onChange={setSelectedSetup}
                style={{ width: '100%' }}
                allowClear
              >
                {Array.from(new Set(queues.map(q => q.setup))).map(setup => (
                  <Select.Option key={setup} value={setup}>{setup}</Select.Option>
                ))}
              </Select>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Select
                placeholder="Select Queue"
                value={selectedQueue}
                onChange={setSelectedQueue}
                style={{ width: '100%' }}
                allowClear
              >
                {queues
                  .filter(q => !selectedSetup || q.setup === selectedSetup)
                  .map(queue => (
                    <Select.Option key={`${queue.setup}-${queue.name}`} value={queue.name}>
                      {queue.name} ({queue.messageCount})
                    </Select.Option>
                  ))}
              </Select>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Select
                placeholder="Message Status"
                value={statusFilter}
                onChange={setStatusFilter}
                style={{ width: '100%' }}
                allowClear
              >
                <Select.Option value="pending">Pending</Select.Option>
                <Select.Option value="processing">Processing</Select.Option>
                <Select.Option value="completed">Completed</Select.Option>
                <Select.Option value="failed">Failed</Select.Option>
              </Select>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Input
                placeholder="Search messages..."
                value={searchText}
                onChange={(e) => setSearchText(e.target.value)}
                prefix={<SearchOutlined />}
                allowClear
              />
            </Col>
          </Row>
        </Card>

        {/* Messages Table */}
        <Card title={`Messages (${filteredMessages.length})`}>
          <Spin spinning={loading}>
            <Table
              columns={[
                {
                  title: 'Message ID',
                  dataIndex: 'id',
                  key: 'id',
                  width: 120,
                  render: (text: string) => <Text code>{text}</Text>
                },
                {
                  title: 'Queue',
                  key: 'queue',
                  width: 150,
                  render: (record: Message) => (
                    <Space direction="vertical" size="small">
                      <Text strong>{record.queueName}</Text>
                      <Tag color="blue">{record.setup}</Tag>
                    </Space>
                  )
                },
                {
                  title: 'Type',
                  dataIndex: 'messageType',
                  key: 'messageType',
                  width: 150,
                  render: (text: string) => <Tag color="purple">{text}</Tag>
                },
                {
                  title: 'Status',
                  dataIndex: 'status',
                  key: 'status',
                  width: 120,
                  render: (status: string) => (
                    <Tag color={getStatusColor(status)} icon={getStatusIcon(status)}>
                      {status.toUpperCase()}
                    </Tag>
                  )
                },
                {
                  title: 'Size',
                  dataIndex: 'size',
                  key: 'size',
                  width: 100,
                  render: (size: number) => `${(size / 1024).toFixed(1)} KB`
                },
                {
                  title: 'Timestamp',
                  dataIndex: 'timestamp',
                  key: 'timestamp',
                  width: 180,
                  render: (timestamp: string) => (
                    <Tooltip title={timestamp}>
                      <Text>{dayjs(timestamp).format('MMM DD, HH:mm:ss')}</Text>
                    </Tooltip>
                  )
                },
                {
                  title: 'Consumer',
                  key: 'consumer',
                  width: 150,
                  render: (record: Message) => (
                    record.consumerInfo ? (
                      <Space direction="vertical" size="small">
                        <Text code style={{ fontSize: '11px' }}>{record.consumerInfo.consumerId}</Text>
                        <Tag>{record.consumerInfo.consumerGroup}</Tag>
                      </Space>
                    ) : (
                      <Text type="secondary">-</Text>
                    )
                  )
                },
                {
                  title: 'Actions',
                  key: 'actions',
                  width: 100,
                  render: (record: Message) => (
                    <Space>
                      <Tooltip title="View Details">
                        <Button
                          type="text"
                          icon={<EyeOutlined />}
                          onClick={() => handleViewMessage(record)}
                        />
                      </Tooltip>
                      <Tooltip title="Debug">
                        <Button
                          type="text"
                          icon={<BugOutlined />}
                          onClick={() => {/* console.log('Debug message:', record) */}}
                        />
                      </Tooltip>
                    </Space>
                  )
                }
              ]}
              dataSource={filteredMessages}
              loading={loading}
              pagination={{
                pageSize: 20,
                showSizeChanger: true,
                showQuickJumper: true,
                showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} messages`
              }}
              scroll={{ x: 1200 }}
              size="small"
              locale={{
                emptyText: loading ? 'Loading...' : 'No messages found for the selected criteria. Please check if the backend service is running and has active setups.'
              }}
            />
          </Spin>
        </Card>

        {/* Message Details Modal */}
        <Modal
          title={
            <Space>
              <MessageOutlined />
              <span>Message Details</span>
              {selectedMessage && (
                <Tag color={getStatusColor(selectedMessage.status)}>
                  {selectedMessage.status.toUpperCase()}
                </Tag>
              )}
            </Space>
          }
          open={isMessageModalVisible}
          onCancel={() => setIsMessageModalVisible(false)}
          width={800}
          footer={[
            <Button key="download" icon={<DownloadOutlined />}>
              Export
            </Button>,
            <Button key="close" onClick={() => setIsMessageModalVisible(false)}>
              Close
            </Button>
          ]}
        >
          {selectedMessage && (
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
              {/* Message Metadata */}
              <Card size="small" title="Message Information">
                <Row gutter={[16, 8]}>
                  <Col span={12}>
                    <Text strong>Message ID:</Text>
                    <br />
                    <Text code>{selectedMessage.id}</Text>
                  </Col>
                  <Col span={12}>
                    <Text strong>Queue:</Text>
                    <br />
                    <Space>
                      <Text>{selectedMessage.queueName}</Text>
                      <Tag color="blue">{selectedMessage.setup}</Tag>
                    </Space>
                  </Col>
                  <Col span={12}>
                    <Text strong>Message Type:</Text>
                    <br />
                    <Tag color="purple">{selectedMessage.messageType}</Tag>
                  </Col>
                  <Col span={12}>
                    <Text strong>Size:</Text>
                    <br />
                    <Text>{(selectedMessage.size / 1024).toFixed(1)} KB</Text>
                  </Col>
                  <Col span={12}>
                    <Text strong>Timestamp:</Text>
                    <br />
                    <Text>{dayjs(selectedMessage.timestamp).format('YYYY-MM-DD HH:mm:ss')}</Text>
                  </Col>
                  <Col span={12}>
                    <Text strong>Status:</Text>
                    <br />
                    <Tag color={getStatusColor(selectedMessage.status)} icon={getStatusIcon(selectedMessage.status)}>
                      {selectedMessage.status.toUpperCase()}
                    </Tag>
                  </Col>
                  {selectedMessage.correlationId && (
                    <Col span={12}>
                      <Text strong>Correlation ID:</Text>
                      <br />
                      <Text code>{selectedMessage.correlationId}</Text>
                    </Col>
                  )}
                  {selectedMessage.causationId && (
                    <Col span={12}>
                      <Text strong>Causation ID:</Text>
                      <br />
                      <Text code>{selectedMessage.causationId}</Text>
                    </Col>
                  )}
                </Row>
              </Card>

              {/* Consumer Information */}
              {selectedMessage.consumerInfo && (
                <Card size="small" title="Consumer Information">
                  <Row gutter={[16, 8]}>
                    <Col span={12}>
                      <Text strong>Consumer ID:</Text>
                      <br />
                      <Text code>{selectedMessage.consumerInfo.consumerId}</Text>
                    </Col>
                    <Col span={12}>
                      <Text strong>Consumer Group:</Text>
                      <br />
                      <Tag>{selectedMessage.consumerInfo.consumerGroup}</Tag>
                    </Col>
                    {selectedMessage.consumerInfo.processedAt && (
                      <Col span={12}>
                        <Text strong>Processed At:</Text>
                        <br />
                        <Text>{dayjs(selectedMessage.consumerInfo.processedAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                      </Col>
                    )}
                  </Row>
                </Card>
              )}

              {/* Message Headers */}
              <Card size="small" title="Headers">
                <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                  {JSON.stringify(selectedMessage.headers, null, 2)}
                </pre>
              </Card>

              {/* Message Payload */}
              <Card size="small" title="Payload">
                <pre style={{ background: '#f5f5f5', padding: '12px', borderRadius: '4px', fontSize: '12px' }}>
                  {JSON.stringify(selectedMessage.payload, null, 2)}
                </pre>
              </Card>
            </Space>
          )}
        </Modal>

        {/* Advanced Filters Drawer */}
        <Drawer
          title="Advanced Filters"
          placement="right"
          onClose={() => setIsFilterDrawerVisible(false)}
          open={isFilterDrawerVisible}
          width={400}
          extra={
            <Button onClick={handleClearFilters} icon={<ClearOutlined />}>
              Clear All
            </Button>
          }
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Text strong>Setup & Queue</Text>
              <Divider />
              <Space direction="vertical" style={{ width: '100%' }}>
                <Select
                  placeholder="Select Setup"
                  value={selectedSetup}
                  onChange={setSelectedSetup}
                  style={{ width: '100%' }}
                  allowClear
                >
                  {Array.from(new Set(queues.map(q => q.setup))).map(setup => (
                    <Select.Option key={setup} value={setup}>{setup}</Select.Option>
                  ))}
                </Select>
                <Select
                  placeholder="Select Queue"
                  value={selectedQueue}
                  onChange={setSelectedQueue}
                  style={{ width: '100%' }}
                  allowClear
                >
                  {queues
                    .filter(q => !selectedSetup || q.setup === selectedSetup)
                    .map(queue => (
                      <Select.Option key={`${queue.setup}-${queue.name}`} value={queue.name}>
                        {queue.name} ({queue.messageCount})
                      </Select.Option>
                    ))}
                </Select>
              </Space>
            </div>

            <div>
              <Text strong>Message Filters</Text>
              <Divider />
              <Space direction="vertical" style={{ width: '100%' }}>
                <Input
                  placeholder="Message Type"
                  value={messageTypeFilter}
                  onChange={(e) => setMessageTypeFilter(e.target.value)}
                  allowClear
                />
                <Select
                  placeholder="Status"
                  value={statusFilter}
                  onChange={setStatusFilter}
                  style={{ width: '100%' }}
                  allowClear
                >
                  <Select.Option value="pending">Pending</Select.Option>
                  <Select.Option value="processing">Processing</Select.Option>
                  <Select.Option value="completed">Completed</Select.Option>
                  <Select.Option value="failed">Failed</Select.Option>
                </Select>
              </Space>
            </div>

            <div>
              <Text strong>Time Range</Text>
              <Divider />
              <RangePicker
                value={dateRange}
                onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
                showTime
                style={{ width: '100%' }}
              />
            </div>

            <div>
              <Text strong>Content Search</Text>
              <Divider />
              <TextArea
                placeholder="Search in message payload, headers, or IDs..."
                value={searchText}
                onChange={(e) => setSearchText(e.target.value)}
                rows={3}
              />
            </div>
          </Space>
        </Drawer>
      </Space>
    </div>
  )
}

export default MessageBrowser
