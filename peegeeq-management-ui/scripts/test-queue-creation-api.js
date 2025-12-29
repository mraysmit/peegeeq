#!/usr/bin/env node

/**
 * Test script to verify queue creation API
 */

const API_BASE_URL = 'http://localhost:8080'

async function testQueueCreation() {
  console.log('\n🧪 Testing Queue Creation API...\n')

  // Check if "default" setup exists
  console.log('1. Checking for "default" setup...')
  const setupsResponse = await fetch(`${API_BASE_URL}/api/v1/setups`)
  const setupsData = await setupsResponse.json()

  if (!setupsData.setupIds || !setupsData.setupIds.includes('default')) {
    console.error('❌ "default" setup not found. Please run the UI tests first to create it.')
    console.error('   Available setups:', setupsData.setupIds)
    return
  }

  console.log('✅ "default" setup exists\n')

  // Now test queue creation with CORRECT field name
  console.log('2. Creating queue with CORRECT field name (setup)...')
  const queueResponse1 = await fetch(`${API_BASE_URL}/api/v1/management/queues`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      setup: 'default',  // CORRECT field name
      name: 'test-queue-1',
      type: 'native'
    })
  })

  if (queueResponse1.ok) {
    const result = await queueResponse1.json()
    console.log('✅ Queue created successfully with "setup" field')
    console.log('   Response:', JSON.stringify(result, null, 2))
  } else {
    const error = await queueResponse1.text()
    console.error('❌ Failed with "setup" field:', error)
  }

  console.log('\n3. Creating queue with WRONG field name (setupId)...')
  const queueResponse2 = await fetch(`${API_BASE_URL}/api/v1/management/queues`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      setupId: 'default',  // WRONG field name
      name: 'test-queue-2',
      type: 'native'
    })
  })

  if (queueResponse2.ok) {
    const result = await queueResponse2.json()
    console.log('✅ Queue created successfully with "setupId" field')
    console.log('   Response:', JSON.stringify(result, null, 2))
  } else {
    const error = await queueResponse2.text()
    console.error('❌ Failed with "setupId" field:', error)
  }

  // Cleanup
  console.log('\n4. Cleaning up...')
  await fetch(`${API_BASE_URL}/api/v1/setups/default/queues/test-queue-1`, { method: 'DELETE' })
  await fetch(`${API_BASE_URL}/api/v1/setups/default/queues/test-queue-2`, { method: 'DELETE' })
  console.log('✅ Cleanup complete\n')
}

testQueueCreation().catch(console.error)

