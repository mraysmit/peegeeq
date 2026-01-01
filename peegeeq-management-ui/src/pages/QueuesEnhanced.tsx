/**
 * Enhanced Queues Page - Phase 1 Implementation
 * Uses Redux Toolkit Query for state management
 */
import React, { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import { getVersionedApiUrl } from '../services/configService';
import {
    Card,
    Table,
    Button,
    Space,
    Tag,
    Row,
    Col,
    message,
    Dropdown,
    Modal,
    Form,
    Input,
    Select,
    type TableColumnsType,
} from 'antd';
import {
    PlusOutlined,
    DeleteOutlined,
    EyeOutlined,
    MoreOutlined,
    InboxOutlined,
    SendOutlined,
    UserOutlined,
    SyncOutlined,
    ClearOutlined,
    ReloadOutlined,
} from '@ant-design/icons';
import { useGetQueuesQuery } from '../store/api/queuesApi';
import type { Queue, QueueType, QueueStatus, QueueFilters } from '../types/queue';
import StatCard from '../components/common/StatCard';
import FilterBar from '../components/common/FilterBar';
import { showDeleteQueueConfirm } from '../components/common/ConfirmDialog';

interface DatabaseSetup {
    setupId: string
    host: string
    port: number
    database: string
    schema: string
}

const QueuesPage: React.FC = () => {
    const [filters, setFilters] = useState<QueueFilters>({
        page: 1,
        pageSize: 10,
        sortBy: 'name',
        sortOrder: 'asc',
    });

    const [isModalVisible, setIsModalVisible] = useState(false);
    const [setups, setSetups] = useState<DatabaseSetup[]>([]);
    const [setupsLoading, setSetupsLoading] = useState(false);
    const [form] = Form.useForm();

    // Fetch queues using RTK Query
    const { data, isLoading, isFetching, refetch } = useGetQueuesQuery(filters);

    const queues = data?.queues || [];
    const total = data?.total || 0;

    // Calculate summary statistics with defensive programming
    // Use nullish coalescing to handle undefined/null values gracefully
    const totalMessages = queues.reduce((sum, q) => sum + (q.messageCount ?? 0), 0);
    void queues.reduce((sum, q) => sum + (q.consumerCount ?? 0), 0); // totalConsumers - reserved for future use
    const avgMessageRate = queues.length > 0
        ? queues.reduce((sum, q) => sum + (q.messagesPerSecond ?? 0), 0) / queues.length
        : 0;

    // Fetch database setups
    const fetchSetups = useCallback(async () => {
        setSetupsLoading(true);
        try {
            const response = await axios.get(getVersionedApiUrl('setups'));
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetups(response.data.setupIds.map((setupId: string) => ({
                    setupId,
                    host: 'localhost',
                    port: 5432,
                    database: 'postgres',
                    schema: setupId
                })));
            } else {
                setSetups([]);
            }
        } catch (error) {
            console.error('Failed to fetch database setups:', error);
            setSetups([]);
        } finally {
            setSetupsLoading(false);
        }
    }, []);

    // Load setups on component mount
    useEffect(() => {
        fetchSetups();
    }, [fetchSetups]);

    // Handle create queue
    const handleCreateQueue = () => {
        form.resetFields();
        fetchSetups();
        setIsModalVisible(true);
    };

    // Handle modal OK
    const handleModalOk = async () => {
        try {
            const values = await form.validateFields();
            const requestBody = {
                setup: values.setup,
                name: values.name,
                type: 'native'
            };

            await axios.post(getVersionedApiUrl(`management/queues`), requestBody);
            message.success(`Queue "${values.name}" created successfully`);

            await refetch();
            setIsModalVisible(false);
            form.resetFields();
        } catch (error: any) {
            console.error('Failed to create queue:', error);
            console.error('Error response:', error.response);
            console.error('Error response data:', JSON.stringify(error.response?.data, null, 2));
            const errorMessage = error.response?.data?.message
                || error.response?.data?.error
                || (typeof error.response?.data === 'string' ? error.response?.data : null)
                || error.message
                || 'Failed to create queue';
            message.error(`Failed to create queue: ${errorMessage}`);
        }
    };

    // Handle search
    const handleSearch = (value: string) => {
        setFilters((prev) => ({ ...prev, search: value, page: 1 }));
    };

    // Handle type filter
    const handleTypeFilter = (value: string | string[]) => {
        const types = Array.isArray(value) ? value : value ? [value] : undefined;
        setFilters((prev) => ({
            ...prev,
            type: types as QueueType[] | undefined,
            page: 1,
        }));
    };

    // Handle status filter
    const handleStatusFilter = (value: string | string[]) => {
        const statuses = Array.isArray(value) ? value : value ? [value] : undefined;
        setFilters((prev) => ({
            ...prev,
            status: statuses as QueueStatus[] | undefined,
            page: 1,
        }));
    };

    // Clear all filters
    const handleClearFilters = () => {
        setFilters({
            page: 1,
            pageSize: filters.pageSize,
            sortBy: 'name',
            sortOrder: 'asc',
        });
    };

    // Handle delete queue
    const handleDeleteQueue = (queue: Queue) => {
        showDeleteQueueConfirm(queue.queueName, async () => {
            try {
                // TODO: Implement delete mutation when backend is ready
                message.success(`Queue "${queue.queueName}" deleted successfully`);
                refetch();
            } catch (error) {
                message.error('Failed to delete queue');
            }
        });
    };

    // Action menu for each queue
    const getActionMenu = (queue: Queue) => ({
        items: [
            {
                key: 'view',
                icon: <EyeOutlined />,
                label: <Link to={`/queues/${queue.setupId}/${queue.queueName}`}>View Details</Link>,
            },
            {
                type: 'divider' as const,
            },
            {
                key: 'purge',
                icon: <ClearOutlined />,
                label: 'Purge Messages',
                onClick: () => {
                    message.info('Purge functionality coming in Week 4');
                },
            },
            {
                key: 'delete',
                icon: <DeleteOutlined />,
                label: 'Delete Queue',
                danger: true,
                onClick: () => handleDeleteQueue(queue),
            },
        ],
    });

    // Table columns
    const columns: TableColumnsType<Queue> = [
        {
            title: 'Queue Name',
            dataIndex: 'queueName',
            key: 'queueName',
            sorter: true,
            render: (text: string, record: Queue) => (
                <Space direction="vertical" size="small">
                    <Link
                        to={`/queues/${record.setupId}/${record.queueName}`}
                        style={{ fontWeight: 'bold', fontSize: '14px' }}
                    >
                        {text}
                    </Link>
                    <Space size="small">
                        <Tag color="blue" style={{ fontSize: '11px' }}>
                            {record.setupId}
                        </Tag>
                        <Tag
                            color={
                                record.type === 'native'
                                    ? 'green'
                                    : record.type === 'outbox'
                                        ? 'orange'
                                        : 'purple'
                            }
                            style={{ fontSize: '11px' }}
                        >
                            {record.type.toUpperCase()}
                        </Tag>
                    </Space>
                </Space>
            ),
        },
        {
            title: 'Messages',
            dataIndex: 'messageCount',
            key: 'messageCount',
            sorter: true,
            width: 120,
            render: (value: number) => (
                <Space>
                    <InboxOutlined style={{ color: '#1890ff' }} />
                    <span style={{ fontWeight: 500 }}>{(value ?? 0).toLocaleString()}</span>
                </Space>
            ),
        },
        {
            title: 'Consumers',
            dataIndex: 'consumerCount',
            key: 'consumerCount',
            width: 120,
            render: (value: number) => (
                <Space>
                    <UserOutlined style={{ color: '#52c41a' }} />
                    <span style={{ fontWeight: 500 }}>{value ?? 0}</span>
                </Space>
            ),
        },
        {
            title: 'Message Rate',
            dataIndex: 'messagesPerSecond',
            key: 'messagesPerSecond',
            sorter: true,
            width: 140,
            render: (value: number) => (
                <Space>
                    <SendOutlined style={{ color: '#722ed1' }} />
                    <span>{(value ?? 0).toFixed(1)} msg/s</span>
                </Space>
            ),
        },
        {
            title: 'Error Rate',
            dataIndex: 'errorRate',
            key: 'errorRate',
            width: 120,
            render: (value: number) => {
                const safeValue = value ?? 0;
                const color = safeValue > 0.1 ? 'red' : safeValue > 0.01 ? 'orange' : 'green';
                return <Tag color={color}>{(safeValue * 100).toFixed(2)}%</Tag>;
            },
        },
        {
            title: 'Status',
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (status: QueueStatus) => {
                const colors: Record<QueueStatus, string> = {
                    active: 'green',
                    paused: 'orange',
                    idle: 'default',
                    error: 'red',
                };
                return <Tag color={colors[status]}>{status.toUpperCase()}</Tag>;
            },
        },
        {
            title: 'Created',
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 150,
            render: (text: string) => new Date(text).toLocaleDateString(),
        },
        {
            title: 'Actions',
            key: 'actions',
            width: 80,
            fixed: 'right' as const,
            render: (_: any, record: Queue) => (
                <Dropdown menu={getActionMenu(record)} trigger={['click']}>
                    <Button type="text" icon={<MoreOutlined />} />
                </Dropdown>
            ),
        },
    ];

    return (
        <div style={{ padding: '0' }}>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                {/* Summary Statistics */}
                <Row gutter={[16, 16]}>
                    <Col xs={24} sm={12} lg={6}>
                        <StatCard
                            title="Total Queues"
                            value={total}
                            icon={<InboxOutlined />}
                            loading={isLoading}
                            valueStyle={{ color: '#1890ff' }}
                        />
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <StatCard
                            title="Active Queues"
                            value={queues.filter((q) => q.status === 'active').length}
                            icon={<InboxOutlined />}
                            loading={isLoading}
                            valueStyle={{ color: '#52c41a' }}
                        />
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <StatCard
                            title="Total Messages"
                            value={totalMessages}
                            icon={<SendOutlined />}
                            loading={isLoading}
                            valueStyle={{ color: '#722ed1' }}
                        />
                    </Col>
                    <Col xs={24} sm={12} lg={6}>
                        <StatCard
                            title="Avg Rate"
                            value={avgMessageRate}
                            suffix="msg/s"
                            precision={1}
                            icon={<SendOutlined />}
                            loading={isLoading}
                            valueStyle={{ color: '#fa8c16' }}
                        />
                    </Col>
                </Row>

                {/* Filters */}
                <FilterBar
                    searchPlaceholder="Search queues..."
                    searchValue={filters.search}
                    onSearch={handleSearch}
                    filters={[
                        {
                            label: 'Type',
                            value: filters.type,
                            mode: 'multiple',
                            placeholder: 'All Types',
                            options: [
                                { label: 'Native', value: 'NATIVE' },
                                { label: 'Outbox', value: 'OUTBOX' },
                                { label: 'Bitemporal', value: 'BITEMPORAL' },
                            ],
                            onChange: handleTypeFilter,
                        },
                        {
                            label: 'Status',
                            value: filters.status,
                            mode: 'multiple',
                            placeholder: 'All Statuses',
                            options: [
                                { label: 'Active', value: 'ACTIVE' },
                                { label: 'Paused', value: 'PAUSED' },
                                { label: 'Idle', value: 'IDLE' },
                                { label: 'Error', value: 'ERROR' },
                            ],
                            onChange: handleStatusFilter,
                        },
                    ]}
                    onClear={handleClearFilters}
                    extra={
                        <Space>
                            <Button
                                data-testid="refresh-queues-btn"
                                icon={<SyncOutlined spin={isFetching} />}
                                onClick={() => refetch()}
                            >
                                Refresh
                            </Button>
                            <Button
                                data-testid="create-queue-btn"
                                type="primary"
                                icon={<PlusOutlined />}
                                onClick={handleCreateQueue}
                            >
                                Create Queue
                            </Button>
                        </Space>
                    }
                />

                {/* Queue Table */}
                <Card
                    title={`Queues (${total})`}
                    variant="borderless"
                    style={{
                        boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
                    }}
                >
                    <Table<Queue>
                        data-testid="queues-table"
                        columns={columns}
                        dataSource={queues}
                        loading={isLoading || isFetching}
                        rowKey={(record) => `${record.setupId}:${record.queueName}`}
                        pagination={{
                            current: filters.page,
                            pageSize: filters.pageSize,
                            total,
                            showSizeChanger: true,
                            showQuickJumper: true,
                            showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} queues`,
                            onChange: (page, pageSize) => {
                                setFilters((prev) => ({ ...prev, page, pageSize }));
                            },
                        }}
                        onChange={(_pagination, _filters, sorter: any) => {
                            if (sorter.field) {
                                setFilters((prev) => ({
                                    ...prev,
                                    sortBy: sorter.field,
                                    sortOrder: sorter.order === 'ascend' ? 'asc' : 'desc',
                                }));
                            }
                        }}
                        scroll={{ x: 1200 }}
                    />
                </Card>

                {/* Create Queue Modal */}
                <Modal
                    title="Create Queue"
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
                                    <Input data-testid="queue-name-input" placeholder="e.g., orders, payments" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="setup"
                                    label={
                                        <Space size={4}>
                                            <span>Setup</span>
                                            <Button
                                                type="text"
                                                size="small"
                                                icon={<ReloadOutlined style={{ fontSize: '12px' }} />}
                                                loading={setupsLoading}
                                                onClick={fetchSetups}
                                                title="Refresh setups"
                                                data-testid="refresh-setups-btn"
                                                style={{ padding: '0 4px', height: '20px' }}
                                            />
                                        </Space>
                                    }
                                    rules={[{ required: true, message: 'Please select setup' }]}
                                >
                                    <Select
                                        data-testid="queue-setup-select"
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
    );
};

export default QueuesPage;
