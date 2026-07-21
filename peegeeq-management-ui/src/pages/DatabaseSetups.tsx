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
    ApiOutlined,
    DisconnectOutlined,
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
    /** null = details lookup failed — count unknown, rendered as "—", never a fabricated 0 */
    queues: number | null
    eventStores: number | null
    status: 'active' | 'creating' | 'failed' | 'unavailable'
    createdAt: string
}

const DatabaseSetups = () => {
    const [setups, setSetups] = useState<DatabaseSetup[]>([])
    const [loading, setLoading] = useState(false)
    const [isModalVisible, setIsModalVisible] = useState(false)
    const [isConnectModalVisible, setIsConnectModalVisible] = useState(false)
    const [form] = Form.useForm()
    const [connectForm] = Form.useForm()
    const [viewDetailsSetup, setViewDetailsSetup] = useState<DatabaseSetup | null>(null)
    const [detailsData, setDetailsData] = useState<any | null>(null)
    const [dropTarget, setDropTarget] = useState<{ setupId: string; databaseName: string } | null>(null)
    const [dropConfirmText, setDropConfirmText] = useState('')
    const [dropInProgress, setDropInProgress] = useState(false)

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
                            // Surface the failure on the row itself: UNAVAILABLE with unknown
                            // counts — a failed details lookup must not masquerade as a
                            // healthy empty setup (no-error-swallowing rule).
                            return {
                                key: setupId,
                                setupId: setupId,
                                databaseName: setupId,
                                host: 'localhost',
                                port: 5432,
                                queues: null,
                                eventStores: null,
                                status: 'unavailable' as const,
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

    const handleDetachSetup = async (setup: DatabaseSetup) => {
        Modal.confirm({
            title: 'Detach Database Setup',
            icon: <ExclamationCircleOutlined />,
            content: (
                <div>
                    <p>Detach setup <strong>{setup.setupId}</strong> from this backend?</p>
                    <p>This is <strong>non-destructive</strong> — it releases the in-memory binding and stops the manager, but:</p>
                    <ul>
                        <li>The database <strong>{setup.databaseName}</strong> is <strong>not</strong> dropped</li>
                        <li>Its queues, event stores, and data are preserved</li>
                        <li>You can reconnect to it later</li>
                    </ul>
                    <p>Dropping the database is a separate, explicitly-guarded operation.</p>
                </div>
            ),
            okText: 'Detach',
            onOk: async () => {
                try {
                    await axios.post(getVersionedApiUrl(`setups/${setup.setupId}/detach`))
                    message.success(`Setup ${setup.setupId} detached (data preserved)`)
                    useManagementStore.getState().addNotification({ resource: setup.setupId, action: 'setup detached' })
                    await fetchSetups()
                } catch (error: any) {
                    console.error('Failed to detach setup:', error)
                    const errorMsg = error.response?.data?.error || error.message || 'Failed to detach setup'
                    message.error(errorMsg)
                }
            },
        })
    }

    const handleOpenDropModal = async (setup: DatabaseSetup) => {
        // The table row's databaseName is a placeholder (the setupId); the type-to-confirm guard needs the
        // setup's REAL database name, so resolve it from the details endpoint before opening the modal.
        try {
            const response = await axios.get(getVersionedApiUrl(`setups/${setup.setupId}`))
            const databaseName = response.data?.databaseName
            if (!databaseName) {
                message.error(`Could not resolve the database name for setup ${setup.setupId}`)
                return
            }
            setDropConfirmText('')
            setDropTarget({ setupId: setup.setupId, databaseName })
        } catch (error: any) {
            console.error('Failed to resolve database name for drop:', error)
            const errorMsg = error.response?.data?.error || error.message || 'Failed to resolve database name'
            message.error(errorMsg)
        }
    }

    const handleDropDatabase = async () => {
        if (!dropTarget) return
        setDropInProgress(true)
        try {
            // Send exactly what the user typed — the backend guard is the authority on the match.
            await axios.post(getVersionedApiUrl(`setups/${dropTarget.setupId}/database/drop`), {
                confirmDatabaseName: dropConfirmText,
            }, { timeout: 120000 })
            message.success(`Database ${dropTarget.databaseName} dropped (setup ${dropTarget.setupId})`)
            useManagementStore.getState().addNotification({ resource: dropTarget.setupId, action: 'database dropped' })
            setDropTarget(null)
            setDropConfirmText('')
            await fetchSetups()
        } catch (error: any) {
            console.error('Failed to drop database:', error)
            const errorMsg = error.response?.data?.error || error.message || 'Failed to drop database'
            message.error(errorMsg)
        } finally {
            setDropInProgress(false)
        }
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

    const handleConnectSetup = () => {
        connectForm.resetFields()
        setIsConnectModalVisible(true)
    }

    const handleConnectModalOk = async () => {
        try {
            const values = await connectForm.validateFields()

            const connectRequest = {
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
                // Ignored on connect — the backend reconstitutes queues/event stores from the schema.
                queues: [],
                eventStores: []
            }

            await axios.post(getVersionedApiUrl('database-setup/connect'), connectRequest, {
                timeout: 120000
            })

            message.success(`Connected to setup ${values.setupId}`)
            useManagementStore.getState().addNotification({ resource: values.setupId, action: 'setup connected' })
            setIsConnectModalVisible(false)
            connectForm.resetFields()
            await fetchSetups()
        } catch (error: any) {
            if (error?.errorFields) return // form validation error — keep the modal open
            console.error('Failed to connect to setup:', error)
            const errorMsg = error.response?.data?.error || error.message || 'Failed to connect to setup'
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
                key: 'detach',
                icon: <DisconnectOutlined />,
                label: 'Detach Setup',
                onClick: () => handleDetachSetup(setup),
            },
            {
                type: 'divider' as const,
            },
            {
                key: 'drop',
                icon: <DeleteOutlined />,
                label: 'Drop Database…',
                danger: true,
                onClick: () => handleOpenDropModal(setup),
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
            render: (count: number | null) => (count === null ? <Tag>—</Tag> : <Tag color="blue">{count}</Tag>),
        },
        {
            title: 'Event Stores',
            dataIndex: 'eventStores',
            key: 'eventStores',
            render: (count: number | null) => (count === null ? <Tag>—</Tag> : <Tag color="purple">{count}</Tag>),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: string) => {
                const colors = { active: 'green', creating: 'orange', failed: 'red', unavailable: 'orange' }
                const icons = { active: <CheckCircleOutlined />, creating: null, failed: <ExclamationCircleOutlined />, unavailable: <ExclamationCircleOutlined /> }
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
                        <Space>
                            <Button icon={<ApiOutlined />} onClick={handleConnectSetup} data-testid="database-setups-connect-btn">
                                Connect to Existing
                            </Button>
                            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateSetup} data-testid="database-setups-create-btn">
                                Create Setup
                            </Button>
                        </Space>
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

                {/* Connect to Existing Setup Modal */}
                <Modal
                    title="Connect to Existing Setup"
                    open={isConnectModalVisible}
                    onOk={handleConnectModalOk}
                    onCancel={() => setIsConnectModalVisible(false)}
                    width={700}
                    okText="Connect"
                >
                    <Alert
                        message="Non-destructive connect"
                        description="This attaches to an EXISTING PeeGeeQ setup. It will not create or modify any database — it connects to the existing schema and reconstitutes its queues and event stores. The password is used to connect and is not stored."
                        type="info"
                        showIcon
                        style={{ marginBottom: 16 }}
                    />

                    <Form form={connectForm} layout="vertical">
                        <Divider>Setup Information</Divider>
                        <Form.Item
                            name="setupId"
                            label="Setup ID"
                            rules={[{ required: true, message: 'Please enter setup ID' }]}
                            extra="The identifier of the existing setup to connect to"
                        >
                            <Input placeholder="e.g., production-setup" />
                        </Form.Item>

                        <Divider>Database Configuration</Divider>
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item name="host" label="Host" initialValue="localhost">
                                    <Input placeholder="localhost" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item name="port" label="Port" initialValue={5432}>
                                    <InputNumber min={1} max={65535} style={{ width: '100%' }} />
                                </Form.Item>
                            </Col>
                        </Row>

                        <Form.Item
                            name="databaseName"
                            label="Database Name"
                            rules={[{ required: true, message: 'Please enter database name' }]}
                            extra="The existing database this setup lives in"
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
                                <Form.Item name="sslEnabled" valuePropName="checked" initialValue={false}>
                                    <Checkbox>Enable SSL</Checkbox>
                                </Form.Item>
                            </Col>
                        </Row>
                    </Form>
                </Modal>

                {/* Drop Database Modal — the destructive path, type-to-confirm guarded */}
                <Modal
                    title="Drop Database"
                    open={dropTarget !== null}
                    onCancel={() => { setDropTarget(null); setDropConfirmText('') }}
                    footer={[
                        <Button key="cancel" onClick={() => { setDropTarget(null); setDropConfirmText('') }}>
                            Cancel
                        </Button>,
                        <Button
                            key="drop"
                            danger
                            type="primary"
                            loading={dropInProgress}
                            disabled={dropConfirmText !== dropTarget?.databaseName}
                            onClick={handleDropDatabase}
                            data-testid="drop-database-confirm-btn"
                        >
                            Drop Database
                        </Button>,
                    ]}
                    width={600}
                >
                    <Alert
                        message="This is irreversible"
                        description={
                            <div>
                                <p>
                                    This will <strong>permanently drop the database</strong>{' '}
                                    <strong>{dropTarget?.databaseName}</strong> for setup{' '}
                                    <strong>{dropTarget?.setupId}</strong> — all queues, event stores, and
                                    data in it will be destroyed. There is no undo.
                                </p>
                                <p style={{ marginBottom: 0 }}>
                                    If you only want to disconnect and keep the data, use <strong>Detach Setup</strong> instead.
                                </p>
                            </div>
                        }
                        type="error"
                        showIcon
                        style={{ marginBottom: 16 }}
                    />
                    <p>
                        Type the database name <strong>{dropTarget?.databaseName}</strong> to confirm:
                    </p>
                    <Input
                        value={dropConfirmText}
                        onChange={(e) => setDropConfirmText(e.target.value)}
                        placeholder={dropTarget?.databaseName}
                        data-testid="drop-database-confirm-input"
                    />
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

