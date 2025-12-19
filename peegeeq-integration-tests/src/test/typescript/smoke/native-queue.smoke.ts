/**
 * Native Queue E2E Smoke Tests (TypeScript)
 * 
 * Verifies complete message flow through native queue pattern from TypeScript client.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';

const API_BASE_URL = process.env.PEEGEEQ_API_URL || 'http://localhost:8080';

interface SendMessageResponse {
  message: string;
  messageId: string;
  queueName: string;
  setupId: string;
  priority: number;
  correlationId?: string;
  messageGroup?: string;
}

interface SetupRequest {
  setupId: string;
  databaseConfig: {
    host: string;
    port: number;
    databaseName: string;
    username: string;
    password: string;
    schema: string;
    templateDatabase: string;
    encoding: string;
  };
  queues: Array<{
    queueName: string;
    maxRetries: number;
    visibilityTimeoutSeconds: number;
  }>;
  eventStores: Array<unknown>;
}

describe('Native Queue Smoke Tests (TypeScript)', () => {
  let setupId: string;
  const queueName = 'ts-smoke-test-queue';

  beforeAll(async () => {
    setupId = `ts-smoke-${Date.now()}`;

    const setupRequest: SetupRequest = {
      setupId,
      databaseConfig: {
        host: process.env.PG_HOST || 'localhost',
        port: parseInt(process.env.PG_PORT || '5432'),
        databaseName: `ts_smoke_db_${Date.now()}`,
        username: process.env.PG_USER || 'postgres',
        password: process.env.PG_PASSWORD || 'postgres',
        schema: 'public',
        templateDatabase: 'template0',
        encoding: 'UTF8'
      },
      queues: [
        {
          queueName,
          maxRetries: 3,
          visibilityTimeoutSeconds: 30
        }
      ],
      eventStores: []
    };

    const response = await fetch(`${API_BASE_URL}/api/v1/database-setup/create`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(setupRequest)
    });

    expect(response.ok).toBe(true);
    console.log(`Setup created: ${setupId}`);
  }, 60000);

  afterAll(async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, {
      method: 'DELETE'
    });

    if (response.ok) {
      console.log(`Setup deleted: ${setupId}`);
    }
  }, 30000);

  it('should send message and receive confirmation', async () => {
    const messagePayload = {
      payload: {
        orderId: 'TS-ORDER-12345',
        customerId: 'TS-CUST-67890',
        amount: 99.99,
        timestamp: Date.now()
      },
      priority: 5,
      headers: {
        source: 'ts-smoke-test',
        version: '1.0'
      }
    };

    const response = await fetch(
      `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/messages`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(messagePayload)
      }
    );

    expect(response.ok).toBe(true);

    const result: SendMessageResponse = await response.json();

    expect(result.messageId).toBeDefined();
    expect(result.queueName).toBe(queueName);
    expect(result.setupId).toBe(setupId);
    expect(result.priority).toBe(5);

    console.log(`Message sent: ${result.messageId}`);
  });

  it('should propagate correlation ID through all layers', async () => {
    const correlationId = `ts-corr-${Date.now()}`;

    const messagePayload = {
      payload: { test: 'ts-correlation-test' },
      correlationId
    };

    const response = await fetch(
      `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/messages`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(messagePayload)
      }
    );

    expect(response.ok).toBe(true);

    const result: SendMessageResponse = await response.json();

    expect(result.correlationId).toBe(correlationId);
    console.log(`Correlation ID propagated: ${correlationId}`);
  });

  it('should propagate message group through all layers', async () => {
    const messageGroup = `ts-group-${Date.now()}`;

    const messagePayload = {
      payload: { test: 'ts-message-group-test' },
      messageGroup
    };

    const response = await fetch(
      `${API_BASE_URL}/api/v1/queues/${setupId}/${queueName}/messages`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(messagePayload)
      }
    );

    expect(response.ok).toBe(true);

    const result: SendMessageResponse = await response.json();

    expect(result.messageGroup).toBe(messageGroup);
    console.log(`Message group propagated: ${messageGroup}`);
  });
});

