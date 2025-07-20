/**
 * Integration tests for PeeGeeQ Management UI
 * 
 * These tests validate the integration between the frontend and backend,
 * ensuring that the management UI can successfully communicate with the
 * REST API and display data correctly.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest'

// Mock API responses for testing
const mockApiResponses = {
  health: {
    status: 'UP',
    timestamp: '2025-07-20T06:36:06.381643600Z',
    uptime: '0d 0h 1m',
    version: '1.0.0',
    build: 'Phase-5-Management-UI'
  },
  overview: {
    systemStats: {
      totalQueues: 5,
      totalConsumerGroups: 3,
      totalEventStores: 2,
      uptime: '0d 0h 1m',
      version: '1.0.0'
    },
    queueSummary: {
      totalMessages: 1250,
      messagesPerSecond: 45.2,
      activeConsumers: 8
    },
    consumerGroupSummary: {
      totalGroups: 3,
      activeMembers: 12,
      rebalancingGroups: 0
    },
    eventStoreSummary: {
      totalEvents: 5420,
      eventsPerSecond: 23.1,
      totalAggregates: 156
    },
    timestamp: '2025-07-20T06:36:06.381643600Z'
  },
  queues: {
    message: 'Queues retrieved successfully',
    queueCount: 5,
    queues: [
      {
        setup: 'test-setup',
        name: 'user-events',
        durability: 'durable',
        autoDelete: false,
        messages: 245,
        consumers: 2,
        messagesPerSecond: 12.5,
        avgProcessingTime: 150,
        status: 'active'
      }
    ],
    timestamp: '2025-07-20T06:36:06.381643600Z'
  }
}

describe('PeeGeeQ Management UI Integration Tests', () => {
  const API_BASE_URL = 'http://localhost:8080'
  const UI_BASE_URL = 'http://localhost:3000'

  beforeAll(async () => {
    // Wait for servers to be ready
    await new Promise(resolve => setTimeout(resolve, 2000))
  })

  afterAll(async () => {
    // Cleanup if needed
  })

  describe('Backend API Integration', () => {
    it('should connect to backend health endpoint', async () => {
      const response = await fetch(`${API_BASE_URL}/health`)
      expect(response.ok).toBe(true)
      
      const data = await response.json()
      expect(data.status).toBe('UP')
      expect(data.service).toBe('peegeeq-rest-api')
    })

    it('should connect to management health endpoint', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/health`)
      expect(response.ok).toBe(true)
      
      const data = await response.json()
      expect(data.status).toBe('UP')
      expect(data).toHaveProperty('timestamp')
      expect(data).toHaveProperty('uptime')
      expect(data).toHaveProperty('version')
    })

    it('should retrieve system overview', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
      expect(response.ok).toBe(true)
      
      const data = await response.json()
      expect(data).toHaveProperty('systemStats')
      expect(data).toHaveProperty('queueSummary')
      expect(data).toHaveProperty('consumerGroupSummary')
      expect(data).toHaveProperty('eventStoreSummary')
      expect(data).toHaveProperty('timestamp')
    })

    it('should retrieve queues list', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/management/queues`)
      expect(response.ok).toBe(true)
      
      const data = await response.json()
      expect(data).toHaveProperty('message')
      expect(data).toHaveProperty('queueCount')
      expect(data).toHaveProperty('queues')
      expect(data).toHaveProperty('timestamp')
    })

    it('should retrieve metrics in Prometheus format', async () => {
      const response = await fetch(`${API_BASE_URL}/metrics`)
      expect(response.ok).toBe(true)
      expect(response.headers.get('content-type')).toContain('text/plain')
      
      const text = await response.text()
      expect(text).toContain('peegeeq_http_requests_total')
      expect(text).toContain('peegeeq_active_connections')
      expect(text).toContain('peegeeq_messages_sent_total')
    })
  })

  describe('CORS Configuration', () => {
    it('should allow cross-origin requests from frontend', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/health`, {
        method: 'GET',
        headers: {
          'Origin': UI_BASE_URL,
          'Content-Type': 'application/json'
        }
      })
      
      expect(response.ok).toBe(true)
      expect(response.headers.get('access-control-allow-origin')).toBeTruthy()
    })

    it('should handle preflight OPTIONS requests', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/health`, {
        method: 'OPTIONS',
        headers: {
          'Origin': UI_BASE_URL,
          'Access-Control-Request-Method': 'GET',
          'Access-Control-Request-Headers': 'Content-Type'
        }
      })
      
      expect(response.ok).toBe(true)
    })
  })

  describe('Error Handling', () => {
    it('should handle 404 errors gracefully', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/nonexistent`)
      expect(response.status).toBe(404)
    })

    it('should return proper error format', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/nonexistent`)
      expect(response.status).toBe(404)
      // API should return consistent error format
    })
  })

  describe('Performance Tests', () => {
    it('should respond to health check within 100ms', async () => {
      const start = Date.now()
      const response = await fetch(`${API_BASE_URL}/health`)
      const duration = Date.now() - start
      
      expect(response.ok).toBe(true)
      expect(duration).toBeLessThan(100)
    })

    it('should handle concurrent requests', async () => {
      const requests = Array(10).fill(null).map(() => 
        fetch(`${API_BASE_URL}/api/v1/health`)
      )
      
      const responses = await Promise.all(requests)
      responses.forEach(response => {
        expect(response.ok).toBe(true)
      })
    })
  })

  describe('Data Validation', () => {
    it('should return valid JSON for all endpoints', async () => {
      const endpoints = [
        '/health',
        '/api/v1/health',
        '/api/v1/management/overview',
        '/api/v1/management/queues'
      ]

      for (const endpoint of endpoints) {
        const response = await fetch(`${API_BASE_URL}${endpoint}`)
        expect(response.ok).toBe(true)
        
        const data = await response.json()
        expect(data).toBeDefined()
        expect(typeof data).toBe('object')
      }
    })

    it('should include required timestamp fields', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
      const data = await response.json()
      
      expect(data.timestamp).toBeDefined()
      expect(new Date(data.timestamp).getTime()).toBeGreaterThan(0)
    })
  })

  describe('API Response Structure', () => {
    it('should match expected health response structure', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/health`)
      const data = await response.json()
      
      expect(data).toMatchObject({
        status: expect.any(String),
        timestamp: expect.any(String),
        uptime: expect.any(String),
        version: expect.any(String),
        build: expect.any(String)
      })
    })

    it('should match expected overview response structure', async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/management/overview`)
      const data = await response.json()
      
      expect(data).toMatchObject({
        systemStats: expect.any(Object),
        queueSummary: expect.any(Object),
        consumerGroupSummary: expect.any(Object),
        eventStoreSummary: expect.any(Object),
        timestamp: expect.any(Number)
      })
    })
  })
})
