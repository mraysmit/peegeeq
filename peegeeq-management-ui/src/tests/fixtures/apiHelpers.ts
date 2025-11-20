/**
 * API Helper Functions for E2E Tests
 * 
 * Provides utility functions for interacting with the PeeGeeQ REST API
 * during E2E tests to set up and tear down test data.
 */

import { APIRequestContext } from '@playwright/test'
import { QueueFixture, ConsumerGroupFixture, MessageFixture } from './testData'

const API_BASE_URL = 'http://localhost:8080'

/**
 * Create a queue via API
 */
export async function createQueue(request: APIRequestContext, queue: QueueFixture): Promise<any> {
  const response = await request.post(`${API_BASE_URL}/api/v1/management/queues`, {
    data: {
      name: queue.name,
      setup: queue.setup.toLowerCase(),
      durability: queue.durability.toLowerCase(),
      maxLength: queue.maxLength,
      description: queue.description
    }
  })
  
  if (!response.ok()) {
    console.error(`Failed to create queue ${queue.name}: ${response.status()}`)
    return null
  }
  
  return await response.json()
}

/**
 * Delete a queue via API
 */
export async function deleteQueue(request: APIRequestContext, queueName: string): Promise<boolean> {
  const response = await request.delete(`${API_BASE_URL}/api/v1/management/queues/${queueName}`)
  return response.ok()
}

/**
 * Get all queues via API
 */
export async function getQueues(request: APIRequestContext): Promise<any[]> {
  const response = await request.get(`${API_BASE_URL}/api/v1/management/queues`)
  
  if (!response.ok()) {
    return []
  }
  
  return await response.json()
}

/**
 * Create a consumer group via API
 */
export async function createConsumerGroup(request: APIRequestContext, group: ConsumerGroupFixture): Promise<any> {
  const response = await request.post(`${API_BASE_URL}/api/v1/management/consumer-groups`, {
    data: {
      name: group.name,
      queueName: group.queueName,
      maxMembers: group.maxMembers,
      rebalanceStrategy: group.rebalanceStrategy
    }
  })
  
  if (!response.ok()) {
    console.error(`Failed to create consumer group ${group.name}: ${response.status()}`)
    return null
  }
  
  return await response.json()
}

/**
 * Delete a consumer group via API
 */
export async function deleteConsumerGroup(request: APIRequestContext, groupName: string): Promise<boolean> {
  const response = await request.delete(`${API_BASE_URL}/api/v1/management/consumer-groups/${groupName}`)
  return response.ok()
}

/**
 * Publish a message via API
 */
export async function publishMessage(request: APIRequestContext, message: MessageFixture): Promise<any> {
  const response = await request.post(`${API_BASE_URL}/api/v1/queues/${message.queue}/messages`, {
    data: message.payload,
    headers: message.headers || {}
  })
  
  if (!response.ok()) {
    console.error(`Failed to publish message to ${message.queue}: ${response.status()}`)
    return null
  }
  
  return await response.json()
}

/**
 * Check if backend is healthy
 */
export async function checkHealth(request: APIRequestContext): Promise<boolean> {
  try {
    const response = await request.get(`${API_BASE_URL}/health`)
    return response.ok()
  } catch (error) {
    return false
  }
}

/**
 * Wait for queue to be created and ready
 */
export async function waitForQueue(request: APIRequestContext, queueName: string, timeoutMs: number = 5000): Promise<boolean> {
  const startTime = Date.now()
  
  while (Date.now() - startTime < timeoutMs) {
    const queues = await getQueues(request)
    if (queues.some((q: any) => q.name === queueName)) {
      return true
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  
  return false
}

/**
 * Clean up all test queues (queues starting with 'test-')
 */
export async function cleanupTestQueues(request: APIRequestContext): Promise<void> {
  const queues = await getQueues(request)
  const testQueues = queues.filter((q: any) => q.name.startsWith('test-'))
  
  for (const queue of testQueues) {
    await deleteQueue(request, queue.name)
  }
}
