import { test, expect, Page } from '@playwright/test'

/**
 * Full System Integration Test
 * 
 * This test suite validates that the management UI correctly displays data
 * when the system is running with real activity including:
 * - Database setup creation
 * - Queue creation and message production
 * - Consumer group activity
 * - Real-time data updates
 */

const API_BASE_URL = 'http://localhost:8080'
const UI_BASE_URL = 'http://localhost:3000'

// Test data for creating a complete system setup
const TEST_SETUP = {
  setupId: 'ui-test-setup',
  databaseConfig: {
    host: 'localhost',
    port: 5432,
    username: 'postgres',
    password: 'postgres',
    databaseName: 'peegeeq_ui_test'
  },
  queues: [
    {
      queueName: 'orders',
      implementationType: 'native',
      configuration: {
        maxRetries: 3,
        visibilityTimeoutSeconds: 30
      },
      consumerGroups: [
        { groupName: 'order-processors', maxConcurrency: 2 }
      ]
    },
    {
      queueName: 'payments',
      implementationType: 'native',
      configuration: {
        maxRetries: 5,
        visibilityTimeoutSeconds: 60
      },
      consumerGroups: [
        { groupName: 'payment-handlers', maxConcurrency: 1 }
      ]
    },
    {
      queueName: 'notifications',
      implementationType: 'native',
      configuration: {
        maxRetries: 2,
        visibilityTimeoutSeconds: 15
      },
      consumerGroups: [
        { groupName: 'notification-senders', maxConcurrency: 3 }
      ]
    }
  ],
  eventStores: [
    {
      eventStoreName: 'user-events',
      configuration: {
        retentionDays: 30,
        partitionStrategy: 'daily'
      }
    }
  ]
}

// Sample messages to send to queues
const SAMPLE_MESSAGES = [
  {
    queue: 'orders',
    messages: [
      {
        payload: { orderId: 'ORD-001', customerId: 'CUST-123', amount: 99.99 },
        headers: { source: 'web-app', priority: 'high' },
        messageType: 'OrderCreated'
      },
      {
        payload: { orderId: 'ORD-002', customerId: 'CUST-456', amount: 149.99 },
        headers: { source: 'mobile-app', priority: 'normal' },
        messageType: 'OrderCreated'
      }
    ]
  },
  {
    queue: 'payments',
    messages: [
      {
        payload: { paymentId: 'PAY-001', orderId: 'ORD-001', amount: 99.99 },
        headers: { source: 'payment-service', priority: 'high' },
        messageType: 'PaymentRequested'
      }
    ]
  },
  {
    queue: 'notifications',
    messages: [
      {
        payload: { notificationId: 'NOT-001', customerId: 'CUST-123', type: 'order_confirmation' },
        headers: { source: 'notification-service', priority: 'normal' },
        messageType: 'NotificationRequested'
      },
      {
        payload: { notificationId: 'NOT-002', customerId: 'CUST-456', type: 'payment_confirmation' },
        headers: { source: 'notification-service', priority: 'normal' },
        messageType: 'NotificationRequested'
      }
    ]
  }
]

