# PeeGeeQ Management UI - API Reference

**REST API Documentation**  
*Last Updated: 2025-12-23*

---

## Table of Contents

1. [Overview](#overview)
2. [Base Configuration](#base-configuration)
3. [Authentication](#authentication)
4. [System & Health APIs](#system--health-apis)
5. [Queue Management APIs](#queue-management-apis)
6. [Consumer Group APIs](#consumer-group-apis)
7. [Event Store APIs](#event-store-apis)
8. [Message APIs](#message-apis)
9. [Implementation Status](#implementation-status)
10. [Error Handling](#error-handling)

---

## Overview

The PeeGeeQ Management UI communicates with the PeeGeeQ REST server via HTTP/REST APIs. All management APIs use the `/api/v1/management` prefix.

### API Characteristics

- **Protocol**: HTTP/1.1
- **Format**: JSON
- **Base URL**: `http://localhost:8080` (configurable)
- **CORS**: Enabled for `http://localhost:3000` in development
- **Authentication**: None (currently) - planned for production

### Status Indicators

Throughout this document, endpoints are marked with status indicators:

- ✅ **Implemented**: Fully working with real backend
- ⚠️ **Partial**: Partially implemented or using placeholder data
- ❌ **Not Implemented**: Planned but not yet available
- 🔄 **Placeholder**: Frontend ready, backend endpoint missing

---

## Base Configuration

### Environment Variables

```bash
# .env
VITE_API_URL=http://localhost:8080
VITE_API_TIMEOUT=30000
```

### Axios Configuration

```typescript
// src/utils/axios.ts
const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const API_TIMEOUT = 30000; // 30 seconds

const axiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});
```

### RTK Query Configuration

```typescript
// src/store/api/baseApi.ts
export const baseApi = createApi({
  reducerPath: 'api',
  baseQuery: fetchBaseQuery({ 
    baseUrl: API_BASE_URL,
    timeout: API_TIMEOUT,
  }),
  tagTypes: ['Queue', 'ConsumerGroup', 'EventStore', 'Message'],
  endpoints: () => ({}),
});
```

---

## Authentication

### Current Status: ❌ Not Implemented

**Planned for Production:**

```typescript
// Future authentication header
headers: {
  'Authorization': `Bearer ${token}`,
  'Content-Type': 'application/json',
}
```

**Authentication Flow** (Planned):
1. User logs in with credentials
2. Server returns JWT token
3. Token stored in httpOnly cookie or localStorage
4. Token included in all subsequent requests
5. Token refresh mechanism for long sessions

---

## System & Health APIs

### Health Check

**Status**: ✅ Implemented

```http
GET /health
```

**Purpose**: Check if backend service is running

**Response**: `200 OK`

**Used By**: 
- `global-setup.ts` (test pre-flight check)
- `src/utils/healthCheck.ts`

**Example**:
```typescript
const response = await axios.get('http://localhost:8080/health');
// response.status === 200
```

---

### System Overview

**Status**: ⚠️ Partial (most data is real, some features incomplete)

```http
GET /api/v1/management/overview
```

**Purpose**: Get system statistics and overview data

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
  },
  "recentActivity": [
    {
      "timestamp": "2024-11-20T10:30:00Z",
      "type": "queue_created",
      "description": "Queue 'orders-queue' created",
      "severity": "info"
    }
  ]
}
```

**Used By**:
- `src/pages/Overview.tsx`
- `src/store/api/managementApi.ts`

**Implementation Status** (Updated December 2025):
- ✅ Endpoint exists
- ✅ Queue statistics use real database queries
- ✅ Consumer group counts use actual subscription data
- ✅ Event store counts use real event store stats
- ⚠️ `recentActivity` returns empty array (TODO in ManagementApiHandler line 563-567)
- ❌ Real-time updates not implemented (WebSocket planned)

---

## Queue Management APIs

### List All Queues

**Status**: ✅ Implemented

```http
GET /api/v1/management/queues
```

**Query Parameters**:
| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `type` | string | Filter by queue type (comma-separated) | `standard,priority` |
| `status` | string | Filter by status (comma-separated) | `active,paused` |
| `setupId` | string | Filter by setup ID | `prod-01` |
| `search` | string | Search query string | `orders` |
| `sortBy` | string | Field to sort by | `name`, `messages` |
| `sortOrder` | string | Sort order | `asc`, `desc` |
| `page` | number | Page number (1-based) | `1` |
| `pageSize` | number | Items per page | `20` |

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
      "messageRate": 15.5,
      "type": "standard"
    }
  ],
  "total": 15,
  "page": 1,
  "pageSize": 20
}
```

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/Queues.tsx`
- `src/pages/MessageBrowser.tsx`

**Example**:
```typescript
const { data } = useGetQueuesQuery({
  type: 'standard',
  status: 'active',
  page: 1,
  pageSize: 20,
});
```

---

### Get Queue Details

**Status**: ✅ Implemented

```http
GET /api/management/queues/:setupId/:queueName
```

**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

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

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/QueueDetails.tsx`

---

### Create Queue

**Status**: ✅ Implemented

```http
POST /api/v1/management/queues
```

**Request Body**:
```json
{
  "name": "orders-queue",
  "setup": "production",
  "type": "standard",
  "durability": "durable",
  "maxLength": 10000,
  "autoDelete": false,
  "ttl": 3600000,
  "description": "Queue for order processing"
}
```

**Alternative Endpoint**:
```http
POST /api/management/queues/:setupId/:queueName
```

**Request Body** (alternative):
```json
{
  "durability": "durable",
  "maxLength": 10000,
  "autoDelete": false,
  "ttl": 3600000,
  "description": "Queue for order processing"
}
```

**Response**:
```json
{
  "name": "orders-queue",
  "setupId": "prod-01",
  "status": "active",
  "created": "2024-11-20T10:30:00Z"
}
```

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/Queues.tsx`

**Example**:
```typescript
const [createQueue] = useCreateQueueMutation();

await createQueue({
  name: 'new-queue',
  setup: 'production',
  type: 'standard',
});
```

---

### Update Queue Configuration

**Status**: ⚠️ Partial

```http
PATCH /api/management/queues/:setupId/:queueName/config
```

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

**Alternative Endpoint**:
```http
PUT /api/v1/management/queues/:key
```

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/Queues.tsx`

---

### Delete Queue

**Status**: ✅ Implemented

```http
DELETE /api/v1/management/queues/:queueName
```

**Alternative Endpoints**:
```http
DELETE /api/management/queues/:setupId/:queueName
DELETE /api/v1/management/queues/:key
```

**Query Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `ifEmpty` | boolean | Only delete if queue is empty |
| `ifUnused` | boolean | Only delete if no consumers |

**Response**: `204 No Content`

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/Queues.tsx`

**Example**:
```typescript
const [deleteQueue] = useDeleteQueueMutation();

await deleteQueue({
  queueName: 'old-queue',
  ifEmpty: true,
});
```

---

### Queue Operations

#### Purge Queue

**Status**: ⚠️ Partial (endpoint exists, implementation incomplete)

```http
POST /api/management/queues/:setupId/:queueName/purge
```

**Alternative**:
```http
POST /api/v1/queues/:setupId/:queueName/purge
```

**Purpose**: Remove all messages from a queue

**Response**: `200 OK`

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/QueueDetails.tsx`

**Implementation Status** (December 2025):
- ✅ Endpoint exists and is registered
- ⚠️ Not fully implemented - TODO in ManagementApiHandler line 1628
- ❌ May not correctly purge all messages from database
- **Recommendation**: Test thoroughly before using in production

---

#### Pause Queue

**Status**: ✅ Implemented (December 24, 2025)

```http
POST /api/v1/queues/:setupId/:queueName/pause
```

**Purpose**: Pause message consumption from a queue by pausing all consumer group subscriptions

**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Response**: `200 OK`
```json
{
  "message": "Queue paused successfully",
  "queueName": "test_queue",
  "setupId": "my-setup",
  "pausedSubscriptions": 3,
  "timestamp": 1766552901671
}
```

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/QueueDetailsEnhanced.tsx`

**Implementation Details**:
- Pauses all consumer group subscriptions for the queue/topic
- Returns count of paused subscriptions (0 if no subscriptions exist)
- Uses reactive programming with Vert.x Futures
- Implemented in `ManagementApiHandler.pauseQueue()`
- Tested in `QueuePauseResumeManualTest.java`

---

#### Resume Queue

**Status**: ✅ Implemented (December 24, 2025)

```http
POST /api/v1/queues/:setupId/:queueName/resume
```

**Purpose**: Resume message consumption from a paused queue by resuming all consumer group subscriptions

**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Response**: `200 OK`
```json
{
  "message": "Queue resumed successfully",
  "queueName": "test_queue",
  "setupId": "my-setup",
  "resumedSubscriptions": 3,
  "timestamp": 1766552901671
}
```

**Used By**:
- `src/store/api/queuesApi.ts`
- `src/pages/QueueDetailsEnhanced.tsx`

**Implementation Details**:
- Resumes all consumer group subscriptions for the queue/topic
- Returns count of resumed subscriptions (0 if no subscriptions exist)
- Uses reactive programming with Vert.x Futures
- Implemented in `ManagementApiHandler.resumeQueue()`
- Tested in `QueuePauseResumeManualTest.java`

---

#### Get Queue Consumers

**Status**: ⚠️ Partial

```http
GET /api/v1/queues/:setupId/:queueName/consumers
```

**Purpose**: Get list of consumers connected to a queue

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

**Used By**:
- `src/pages/QueueDetails.tsx`

---

#### Get Queue Bindings

**Status**: ⚠️ Partial

```http
GET /api/v1/queues/:setupId/:queueName/bindings
```

**Purpose**: Get exchange bindings for a queue

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

**Used By**:
- `src/pages/QueueDetails.tsx`

---

## Consumer Group APIs

### List Consumer Groups

**Status**: ✅ Implemented (uses real subscription data)

```http
GET /api/v1/management/consumer-groups
```

**Query Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `queueName` | string | Filter by queue name |
| `status` | string | Filter by status |
| `page` | number | Page number |
| `pageSize` | number | Items per page |

**Response**:
```json
{
  "consumerGroups": [
    {
      "name": "order-processors",
      "queueName": "orders-queue",
      "consumers": 5,
      "status": "active",
      "messagesProcessed": 15234,
      "avgProcessingTime": 125.5
    }
  ],
  "total": 8,
  "page": 1,
  "pageSize": 20
}
```

**Used By**:
- `src/store/api/consumerGroupsApi.ts`
- `src/pages/ConsumerGroups.tsx`

**Implementation Notes** (December 2025):
- ✅ Now uses `SubscriptionService.listSubscriptions()` for real data
- ✅ ConsumerGroupHandler refactored to use `QueueFactory.createConsumerGroup()`
- ✅ Old in-memory implementation removed

---

### Get Consumer Group Details

**Status**: ✅ Implemented

```http
GET /api/v1/management/consumer-groups/:groupName
```

**Path Parameters**:
- `groupName`: Consumer group name

**Response**:
```json
{
  "name": "order-processors",
  "queueName": "orders-queue",
  "status": "active",
  "consumers": [
    {
      "consumerId": "consumer-1",
      "status": "active",
      "messagesProcessed": 3045,
      "lastActivity": "2024-11-20T10:30:00Z"
    }
  ],
  "config": {
    "prefetchCount": 10,
    "ackMode": "auto"
  }
}
```

**Used By**:
- `src/store/api/consumerGroupsApi.ts`
- `src/pages/ConsumerGroupDetails.tsx`

---

### Create Consumer Group

**Status**: ✅ Implemented

```http
POST /api/v1/management/consumer-groups
```

**Alternative Endpoint**:
```http
POST /api/v1/queues/:setupId/:queueName/consumer-groups
```

**Request Body**:
```json
{
  "name": "new-processors",
  "queueName": "orders-queue",
  "prefetchCount": 10,
  "ackMode": "auto"
}
```

**Used By**:
- `src/store/api/consumerGroupsApi.ts`

**Implementation Notes** (December 2025):
- ✅ Uses `QueueFactory.createConsumerGroup()` for real consumer group creation
- ✅ Integrated with actual queue implementations

---

### Delete Consumer Group

**Status**: ✅ Implemented

```http
DELETE /api/v1/management/consumer-groups/:groupName
```

**Alternative Endpoint**:
```http
DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName
```

**Path Parameters**:
- `groupName`: Consumer group name

**Used By**:
- `src/store/api/consumerGroupsApi.ts`

**Implementation Notes** (December 2025):
- ✅ Uses `ConsumerGroup.close()` for proper cleanup
- ✅ Removes consumer group from queue factory

---

## Event Store APIs

### List Event Stores

**Status**: ✅ Implemented (uses real event store data)

```http
GET /api/v1/management/event-stores
```

**Alternative Endpoint**:
```http
GET /api/v1/setups/:setupId/eventstores
```

**Query Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | string | Filter by status |
| `page` | number | Page number |
| `pageSize` | number | Items per page |

**Response**:
```json
{
  "eventStores": [
    {
      "name": "order-events",
      "status": "active",
      "eventCount": 125000,
      "size": "2.5 GB",
      "retention": "30 days"
    }
  ],
  "total": 3,
  "page": 1,
  "pageSize": 20
}
```

**Used By**:
- `src/store/api/eventStoresApi.ts`
- `src/pages/EventStores.tsx`

**Implementation Notes** (December 2025):
- ✅ Endpoint added in December 2025
- ✅ Uses real event store stats from `BiTemporalEventStore.getStats()`
- ✅ Event count uses actual database queries

---

### Get Event Store Details

**Status**: ✅ Implemented (uses real event store stats)

```http
GET /api/v1/management/event-stores/:storeName
```

**Alternative Endpoint**:
```http
GET /api/v1/eventstores/:setupId/:eventStoreName/stats
```

**Path Parameters**:
- `storeName`: Event store name

**Response**:
```json
{
  "name": "order-events",
  "status": "active",
  "eventCount": 125000,
  "size": "2.5 GB",
  "retention": "30 days",
  "config": {
    "maxSize": "10 GB",
    "compressionEnabled": true
  },
  "statistics": {
    "writeRate": 45.2,
    "readRate": 12.5
  }
}
```

**Used By**:
- `src/store/api/eventStoresApi.ts`
- `src/pages/EventStoreDetails.tsx`

**Implementation Notes** (December 2025):
- ✅ Uses `BiTemporalEventStore.getStats()` for real statistics
- ✅ Event count and correction count are accurate
- ⚠️ Aggregate count returns 0 (needs EventStoreStats API extension)

---

### Create Event Store

**Status**: ⚠️ Partial (endpoint exists, needs completion)

```http
POST /api/v1/management/event-stores
```

**Alternative Endpoint**:
```http
POST /api/v1/setups/:setupId/eventstores
```

**Request Body**:
```json
{
  "name": "new-events",
  "retention": "30 days",
  "maxSize": "10 GB",
  "compressionEnabled": true
}
```

**Used By**:
- `src/store/api/eventStoresApi.ts`

**Implementation Notes**:
- ⚠️ Endpoint registered but implementation needs completion
- ⚠️ Event store creation logic needs to be finalized
- Use setup-specific endpoint for better organization

---

### Delete Event Store

**Status**: ⚠️ Partial (endpoint exists, needs completion)

```http
DELETE /api/v1/management/event-stores/:storeName
```

**Alternative Endpoint**:
```http
DELETE /api/v1/setups/:setupId/eventstores/:eventStoreName
```

**Path Parameters**:
- `storeName`: Event store name

**Used By**:
- `src/store/api/eventStoresApi.ts`

**Implementation Notes**:
- ⚠️ Endpoint registered but implementation needs completion
- ⚠️ Event store deletion logic needs to be finalized
- Consider data retention policies before deletion

---

## Message APIs

### Browse Messages

**Status**: ⚠️ Partial (endpoint exists, returns empty array)

```http
GET /api/v1/management/queues/:queueName/messages
```

**Path Parameters**:
- `queueName`: Queue name

**Query Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `limit` | number | Max messages to return (default: 50) |
| `offset` | number | Offset for pagination |
| `filter` | string | Filter expression |

**Response**:
```json
{
  "messages": [
    {
      "messageId": "msg-123",
      "timestamp": "2024-11-20T10:30:00Z",
      "payload": "{\"orderId\": 12345}",
      "headers": {
        "content-type": "application/json",
        "correlation-id": "corr-456"
      },
      "size": 256,
      "redelivered": false
    }
  ],
  "total": 1250,
  "hasMore": true
}
```

**Used By**:
- `src/store/api/messagesApi.ts`
- `src/pages/MessageBrowser.tsx`

**Implementation Status** (December 2025):
- ✅ Endpoint exists and is registered
- ⚠️ Returns empty array - TODO in ManagementApiHandler line 761-776
- ❌ Needs database message retrieval implementation
- ❌ Message polling not implemented (line 1560)

---

### Get Message Details

**Status**: ⚠️ Partial

```http
GET /api/v1/management/queues/:queueName/messages/:messageId
```

**Path Parameters**:
- `queueName`: Queue name
- `messageId`: Message ID

**Response**:
```json
{
  "messageId": "msg-123",
  "timestamp": "2024-11-20T10:30:00Z",
  "payload": "{\"orderId\": 12345, \"items\": [...]}",
  "headers": {
    "content-type": "application/json",
    "correlation-id": "corr-456",
    "reply-to": "response-queue"
  },
  "properties": {
    "priority": 5,
    "expiration": "3600000",
    "deliveryMode": "persistent"
  },
  "size": 1024,
  "redelivered": false,
  "redeliveryCount": 0
}
```

**Used By**:
- `src/store/api/messagesApi.ts`
- `src/pages/MessageDetails.tsx`

---

### Publish Message

**Status**: ✅ Implemented (use queue message endpoint)

```http
POST /api/v1/queues/:setupId/:queueName/messages
```

**Path Parameters**:
- `setupId`: Setup identifier
- `queueName`: Queue name

**Request Body**:
```json
{
  "payload": "{\"orderId\": 12345}",
  "headers": {
    "content-type": "application/json",
    "correlation-id": "corr-456"
  },
  "properties": {
    "priority": 5,
    "deliveryMode": "persistent"
  }
}
```

**Response**:
```json
{
  "messageId": "msg-789",
  "timestamp": "2024-11-20T10:30:00Z"
}
```

**Used By**:
- `src/store/api/messagesApi.ts`
- `src/pages/MessageBrowser.tsx`

**Implementation Notes** (December 2025):
- ✅ Fully implemented in QueueHandler
- ✅ Supports batch message sending
- ✅ Returns message ID and timestamp
- Note: Use the queue-specific endpoint, not the management endpoint

---

### Delete Message

**Status**: 🔄 Placeholder

```http
DELETE /api/v1/management/queues/:queueName/messages/:messageId
```

**Path Parameters**:
- `queueName`: Queue name
- `messageId`: Message ID

**Response**: `204 No Content`

**Used By**:
- `src/store/api/messagesApi.ts`

---

## Implementation Status

### Summary by Category

**Last Updated**: December 2025 (based on `peegeeq-rest` gap analysis)

| Category | Total Endpoints | ✅ Implemented | ⚠️ Partial | 🔄 Placeholder | ❌ Not Started |
|----------|----------------|---------------|-----------|---------------|---------------|
| **System & Health** | 2 | 2 | 0 | 0 | 0 |
| **Queue Management** | 10 | 8 | 2 | 0 | 0 |
| **Consumer Groups** | 4 | 4 | 0 | 0 | 0 |
| **Event Stores** | 4 | 2 | 2 | 0 | 0 |
| **Messages** | 4 | 1 | 3 | 0 | 0 |
| **Total** | **24** | **17** | **7** | **0** | **0** |

### Recent Improvements (December 2025)

**✅ Resolved Gaps:**
- Queue statistics now use **real database queries** (not placeholders)
- Consumer groups now use **actual subscription data** (not random data)
- Event store queries now use **real implementations** (placeholders removed)
- Added `GET /api/v1/setups/:setupId/queues` endpoint
- Added `GET /api/v1/setups/:setupId/eventstores` endpoint
- Fixed response status codes (event store returns 201, health includes `healthy` field)
- ConsumerGroupHandler refactored to use `QueueFactory.createConsumerGroup()`

**⚠️ Remaining Limitations:**
- Recent activity returns empty array (TODO in ManagementApiHandler)
- Message browsing returns empty array (TODO - needs database message retrieval)
- Queue bindings return empty array (TODO - needs binding tracking)
- Message polling returns empty array (TODO - needs proper implementation)
- Queue purge not fully implemented (TODO)

### Priority for Completion

**High Priority** (Production Critical):
1. ✅ ~~Complete queue CRUD operations~~ - **DONE**
2. ⚠️ Message publish functionality - **PARTIAL** (endpoint exists, needs full implementation)
3. ⚠️ Message browsing - **PARTIAL** (returns empty, needs database retrieval)
4. ✅ ~~Real-time metrics for system overview~~ - **DONE** (now uses real data)
5. ✅ ~~Consumer group CRUD operations~~ - **DONE**

**Medium Priority** (Enhanced Management):
1. ⚠️ Event store CRUD operations - **PARTIAL** (read operations work, write needs completion)
2. ⚠️ Recent activity logging - **NOT IMPLEMENTED** (returns empty)
3. ⚠️ Queue bindings management - **NOT IMPLEMENTED** (returns empty)
4. ⚠️ Message polling - **NOT IMPLEMENTED** (returns empty)
5. ⚠️ Queue purge - **PARTIAL** (not fully implemented)

**Low Priority** (Nice to Have):
1. Bulk operations
2. Export/import configurations
3. Historical metrics
4. Advanced search
5. Custom dashboards

---

## Error Handling

### Standard Error Response

All API errors follow this format:

```json
{
  "error": {
    "code": "QUEUE_NOT_FOUND",
    "message": "Queue 'orders-queue' not found",
    "details": {
      "queueName": "orders-queue",
      "setupId": "prod-01"
    },
    "timestamp": "2024-11-20T10:30:00Z"
  }
}
```

### HTTP Status Codes

| Status Code | Meaning | Example |
|-------------|---------|---------|
| `200 OK` | Success | GET request successful |
| `201 Created` | Resource created | Queue created successfully |
| `204 No Content` | Success, no response body | Queue deleted |
| `400 Bad Request` | Invalid request data | Missing required field |
| `401 Unauthorized` | Authentication required | Invalid or missing token |
| `403 Forbidden` | Insufficient permissions | User cannot delete queue |
| `404 Not Found` | Resource not found | Queue doesn't exist |
| `409 Conflict` | Resource conflict | Queue already exists |
| `422 Unprocessable Entity` | Validation error | Invalid queue name format |
| `500 Internal Server Error` | Server error | Unexpected server error |
| `503 Service Unavailable` | Service temporarily unavailable | Backend is down |

### Error Codes

| Error Code | Description | HTTP Status |
|------------|-------------|-------------|
| `QUEUE_NOT_FOUND` | Queue doesn't exist | 404 |
| `QUEUE_ALREADY_EXISTS` | Queue name already in use | 409 |
| `INVALID_QUEUE_NAME` | Queue name format invalid | 422 |
| `CONSUMER_GROUP_NOT_FOUND` | Consumer group doesn't exist | 404 |
| `EVENT_STORE_NOT_FOUND` | Event store doesn't exist | 404 |
| `MESSAGE_NOT_FOUND` | Message doesn't exist | 404 |
| `VALIDATION_ERROR` | Request validation failed | 422 |
| `INTERNAL_ERROR` | Unexpected server error | 500 |

### Frontend Error Handling

```typescript
// Example error handling in RTK Query
const { data, error, isLoading } = useGetQueuesQuery();

if (error) {
  if ('status' in error) {
    // HTTP error
    const httpError = error as { status: number; data: any };

    if (httpError.status === 404) {
      // Handle not found
    } else if (httpError.status === 500) {
      // Handle server error
    }
  } else {
    // Network error
    console.error('Network error:', error);
  }
}
```

### Retry Logic

RTK Query automatically retries failed requests:

```typescript
// Configured in baseApi.ts
export const baseApi = createApi({
  // ...
  refetchOnMountOrArgChange: 30, // Refetch after 30 seconds
  refetchOnFocus: true,
  refetchOnReconnect: true,
});
```

---

## Rate Limiting

### Current Status: ❌ Not Implemented

**Planned for Production:**

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1605876000

{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Try again in 60 seconds."
  }
}
```

---

## WebSocket APIs (Future)

### Real-Time Updates

**Status**: ❌ Not Implemented (Planned)

**Planned WebSocket Endpoint**:
```
ws://localhost:8080/api/v1/management/ws
```

**Use Cases**:
- Real-time queue statistics updates
- Live message count updates
- Consumer connection/disconnection events
- System health status changes

**Example Protocol**:
```json
// Subscribe to queue updates
{
  "action": "subscribe",
  "resource": "queue",
  "queueName": "orders-queue"
}

// Receive updates
{
  "type": "queue_update",
  "queueName": "orders-queue",
  "data": {
    "messageCount": 1251,
    "consumerCount": 5
  },
  "timestamp": "2024-11-20T10:30:00Z"
}
```

---

## Known Limitations

### Backend Implementation Gaps

Based on the **PeeGeeQ REST Client-Server Gap Analysis** (December 2025), the following limitations exist in the backend:

#### ManagementApiHandler Incomplete Features

| Feature | Status | Location | Impact |
|---------|--------|----------|--------|
| **Recent Activity** | Returns empty array | ManagementApiHandler line 563-567 | Dashboard shows no recent activity |
| **Message Browsing** | Returns empty array | ManagementApiHandler line 761-776 | Cannot browse messages in UI |
| **Queue Bindings** | Returns empty array | ManagementApiHandler line 1505 | Cannot view queue bindings |
| **Message Polling** | Returns empty array | ManagementApiHandler line 1560 | Cannot poll messages |
| **Queue Purge** | Not fully implemented | ManagementApiHandler line 1628 | Purge operation may not work correctly |

#### Workarounds

1. **Message Browsing**: Use SSE streaming or direct queue consumption instead
2. **Recent Activity**: Implement client-side activity tracking temporarily
3. **Queue Bindings**: Document bindings externally until implemented
4. **Message Polling**: Use SSE streaming for real-time message delivery

#### Planned Improvements

These gaps are documented in `peegeeq-rest/docs/PEEGEEQ_REST_CLIENT_SERVER_GAP_ANALYSIS.md` and are tracked for future implementation.

**Priority**: Medium (these are management/monitoring features, not core functionality)

---

## Cross-References

### Backend Documentation

For detailed implementation status and gap analysis, see:
- **`peegeeq-rest/docs/PEEGEEQ_REST_CLIENT_SERVER_GAP_ANALYSIS.md`** - Complete gap analysis between server and clients
- **`peegeeq-rest/docs/PEEGEEQ_REST_MODULE_GUIDE.md`** - REST module architecture and handler documentation
- **`peegeeq-rest/docs/PEEGEEQ-REST-TEST-CATEGORIZATION-GUIDE.md`** - Testing strategy and categories

### Key Findings from Gap Analysis

**✅ Resolved (December 2025)**:
- Queue statistics now use real database queries
- Consumer groups use actual subscription data
- Event store queries use real implementations
- ConsumerGroupHandler refactored to use QueueFactory
- Added missing endpoints for listing queues and event stores

**⚠️ Remaining Gaps**:
- Recent activity logging (returns empty)
- Message browsing (returns empty)
- Queue bindings tracking (returns empty)
- Message polling (returns empty)
- Queue purge (partial implementation)

---

## References

### Related Documentation
- [Quick Start Guide](QUICK_START.md)
- [Design & Architecture](DESIGN_AND_ARCHITECTURE.md)
- [Testing Guide](TESTING_GUIDE.md)
- [Production Readiness](PRODUCTION_READINESS.md)

### Backend Documentation
- [PeeGeeQ REST Client-Server Gap Analysis](../../peegeeq-rest/docs/PEEGEEQ_REST_CLIENT_SERVER_GAP_ANALYSIS.md)
- [PeeGeeQ REST Module Guide](../../peegeeq-rest/docs/PEEGEEQ_REST_MODULE_GUIDE.md)
- [PeeGeeQ REST Test Categorization](../../peegeeq-rest/docs/PEEGEEQ-REST-TEST-CATEGORIZATION-GUIDE.md)

### External Resources
- [REST API Best Practices](https://restfulapi.net/)
- [HTTP Status Codes](https://httpstatuses.com/)
- [RTK Query Documentation](https://redux-toolkit.js.org/rtk-query/overview)

---

**Document Version**: 1.1
**Last Updated**: 2025-12-24 (Updated with December 2025 backend improvements)
**Maintained By**: PeeGeeQ Development Team


