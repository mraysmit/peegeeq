import React, { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
import {
    Card,
    Select,
    Row,
    Col,
    Input,
    Button,
    Tree,
    Space,
    Tag,
    Typography,
    Empty,
    Drawer,
    Descriptions,
    message
} from 'antd'
import { BranchesOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { peeGeeQClient } from '../api/PeeGeeQClient'
import { BiTemporalEvent } from '../api/types'

const { Text, Title } = Typography

interface EventStore {
    key: string
    name: string
    setupId: string
}

interface TreeNode {
    title: React.ReactNode
    key: string
    children?: TreeNode[]
    event?: BiTemporalEvent
}

const CausationTreePage = () => {
    const [setupIds, setSetupIds] = useState<string[]>([])
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [selectedSetupId, setSelectedSetupId] = useState<string>('')
    const [selectedEventStore, setSelectedEventStore] = useState<string>('')
    const [setupsLoading, setSetupsLoading] = useState(false)

    const [correlationId, setCorrelationId] = useState('')
    const [causationTreeData, setCausationTreeData] = useState<TreeNode[]>([])
    const [causationLoading, setCausationLoading] = useState(false)
    const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([])

    const [selectedEvent, setSelectedEvent] = useState<BiTemporalEvent | null>(null)
    const [drawerVisible, setDrawerVisible] = useState(false)

    const fetchSetups = async () => {
        setSetupsLoading(true)
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetupIds(response.data.setupIds)
            } else {
                setSetupIds([])
            }
        } catch (error) {
            console.error('Failed to fetch setups:', error)
            setSetupIds([])
        } finally {
            setSetupsLoading(false)
        }
    }

    const fetchEventStores = async () => {
        try {
            const response = await axios.get(getVersionedApiUrl('management/event-stores'))
            if (response.data.eventStores && Array.isArray(response.data.eventStores)) {
                setEventStores(response.data.eventStores.map((store: any) => ({
                    key: `${store.setup}-${store.name}`,
                    name: store.name,
                    setupId: store.setup,
                })))
            } else {
                setEventStores([])
            }
        } catch (error) {
            console.error('Failed to fetch event stores:', error)
            setEventStores([])
        }
    }

    useEffect(() => {
        fetchSetups()
        fetchEventStores()
    }, [])

    useEffect(() => {
        setSelectedEventStore('')
    }, [selectedSetupId])

    const storesForSetup = eventStores.filter(s => s.setupId === selectedSetupId)

    const buildCausationTree = (events: BiTemporalEvent[]): TreeNode[] => {
        const eventMap = new Map<string, TreeNode>()
        const roots: TreeNode[] = []

        events.forEach(event => {
            eventMap.set(event.eventId, {
                title: renderEventNode(event),
                key: event.eventId,
                children: [],
                event
            })
        })

        events.forEach(event => {
            const node = eventMap.get(event.eventId)
            if (!node) return
            if (event.causationId && eventMap.has(event.causationId)) {
                eventMap.get(event.causationId)!.children!.push(node)
            } else {
                roots.push(node)
            }
        })

        const sortNodes = (nodes: TreeNode[]) => {
            nodes.sort((a, b) =>
                dayjs(a.event?.transactionTime).valueOf() - dayjs(b.event?.transactionTime).valueOf()
            )
            nodes.forEach(node => {
                if (node.children && node.children.length > 0) {
                    sortNodes(node.children)
                } else {
                    node.children = undefined
                }
            })
        }
        sortNodes(roots)
        return roots
    }

    const renderEventNode = (event: BiTemporalEvent) => (
        <div style={{ borderBottom: '1px solid #d9d9d9', padding: '4px 0', width: '100%' }}>
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
                    onClick={(e) => { e.stopPropagation(); setSelectedEvent(event); setDrawerVisible(true) }}
                />
            </Space>
        </div>
    )

    const fetchCausationTree = async () => {
        if (!selectedSetupId || !selectedEventStore) {
            message.warning('Please select a setup and event store')
            return
        }
        if (!correlationId) {
            message.warning('Please enter a Correlation ID')
            return
        }
        setCausationLoading(true)
        try {
            const response = await peeGeeQClient.queryEvents(selectedSetupId, selectedEventStore, {
                correlationId,
                limit: 1000,
                offset: 0,
                sortOrder: 'TRANSACTION_TIME_ASC',
                includeCorrections: true
            })
            const events = response.events || []
            if (events.length === 0) {
                message.info('No events found for this Correlation ID')
                setCausationTreeData([])
                return
            }
            setCausationTreeData(buildCausationTree(events))
            setExpandedKeys(events.map(e => e.eventId))
        } catch (error: any) {
            message.error(`Failed to fetch causation tree: ${error.message}`)
        } finally {
            setCausationLoading(false)
        }
    }

    return (
        <div>
            <Title level={1} style={{ marginBottom: 24 }}>
                <BranchesOutlined style={{ marginRight: 8 }} />
                Causation Tree
            </Title>

            <Card title="Select Event Store" size="small" style={{ marginBottom: 16 }}>
                <Row gutter={16}>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            data-testid="causation-setup-select"
                            placeholder="Select setup"
                            style={{ width: '100%' }}
                            value={selectedSetupId || undefined}
                            onChange={setSelectedSetupId}
                            loading={setupsLoading}
                            allowClear
                        >
                            {setupIds.map(id => (
                                <Select.Option key={id} value={id}>{id}</Select.Option>
                            ))}
                        </Select>
                    </Col>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            data-testid="causation-eventstore-select"
                            placeholder="Select event store"
                            style={{ width: '100%' }}
                            value={selectedEventStore || undefined}
                            onChange={setSelectedEventStore}
                            disabled={!selectedSetupId}
                            allowClear
                        >
                            {storesForSetup.map(store => (
                                <Select.Option key={store.key} value={store.name}>
                                    {store.name}
                                </Select.Option>
                            ))}
                        </Select>
                    </Col>
                </Row>
            </Card>

            <Card title={<span><BranchesOutlined /> Causation Tree</span>}>
                <Space style={{ marginBottom: 16 }}>
                    <Input
                        data-testid="causation-correlation-input"
                        placeholder="Enter Correlation ID"
                        value={correlationId}
                        onChange={e => setCorrelationId(e.target.value)}
                        style={{ width: 360 }}
                        onPressEnter={fetchCausationTree}
                    />
                    <Button
                        type="primary"
                        icon={<SearchOutlined />}
                        onClick={fetchCausationTree}
                        loading={causationLoading}
                        disabled={!selectedSetupId || !selectedEventStore}
                    >
                        Trace
                    </Button>
                </Space>

                {causationTreeData.length > 0 ? (
                    <Tree
                        showLine
                        showIcon={false}
                        blockNode
                        defaultExpandAll
                        expandedKeys={expandedKeys}
                        onExpand={setExpandedKeys}
                        treeData={causationTreeData}
                        style={{ background: '#fafafa', padding: 16, borderRadius: 8 }}
                    />
                ) : (
                    <Empty description={
                        selectedSetupId && selectedEventStore
                            ? 'Enter a Correlation ID and click Trace to visualize the event flow'
                            : 'Select a setup and event store first'
                    } />
                )}
            </Card>

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
                                            setCorrelationId(selectedEvent.correlationId!)
                                            setDrawerVisible(false)
                                            setTimeout(() => fetchCausationTree(), 100)
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
    )
}

export default CausationTreePage