test.describe('Full System Integration Tests', () => {
  
  test.beforeAll(async () => {
    console.log('🚀 Starting full system integration test...')
    
    // Verify backend is running
    try {
      const response = await fetch(`${API_BASE_URL}/health`)
      if (!response.ok) {
        throw new Error(`Backend not available: ${response.status}`)
      }
      console.log('✅ Backend is running and healthy')
    } catch (error) {
      throw new Error(`Backend is not running. Please start the REST server on port 8080. Error: ${error}`)
    }
  })

  test('should display real system data in management UI', async ({ page }) => {
    console.log('📊 Testing full system data flow...')
    
    // Step 1: Create database setup with queues and consumer groups
    console.log('  🔧 Creating database setup...')
    const setupResponse = await fetch(`${API_BASE_URL}/api/v1/database-setup/create`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(TEST_SETUP)
    })
    
    expect(setupResponse.ok).toBe(true)
    const setupResult = await setupResponse.json()
    console.log(`  ✅ Database setup created: ${setupResult.setupId}`)
    
    // Step 2: Send messages to all queues
    console.log('  📨 Sending test messages...')
    let totalMessagesSent = 0
    
    for (const queueData of SAMPLE_MESSAGES) {
      for (const message of queueData.messages) {
        const messageResponse = await fetch(
          `${API_BASE_URL}/api/v1/queues/${TEST_SETUP.setupId}/${queueData.queue}/messages`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(message)
          }
        )
        
        expect(messageResponse.ok).toBe(true)
        totalMessagesSent++
      }
    }
    
    console.log(`  ✅ Sent ${totalMessagesSent} test messages`)
    
    // Step 3: Wait a moment for data to propagate
    await page.waitForTimeout(2000)
    
    // Step 4: Navigate to management UI and verify data display
    console.log('  🖥️  Testing UI data display...')
    await page.goto(UI_BASE_URL)
    await page.waitForLoadState('networkidle')
    
    // Wait for data to load
    await page.waitForTimeout(3000)
    
    // Test Overview Page
    await testOverviewPage(page, totalMessagesSent)
    
    // Test Queues Page
    await testQueuesPage(page)
    
    // Test Consumer Groups Page
    await testConsumerGroupsPage(page)
    
    // Test Event Stores Page (if implemented)
    await testEventStoresPage(page)
    
    console.log('  ✅ All UI data validation tests passed!')
    
    // Step 5: Cleanup - destroy the test setup
    console.log('  🧹 Cleaning up test setup...')
    const cleanupResponse = await fetch(`${API_BASE_URL}/api/v1/database-setup/${TEST_SETUP.setupId}`, {
      method: 'DELETE'
    })
    
    if (cleanupResponse.ok) {
      console.log('  ✅ Test setup cleaned up successfully')
    } else {
      console.log('  ⚠️  Cleanup failed - manual cleanup may be required')
    }
  })
})

async function testOverviewPage(page: Page, expectedMessages: number) {
  console.log('    📈 Testing Overview page...')
  
  // Check that statistics cards are visible and contain data
  const statisticsCards = page.locator('.ant-statistic')
  await expect(statisticsCards).toHaveCount(4)
  
  // Check for specific statistics
  await expect(page.locator('text=Total Queues')).toBeVisible()
  await expect(page.locator('text=Total Consumer Groups')).toBeVisible()
  
  // Verify queue count shows our test queues
  const queueCountElement = page.locator('.ant-statistic-content').filter({ hasText: /^[0-9]+$/ }).first()
  const queueCount = await queueCountElement.textContent()
  expect(parseInt(queueCount || '0')).toBeGreaterThanOrEqual(3) // At least our 3 test queues
  
  console.log('    ✅ Overview page validation passed')
}

async function testQueuesPage(page: Page) {
  console.log('    📋 Testing Queues page...')
  
  // Navigate to Queues page
  await page.click('text=Queues')
  await expect(page.locator('h1:has-text("Queue Management")')).toBeVisible()
  
  // Wait for queue data to load
  await page.waitForTimeout(2000)
  
  // Check that queue table is visible and contains our test queues
  await expect(page.locator('.ant-table')).toBeVisible()
  
  // Look for our test queue names
  await expect(page.locator('text=orders')).toBeVisible()
  await expect(page.locator('text=payments')).toBeVisible()
  await expect(page.locator('text=notifications')).toBeVisible()
  
  console.log('    ✅ Queues page validation passed')
}

async function testConsumerGroupsPage(page: Page) {
  console.log('    👥 Testing Consumer Groups page...')
  
  // Navigate to Consumer Groups page
  await page.click('text=Consumer Groups')
  await expect(page.locator('h1:has-text("Consumer Groups")')).toBeVisible()
  
  // Wait for consumer group data to load
  await page.waitForTimeout(2000)
  
  // Check that consumer groups table is visible
  await expect(page.locator('.ant-table')).toBeVisible()
  
  // Look for our test consumer group names
  await expect(page.locator('text=order-processors')).toBeVisible()
  await expect(page.locator('text=payment-handlers')).toBeVisible()
  await expect(page.locator('text=notification-senders')).toBeVisible()
  
  console.log('    ✅ Consumer Groups page validation passed')
}

async function testEventStoresPage(page: Page) {
  console.log('    📚 Testing Event Stores page...')
  
  // Navigate to Event Stores page
  await page.click('text=Event Stores')
  await expect(page.locator('h1:has-text("Event Stores")')).toBeVisible()
  
  // Wait for event store data to load
  await page.waitForTimeout(2000)
  
  // Check that event stores interface is visible
  await expect(page.locator('.ant-table')).toBeVisible()
  
  console.log('    ✅ Event Stores page validation passed')
}
