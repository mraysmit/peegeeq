/**
 * Test Data Fixtures for PeeGeeQ Management UI E2E Tests
 * 
 * Provides consistent test data for creating queues, consumer groups,
 * and other entities during testing.
 */

export interface QueueFixture {
  name: string
  setup: string
  durability: string
  maxLength?: number
  description?: string
}

export interface ConsumerGroupFixture {
  name: string
  queueName: string
  maxMembers?: number
  rebalanceStrategy?: string
}

export interface MessageFixture {
  queue: string
  payload: any
  headers?: Record<string, string>
}

/**
 * Queue test fixtures
 */
export const queueFixtures: QueueFixture[] = [
  {
    name: 'test-queue-orders',
    setup: 'Production',
    durability: 'Durable',
    maxLength: 10000,
    description: 'Test queue for order processing'
  },
  {
    name: 'test-queue-payments',
    setup: 'Production',
    durability: 'Durable',
    maxLength: 5000,
    description: 'Test queue for payment processing'
  },
  {
    name: 'test-queue-notifications',
    setup: 'Development',
    durability: 'Non-Durable',
    description: 'Test queue for notifications'
  }
]

/**
 * Consumer group test fixtures
 */
export const consumerGroupFixtures: ConsumerGroupFixture[] = [
  {
    name: 'test-cg-order-processors',
    queueName: 'test-queue-orders',
    maxMembers: 5,
    rebalanceStrategy: 'Range'
  },
  {
    name: 'test-cg-payment-processors',
    queueName: 'test-queue-payments',
    maxMembers: 3,
    rebalanceStrategy: 'RoundRobin'
  }
]

/**
 * Message test fixtures
 */
export const messageFixtures: MessageFixture[] = [
  {
    queue: 'test-queue-orders',
    payload: {
      orderId: 'ORD-001',
      customerId: 'CUST-123',
      items: ['item1', 'item2'],
      total: 99.99
    },
    headers: {
      'content-type': 'application/json',
      'message-type': 'order.created'
    }
  },
  {
    queue: 'test-queue-payments',
    payload: {
      paymentId: 'PAY-001',
      orderId: 'ORD-001',
      amount: 99.99,
      method: 'credit_card'
    },
    headers: {
      'content-type': 'application/json',
      'message-type': 'payment.processed'
    }
  }
]

/**
 * Helper function to generate unique test names
 */
export function generateTestName(prefix: string): string {
  const timestamp = Date.now()
  const random = Math.floor(Math.random() * 1000)
  return `${prefix}-${timestamp}-${random}`
}

/**
 * Helper function to get a random fixture
 */
export function getRandomQueue(): QueueFixture {
  return queueFixtures[Math.floor(Math.random() * queueFixtures.length)]
}

export function getRandomConsumerGroup(): ConsumerGroupFixture {
  return consumerGroupFixtures[Math.floor(Math.random() * consumerGroupFixtures.length)]
}

/**
 * Clean up test data by name prefix
 */
export async function cleanupTestData(apiBaseUrl: string, prefix: string = 'test-') {
  // This would call DELETE endpoints for test data
  // Implementation depends on API capabilities
  console.log(`Cleanup test data with prefix: ${prefix} from ${apiBaseUrl}`)
}
