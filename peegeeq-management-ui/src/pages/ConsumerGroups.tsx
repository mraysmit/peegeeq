import React, { useState, useEffect } from 'react'
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
    Progress,
    Tooltip,
    Dropdown,
    Typography,
    Descriptions,
    message
} from 'antd'
import {
    TeamOutlined,
    PlusOutlined,
    DeleteOutlined,
    EyeOutlined,
    MoreOutlined,
    ClockCircleOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    PauseCircleOutlined,
    PlayCircleOutlined,
    StopOutlined,
    ReloadOutlined,
    ApiOutlined,
    HeartOutlined,
    HistoryOutlined
} from '@ant-design/icons'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

import SetupScopeBar from '../components/common/SetupScopeBar'
import { useManagementStore } from '../stores/managementStore'

const { Text, Title } = Typography

interface ConsumerGroup {
    key: string
    groupName: string
    setupId: string
    queueName: string
    memberCount: number
    implementationType: string
    status: 'active' | 'paused' | 'dead' | 'cancelled'
    createdAt: string
    subscribedAt: string
    lastActiveAt: string
    lastHeartbeatAt: string | null
    backfillStatus: string
    lag: number
    backfillProcessedMessages?: number
    backfillTotalMessages?: number
    backfillStartedAt?: string
    backfillCompletedAt?: string
}






