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
  InputNumber,
  Row,
  Col,
  message,
  Dropdown,
  Checkbox,
  Divider,
  Alert
} from 'antd'
import {
  PlusOutlined,
  DeleteOutlined,
  EyeOutlined,
  MoreOutlined,
  DatabaseOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined
} from '@ant-design/icons'

interface DatabaseSetup {
  key: string
  setupId: string
  databaseName: string
  host: string
  port: number
  queues: number
  eventStores: number
  status: 'active' | 'creating' | 'failed'
  createdAt: string
}

const DatabaseSetups = () => {
  const [setups, setSetups] = useState<DatabaseSetup[]>([])
  const [loading, setLoading] = useState(false)
  const [isModalVisible, setIsModalVisible] = useState(false)
  const [form] = Form.useForm()

  const fetchSetups = async () => {
    setLoading(true)
    try {
      const response = await axios.get('/api/v1/setups')
      if (response.data.setupIds && Array.isArray(response.data.setupIds)) {
        // For each setup ID, fetch details
        const setupDetails = await Promise.all(
          response.data.setupIds.map(async (setupId: string) => {
            try {
              const details = await axios.get(`/api/v1/setups/${setupId}`)
              return {
                key: setupId,
                setupId: setupId,
                databaseName: setupId, // Simplified - would need actual DB name from backend
                host: 'localhost',
                port: 5432,
                queues: details.data.queueFactories?.length || 0,
                eventStores: details.data.eventStores?.length || 0,
                status: details.data.status?.toLowerCase() || 'active',
                createdAt: new Date().toISOString()
              }
            } catch {
              return {
                key: setupId,
                setupId: setupId,
                databaseName: setupId,
                host: 'localhost',
                port: 5432,
                queues: 0,
                eventStores: 0,
                status: 'active' as const,
                createdAt: new Date().toISOString()
              }
            }
          })
        )
        setSetups(setupDetails)
      }
    } catch (error) {
      console.error('Failed to fetch setups:', error)
      message.error('Failed to load database setups')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchSetups()
  }, [])

  const handleCreateSetup = () => {
    form.resetFields()
    setIsModalVisible(true)
  }

  const handleDeleteSetup = async (setup: DatabaseSetup) => {
    Modal.confirm({
      title: 'Delete Database Setup',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>Are you sure you want to delete setup <strong>{setup.setupId}</strong>?</p>
          <p>This will:</p>
          <ul>
            <li>Delete the database <strong>{setup.databaseName}</strong></li>
            <li>Remove all {setup.queues} queues</li>
            <li>Remove all {setup.eventStores} event stores</li>
            <li>This action cannot be undone</li>
          </ul>
        </div>
      ),
      okText: 'Delete',
      okType: 'danger',
      onOk: async () => {
        try {
          await axios.delete(`/api/v1/database-setup/${setup.setupId}`)
          message.success(`Setup ${setup.setupId} deleted successfully`)
          await fetchSetups()
        } catch (error) {
          console.error('Failed to delete setup:', error)
          message.error('Failed to delete setup')
        }
      },
    })
  }

  const handleModalOk = async () => {
    try {
      const values = await form.validateFields()

      const setupRequest = {
        setupId: values.setupId,
        databaseConfig: {
          host: values.host || 'localhost',
          port: values.port || 5432,
          databaseName: values.databaseName,
          username: values.username,
          password: values.password,
          schema: values.schema || 'public',
          sslEnabled: values.sslEnabled || false,
          templateDatabase: 'template0',
          encoding: 'UTF8'
        },
        queues: [],
        eventStores: []
      }

      await axios.post('/api/v1/database-setup/create', setupRequest, {
        timeout: 120000 // 2 minutes for database creation
      })

      message.success(`Setup ${values.setupId} created successfully`)
      setIsModalVisible(false)
      form.resetFields()
      await fetchSetups()
    } catch (error: any) {
      console.error('Failed to create setup:', error)
      const errorMsg = error.response?.data?.error || error.message || 'Failed to create setup'
      message.error(errorMsg)
    }
  }

  const getActionMenu = (setup: DatabaseSetup) => ({
    items: [
      {
        key: 'view',
        icon: <EyeOutlined />,
        label: 'View Details',
        onClick: () => message.info('View details coming soon'),
      },
      {
        type: 'divider' as const,
      },
      {
        key: 'delete',
        icon: <DeleteOutlined />,
        label: 'Delete Setup',
        danger: true,
        onClick: () => handleDeleteSetup(setup),
      },
    ],
  })

  const columns = [
    {
      title: 'Setup ID',
      dataIndex: 'setupId',
      key: 'setupId',
      render: (text: string) => (
        <Space>
          <DatabaseOutlined />
          <strong>{text}</strong>
        </Space>
      ),
    },
    {
      title: 'Database',
      dataIndex: 'databaseName',
      key: 'databaseName',
    },
    {
      title: 'Connection',
      key: 'connection',
      render: (record: DatabaseSetup) => `${record.host}:${record.port}`,
    },
    {
      title: 'Queues',
      dataIndex: 'queues',
      key: 'queues',
      render: (count: number) => <Tag color="blue">{count}</Tag>,
    },
    {
      title: 'Event Stores',
      dataIndex: 'eventStores',
      key: 'eventStores',
      render: (count: number) => <Tag color="purple">{count}</Tag>,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const colors = { active: 'green', creating: 'orange', failed: 'red' }
        const icons = { active: <CheckCircleOutlined />, creating: null, failed: <ExclamationCircleOutlined /> }
        return (
          <Tag color={colors[status as keyof typeof colors]} icon={icons[status as keyof typeof icons]}>
            {status.toUpperCase()}
          </Tag>
        )
      },
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (record: DatabaseSetup) => (
        <Dropdown menu={getActionMenu(record)} trigger={['click']}>
          <Button type="text" icon={<MoreOutlined />} />
        </Dropdown>
      ),
    },
  ]

  return (
    <div className="fade-in">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card
          title="Database Setups"
          extra={
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateSetup}>
              Create Setup
            </Button>
          }
        >
          {setups.length === 0 && !loading && (
            <Alert
              message="No Database Setups Found"
              description="Create your first database setup to start using PeeGeeQ. A setup represents a database configuration with queues and event stores."
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
            />
          )}
          
          <Table
            columns={columns}
            dataSource={setups}
            loading={loading}
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              showTotal: (total) => `${total} setups`,
            }}
          />
        </Card>

        {/* Create Setup Modal */}
        <Modal
          title="Create Database Setup"
          open={isModalVisible}
          onOk={handleModalOk}
          onCancel={() => setIsModalVisible(false)}
          width={700}
          okText="Create Setup"
        >
          <Alert
            message="Database Permissions Required"
            description="Creating a setup will create a new PostgreSQL database. Ensure your database user has CREATEDB permission."
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
          />
          
          <Form form={form} layout="vertical">
            <Divider>Setup Information</Divider>
            <Form.Item
              name="setupId"
              label="Setup ID"
              rules={[{ required: true, message: 'Please enter setup ID' }]}
              extra="Unique identifier for this setup (e.g., production, staging, e2e-test)"
            >
              <Input placeholder="e.g., production-setup" />
            </Form.Item>

            <Divider>Database Configuration</Divider>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="host"
                  label="Host"
                  initialValue="localhost"
                >
                  <Input placeholder="localhost" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="port"
                  label="Port"
                  initialValue={5432}
                >
                  <InputNumber min={1} max={65535} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              name="databaseName"
              label="Database Name"
              rules={[{ required: true, message: 'Please enter database name' }]}
              extra="A new database will be created with this name"
            >
              <Input placeholder="e.g., peegeeq_production" />
            </Form.Item>

            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="username"
                  label="Username"
                  rules={[{ required: true, message: 'Please enter username' }]}
                  initialValue="peegeeq"
                >
                  <Input />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="password"
                  label="Password"
                  rules={[{ required: true, message: 'Please enter password' }]}
                >
                  <Input.Password />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="schema"
                  label="Schema"
                  initialValue="public"
                >
                  <Input />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="sslEnabled"
                  valuePropName="checked"
                  initialValue={false}
                >
                  <Checkbox>Enable SSL</Checkbox>
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Modal>
      </Space>
    </div>
  )
}

export default DatabaseSetups

