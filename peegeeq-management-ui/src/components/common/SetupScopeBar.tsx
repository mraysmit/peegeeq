import { useState, useEffect } from 'react'
import axios from 'axios'
import { Select, Row, Col, Typography, message } from 'antd'
import { getVersionedApiUrl } from '../../services/configService'
import { useManagementStore } from '../../stores/managementStore'

interface SetupScopeBarProps {
    /** 'setup' renders the setup selector only; 'setup+queue' adds a dependent queue selector. */
    mode?: 'setup' | 'setup+queue'
    /** Optional content rendered in a Col immediately after the setup selector. */
    extra?: React.ReactNode
}

const SetupScopeBar = ({ mode = 'setup', extra }: SetupScopeBarProps) => {
    const { selectedSetupId, selectedQueueName, setSelectedSetup, setSelectedQueue } = useManagementStore()
    const [setupIds, setSetupIds] = useState<string[]>([])
    const [queueNames, setQueueNames] = useState<string[]>([])
    const [loading, setLoading] = useState(false)
    const [queuesLoading, setQueuesLoading] = useState(false)

    useEffect(() => {
        const fetchSetups = async () => {
            setLoading(true)
            try {
                const response = await axios.get(getVersionedApiUrl('setups'))
                if (response.data && Array.isArray(response.data.setupIds)) {
                    const ids: string[] = response.data.setupIds
                    setSetupIds(ids)
                    // Auto-select when exactly one setup exists and nothing is already selected
                    if (ids.length === 1 && !selectedSetupId) {
                        setSelectedSetup(ids[0])
                    }
                } else {
                    setSetupIds([])
                }
            } catch (err) {
                setSetupIds([])
                message.error('Failed to load setups')
                console.error('Failed to load setups', err)
            } finally {
                setLoading(false)
            }
        }
        fetchSetups()
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    useEffect(() => {
        if (mode !== 'setup+queue' || !selectedSetupId) {
            setQueueNames([])
            return
        }
        const fetchQueues = async () => {
            setQueuesLoading(true)
            try {
                const response = await axios.get(getVersionedApiUrl(`setups/${selectedSetupId}`))
                const factories = response.data?.queueFactories
                // The API returns queueFactories as a JSON array of queue name strings.
                // Object.keys(array) returns numeric indices, not the values — handle both formats.
                const names: string[] = Array.isArray(factories)
                    ? factories
                    : (factories && typeof factories === 'object' ? Object.keys(factories) : [])
                setQueueNames(names)
                // Auto-select when exactly one queue exists and nothing is already selected
                if (names.length === 1 && !selectedQueueName) {
                    setSelectedQueue(names[0])
                }
            } catch (err) {
                setQueueNames([])
                message.error('Failed to load queues for setup')
                console.error('Failed to load queues for setup', err)
            } finally {
                setQueuesLoading(false)
            }
        }
        fetchQueues()
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedSetupId, mode])

    return (
        <Row gutter={[16, 0]} align="bottom" data-testid="scope-bar" style={{ marginBottom: 16 }}>
            <Col xs={24} sm={12} md={8} lg={6}>
                <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4, fontSize: 12 }}>Setup</Typography.Text>
                <Select
                    data-testid="setup-scope-selector"
                    placeholder="All setups"
                    style={{ width: '100%' }}
                    value={selectedSetupId || undefined}
                    onChange={(value) => setSelectedSetup(value ?? null)}
                    allowClear
                    showSearch
                    optionFilterProp="children"
                    loading={loading}
                >
                    {setupIds.map(id => (
                        <Select.Option key={id} value={id}>{id}</Select.Option>
                    ))}
                </Select>
            </Col>
            {extra && (
                <Col flex="none" style={{ paddingBottom: 0 }}>{extra}</Col>
            )}
            {mode === 'setup+queue' && (
                <Col xs={24} sm={12} md={8} lg={6}>
                    <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4, fontSize: 12 }}>Queue</Typography.Text>
                    <Select
                        data-testid="queue-scope-selector"
                        placeholder="All queues"
                        style={{ width: '100%' }}
                        value={selectedQueueName || undefined}
                        onChange={(value) => setSelectedQueue(value ?? null)}
                        allowClear
                        showSearch
                        optionFilterProp="children"
                        disabled={!selectedSetupId}
                        loading={queuesLoading}
                    >
                        {queueNames.map(name => (
                            <Select.Option key={name} value={name}>{name}</Select.Option>
                        ))}
                    </Select>
                </Col>
            )}
        </Row>
    )
}

export default SetupScopeBar
