# PeeGeeQ Management UI - REST API Interfaces

This document lists all REST API endpoints that the PeeGeeQ Management UI currently calls. The UI is built with React, TypeScript, and uses RTK Query and Axios for API communication.

**Base URL**: `http://localhost:8080` (configurable via `VITE_API_URL` environment variable)

**API Prefix**: All management APIs use the `/api/v1` or `/api` prefix

---

## Table of Contents

1. [System & Health](#system--health)
2. [Queue Management](#queue-management)
3. [Queue Operations](#queue-operations)
4. [Message Operations](#message-operations)
5. [Consumer Group Management](#consumer-group-management)
6. [Event Store Management](#event-store-management)
7. [Real-Time Connections](#real-time-connections)

---

## System & Health

### Health Check
**Endpoint**: `GET /health`  
**Purpose**: Check if backend service is running  
**Used By**: `global-setup.ts` (test pre-flight check)  
**Response**: 200 OK if healthy

**Example**:
```typescript
fetch('http://localhost:8080/health')
```

---

### System Overview
**Endpoint**: `GET /api/v1/management/overview`  
**Purpose**: Get system statistics and overview data  
**Used By**: `Overview.tsx`, `managementStore.ts`  
**Response**:
```json
{
  "systemStats": {
    "totalQueues": 15,
    "totalConsumerGroups": 8,
    "totalEventStores": 3,
    "totalMessages": 12500,
    "messagesPerSecond": 45.2,
    "activeConnections": 12,
    "uptime": "5d 3h 22m"
  }
}
```

**Implementation Location**: `src/stores/managementStore.ts:102`, `src/pages/Overview.tsx:88`

---

## Queue Management

### List All Queues
**Endpoint**: `GET /api/v1/management/queues`  
**Purpose**: Retrieve list of all queues with statistics  
**Query Parameters**:
- `type` (optional): Filter by queue type (comma-separated)
- `status` (optional): Filter by status (comma-separated)
- `setupId` (optional): Filter by setup ID
- `search` (optional): Search query string
- `sortBy` (optional): Field to sort by
- `sortOrder` (optional): Sort order (asc/desc)
- `page` (optional): Page number
- `pageSize` (optional): Items per page

**Used By**: `queuesApi.ts`, `Queues.tsx`, `MessageBrowser.tsx`, `managementStore.ts`

**Response**:
```json
{
  "queues": [
    {
      "name": "orders-queue",
      "setup": "production",
      "setupId": "prod-01",
      "queueName": "orders-queue",
      "messages": 1250,
      "consumers": 5,
      "status": "active",
      "messageRate": 15.5
    }
  ],
  "total": 15,
  "page": 1,
  "pageSize": 20
}
```

**Implementation Locations**:
- `src/store/api/queuesApi.ts:27-52` (RTK Query)
- `src/stores/managementStore.ts:133`
- `src/pages/Queues.tsx:58`
- `src/pages/MessageBrowser.tsx:99`

---

### Get Queue Details
**Endpoint**: `GET /api/management/queues/:setupId/:queueName`  
**Purpose**: Get detailed information about a specific queue  
**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Used By**: `queuesApi.ts`, `QueueDetails.tsx`

**Response**:
```json
{
  "name": "orders-queue",
  "setupId": "prod-01",
  "status": "active",
  "messageCount": 1250,
  "consumerCount": 5,
  "config": {
    "maxLength": 10000,
    "durability": "durable",
    "autoDelete": false,
    "ttl": 3600000
  },
  "statistics": {
    "publishRate": 15.5,
    "consumeRate": 14.2,
    "avgLatency": 12.5
  }
}
```

**Implementation Location**: `src/store/api/queuesApi.ts:54-60`

---

### Create Queue
**Endpoint**: `POST /api/management/queues/:setupId/:queueName`  
**Purpose**: Create a new queue  
**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Request Body**:
```json
{
  "durability": "durable",
  "maxLength": 10000,
  "autoDelete": false,
  "ttl": 3600000,
  "description": "Queue for order processing"
}
```

**Alternative Endpoint**: `POST /api/v1/management/queues`  
**Request Body** (alternative format):
```json
{
  "name": "orders-queue",
  "setup": "production",
  "durability": "durable",
  "maxLength": 10000,
  "description": "Queue for order processing"
}
```

**Used By**: `queuesApi.ts`, `Queues.tsx`, `apiHelpers.ts` (test fixtures)

**Implementation Locations**:
- `src/store/api/queuesApi.ts:62-69` (RTK Query)
- `src/pages/Queues.tsx:131`
- `src/tests/fixtures/apiHelpers.ts:17`

---

### Update Queue Configuration
**Endpoint**: `PATCH /api/management/queues/:setupId/:queueName/config`  
**Purpose**: Update queue configuration  
**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Request Body** (partial updates supported):
```json
{
  "maxLength": 20000,
  "ttl": 7200000
}
```

**Alternative Endpoint**: `PUT /api/v1/management/queues/:key`  
**Used By**: `queuesApi.ts`, `Queues.tsx`

**Implementation Locations**:
- `src/store/api/queuesApi.ts:71-79`
- `src/pages/Queues.tsx:128`

---

### Delete Queue
**Endpoint**: `DELETE /api/management/queues/:setupId/:queueName`  
**Purpose**: Delete a queue  
**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Query Parameters**:
- `ifEmpty` (optional): Only delete if queue is empty
- `ifUnused` (optional): Only delete if no consumers

**Alternative Endpoint**: `DELETE /api/v1/management/queues/:queueName`  
**Alternative Endpoint**: `DELETE /api/v1/management/queues/:key`

**Used By**: `queuesApi.ts`, `Queues.tsx`, `apiHelpers.ts`

**Implementation Locations**:
- `src/store/api/queuesApi.ts:114-140` (within performQueueOperation)
- `src/pages/Queues.tsx:110`
- `src/tests/fixtures/apiHelpers.ts:39`

---

## Queue Operations

### Get Queue Consumers
**Endpoint**: `GET /api/v1/queues/:setupId/:queueName/consumers`  
**Purpose**: Get list of consumers connected to a queue  
**Used By**: `QueueDetails.tsx`

**Response**:
```json
{
  "consumers": [
    {
      "consumerId": "consumer-123",
      "connectionId": "conn-456",
      "subscribed": "2024-11-20T10:30:00Z",
      "messagesProcessed": 1523,
      "prefetchCount": 10
    }
  ]
}
```

**Implementation Location**: `src/pages/QueueDetails.tsx:131`

---

### Get Queue Bindings
**Endpoint**: `GET /api/v1/queues/:setupId/:queueName/bindings`  
**Purpose**: Get exchange bindings for a queue  
**Used By**: `QueueDetails.tsx`

**Response**:
```json
{
  "bindings": [
    {
      "exchange": "orders-exchange",
      "routingKey": "order.created",
      "arguments": {}
    }
  ]
}
```

**Implementation Location**: `src/pages/QueueDetails.tsx:150`

---

### Purge Queue
**Endpoint**: `POST /api/management/queues/:setupId/:queueName/purge`  
**Alternative**: `POST /api/v1/queues/:setupId/:queueName/purge`  
**Purpose**: Remove all messages from a queue  
**Used By**: `queuesApi.ts`, `QueueDetails.tsx`

**Implementation Locations**:
- `src/store/api/queuesApi.ts:118-121`
- `src/pages/QueueDetails.tsx:248`

---

### Pause Queue
**Endpoint**: `POST /api/management/queues/:setupId/:queueName/pause`  
**Purpose**: Pause message consumption from a queue  
**Used By**: `queuesApi.ts`

**Implementation Location**: `src/store/api/queuesApi.ts:130-133`

---

### Resume Queue
**Endpoint**: `POST /api/management/queues/:setupId/:queueName/resume`  
**Purpose**: Resume message consumption from a paused queue  
**Used By**: `queuesApi.ts`

**Implementation Location**: `src/store/api/queuesApi.ts:134-137`

---

## Message Operations

### Get Messages (Browse)
**Endpoint**: `GET /api/management/queues/:setupId/:queueName/messages`  
**Alternative**: `GET /api/v1/queues/:setupId/:queueName/messages`  
**Purpose**: Browse messages in a queue (non-destructive)  
**Query Parameters**:
- `count` (optional): Number of messages to retrieve
- `ackMode` (optional): Acknowledgment mode
- `offset` (optional): Offset for pagination
- `filter` (optional): Filter expression

**Used By**: `queuesApi.ts`, `QueueDetails.tsx`

**Response**:
```json
{
  "messages": [
    {
      "messageId": "msg-123",
      "payload": { "orderId": "ORD-001" },
      "headers": {
        "content-type": "application/json"
      },
      "timestamp": "2024-11-20T10:30:00Z",
      "state": "PENDING"
    }
  ],
  "total": 1250,
  "hasMore": true
}
```

**Implementation Locations**:
- `src/store/api/queuesApi.ts:89-101`
- `src/pages/QueueDetails.tsx:182`

---

### Publish Message
**Endpoint**: `POST /api/management/queues/:setupId/:queueName/publish`  
**Alternative**: `POST /api/v1/queues/:setupId/:queueName/publish`  
**Alternative**: `POST /api/v1/queues/:queue/messages` (test fixture format)  
**Purpose**: Publish a test message to a queue  

**Request Body**:
```json
{
  "payload": {
    "orderId": "ORD-001",
    "customerId": "CUST-123"
  },
  "headers": {
    "content-type": "application/json",
    "priority": "high"
  },
  "routingKey": "order.created"
}
```

**Used By**: `queuesApi.ts`, `QueueDetails.tsx`, `apiHelpers.ts`

**Response**:
```json
{
  "messageId": "msg-789"
}
```

**Implementation Locations**:
- `src/store/api/queuesApi.ts:103-112`
- `src/pages/QueueDetails.tsx:216`
- `src/tests/fixtures/apiHelpers.ts:87`

---

### Move Messages
**Endpoint**: `POST /api/management/queues/:setupId/:queueName/move`  
**Purpose**: Move messages between queues  

**Request Body**:
```json
{
  "targetSetupId": "prod-01",
  "targetQueueName": "orders-dlq",
  "count": 100,
  "filter": "state='FAILED'"
}
```

**Used By**: `queuesApi.ts`

**Response**:
```json
{
  "movedCount": 95
}
```

**Implementation Location**: `src/store/api/queuesApi.ts:156-172`

---

### Browse Messages (Alternative Endpoint)
**Endpoint**: `GET /api/v1/management/messages`  
**Purpose**: Browse messages across multiple queues  
**Query Parameters**:
- `queueName` (optional): Filter by queue name
- `setupId` (optional): Filter by setup ID
- `status` (optional): Filter by message status
- `limit` (optional): Maximum messages to return

**Used By**: `MessageBrowser.tsx`

**Implementation Location**: `src/pages/MessageBrowser.tsx:124`

---

## Consumer Group Management

### List Consumer Groups
**Endpoint**: `GET /api/v1/management/consumer-groups`  
**Purpose**: Get list of all consumer groups  
**Used By**: `ConsumerGroups.tsx`, `managementStore.ts`

**Response**:
```json
{
  "consumerGroups": [
    {
      "name": "order-processors",
      "groupName": "order-processors",
      "queueName": "orders-queue",
      "setup": "production",
      "members": 5,
      "status": "active",
      "lag": 125,
      "partition": 3,
      "lastRebalance": "2024-11-20T09:15:00Z",
      "createdAt": "2024-11-15T08:00:00Z"
    }
  ]
}
```

**Implementation Locations**:
- `src/stores/managementStore.ts:154`
- `src/pages/ConsumerGroups.tsx:93`

---

### Create Consumer Group
**Endpoint**: `POST /api/v1/management/consumer-groups`  
**Purpose**: Create a new consumer group  

**Request Body**:
```json
{
  "name": "order-processors",
  "queueName": "orders-queue",
  "maxMembers": 10,
  "rebalanceStrategy": "Range"
}
```

**Used By**: `apiHelpers.ts` (test fixtures)

**Implementation Location**: `src/tests/fixtures/apiHelpers.ts:60`

---

### Delete Consumer Group
**Endpoint**: `DELETE /api/v1/management/consumer-groups/:groupName`  
**Purpose**: Delete a consumer group  
**Path Parameters**:
- `groupName`: Consumer group name

**Alternative**: `DELETE /api/v1/management/consumer-groups/:groupId`

**Used By**: `apiHelpers.ts` (test fixtures)

**Implementation Location**: `src/tests/fixtures/apiHelpers.ts:81`

---

## Event Store Management

### List Event Stores
**Endpoint**: `GET /api/v1/management/event-stores`  
**Purpose**: Get list of all bitemporal event stores  
**Used By**: `EventStores.tsx`

**Response**:
```json
{
  "eventStores": [
    {
      "name": "orders-events",
      "setup": "production",
      "events": 15000,
      "createdAt": "2024-11-01T00:00:00Z",
      "lastEvent": "2024-11-20T10:30:00Z",
      "retention": "365d",
      "biTemporal": true,
      "corrections": 25
    }
  ]
}
```

**Implementation Location**: `src/pages/EventStores.tsx:110`

---

## Queue Chart Data

### Get Queue Chart Data
**Endpoint**: `GET /api/management/queues/:setupId/:queueName/charts`  
**Purpose**: Get time-series chart data for a queue  
**Query Parameters**:
- `timeRange`: Time range for data (e.g., '1h', '24h', '7d')

**Used By**: `queuesApi.ts`

**Response**:
```json
{
  "timeSeries": [
    {
      "timestamp": "2024-11-20T10:00:00Z",
      "messageCount": 1250,
      "publishRate": 15.5,
      "consumeRate": 14.2
    }
  ]
}
```

**Implementation Location**: `src/store/api/queuesApi.ts:174-180`

---

## Real-Time Connections

The UI also supports real-time updates via WebSocket and Server-Sent Events (SSE).

### WebSocket Endpoints

#### Message Stream
**Endpoint**: `ws://localhost:8080/ws/messages/:setupId/:queueName`  
**Purpose**: Real-time message stream for a specific queue  
**Used By**: `useRealTimeUpdates.ts`

**Message Format**:
```json
{
  "type": "message",
  "data": {
    "messageId": "msg-123",
    "payload": {},
    "timestamp": "2024-11-20T10:30:00Z"
  }
}
```

**Implementation Location**: `src/hooks/useRealTimeUpdates.ts:169`

---

#### System Monitoring
**Endpoint**: `ws://localhost:8080/ws/monitoring`  
**Purpose**: Real-time system statistics and alerts  
**Used By**: `useRealTimeUpdates.ts`

**Message Types**:
- `system_stats`: System statistics update
- `alert`: System alert notification
- `queue_update`: Queue status change

**Implementation Location**: `src/hooks/useRealTimeUpdates.ts:191`

---

### Server-Sent Events (SSE) Endpoints

#### System Metrics
**Endpoint**: `GET /sse/metrics`  
**Purpose**: Real-time system metrics stream  
**Used By**: `useRealTimeUpdates.ts`

**Implementation Location**: `src/hooks/useRealTimeUpdates.ts:128`

---

#### Queue Updates
**Endpoint**: `GET /sse/queues/:setupId`  
**Purpose**: Real-time queue updates for a setup  
**Used By**: `useRealTimeUpdates.ts`

**Data Format**:
```json
{
  "queues": [
    {
      "name": "orders-queue",
      "messages": 1250,
      "consumers": 5
    }
  ]
}
```

**Implementation Location**: `src/hooks/useRealTimeUpdates.ts:143`

---

## API Configuration

### Base URL Configuration
The API base URL is configurable via environment variable:

```typescript
const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'
```

**Configuration Locations**:
- RTK Query: `src/store/api/queuesApi.ts:20`
- Test Fixtures: `src/tests/fixtures/apiHelpers.ts:11`
- Global Setup: `src/tests/global-setup.ts:8`

### Default Values
- **Base URL**: `http://localhost:8080`
- **API Prefix**: `/api/v1` or `/api`
- **WebSocket Port**: `8080` (same as HTTP)

---

## Summary Statistics

**Total REST Endpoints**: 25+
- **Queue Management**: 9 endpoints
- **Message Operations**: 4 endpoints
- **Consumer Groups**: 3 endpoints
- **Event Stores**: 1 endpoint
- **System/Health**: 2 endpoints
- **Queue Operations**: 6 endpoints
- **Real-Time**: 4 endpoints (WebSocket/SSE)

**API Technologies Used**:
- **RTK Query** (Redux Toolkit Query): Primary API client for queue management
- **Axios**: Legacy API calls in page components
- **Fetch API**: Health checks and test utilities
- **WebSocket**: Real-time message streaming
- **EventSource**: Server-Sent Events for metrics

**Authentication**: Currently none (local development setup)

---

## Implementation Notes

### API Client Libraries
1. **RTK Query** (`@reduxjs/toolkit/query`):
   - Located in `src/store/api/queuesApi.ts`
   - Provides caching, automatic refetching, and optimistic updates
   - Primary API for queue management

2. **Axios**:
   - Used in page components (`src/pages/*.tsx`)
   - Legacy implementation being migrated to RTK Query
   - Used in Zustand store (`src/stores/managementStore.ts`)

3. **Playwright APIRequestContext**:
   - Used in test fixtures (`src/tests/fixtures/apiHelpers.ts`)
   - Provides request handling during e2e tests

### Migration Status
The UI is currently in a **transition phase**:
- New queue management features use **RTK Query**
- Legacy pages still use **Axios** directly
- Gradual migration to RTK Query is ongoing

### Known Issues
1. Some endpoints have **multiple formats** (e.g., queue creation has 2 different request formats)
2. **Inconsistent path parameters** (some use `:key`, others use `:setupId/:queueName`)
3. **Mock data** in some responses (event stores, consumer group members)
4. **WebSocket/SSE endpoints** not fully implemented on backend

---

## Test Data Requirements

For e2e tests to pass, the backend needs to provide data for:

### Required Test Data
1. **Queues**: 3-5 test queues with names starting with `test-queue-`
2. **Consumer Groups**: 2-3 groups associated with test queues
3. **Messages**: Sample messages in test queues for message browser
4. **Event Stores**: 1-2 event stores (if implemented)

### Test Fixture Endpoints Used
- `POST /api/v1/management/queues` - Create test queues
- `DELETE /api/v1/management/queues/:queueName` - Cleanup test queues
- `POST /api/v1/management/consumer-groups` - Create test groups
- `DELETE /api/v1/management/consumer-groups/:groupName` - Cleanup test groups
- `POST /api/v1/queues/:queue/messages` - Publish test messages

See `src/tests/fixtures/testData.ts` for test data fixtures.

---

**Document Version**: 1.0  
**Last Updated**: November 21, 2025  
**Maintained By**: PeeGeeQ Development Team
