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
} from '@ant-design/icons';
import { useGetQueueDetailsQuery } from '../store/api/queuesApi';
import StatCard from '../components/common/StatCard';

const { Title, Text } = Typography;

const QueueDetailsPage = () => {
  const { setupId, queueName } = useParams<{ setupId: string; queueName: string }>();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('overview');

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
          const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
          const response = await fetch(
            `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/${action}`,
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
          const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
          const response = await fetch(
            `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/purge`,
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
          const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
          const response = await fetch(
            `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}`,
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
        bordered={false}
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
            value={queue.statistics.messageCount}
            icon={<InboxOutlined />}
            valueStyle={{ color: '#1890ff' }}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Consumers"
            value={queue.statistics.consumerCount}
            suffix={`/ ${queue.statistics.activeConsumers} active`}
            icon={<UserOutlined />}
            valueStyle={{ color: '#52c41a' }}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Message Rate"
            value={queue.statistics.messagesPerSecond}
            suffix="msg/s"
            precision={1}
            icon={<SendOutlined />}
            valueStyle={{ color: '#722ed1' }}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Avg Processing Time"
            value={queue.statistics.processingTime.avg}
            suffix="ms"
            precision={0}
            icon={<ClockCircleOutlined />}
            valueStyle={{ color: '#fa8c16' }}
          />
        </Col>
      </Row>

      {/* Tabs */}
      <Card
        bordered={false}
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
                            <Text strong>Queue Depth:</Text> <Text>{queue.statistics.queueDepth}</Text>
                          </div>
                          <div>
                            <Text strong>Error Rate:</Text> <Text>{(queue.statistics.errorRate * 100).toFixed(2)}%</Text>
                          </div>
                          <div>
                            <Text strong>P50 Processing:</Text> <Text>{queue.statistics.processingTime.p50}ms</Text>
                          </div>
                          <div>
                            <Text strong>P95 Processing:</Text> <Text>{queue.statistics.processingTime.p95}ms</Text>
                          </div>
                          <div>
                            <Text strong>P99 Processing:</Text> <Text>{queue.statistics.processingTime.p99}ms</Text>
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
              label: `Consumers (${queue.consumers.length})`,
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
                <Alert
                  message="Messages Tab - Coming in Week 3"
                  description="Message browser (non-destructive) and message publisher (test messages) will be available here."
                  type="info"
                  showIcon
                />
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
    </div>
  );
};

export default QueueDetailsPage;
