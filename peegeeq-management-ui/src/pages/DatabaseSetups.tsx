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
    InputNumber,
    Row,
    Col,
    message,
    Dropdown,
    Checkbox,
    Divider,
    Alert,
    Typography,
    Descriptions
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

const { Title } = Typography

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
    const [viewDetailsSetup, setViewDetailsSetup] = useState<DatabaseSetup | null>(null)
    const [detailsData, setDetailsData] = useState<any | null>(null)

    const fetchSetups = async () => {
        setLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data.setupIds && Array.isArray(response.data.setupIds)) {
                // For each setup ID, fetch details
                const setupDetails = await Promise.all(
                    response.data.setupIds.map(async (setupId: string) => {
                        try {
                            const details = await axios.get(getVersionedApiUrl(`setups/${setupId}`))
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
                    await axios.delete(getVersionedApiUrl(`database-setup/${setup.setupId}`))
                    message.success(`Setup ${setup.setupId} deleted successfully`)
                    useManagementStore.getState().addNotification({ resource: setup.setupId, action: 'setup deleted' })
                    await fetchSetups()
                } catch (error: any) {
                    console.error('Failed to delete setup:', error)
                    const errorMsg = error.response?.data?.error || error.message || 'Failed to delete setup'
                    message.error(errorMsg)
                }
            },
        })
    }

    const handleViewDetails = async (setup: DatabaseSetup) => {
        setViewDetailsSetup(setup)
        setDetailsData(null)
        try {
            const response = await axios.get(getVersionedApiUrl(`setups/${setup.setupId}`))
            setDetailsData(response.data)
        } catch (error: any) {
            console.error('Failed to fetch setup details:', error)
            const errorMsg = error.response?.data?.error || error.message || 'Failed to load setup details'
            message.error(errorMsg)
        }
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
                    schema: values.schema,
                    sslEnabled: values.sslEnabled || false,
                    templateDatabase: 'template0',
                    encoding: 'UTF8'
                },
                queues: [],
                eventStores: []
            }

            await axios.post(getVersionedApiUrl('database-setup/create'), setupRequest, {
                timeout: 120000 // 2 minutes for database creation
            })

            message.success(`Setup ${values.setupId} created successfully`)
            useManagementStore.getState().addNotification({ resource: values.setupId, action: 'setup created' })
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
                onClick: () => handleViewDetails(setup),
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
                    <Button type="text" icon={<MoreOutlined />} data-testid={`setup-action-btn-${record.setupId}`} />
                </Dropdown>
            ),
        },
    ]

    return (
        <div className="fade-in">
            <Title level={1}>Database Setups</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                <Card
                    title="Database Setups"
                    extra={
                        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateSetup} data-testid="database-setups-create-btn">
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
                            data-testid="no-setups-alert"
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
                        data-testid="database-setups-table"
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
                                    rules={[{ required: true, message: 'Please enter schema' }]}
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

                {/* Setup Details Modal */}
                <Modal
                    title="Setup Details"
                    open={viewDetailsSetup !== null}
                    onCancel={() => { setViewDetailsSetup(null); setDetailsData(null) }}
                    footer={[
                        <Button key="close" onClick={() => { setViewDetailsSetup(null); setDetailsData(null) }}>
                            Close
                        </Button>,
                    ]}
                    width={600}
                >
                    <div data-testid="setup-details-modal">
                        <Descriptions bordered column={1} size="small">
                            <Descriptions.Item label="Setup ID">{viewDetailsSetup?.setupId}</Descriptions.Item>
                            <Descriptions.Item label="Status">
                                {detailsData?.status
                                    ? <Tag color="green">{String(detailsData.status).toUpperCase()}</Tag>
                                    : '—'}
                            </Descriptions.Item>
                            <Descriptions.Item label="Host">{detailsData?.host ?? '—'}</Descriptions.Item>
                            <Descriptions.Item label="Port">{detailsData?.port ?? '—'}</Descriptions.Item>
                            <Descriptions.Item label="Database Name">{detailsData?.databaseName ?? '—'}</Descriptions.Item>
                            <Descriptions.Item label="Schema">{detailsData?.schema ?? '—'}</Descriptions.Item>
                            <Descriptions.Item label="Queues">{detailsData?.queueFactories?.length ?? 0}</Descriptions.Item>
                            <Descriptions.Item label="Event Stores">{detailsData?.eventStores?.length ?? 0}</Descriptions.Item>
                        </Descriptions>
                    </div>
                </Modal>
            </Space>
        </div>
    )
}

export default DatabaseSetups

