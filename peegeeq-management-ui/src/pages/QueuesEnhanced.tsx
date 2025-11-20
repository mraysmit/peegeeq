/**
 * Enhanced Queues Page - Phase 1 Implementation
 * Uses Redux Toolkit Query for state management
 */
import React, { useState } from 'react';
import { Link } from 'react-router-dom';
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
} from '@ant-design/icons';
import { useGetQueuesQuery } from '../store/api/queuesApi';
import type { Queue, QueueType, QueueStatus, QueueFilters } from '../types/queue';
import StatCard from '../components/common/StatCard';
import FilterBar from '../components/common/FilterBar';
import { showDeleteQueueConfirm } from '../components/common/ConfirmDialog';

const QueuesPage: React.FC = () => {
  const [filters, setFilters] = useState<QueueFilters>({
    page: 1,
    pageSize: 10,
    sortBy: 'name',
    sortOrder: 'asc',
  });

  // Fetch queues using RTK Query
  const { data, isLoading, isFetching, refetch } = useGetQueuesQuery(filters);

  const queues = data?.queues || [];
  const total = data?.total || 0;

  // Calculate summary statistics
  const totalMessages = queues.reduce((sum, q) => sum + q.messageCount, 0);
  const totalConsumers = queues.reduce((sum, q) => sum + q.consumerCount, 0);
  const avgMessageRate = queues.length > 0
    ? queues.reduce((sum, q) => sum + q.messagesPerSecond, 0) / queues.length
    : 0;

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
                record.type === 'NATIVE'
                  ? 'green'
                  : record.type === 'OUTBOX'
                  ? 'orange'
                  : 'purple'
              }
              style={{ fontSize: '11px' }}
            >
              {record.type}
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
          <span style={{ fontWeight: 500 }}>{value.toLocaleString()}</span>
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
          <span style={{ fontWeight: 500 }}>{value}</span>
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
          <span>{value.toFixed(1)} msg/s</span>
        </Space>
      ),
    },
    {
      title: 'Error Rate',
      dataIndex: 'errorRate',
      key: 'errorRate',
      width: 120,
      render: (value: number) => {
        const color = value > 0.1 ? 'red' : value > 0.01 ? 'orange' : 'green';
        return <Tag color={color}>{(value * 100).toFixed(2)}%</Tag>;
      },
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: QueueStatus) => {
        const colors: Record<QueueStatus, string> = {
          ACTIVE: 'green',
          PAUSED: 'orange',
          IDLE: 'default',
          ERROR: 'red',
        };
        return <Tag color={colors[status]}>{status}</Tag>;
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
              value={queues.filter((q) => q.status === 'ACTIVE').length}
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
                icon={<SyncOutlined spin={isFetching} />}
                onClick={() => refetch()}
              >
                Refresh
              </Button>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => message.info('Create queue functionality coming in Week 2')}
              >
                Create Queue
              </Button>
            </Space>
          }
        />

        {/* Queue Table */}
        <Card
          title={`Queues (${total})`}
          bordered={false}
          style={{
            boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
          }}
        >
          <Table<Queue>
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
            onChange={(pagination, _filters, sorter: any) => {
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
      </Space>
    </div>
  );
};

export default QueuesPage;
