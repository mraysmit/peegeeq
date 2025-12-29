import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import axios from 'axios'
import { getApiUrl } from '../services/configService'
import {
  Card,
  Tabs,
  Button,
  Space,
  Statistic,
  Row,
  Col,
  Table,
  Tag,
  message,
  Descriptions,
  Spin,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Alert
} from 'antd'
import {
  ArrowLeftOutlined,
  InboxOutlined,
  SendOutlined,
  UserOutlined,
  DeleteOutlined,
  ExclamationCircleOutlined,
  DownloadOutlined,
  ReloadOutlined
} from '@ant-design/icons'

const { TextArea } = Input

interface QueueDetails {
  name: string
  setup: string
  messages: number
  consumers: number
  messageRate: number
  consumerRate: number
  status: 'active' | 'idle' | 'error'
  durability: 'durable' | 'transient'
  autoDelete: boolean
  createdAt: string
  lastActivity: string
}

interface Consumer {
  key: string
  name: string
  prefetchCount: number
  ackMode: 'auto' | 'manual'
  active: boolean
  messagesProcessed: number
  lastActivity: string
}

interface Binding {
  key: string
  source: string
  routingKey: string
  arguments: Record<string, any>
}

interface Message {
  messageId: string
  payload: any
  headers: Record<string, string>
  timestamp: number
  priority: number
  messageType: string
}

