# PeeGeeQ REST API Reference

**Version:** 1.0-SNAPSHOT  
**Base URL:** `http://localhost:8080`  
**Framework:** Vert.x 5.0.4

## Table of Contents

1. [Database Setup Endpoints](#database-setup-endpoints)
2. [Queue Management Endpoints](#queue-management-endpoints)
3. [Queue Message Operations](#queue-message-operations)
4. [Consumer Group Endpoints](#consumer-group-endpoints)
5. [Consumer Group Subscription Options](#consumer-group-subscription-options-phase-32)
6. [Webhook Subscription Endpoints](#webhook-subscription-endpoints)
7. [Dead Letter Queue Endpoints](#dead-letter-queue-endpoints)
8. [Subscription Lifecycle Endpoints](#subscription-lifecycle-endpoints)
9. [Event Store Endpoints](#event-store-endpoints)
10. [Per-Setup Health Endpoints](#per-setup-health-endpoints)
11. [Management API Endpoints](#management-api-endpoints)
12. [Health & Metrics Endpoints](#health--metrics-endpoints)
13. [Real-time Streaming Endpoints](#real-time-streaming-endpoints)

---

## Database Setup Endpoints

Database setups are the foundational resource in PeeGeeQ. A setup represents an isolated messaging environment with its own queues, event stores, and configuration. Each setup creates dedicated PostgreSQL tables and manages its own connection pool.

**Key Concepts:**
- **Setup ID**: A unique identifier for the messaging environment (e.g., "orders-service", "payments-prod")
- **Isolation**: Each setup has isolated queues and event stores - messages in one setup cannot be accessed from another
- **Lifecycle**: Setups go through states: CREATING → ACTIVE → (optionally) FAILED
- **Resource Management**: Destroying a setup cleans up all associated database tables and connections

**When to Use Multiple Setups:**
- Separate environments (dev, staging, prod)
- Multi-tenant applications where each tenant needs isolated messaging
- Microservices that need independent message queues

### Create Database Setup

Creates a complete database setup with queues and event stores. This is the primary entry point for initializing PeeGeeQ messaging infrastructure.

**What This Endpoint Does:**
1. Creates PostgreSQL tables for message queues (queue_messages, dead_letter_queue)
2. Creates PostgreSQL tables for event stores (bitemporal_events) if configured
3. Sets up LISTEN/NOTIFY channels for real-time message delivery
4. Initializes connection pools and factory instances
5. Returns immediately with CREATING status, then transitions to ACTIVE when ready

**Endpoint:** `POST /api/v1/database-setup/create`
**Alternative:** `POST /api/v1/setups` (RESTful alias)
**Handler:** `DatabaseSetupHandler.createSetup()`
**Service:** `DatabaseSetupService.createCompleteSetup()`

**Request Body:**
```json
{
  "setupId": "string",
  "queues": [
    {
      "queueName": "string",
      "maxRetries": 3,
      "visibilityTimeoutSeconds": 30,
      "deadLetterEnabled": true
    }
  ],
  "eventStores": [
    {
      "eventStoreName": "string",
      "tableName": "string",
      "biTemporalEnabled": true,
      "notificationPrefix": "string"
    }
  ]
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `setupId` | string | Yes | - | Unique identifier for this setup. Must be alphanumeric with hyphens/underscores. Used in all subsequent API calls. |
| `queues` | array | No | [] | List of queues to create. Can be empty if only event stores are needed. |
| `queues[].queueName` | string | Yes | - | Name of the queue. Must be unique within the setup. |
| `queues[].maxRetries` | integer | No | 3 | Maximum delivery attempts before moving to dead letter queue. Range: 1-100. |
| `queues[].visibilityTimeoutSeconds` | integer | No | 30 | Seconds a message is hidden after being received. If not acknowledged within this time, message becomes visible again. Range: 1-43200 (12 hours). |
| `queues[].deadLetterEnabled` | boolean | No | true | Whether to move failed messages to dead letter queue. If false, messages are deleted after max retries. |
| `eventStores` | array | No | [] | List of event stores to create. Can be empty if only queues are needed. |
| `eventStores[].eventStoreName` | string | Yes | - | Logical name for the event store. Used in API calls. |
| `eventStores[].tableName` | string | No | auto-generated | PostgreSQL table name. If not provided, derived from eventStoreName. |
| `eventStores[].biTemporalEnabled` | boolean | No | true | Enable bi-temporal event storage (transaction time + valid time). |
| `eventStores[].notificationPrefix` | string | No | auto-generated | Prefix for PostgreSQL NOTIFY channel names. |

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "createdAt": "timestamp",
  "queueFactories": {},
  "eventStores": {}
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setupId` | string | The setup identifier (echoed from request) |
| `status` | enum | Current state: `CREATING` (initializing), `ACTIVE` (ready for use), `FAILED` (initialization error) |
| `createdAt` | string | ISO-8601 timestamp when setup was created |
| `queueFactories` | object | Map of queue names to their factory metadata |
| `eventStores` | object | Map of event store names to their metadata |

**Error Responses:**

| Status | Condition | Response |
|:-------|:----------|:---------|
| `400 Bad Request` | Invalid setupId format or missing required fields | `{"error": "setupId must be alphanumeric"}` |
| `409 Conflict` | Setup with this ID already exists | `{"error": "Setup 'my-setup' already exists"}` |
| `500 Internal Server Error` | Database connection or table creation failed | `{"error": "Failed to create tables: connection refused"}` |

---

### Destroy Database Setup

Destroys a database setup and cleans up all associated resources. This is a destructive operation that cannot be undone.

**What This Endpoint Does:**
1. Stops all active consumers and producers for this setup
2. Closes all SSE and WebSocket connections
3. Drops all PostgreSQL tables created for this setup
4. Releases connection pool resources
5. Removes setup from the active registry

**Warning:** All messages in queues and all events in event stores will be permanently deleted.

**Endpoint:** `DELETE /api/v1/database-setup/:setupId`
**Alternative:** `DELETE /api/v1/setups/:setupId` (RESTful alias)
**Handler:** `DatabaseSetupHandler.destroySetup()`
**Service:** `DatabaseSetupService.destroySetup()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The unique identifier of the setup to destroy |

**Response:** `204 No Content`

---

### Get Setup Status

Retrieves the current status of a database setup. Use this to check if a setup has finished initializing or to monitor setup health.

**Use Cases:**
- Poll after creating a setup to wait for ACTIVE status
- Health monitoring dashboards
- Debugging setup initialization issues

**Endpoint:** `GET /api/v1/database-setup/:setupId/status`
**Handler:** `DatabaseSetupHandler.getStatus()`
**Service:** `DatabaseSetupService.getSetupStatus()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The unique identifier of the setup |

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "createdAt": "timestamp"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setupId` | string | The setup identifier |
| `status` | enum | `CREATING` - Setup is initializing tables and connections. `ACTIVE` - Setup is ready for use. `FAILED` - Setup initialization failed (check logs). |
| `createdAt` | string | ISO-8601 timestamp when setup creation was initiated |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup with this ID does not exist |

---

### Add Queue to Setup

Dynamically adds a new queue to an existing database setup. This allows you to expand your messaging infrastructure without recreating the entire setup.

**What This Endpoint Does:**
1. Creates a new queue table in PostgreSQL
2. Sets up LISTEN/NOTIFY channel for the queue
3. Initializes producer and consumer factories
4. Makes the queue immediately available for use

**Use Cases:**
- Adding queues for new features without downtime
- Dynamic queue creation based on tenant requirements
- Scaling messaging infrastructure incrementally

**Endpoint:** `POST /api/v1/database-setup/:setupId/queues`
**Handler:** `DatabaseSetupHandler.addQueue()`
**Service:** `DatabaseSetupService.addQueue()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup to add the queue to |

**Request Body:**
```json
{
  "queueName": "string",
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `queueName` | string | Yes | - | Unique name for the queue within this setup |
| `maxRetries` | integer | No | 3 | Maximum delivery attempts before dead-lettering |
| `visibilityTimeoutSeconds` | integer | No | 30 | How long a message is hidden after being received |
| `deadLetterEnabled` | boolean | No | true | Whether to preserve failed messages in dead letter queue |

**Response:** `201 Created`
```json
{
  "message": "Queue added successfully",
  "queueName": "string",
  "setupId": "string"
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |
| `409 Conflict` | Queue with this name already exists in the setup |
| `400 Bad Request` | Invalid queue name or parameters |

---

### Add Event Store to Setup

Dynamically adds a new bi-temporal event store to an existing database setup.

**What This Endpoint Does:**
1. Creates event store tables with bi-temporal columns (valid_from, valid_to, transaction_time)
2. Sets up indexes for efficient temporal queries
3. Configures LISTEN/NOTIFY for real-time event streaming
4. Makes the event store immediately available

**Use Cases:**
- Adding event sourcing for new aggregates
- Expanding audit trail capabilities
- Creating domain-specific event stores

**Endpoint:** `POST /api/v1/database-setup/:setupId/eventstores`
**Handler:** `DatabaseSetupHandler.addEventStore()`
**Service:** `DatabaseSetupService.addEventStore()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup to add the event store to |

**Request Body:**
```json
{
  "eventStoreName": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "notificationPrefix": "string"
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `eventStoreName` | string | Yes | - | Logical name for the event store |
| `tableName` | string | No | auto-generated | PostgreSQL table name for storing events |
| `biTemporalEnabled` | boolean | No | true | Enable bi-temporal storage with valid time and transaction time |
| `notificationPrefix` | string | No | auto-generated | Prefix for NOTIFY channel names |

**Response:** `201 Created`
```json
{
  "message": "Event store added successfully",
  "eventStoreName": "string",
  "setupId": "string"
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |
| `409 Conflict` | Event store with this name already exists |

---

### List All Setups

Lists all database setups in the system. Useful for administrative dashboards and monitoring.

**Use Cases:**
- Administrative overview of all messaging environments
- Monitoring dashboards
- Cleanup scripts to find orphaned setups

**Endpoint:** `GET /api/v1/setups`
**Handler:** `DatabaseSetupHandler.listSetups()`
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "setups": [
    {
      "setupId": "string",
      "status": "ACTIVE|CREATING|FAILED",
      "createdAt": "timestamp"
    }
  ],
  "count": 0,
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setups` | array | List of all setups |
| `setups[].setupId` | string | Unique identifier |
| `setups[].status` | enum | Current state of the setup |
| `setups[].createdAt` | string | ISO-8601 creation timestamp |
| `count` | integer | Total number of setups |
| `timestamp` | integer | Response timestamp (epoch milliseconds) |

---

### Get Setup Details

Gets comprehensive information about a specific setup including all queues, event stores, and configuration.

**Use Cases:**
- Inspecting setup configuration
- Debugging message routing issues
- Verifying queue and event store creation

**Endpoint:** `GET /api/v1/setups/:setupId`
**Handler:** `DatabaseSetupHandler.getSetupDetails()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup identifier |

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "createdAt": "timestamp",
  "queues": [
    {
      "queueName": "string",
      "maxRetries": 3,
      "visibilityTimeoutSeconds": 30,
      "deadLetterEnabled": true
    }
  ],
  "eventStores": [
    {
      "eventStoreName": "string",
      "biTemporalEnabled": true
    }
  ],
  "implementationType": "peegeeq-native|peegeeq-outbox"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setupId` | string | The setup identifier |
| `status` | enum | Current state |
| `createdAt` | string | ISO-8601 creation timestamp |
| `queues` | array | All queues in this setup with their configuration |
| `eventStores` | array | All event stores in this setup |
| `implementationType` | enum | `peegeeq-native` (PostgreSQL LISTEN/NOTIFY) or `peegeeq-outbox` (transactional outbox pattern) |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |

---

### Delete Setup

Deletes a database setup and all associated resources. This is an alias for the Destroy Database Setup endpoint using RESTful conventions.

**Endpoint:** `DELETE /api/v1/setups/:setupId`
**Handler:** `DatabaseSetupHandler.deleteSetup()`
**Service:** `DatabaseSetupService.destroySetup()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup to delete |

**Response:** `204 No Content`

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |

---

## Queue Management Endpoints

Queue management endpoints provide visibility into queue state and allow administrative operations on queues. These are read-only and administrative operations - for sending and receiving messages, see [Queue Message Operations](#queue-message-operations).

**Key Concepts:**
- **Queue**: A named destination for messages within a setup
- **Pending Messages**: Messages waiting to be consumed
- **In-Flight Messages**: Messages currently being processed (visibility timeout active)
- **Dead Letter Messages**: Messages that failed processing after max retries

### Get Queue Details

Gets detailed information about a specific queue including message counts and configuration.

**Use Cases:**
- Monitoring queue depth and health
- Debugging message processing issues
- Capacity planning

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName`
**Handler:** `ManagementApiHandler.getQueueDetails()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup containing the queue |
| `queueName` | string | Yes | The queue name |

**Response:** `200 OK`
```json
{
  "name": "string",
  "setup": "string",
  "implementationType": "peegeeq-native|peegeeq-outbox",
  "status": "active|error",
  "messages": 0,
  "consumers": 0,
  "messageRate": 0.0,
  "consumerRate": 0.0,
  "durability": "durable",
  "autoDelete": false,
  "createdAt": "timestamp",
  "lastActivity": "timestamp"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `name` | string | Queue name |
| `setup` | string | Parent setup ID |
| `implementationType` | enum | `peegeeq-native` or `peegeeq-outbox` |
| `status` | enum | `active` (healthy) or `error` (issues detected) |
| `messages` | integer | Total pending messages in queue |
| `consumers` | integer | Number of active consumers |
| `messageRate` | number | Messages per second (rolling average) |
| `consumerRate` | number | Consumption rate per second |
| `durability` | string | Always `durable` - messages survive restarts |
| `autoDelete` | boolean | Whether queue auto-deletes when last consumer disconnects |
| `createdAt` | string | ISO-8601 queue creation timestamp |
| `lastActivity` | string | ISO-8601 timestamp of last message activity |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup or queue does not exist |

---

### Get Queue Statistics

Gets statistics for a specific queue including message counts and processing metrics.

**Implementation Status:** PARTIAL - Currently returns basic queue availability information. Full message statistics (counts, processing rates) require `QueueFactory.getStats()` to be implemented.

**Use Cases:**
- Monitoring queue health and availability
- Checking queue implementation type
- Alerting on queue depth thresholds (when fully implemented)
- Performance analysis (when fully implemented)

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stats`
**Handler:** `QueueHandler.getQueueStats()`
**Service:** `DatabaseSetupService.getSetupResult()`, `QueueFactory.isHealthy()`, `QueueFactory.getImplementationType()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |

**Response:** `200 OK`
```json
{
  "queueName": "string",
  "setupId": "string",
  "implementationType": "native|outbox",
  "healthy": true,
  "totalMessages": 0,
  "pendingMessages": 0,
  "processedMessages": 0,
  "note": "Full message statistics require QueueFactory.getStats() implementation",
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `queueName` | string | Queue name |
| `setupId` | string | Setup ID containing the queue |
| `implementationType` | string | Queue implementation type (`native` or `outbox`) |
| `healthy` | boolean | Whether the queue factory is healthy |
| `totalMessages` | integer | Total messages (currently 0 - placeholder) |
| `pendingMessages` | integer | Pending messages (currently 0 - placeholder) |
| `processedMessages` | integer | Processed messages (currently 0 - placeholder) |
| `note` | string | Implementation status note |
| `timestamp` | integer | Response timestamp in epoch milliseconds |

---

### Get Queue Consumers

Gets list of active consumers for a queue. Useful for monitoring consumer health and load distribution.

**Use Cases:**
- Verifying consumers are connected
- Debugging load balancing issues
- Monitoring consumer group membership

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumers`
**Handler:** `ManagementApiHandler.getQueueConsumers()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |

**Response:** `200 OK`
```json
{
  "message": "Consumers retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "consumerCount": 0,
  "consumers": [
    {
      "consumerId": "string",
      "groupName": "string",
      "status": "active|idle",
      "messagesProcessed": 0,
      "lastActivity": "timestamp",
      "connectionType": "SSE|WebSocket|Polling|Webhook"
    }
  ],
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `consumerCount` | integer | Total number of active consumers |
| `consumers` | array | List of consumer details |
| `consumers[].consumerId` | string | Unique consumer identifier |
| `consumers[].groupName` | string | Consumer group name (if part of a group) |
| `consumers[].status` | enum | `active` (processing) or `idle` (waiting) |
| `consumers[].messagesProcessed` | integer | Messages processed by this consumer |
| `consumers[].lastActivity` | string | Last message processing timestamp |
| `consumers[].connectionType` | enum | How consumer receives messages |

---

### Get Queue Bindings

Gets bindings for a specific queue. Bindings define routing rules for messages.

**Note:** In PeeGeeQ, bindings are primarily used with the outbox pattern for routing messages based on headers.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/bindings`
**Handler:** `ManagementApiHandler.getQueueBindings()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Bindings retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "bindingCount": 0,
  "bindings": [],
  "timestamp": 0
}
```

---

### Purge Queue

Purges all messages from a queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/purge`  
**Handler:** `ManagementApiHandler.purgeQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Queue purge initiated",
  "queueName": "string",
  "setupId": "string",
  "timestamp": 0
}
```

---

## Queue Message Operations

Message operations are the core of PeeGeeQ - sending messages to queues and receiving them for processing. PeeGeeQ supports multiple consumption patterns: polling, Server-Sent Events (SSE), WebSocket, and webhook push delivery.

**Key Concepts:**
- **Message ID**: Unique identifier assigned when a message is sent
- **Payload**: The message content (any JSON object)
- **Headers**: Key-value metadata for routing and filtering
- **Priority**: Higher priority messages are delivered first (1-10, default 5)
- **Delay**: Messages can be delayed before becoming visible
- **Visibility Timeout**: After receiving, message is hidden for processing time
- **Acknowledgment**: Consumer must acknowledge successful processing

**Message Lifecycle:**
1. **Sent** - Message is stored in queue
2. **Delayed** (optional) - Message waits for delay period
3. **Pending** - Message is visible and available for consumption
4. **In-Flight** - Message received by consumer, visibility timeout active
5. **Acknowledged** - Successfully processed, removed from queue
6. **Dead-Lettered** - Failed after max retries, moved to DLQ

### Send Message

Sends a single message to a queue. The message is immediately stored and will be delivered to the next available consumer.

**Use Cases:**
- Publishing events from your application
- Sending commands to worker processes
- Queueing tasks for background processing

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/messages`
**Handler:** `QueueHandler.sendMessage()`
**Service:** Uses `QueueFactory.createProducer()` and `MessageProducer.send()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup containing the queue |
| `queueName` | string | Yes | The target queue name |

**Request Body:**
```json
{
  "payload": {},
  "headers": {
    "key": "value"
  },
  "priority": 5,
  "delaySeconds": 0,
  "messageType": "string",
  "correlationId": "string",
  "messageGroup": "string"
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `payload` | object | Yes | - | The message content. Can be any valid JSON object. |
| `headers` | object | No | {} | Key-value pairs for routing and filtering. Keys and values must be strings. |
| `priority` | integer | No | 5 | Message priority (1-10). Higher values = higher priority. |
| `delaySeconds` | integer | No | 0 | Delay before message becomes visible. Range: 0-900 (15 minutes). |
| `messageType` | string | No | null | Optional type identifier for message routing/filtering. |
| `correlationId` | string | No | auto-generated | Correlation ID for distributed tracing. If not provided, a UUID is generated. |
| `messageGroup` | string | No | null | Message group ID for ordered processing within a partition. Messages with the same group are processed in order. |

**Response:** `200 OK`
```json
{
  "message": "Message sent successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "correlationId": "string",
  "timestamp": 0,
  "messageType": "string",
  "priority": 5,
  "delaySeconds": 0,
  "messageGroup": "string",
  "customHeadersCount": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `messageId` | string | Unique identifier for the message (UUID) |
| `correlationId` | string | Correlation ID for distributed tracing (same as messageId if not provided) |
| `timestamp` | integer | Epoch milliseconds when message was stored |
| `messageGroup` | string | Message group ID (only present if specified in request) |
| `customHeadersCount` | integer | Number of custom headers attached |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | Missing payload or invalid parameters |
| `404 Not Found` | Setup or queue does not exist |
| `413 Payload Too Large` | Payload exceeds maximum size (default 256KB) |

---

### Send Batch Messages

Sends multiple messages to a queue in a single request. More efficient than sending messages individually when you have multiple messages to send.

**Use Cases:**
- Bulk event publishing
- Importing data as messages
- High-throughput producers

**Performance:** Batch sending is significantly faster than individual sends due to reduced network round-trips and database transaction overhead.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/messages/batch`
**Handler:** `QueueHandler.sendMessages()`
**Service:** Uses `QueueFactory.createProducer()` and `MessageProducer.send()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup containing the queue |
| `queueName` | string | Yes | The target queue name |

**Request Body:**
```json
{
  "messages": [
    {
      "payload": {},
      "headers": {},
      "priority": 5,
      "delaySeconds": 0
    }
  ],
  "failOnError": true,
  "maxBatchSize": 100
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `messages` | array | Yes | - | Array of messages to send (same format as single message) |
| `failOnError` | boolean | No | true | If true, entire batch fails on any error. If false, partial success is allowed. |
| `maxBatchSize` | integer | No | 100 | Maximum messages per batch. Range: 1-1000. |

**Response:** `200 OK` (all succeeded) or `207 Multi-Status` (partial success when failOnError=false)
```json
{
  "message": "Batch messages processed",
  "queueName": "string",
  "setupId": "string",
  "totalMessages": 0,
  "successfulMessages": 0,
  "failedMessages": 0,
  "messageIds": []
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `totalMessages` | integer | Total messages in the batch request |
| `successfulMessages` | integer | Messages successfully sent |
| `failedMessages` | integer | Messages that failed to send |
| `messageIds` | array | Array of message IDs for successful messages |

---

### Get Next Message

Polls for the next available message from a queue. This is a synchronous pull-based consumption pattern.

**When to Use Polling:**
- Simple consumers that process messages one at a time
- Batch processing jobs that run periodically
- When you need explicit control over message retrieval timing

**Note:** For real-time message delivery, consider using SSE, WebSocket, or Webhook endpoints instead.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages/next`
**Handler:** `QueueHandler.getNextMessage()`
**Service:** Uses `QueueFactory.createConsumer()` and internal polling

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `timeout` | integer | No | 30000 | Visibility timeout in milliseconds. Message is hidden for this duration. |
| `maxWait` | integer | No | 5000 | Maximum time to wait for a message if queue is empty. |
| `consumerGroup` | string | No | null | Consumer group name for coordinated consumption. |

**Response:** `200 OK` (message found) or `204 No Content` (no messages available)
```json
{
  "message": "Message retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "payload": {},
  "headers": {},
  "timestamp": 0,
  "priority": 5,
  "messageType": "string",
  "deliveryCount": 1,
  "visibilityTimeout": 30000
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `messageId` | string | Unique message identifier - use this to acknowledge |
| `payload` | object | The message content |
| `headers` | object | Message headers/metadata |
| `timestamp` | integer | When message was originally sent |
| `priority` | integer | Message priority |
| `messageType` | string | Optional message type |
| `deliveryCount` | integer | Number of times this message has been delivered |
| `visibilityTimeout` | integer | Milliseconds until message becomes visible again if not acknowledged |

**Important:** You must acknowledge the message after processing using the Acknowledge Message endpoint. If not acknowledged within the visibility timeout, the message will become visible again and be redelivered.

---

### Get Multiple Messages

Polls for multiple messages from a queue in a single request. More efficient than calling Get Next Message repeatedly.

**Use Cases:**
- Batch processing multiple messages together
- Reducing network round-trips for high-throughput consumers
- Prefetching messages for local processing

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages`
**Handler:** `QueueHandler.getMessages()`
**Service:** Uses `QueueFactory.createConsumer()` and internal polling

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `limit` | integer | No | 10 | Maximum messages to retrieve. Range: 1-100. |
| `timeout` | integer | No | 5000 | Maximum wait time in milliseconds. |
| `consumerGroup` | string | No | null | Consumer group name. |

**Response:** `200 OK`
```json
{
  "message": "Messages retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "messageCount": 0,
  "timestamp": 0,
  "messages": [
    {
      "messageId": "string",
      "payload": {},
      "headers": {},
      "timestamp": 0,
      "priority": 5,
      "deliveryCount": 1
    }
  ]
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `messageCount` | integer | Number of messages returned (may be less than limit) |
| `messages` | array | Array of message objects |

---

### Acknowledge Message

Acknowledges a message, marking it as successfully processed and removing it from the queue. This is a critical step in the message lifecycle.

**Important:** Always acknowledge messages after successful processing. Failure to acknowledge will cause the message to be redelivered after the visibility timeout expires.

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/messages/:messageId`
**Handler:** `QueueHandler.acknowledgeMessage()`
**Service:** Uses `QueueFactory.createConsumer()` and internal acknowledgment

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |
| `messageId` | string | Yes | The message ID to acknowledge (from Get Next Message response) |

**Response:** `200 OK` (acknowledged) or `404 Not Found` (message not found or already acknowledged)
```json
{
  "message": "Message acknowledged successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "timestamp": 0
}
```

---

## Consumer Group Endpoints

### Create Consumer Group

Creates a new consumer group for a queue. Consumer groups enable coordinated message consumption across multiple consumers with load balancing.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/consumer-groups`
**Handler:** `ConsumerGroupHandler.createConsumerGroup()`
**Service:** `DatabaseSetupService.getSetupResult()`, `QueueFactory.createConsumerGroup()`

**Note:** This endpoint creates a real PostgreSQL-backed consumer group via `QueueFactory.createConsumerGroup()`. The consumer group is backed by either the native or outbox pattern depending on the queue configuration.

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Request Body:**
```json
{
  "groupName": "string",
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000
}
```

**Response:** `201 Created`
```json
{
  "message": "Consumer group created successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "groupId": "string",
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000,
  "timestamp": 0
}
```

---

### List Consumer Groups

Lists all consumer groups for a queue. Returns information from real consumer groups created via `QueueFactory.createConsumerGroup()`.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumer-groups`
**Handler:** `ConsumerGroupHandler.listConsumerGroups()`
**Service:** `ConsumerGroup.getActiveConsumerCount()`, `ConsumerGroup.getConsumerIds()`, `ConsumerGroup.getStats()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Consumer groups retrieved successfully",
  "setupId": "string",
  "queueName": "string",
  "groupCount": 0,
  "groups": [
    {
      "groupName": "string",
      "groupId": "string",
      "memberCount": 0,
      "maxMembers": 10,
      "loadBalancingStrategy": "ROUND_ROBIN",
      "sessionTimeout": 30000,
      "createdAt": 0,
      "lastActivity": 0
    }
  ],
  "timestamp": 0
}
```

---

### Get Consumer Group

Gets details of a specific consumer group including all consumer members and their states.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`
**Handler:** `ConsumerGroupHandler.getConsumerGroup()`
**Service:** `ConsumerGroup.getActiveConsumerCount()`, `ConsumerGroup.getConsumerIds()`, `ConsumerGroup.isActive()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Consumer group retrieved successfully",
  "groupName": "string",
  "groupId": "string",
  "setupId": "string",
  "queueName": "string",
  "memberCount": 0,
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000,
  "createdAt": 0,
  "lastActivity": 0,
  "members": [
    {
      "memberId": "string",
      "memberName": "string",
      "joinedAt": 0,
      "lastHeartbeat": 0,
      "assignedPartitions": 0,
      "status": "ACTIVE"
    }
  ],
  "timestamp": 0
}
```

---

### Join Consumer Group

Joins a consumer group by adding a new consumer member. This calls `ConsumerGroup.addConsumer()` on the real PostgreSQL-backed consumer group.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members`
**Handler:** `ConsumerGroupHandler.joinConsumerGroup()`
**Service:** `ConsumerGroup.addConsumer(consumerId, MessageHandler)`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Request Body:**
```json
{
  "memberName": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Successfully joined consumer group",
  "groupName": "string",
  "memberId": "string",
  "memberName": "string",
  "assignedPartitions": 0,
  "memberCount": 0,
  "timestamp": 0
}
```

---

### Leave Consumer Group

Leaves a consumer group by removing a consumer member. This calls `ConsumerGroup.removeConsumer()` on the real PostgreSQL-backed consumer group.

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId`
**Handler:** `ConsumerGroupHandler.leaveConsumerGroup()`
**Service:** `ConsumerGroup.removeConsumer(consumerId)`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name
- `memberId` (string, required): The member ID

**Response:** `200 OK`
```json
{
  "message": "Successfully left consumer group",
  "groupName": "string",
  "memberId": "string",
  "memberCount": 0,
  "timestamp": 0
}
```

---

### Delete Consumer Group

Deletes a consumer group and releases all resources. This calls `ConsumerGroup.close()` on the real PostgreSQL-backed consumer group to properly stop all consumers.

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`
**Handler:** `ConsumerGroupHandler.deleteConsumerGroup()`
**Service:** `ConsumerGroup.close()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Consumer group deleted successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "timestamp": 0
}
```

---

## Consumer Group Subscription Options (Phase 3.2)

### Set Subscription Options

Sets subscription options for a consumer group, controlling how consumers start reading messages.

**Endpoint:** `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.updateSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Request Body:**
```json
{
  "startPosition": "FROM_NOW|FROM_BEGINNING|FROM_MESSAGE_ID|FROM_TIMESTAMP",
  "startFromMessageId": 12345,
  "startFromTimestamp": "2025-11-23T00:00:00Z",
  "heartbeatIntervalSeconds": 60,
  "heartbeatTimeoutSeconds": 300
}
```

**Field Descriptions:**
- `startPosition` (string, required): Where consumers should start reading messages
  - `FROM_NOW`: Start from next new message (default)
  - `FROM_BEGINNING`: Start from oldest available message (backfill scenario)
  - `FROM_MESSAGE_ID`: Start from specific message ID (requires `startFromMessageId`)
  - `FROM_TIMESTAMP`: Start from specific timestamp (requires `startFromTimestamp`)
- `startFromMessageId` (number, optional): Message ID to start from when `startPosition=FROM_MESSAGE_ID`
- `startFromTimestamp` (string, optional): ISO-8601 timestamp when `startPosition=FROM_TIMESTAMP`
- `heartbeatIntervalSeconds` (number, optional): Seconds between heartbeats (default: 60, range: 10-300)
- `heartbeatTimeoutSeconds` (number, optional): Seconds before consumer considered dead (default: 300, range: 60-3600)

**Validation Rules:**
- Consumer group MUST exist (returns 404 if not found)
- `heartbeatIntervalSeconds` must be less than `heartbeatTimeoutSeconds`
- If `startPosition=FROM_MESSAGE_ID`, `startFromMessageId` is required
- If `startPosition=FROM_TIMESTAMP`, `startFromTimestamp` is required and must be valid ISO-8601

**Response:** `200 OK`
```json
{
  "message": "Subscription options updated successfully",
  "setupId": "string",
  "queueName": "string",
  "groupName": "string",
  "subscriptionOptions": {
    "startPosition": "FROM_BEGINNING",
    "startFromMessageId": null,
    "startFromTimestamp": null,
    "heartbeatIntervalSeconds": 60,
    "heartbeatTimeoutSeconds": 300
  },
  "timestamp": 1732320000000
}
```

**Error Responses:**

`404 Not Found` - Consumer group doesn't exist:
```json
{
  "error": "Consumer group 'my-group' not found for queue 'my-queue' in setup 'my-setup'",
  "timestamp": 1732320000000
}
```

`400 Bad Request` - Invalid parameters:
```json
{
  "error": "startFromMessageId is required when startPosition=FROM_MESSAGE_ID",
  "timestamp": 1732320000000
}
```

**Use Cases:**

1. **Late-joining consumers (Backfill):**
```json
{
  "startPosition": "FROM_BEGINNING"
}
```
New consumers will receive all historical messages from the beginning.

2. **Replay from specific point:**
```json
{
  "startPosition": "FROM_MESSAGE_ID",
  "startFromMessageId": 98765
}
```
Consumers will start reading from message ID 98765 onwards.

3. **Time-based replay:**
```json
{
  "startPosition": "FROM_TIMESTAMP",
  "startFromTimestamp": "2025-11-22T12:00:00Z"
}
```
Consumers will start reading messages sent after noon on Nov 22, 2025.

4. **Custom heartbeat settings:**
```json
{
  "startPosition": "FROM_NOW",
  "heartbeatIntervalSeconds": 30,
  "heartbeatTimeoutSeconds": 180
}
```
More frequent heartbeats for faster failure detection.

---

### Get Subscription Options

Gets the current subscription options for a consumer group.

**Endpoint:** `GET /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.getSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Subscription options retrieved successfully",
  "setupId": "string",
  "queueName": "string",
  "groupName": "string",
  "subscriptionOptions": {
    "startPosition": "FROM_NOW",
    "startFromMessageId": null,
    "startFromTimestamp": null,
    "heartbeatIntervalSeconds": 60,
    "heartbeatTimeoutSeconds": 300
  },
  "timestamp": 1732320000000
}
```

**Behavior for Non-Existent Groups:**
- Returns `200 OK` with default options
- Does not return 404 error
- Default options are returned as if explicitly set

**Default Subscription Options:**
```json
{
  "startPosition": "FROM_NOW",
  "startFromMessageId": null,
  "startFromTimestamp": null,
  "heartbeatIntervalSeconds": 60,
  "heartbeatTimeoutSeconds": 300
}
```

---

### Delete Subscription Options

Deletes subscription options for a consumer group, reverting to defaults.

**Endpoint:** `DELETE /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.deleteSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `204 No Content`

No response body. Subscription options are deleted, and subsequent consumers will use default options.

**Note:** Deleting subscription options does NOT affect currently active consumers. Only new consumers joining the group will use default options.

---

### SSE Streaming with Consumer Group

Streams messages via Server-Sent Events using consumer group subscription options.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stream?consumerGroup={groupName}`  
**Handler:** `ServerSentEventsHandler.handleQueueStream()`  
**Service:** Uses `ConsumerGroupHandler.getSubscriptionOptionsInternal()` for configuration

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `consumerGroup` (string, optional): Consumer group name
- `batchSize` (number, optional): Messages per batch (default: 10, max: 100)
- `maxWaitTime` (number, optional): Max wait time in milliseconds (default: 5000)
- `messageType` (string, optional): Filter by message type
- `filterHeaders` (string, optional): Comma-separated header filters (e.g., "key1:value1,key2:value2")

**Response:** `200 OK` (Content-Type: text/event-stream)

**Event Types:**

1. **Connection Event** (sent immediately):
```
event: connection
data: {"connectionId":"sse-1","setupId":"my-setup","queueName":"my-queue","consumerGroup":"my-group","batchSize":10,"maxWaitTime":5000,"filters":{},"timestamp":1732320000000}
```

2. **Configured Event** (sent after applying subscription options):
```
event: configured
data: {"startPosition":"FROM_BEGINNING","heartbeatIntervalSeconds":60,"heartbeatTimeoutSeconds":300,"consumerGroup":"my-group","timestamp":1732320000001}
```

3. **Message Event**:
```
event: message
data: {"messageId":"msg-123","payload":{"key":"value"},"headers":{"content-type":"application/json"},"timestamp":1732320000002}
```

4. **Heartbeat Event** (sent every heartbeatIntervalSeconds):
```
event: heartbeat
data: {"timestamp":1732320060000}
```

5. **Error Event**:
```
event: error
data: {"error":"Failed to fetch messages","timestamp":1732320000003}
```

6. **End Event** (connection closed):
```
event: end
data: {"reason":"Connection closed by client","timestamp":1732320000004}
```

**Subscription Options Application:**

When `consumerGroup` parameter is provided:
1. Handler retrieves subscription options via `getSubscriptionOptionsInternal()`
2. If consumer group exists, configured options are used
3. If consumer group doesn't exist:
   - Logs warning: "Consumer group '{groupName}' not found, using default subscription options"
   - Falls back to default options (FROM_NOW, 60s heartbeat)
   - Connection succeeds (no 404 error)
4. Subscription options are applied to the message consumer
5. `configured` event is sent with applied options

**Example Usage:**

```bash
# Stream with consumer group (uses FROM_BEGINNING if configured)
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group"

# Stream without consumer group (uses defaults)
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream"

# Stream with filters
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group&messageType=order.created&filterHeaders=region:us-east"
```

**Client Implementation Example:**

```javascript
const evtSource = new EventSource(
  'http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group'
);

evtSource.addEventListener('connection', (e) => {
  const data = JSON.parse(e.data);
  console.log('Connected:', data.connectionId);
  console.log('Consumer group:', data.consumerGroup);
});

evtSource.addEventListener('configured', (e) => {
  const data = JSON.parse(e.data);
  console.log('Start position:', data.startPosition);
  console.log('Heartbeat interval:', data.heartbeatIntervalSeconds);
});

evtSource.addEventListener('message', (e) => {
  const data = JSON.parse(e.data);
  console.log('Received message:', data.messageId, data.payload);
  // Process message...
});

evtSource.addEventListener('heartbeat', (e) => {
  const data = JSON.parse(e.data);
  console.log('Heartbeat at:', new Date(data.timestamp));
});

evtSource.addEventListener('error', (e) => {
  const data = JSON.parse(e.data);
  console.error('Error:', data.error);
});

// Close connection when done
// evtSource.close();
```

---

### Consumer Group Subscription Options Workflow

**Complete Workflow Example:**

```bash
# 1. Create consumer group
curl -X POST http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups \
  -H "Content-Type: application/json" \
  -d '{
    "groupName": "analytics-team",
    "maxMembers": 5
  }'

# 2. Set subscription options for backfill
curl -X POST http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription \
  -H "Content-Type: application/json" \
  -d '{
    "startPosition": "FROM_BEGINNING",
    "heartbeatIntervalSeconds": 45
  }'

# 3. Connect via SSE with consumer group
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=analytics-team"

# Consumer will receive:
# - connection event with groupName="analytics-team"
# - configured event with startPosition="FROM_BEGINNING", heartbeatIntervalSeconds=45
# - All historical messages starting from the beginning
# - Heartbeats every 45 seconds

# 4. Update subscription options (for next consumers)
curl -X POST http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription \
  -H "Content-Type: application/json" \
  -d '{
    "startPosition": "FROM_NOW",
    "heartbeatIntervalSeconds": 60
  }'

# 5. Get current subscription options
curl http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription

# 6. Delete subscription options (revert to defaults)
curl -X DELETE http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription

# 7. Delete consumer group
curl -X DELETE http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups/analytics-team
```

---

### Subscription Options Best Practices

**1. Choose the Right Start Position:**

- **FROM_NOW** (default): Production consumers that only need new messages
- **FROM_BEGINNING**: Analytics, reporting, data reprocessing, backfill scenarios
- **FROM_MESSAGE_ID**: Resume processing after known last processed message
- **FROM_TIMESTAMP**: Replay messages from specific time (incident recovery, time-based replay)

**2. Configure Heartbeats Based on Requirements:**

- **Low latency failure detection**: 30s interval, 180s timeout
- **Standard (default)**: 60s interval, 300s timeout
- **Low network overhead**: 120s interval, 600s timeout

**3. Set Subscription Options BEFORE Consumers Connect:**

```bash
# ✅ CORRECT ORDER
POST /api/v1/queues/{setupId}/{queueName}/consumer-groups  # Create group
POST /api/v1/consumer-groups/{setupId}/{queueName}/{groupName}/subscription  # Configure
GET  /api/v1/queues/{setupId}/{queueName}/stream?consumerGroup={groupName}  # Connect

# ❌ WRONG ORDER - Consumer will use defaults first
GET  /api/v1/queues/{setupId}/{queueName}/stream?consumerGroup={groupName}  # Connect
POST /api/v1/consumer-groups/{setupId}/{queueName}/{groupName}/subscription  # Too late!
```

**4. Validation Considerations:**

- Always validate consumer group exists before setting subscription options
- 404 error means the group hasn't been created yet
- GET subscription options returns defaults for non-existent groups (no validation)

**5. Impact on Active Consumers:**

- Changing subscription options does NOT affect currently connected consumers
- Only new consumers joining the group use updated options
- To apply new options to existing consumers: disconnect and reconnect

---

### Integration Testing

See `ConsumerGroupSubscriptionIntegrationTest.java` for comprehensive test coverage:

1. **testSetSubscriptionOptionsWithoutConsumerGroup** - Validates 404 error for non-existent groups
2. **testSSEWithNonExistentConsumerGroup** - Validates graceful fallback to defaults
3. **testCompleteWorkflow** - Tests create group → set options → connect SSE → verify options applied
4. **testGetSubscriptionOptionsForNonExistentGroup** - Validates GET returns defaults without 404
5. **testDeleteSubscriptionOptions** - Tests delete operation
6. **testSSEWithoutConsumerGroupUsesDefaults** - Tests SSE without consumer group parameter

All tests use TestContainers with PostgreSQL for realistic integration testing.

---

## Webhook Subscription Endpoints

Webhook subscriptions enable push-based message delivery where PeeGeeQ automatically sends messages to your HTTP endpoint. This is the recommended approach for production systems as it eliminates polling overhead and provides immediate message delivery.

**Key Concepts:**
- **Push Delivery**: Messages are sent to your endpoint as soon as they arrive in the queue
- **Automatic Retry**: Failed deliveries are retried with exponential backoff
- **Circuit Breaker**: After consecutive failures, subscription is paused to prevent overwhelming failing endpoints
- **Filtering**: Only receive messages matching your filter criteria
- **Custom Headers**: Include authentication tokens or custom headers in webhook requests

**Webhook vs Other Consumption Patterns:**

| Pattern | Best For | Latency | Complexity |
|:--------|:---------|:--------|:-----------|
| **Webhook** | Production systems, microservices | Lowest | Medium (requires HTTP endpoint) |
| **SSE** | Browser clients, real-time dashboards | Low | Low |
| **WebSocket** | Bidirectional communication | Low | Medium |
| **Polling** | Simple scripts, batch processing | High | Lowest |

### Create Webhook Subscription

Creates a new webhook subscription for push-based message delivery. Once created, messages will immediately start flowing to your webhook URL.

**What This Endpoint Does:**
1. Validates the webhook URL is reachable (optional health check)
2. Creates an internal consumer for the queue
3. Starts a background process that delivers messages to your webhook
4. Returns a subscription ID for management

**Endpoint:** `POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions`
**Handler:** `WebhookSubscriptionHandler.createSubscription()`
**Service:** `DatabaseSetupService.getSetupResult()`, `QueueFactory.createConsumer()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup containing the queue |
| `queueName` | string | Yes | The queue to subscribe to |

**Request Body:**
```json
{
  "webhookUrl": "https://your-service.example.com/webhook",
  "headers": {
    "Authorization": "Bearer your-token",
    "X-Custom-Header": "value"
  },
  "filters": {
    "messageType": "order.created",
    "region": "us-east"
  },
  "retryPolicy": {
    "maxRetries": 3,
    "initialDelayMs": 1000,
    "maxDelayMs": 30000
  }
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `webhookUrl` | string | Yes | - | HTTPS URL to receive messages. Must be publicly accessible. |
| `headers` | object | No | {} | Custom headers to include in webhook requests. Use for authentication. |
| `filters` | object | No | {} | Filter criteria. Only messages matching ALL filters are delivered. |
| `filters.messageType` | string | No | - | Only deliver messages with this type |
| `filters.*` | string | No | - | Match against message headers |
| `retryPolicy.maxRetries` | integer | No | 3 | Maximum delivery attempts per message |
| `retryPolicy.initialDelayMs` | integer | No | 1000 | Initial retry delay (exponential backoff) |
| `retryPolicy.maxDelayMs` | integer | No | 30000 | Maximum retry delay |

**Response:** `201 Created`
```json
{
  "message": "Webhook subscription created successfully",
  "subscriptionId": "uuid",
  "setupId": "string",
  "queueName": "string",
  "webhookUrl": "https://your-service.example.com/webhook",
  "status": "ACTIVE",
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `subscriptionId` | string | Unique identifier for this subscription. Use for get/delete operations. |
| `status` | enum | `ACTIVE` - Subscription is delivering messages |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | Invalid webhook URL or missing required fields |
| `404 Not Found` | Setup or queue does not exist |
| `422 Unprocessable Entity` | Webhook URL is not reachable |

---

### Get Webhook Subscription

Gets details of a specific webhook subscription including delivery statistics and current status.

**Use Cases:**
- Monitoring webhook health
- Debugging delivery failures
- Checking subscription configuration

**Endpoint:** `GET /api/v1/webhook-subscriptions/:subscriptionId`
**Handler:** `WebhookSubscriptionHandler.getSubscription()`
**Service:** In-memory subscription registry

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `subscriptionId` | string | Yes | The subscription UUID from create response |

**Response:** `200 OK`
```json
{
  "subscriptionId": "uuid",
  "setupId": "string",
  "queueName": "string",
  "webhookUrl": "https://your-service.example.com/webhook",
  "headers": {},
  "filters": {},
  "status": "ACTIVE|PAUSED|FAILED|DELETED",
  "consecutiveFailures": 0,
  "totalDeliveries": 0,
  "successfulDeliveries": 0,
  "failedDeliveries": 0,
  "createdAt": 0,
  "lastDeliveryAt": 0,
  "lastError": "string"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `status` | enum | `ACTIVE` (delivering), `PAUSED` (manually paused), `FAILED` (circuit breaker open), `DELETED` (being cleaned up) |
| `consecutiveFailures` | integer | Current consecutive failure count. Resets on successful delivery. |
| `totalDeliveries` | integer | Total delivery attempts |
| `successfulDeliveries` | integer | Successful deliveries (2xx response) |
| `failedDeliveries` | integer | Failed deliveries |
| `lastDeliveryAt` | integer | Epoch milliseconds of last delivery attempt |
| `lastError` | string | Error message from last failed delivery |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |

---

### Delete Webhook Subscription

Deletes a webhook subscription and stops message delivery. Any in-flight deliveries will complete, but no new messages will be sent.

**Endpoint:** `DELETE /api/v1/webhook-subscriptions/:subscriptionId`
**Handler:** `WebhookSubscriptionHandler.deleteSubscription()`
**Service:** In-memory subscription registry, `MessageConsumer.close()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `subscriptionId` | string | Yes | The subscription UUID |
- `subscriptionId` (string, required): The subscription UUID

**Response:** `200 OK`
```json
{
  "message": "Webhook subscription deleted successfully",
  "subscriptionId": "uuid",
  "timestamp": 0
}
```

---

### Webhook Delivery Behavior

When messages arrive in the queue, they are automatically pushed to the webhook URL:

**Outbound HTTP POST:**
```json
{
  "subscriptionId": "uuid",
  "queueName": "string",
  "messageId": "string",
  "payload": {},
  "headers": {},
  "timestamp": 0
}
```

**Failure Handling:**
- Webhook must return 2xx status code for success
- Non-2xx responses are counted as failures
- After 5 consecutive failures, subscription status changes to `FAILED`
- Failed subscriptions stop receiving messages until manually reactivated

---

## Dead Letter Queue Endpoints

The Dead Letter Queue (DLQ) stores messages that failed processing after exhausting all retry attempts. These endpoints allow you to inspect, reprocess, or delete failed messages.

**Key Concepts:**
- **Dead Letter Message**: A message that failed processing after `maxRetries` attempts
- **Failure Count**: Number of times the message was attempted before dead-lettering
- **Error Message**: The exception or error that caused the final failure
- **Reprocessing**: Moving a message back to the original queue for another attempt

**When Messages Are Dead-Lettered:**
1. Consumer throws an exception during processing
2. Message is not acknowledged within visibility timeout (multiple times)
3. Consumer explicitly rejects the message
4. Message exceeds maximum retry count configured for the queue

**DLQ Workflow:**
1. Message fails processing → retry with backoff
2. After `maxRetries` failures → move to DLQ
3. Operations team investigates via these endpoints
4. Fix the issue (code bug, external service, data problem)
5. Reprocess the message or delete if no longer needed

### List Dead Letter Messages

Lists dead letter messages with optional filtering and pagination. Use this to monitor failed messages and identify patterns.

**Use Cases:**
- Monitoring dashboard for failed messages
- Identifying recurring failure patterns
- Bulk operations on failed messages

**Endpoint:** `GET /api/v1/setups/:setupId/deadletter/messages`
**Handler:** `DeadLetterHandler.listMessages()`
**Service:** `DeadLetterService.getDeadLetterMessages()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `topic` | string | No | all | Filter by original queue name |
| `limit` | integer | No | 50 | Maximum messages to return. Range: 1-1000. |
| `offset` | integer | No | 0 | Offset for pagination |
| `sortBy` | string | No | deadLetteredAt | Sort field: `deadLetteredAt`, `originalTimestamp`, `failureCount` |
| `sortOrder` | string | No | desc | Sort order: `asc` or `desc` |

**Response:** `200 OK`
```json
{
  "messages": [
    {
      "id": "string",
      "topic": "string",
      "payload": {},
      "headers": {},
      "errorMessage": "string",
      "failureCount": 3,
      "originalTimestamp": 0,
      "deadLetteredAt": 0
    }
  ],
  "total": 0,
  "limit": 50,
  "offset": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `messages` | array | List of dead letter messages |
| `messages[].id` | string | Unique message identifier |
| `messages[].topic` | string | Original queue name |
| `messages[].payload` | object | Original message content |
| `messages[].headers` | object | Original message headers |
| `messages[].errorMessage` | string | Error from last processing attempt |
| `messages[].failureCount` | integer | Total processing attempts |
| `messages[].originalTimestamp` | integer | When message was originally sent |
| `messages[].deadLetteredAt` | integer | When message was moved to DLQ |
| `total` | integer | Total messages matching filter |

---

### Get Dead Letter Message

Gets detailed information about a specific dead letter message including full stack trace.

**Use Cases:**
- Debugging specific message failures
- Inspecting message payload for data issues
- Getting full error details

**Endpoint:** `GET /api/v1/setups/:setupId/deadletter/messages/:messageId`
**Handler:** `DeadLetterHandler.getMessage()`
**Service:** `DeadLetterService.getDeadLetterMessage()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `messageId` | string | Yes | The dead letter message ID |

**Response:** `200 OK`
```json
{
  "id": "string",
  "topic": "string",
  "payload": {},
  "headers": {},
  "errorMessage": "string",
  "stackTrace": "string",
  "failureCount": 3,
  "failureHistory": [
    {
      "attemptNumber": 1,
      "timestamp": 0,
      "error": "string"
    }
  ],
  "originalTimestamp": 0,
  "deadLetteredAt": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `stackTrace` | string | Full stack trace from last failure |
| `failureHistory` | array | History of all processing attempts |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Message does not exist |

---

### Reprocess Dead Letter Message

Moves a dead letter message back to its original queue for reprocessing. The message will be treated as a new message with reset retry count.

**When to Reprocess:**
- After fixing a bug in consumer code
- After an external service is restored
- After correcting data issues

**Important:** Ensure the underlying issue is fixed before reprocessing, or the message will fail again and return to the DLQ.

**Endpoint:** `POST /api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess`
**Handler:** `DeadLetterHandler.reprocessMessage()`
**Service:** `DeadLetterService.reprocessMessage()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `messageId` | string | Yes | The dead letter message ID |

**Request Body (optional):**
```json
{
  "priority": 10,
  "delaySeconds": 60
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `priority` | integer | No | original | Override message priority (1-10) |
| `delaySeconds` | integer | No | 0 | Delay before message becomes visible |

**Response:** `200 OK`
```json
{
  "message": "Message reprocessed successfully",
  "messageId": "string",
  "newMessageId": "string",
  "topic": "string",
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `newMessageId` | string | New message ID in the original queue |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Message does not exist |
| `409 Conflict` | Message already reprocessed |

---

### Delete Dead Letter Message

Permanently deletes a dead letter message. Use this when a message is no longer needed or cannot be fixed.

**Warning:** This operation cannot be undone. The message and all its data will be permanently deleted.

**Endpoint:** `DELETE /api/v1/setups/:setupId/deadletter/messages/:messageId`
**Handler:** `DeadLetterHandler.deleteMessage()`
**Service:** `DeadLetterService.deleteMessage()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `messageId` | string | Yes | The dead letter message ID |
- `messageId` (string, required): The dead letter message ID

**Response:** `200 OK`
```json
{
  "message": "Message deleted successfully",
  "messageId": "string",
  "timestamp": 0
}
```

---

### Get Dead Letter Statistics

Gets statistics for dead letter messages.

**Endpoint:** `GET /api/v1/setups/:setupId/deadletter/stats`
**Handler:** `DeadLetterHandler.getStats()`
**Service:** `DeadLetterService.getStats()`

**Path Parameters:**
- `setupId` (string, required): The setup ID

**Response:** `200 OK`
```json
{
  "totalMessages": 0,
  "messagesByTopic": {
    "queue-name": 5
  },
  "oldestMessage": 0,
  "newestMessage": 0
}
```

---

### Cleanup Dead Letter Messages

Cleans up old dead letter messages based on age.

**Endpoint:** `POST /api/v1/setups/:setupId/deadletter/cleanup`
**Handler:** `DeadLetterHandler.cleanup()`
**Service:** `DeadLetterService.cleanup()`

**Path Parameters:**
- `setupId` (string, required): The setup ID

**Request Body:**
```json
{
  "olderThanDays": 30
}
```

**Response:** `200 OK`
```json
{
  "message": "Cleanup completed",
  "deletedCount": 0,
  "timestamp": 0
}
```

---

## Subscription Lifecycle Endpoints

Subscription lifecycle endpoints allow you to manage the state of consumer group subscriptions. You can pause message delivery during maintenance, resume after issues are resolved, and monitor subscription health via heartbeats.

**Key Concepts:**
- **Subscription**: A consumer group's connection to a queue
- **Pause**: Temporarily stop message delivery without losing position
- **Resume**: Restart message delivery from where it was paused
- **Heartbeat**: Signal that a subscription is still active

**Subscription States:**
- `ACTIVE` - Subscription is receiving messages
- `PAUSED` - Subscription is paused, no messages delivered
- `STALE` - No heartbeat received within timeout period

**When to Use These Endpoints:**
- **Pause**: During deployments, maintenance windows, or when investigating issues
- **Resume**: After maintenance is complete or issues are resolved
- **Heartbeat**: Keep subscriptions alive in long-running consumers

### List Subscriptions

Lists all consumer group subscriptions for a topic/queue. Useful for monitoring which consumer groups are active.

**Use Cases:**
- Monitoring dashboard showing all active subscriptions
- Identifying stale or paused subscriptions
- Capacity planning based on consumer group count

**Endpoint:** `GET /api/v1/setups/:setupId/subscriptions/:topic`
**Handler:** `SubscriptionHandler.listSubscriptions()`
**Service:** `SubscriptionService.listSubscriptions()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |

**Response:** `200 OK`
```json
{
  "subscriptions": [
    {
      "groupName": "string",
      "topic": "string",
      "status": "ACTIVE|PAUSED|STALE",
      "memberCount": 0,
      "lastHeartbeat": 0,
      "createdAt": 0
    }
  ],
  "count": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `subscriptions` | array | List of subscriptions |
| `subscriptions[].groupName` | string | Consumer group name |
| `subscriptions[].status` | enum | Current subscription state |
| `subscriptions[].memberCount` | integer | Number of active consumers in the group |
| `subscriptions[].lastHeartbeat` | integer | Epoch milliseconds of last heartbeat |
| `subscriptions[].createdAt` | integer | When subscription was created |
| `count` | integer | Total number of subscriptions |

---

### Get Subscription

Gets detailed information about a specific subscription.

**Endpoint:** `GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName`
**Handler:** `SubscriptionHandler.getSubscription()`
**Service:** `SubscriptionService.getSubscription()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "groupName": "string",
  "topic": "string",
  "status": "ACTIVE|PAUSED|STALE",
  "memberCount": 0,
  "members": [
    {
      "consumerId": "string",
      "status": "active|idle",
      "lastActivity": 0
    }
  ],
  "lastHeartbeat": 0,
  "createdAt": 0,
  "pausedAt": 0,
  "pauseReason": "string",
  "messagesProcessed": 0,
  "currentOffset": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `members` | array | Individual consumers in the group |
| `pausedAt` | integer | When subscription was paused (null if active) |
| `pauseReason` | string | Reason provided when pausing |
| `messagesProcessed` | integer | Total messages processed by this subscription |
| `currentOffset` | integer | Current position in the queue |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |

---

### Pause Subscription

Pauses a subscription, stopping message delivery to all consumers in the group. Messages continue to accumulate in the queue and will be delivered when resumed.

**Use Cases:**
- Planned maintenance windows
- Investigating processing issues
- Preventing message processing during deployments

**Important:** Pausing does not disconnect consumers - it only stops message delivery. Consumers remain connected and will receive messages when resumed.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause`
**Handler:** `SubscriptionHandler.pauseSubscription()`
**Service:** `SubscriptionService.pauseSubscription()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Request Body:**
```json
{
  "reason": "Maintenance window"
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `reason` | string | No | null | Human-readable reason for pausing. Stored for audit purposes. |

**Response:** `200 OK`
```json
{
  "message": "Subscription paused successfully",
  "groupName": "string",
  "topic": "string",
  "status": "PAUSED",
  "pausedAt": 0,
  "timestamp": 0
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |
| `409 Conflict` | Subscription is already paused |

---

### Resume Subscription

Resumes a paused subscription, restarting message delivery. Messages that accumulated during the pause will be delivered.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume`
**Handler:** `SubscriptionHandler.resumeSubscription()`
**Service:** `SubscriptionService.resumeSubscription()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "message": "Subscription resumed successfully",
  "groupName": "string",
  "topic": "string",
  "status": "ACTIVE",
  "pauseDuration": 0,
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `pauseDuration` | integer | How long the subscription was paused (milliseconds) |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |
| `409 Conflict` | Subscription is not paused |

---

### Update Heartbeat

Updates the heartbeat timestamp for a subscription. Use this to signal that a consumer is still active and prevent the subscription from being marked as stale.

**When to Send Heartbeats:**
- Long-running consumers should send heartbeats periodically
- Recommended interval: every 30-60 seconds
- If no heartbeat is received within the timeout period, subscription may be marked stale

**Note:** SSE and WebSocket connections automatically send heartbeats. This endpoint is primarily for polling consumers.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat`
**Handler:** `SubscriptionHandler.updateHeartbeat()`
**Service:** `SubscriptionService.updateHeartbeat()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "message": "Heartbeat updated",
  "groupName": "string",
  "topic": "string",
  "lastHeartbeat": 0,
  "nextHeartbeatDue": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `lastHeartbeat` | integer | Epoch milliseconds of this heartbeat |
| `nextHeartbeatDue` | integer | When the next heartbeat should be sent |

---

### Cancel Subscription

Cancels and removes a subscription. All consumers in the group will be disconnected and the subscription state will be deleted.

**Warning:** This is a destructive operation. The subscription's position in the queue will be lost.

**Endpoint:** `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName`
**Handler:** `SubscriptionHandler.cancelSubscription()`
**Service:** `SubscriptionService.cancelSubscription()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "message": "Subscription cancelled successfully",
  "groupName": "string",
  "topic": "string",
  "disconnectedConsumers": 0,
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `disconnectedConsumers` | integer | Number of consumers that were disconnected |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |

---

## Event Store Endpoints

Event stores provide bi-temporal event sourcing capabilities. Events are stored with both a valid time (when the event occurred in the real world) and a transaction time (when the event was recorded in the system). This enables powerful temporal queries and corrections.

**Key Concepts:**
- **Event**: An immutable record of something that happened
- **Valid Time**: When the event occurred in the real world
- **Transaction Time**: When the event was recorded in the system
- **Correction**: A new version of an event that supersedes a previous version
- **Bi-Temporal Query**: Query events as they were known at a specific point in time

**Use Cases:**
- Audit trails with full history
- Regulatory compliance (MiFID II, GDPR)
- Event sourcing for domain aggregates
- Point-in-time reporting

### Store Event

Stores a new event in a bi-temporal event store. The event is immediately available for queries and will trigger notifications to any SSE subscribers.

**Endpoint:** `POST /api/v1/eventstores/:setupId/:eventStoreName/events`
**Handler:** `EventStoreHandler.storeEvent()`
**Service:** `DatabaseSetupService.getSetupResult()`, `BiTemporalEventStore.append()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `eventStoreName` | string | Yes | The event store name |

**Request Body:**
```json
{
  "eventType": "string",
  "eventData": {},
  "validFrom": "ISO-8601 timestamp",
  "validTo": "ISO-8601 timestamp",
  "correlationId": "string",
  "causationId": "string",
  "metadata": {}
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `eventType` | string | Yes | - | Type/category of the event (e.g., "order.created", "payment.received") |
| `eventData` | object | Yes | - | The event payload - any valid JSON object |
| `validFrom` | string | No | now | ISO-8601 timestamp when the event became valid in the real world |
| `validTo` | string | No | infinity | ISO-8601 timestamp when the event stopped being valid |
| `correlationId` | string | No | null | ID to correlate related events across services |
| `causationId` | string | No | null | ID of the event that caused this event |
| `metadata` | object | No | {} | Additional metadata (user ID, source system, etc.) |

**Response:** `201 Created`
```json
{
  "message": "Event stored successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "version": 1,
  "transactionTime": "ISO-8601 timestamp",
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `eventId` | string | Unique identifier for the event |
| `version` | integer | Version number (1 for new events) |
| `transactionTime` | string | When the event was recorded in the system |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | Missing required fields or invalid data |
| `404 Not Found` | Setup or event store does not exist |

---

### Query Events

Queries events by type, time range, and other criteria. Supports both valid time and transaction time queries.

**Use Cases:**
- Retrieve all events of a specific type
- Point-in-time queries for reporting
- Audit trail queries by correlation ID

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events`
**Handler:** `EventStoreHandler.queryEvents()`
**Service:** `DatabaseSetupService.getSetupResult()`, `BiTemporalEventStore.query()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `eventStoreName` | string | Yes | The event store name |

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `eventType` | string | No | all | Filter by event type |
| `fromTime` | string | No | - | ISO-8601 start of valid time range |
| `toTime` | string | No | - | ISO-8601 end of valid time range |
| `asOfTransactionTime` | string | No | now | Query as of this transaction time |
| `limit` | integer | No | 100 | Maximum events to return. Range: 1-1000. |
| `offset` | integer | No | 0 | Offset for pagination |
| `correlationId` | string | No | - | Filter by correlation ID |
| `causationId` | string | No | - | Filter by causation ID |

**Response:** `200 OK`
```json
{
  "message": "Events retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventCount": 0,
  "limit": 100,
  "offset": 0,
  "hasMore": false,
  "filters": {
    "eventType": "string",
    "fromTime": "string",
    "toTime": "string"
  },
  "events": [
    {
      "id": "string",
      "eventType": "string",
      "eventData": {},
      "validFrom": "ISO-8601 timestamp",
      "validTo": "ISO-8601 timestamp",
      "transactionTime": "ISO-8601 timestamp",
      "correlationId": "string",
      "causationId": "string",
      "version": 1,
      "metadata": {}
    }
  ],
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `eventCount` | integer | Number of events returned |
| `hasMore` | boolean | Whether more events exist beyond this page |
| `filters` | object | Applied filters (echoed from request) |
| `events` | array | List of matching events |
| `events[].version` | integer | Event version (>1 indicates corrections exist) |

---

### Get Event

Gets a specific event by ID. Returns the latest version of the event.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId`
**Handler:** `EventStoreHandler.getEvent()`
**Service:** `DatabaseSetupService.getSetupResult()`, `BiTemporalEventStore.get()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `eventStoreName` | string | Yes | The event store name |
| `eventId` | string | Yes | The event ID |

**Response:** `200 OK`
```json
{
  "message": "Event retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "event": {
    "id": "string",
    "eventType": "string",
    "eventData": {},
    "validFrom": "ISO-8601 timestamp",
    "validTo": "ISO-8601 timestamp",
    "transactionTime": "ISO-8601 timestamp",
    "correlationId": "string",
    "causationId": "string",
    "version": 1,
    "metadata": {}
  },
  "timestamp": 0
}
```

---

### Get Event Store Statistics

Gets statistics for an event store.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/stats`  
**Handler:** `EventStoreHandler.getStats()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Response:** `200 OK`
```json
{
  "message": "Event store statistics retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "stats": {
    "eventStoreName": "string",
    "totalEvents": 0,
    "totalCorrections": 0,
    "eventCountsByType": {}
  },
  "timestamp": 0
}
```

---

### Get All Event Versions

Gets all versions of an event (bi-temporal history).

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions`
**Handler:** `EventStoreHandler.getAllVersions()`
**Service:** `BiTemporalEventStore.getAllVersions()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name
- `eventId` (string, required): The event ID

**Response:** `200 OK`
```json
{
  "message": "Event versions retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "versionCount": 0,
  "versions": [
    {
      "id": "string",
      "eventType": "string",
      "eventData": {},
      "validFrom": "ISO-8601 timestamp",
      "validTo": "ISO-8601 timestamp",
      "transactionTime": "ISO-8601 timestamp",
      "version": 1
    }
  ],
  "timestamp": 0
}
```

---

### Get Event As-Of Transaction Time

Gets the event state as it was known at a specific transaction time.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at`
**Handler:** `EventStoreHandler.getAsOfTransactionTime()`
**Service:** `BiTemporalEventStore.getAsOfTransactionTime()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name
- `eventId` (string, required): The event ID

**Query Parameters:**
- `transactionTime` (string, required): ISO-8601 timestamp

**Response:** `200 OK`
```json
{
  "message": "Event retrieved as of transaction time",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "asOfTransactionTime": "ISO-8601 timestamp",
  "event": {
    "id": "string",
    "eventType": "string",
    "eventData": {},
    "validFrom": "ISO-8601 timestamp",
    "validTo": "ISO-8601 timestamp",
    "transactionTime": "ISO-8601 timestamp",
    "version": 1
  },
  "timestamp": 0
}
```

---

### Append Correction

Appends a correction to an existing event (bi-temporal correction).

**Endpoint:** `POST /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections`
**Handler:** `EventStoreHandler.appendCorrection()`
**Service:** `BiTemporalEventStore.appendCorrection()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name
- `eventId` (string, required): The event ID to correct

**Request Body:**
```json
{
  "eventData": {},
  "validFrom": "ISO-8601 timestamp",
  "validTo": "ISO-8601 timestamp",
  "correctionReason": "string"
}
```

**Response:** `200 OK`
```json
{
  "message": "Correction appended successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "newVersion": 2,
  "transactionTime": "ISO-8601 timestamp",
  "timestamp": 0
}
```

---

### Stream Events (SSE)

Streams events in real-time via Server-Sent Events.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events/stream`
**Handler:** `EventStoreHandler.handleEventStream()`
**Service:** `BiTemporalEventStore` with LISTEN/NOTIFY

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Query Parameters:**
- `eventType` (string, optional): Filter by event type
- `fromTime` (string, optional): Start streaming from this timestamp

**Response:** `200 OK` (Content-Type: text/event-stream)

**Event Stream Format:**
```
event: connection
data: {"connectionId":"sse-1","eventStoreName":"string","setupId":"string","timestamp":0}

event: event
data: {"id":"string","eventType":"string","eventData":{},"validFrom":"timestamp","transactionTime":"timestamp"}

event: heartbeat
data: {"timestamp":0}

event: error
data: {"error":"string"}
```

---

## Per-Setup Health Endpoints

Per-setup health endpoints provide detailed health information for individual setups and their components. Use these for monitoring, alerting, and debugging.

**Key Concepts:**
- **Overall Health**: Aggregate health status of all components
- **Component Health**: Individual health status of database, queues, event stores
- **Health Status**: `UP` (healthy), `DOWN` (unhealthy), `DEGRADED` (partially healthy)

**Health Check Components:**
- **Database**: PostgreSQL connection pool health
- **Queues**: Individual queue health (message backlog, consumer count)
- **Event Stores**: Event store health (storage, indexing)
- **Consumers**: Active consumer connections

**Integration with Monitoring:**
- Use `/health` endpoint for load balancer health checks
- Use `/health/components` for detailed Prometheus/Grafana dashboards
- HTTP 200 = healthy, HTTP 503 = unhealthy

### Get Overall Health

Gets aggregate health status for a setup. Returns UP only if all critical components are healthy.

**Use Cases:**
- Load balancer health checks
- Kubernetes liveness/readiness probes
- Quick health verification

**Endpoint:** `GET /api/v1/setups/:setupId/health`
**Handler:** `HealthHandler.getOverallHealth()`
**Service:** `HealthService.getOverallHealthAsync()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK` (healthy) or `503 Service Unavailable` (unhealthy)
```json
{
  "status": "UP|DOWN|DEGRADED",
  "healthy": true,
  "setupId": "string",
  "components": {
    "database": "UP",
    "queues": "UP",
    "eventStores": "UP"
  },
  "details": {
    "activeConnections": 5,
    "pendingMessages": 100,
    "consumerCount": 3
  },
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `status` | enum | `UP` (all healthy), `DOWN` (critical failure), `DEGRADED` (some issues) |
| `healthy` | boolean | Simple boolean for programmatic checks |
| `components` | object | Status of each component category |
| `details` | object | Additional metrics for debugging |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |
| `503 Service Unavailable` | Setup is unhealthy (response body contains details) |

---

### List Component Health

Lists detailed health status of all components in a setup. Provides granular information for debugging and monitoring.

**Use Cases:**
- Detailed monitoring dashboards
- Identifying specific component failures
- Capacity planning

**Endpoint:** `GET /api/v1/setups/:setupId/health/components`
**Handler:** `HealthHandler.listComponentHealth()`
**Service:** `HealthService.getAllComponentHealth()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`
```json
{
  "components": [
    {
      "name": "database",
      "type": "infrastructure",
      "status": "UP|DOWN|DEGRADED",
      "details": {
        "connectionPool": "healthy",
        "activeConnections": 5,
        "maxConnections": 20,
        "waitingRequests": 0
      },
      "lastChecked": 0
    },
    {
      "name": "queue-orders",
      "type": "queue",
      "status": "UP",
      "details": {
        "pendingMessages": 10,
        "inFlightMessages": 2,
        "consumerCount": 3,
        "oldestMessageAge": 5000
      },
      "lastChecked": 0
    },
    {
      "name": "eventstore-audit",
      "type": "eventstore",
      "status": "UP",
      "details": {
        "totalEvents": 1000,
        "lastEventTime": 0
      },
      "lastChecked": 0
    }
  ],
  "count": 3,
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `components` | array | List of all components |
| `components[].name` | string | Component identifier |
| `components[].type` | enum | `infrastructure`, `queue`, `eventstore`, `consumer` |
| `components[].status` | enum | Health status |
| `components[].details` | object | Component-specific metrics |
| `components[].lastChecked` | integer | When health was last verified |

---

### Get Component Health

Gets detailed health status of a specific component.

**Endpoint:** `GET /api/v1/setups/:setupId/health/components/:name`
**Handler:** `HealthHandler.getComponentHealth()`
**Service:** `HealthService.getComponentHealth()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `name` | string | Yes | The component name (e.g., "database", "queue-orders") |

**Response:** `200 OK`
```json
{
  "name": "database",
  "type": "infrastructure",
  "status": "UP|DOWN|DEGRADED",
  "details": {
    "connectionPool": "healthy",
    "activeConnections": 5,
    "maxConnections": 20,
    "idleConnections": 15,
    "waitingRequests": 0,
    "averageAcquisitionTime": 2
  },
  "history": [
    {
      "timestamp": 0,
      "status": "UP"
    }
  ],
  "lastChecked": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `history` | array | Recent health check history (last 10 checks) |

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Component does not exist |

---

## Management API Endpoints

Management API endpoints provide system-wide visibility and administrative operations across all setups. These are primarily used for operational dashboards and administrative tools.

**Key Concepts:**
- **System Overview**: Aggregate statistics across all setups
- **Cross-Setup Queries**: Find resources across multiple setups
- **Administrative Operations**: System-wide maintenance tasks

### Get System Overview

Gets overall system statistics and overview across all setups. Useful for high-level monitoring dashboards.

**Endpoint:** `GET /api/v1/management/overview`
**Handler:** `ManagementApiHandler.getSystemOverview()`
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "systemStats": {
    "totalSetups": 0,
    "totalQueues": 0,
    "totalConsumerGroups": 0,
    "totalEventStores": 0,
    "totalMessages": 0,
    "messagesPerSecond": 0.0,
    "activeConnections": 0,
    "uptime": "string",
    "startTime": 0
  },
  "queueSummary": {
    "total": 0,
    "active": 0,
    "idle": 0,
    "error": 0
  },
  "consumerGroupSummary": {
    "total": 0,
    "active": 0,
    "members": 0
  },
  "eventStoreSummary": {
    "total": 0,
    "events": 0,
    "corrections": 0
  },
  "recentActivity": [],
  "timestamp": 0
}
```

---

### Get All Queues

Gets a list of all queues across all setups.

**Endpoint:** `GET /api/v1/management/queues`  
**Handler:** `ManagementApiHandler.getQueues()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Queues retrieved successfully",
  "queueCount": 0,
  "queues": [
    {
      "name": "string",
      "setup": "string",
      "implementationType": "peegeeq-native|peegeeq-outbox",
      "status": "active|idle|error",
      "messages": 0,
      "consumers": 0,
      "messageRate": 0.0,
      "consumerRate": 0.0,
      "durability": "durable",
      "autoDelete": false,
      "createdAt": "timestamp",
      "lastActivity": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Queue

Creates a new queue in a setup.

**Endpoint:** `POST /api/v1/management/queues`  
**Handler:** `ManagementApiHandler.createQueue()`  
**Service:** `DatabaseSetupService.addQueue()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Response:** `201 Created`
```json
{
  "message": "Queue created successfully",
  "queueName": "string",
  "setupId": "string",
  "queueId": "string",
  "timestamp": 0
}
```

---

### Update Queue

Updates queue configuration.

**Endpoint:** `PUT /api/v1/management/queues/:queueId`  
**Handler:** `ManagementApiHandler.updateQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `queueId` (string, required): Queue ID in format "setupId-queueName"

**Request Body:**
```json
{
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Response:** `200 OK`
```json
{
  "message": "Queue configuration updated successfully",
  "queueId": "string",
  "setupId": "string",
  "queueName": "string",
  "note": "Configuration updates are applied to runtime settings",
  "timestamp": 0
}
```

---

### Delete Queue

Deletes a queue from a setup.

**Endpoint:** `DELETE /api/v1/management/queues/:queueId`  
**Handler:** `ManagementApiHandler.deleteQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `queueId` (string, required): Queue ID in format "setupId-queueName"

**Response:** `200 OK`
```json
{
  "message": "Queue deleted successfully",
  "queueId": "string",
  "setupId": "string",
  "queueName": "string",
  "note": "Queue resources have been cleaned up",
  "timestamp": 0
}
```

---

### Get All Consumer Groups

Gets a list of all consumer groups across all queues.

**Endpoint:** `GET /api/v1/management/consumer-groups`  
**Handler:** `ManagementApiHandler.getConsumerGroups()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Consumer groups retrieved successfully",
  "groupCount": 0,
  "consumerGroups": [
    {
      "name": "string",
      "setup": "string",
      "queueName": "string",
      "implementationType": "string",
      "members": 0,
      "status": "active|error",
      "partition": 0,
      "lag": 0,
      "createdAt": "timestamp",
      "lastRebalance": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Consumer Group (Management)

Creates a new consumer group via management API.

**Endpoint:** `POST /api/v1/management/consumer-groups`  
**Handler:** `ManagementApiHandler.createConsumerGroup()`  
**Service:** `QueueFactory.createConsumerGroup()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "queueName": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Consumer group created successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "groupId": "string",
  "timestamp": 0
}
```

---

### Delete Consumer Group (Management)

Deletes a consumer group via management API.

**Endpoint:** `DELETE /api/v1/management/consumer-groups/:groupId`  
**Handler:** `ManagementApiHandler.deleteConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `groupId` (string, required): Group ID in format "setupId-groupName"

**Response:** `200 OK`
```json
{
  "message": "Consumer group deleted successfully",
  "groupId": "string",
  "setupId": "string",
  "groupName": "string",
  "note": "Consumer group has been stopped and cleaned up",
  "timestamp": 0
}
```

---

### Get All Event Stores

Gets a list of all event stores across all setups.

**Endpoint:** `GET /api/v1/management/event-stores`  
**Handler:** `ManagementApiHandler.getEventStores()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Event stores retrieved successfully",
  "eventStoreCount": 0,
  "eventStores": [
    {
      "name": "string",
      "setup": "string",
      "events": 0,
      "aggregates": 0,
      "corrections": 0,
      "biTemporal": true,
      "retention": "365d",
      "status": "active",
      "createdAt": "timestamp",
      "lastEvent": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Event Store

Creates a new event store in a setup.

**Endpoint:** `POST /api/v1/management/event-stores`  
**Handler:** `ManagementApiHandler.createEventStore()`  
**Service:** `DatabaseSetupService.addEventStore()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "notificationPrefix": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Event store created successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "storeId": "string",
  "timestamp": 0
}
```

---

### Delete Event Store

Deletes an event store from a setup.

**Endpoint:** `DELETE /api/v1/management/event-stores/:storeId`  
**Handler:** `ManagementApiHandler.deleteEventStore()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `storeId` (string, required): Store ID in format "setupId-storeName"

**Response:** `200 OK`
```json
{
  "message": "Event store deleted successfully",
  "storeId": "string",
  "setupId": "string",
  "storeName": "string",
  "note": "Event store and associated data have been removed",
  "timestamp": 0
}
```

---

### Get Messages

Gets messages for browsing (management view).

**Endpoint:** `GET /api/v1/management/messages`  
**Handler:** `ManagementApiHandler.getMessages()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Query Parameters:**
- `setup` (string, optional): Filter by setup ID
- `queue` (string, optional): Filter by queue name
- `limit` (number, optional): Maximum number of messages
- `offset` (number, optional): Offset for pagination

**Response:** `200 OK`
```json
{
  "message": "Messages retrieved successfully",
  "messageCount": 0,
  "messages": [],
  "timestamp": 0
}
```

---

### Get System Metrics

Gets real-time system metrics.

**Endpoint:** `GET /api/v1/management/metrics`  
**Handler:** `ManagementApiHandler.getMetrics()`  
**Service:** Internal metrics cache

**Response:** `200 OK`
```json
{
  "timestamp": 0,
  "uptime": 0,
  "memoryUsed": 0,
  "memoryTotal": 0,
  "memoryMax": 0,
  "cpuCores": 0,
  "threadsActive": 0,
  "messagesPerSecond": 0.0,
  "activeConnections": 0,
  "totalMessages": 0
}
```

---

## Health & Metrics Endpoints

### Health Check

Basic health check endpoint.

**Endpoint:** `GET /health`  
**Handler:** Inline handler in `PeeGeeQRestServer.createRouter()`  
**Service:** None

**Response:** `200 OK`
```json
{
  "status": "UP",
  "service": "peegeeq-rest-api"
}
```

---

### Health Check (Management)

Enhanced health check for management UI.

**Endpoint:** `GET /api/v1/health`  
**Handler:** `ManagementApiHandler.getHealth()`  
**Service:** Internal system information

**Response:** `200 OK`
```json
{
  "status": "UP",
  "timestamp": "ISO-8601 timestamp",
  "uptime": "string",
  "version": "1.0.0",
  "build": "Phase-5-Management-UI"
}
```

---

### Metrics Endpoint

Prometheus-compatible metrics endpoint.

**Endpoint:** `GET /metrics`  
**Handler:** Inline handler in `PeeGeeQRestServer.createRouter()`  
**Service:** Micrometer MeterRegistry

**Response:** `200 OK` (Content-Type: text/plain; version=0.0.4)
```
# HELP peegeeq_http_requests_total Total HTTP requests
# TYPE peegeeq_http_requests_total counter
peegeeq_http_requests_total 0
# HELP peegeeq_active_connections Active connections
# TYPE peegeeq_active_connections gauge
peegeeq_active_connections 0
# HELP peegeeq_messages_sent_total Total messages sent
# TYPE peegeeq_messages_sent_total counter
peegeeq_messages_sent_total 0
```

---

## Real-time Streaming Endpoints

### Server-Sent Events (SSE) Stream

Streams queue messages in real-time via Server-Sent Events.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stream`  
**Handler:** `ServerSentEventsHandler.handleQueueStream()`  
**Service:** `DatabaseSetupService.getSetupResult()` and `QueueFactory.createConsumer()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `consumerGroup` (string, optional): Consumer group name
- `autoAck` (boolean, optional): Auto-acknowledge messages (default: false)

**Response:** `200 OK` (Content-Type: text/event-stream)

**Event Stream Format:**
```
event: message
data: {"messageId":"...","payload":{...},"headers":{...}}

event: heartbeat
data: {"timestamp":1234567890}

event: error
data: {"error":"..."}

event: end
data: {"reason":"..."}
```

---

### WebSocket Stream

Streams queue messages in real-time via WebSocket.

**Endpoint:** `ws://localhost:8080/ws/queues/:setupId/:queueName`  
**Handler:** `WebSocketHandler.handleQueueStream()`  
**Service:** `DatabaseSetupService.getSetupResult()` and `QueueFactory.createConsumer()`

**Path Parameters:**
- `setupId` (string): The setup ID (in WebSocket path)
- `queueName` (string): The queue name (in WebSocket path)

**WebSocket Message Format (Incoming):**
```json
{
  "action": "subscribe",
  "consumerGroup": "string",
  "autoAck": false
}
```

**WebSocket Message Format (Outgoing):**
```json
{
  "type": "message",
  "messageId": "string",
  "payload": {},
  "headers": {},
  "timestamp": 0
}
```

---

## Services Used

### DatabaseSetupService

Primary service for managing database setups, queues, and event stores.

**Key Methods:**
- `createCompleteSetup(DatabaseSetupRequest)` - Creates a complete database setup
- `destroySetup(String setupId)` - Destroys a setup and cleans up resources
- `getSetupStatus(String setupId)` - Gets the status of a setup
- `getSetupResult(String setupId)` - Gets complete setup result with factories
- `addQueue(String setupId, QueueConfig)` - Adds a queue to an existing setup
- `addEventStore(String setupId, EventStoreConfig)` - Adds an event store to a setup
- `getAllActiveSetupIds()` - Gets all active setup IDs

---

### QueueFactory

Factory for creating queue producers and consumers.

**Key Methods:**
- `createProducer(String queueName, Class<T>)` - Creates a message producer
- `createConsumer(String queueName, Class<T>)` - Creates a message consumer
- `createConsumerGroup(String groupName, String queueName, Class<T>)` - Creates a consumer group
- `isHealthy()` - Checks if the queue factory is healthy
- `getImplementationType()` - Gets the implementation type (peegeeq-native or peegeeq-outbox)
- `close()` - Closes the queue factory and releases resources

---

### MessageProducer

Interface for sending messages to queues.

**Key Methods:**
- `send(T message, Map<String,String> headers, String correlationId, String messageGroup)` - Sends a message
- `close()` - Closes the producer

---

### MessageConsumer

Interface for consuming messages from queues.

**Key Methods:**
- `start()` - Starts the consumer
- `stop()` - Stops the consumer
- `close()` - Closes the consumer

---

## Error Responses

All endpoints return standard error responses in the following format:

```json
{
  "error": "Error message description",
  "timestamp": 1234567890
}
```

**Common HTTP Status Codes:**
- `200 OK` - Request successful
- `201 Created` - Resource created successfully
- `204 No Content` - Request successful, no content to return
- `207 Multi-Status` - Batch operation with partial success
- `400 Bad Request` - Invalid request format or parameters
- `404 Not Found` - Resource not found
- `409 Conflict` - Resource already exists or conflict
- `500 Internal Server Error` - Server error
- `501 Not Implemented` - Feature not yet implemented

---

## Implementation Types

PeeGeeQ supports multiple queue implementation types:

1. **peegeeq-native**: Native PostgreSQL-based queue implementation with high performance
2. **peegeeq-outbox**: Transactional outbox pattern implementation for reliable messaging

The implementation type is determined during setup creation and affects message delivery guarantees and performance characteristics.

---

## Load Balancing Strategies

Consumer groups support different load balancing strategies:

1. **ROUND_ROBIN**: Messages are distributed evenly across all members in rotation
2. **STICKY**: Messages with the same message group ID are always sent to the same member
3. **LEAST_LOADED**: Messages are sent to the member with the least current load

---

## Notes

- All timestamps are in milliseconds since epoch unless otherwise specified
- ISO-8601 format is used for date/time strings in event stores
- WebSocket connections require upgrade from HTTP
- SSE streams maintain persistent connections with automatic reconnection
- Consumer groups provide coordinated message consumption across multiple consumers
- Bi-temporal event stores support both transaction time and valid time dimensions
- Queue messages support priorities from 1 (lowest) to 10 (highest)
- Dead letter queues are automatically created when `deadLetterEnabled` is true
- Message acknowledgment is required for at-least-once delivery semantics

---

## Gap Analysis: REST API vs Core Services

This section identifies gaps, missing features, and parameter misalignments between the REST API endpoints and the underlying PeeGeeQ core service APIs.

### 1. Queue Management Gaps

#### 1.1 Missing QueueConfig Parameters

**Status:** ⚠️ **CRITICAL GAP**

The REST API only exposes a subset of QueueConfig parameters:

**Exposed in REST API:**
- `maxRetries` (Integer)
- `visibilityTimeoutSeconds` (Integer) - Note: Core API uses Duration
- `deadLetterEnabled` (Boolean)

**Missing from REST API:**
- `batchSize` (Integer, default: 10) - Controls batch processing size
- `pollingInterval` (Duration, default: 5 seconds) - Controls polling frequency
- `fifoEnabled` (Boolean, default: false) - FIFO queue ordering
- `deadLetterQueueName` (String) - Custom dead letter queue name

**Impact:** REST API consumers cannot configure batch processing, polling intervals, FIFO ordering, or custom dead letter queues.

**Recommendation:** Add these parameters to the queue creation/update endpoints.

---

#### 1.2 Duration vs Seconds Mismatch

**Status:** ⚠️ **INCONSISTENCY**

- **Core API:** Uses `java.time.Duration` for `visibilityTimeout` (default: 5 minutes)
- **REST API:** Uses integer seconds `visibilityTimeoutSeconds`

**Impact:** Loss of precision for sub-second durations, inconsistent API design.

**Recommendation:** Consider supporting ISO-8601 duration format in REST API (e.g., "PT5M") or document the conversion clearly.

---

#### 1.3 Missing Consumer Configuration

**Status:** ⚠️ **MAJOR GAP**

The core `QueueFactory.createConsumer()` method supports custom consumer configuration:

```java
<T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig)
```

**REST API:** No endpoint supports consumer configuration (polling vs LISTEN/NOTIFY modes).

**Impact:** REST API consumers cannot configure advanced consumer behaviors like switching between polling and push-based consumption.

**Recommendation:** Add consumer configuration parameters to the subscription/consumption endpoints.

---

### 2. Message Operations Gaps

#### 2.1 Missing Message Acknowledgment Features

**Status:** ⚠️ **MAJOR GAP**

**Core API:** `MessageConsumer` provides push-based subscription with automatic message delivery via `MessageHandler`.

**REST API:** Implements pull-based polling with manual acknowledgment, but:
- `getNextMessage()` and `getMessages()` are **placeholder implementations**
- `acknowledgeMessage()` is **placeholder implementation**
- No negative acknowledgment (NACK) support
- No visibility timeout extension
- No message requeue with delay

**Impact:** REST API message consumption is not fully functional. Messages cannot be reliably consumed and acknowledged.

**Recommendation:** 
1. Implement actual database polling queries for message retrieval
2. Add advisory locking for message ownership
3. Implement proper acknowledgment with database updates
4. Add NACK endpoint for failed message processing
5. Add visibility timeout extension endpoint

---

#### 2.2 Missing MessageProducer Return Values

**Status:** ⚠️ **INCONSISTENCY**

**Core API:** `MessageProducer.send()` returns `CompletableFuture<Void>` - no message ID returned

**REST API:** Returns `messageId` in the response, using `correlationId` as the message ID

**Impact:** REST API creates an expectation that the core API doesn't fulfill. The `messageId` returned is actually the `correlationId`, not a database-generated message ID.

**Recommendation:** 
1. Update core API to return message IDs from `send()` methods
2. Document clearly that `messageId` in REST responses is the correlation ID
3. Consider adding a separate `databaseMessageId` field for the actual row ID

---

#### 2.3 Missing Reactive API Endpoints

**Status:** ⚠️ **FEATURE GAP**

**Core API:** Provides reactive methods (`sendReactive()`, `subscribeReactive()`) using Vert.x `Future`

**REST API:** Does not expose reactive endpoints. All endpoints use blocking `CompletableFuture` style.

**Impact:** Cannot leverage reactive programming benefits in REST API.

**Recommendation:** This is acceptable for REST APIs. Reactive programming is typically used internally, not exposed via REST.

---

### 3. Consumer Group Gaps

#### 3.1 Missing SubscriptionOptions

**Status:** ⚠️ **CRITICAL GAP**

**Core API:** `ConsumerGroup.start(SubscriptionOptions)` supports:
- `startPosition` (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
- `startFromMessageId` (Long) - Start from specific message
- `startFromTimestamp` (Instant) - Start from specific time
- `heartbeatIntervalSeconds` (Integer, default: 60)
- `heartbeatTimeoutSeconds` (Integer, default: 300)

**REST API:** 
- `createConsumerGroup()` endpoint has no subscription options
- `joinConsumerGroup()` endpoint has no subscription options
- No way to specify start position for late-joining consumers

**Impact:** Cannot implement backfill scenarios or replay messages from specific points. All consumers can only consume from NOW.

**Recommendation:** Add subscription options to consumer group creation and join endpoints.

---

#### 3.2 Missing ConsumerGroup Methods

**Status:** ⚠️ **FEATURE GAP**

**Core API** provides these methods not exposed in REST API:
- `addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter)` - Per-consumer filtering
- `setMessageHandler(MessageHandler<T> handler)` - Simple single-handler setup
- `setGroupFilter(Predicate<Message<T>> groupFilter)` - Group-level filtering
- `getGroupFilter()` - Get current group filter
- `getStats()` - Detailed consumer group statistics

**REST API:** Only exposes basic add/remove member operations, no filtering or statistics.

**Impact:** Cannot implement message filtering at the group or consumer level via REST API.

**Recommendation:** Add filtering and statistics endpoints.

---

#### 3.3 Missing ConsumerGroupStats

**Status:** ⚠️ **FEATURE GAP**

**Core API:** Provides `ConsumerGroupStats` interface (details not in current codebase snapshot)

**REST API:** Returns basic member count and status, no detailed statistics.

**Impact:** No visibility into consumer group performance metrics.

**Recommendation:** Implement full statistics endpoint matching core API capabilities.

---

### 4. Event Store Gaps

#### 4.1 Missing EventStoreConfig Parameters

**Status:** ⚠️ **MAJOR GAP**

**REST API** only exposes:
- `eventStoreName` (String)
- `tableName` (String)
- `biTemporalEnabled` (Boolean)
- `notificationPrefix` (String)

**Core API** also supports:
- `queryLimit` (Integer, default: 1000) - Max query results
- `metricsEnabled` (Boolean, default: true) - Enable metrics collection
- `eventType` (Class<?>, default: Object.class) - Event payload type
- `partitionStrategy` (String, default: "monthly") - Table partitioning strategy

**Impact:** Cannot configure query limits, metrics, event types, or partitioning via REST API.

**Recommendation:** Add missing parameters to event store creation endpoint.

---

#### 4.2 Missing Event Store Methods

**Status:** ⚠️ **CRITICAL GAP**

**Core API** provides extensive event store operations:

**Missing from REST API:**
- `append()` variations with different parameter combinations
- `appendCorrection()` - Bi-temporal corrections with `correctionReason`
- `appendInTransaction()` - Transaction-aware event appending (4 variations)
- `getById(String eventId)` - Get specific event
- `getAllVersions(String eventId)` - Get all versions including corrections
- `getAsOfTransactionTime(String eventId, Instant asOfTransactionTime)` - Point-in-time query
- `subscribe()` methods - Real-time event subscription (2 variations)
- `unsubscribe()` - Unsubscribe from events
- `getStats()` - Event store statistics with detailed interface

**REST API** provides:
- Basic `storeEvent()` - Limited to single append
- `queryEvents()` - Basic query with filters
- `getEvent()` - Get single event (placeholder implementation)
- `getStats()` - Basic statistics (placeholder implementation)

**Impact:** 
- Cannot create bi-temporal corrections via REST API
- Cannot participate in transactions
- No version history queries
- No real-time event subscription (except via WebSocket/SSE which are separate)

**Recommendation:** Add comprehensive event store endpoints matching core API.

---

#### 4.3 Missing EventQuery Parameters

**Status:** ⚠️ **MAJOR GAP**

**REST API** query parameters:
- `eventType` (String)
- `fromTime` (ISO-8601)
- `toTime` (ISO-8601)
- `limit` (Integer, max: 1000)
- `offset` (Integer)
- `correlationId` (String)
- `causationId` (String)

**Core API** `EventQuery` also supports:
- `aggregateId` (String) - Group related events
- `validTimeRange` (TemporalRange) - Bi-temporal valid time filtering
- `transactionTimeRange` (TemporalRange) - Bi-temporal transaction time filtering
- `headerFilters` (Map<String, String>) - Filter by custom headers
- `sortOrder` (Enum: 6 options) - Various sort orders
- `includeCorrections` (Boolean, default: true) - Include/exclude corrections
- `versionRange` (Long min/max) - Filter by version numbers

**Impact:** Cannot perform bi-temporal queries via REST API. Missing critical filtering capabilities.

**Recommendation:** Add bi-temporal temporal ranges and additional filters to query endpoint.

---

#### 4.4 Placeholder Implementations

**Status:** 🔴 **BLOCKER**

These event store methods are **placeholder implementations** with no actual database queries:

- `queryEvents()` - Returns sample/mock events
- `getEvent()` - Returns sample event or null
- `getStats()` - Returns sample statistics

**Impact:** Event store REST API is **NOT FUNCTIONAL** for production use.

**Recommendation:** Implement actual database queries before production use.

---

### 5. Database Setup Gaps

#### 5.1 Complete Coverage

**Status:** ✅ **COMPLETE**

The Database Setup endpoints have good coverage:
- `createCompleteSetup()` ✅
- `destroySetup()` ✅
- `getSetupStatus()` ✅
- `getSetupResult()` ✅ (via internal use)
- `addQueue()` ✅
- `addEventStore()` ✅
- `getAllActiveSetupIds()` ✅ (via internal use)

All reactive variants are internal convenience methods - not needed in REST API.

---

### 6. Streaming Endpoints Gaps

#### 6.1 Server-Sent Events (SSE)

**Status:** ⚠️ **PARTIAL IMPLEMENTATION**

**REST API:** Provides SSE endpoint `GET /api/v1/queues/:setupId/:queueName/stream`

**Core API:** Uses `MessageConsumer.subscribe(MessageHandler)` for push-based consumption

**Gap:** SSE handler creates consumers but:
- No consumer configuration support
- No start position control
- Limited error handling
- No reconnection token support

**Recommendation:** Add subscription options and reconnection tokens to SSE endpoint.

---

#### 6.2 WebSocket Stream

**Status:** ⚠️ **PARTIAL IMPLEMENTATION**

**REST API:** Provides WebSocket endpoint `ws://localhost:8080/ws/queues/:setupId/:queueName`

**Core API:** Uses `MessageConsumer.subscribe(MessageHandler)` for push-based consumption

**Gap:** WebSocket handler:
- No consumer configuration support
- No start position control
- Limited protocol for control messages
- No consumer group support in WebSocket mode

**Recommendation:** Define comprehensive WebSocket protocol with control messages.

---

### 7. Management API Gaps

#### 7.1 Metrics Integration

**Status:** ⚠️ **INCOMPLETE**

**REST API:** 
- Basic Prometheus metrics endpoint with hardcoded values
- `getMetrics()` endpoint with cached system metrics

**Core API:** Has `MetricsProvider` interface (not examined in detail)

**Gap:** 
- Metrics are not integrated with actual queue/event store operations
- No per-queue, per-consumer-group, or per-event-store metrics
- Prometheus endpoint returns placeholder data

**Recommendation:** Integrate with core `MetricsProvider` for real metrics.

---

#### 7.2 Real-time Statistics

**Status:** 🔴 **NOT IMPLEMENTED**

**REST API Methods Return Zero/Empty:**
- `getRealMessageCount()` - Always returns 0
- `getRealConsumerCount()` - Always returns 0
- `getRealMessageRate()` - Always returns 0.0
- `getRealConsumerRate()` - Always returns 0.0
- `getRealEventCount()` - Always returns 0
- `getRealAggregateCount()` - Always returns 0
- `getRealCorrectionCount()` - Always returns 0

**Impact:** Management UI shows no real data.

**Recommendation:** Implement database queries for real-time statistics.

---

### 8. Missing Core Features in REST API

#### 8.1 Message Filtering

**Status:** 🔴 **NOT AVAILABLE**

**Core API:** `MessageFilter` interface for filtering messages

**REST API:** No message filtering support

**Recommendation:** Add filtering parameters to consumption endpoints.

---

#### 8.2 Message Handlers

**Status:** ⚠️ **NOT APPLICABLE**

**Core API:** `MessageHandler<T>` for push-based async message processing

**REST API:** Uses pull-based HTTP polling - handlers not applicable

**Decision:** This is an expected difference between push and pull models.

---

#### 8.3 Transaction Participation

**Status:** 🔴 **NOT AVAILABLE**

**Core API:** 
- `EventStore.appendInTransaction()` - 4 variations for ACID guarantees
- Requires `io.vertx.sqlclient.SqlConnection` parameter

**REST API:** No transaction support

**Impact:** Cannot ensure ACID guarantees between business operations and event logging via REST API.

**Recommendation:** This is a limitation of REST APIs. Document that transactional operations require direct SDK usage.

---

#### 8.4 CloudEvents Support

**Status:** ⚠️ **PARTIAL**

**Core API:** Detects CloudEvents Jackson module at runtime

**REST API:** Message sending supports custom headers and payloads but no explicit CloudEvents schema validation

**Recommendation:** Add CloudEvents-specific endpoints or document CloudEvents payload structure.

---

### 9. Parameter Type Conversions

#### 9.1 Duration to Seconds

**Issue:** Core API uses `java.time.Duration`, REST API uses integer seconds

**Affected Parameters:**
- `visibilityTimeout` (Core: Duration, REST: visibilityTimeoutSeconds)
- `pollingInterval` (Core: Duration, REST: not exposed)

**Recommendation:** Document conversion clearly or support ISO-8601 duration strings.

---

#### 9.2 Instant to String

**Issue:** Core API uses `java.time.Instant`, REST API uses ISO-8601 strings

**Affected Parameters:**
- All timestamp fields in event queries
- Valid time and transaction time in events

**Status:** ✅ **ACCEPTABLE** - Standard REST API practice

---

#### 9.3 Enum to String

**Issue:** Core API uses Java enums, REST API uses strings

**Affected:**
- `LoadBalancingStrategy` (ROUND_ROBIN, STICKY, LEAST_LOADED)
- `EventQuery.SortOrder` (6 enum values)
- `StartPosition` (4 enum values)

**Status:** ✅ **ACCEPTABLE** - Standard REST API practice with validation needed

---

### 10. Summary of Critical Gaps

#### 🔴 Blockers (Must Fix Before Production)

1. **Message consumption endpoints are placeholders** - No actual polling implementation
2. **Message acknowledgment is placeholder** - No actual database updates
3. **Event store query is placeholder** - Returns mock data
4. **Event store stats is placeholder** - Returns mock data
5. **All real-time statistics return zero** - No database queries implemented

#### ⚠️ High Priority (Significant Feature Gaps)

1. Missing QueueConfig parameters (batchSize, pollingInterval, fifoEnabled, deadLetterQueueName)
2. Missing SubscriptionOptions in consumer groups (start position, heartbeats)
3. Missing EventStoreConfig parameters (queryLimit, metricsEnabled, partitionStrategy)
4. Missing bi-temporal query capabilities (temporal ranges, corrections)
5. Missing consumer group filtering (per-consumer and group-level)
6. Missing event correction endpoints (appendCorrection)
7. No transaction support for event appending

#### ℹ️ Medium Priority (Nice to Have)

1. Consumer configuration support
2. Message filtering in consumption
3. Extended statistics and metrics
4. Version history queries for events
5. Real-time event subscription via REST
6. CloudEvents explicit support
7. Negative acknowledgment (NACK) support

#### ✅ Low Priority (Acceptable As-Is)

1. Reactive API methods (internal use only)
2. Type conversions (Duration, Instant, Enum) - standard REST practice
3. Message handlers (push vs pull model difference)
4. Transaction participation (SDK-only feature)

---

### 11. Alignment Recommendations

#### Phase 1: Critical Fixes (Blockers)
1. Implement actual database queries for message polling
2. Implement actual database queries for acknowledgment
3. Implement actual database queries for event store operations
4. Implement actual database queries for statistics

#### Phase 2: High Priority Features
1. Add missing configuration parameters to all creation endpoints
2. Add subscription options to consumer groups
3. Add bi-temporal query support to event store
4. Add correction endpoints to event store
5. Add filtering support to consumer groups

#### Phase 3: Medium Priority Enhancements
1. Add consumer configuration to message consumption
2. Add message filtering to consumption endpoints
3. Enhance statistics and metrics integration
4. Add version history queries
5. Document CloudEvents support

#### Phase 4: Documentation
1. Document all parameter type conversions
2. Document feature limitations (transactions, reactive APIs)
3. Create migration guide from SDK to REST API
4. Add examples for common use cases

---

**Document Version:** 1.0  
**Last Updated:** 2025-07-19  
**Author:** Mark Andrew Ray-Smith Cityline Ltd
