import React, { useState, useEffect, useCallback } from 'react'
import { Card, Form, Input, Button, Space, message, Alert, Typography, Divider, Badge, Row, Col, InputNumber, Switch } from 'antd'
import { ApiOutlined, CheckCircleOutlined, CloseCircleOutlined, SyncOutlined, DisconnectOutlined, ThunderboltOutlined } from '@ant-design/icons'
import {
  getBackendConfig,
  saveBackendConfig,
  testRestConnection,
  testWebSocketConnection,
  testSSEConnection,
  resetBackendConfig,
  BackendConfig
} from '../services/configService'

const { Title, Text } = Typography

const Settings: React.FC = () => {
  const [form] = Form.useForm()
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [currentConnectionStatus, setCurrentConnectionStatus] = useState<'connected' | 'disconnected' | 'checking'>('checking')

  // Individual ping states
  const [pingRestLoading, setPingRestLoading] = useState(false)
  const [pingRestResult, setPingRestResult] = useState<{ success: boolean; message: string; timestamp?: Date } | null>(null)
  const [pingWsLoading, setPingWsLoading] = useState(false)
  const [pingWsResult, setPingWsResult] = useState<{ success: boolean; message: string; timestamp?: Date } | null>(null)
  const [pingSseLoading, setPingSseLoading] = useState(false)
  const [pingSseResult, setPingSseResult] = useState<{ success: boolean; message: string; timestamp?: Date } | null>(null)

  // Auto-ping intervals (in seconds, 0 = disabled)
  const [restPingInterval, setRestPingInterval] = useState(10)
  const [wsPingInterval, setWsPingInterval] = useState(10)
  const [ssePingInterval, setSSePingInterval] = useState(10)

  // Auto-ping enabled states
  const [restAutoPingEnabled, setRestAutoPingEnabled] = useState(false)
  const [wsAutoPingEnabled, setWsAutoPingEnabled] = useState(false)
  const [sseAutoPingEnabled, setSseAutoPingEnabled] = useState(false)

  // Define callback functions first (before useEffect hooks that depend on them)
  const checkCurrentConnection = async () => {
    setCurrentConnectionStatus('checking')
    const config = getBackendConfig()
    const result = await testRestConnection(config.apiUrl)
    setCurrentConnectionStatus(result.success ? 'connected' : 'disconnected')
  }

  const handlePingRest = useCallback(async () => {
    try {
      const apiUrl = form.getFieldValue('apiUrl')
      if (!apiUrl) {
        return
      }

      setPingRestLoading(true)

      const result = await testRestConnection(apiUrl)
      setPingRestResult({
        ...result,
        timestamp: new Date()
      })

      // Only show error messages, not success messages
      if (!result.success) {
        message.error(`REST API ping failed: ${result.message}`)
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      setPingRestResult({
        success: false,
        message: errorMessage,
        timestamp: new Date()
      })
      message.error(`REST API ping failed: ${errorMessage}`)
    } finally {
      setPingRestLoading(false)
    }
  }, [form])

  const handlePingWebSocket = useCallback(async () => {
    try {
      const apiUrl = form.getFieldValue('apiUrl')
      if (!apiUrl) {
        return
      }

      setPingWsLoading(true)

      const wsUrl = `${apiUrl.replace('http', 'ws')}/ws/health`
      const result = await testWebSocketConnection(wsUrl)
      setPingWsResult({
        ...result,
        timestamp: new Date()
      })

      // Only show error messages, not success messages
      if (!result.success) {
        message.error(`WebSocket ping failed: ${result.message}`)
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      setPingWsResult({
        success: false,
        message: errorMessage,
        timestamp: new Date()
      })
      message.error(`WebSocket ping failed: ${errorMessage}`)
    } finally {
      setPingWsLoading(false)
    }
  }, [form])

  const handlePingSSE = useCallback(async () => {
    try {
      const apiUrl = form.getFieldValue('apiUrl')
      if (!apiUrl) {
        return
      }

      setPingSseLoading(true)

      const result = await testSSEConnection(apiUrl)
      setPingSseResult({
        ...result,
        timestamp: new Date()
      })

      // Only show error messages, not success messages
      if (!result.success) {
        message.error(`SSE ping failed: ${result.message}`)
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      setPingSseResult({
        success: false,
        message: errorMessage,
        timestamp: new Date()
      })
      message.error(`SSE ping failed: ${errorMessage}`)
    } finally {
      setPingSseLoading(false)
    }
  }, [form])

  // Load configuration on mount
  useEffect(() => {
    // Load current configuration
    const config = getBackendConfig()
    form.setFieldsValue(config)

    // Check current connection status
    checkCurrentConnection()
  }, [form])

  // Auto-ping for REST
  useEffect(() => {
    if (!restAutoPingEnabled || restPingInterval <= 0) return

    const interval = setInterval(() => {
      handlePingRest()
    }, restPingInterval * 1000)

    return () => clearInterval(interval)
  }, [restAutoPingEnabled, restPingInterval, handlePingRest])

  // Auto-ping for WebSocket
  useEffect(() => {
    if (!wsAutoPingEnabled || wsPingInterval <= 0) return

    const interval = setInterval(() => {
      handlePingWebSocket()
    }, wsPingInterval * 1000)

    return () => clearInterval(interval)
  }, [wsAutoPingEnabled, wsPingInterval, handlePingWebSocket])

  // Auto-ping for SSE
  useEffect(() => {
    if (!sseAutoPingEnabled || ssePingInterval <= 0) return

    const interval = setInterval(() => {
      handlePingSSE()
    }, ssePingInterval * 1000)

    return () => clearInterval(interval)
  }, [sseAutoPingEnabled, ssePingInterval, handlePingSSE])

  const handleTestConnection = async () => {
    try {
      const apiUrl = form.getFieldValue('apiUrl')
      if (!apiUrl) {
        message.warning('Please enter an API URL first')
        return
      }

      setTesting(true)
      setTestResult(null)

      const result = await testRestConnection(apiUrl)
      setTestResult(result)

      if (result.success) {
        message.success('Connection test successful!')
      } else {
        message.error(`Connection test failed: ${result.message}`)
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      setTestResult({
        success: false,
        message: errorMessage
      })
      message.error(`Connection test failed: ${errorMessage}`)
    } finally {
      setTesting(false)
    }
  }

  const handleSave = async () => {
    try {
      const values = await form.validateFields()
      setSaving(true)

      const config: BackendConfig = {
        apiUrl: values.apiUrl,
        wsUrl: values.wsUrl
      }

      saveBackendConfig(config)

      // Dispatch custom event to notify other components
      window.dispatchEvent(new CustomEvent('peegeeq-config-changed', { detail: config }))

      // Recheck connection status with the new config
      await checkCurrentConnection()

      message.success('Configuration saved successfully!')
      setTestResult(null)
    } catch (error) {
      message.error('Failed to save configuration')
    } finally {
      setSaving(false)
    }
  }

  const handleReset = async () => {
    resetBackendConfig()
    const defaultConfig = getBackendConfig()
    form.setFieldsValue(defaultConfig)
    setTestResult(null)

    // Dispatch custom event to notify other components
    window.dispatchEvent(new CustomEvent('peegeeq-config-changed', { detail: defaultConfig }))

    // Recheck connection status with the default config
    await checkCurrentConnection()

    message.info('Configuration reset to defaults')
  }

  return (
    <div className="fade-in">
      <Title level={1}>Settings</Title>

      <Card
        title={
          <Space>
            <ApiOutlined />
            <span>Backend Connection</span>
          </Space>
        }
        extra={
          <Space>
            <Text type="secondary">Current Status:</Text>
            {currentConnectionStatus === 'checking' && (
              <Badge status="processing" text="Checking..." />
            )}
            {currentConnectionStatus === 'connected' && (
              <Badge status="success" text="Connected" />
            )}
            {currentConnectionStatus === 'disconnected' && (
              <Badge status="error" text="Disconnected" />
            )}
          </Space>
        }
      >
        <Alert
          message="Backend Configuration"
          description="Configure the connection to the PeeGeeQ REST API server. Test the connection before saving to ensure the server is accessible."
          type="info"
          showIcon
          style={{ marginBottom: 24 }}
        />

        <Form
          form={form}
          layout="vertical"
          data-testid="settings-form"
        >
          <Form.Item
            label="API URL"
            name="apiUrl"
            rules={[
              { required: true, message: 'Please enter the API URL' },
              { type: 'url', message: 'Please enter a valid URL' }
            ]}
            extra="The base URL of the PeeGeeQ REST API (e.g., http://localhost:8080)"
          >
            <Input
              placeholder="http://localhost:8080"
              data-testid="api-url-input"
            />
          </Form.Item>

          <Form.Item
            label="WebSocket URL (Optional)"
            name="wsUrl"
            rules={[
              { type: 'url', message: 'Please enter a valid WebSocket URL' }
            ]}
            extra="The WebSocket URL for real-time updates (e.g., ws://localhost:8080/ws)"
          >
            <Input
              placeholder="ws://localhost:8080/ws"
              data-testid="ws-url-input"
            />
          </Form.Item>

          {testResult && (
            <Alert
              message={testResult.success ? 'Connection Successful' : 'Connection Failed'}
              description={testResult.message}
              type={testResult.success ? 'success' : 'error'}
              icon={testResult.success ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
              showIcon
              style={{ marginBottom: 16 }}
              data-testid="test-result"
            />
          )}

          <Space wrap>
            <Button
              type="primary"
              icon={<SyncOutlined />}
              onClick={async () => {
                await handleTestConnection()
                // Always recheck after test
                await checkCurrentConnection()
              }}
              loading={testing}
              data-testid="test-connection-btn"
            >
              Test & Connect
            </Button>
            <Button
              danger
              icon={<DisconnectOutlined />}
              onClick={async () => {
                // Change to a non-existent URL to force disconnect
                form.setFieldsValue({
                  apiUrl: 'http://localhost:9999',
                  wsUrl: 'ws://localhost:9999/ws'
                })
                await handleSave()
                // Force immediate recheck
                await checkCurrentConnection()
                message.info('Disconnected from backend')
              }}
              disabled={currentConnectionStatus === 'disconnected'}
              data-testid="disconnect-btn"
            >
              Disconnect
            </Button>
            <Button
              type="default"
              onClick={handleSave}
              loading={saving}
              data-testid="save-settings-btn"
            >
              Save Configuration
            </Button>
            <Button
              onClick={handleReset}
              data-testid="reset-settings-btn"
            >
              Reset to Defaults
            </Button>
          </Space>
        </Form>

        <Divider />

        <div>
          <Text type="secondary">
            <strong>Note:</strong> After changing the backend configuration, you may need to refresh the page for all components to use the new settings.
          </Text>
        </div>
      </Card>

      <Card
        title={
          <Space>
            <ThunderboltOutlined />
            <span>Connection Health Checks</span>
          </Space>
        }
        style={{ marginTop: 24 }}
      >
        <Alert
          message="Individual Health Checks"
          description="Test each connection type individually to diagnose connectivity issues."
          type="info"
          showIcon
          style={{ marginBottom: 24 }}
        />

        <Row gutter={[16, 16]}>
          <Col xs={24} md={8}>
            <Card
              size="small"
              title="REST API"
              extra={
                pingRestResult && (
                  pingRestResult.success ? (
                    <CheckCircleOutlined style={{ color: '#52c41a' }} />
                  ) : (
                    <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                  )
                )
              }
            >
              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <Text type="secondary">Endpoint: /api/v1/health</Text>
                {pingRestResult && (
                  <Alert
                    message={
                      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                        <span>{pingRestResult.message}</span>
                        {pingRestResult.timestamp && (
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            Last ping: {pingRestResult.timestamp.toLocaleTimeString()}
                          </Text>
                        )}
                      </Space>
                    }
                    type={pingRestResult.success ? 'success' : 'error'}
                    showIcon
                    style={{ marginBottom: 8 }}
                  />
                )}
                <Button
                  type="primary"
                  icon={<SyncOutlined />}
                  onClick={handlePingRest}
                  loading={pingRestLoading}
                  block
                >
                  Ping Now
                </Button>
                <Divider style={{ margin: '8px 0' }} />
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Space>
                    <Switch
                      checked={restAutoPingEnabled}
                      onChange={setRestAutoPingEnabled}
                      size="small"
                    />
                    <Text type="secondary">Auto-ping</Text>
                  </Space>
                  {restAutoPingEnabled && (
                    <InputNumber
                      min={1}
                      max={300}
                      value={restPingInterval}
                      onChange={(val) => setRestPingInterval(val || 10)}
                      size="small"
                      style={{ width: 80 }}
                      addonAfter="sec"
                    />
                  )}
                </Space>
              </Space>
            </Card>
          </Col>

          <Col xs={24} md={8}>
            <Card
              size="small"
              title="WebSocket"
              extra={
                pingWsResult && (
                  pingWsResult.success ? (
                    <CheckCircleOutlined style={{ color: '#52c41a' }} />
                  ) : (
                    <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                  )
                )
              }
            >
              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <Text type="secondary">Endpoint: /ws/health</Text>
                {pingWsResult && (
                  <Alert
                    message={
                      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                        <span>{pingWsResult.message}</span>
                        {pingWsResult.timestamp && (
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            Last ping: {pingWsResult.timestamp.toLocaleTimeString()}
                          </Text>
                        )}
                      </Space>
                    }
                    type={pingWsResult.success ? 'success' : 'error'}
                    showIcon
                    style={{ marginBottom: 8 }}
                  />
                )}
                <Button
                  type="primary"
                  icon={<SyncOutlined />}
                  onClick={handlePingWebSocket}
                  loading={pingWsLoading}
                  block
                >
                  Ping Now
                </Button>
                <Divider style={{ margin: '8px 0' }} />
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Space>
                    <Switch
                      checked={wsAutoPingEnabled}
                      onChange={setWsAutoPingEnabled}
                      size="small"
                    />
                    <Text type="secondary">Auto-ping</Text>
                  </Space>
                  {wsAutoPingEnabled && (
                    <InputNumber
                      min={1}
                      max={300}
                      value={wsPingInterval}
                      onChange={(val) => setWsPingInterval(val || 10)}
                      size="small"
                      style={{ width: 80 }}
                      addonAfter="sec"
                    />
                  )}
                </Space>
              </Space>
            </Card>
          </Col>

          <Col xs={24} md={8}>
            <Card
              size="small"
              title="Server-Sent Events"
              extra={
                pingSseResult && (
                  pingSseResult.success ? (
                    <CheckCircleOutlined style={{ color: '#52c41a' }} />
                  ) : (
                    <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                  )
                )
              }
            >
              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <Text type="secondary">Endpoint: /api/v1/sse/health</Text>
                {pingSseResult && (
                  <Alert
                    message={
                      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                        <span>{pingSseResult.message}</span>
                        {pingSseResult.timestamp && (
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            Last ping: {pingSseResult.timestamp.toLocaleTimeString()}
                          </Text>
                        )}
                      </Space>
                    }
                    type={pingSseResult.success ? 'success' : 'error'}
                    showIcon
                    style={{ marginBottom: 8 }}
                  />
                )}
                <Button
                  type="primary"
                  icon={<SyncOutlined />}
                  onClick={handlePingSSE}
                  loading={pingSseLoading}
                  block
                >
                  Ping Now
                </Button>
                <Divider style={{ margin: '8px 0' }} />
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Space>
                    <Switch
                      checked={sseAutoPingEnabled}
                      onChange={setSseAutoPingEnabled}
                      size="small"
                    />
                    <Text type="secondary">Auto-ping</Text>
                  </Space>
                  {sseAutoPingEnabled && (
                    <InputNumber
                      min={1}
                      max={300}
                      value={ssePingInterval}
                      onChange={(val) => setSSePingInterval(val || 10)}
                      size="small"
                      style={{ width: 80 }}
                      addonAfter="sec"
                    />
                  )}
                </Space>
              </Space>
            </Card>
          </Col>
        </Row>
      </Card>
    </div>
  )
}

export default Settings
