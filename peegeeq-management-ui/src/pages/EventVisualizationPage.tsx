import { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
import { Card, Select, Row, Col, Typography } from 'antd'
import { BranchesOutlined } from '@ant-design/icons'
import EventVisualization from '../components/EventVisualization'
import SetupScopeBar from '../components/common/SetupScopeBar'
import { useManagementStore } from '../stores/managementStore'

const { Title } = Typography

interface EventStore {
    key: string
    name: string
    setupId: string
}

const EventVisualizationPage = () => {
    const { selectedSetupId, setSelectedSetup } = useManagementStore()
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [selectedEventStore, setSelectedEventStore] = useState<string>('')
    const [setupIds, setSetupIds] = useState<string[]>([])

    const fetchSetups = async () => {
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

    return (
        <div>
            <Title level={1} style={{ marginBottom: 24 }}>
                <BranchesOutlined style={{ marginRight: 8 }} />
                Event Visualization
            </Title>

            <SetupScopeBar />

            <Card title="Select Event Store" style={{ marginBottom: 16 }}>
                <Row gutter={16}>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            data-testid="viz-setup-select"
                            placeholder="Select setup"
                            style={{ width: '100%' }}
                            value={selectedSetupId || undefined}
                            onChange={(value) => setSelectedSetup(value)}
                            allowClear
                        >
                            {setupIds.map(id => (
                                <Select.Option key={id} value={id}>{id}</Select.Option>
                            ))}
                        </Select>
                    </Col>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            placeholder="Select event store"
                            style={{ width: '100%' }}
                            value={selectedEventStore || undefined}
                            onChange={(value) => setSelectedEventStore(value)}
                            disabled={!selectedSetupId}
                            allowClear
                            data-testid="viz-eventstore-select"
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

            <EventVisualization
                setupId={selectedSetupId ?? ''}
                eventStoreName={selectedEventStore}
            />
        </div>
    )
}

export default EventVisualizationPage
