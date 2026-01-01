import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
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
    message,
    Dropdown
} from 'antd'
import {
    PlusOutlined,
    DeleteOutlined,
    EditOutlined,
    EyeOutlined,
    MoreOutlined,
    InboxOutlined,
    SendOutlined,
    DownloadOutlined,
    UserOutlined,
} from '@ant-design/icons'

interface Queue {
    key: string
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
}



interface DatabaseSetup {
    setupId: string
    host: string
    port: number
    database: string
    schema: string
}

const Queues = () => {
    const [queues, setQueues] = useState<Queue[]>([])
    const [setups, setSetups] = useState<DatabaseSetup[]>([])
    const [loading, setLoading] = useState(true)
    const [setupsLoading, setSetupsLoading] = useState(false)
    const [isModalVisible, setIsModalVisible] = useState(false)
    const [selectedQueue, setSelectedQueue] = useState<Queue | null>(null)
    const [form] = Form.useForm()

    const fetchQueues = async () => {
        setLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('management/queues'))
            if (response.data.queues && Array.isArray(response.data.queues)) {
                setQueues(response.data.queues.map((queue: any) => ({
                    key: `${queue.setup}-${queue.name}`,
                    name: queue.name,
                    setup: queue.setup,
                    messages: queue.messages,
                    consumers: queue.consumers,
                    messageRate: queue.messageRate || 0,
                    consumerRate: queue.consumerRate || 0,
                    status: queue.status,
                    durability: queue.durability || 'durable',
                    autoDelete: queue.autoDelete || false,
                    createdAt: queue.createdAt ? new Date(queue.createdAt).toLocaleString() : 'Unknown',
                })))
            }
        } catch (error) {
            console.error('Failed to fetch queues:', error)
            // Show error message instead of mock data
            message.error('Failed to load queues. Please check if the backend service is running.')
        } finally {
            setLoading(false)
        }
    }

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                // Convert setupIds array to DatabaseSetup objects
                setSetups(response.data.setupIds.map((setupId: string) => ({
                    setupId,
                    host: 'localhost',
                    port: 5432,
                    database: 'postgres',
                    schema: setupId
                })))
            }
        } catch (error) {
            console.error('Failed to fetch database setups:', error)
            message.error('Failed to load database setups')
        } finally {
            setSetupsLoading(false)
        }
    }

    useEffect(() => {
        fetchQueues()
        // Refresh every 30 seconds
        const interval = setInterval(fetchQueues, 30000)
        return () => clearInterval(interval)
    }, [])

    useEffect(() => {
        fetchSetups()
    }, [])

    const handleCreateQueue = () => {
        setSelectedQueue(null)
        form.resetFields()
        fetchSetups() // Refresh setups when opening modal
        setIsModalVisible(true)
    }

    const handleEditQueue = (queue: Queue) => {
        setSelectedQueue(queue)
        form.setFieldsValue(queue)
        setIsModalVisible(true)
    }

    const handleDeleteQueue = async (queue: Queue) => {
        Modal.confirm({
            title: 'Delete Queue',
            content: `Are you sure you want to delete queue "${queue.name}"? This action cannot be undone.`,
            okText: 'Delete',
            okType: 'danger',
            onOk: async () => {
                try {
                    await axios.delete(getVersionedApiUrl(`management/queues/${queue.key}`))
                    // Refresh the queue list after successful deletion
                    await fetchQueues()
                } catch (error) {
                    console.error('Failed to delete queue:', error)
                    // For now, still remove from local state as fallback
                    setQueues(queues.filter(q => q.key !== queue.key))
                }
            },
        })
    }

    const handleModalOk = async () => {
        try {
            const values = await form.validateFields()

            if (selectedQueue) {
                // Update existing queue - not implemented yet
                message.warning('Queue editing is not yet implemented')
                return
            } else {
                // Create new queue - call correct API endpoint
                const requestBody = {
                    setup: values.setup,  // Backend expects "setup" not "setupId"
                    name: values.name,
                    type: 'native'
                }

                await axios.post(getVersionedApiUrl(`management/queues`), requestBody)
                message.success(`Queue "${values.name}" created successfully`)
            }

            // Refresh the queue list after successful operation
            await fetchQueues()
            setIsModalVisible(false)
            setSelectedQueue(null)
            form.resetFields()
        } catch (error: any) {
            console.error('Failed to save queue:', error)
            message.error(error.response?.data?.message || 'Failed to create queue')
            // Keep modal open on error so user can retry
        }
    }

    const getActionMenu = (queue: Queue) => ({
        items: [
            {
                key: 'view',
                icon: <EyeOutlined />,
                label: 'View Details',
                onClick: () => {/* console.log('View', queue.name) */ },
            },
            {
                key: 'edit',
                icon: <EditOutlined />,
                label: 'Edit',
                onClick: () => handleEditQueue(queue),
            },
            {
                type: 'divider' as const,
            },
            {
                key: 'delete',
                icon: <DeleteOutlined />,
                label: 'Delete',
                danger: true,
                onClick: () => handleDeleteQueue(queue),
            },
        ],
    })

    const columns = [
        {
            title: 'Queue Name',
            dataIndex: 'name',
            key: 'name',
            render: (text: string, record: Queue) => (
                <Space direction="vertical" size="small">
                    <Space>
                        <Link to={`/queues/${record.setup}/${record.name}`} style={{ fontWeight: 'bold' }}>
                            {text}
                        </Link>
                        <Tag color="blue">{record.setup}</Tag>
                    </Space>
                    <Space size="small">
                        <Tag color={record.durability === 'durable' ? 'green' : 'orange'}>
                            {record.durability}
                        </Tag>
                        {record.autoDelete && <Tag color="red">auto-delete</Tag>}
                    </Space>
                </Space>
            ),
        },
        {
            title: 'Messages',
            dataIndex: 'messages',
            key: 'messages',
            render: (value: number) => (
                <Statistic
                    value={value}
                    valueStyle={{ fontSize: '14px' }}
                    prefix={<InboxOutlined />}
                />
            ),
        },
        {
            title: 'Consumers',
            dataIndex: 'consumers',
            key: 'consumers',
            render: (value: number) => (
                <Statistic
                    value={value}
                    valueStyle={{ fontSize: '14px' }}
                    prefix={<UserOutlined />}
                />
            ),
        },
        {
            title: 'Message Rate',
            key: 'rates',
            render: (record: Queue) => (
                <Space direction="vertical" size="small">
                    <div>
                        <SendOutlined style={{ color: '#1890ff', marginRight: 4 }} />
                        {record.messageRate.toFixed(1)} msg/s
                    </div>
                    <div>
                        <DownloadOutlined style={{ color: '#52c41a', marginRight: 4 }} />
                        {record.consumerRate.toFixed(1)} msg/s
                    </div>
                </Space>
            ),
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: string) => {
                const colors = { active: 'green', idle: 'orange', error: 'red' }
                return <Tag color={colors[status as keyof typeof colors]}>{status.toUpperCase()}</Tag>
            },
        },
        {
            title: 'Created',
            dataIndex: 'createdAt',
            key: 'createdAt',
            render: (text: string) => new Date(text).toLocaleDateString(),
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (record: Queue) => (
                <Dropdown menu={getActionMenu(record)} trigger={['click']}>
                    <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            ),
        },
    ]

    // Calculate summary statistics
    const totalMessages = queues.reduce((sum, q) => sum + q.messages, 0)

    const activeQueues = queues.filter(q => q.status === 'active').length
    const avgMessageRate = queues.reduce((sum, q) => sum + q.messageRate, 0) / queues.length

    return (
        <div className="fade-in">
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* Summary Cards */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Total Queues"
                                value={queues.length}
                                prefix={<InboxOutlined style={{ color: '#1890ff' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Active Queues"
                                value={activeQueues}
                                prefix={<InboxOutlined style={{ color: '#52c41a' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Total Messages"
                                value={totalMessages}
                                prefix={<SendOutlined style={{ color: '#722ed1' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Avg Rate"
                                value={avgMessageRate.toFixed(1)}
                                suffix="msg/s"
                                prefix={<DownloadOutlined style={{ color: '#fa8c16' }} />}
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Queue Table */}
                <Card
                    title="Queues"
                    extra={
                        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateQueue}>
                            Create Queue
                        </Button>
                    }
                >
                    <Table
                        columns={columns}
                        dataSource={queues}
                        loading={loading}
                        pagination={{
                            pageSize: 10,
                            showSizeChanger: true,
                            showQuickJumper: true,
                            showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} queues`,
                        }}
                        locale={{
                            emptyText: loading ? 'Loading...' : 'No queues found. Please check if the backend service is running and has active setups.'
                        }}
                    />
                </Card>

                {/* Create/Edit Modal */}
                <Modal
                    title={selectedQueue ? 'Edit Queue' : 'Create Queue'}
                    open={isModalVisible}
                    onOk={handleModalOk}
                    onCancel={() => setIsModalVisible(false)}
                    width={600}
                >
                    <Form form={form} layout="vertical">
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item
                                    name="name"
                                    label="Queue Name"
                                    rules={[{ required: true, message: 'Please enter queue name' }]}
                                >
                                    <Input placeholder="e.g., orders, payments" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="setup"
                                    label="Setup"
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
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item
                                    name="durability"
                                    label="Durability"
                                    initialValue="durable"
                                >
                                    <Select>
                                        <Select.Option value="durable">Durable</Select.Option>
                                        <Select.Option value="transient">Transient</Select.Option>
                                    </Select>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="autoDelete"
                                    label="Auto Delete"
                                    valuePropName="checked"
                                    initialValue={false}
                                >
                                    <Select>
                                        <Select.Option value={false}>No</Select.Option>
                                        <Select.Option value={true}>Yes</Select.Option>
                                    </Select>
                                </Form.Item>
                            </Col>
                        </Row>
                    </Form>
                </Modal>
            </Space>
        </div>
    )
}

export default Queues
