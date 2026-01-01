/**
 * Enhanced Queue Details Page - Phase 1 Implementation
 * Multi-tab interface for comprehensive queue management
 */
import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Card,
    Tabs,
    Button,
    Space,
    Tag,
    Typography,
    Breadcrumb,
    Row,
    Col,
    Spin,
    Alert,
    Dropdown,
    Modal,
    message,
    Form,
    Input,
    InputNumber,
    Table,
} from 'antd';
import {
    ArrowLeftOutlined,
    MoreOutlined,
    ReloadOutlined,
    DeleteOutlined,
    ClearOutlined,
    PauseOutlined,
    PlayCircleOutlined,
    InboxOutlined,
    UserOutlined,
    SendOutlined,
    ClockCircleOutlined,
    DownloadOutlined,
} from '@ant-design/icons';
import { useGetQueueDetailsQuery } from '../store/api/queuesApi';
import StatCard from '../components/common/StatCard';
import axios from 'axios';
import { getVersionedApiUrl } from '../services/configService';

const { Title, Text } = Typography;
const { TextArea } = Input;

interface Message {
    messageId: string;
    payload: any;
    headers: Record<string, string>;
    timestamp: number;
    priority: number;
    messageType?: string;
    deliveryCount?: number;
}

const QueueDetailsPage = () => {
    const { setupId, queueName } = useParams<{ setupId: string; queueName: string }>();
    const navigate = useNavigate();
    const [activeTab, setActiveTab] = useState('overview');

    // Message operations state
    const [messages, setMessages] = useState<Message[]>([]);
    const [publishModalVisible, setPublishModalVisible] = useState(false);
    const [getMessagesModalVisible, setGetMessagesModalVisible] = useState(false);
    const [publishForm] = Form.useForm();
    const [getMessagesForm] = Form.useForm();

    // Fetch queue details
    const { data: queue, isLoading, isFetching, refetch, error } = useGetQueueDetailsQuery(
        { setupId: setupId!, queueName: queueName! },
        { skip: !setupId || !queueName, pollingInterval: 5000 } // Poll every 5 seconds
    );

    if (isLoading) {
        return (
            <div style={{ textAlign: 'center', padding: '100px 0' }}>
                <Spin size="large" />
                <div style={{ marginTop: 16 }}>Loading queue details...</div>
            </div>
        );
    }

    if (error || !queue) {
        return (
            <Alert
                message="Error Loading Queue"
                description="Failed to load queue details. The queue may not exist or the backend service may be unavailable."
                type="error"
                showIcon
                action={
                    <Space>
                        <Button onClick={() => refetch()}>Retry</Button>
                        <Button onClick={() => navigate('/queues')}>Back to Queues</Button>
                    </Space>
                }
            />
        );
    }

    // Handler for pausing/resuming queue
    const handlePauseResume = async () => {
        const action = queue.status === 'active' ? 'pause' : 'resume';
        const actionLabel = action === 'pause' ? 'Pause' : 'Resume';

        Modal.confirm({
            title: `${actionLabel} Queue`,
            content: `Are you sure you want to ${action} queue "${queueName}"?`,
            okText: actionLabel,
            okType: action === 'pause' ? 'primary' : 'default',
            onOk: async () => {
                try {
                    const response = await fetch(
                        getVersionedApiUrl(`/queues/${setupId}/${queueName}/${action}`),
                        { method: 'POST' }
                    );

                    if (response.ok) {
                        const data = await response.json();
                        const count = action === 'pause' ? data.pausedSubscriptions : data.resumedSubscriptions;
                        message.success(`Queue ${action}d successfully. ${count} subscription(s) affected.`);
                        refetch(); // Refresh the queue details
                    } else {
                        const errorData = await response.json().catch(() => ({}));
                        message.error(`Failed to ${action} queue: ${errorData.message || response.statusText}`);
                    }
                } catch (error) {
                    message.error(`Failed to ${action} queue: ${error instanceof Error ? error.message : 'Unknown error'}`);
                }
            }
        });
    };

    // Handler for purging queue
    const handlePurgeQueue = async () => {
        Modal.confirm({
            title: 'Purge Queue',
            content: `Are you sure you want to purge all messages from queue "${queueName}"? This action cannot be undone.`,
            okText: 'Purge',
            okType: 'danger',
            onOk: async () => {
                try {
                    const response = await fetch(
                        getVersionedApiUrl(`/queues/${setupId}/${queueName}/purge`),
                        { method: 'POST' }
                    );

                    if (response.ok) {
                        const data = await response.json();
                        message.success(`Queue purged successfully. ${data.purgedCount} message(s) removed.`);
                        refetch(); // Refresh the queue details
                    } else {
                        const errorData = await response.json().catch(() => ({}));
                        message.error(`Failed to purge queue: ${errorData.message || response.statusText}`);
                    }
                } catch (error) {
                    message.error(`Failed to purge queue: ${error instanceof Error ? error.message : 'Unknown error'}`);
                }
            }
        });
    };

    // Handler for deleting queue
    const handleDeleteQueue = async () => {
        Modal.confirm({
            title: 'Delete Queue',
            content: (
                <div>
                    <p>Are you sure you want to delete queue <strong>"{queueName}"</strong>?</p>
                    <p style={{ color: '#ff4d4f', marginTop: 8 }}>
                        ⚠️ This action cannot be undone. All messages and queue configuration will be permanently deleted.
                    </p>
                </div>
            ),
            okText: 'Delete',
            okType: 'danger',
            onOk: async () => {
                try {
                    const response = await fetch(
                        getVersionedApiUrl(`/queues/${setupId}/${queueName}`),
                        { method: 'DELETE' }
                    );

                    if (response.ok) {
                        const data = await response.json();
                        message.success(`Queue deleted successfully. ${data.deletedMessages || 0} message(s) removed.`);
                        // Navigate back to queues list after successful deletion
                        navigate('/queues');
                    } else {
                        const errorData = await response.json().catch(() => ({}));
                        message.error(`Failed to delete queue: ${errorData.message || response.statusText}`);
                    }
                } catch (error) {
                    message.error(`Failed to delete queue: ${error instanceof Error ? error.message : 'Unknown error'}`);
                }
            }
        });
    };

    // Handler for publishing message
    const handlePublishMessage = async () => {
        try {
            const values = await publishForm.validateFields();

            // Parse payload if it's JSON string
            let payload = values.payload;
            try {
                payload = JSON.parse(values.payload);
            } catch {
                // Keep as string if not valid JSON
            }

            // Parse headers if provided
            let headers = {};
            if (values.headers) {
                try {
                    headers = JSON.parse(values.headers);
                } catch {
                    message.error('Invalid JSON format for headers');
                    return;
                }
            }

            const response = await axios.post(getVersionedApiUrl(`/queues/${setupId}/${queueName}/messages`), {
                payload,
                headers,
                priority: values.priority,
                delaySeconds: values.delaySeconds || 0
            });

            if (response.data) {
                message.success(`Message published successfully. ID: ${response.data.messageId}`);
                setPublishModalVisible(false);
                publishForm.resetFields();
                refetch(); // Refresh stats
            }
        } catch (error) {
            console.error('Failed to publish message:', error);
            message.error('Failed to publish message');
        }
    };

    // Handler for getting messages
    const handleGetMessages = async () => {
        try {
            const values = await getMessagesForm.validateFields();

            const response = await axios.get(getVersionedApiUrl(`/queues/${setupId}/${queueName}/messages`), {
                params: {
                    limit: values.count || 10
                }
            });

            if (response.data && response.data.messages) {
                setMessages(response.data.messages);
                message.success(`Retrieved ${response.data.messages.length} messages`);
                setGetMessagesModalVisible(false);
            } else if (response.data) {
                // Handle case where messages array might be directly in response
                const msgs = Array.isArray(response.data) ? response.data : [];
                setMessages(msgs);
                message.success(`Retrieved ${msgs.length} messages`);
                setGetMessagesModalVisible(false);
            }
        } catch (error) {
            console.error('Failed to get messages:', error);
            message.error('Failed to retrieve messages');
        }
    };

    // Message table columns
    const messageColumns = [
        {
            title: 'Message ID',
            dataIndex: 'messageId',
            key: 'messageId',
            render: (text: string) => <Tag color="blue">{text?.substring(0, 8)}...</Tag>,
        },
        {
            title: 'Type',
            dataIndex: 'messageType',
            key: 'messageType',
            render: (text: string) => text || '-',
        },
        {
            title: 'Priority',
            dataIndex: 'priority',
            key: 'priority',
        },
        {
            title: 'Delivery Count',
            dataIndex: 'deliveryCount',
            key: 'deliveryCount',
            render: (count: number) => count || 1,
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
                            content: <pre style={{ maxHeight: '400px', overflow: 'auto' }}>{JSON.stringify(payload, null, 2)}</pre>,
                            width: 800,
                        });
                    }}
                >
                    View
                </Button>
            ),
        },
    ];

    // Queue actions menu
    const actionsMenu = {
        items: [
            {
                key: 'pause',
                icon: queue.status === 'active' ? <PauseOutlined /> : <PlayCircleOutlined />,
                label: queue.status === 'active' ? 'Pause Queue' : 'Resume Queue',
                onClick: handlePauseResume,
            },
            {
                key: 'purge',
                icon: <ClearOutlined />,
                label: 'Purge Messages',
                onClick: handlePurgeQueue,
            },
            {
                type: 'divider' as const,
            },
            {
                key: 'delete',
                icon: <DeleteOutlined />,
                label: 'Delete Queue',
                danger: true,
                onClick: handleDeleteQueue,
            },
        ],
    };

    // Get queue type color
    const getTypeColor = (type: string) => {
        switch (type) {
            case 'NATIVE':
                return 'green';
            case 'OUTBOX':
                return 'orange';
            case 'BITEMPORAL':
                return 'purple';
            default:
                return 'default';
        }
    };

    // Get status color
    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE':
                return 'green';
            case 'PAUSED':
                return 'orange';
            case 'IDLE':
                return 'default';
            case 'ERROR':
                return 'red';
            default:
                return 'default';
        }
    };

    return (
        <div>
            {/* Breadcrumb */}
            <Breadcrumb
                style={{ marginBottom: 16 }}
                items={[
                    { title: 'Home', href: '/' },
                    { title: 'Queues', href: '/queues' },
                    { title: queueName },
                ]}
            />

            {/* Header */}
            <Card
                variant="borderless"
                style={{ marginBottom: 16, boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }}
            >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <Space direction="vertical" size="small">
                        <Space>
                            <Button
                                type="text"
                                icon={<ArrowLeftOutlined />}
                                onClick={() => navigate('/queues')}
                            >
                                Back
                            </Button>
                            <Title level={3} style={{ margin: 0 }}>
                                {queueName}
                            </Title>
                        </Space>
                        <Space size="small">
                            <Tag color="blue">{setupId}</Tag>
                            <Tag color={getTypeColor(queue.type)}>{queue.type}</Tag>
                            <Tag color={getStatusColor(queue.status)}>{queue.status}</Tag>
                        </Space>
                        {queue.description && (
                            <Text type="secondary">{queue.description}</Text>
                        )}
                    </Space>
                    <Space>
                        <Button
                            data-testid="refresh-queue-btn"
                            icon={<ReloadOutlined spin={isFetching} />}
                            onClick={() => refetch()}
                        >
                            Refresh
                        </Button>
                        <Dropdown menu={actionsMenu} trigger={['click']}>
                            <Button data-testid="queue-actions-btn" icon={<MoreOutlined />}>Actions</Button>
                        </Dropdown>
                    </Space>
                </div>
            </Card>

            {/* Statistics Cards */}
            <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
                <Col xs={24} sm={12} lg={6}>
                    <StatCard
                        title="Messages"
                        value={queue.statistics?.messageCount || 0}
                        icon={<InboxOutlined />}
                        valueStyle={{ color: '#1890ff' }}
                    />
                </Col>
                <Col xs={24} sm={12} lg={6}>
                    <StatCard
                        title="Consumers"
                        value={queue.statistics?.consumerCount || 0}
                        suffix={`/ ${queue.statistics?.activeConsumers || 0} active`}
                        icon={<UserOutlined />}
                        valueStyle={{ color: '#52c41a' }}
                    />
                </Col>
                <Col xs={24} sm={12} lg={6}>
                    <StatCard
                        title="Message Rate"
                        value={queue.statistics?.messagesPerSecond || 0}
                        suffix="msg/s"
                        precision={1}
                        icon={<SendOutlined />}
                        valueStyle={{ color: '#722ed1' }}
                    />
                </Col>
                <Col xs={24} sm={12} lg={6}>
                    <StatCard
                        title="Avg Processing Time"
                        value={queue.statistics?.processingTime?.avg || 0}
                        suffix="ms"
                        precision={0}
                        icon={<ClockCircleOutlined />}
                        valueStyle={{ color: '#fa8c16' }}
                    />
                </Col>
            </Row>

            {/* Tabs */}
            <Card
                variant="borderless"
                style={{ boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }}
            >
                <Tabs
                    data-testid="queue-details-tabs"
                    activeKey={activeTab}
                    onChange={setActiveTab}
                    items={[
                        {
                            key: 'overview',
                            label: 'Overview',
                            children: (
                                <div>
                                    <Alert
                                        message="Overview Tab"
                                        description="Queue configuration, properties, and detailed statistics will be displayed here."
                                        type="info"
                                        showIcon
                                        style={{ marginBottom: 16 }}
                                    />
                                    <Row gutter={[16, 16]}>
                                        <Col span={12}>
                                            <Card title="Queue Information" size="small">
                                                <Space direction="vertical" style={{ width: '100%' }}>
                                                    <div>
                                                        <Text strong>Setup ID:</Text> <Text>{queue.setupId}</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>Queue Name:</Text> <Text>{queue.queueName}</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>Type:</Text> <Tag color={getTypeColor(queue.type)}>{queue.type}</Tag>
                                                    </div>
                                                    <div>
                                                        <Text strong>Status:</Text> <Tag color={getStatusColor(queue.status)}>{queue.status}</Tag>
                                                    </div>
                                                    <div>
                                                        <Text strong>Created:</Text> <Text>{new Date(queue.createdAt).toLocaleString()}</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>Updated:</Text> <Text>{new Date(queue.updatedAt).toLocaleString()}</Text>
                                                    </div>
                                                </Space>
                                            </Card>
                                        </Col>
                                        <Col span={12}>
                                            <Card title="Performance Metrics" size="small">
                                                <Space direction="vertical" style={{ width: '100%' }}>
                                                    <div>
                                                        <Text strong>Queue Depth:</Text> <Text>{queue.statistics?.queueDepth || 0}</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>Error Rate:</Text> <Text>{((queue.statistics?.errorRate || 0) * 100).toFixed(2)}%</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>P50 Processing:</Text> <Text>{queue.statistics?.processingTime?.p50 || 0}ms</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>P95 Processing:</Text> <Text>{queue.statistics?.processingTime?.p95 || 0}ms</Text>
                                                    </div>
                                                    <div>
                                                        <Text strong>P99 Processing:</Text> <Text>{queue.statistics?.processingTime?.p99 || 0}ms</Text>
                                                    </div>
                                                </Space>
                                            </Card>
                                        </Col>
                                    </Row>
                                </div>
                            ),
                        },
                        {
                            key: 'consumers',
                            label: `Consumers (${queue.consumers?.length || 0})`,
                            children: (
                                <Alert
                                    message="Consumers Tab - Coming in Week 5"
                                    description="List of active consumers, their statistics, and health status will be displayed here."
                                    type="info"
                                    showIcon
                                />
                            ),
                        },
                        {
                            key: 'messages',
                            label: 'Messages',
                            children: (
                                <div>
                                    <Space style={{ marginBottom: 16 }}>
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
                                        <Button
                                            icon={<ReloadOutlined />}
                                            onClick={() => setMessages([])}
                                            disabled={messages.length === 0}
                                        >
                                            Clear
                                        </Button>
                                    </Space>
                                    <Table
                                        columns={messageColumns}
                                        dataSource={messages}
                                        rowKey="messageId"
                                        pagination={{ pageSize: 10 }}
                                        locale={{ emptyText: 'No messages retrieved. Click "Get Messages" to fetch messages from the queue.' }}
                                    />
                                </div>
                            ),
                        },
                        {
                            key: 'bindings',
                            label: 'Bindings',
                            children: (
                                <Alert
                                    message="Bindings Tab - Coming in Week 5"
                                    description="Queue bindings, routing keys, and exchange relationships will be displayed here."
                                    type="info"
                                    showIcon
                                />
                            ),
                        },
                        {
                            key: 'charts',
                            label: 'Charts',
                            children: (
                                <Alert
                                    message="Charts Tab - Coming in Week 2"
                                    description="Time-series charts for message rates, queue depth, error rates, and processing times will be displayed here."
                                    type="info"
                                    showIcon
                                />
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
                okText="Publish"
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
                okText="Get Messages"
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
                    <Alert
                        message="Non-Destructive Read"
                        description="Messages are retrieved for viewing only and remain in the queue. They will not be removed or acknowledged."
                        type="info"
                        showIcon
                        style={{ marginTop: 8 }}
                    />
                </Form>
            </Modal>
        </div>
    );
};

export default QueueDetailsPage;
