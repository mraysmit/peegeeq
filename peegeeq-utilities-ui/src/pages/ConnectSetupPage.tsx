import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Form,
  Input,
  InputNumber,
  Button,
  Space,
  Alert,
  Collapse,
  Typography,
  Card,
  Checkbox,
} from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { connectExisting } from '../services/setupService'
import { DEFAULT_DATABASE_CONFIG } from '../types/setup'

const { Title } = Typography

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const anyErr = err as Record<string, unknown>
    const responseData = (anyErr.response as Record<string, unknown> | undefined)?.data as
      | Record<string, unknown>
      | undefined
    if (typeof responseData?.error === 'string') return responseData.error
    if (typeof (anyErr as { message?: unknown }).message === 'string')
      return (anyErr as { message: string }).message
  }
  return 'An unexpected error occurred'
}

export default function ConnectSetupPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm()

  async function handleConnect() {
    try {
      const values = await form.validateFields()
      setLoading(true)
      setError(null)
      await connectExisting({
        setupId: values.setupId,
        databaseConfig: {
          ...DEFAULT_DATABASE_CONFIG,
          host: values.host ?? DEFAULT_DATABASE_CONFIG.host,
          port: values.port ?? DEFAULT_DATABASE_CONFIG.port,
          username: values.username ?? DEFAULT_DATABASE_CONFIG.username,
          schema: values.schema ?? DEFAULT_DATABASE_CONFIG.schema,
          sslEnabled: values.sslEnabled ?? false,
          databaseName: values.databaseName,
          password: values.password,
        },
        // Ignored on connect — the backend reconstitutes queues/event stores from the schema.
        queues: [],
        eventStores: [],
      })
      navigate('/setups')
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return
      setError(extractErrorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  function handleCancel() {
    navigate(-1)
  }

  const connectionDetailsItems = [
    {
      key: 'connection',
      label: 'Connection details',
      children: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item name="host" label="Host" style={{ flex: 1, marginBottom: 0 }}>
              <Input placeholder="localhost" />
            </Form.Item>
            <Form.Item name="port" label="Port" style={{ flex: 1, marginBottom: 0 }}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item
              name="username"
              label="Username"
              rules={[{ required: true, message: 'Please enter a username' }]}
              style={{ flex: 1, marginBottom: 0 }}
            >
              <Input />
            </Form.Item>
            <Form.Item name="schema" label="Schema" style={{ flex: 1, marginBottom: 0 }}>
              <Input />
            </Form.Item>
          </Space.Compact>
          <Form.Item name="sslEnabled" valuePropName="checked" style={{ marginBottom: 0 }}>
            <Checkbox>Enable SSL</Checkbox>
          </Form.Item>
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="connect-setup-page" style={{ maxWidth: 520 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={handleCancel}
          disabled={loading}
          data-testid="back-button"
        >
          Back
        </Button>
      </Space>

      <Title level={3}>Connect to Existing Setup</Title>

      <Card>
        <Alert
          message="Non-destructive connect"
          description="This attaches to an EXISTING PeeGeeQ setup. It will not create or modify any database — it connects to the existing schema and reconstitutes its queues and event stores. The password is used to connect and is not stored."
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />

        {error && (
          <Alert
            type="error"
            message={error}
            style={{ marginBottom: 16 }}
            showIcon
            closable
            onClose={() => setError(null)}
          />
        )}

        <Form
          form={form}
          layout="vertical"
          initialValues={{
            host: DEFAULT_DATABASE_CONFIG.host,
            port: DEFAULT_DATABASE_CONFIG.port,
            username: DEFAULT_DATABASE_CONFIG.username,
            schema: DEFAULT_DATABASE_CONFIG.schema,
            sslEnabled: false,
          }}
        >
          <Form.Item
            name="setupId"
            label="Setup ID"
            rules={[{ required: true, message: 'Please enter the setup ID' }]}
            extra="The identifier of the existing setup to connect to"
          >
            <Input placeholder="my-existing-setup" autoFocus />
          </Form.Item>

          <Form.Item
            name="databaseName"
            label="Database name"
            rules={[{ required: true, message: 'Please enter the database name' }]}
            extra="The existing PostgreSQL database this setup lives in"
          >
            <Input placeholder="peegeeq_dev" />
          </Form.Item>

          <Form.Item
            name="password"
            label="Database password"
            rules={[{ required: true, message: 'Please enter the database password' }]}
          >
            <Input.Password />
          </Form.Item>

          <Collapse ghost items={connectionDetailsItems} style={{ marginBottom: 16 }} />

          <Space>
            <Button onClick={handleCancel} disabled={loading}>
              Cancel
            </Button>
            <Button type="primary" onClick={handleConnect} loading={loading} data-testid="connect-button">
              Connect
            </Button>
          </Space>
        </Form>
      </Card>
    </div>
  )
}