const ConsumerGroups: React.FC = () => {
    const { selectedSetupId, selectedQueueName } = useManagementStore()
    const [consumerGroups, setConsumerGroups] = useState<ConsumerGroup[]>([])
    const [selectedGroup, setSelectedGroup] = useState<ConsumerGroup | null>(null)
    const [isCreateModalVisible, setIsCreateModalVisible] = useState(false)
    const [isDetailsModalVisible, setIsDetailsModalVisible] = useState(false)
    const [loading, setLoading] = useState(true)
    const [setups, setSetups] = useState<{ setupId: string }[]>([])
    const [setupsLoading, setSetupsLoading] = useState(false)
    const [form] = Form.useForm()

    const fetchConsumerGroups = async () => {
        setLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('management/consumer-groups'))
            if (response.data.consumerGroups && Array.isArray(response.data.consumerGroups)) {
                setConsumerGroups(response.data.consumerGroups.map((group: any, index: number) => ({
                    key: index.toString(),
                    groupName: group.name,
                    setupId: group.setup,
                    queueName: group.queueName,
                    memberCount: group.members || 0,
                    implementationType: group.implementationType || 'NATIVE_QUEUE',
                    status: group.status,
                    createdAt: group.createdAt || new Date().toISOString(),
                    subscribedAt: group.subscribedAt || group.createdAt || new Date().toISOString(),
                    lastActiveAt: group.lastActiveAt || group.createdAt || new Date().toISOString(),
                    lastHeartbeatAt: group.lastHeartbeatAt || null,
                    backfillStatus: group.backfillStatus || 'NONE',
                    lag: group.lag || 0,
                    backfillProcessedMessages: group.backfillProcessedMessages,
                    backfillTotalMessages: group.backfillTotalMessages,
                    backfillStartedAt: group.backfillStartedAt,
                    backfillCompletedAt: group.backfillCompletedAt,
                })))
            }
        } catch (error) {
            console.error('Failed to fetch consumer groups:', error)
            message.error('Failed to load consumer groups. Please check if the backend service is running.')
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        fetchConsumerGroups()
        const interval = setInterval(fetchConsumerGroups, 30000)
        return () => clearInterval(interval)
    }, [])

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetups(response.data.setupIds.map((setupId: string) => ({ setupId })))
            } else {
                setSetups([])
            }
        } catch (error) {
            console.error('Failed to fetch database setups:', error)
            setSetups([])
        } finally {
            setSetupsLoading(false)
        }
    }

    const fetchQueuesForSetup = async (_setupId: string) => {
        // queue suggestions removed — Input accepts free text
    }

    useEffect(() => {
        if (isCreateModalVisible) {
            form.resetFields()
            if (selectedSetupId) {
                form.setFieldValue('setupId', selectedSetupId)
                fetchQueuesForSetup(selectedSetupId)
            }
            if (selectedQueueName) {
                form.setFieldValue('queueName', selectedQueueName)
            }
        }
    }, [isCreateModalVisible])

    const handleCreateGroup = () => {
        fetchSetups()
        setIsCreateModalVisible(true)
    }

    const handleViewDetails = (group: ConsumerGroup) => {
        setSelectedGroup(group)
        setIsDetailsModalVisible(true)
    }

    const handleDeleteGroup = (group: ConsumerGroup) => {
        Modal.confirm({
            title: 'Delete Consumer Group',
            content: `Are you sure you want to delete consumer group "${group.groupName}"? This will cancel the subscription.`,
            okText: 'Delete',
            okType: 'danger',
            onOk: async () => {
                try {
                    await axios.delete(getVersionedApiUrl(
                        `management/consumer-groups/${group.setupId}/${group.queueName}/${group.groupName}`
                    ))
                    fetchConsumerGroups()
                } catch (error) {
                    console.error('Failed to delete consumer group:', error)
                    message.error('Failed to delete consumer group.')
                }
            },
        })
    }

    const handleCreateModalOk = () => {
        form.validateFields().then(async values => {
            try {
                await axios.post(getVersionedApiUrl('management/consumer-groups'), {
                    name: values.groupName,
                    setup: values.setupId,
                    queueName: values.queueName,
                })
                setIsCreateModalVisible(false)
                form.resetFields()
                fetchConsumerGroups()
            } catch (error) {
                console.error('Failed to create consumer group:', error)
                message.error('Failed to create consumer group. Please check if the backend service is running.')
            }
        })
    }

    const getStatusColor = (status: string) => {
        const colors: Record<string, string> = {
            active: 'green',
            paused: 'orange',
            dead: 'red',
            cancelled: 'default'
        }
        return colors[status] || 'default'
    }

    const getStatusIcon = (status: string) => {
        const icons: Record<string, React.ReactNode> = {
            active: <CheckCircleOutlined />,
            paused: <PauseCircleOutlined />,
            dead: <ExclamationCircleOutlined />,
            cancelled: <StopOutlined />
        }
        return icons[status]
    }

    const getBackfillColor = (status: string) => {
        const colors: Record<string, string> = {
            NONE: 'default',
            IN_PROGRESS: 'blue',
            COMPLETED: 'green',
            FAILED: 'red'
        }
        return colors[status] || 'default'
    }

    const handlePauseGroup = async (group: ConsumerGroup) => {
        try {
            await axios.post(getVersionedApiUrl(
                `management/consumer-groups/${group.setupId}/${group.queueName}/${group.groupName}/pause`
            ))
            fetchConsumerGroups()
        } catch (error) {
            console.error('Failed to pause consumer group:', error)
            message.error('Failed to pause consumer group.')
        }
    }

    const handleResumeGroup = async (group: ConsumerGroup) => {
        try {
            await axios.post(getVersionedApiUrl(
                `management/consumer-groups/${group.setupId}/${group.queueName}/${group.groupName}/resume`
            ))
            fetchConsumerGroups()
        } catch (error) {
            console.error('Failed to resume consumer group:', error)
            message.error('Failed to resume consumer group.')
        }
    }

    const handleBackfillGroup = async (group: ConsumerGroup) => {
        try {
            await axios.post(getVersionedApiUrl(
                `management/consumer-groups/${group.setupId}/${group.queueName}/${group.groupName}/backfill`
            ))
            message.success(`Backfill started for group '${group.groupName}'`)
            fetchConsumerGroups()
        } catch (error) {
            console.error('Failed to start backfill:', error)
            message.error('Failed to start backfill.')
        }
    }

    const getActionMenu = (group: ConsumerGroup) => ({
        items: [
            {
                key: 'view',
                icon: <EyeOutlined />,
                label: 'View Details',
                onClick: () => handleViewDetails(group),
            },
            ...(group.status === 'active' ? [{
                key: 'pause',
                icon: <PauseCircleOutlined />,
                label: 'Pause Group',
                onClick: () => handlePauseGroup(group),
            }] : []),
            ...(group.status === 'paused' ? [{
                key: 'resume',
                icon: <PlayCircleOutlined />,
                label: 'Resume Group',
                onClick: () => handleResumeGroup(group),
            }] : []),
            ...((group.status === 'active' || group.status === 'paused') && group.backfillStatus !== 'IN_PROGRESS' ? [{
                key: 'backfill',
                icon: <HistoryOutlined />,
                label: 'Start Backfill',
                onClick: () => handleBackfillGroup(group),
            }] : []),
            {
                type: 'divider' as const,
            },
            {
                key: 'delete',
                icon: <DeleteOutlined />,
                label: 'Delete Group',
                danger: true,
                onClick: () => handleDeleteGroup(group),
            },
        ],
    })

    const columns = [
        {
            title: 'Group Name',
            dataIndex: 'groupName',
            key: 'groupName',
            render: (text: string, record: ConsumerGroup) => (
                <Space direction="vertical" size="small">
                    <Space>
                        <strong>{text}</strong>
                        <Tag color="blue">{record.setupId}</Tag>
                    </Space>
                    <Space size="small">
                        <Tag color="purple">{record.queueName}</Tag>
                        <Tag color={record.implementationType === 'OUTBOX' ? 'gold' : 'geekblue'}>
                            {record.implementationType}
                        </Tag>
                    </Space>
                </Space>
            ),
        },
        {
            title: 'Members',
            dataIndex: 'memberCount',
            key: 'memberCount',
            render: (count: number) => <Text>{count}</Text>,
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            render: (status: string, record: ConsumerGroup) => (
                <Space direction="vertical" size="small">
                    <Tag color={getStatusColor(status)} icon={getStatusIcon(status)}>
                        {status.toUpperCase()}
                    </Tag>
                    {record.lastHeartbeatAt && (
                        <Tooltip title={`Last heartbeat: ${dayjs(record.lastHeartbeatAt).format('MMM DD, HH:mm')}`}>
                            <Text type="secondary" style={{ fontSize: '11px' }}>
                                <HeartOutlined /> {dayjs(record.lastHeartbeatAt).fromNow()}
                            </Text>
                        </Tooltip>
                    )}
                </Space>
            ),
        },
        {
            title: 'Heartbeat',
            dataIndex: 'lastHeartbeatAt',
            key: 'lastHeartbeatAt',
            render: (value: string | null) =>
                value
                    ? (
                        <Tooltip title={value}>
                            <Space>
                                <HeartOutlined style={{ color: '#52c41a' }} />
                                <Text>{dayjs(value).fromNow()}</Text>
                            </Space>
                        </Tooltip>
                    )
                    : <Text type="secondary">Never</Text>,
        },
        {
            title: 'Subscribed',
            dataIndex: 'subscribedAt',
            key: 'subscribedAt',
            render: (text: string) => (
                <Tooltip title={text}>
                    <Text>{dayjs(text).format('MMM DD, YYYY')}</Text>
                </Tooltip>
            ),
        },
        {
            title: 'Backfill',
            dataIndex: 'backfillStatus',
            key: 'backfillStatus',
            render: (status: string) => (
                <Tag color={getBackfillColor(status)}>{status}</Tag>
            ),
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (record: ConsumerGroup) => (
                <Dropdown menu={getActionMenu(record)} trigger={['click']}>
                    <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            ),
        },
    ]

    // Calculate summary statistics
    const filteredGroups = consumerGroups
        .filter(g => !selectedSetupId || g.setupId === selectedSetupId)
        .filter(g => !selectedQueueName || g.queueName === selectedQueueName)
    const totalGroups = filteredGroups.length
    const activeGroups = filteredGroups.filter(g => g.status === 'active').length
    const deadGroups = filteredGroups.filter(g => g.status === 'dead').length
    const backfillActive = filteredGroups.filter(g => g.backfillStatus === 'IN_PROGRESS').length

    return (
        <div className="fade-in">
            <Title level={1}>Consumer Groups</Title>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* Setup + Queue scope selector */}
                <SetupScopeBar mode="setup+queue" />

                {/* Summary Cards */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Total Groups"
                                value={totalGroups}
                                prefix={<TeamOutlined style={{ color: '#1890ff' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Active Groups"
                                value={activeGroups}
                                prefix={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Dead Groups"
                                value={deadGroups}
                                prefix={<ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />}
                            />
                        </Card>
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <Card>
                            <Statistic
                                title="Backfill Active"
                                value={backfillActive}
                                prefix={<ReloadOutlined style={{ color: '#1890ff' }} />}
                            />
                        </Card>
                    </Col>
                </Row>

                {/* Consumer Groups Table */}
                <Card
                    title="Consumer Groups"
                    extra={
                        <Space>
                            <Button
                                icon={<ReloadOutlined />}
                                loading={loading}
                                onClick={fetchConsumerGroups}
                            >
                                Refresh
                            </Button>
                            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateGroup} data-testid="create-group-btn">
                                Create Group
                            </Button>
                        </Space>
                    }
                >
                    <Table
                        data-testid="consumer-groups-table"
                        columns={columns}
                        dataSource={filteredGroups}
                        pagination={{
                            pageSize: 10,
                            showSizeChanger: true,
                            showQuickJumper: true,
                            showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} consumer groups`,
                        }}
                        loading={loading}
                        locale={{
                            emptyText: loading ? 'Loading...' : 'No consumer groups found. Please check if the backend service is running and has active setups.'
                        }}
                    />
                </Card>

                {/* Create Consumer Group Modal */}
                <Modal
                    title="Create Consumer Group"
                    open={isCreateModalVisible}
                    onOk={handleCreateModalOk}
                    onCancel={() => setIsCreateModalVisible(false)}
                    width={600}
                >
                    <Form
                        form={form}
                        layout="vertical"
                        onValuesChange={(changed) => {
                            if (changed.setupId) {
                                form.setFieldValue('queueName', undefined)
                                fetchQueuesForSetup(changed.setupId)
                            }
                        }}
                    >
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item
                                    name="groupName"
                                    label="Group Name"
                                    rules={[{ required: true, message: 'Please enter group name' }]}
                                >
                                    <Input placeholder="e.g., order-processors" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="setupId"
                                    label="Setup"
                                    rules={[{ required: true, message: 'Please select setup' }]}
                                >
                                    <Select
                                        placeholder="Select setup"
                                        loading={setupsLoading}
                                        data-testid="create-group-setup-select"
                                        options={setups.map(s => ({ value: s.setupId, label: s.setupId }))}
                                    />
                                </Form.Item>
                            </Col>
                        </Row>
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item
                                    name="queueName"
                                    label="Queue Name"
                                    rules={[{ required: true, message: 'Please enter queue name' }]}
                                >
                                    <Input
                                        placeholder="Type queue name"
                                        data-testid="create-group-queue-input"
                                    />
                                </Form.Item>
                            </Col>
                        </Row>
                    </Form>
                </Modal>

                {/* Consumer Group Details Modal */}
                <Modal
                    title={
                        <Space>
                            <TeamOutlined />
                            <span>Consumer Group Details</span>
                            {selectedGroup && (
                                <Tag color={getStatusColor(selectedGroup.status)}>
                                    {selectedGroup.status.toUpperCase()}
                                </Tag>
                            )}
                        </Space>
                    }
                    open={isDetailsModalVisible}
                    onCancel={() => setIsDetailsModalVisible(false)}
                    width={1000}
                    footer={[
                        <Button key="close" onClick={() => setIsDetailsModalVisible(false)}>
                            Close
                        </Button>
                    ]}
                >
                    {selectedGroup && (
                        <Space direction="vertical" size="large" style={{ width: '100%' }}>
                            {/* Group Information */}
                            <Card size="small" title="Group Information">
                                <Descriptions column={2} size="small">
                                    <Descriptions.Item label="Group Name">
                                        <Text strong>{selectedGroup.groupName}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Status">
                                        <Tag color={getStatusColor(selectedGroup.status)} icon={getStatusIcon(selectedGroup.status)}>
                                            {selectedGroup.status.toUpperCase()}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Setup">
                                        <Tag color="blue">{selectedGroup.setupId}</Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Queue">
                                        <Tag color="purple">{selectedGroup.queueName}</Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Implementation Type">
                                        <Tag color={selectedGroup.implementationType === 'OUTBOX' ? 'gold' : 'geekblue'}>
                                            {selectedGroup.implementationType}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Backfill Status">
                                        <Tag color={getBackfillColor(selectedGroup.backfillStatus)}>
                                            {selectedGroup.backfillStatus}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Subscribed At">
                                        <Text>{dayjs(selectedGroup.subscribedAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Last Heartbeat">
                                        <Text>
                                            {selectedGroup.lastHeartbeatAt
                                                ? dayjs(selectedGroup.lastHeartbeatAt).format('YYYY-MM-DD HH:mm:ss')
                                                : 'Never'
                                            }
                                        </Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Created">
                                        <Text>{dayjs(selectedGroup.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                                    </Descriptions.Item>
                                </Descriptions>
                            </Card>

                            {/* Subscription Details */}
                            <Row gutter={[16, 16]}>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Subscribed At"
                                            value={dayjs(selectedGroup.subscribedAt).format('MMM DD')}
                                            prefix={<ClockCircleOutlined />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Last Active"
                                            value={dayjs(selectedGroup.lastActiveAt).fromNow()}
                                            prefix={<ReloadOutlined />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Last Heartbeat"
                                            value={selectedGroup.lastHeartbeatAt ? dayjs(selectedGroup.lastHeartbeatAt).fromNow() : 'Never'}
                                            prefix={<HeartOutlined style={{ color: selectedGroup.lastHeartbeatAt ? '#52c41a' : '#ff4d4f' }} />}
                                        />
                                    </Card>
                                </Col>
                                <Col span={6}>
                                    <Card size="small">
                                        <Statistic
                                            title="Consumer Lag"
                                            value={selectedGroup.lag}
                                            prefix={<ApiOutlined />}
                                        />
                                    </Card>
                                </Col>
                            </Row>

                            {/* Backfill Details */}
                            <Card size="small" title="Backfill Details">
                                <Descriptions column={2} size="small">
                                    <Descriptions.Item label="Backfill Status">
                                        <Tag color={getBackfillColor(selectedGroup.backfillStatus)}>
                                            {selectedGroup.backfillStatus}
                                        </Tag>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Processed Messages">
                                        <Text>{selectedGroup.backfillProcessedMessages ?? 'N/A'}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Total Messages">
                                        <Text>{selectedGroup.backfillTotalMessages ?? 'N/A'}</Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Started At">
                                        <Text>
                                            {selectedGroup.backfillStartedAt
                                                ? dayjs(selectedGroup.backfillStartedAt).format('YYYY-MM-DD HH:mm:ss')
                                                : 'N/A'
                                            }
                                        </Text>
                                    </Descriptions.Item>
                                    <Descriptions.Item label="Completed At">
                                        <Text>
                                            {selectedGroup.backfillCompletedAt
                                                ? dayjs(selectedGroup.backfillCompletedAt).format('YYYY-MM-DD HH:mm:ss')
                                                : 'N/A'
                                            }
                                        </Text>
                                    </Descriptions.Item>
                                </Descriptions>
                                {selectedGroup.backfillStatus === 'IN_PROGRESS' &&
                                    selectedGroup.backfillTotalMessages != null &&
                                    selectedGroup.backfillTotalMessages > 0 && (
                                        <Progress
                                            percent={Math.round(
                                                ((selectedGroup.backfillProcessedMessages ?? 0) / selectedGroup.backfillTotalMessages) * 100
                                            )}
                                            style={{ marginTop: 12 }}
                                        />
                                    )}
                            </Card>
                        </Space>
                    )}
                </Modal>
            </Space>
        </div>
    )
}

export default ConsumerGroups
