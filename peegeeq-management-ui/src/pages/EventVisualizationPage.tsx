import { useState, useEffect } from 'react'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'
import { Card, Select, Row, Col, Typography, message } from 'antd'
import { BranchesOutlined } from '@ant-design/icons'
import EventVisualization from '../components/EventVisualization'

const { Title } = Typography

interface EventStore {
    key: string
    name: string
    setupId: string
}

const EventVisualizationPage = () => {
    const [setups, setSetups] = useState<string[]>([])
    const [eventStores, setEventStores] = useState<EventStore[]>([])
    const [selectedSetup, setSelectedSetup] = useState<string>('')
    const [selectedEventStore, setSelectedEventStore] = useState<string>('')

    const fetchSetups = async () => {
        try {
            const response = await axios.get(getVersionedApiUrl('setups'))
            if (response.data && Array.isArray(response.data.setupIds)) {
                setSetups(response.data.setupIds)
            } else {
                setSetups([])
            }
        } catch (error) {
            console.error('Failed to fetch setups:', error)
            message.error('Failed to load database setups')
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

    const storesForSetup = eventStores.filter(s => s.setupId === selectedSetup)

    return (
        <div>
            <Title level={1} style={{ marginBottom: 24 }}>
                <BranchesOutlined style={{ marginRight: 8 }} />
                Event Visualization
            </Title>

            <Card title="Select Event Store" style={{ marginBottom: 16 }}>
                <Row gutter={16}>
                    <Col xs={24} sm={12} md={8}>
                        <Select
                            placeholder="Select setup"
                            style={{ width: '100%' }}
                            value={selectedSetup || undefined}
                            onChange={(value) => {
                                setSelectedSetup(value)
                                setSelectedEventStore('')
                            }}
                            allowClear
                            data-testid="viz-setup-select"
                        >
                            {setups.map(id => (
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
                            disabled={!selectedSetup}
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
                setupId={selectedSetup}
                eventStoreName={selectedEventStore}
            />
        </div>
    )
}

export default EventVisualizationPage
