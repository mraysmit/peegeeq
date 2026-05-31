import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Form,
  Input,
  InputNumber,
  Select,
  Button,
  Space,
  Alert,
  Collapse,
  Checkbox,
  Typography,
  Card,
} from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { createQueue } from '../services/queueService'
import { DEFAULT_QUEUE_CONFIG } from '../types/queue'

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

export default function CreateQueuePage() {
  const navigate = useNavigate()
  const { setupId = '' } = useParams<{ setupId: string }>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm()

  async function handleCreate() {
    try {
      const values = await form.validateFields()
      setLoading(true)
      setError(null)
      await createQueue(setupId, {
        queueName: values.queueName,
        implementationType: values.implementationType,
        maxRetries: values.maxRetries ?? DEFAULT_QUEUE_CONFIG.maxRetries,
        visibilityTimeoutSeconds:
          values.visibilityTimeoutSeconds ?? DEFAULT_QUEUE_CONFIG.visibilityTimeoutSeconds,
        batchSize: values.batchSize ?? DEFAULT_QUEUE_CONFIG.batchSize,
        deadLetterEnabled: values.deadLetterEnabled ?? DEFAULT_QUEUE_CONFIG.deadLetterEnabled,
        fifoEnabled: values.fifoEnabled ?? DEFAULT_QUEUE_CONFIG.fifoEnabled,
      })
      navigate(`/setups/${setupId}`)
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return
      setError(extractErrorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  function handleCancel() {
    navigate(`/setups/${setupId}`)
  }

  const advancedItems = [
    {
      key: 'advanced',
      label: 'Advanced settings',
      children: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item name="maxRetries" label="Max retries" style={{ flex: 1, marginBottom: 0 }}>
              <InputNumber min={0} max={100} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="visibilityTimeoutSeconds"
              label="Visibility timeout (s)"
              style={{ flex: 1, marginBottom: 0 }}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Form.Item name="batchSize" label="Batch size" style={{ marginBottom: 0 }}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="deadLetterEnabled" valuePropName="checked" style={{ marginBottom: 0 }}>
            <Checkbox>Enable dead-letter queue</Checkbox>
          </Form.Item>
          <Form.Item name="fifoEnabled" valuePropName="checked" style={{ marginBottom: 0 }}>
            <Checkbox>FIFO ordering</Checkbox>
          </Form.Item>
        </Space>
      ),
    },
  ]

  return (
    <div data-testid="create-queue-page" style={{ maxWidth: 520 }}>
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

      <Title level={3}>Create Queue in {setupId}</Title>

      <Card>
        {error && (
          <Alert
            type="error"
            message={error}
            style={{ marginBottom: 16 }}
            showIcon
            closable
            onClose={() => setError(null)}
            data-testid="create-queue-error"
          />
        )}

        <Form
          form={form}
          layout="vertical"
          initialValues={{
            implementationType: 'native',
            maxRetries: DEFAULT_QUEUE_CONFIG.maxRetries,
            visibilityTimeoutSeconds: DEFAULT_QUEUE_CONFIG.visibilityTimeoutSeconds,
            batchSize: DEFAULT_QUEUE_CONFIG.batchSize,
            deadLetterEnabled: DEFAULT_QUEUE_CONFIG.deadLetterEnabled,
            fifoEnabled: DEFAULT_QUEUE_CONFIG.fifoEnabled,
          }}
        >
          <Form.Item
            name="queueName"
            label="Queue name"
            rules={[{ required: true, message: 'Please enter a queue name' }]}
            extra="Unique within this setup, e.g. orders, payments"
          >
            <Input placeholder="my-queue" autoFocus />
          </Form.Item>

          <Form.Item
            name="implementationType"
            label="Implementation type"
            rules={[{ required: true, message: 'Please select an implementation type' }]}
            extra="native = LISTEN/NOTIFY low-latency; outbox = transactional polling"
          >
            <Select
              data-testid="implementation-type-select"
              options={[
                { value: 'native', label: 'native' },
                { value: 'outbox', label: 'outbox' },
              ]}
            />
          </Form.Item>

          <Collapse ghost items={advancedItems} style={{ marginBottom: 16 }} />

          <Space>
            <Button onClick={handleCancel} disabled={loading}>
              Cancel
            </Button>
            <Button
              type="primary"
              onClick={handleCreate}
              loading={loading}
              data-testid="create-queue-button"
            >
              Create queue
            </Button>
          </Space>
        </Form>
      </Card>
    </div>
  )
}
