/**
 * Enhanced Queue Details Page - Phase 1 Implementation
 * Multi-tab interface for comprehensive queue management
 */
import React, { useState } from 'react';
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

const QueueDetailsPage: React.FC = () => {
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

  // Queue actions menu
  const actionsMenu = {
    items: [
      {
        key: 'pause',
        icon: queue.status === 'ACTIVE' ? <PauseOutlined /> : <PlayCircleOutlined />,
        label: queue.status === 'ACTIVE' ? 'Pause Queue' : 'Resume Queue',
        onClick: () => {
          console.log('Pause/Resume queue - Coming in Week 4');
        },
      },
      {
        key: 'purge',
        icon: <ClearOutlined />,
        label: 'Purge Messages',
        onClick: () => {
          console.log('Purge queue - Coming in Week 4');
        },
      },
      {
        type: 'divider' as const,
      },
      {
        key: 'delete',
        icon: <DeleteOutlined />,
        label: 'Delete Queue',
        danger: true,
        onClick: () => {
          console.log('Delete queue - Coming in Week 4');
        },
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
              icon={<ReloadOutlined spin={isFetching} />}
              onClick={() => refetch()}
            >
              Refresh
            </Button>
            <Dropdown menu={actionsMenu} trigger={['click']}>
              <Button icon={<MoreOutlined />}>Actions</Button>
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
