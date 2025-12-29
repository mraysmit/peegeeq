import React, { useState, useEffect } from 'react'
import axios from 'axios'
import { getApiUrl } from '../services/configService'
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
  Badge,
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
  UserOutlined,
  PartitionOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
  ApiOutlined,
  HeartOutlined
} from '@ant-design/icons'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { Text, Title } = Typography

interface ConsumerGroupMember {
  memberId: string
  memberName: string
  joinedAt: string
  lastHeartbeat: string
  assignedPartitions: number[]
  status: 'active' | 'inactive' | 'rebalancing'
  processedMessages: number
  errorCount: number
}

interface ConsumerGroup {
  key: string
  groupId: string
  groupName: string
  setupId: string
  queueName: string
  memberCount: number
  maxMembers: number
  loadBalancingStrategy: 'ROUND_ROBIN' | 'RANGE' | 'STICKY' | 'RANDOM'
  sessionTimeout: number
  status: 'active' | 'inactive' | 'rebalancing' | 'error'
  createdAt: string
  lastRebalance?: string
  members: ConsumerGroupMember[]
  totalPartitions: number
  assignedPartitions: number
  messagesPerSecond: number
  totalProcessed: number
}






const ConsumerGroups: React.FC = () => {
  const [consumerGroups, setConsumerGroups] = useState<ConsumerGroup[]>([])
  const [selectedGroup, setSelectedGroup] = useState<ConsumerGroup | null>(null)
  const [isCreateModalVisible, setIsCreateModalVisible] = useState(false)
  const [isDetailsModalVisible, setIsDetailsModalVisible] = useState(false)
  const [loading, setLoading] = useState(true)
  const [form] = Form.useForm()

  const fetchConsumerGroups = async () => {
    setLoading(true)
    try {
      const response = await axios.get(getApiUrl('/api/v1/management/consumer-groups'))
      if (response.data.consumerGroups && Array.isArray(response.data.consumerGroups)) {
        setConsumerGroups(response.data.consumerGroups.map((group: any, index: number) => ({
          key: index.toString(),
          groupId: `group-${index + 1}`,
          groupName: group.name,
          setupId: group.setup,
          queueName: group.name.replace('-processors', '').replace('-handlers', '').replace('-senders', '').replace('-workers', ''),
          memberCount: group.members,
          maxMembers: group.members + Math.floor(Math.random() * 3) + 1,
          loadBalancingStrategy: 'ROUND_ROBIN',
          sessionTimeout: 30000,
          status: group.status,
          messagesPerSecond: Math.floor(Math.random() * 100) + 10,
          lag: group.lag || 0,
          partition: group.partition || 0,
          lastRebalance: group.lastRebalance || new Date().toISOString(),
          createdAt: group.createdAt || new Date().toISOString(),
          members: [] // Will be populated with mock member data for now
        })))
      }
    } catch (error) {
      console.error('Failed to fetch consumer groups:', error)
      // Show error message instead of mock data
      message.error('Failed to load consumer groups. Please check if the backend service is running.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchConsumerGroups()
    // Refresh every 30 seconds
    const interval = setInterval(fetchConsumerGroups, 30000)
    return () => clearInterval(interval)
  }, [])

  const handleCreateGroup = () => {
    form.resetFields()
    setIsCreateModalVisible(true)
  }

  const handleViewDetails = (group: ConsumerGroup) => {
    setSelectedGroup(group)
    setIsDetailsModalVisible(true)
  }

  const handleDeleteGroup = (group: ConsumerGroup) => {
    Modal.confirm({
      title: 'Delete Consumer Group',
      content: `Are you sure you want to delete consumer group "${group.groupName}"? This will disconnect all members.`,
      okText: 'Delete',
      okType: 'danger',
      onOk: () => {
        setConsumerGroups(prev => prev.filter(g => g.key !== group.key))
      },
    })
  }

  const handleCreateModalOk = () => {
    form.validateFields().then(values => {
      const newGroup: ConsumerGroup = {
        key: Date.now().toString(),
        groupId: `group-${Date.now()}`,
        ...values,
        memberCount: 0,
        status: 'inactive' as const,
        createdAt: new Date().toISOString(),
        totalPartitions: 8, // Default
        assignedPartitions: 0,
        messagesPerSecond: 0,
        totalProcessed: 0,
        members: []
      }
      setConsumerGroups(prev => [...prev, newGroup])
      setIsCreateModalVisible(false)
    })
  }

  const getStatusColor = (status: string) => {
    const colors = {
      active: 'green',
      inactive: 'orange',
      rebalancing: 'blue',
      error: 'red'
    }
    return colors[status as keyof typeof colors] || 'default'
  }

  const getStatusIcon = (status: string) => {
    const icons = {
      active: <CheckCircleOutlined />,
      inactive: <ClockCircleOutlined />,
      rebalancing: <ReloadOutlined spin />,
      error: <ExclamationCircleOutlined />
    }
    return icons[status as keyof typeof icons]
  }

  const getMemberStatusColor = (status: string) => {
    const colors = {
      active: 'green',
      inactive: 'red',
      rebalancing: 'blue'
    }
    return colors[status as keyof typeof colors] || 'default'
  }

  const getActionMenu = (group: ConsumerGroup) => ({
    items: [
      {
        key: 'view',
        icon: <EyeOutlined />,
        label: 'View Details',
        onClick: () => handleViewDetails(group),
      },
      {
        key: 'rebalance',
        icon: <ReloadOutlined />,
        label: 'Trigger Rebalance',
        onClick: () => {/* console.log('Rebalance', group.groupName) */},
      },
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
            <Tag color="cyan">{record.loadBalancingStrategy}</Tag>
          </Space>
        </Space>
      ),
    },
    {
      title: 'Members',
      key: 'members',
      render: (record: ConsumerGroup) => (
        <Space direction="vertical" size="small">
          <Space>
            <UserOutlined />
            <Text>{record.memberCount}/{record.maxMembers}</Text>
          </Space>
          <Progress
            percent={(record.memberCount / record.maxMembers) * 100}
            size="small"
            showInfo={false}
            strokeColor={record.memberCount === record.maxMembers ? '#faad14' : '#52c41a'}
          />
        </Space>
      ),
    },
    {
      title: 'Partitions',
      key: 'partitions',
      render: (record: ConsumerGroup) => (
        <Space direction="vertical" size="small">
          <Space>
            <PartitionOutlined />
            <Text>{record.assignedPartitions}/{record.totalPartitions}</Text>
          </Space>
          <Progress
            percent={(record.assignedPartitions / record.totalPartitions) * 100}
            size="small"
            showInfo={false}
            strokeColor={record.assignedPartitions === record.totalPartitions ? '#52c41a' : '#faad14'}
          />
        </Space>
      ),
    },
    {
      title: 'Performance',
      key: 'performance',
      render: (record: ConsumerGroup) => (
        <Space direction="vertical" size="small">
          <div>
            <ApiOutlined style={{ color: '#1890ff', marginRight: 4 }} />
            {record.messagesPerSecond.toFixed(1)} msg/s
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {record.totalProcessed.toLocaleString()} total
            </Text>
          </div>
        </Space>
      ),
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
          {record.lastRebalance && (
            <Tooltip title={`Last rebalance: ${dayjs(record.lastRebalance).format('MMM DD, HH:mm')}`}>
              <Text type="secondary" style={{ fontSize: '11px' }}>
                <ReloadOutlined /> {dayjs(record.lastRebalance).fromNow()}
              </Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text: string) => (
        <Tooltip title={text}>
          <Text>{dayjs(text).format('MMM DD, YYYY')}</Text>
        </Tooltip>
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
  const totalGroups = consumerGroups.length
  const activeGroups = consumerGroups.filter(g => g.status === 'active').length
  const totalMembers = consumerGroups.reduce((sum, g) => sum + g.memberCount, 0)
  const avgMessagesPerSecond = consumerGroups.reduce((sum, g) => sum + g.messagesPerSecond, 0) / totalGroups

  return (
    <div className="fade-in">
      <Title level={1}>Consumer Groups</Title>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
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
                title="Total Members"
                value={totalMembers}
                prefix={<UserOutlined style={{ color: '#722ed1' }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="Avg Throughput"
                value={avgMessagesPerSecond.toFixed(1)}
                suffix="msg/s"
                prefix={<ApiOutlined style={{ color: '#fa8c16' }} />}
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
                onClick={() => {
                  setLoading(true)
                  setTimeout(() => setLoading(false), 1000)
                }}
              >
                Refresh
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateGroup}>
                Create Group
              </Button>
            </Space>
          }
        >
          <Table
            columns={columns}
            dataSource={consumerGroups}
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
          <Form form={form} layout="vertical">
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
                  <Select placeholder="Select setup">
                    <Select.Option value="production">Production</Select.Option>
                    <Select.Option value="staging">Staging</Select.Option>
                    <Select.Option value="development">Development</Select.Option>
                  </Select>
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
                  <Input placeholder="e.g., orders" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="maxMembers"
                  label="Max Members"
                  initialValue={5}
                  rules={[{ required: true, message: 'Please enter max members' }]}
                >
                  <Input type="number" min={1} max={50} />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="loadBalancingStrategy"
                  label="Load Balancing Strategy"
                  initialValue="ROUND_ROBIN"
                >
                  <Select>
                    <Select.Option value="ROUND_ROBIN">Round Robin</Select.Option>
                    <Select.Option value="RANGE">Range</Select.Option>
                    <Select.Option value="STICKY">Sticky</Select.Option>
                    <Select.Option value="RANDOM">Random</Select.Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="sessionTimeout"
                  label="Session Timeout (ms)"
                  initialValue={30000}
                >
                  <Input type="number" min={5000} max={300000} />
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
                  <Descriptions.Item label="Group ID">
                    <Text code>{selectedGroup.groupId}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="Setup">
                    <Tag color="blue">{selectedGroup.setupId}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="Queue">
                    <Tag color="purple">{selectedGroup.queueName}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="Load Balancing">
                    <Tag color="cyan">{selectedGroup.loadBalancingStrategy}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="Session Timeout">
                    <Text>{selectedGroup.sessionTimeout}ms</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="Created">
                    <Text>{dayjs(selectedGroup.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="Last Rebalance">
                    <Text>
                      {selectedGroup.lastRebalance
                        ? dayjs(selectedGroup.lastRebalance).format('YYYY-MM-DD HH:mm:ss')
                        : 'Never'
                      }
                    </Text>
                  </Descriptions.Item>
                </Descriptions>
              </Card>

              {/* Performance Metrics */}
              <Row gutter={[16, 16]}>
                <Col span={6}>
                  <Card size="small">
                    <Statistic
                      title="Members"
                      value={selectedGroup.memberCount}
                      suffix={`/ ${selectedGroup.maxMembers}`}
                      prefix={<UserOutlined />}
                    />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card size="small">
                    <Statistic
                      title="Partitions"
                      value={selectedGroup.assignedPartitions}
                      suffix={`/ ${selectedGroup.totalPartitions}`}
                      prefix={<PartitionOutlined />}
                    />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card size="small">
                    <Statistic
                      title="Throughput"
                      value={selectedGroup.messagesPerSecond.toFixed(1)}
                      suffix="msg/s"
                      prefix={<ApiOutlined />}
                    />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card size="small">
                    <Statistic
                      title="Total Processed"
                      value={selectedGroup.totalProcessed}
                      prefix={<CheckCircleOutlined />}
                    />
                  </Card>
                </Col>
              </Row>

              {/* Members Table */}
              <Card size="small" title={`Members (${selectedGroup.members.length})`}>
                <Table
                  columns={[
                    {
                      title: 'Member Name',
                      dataIndex: 'memberName',
                      key: 'memberName',
                      render: (text: string, record: ConsumerGroupMember) => (
                        <Space direction="vertical" size="small">
                          <Text strong>{text}</Text>
                          <Text code style={{ fontSize: '11px' }}>{record.memberId}</Text>
                        </Space>
                      )
                    },
                    {
                      title: 'Status',
                      dataIndex: 'status',
                      key: 'status',
                      render: (status: string) => (
                        <Tag color={getMemberStatusColor(status)}>
                          {status.toUpperCase()}
                        </Tag>
                      )
                    },
                    {
                      title: 'Assigned Partitions',
                      dataIndex: 'assignedPartitions',
                      key: 'assignedPartitions',
                      render: (partitions: number[]) => (
                        <Space wrap>
                          {partitions.map(p => (
                            <Tag key={p} color="geekblue">{p}</Tag>
                          ))}
                        </Space>
                      )
                    },
                    {
                      title: 'Processed',
                      dataIndex: 'processedMessages',
                      key: 'processedMessages',
                      render: (value: number) => value.toLocaleString()
                    },
                    {
                      title: 'Errors',
                      dataIndex: 'errorCount',
                      key: 'errorCount',
                      render: (value: number) => (
                        <Badge count={value} style={{ backgroundColor: value > 0 ? '#ff4d4f' : '#52c41a' }} />
                      )
                    },
                    {
                      title: 'Last Heartbeat',
                      dataIndex: 'lastHeartbeat',
                      key: 'lastHeartbeat',
                      render: (timestamp: string) => (
                        <Tooltip title={timestamp}>
                          <Space>
                            <HeartOutlined style={{ color: '#52c41a' }} />
                            <Text>{dayjs(timestamp).fromNow()}</Text>
                          </Space>
                        </Tooltip>
                      )
                    },
                    {
                      title: 'Joined',
                      dataIndex: 'joinedAt',
                      key: 'joinedAt',
                      render: (timestamp: string) => (
                        <Tooltip title={timestamp}>
                          <Text>{dayjs(timestamp).format('MMM DD, HH:mm')}</Text>
                        </Tooltip>
                      )
                    }
                  ]}
                  dataSource={selectedGroup.members}
                  pagination={false}
                  size="small"
                />
              </Card>

              {/* Partition Assignment Visualization */}
              <Card size="small" title="Partition Assignment">
                <Row gutter={[8, 8]}>
                  {Array.from({ length: selectedGroup.totalPartitions }, (_, i) => {
                    const assignedMember = selectedGroup.members.find(m =>
                      m.assignedPartitions.includes(i)
                    )
                    return (
                      <Col key={i}>
                        <Tooltip
                          title={assignedMember
                            ? `Partition ${i} assigned to ${assignedMember.memberName}`
                            : `Partition ${i} unassigned`
                          }
                        >
                          <div
                            style={{
                              width: 40,
                              height: 40,
                              border: '1px solid #d9d9d9',
                              borderRadius: 4,
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              backgroundColor: assignedMember ? '#e6f7ff' : '#f5f5f5',
                              color: assignedMember ? '#1890ff' : '#8c8c8c',
                              fontSize: '12px',
                              fontWeight: 'bold'
                            }}
                          >
                            {i}
                          </div>
                        </Tooltip>
                      </Col>
                    )
                  })}
                </Row>
              </Card>
            </Space>
          )}
        </Modal>
      </Space>
    </div>
  )
}

export default ConsumerGroups
