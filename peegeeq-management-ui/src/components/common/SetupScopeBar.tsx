import { useState, useEffect } from 'react'
import axios from 'axios'
import { Select, Row, Col } from 'antd'
import { getVersionedApiUrl } from '../../services/configService'
import { useManagementStore } from '../../stores/managementStore'

interface SetupScopeBarProps {
    /** 'setup' renders the setup selector only. */
    mode?: 'setup'
}

const SetupScopeBar = ({ mode = 'setup' }: SetupScopeBarProps) => {
    const { selectedSetupId, setSelectedSetup } = useManagementStore()
    const [setupIds, setSetupIds] = useState<string[]>([])
    const [loading, setLoading] = useState(false)

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
            } catch {
                setSetupIds([])
            } finally {
                setLoading(false)
            }
        }
        fetchSetups()
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    return (
        <Row gutter={[16, 0]} data-testid="scope-bar" style={{ marginBottom: 16 }}>
            <Col xs={24} sm={12} md={8} lg={6}>
                <Select
                    data-testid="setup-scope-selector"
                    placeholder="All setups"
                    style={{ width: '100%' }}
                    value={selectedSetupId || undefined}
                    onChange={(value) => setSelectedSetup(value ?? null)}
                    allowClear
                    loading={loading}
                >
                    {setupIds.map(id => (
                        <Select.Option key={id} value={id}>{id}</Select.Option>
                    ))}
                </Select>
            </Col>
        </Row>
    )
}

export default SetupScopeBar
