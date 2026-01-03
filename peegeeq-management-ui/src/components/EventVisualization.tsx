import React, { useState, useEffect } from 'react';
import { 
    Card, 
    Tabs, 
    Input, 
    Button, 
    Table, 
    Tree, 
    Space, 
    Tag, 
    Typography, 
    message, 
    Empty, 
    Drawer,
    Descriptions,
    Badge
} from 'antd';
import { 
    SearchOutlined, 
    BranchesOutlined, 
    DatabaseOutlined, 
    ReloadOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { peeGeeQClient } from '../api/PeeGeeQClient';
import { BiTemporalEvent } from '../api/types';

const { Text } = Typography;
const { TabPane } = Tabs;

interface EventVisualizationProps {
    setupId: string;
    eventStoreName: string;
}

interface TreeNode {
    title: React.ReactNode;
    key: string;
    children?: TreeNode[];
    event?: BiTemporalEvent;
}

const EventVisualization: React.FC<EventVisualizationProps> = ({ setupId, eventStoreName }) => {
    const [activeTab, setActiveTab] = useState('causation');
    
    // Causation Tree State
    const [correlationId, setCorrelationId] = useState('');
    const [causationTreeData, setCausationTreeData] = useState<TreeNode[]>([]);
    const [causationLoading, setCausationLoading] = useState(false);
    const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

    // Aggregate Stream State
    const [aggregates, setAggregates] = useState<string[]>([]);
    const [selectedAggregate, setSelectedAggregate] = useState<string | null>(null);
    const [aggregateEvents, setAggregateEvents] = useState<BiTemporalEvent[]>([]);
    const [aggregatesLoading, setAggregatesLoading] = useState(false);
    const [aggregateEventsLoading, setAggregateEventsLoading] = useState(false);
    const [eventTypeFilter, setEventTypeFilter] = useState<string>('');

    // Event Details Drawer State
    const [selectedEvent, setSelectedEvent] = useState<BiTemporalEvent | null>(null);
    const [drawerVisible, setDrawerVisible] = useState(false);

    // Fetch Aggregates
    const fetchAggregates = async () => {
        if (!setupId || !eventStoreName) return;
        
        setAggregatesLoading(true);
        try {
            const response = await peeGeeQClient.getUniqueAggregates(setupId, eventStoreName, eventTypeFilter);
            setAggregates(response.aggregates || []);
        } catch (error: any) {
            message.error(`Failed to fetch aggregates: ${error.message}`);
        } finally {
            setAggregatesLoading(false);
        }
    };

    // Fetch Aggregate Events
    const fetchAggregateEvents = async (aggregateId: string) => {
        if (!setupId || !eventStoreName) return;
        
        setAggregateEventsLoading(true);
        setSelectedAggregate(aggregateId);
        try {
            const response = await peeGeeQClient.queryEvents(setupId, eventStoreName, {
                aggregateId,
                limit: 1000,
                offset: 0,
                sortOrder: 'VERSION_ASC',
                includeCorrections: true
            });
            setAggregateEvents(response.events || []);
        } catch (error: any) {
            message.error(`Failed to fetch events for aggregate ${aggregateId}: ${error.message}`);
        } finally {
            setAggregateEventsLoading(false);
        }
    };

    // Fetch Causation Tree
    const fetchCausationTree = async () => {
        if (!setupId || !eventStoreName || !correlationId) {
            message.warning('Please enter a Correlation ID');
            return;
        }
        
        setCausationLoading(true);
        try {
            // Fetch all events for this correlation ID
            const response = await peeGeeQClient.queryEvents(setupId, eventStoreName, {
                correlationId,
                limit: 1000,
                offset: 0,
                sortOrder: 'TRANSACTION_TIME_ASC',
                includeCorrections: true
            });
            
            const events = response.events || [];
            console.error(`[EventVisualization] Fetched ${events.length} events for correlationId=${correlationId}`);
            if (events.length === 0) {
                message.info('No events found for this Correlation ID');
                setCausationTreeData([]);
                return;
            }

            // Build Tree Structure
            const tree = buildCausationTree(events);
            setCausationTreeData(tree);
            
            // Expand all nodes by default
            const allKeys = events.map(e => e.eventId);
            setExpandedKeys(allKeys);
            
        } catch (error: any) {
            console.error(`[EventVisualization] Error fetching causation tree:`, error);
            message.error(`Failed to fetch causation tree: ${error.message}`);
        } finally {
            setCausationLoading(false);
        }
    };

    // Helper to build tree from flat event list
    const buildCausationTree = (events: BiTemporalEvent[]): TreeNode[] => {
        const eventMap = new Map<string, TreeNode>();
        const roots: TreeNode[] = [];

        // First pass: Create nodes
        events.forEach(event => {
            eventMap.set(event.eventId, {
                title: renderEventNode(event),
                key: event.eventId,
                children: [],
                event
            });
        });

        // Second pass: Link children to parents
        events.forEach(event => {
            const node = eventMap.get(event.eventId);
            if (!node) return;

            if (event.causationId && eventMap.has(event.causationId)) {
                const parent = eventMap.get(event.causationId)!;
                parent.children!.push(node);
            } else {
                roots.push(node);
            }
        });

        // Sort children by transaction time
        const sortNodes = (nodes: TreeNode[]) => {
            nodes.sort((a, b) => {
                const timeA = dayjs(a.event?.transactionTime).valueOf();
                const timeB = dayjs(b.event?.transactionTime).valueOf();
                return timeA - timeB;
            });
            nodes.forEach(node => {
                if (node.children && node.children.length > 0) {
                    sortNodes(node.children);
                } else {
                    node.children = undefined; // Remove empty children array for leaf nodes
                }
            });
        };

        sortNodes(roots);
        return roots;
    };

    const renderEventNode = (event: BiTemporalEvent) => (
        <Space>
            <Tag color="purple">{event.eventType}</Tag>
            <Text type="secondary" style={{ fontSize: '12px' }}>
                {dayjs(event.transactionTime).format('HH:mm:ss.SSS')}
            </Text>
            {event.aggregateId && (
                <Tag color="cyan" style={{ fontSize: '10px' }}>{event.aggregateId}</Tag>
            )}
            <Button 
                type="link" 
                size="small" 
                icon={<SearchOutlined />} 
                onClick={(e) => {
                    e.stopPropagation();
                    handleViewEventDetails(event);
                }}
            />
        </Space>
    );

    const handleViewEventDetails = (event: BiTemporalEvent) => {
        setSelectedEvent(event);
        setDrawerVisible(true);
    };

    // Aggregate Table Columns
    const aggregateColumns = [
        {
            title: 'Aggregate ID',
            dataIndex: 'aggregateId',
            key: 'aggregateId',
            render: (text: string) => <Text strong>{text}</Text>
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: any, record: any) => (
                <Button 
                    type="link" 
                    onClick={() => fetchAggregateEvents(record.aggregateId)}
                >
                    View Stream
                </Button>
            )
        }
    ];

    // Event Stream Columns
    const eventStreamColumns = [
        {
            title: 'Version',
            dataIndex: 'version',
            key: 'version',
            width: 80,
            render: (version: number) => <Badge count={version} style={{ backgroundColor: '#52c41a' }} />
        },
        {
            title: 'Event Type',
            dataIndex: 'eventType',
            key: 'eventType',
            render: (text: string) => <Tag color="purple">{text}</Tag>
        },
        {
            title: 'Valid Time',
            dataIndex: 'validTime',
            key: 'validTime',
            render: (text: string) => dayjs(text).format('YYYY-MM-DD HH:mm:ss')
        },
        {
            title: 'Transaction Time',
            dataIndex: 'transactionTime',
            key: 'transactionTime',
            render: (text: string) => dayjs(text).format('YYYY-MM-DD HH:mm:ss.SSS')
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: any, record: BiTemporalEvent) => (
                <Button 
                    type="link" 
                    icon={<SearchOutlined />} 
                    onClick={() => handleViewEventDetails(record)}
                >
                    Details
                </Button>
            )
        }
    ];

    useEffect(() => {
        if (activeTab === 'aggregates' && aggregates.length === 0) {
            fetchAggregates();
        }
    }, [activeTab, setupId, eventStoreName]);

    const items = [
        {
            key: 'causation',
            label: <span><BranchesOutlined /> Causation Tree</span>,
            children: (
                <Card>
                    <Space style={{ marginBottom: 16 }}>
                        <Input 
                            placeholder="Enter Correlation ID" 
                            value={correlationId}
                            onChange={e => setCorrelationId(e.target.value)}
                            style={{ width: 300 }}
                            onPressEnter={fetchCausationTree}
                        />
                        <Button 
                            type="primary" 
                            icon={<SearchOutlined />} 
                            onClick={fetchCausationTree}
                            loading={causationLoading}
                        >
                            Trace
                        </Button>
                    </Space>

                    {causationTreeData.length > 0 ? (
                        <Tree
                            showLine
                            showIcon={false}
                            defaultExpandAll
                            expandedKeys={expandedKeys}
                            onExpand={setExpandedKeys}
                            treeData={causationTreeData}
                            style={{ background: '#fafafa', padding: 16, borderRadius: 8 }}
                        />
                    ) : (
                        <Empty description="Enter a Correlation ID to visualize the event flow" />
                    )}
                </Card>
            )
        },
        {
            key: 'aggregates',
            label: <span><DatabaseOutlined /> Aggregate Stream</span>,
            children: (
                <div style={{ display: 'flex', gap: 16 }}>
                    <Card title="Aggregates" style={{ width: 300 }} bodyStyle={{ padding: 0 }}>
                        <div style={{ padding: 16, borderBottom: '1px solid #f0f0f0' }}>
                            <Space direction="vertical" style={{ width: '100%' }}>
                                <Input 
                                    placeholder="Filter by Event Type" 
                                    value={eventTypeFilter}
                                    onChange={e => setEventTypeFilter(e.target.value)}
                                    allowClear
                                />
                                <Button 
                                    block 
                                    icon={<ReloadOutlined />} 
                                    onClick={fetchAggregates}
                                    loading={aggregatesLoading}
                                >
                                    Refresh List
                                </Button>
                            </Space>
                        </div>
                        <div style={{ maxHeight: 600, overflowY: 'auto' }}>
                            <Table
                                dataSource={aggregates.map(id => ({ key: id, aggregateId: id }))}
                                columns={aggregateColumns}
                                pagination={false}
                                loading={aggregatesLoading}
                                size="small"
                                onRow={(record) => ({
                                    onClick: () => fetchAggregateEvents(record.aggregateId),
                                    style: { cursor: 'pointer', background: selectedAggregate === record.aggregateId ? '#e6f7ff' : undefined }
                                })}
                            />
                        </div>
                    </Card>

                    <Card title={selectedAggregate ? `Stream: ${selectedAggregate}` : "Select an Aggregate"} style={{ flex: 1 }}>
                        {selectedAggregate ? (
                            <Table
                                dataSource={aggregateEvents}
                                columns={eventStreamColumns}
                                rowKey="eventId"
                                loading={aggregateEventsLoading}
                                pagination={{ pageSize: 10 }}
                            />
                        ) : (
                            <Empty description="Select an aggregate from the list to view its event stream" />
                        )}
                    </Card>
                </div>
            )
        }
    ];

    return (
        <div className="event-visualization">
            <Tabs activeKey={activeTab} onChange={setActiveTab} items={items} />

            <Drawer
                title="Event Details"
                placement="right"
                width={600}
                onClose={() => setDrawerVisible(false)}
                open={drawerVisible}
            >
                {selectedEvent && (
                    <Descriptions column={1} bordered size="small">
                        <Descriptions.Item label="Event ID">
                            <Text copyable>{selectedEvent.eventId}</Text>
                        </Descriptions.Item>
                        <Descriptions.Item label="Event Type">
                            <Tag color="purple">{selectedEvent.eventType}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="Aggregate ID">
                            {selectedEvent.aggregateId ? <Text copyable>{selectedEvent.aggregateId}</Text> : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Correlation ID">
                            {selectedEvent.correlationId ? (
                                <Space>
                                    <Text copyable>{selectedEvent.correlationId}</Text>
                                    <Button 
                                        type="link" 
                                        size="small" 
                                        onClick={() => {
                                            setCorrelationId(selectedEvent.correlationId!);
                                            setActiveTab('causation');
                                            setDrawerVisible(false);
                                            // Trigger fetch in next render cycle effectively
                                            setTimeout(() => fetchCausationTree(), 100);
                                        }}
                                    >
                                        Trace
                                    </Button>
                                </Space>
                            ) : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Causation ID">
                            {selectedEvent.causationId ? <Text copyable>{selectedEvent.causationId}</Text> : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Valid Time">
                            {dayjs(selectedEvent.validTime).format('YYYY-MM-DD HH:mm:ss')}
                        </Descriptions.Item>
                        <Descriptions.Item label="Transaction Time">
                            {dayjs(selectedEvent.transactionTime).format('YYYY-MM-DD HH:mm:ss.SSS')}
                        </Descriptions.Item>
                        <Descriptions.Item label="Payload">
                            <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: '11px' }}>
                                {JSON.stringify(selectedEvent.payload, null, 2)}
                            </pre>
                        </Descriptions.Item>
                        <Descriptions.Item label="Headers">
                            <pre style={{ maxHeight: 200, overflow: 'auto', fontSize: '11px' }}>
                                {JSON.stringify(selectedEvent.headers, null, 2)}
                            </pre>
                        </Descriptions.Item>
                    </Descriptions>
                )}
            </Drawer>
        </div>
    );
};

export default EventVisualization;