const QueueDetails = () => {
  const { queueName } = useParams<{ queueName: string }>()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [queueDetails, setQueueDetails] = useState<QueueDetails | null>(null)
  const [consumers, setConsumers] = useState<Consumer[]>([])
  const [bindings, setBindings] = useState<Binding[]>([])
  const [messages, setMessages] = useState<Message[]>([])
  const [activeTab, setActiveTab] = useState('overview')
  
  // Modal states
  const [publishModalVisible, setPublishModalVisible] = useState(false)
  const [getMessagesModalVisible, setGetMessagesModalVisible] = useState(false)
  const [publishForm] = Form.useForm()
  const [getMessagesForm] = Form.useForm()

  // Fetch queue details
  const fetchQueueDetails = async () => {
    if (!queueName) return

    setLoading(true)
    try {
      // Parse queue name to get setup and queue name (format: "setup-queueName")
      const parts = queueName.split('-', 2)
      if (parts.length !== 2) {
        message.error('Invalid queue identifier format')
        return
      }

      const [setupId, actualQueueName] = parts

      // Fetch queue details from backend
      const response = await axios.get(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}`))
      
      if (response.data) {
        setQueueDetails(response.data)
      }
    } catch (error) {
      console.error('Failed to fetch queue details:', error)
      message.error('Failed to load queue details')
    } finally {
      setLoading(false)
    }
  }

  // Fetch consumers
  const fetchConsumers = async () => {
    if (!queueName) return

    try {
      const parts = queueName.split('-', 2)
      if (parts.length !== 2) return

      const [setupId, actualQueueName] = parts
      const response = await axios.get(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}/consumers`))
      
      if (response.data && response.data.consumers) {
        setConsumers(response.data.consumers)
      }
    } catch (error) {
      console.error('Failed to fetch consumers:', error)
    }
  }

  // Fetch bindings
  const fetchBindings = async () => {
    if (!queueName) return

    try {
      const parts = queueName.split('-', 2)
      if (parts.length !== 2) return

      const [setupId, actualQueueName] = parts
      const response = await axios.get(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}/bindings`))
      
      if (response.data && response.data.bindings) {
        setBindings(response.data.bindings)
      }
    } catch (error) {
      console.error('Failed to fetch bindings:', error)
    }
  }

  useEffect(() => {
    fetchQueueDetails()
    fetchConsumers()
    fetchBindings()

    // Refresh every 30 seconds
    const interval = setInterval(() => {
      fetchQueueDetails()
      fetchConsumers()
      fetchBindings()
    }, 30000)

    return () => clearInterval(interval)
  }, [queueName])

  // Handle get messages
  const handleGetMessages = async () => {
    try {
      const values = await getMessagesForm.validateFields()
      const parts = queueName!.split('-', 2)
      const [setupId, actualQueueName] = parts

      const response = await axios.get(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}/messages`), {
        params: {
          count: values.count,
          ackMode: values.ackMode
        }
      })

      if (response.data && response.data.messages) {
        setMessages(response.data.messages)
        message.success(`Retrieved ${response.data.messages.length} messages`)
        setGetMessagesModalVisible(false)
        setActiveTab('messages')
      }
    } catch (error) {
      console.error('Failed to get messages:', error)
      message.error('Failed to retrieve messages')
    }
  }

  // Handle publish message
  const handlePublishMessage = async () => {
    try {
      const values = await publishForm.validateFields()
      const parts = queueName!.split('-', 2)
      const [setupId, actualQueueName] = parts

      // Parse payload if it's JSON string
      let payload = values.payload
      try {
        payload = JSON.parse(values.payload)
      } catch {
        // Keep as string if not valid JSON
      }

      const response = await axios.post(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}/publish`), {
        payload,
        headers: values.headers ? JSON.parse(values.headers) : {},
        priority: values.priority,
        delaySeconds: values.delaySeconds
      })

      if (response.data) {
        message.success(`Message published successfully. ID: ${response.data.messageId}`)
        setPublishModalVisible(false)
        publishForm.resetFields()
        fetchQueueDetails() // Refresh stats
      }
    } catch (error) {
      console.error('Failed to publish message:', error)
      message.error('Failed to publish message')
    }
  }

  // Handle purge queue
  const handlePurgeQueue = () => {
    Modal.confirm({
      title: 'Purge Queue',
      icon: <ExclamationCircleOutlined />,
      content: `Are you sure you want to delete ALL messages from queue "${queueDetails?.name}"? This action cannot be undone.`,
      okText: 'Purge',
      okType: 'danger',
      onOk: async () => {
        try {
          const parts = queueName!.split('-', 2)
          const [setupId, actualQueueName] = parts

          await axios.post(getApiUrl(`/api/v1/queues/${setupId}/${actualQueueName}/purge`))
          message.success('Queue purged successfully')
          fetchQueueDetails() // Refresh stats
        } catch (error) {
          console.error('Failed to purge queue:', error)
          message.error('Failed to purge queue')
        }
      }
    })
  }

  // Consumer table columns
  const consumerColumns = [
    {
      title: 'Consumer Name',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: 'Prefetch Count',
      dataIndex: 'prefetchCount',
      key: 'prefetchCount',
    },
    {
      title: 'Ack Mode',
      dataIndex: 'ackMode',
      key: 'ackMode',
      render: (ackMode: string) => (
        <Tag color={ackMode === 'auto' ? 'blue' : 'green'}>{ackMode.toUpperCase()}</Tag>
      ),
    },
    {
      title: 'Messages Processed',
      dataIndex: 'messagesProcessed',
      key: 'messagesProcessed',
    },
    {
      title: 'Status',
      dataIndex: 'active',
      key: 'active',
      render: (active: boolean) => (
        <Tag color={active ? 'green' : 'red'}>{active ? 'ACTIVE' : 'IDLE'}</Tag>
      ),
    },
    {
      title: 'Last Activity',
      dataIndex: 'lastActivity',
      key: 'lastActivity',
      render: (text: string) => new Date(text).toLocaleString(),
    },
  ]

  // Binding table columns
  const bindingColumns = [
    {
      title: 'Source Exchange',
      dataIndex: 'source',
      key: 'source',
    },
    {
      title: 'Routing Key',
      dataIndex: 'routingKey',
      key: 'routingKey',
      render: (text: string) => <Tag>{text}</Tag>,
    },
    {
      title: 'Arguments',
      dataIndex: 'arguments',
      key: 'arguments',
      render: (args: Record<string, any>) => (
        <span>{Object.keys(args).length > 0 ? JSON.stringify(args) : 'None'}</span>
      ),
    },
  ]

  // Message table columns
  const messageColumns = [
    {
      title: 'Message ID',
      dataIndex: 'messageId',
      key: 'messageId',
      render: (text: string) => <Tag color="blue">{text.substring(0, 8)}...</Tag>,
    },
    {
      title: 'Type',
      dataIndex: 'messageType',
      key: 'messageType',
    },
    {
      title: 'Priority',
      dataIndex: 'priority',
      key: 'priority',
    },
    {
      title: 'Timestamp',
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (timestamp: number) => new Date(timestamp).toLocaleString(),
    },
    {
      title: 'Payload',
      dataIndex: 'payload',
      key: 'payload',
      render: (payload: any) => (
        <Button
          size="small"
          icon={<DownloadOutlined />}
          onClick={() => {
            Modal.info({
              title: 'Message Payload',
              content: <pre>{JSON.stringify(payload, null, 2)}</pre>,
              width: 800,
            })
          }}
        >
          View
        </Button>
      ),
    },
  ]

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (!queueDetails) {
    return (
      <Alert
        message="Queue Not Found"
        description="The requested queue could not be found."
        type="error"
        showIcon
      />
    )
  }

  return (
    <div className="fade-in">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* Header */}
        <Card>
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/queues')}>
              Back to Queues
            </Button>
            <h2 style={{ margin: 0 }}>Queue: {queueDetails.name}</h2>
            <Tag color="blue">{queueDetails.setup}</Tag>
            <Tag color={queueDetails.status === 'active' ? 'green' : 'red'}>
              {queueDetails.status.toUpperCase()}
            </Tag>
          </Space>
        </Card>

        {/* Summary Statistics */}
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Total Messages"
                value={queueDetails.messages}
                prefix={<InboxOutlined style={{ color: '#1890ff' }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Active Consumers"
                value={queueDetails.consumers}
                prefix={<UserOutlined style={{ color: '#52c41a' }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Message Rate"
                value={queueDetails.messageRate.toFixed(1)}
                suffix="msg/s"
                prefix={<SendOutlined style={{ color: '#722ed1' }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Consumer Rate"
                value={queueDetails.consumerRate.toFixed(1)}
                suffix="msg/s"
                prefix={<DownloadOutlined style={{ color: '#fa8c16' }} />}
              />
            </Card>
          </Col>
        </Row>

        {/* Tabs Content */}
        <Card>
          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            items={[
              {
                key: 'overview',
                label: 'Overview',
                children: (
                  <Descriptions bordered column={2}>
                    <Descriptions.Item label="Queue Name">{queueDetails.name}</Descriptions.Item>
                    <Descriptions.Item label="Setup">{queueDetails.setup}</Descriptions.Item>
                    <Descriptions.Item label="Status">
                      <Tag color={queueDetails.status === 'active' ? 'green' : 'red'}>
                        {queueDetails.status.toUpperCase()}
                      </Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="Durability">
                      <Tag color={queueDetails.durability === 'durable' ? 'green' : 'orange'}>
                        {queueDetails.durability.toUpperCase()}
                      </Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="Auto Delete">
                      {queueDetails.autoDelete ? 'Yes' : 'No'}
                    </Descriptions.Item>
                    <Descriptions.Item label="Total Messages">{queueDetails.messages}</Descriptions.Item>
                    <Descriptions.Item label="Active Consumers">{queueDetails.consumers}</Descriptions.Item>
                    <Descriptions.Item label="Message Rate">
                      {queueDetails.messageRate.toFixed(1)} msg/s
                    </Descriptions.Item>
                    <Descriptions.Item label="Consumer Rate">
                      {queueDetails.consumerRate.toFixed(1)} msg/s
                    </Descriptions.Item>
                    <Descriptions.Item label="Created At">
                      {new Date(queueDetails.createdAt).toLocaleString()}
                    </Descriptions.Item>
                    <Descriptions.Item label="Last Activity">
                      {new Date(queueDetails.lastActivity).toLocaleString()}
                    </Descriptions.Item>
                  </Descriptions>
                ),
              },
              {
                key: 'consumers',
                label: `Consumers (${consumers.length})`,
                children: (
                  <Table
                    columns={consumerColumns}
                    dataSource={consumers}
                    pagination={false}
                    locale={{ emptyText: 'No active consumers' }}
                  />
                ),
              },
              {
                key: 'bindings',
                label: `Bindings (${bindings.length})`,
                children: (
                  <Table
                    columns={bindingColumns}
                    dataSource={bindings}
                    pagination={false}
                    locale={{ emptyText: 'No bindings configured' }}
                  />
                ),
              },
              {
                key: 'messages',
                label: `Messages (${messages.length})`,
                children: (
                  <div>
                    <Space style={{ marginBottom: 16 }}>
                      <Button
                        type="primary"
                        icon={<DownloadOutlined />}
                        onClick={() => setGetMessagesModalVisible(true)}
                      >
                        Get Messages
                      </Button>
                      <Button icon={<ReloadOutlined />} onClick={() => setMessages([])}>
                        Clear
                      </Button>
                    </Space>
                    <Table
                      columns={messageColumns}
                      dataSource={messages}
                      pagination={{ pageSize: 10 }}
                      locale={{ emptyText: 'No messages retrieved. Click "Get Messages" to fetch messages.' }}
                    />
                  </div>
                ),
              },
              {
                key: 'actions',
                label: 'Actions',
                children: (
                  <Space direction="vertical" size="large" style={{ width: '100%' }}>
                    <Card title="Message Operations">
                      <Space>
                        <Button
                          type="primary"
                          icon={<SendOutlined />}
                          onClick={() => setPublishModalVisible(true)}
                        >
                          Publish Message
                        </Button>
                        <Button
                          icon={<DownloadOutlined />}
                          onClick={() => setGetMessagesModalVisible(true)}
                        >
                          Get Messages
                        </Button>
                      </Space>
                    </Card>
                    <Card title="Queue Operations">
                      <Space>
                        <Button
                          danger
                          icon={<DeleteOutlined />}
                          onClick={handlePurgeQueue}
                        >
                          Purge Queue
                        </Button>
                      </Space>
                    </Card>
                  </Space>
                ),
              },
            ]}
          />
        </Card>

        {/* Publish Message Modal */}
        <Modal
          title="Publish Message"
          open={publishModalVisible}
          onOk={handlePublishMessage}
          onCancel={() => setPublishModalVisible(false)}
          width={700}
        >
          <Form form={publishForm} layout="vertical">
            <Form.Item
              name="payload"
              label="Message Payload (JSON)"
              rules={[{ required: true, message: 'Please enter message payload' }]}
            >
              <TextArea
                rows={8}
                placeholder='{"key": "value", "data": {...}}'
              />
            </Form.Item>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="priority" label="Priority (0-10)" initialValue={5}>
                  <InputNumber min={0} max={10} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="delaySeconds" label="Delay (seconds)" initialValue={0}>
                  <InputNumber min={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="headers" label="Custom Headers (JSON)">
              <TextArea
                rows={3}
                placeholder='{"header1": "value1", "header2": "value2"}'
              />
            </Form.Item>
          </Form>
        </Modal>

        {/* Get Messages Modal */}
        <Modal
          title="Get Messages"
          open={getMessagesModalVisible}
          onOk={handleGetMessages}
          onCancel={() => setGetMessagesModalVisible(false)}
        >
          <Form form={getMessagesForm} layout="vertical">
            <Form.Item
              name="count"
              label="Number of Messages"
              initialValue={10}
              rules={[{ required: true, message: 'Please enter message count' }]}
            >
              <InputNumber min={1} max={100} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="ackMode"
              label="Acknowledgement Mode"
              initialValue="manual"
            >
              <Select>
                <Select.Option value="manual">Manual (messages remain in queue)</Select.Option>
                <Select.Option value="auto">Auto (messages removed from queue)</Select.Option>
              </Select>
            </Form.Item>
          </Form>
        </Modal>
      </Space>
    </div>
  )
}

export default QueueDetails
