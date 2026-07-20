# PeeGeeQ REST API Reference

**© Mark Andrew Ray-Smith Cityline Ltd 2025**
Version: 1.0  
Date: Jun 14, 2026  
Author: Mark Andrew Ray-Smith Cityline Ltd

## Table of Contents

1. [Database Setup Endpoints](#database-setup-endpoints)
2. [Queue Management Endpoints](#queue-management-endpoints)
3. [Queue Message Operations](#queue-message-operations)
4. [Consumer Group Endpoints](#consumer-group-endpoints)
5. [Consumer Group Subscription Options](#consumer-group-subscription-options-phase-32)
6. [Webhook Subscription Endpoints](#webhook-subscription-endpoints)
7. [Dead Letter Queue Endpoints](#dead-letter-queue-endpoints)
8. [Subscription Lifecycle Endpoints](#subscription-lifecycle-endpoints)
9. [Consumer Alert Endpoints](#consumer-alert-endpoints)
10. [Event Store Endpoints](#event-store-endpoints)
11. [Per-Setup Health Endpoints](#per-setup-health-endpoints)
12. [Management API Endpoints](#management-api-endpoints)
13. [Health & Metrics Endpoints](#health--metrics-endpoints)
14. [Real-time Streaming Endpoints](#real-time-streaming-endpoints)

---

## Database Setup Endpoints

Database setups are the foundational resource in PeeGeeQ. A setup represents an isolated messaging environment with its own queues, event stores, and configuration. Each setup creates dedicated PostgreSQL tables and manages its own connection pool.

**Key Concepts:**
- **Setup ID**: A unique identifier for the messaging environment (e.g., "orders-service", "payments-prod")
- **Isolation**: Each setup has isolated queues and event stores - messages in one setup cannot be accessed from another
- **Lifecycle**: Setups go through states: CREATING → ACTIVE → (optionally) FAILED
- **Resource Management (changed 2026-07, Phase S)**: Deleting/detaching a setup releases its in-memory binding and connections but **never drops tables or data** — the setup can be reconnected later. The single destructive operation is the guarded [Drop Setup Database](#drop-setup-database-destructive).

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

**Create never destroys (changed 2026-07-17).** If the target database already exists, create
**refuses** with `409 Conflict` and touches nothing — it never drops or overwrites. To attach to an
existing setup, use [Connect to Existing Setup](#connect-to-existing-setup); to recreate, drop the
database first via the separate guarded [Drop Setup Database](#drop-setup-database-destructive)
operation, then create.

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

**Response:** `201 Created`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "queueCount": 0,
  "eventStoreCount": 0,
  "message": "Setup created successfully"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setupId` | string | The setup identifier (echoed from request) |
| `status` | enum | Current state: `CREATING` (initializing), `ACTIVE` (ready for use), `FAILED` (initialization error) |
| `queueCount` | integer | Number of queues created in this setup |
| `eventStoreCount` | integer | Number of event stores created in this setup |
| `message` | string | Human-readable success message |

**Error Responses:**

| Status | Condition | Response |
|:-------|:----------|:---------|
| `400 Bad Request` | Invalid setupId format or missing required fields | `{"error": "setupId must be alphanumeric"}` |
| `409 Conflict` | The setup's database already exists — create refuses, data intact. The message is actionable: attach with `POST /api/v1/database-setup/connect`, or drop the database first (a separate, guarded operation) to recreate. | `{"error": "Setup already exists: my-setup. Use POST /api/v1/database-setup/connect to attach, or drop the database first (a separate, guarded operation) to recreate."}` |
| `503 Service Unavailable` | Database connection or table creation failed | `{"error": "Failed to create setup 'my-setup': connection refused"}` |

---

### Connect to Existing Setup

Non-destructively attaches to a setup whose database and schema already exist — for example after a
backend restart, or to reach a setup provisioned by another instance. Added 2026-07 (Phase S); the
inverse operation is [Detach Setup](#detach-setup-non-destructive).

**What This Endpoint Does:**
1. Validates the PeeGeeQ schema exists (it is **never** created here — a bare database fails with 400)
2. Reconstitutes the setup's queues and event stores from its self-describing registry tables
   (`peegeeq_setup_metadata` + `peegeeq_object_registry`) — each object's exact kind
   (native / outbox / bitemporal) and configuration are recovered from the database, not the request
3. Starts the setup's manager (non-destructive) and registers the reconstituted factories
4. Touches no data — nothing is created, dropped, or modified

**Endpoint:** `POST /api/v1/database-setup/connect`
**Handler:** `DatabaseSetupHandler.connectToExistingSetup()`
**Service:** `DatabaseSetupService.connectToExistingSetup()`

**Request Body:** the same DTO as [Create Database Setup](#create-database-setup), with two
differences in meaning:

| Aspect | Create | Connect |
|:-------|:-------|:--------|
| `setupId` | a new arbitrary label | the **expected** id — validated against the value recovered from `peegeeq_setup_metadata` |
| `queues` / `eventStores` | authored by the caller | **ignored** — contents are reconstituted from the schema, never from the request |
| `databaseConfig.password` | stored in the created setup's runtime config | used to connect, **never persisted** |

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE",
  "queueCount": 0,
  "eventStoreCount": 0,
  "message": "Database setup connected successfully"
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | PeeGeeQ schema absent / not a PeeGeeQ database / recovered setupId does not match the request |
| `503 Service Unavailable` | Connection or authentication failure |

---

### Delete Setup (non-destructive teardown)

Releases a setup's in-memory binding and stops its manager. **Changed 2026-07 (Phase S): this
operation does NOT drop the database or any tables** — the data persists and the setup can be
reconnected later with [Connect to Existing Setup](#connect-to-existing-setup). The only operation
that destroys data is the separate, guarded
[Drop Setup Database](#drop-setup-database-destructive).

**What This Endpoint Does:**
1. Stops all active consumers and producers for this setup
2. Closes all SSE and WebSocket connections
3. Closes the setup's factories, manager, and connection pools
4. Removes the setup from the active in-memory registry
5. Leaves every database artifact — tables, messages, events — untouched

**Endpoint:** `DELETE /api/v1/database-setup/:setupId`
**Alternative:** `DELETE /api/v1/setups/:setupId` (RESTful alias)
**Handler:** `DatabaseSetupHandler.destroySetup()`
**Service:** `DatabaseSetupService.destroySetup()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The unique identifier of the setup to tear down |

**Response:** `204 No Content`

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `503 Service Unavailable` | A resource close genuinely failed (close failures are surfaced, not swallowed — a leaked connection is reported, not hidden) |

---

### Detach Setup (non-destructive)

The explicit, self-describing route for releasing a connected setup — the inverse of
[Connect to Existing Setup](#connect-to-existing-setup). Semantically identical to the
non-destructive Delete above; use this route when the intent is "stop managing this setup here,
keep its data".

**Endpoint:** `POST /api/v1/setups/:setupId/detach`
**Handler:** `DatabaseSetupHandler.detachSetup()`
**Service:** `DatabaseSetupService.detachSetup()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup to detach |

**Response:** `204 No Content` — the binding is released; the database is untouched.

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `503 Service Unavailable` | A resource close genuinely failed |

---

### Drop Setup Database (DESTRUCTIVE)

**The single destructive path.** Drops the setup's PostgreSQL database — irreversible. Deliberately
separate from create/delete/detach (none of which can destroy data) and guarded by a
type-to-confirm token, so a replayed request, a copy-pasted setupId, or an automation re-apply
cannot trigger it by accident. Intended for admin tooling only. Every drop is logged at WARN with a
`DESTRUCTIVE` marker.

**Endpoint:** `POST /api/v1/setups/:setupId/database/drop`
**Handler:** `DatabaseSetupHandler.dropSetupDatabase()`
**Service:** `DatabaseSetupService.dropSetupDatabase(setupId, confirmDatabaseName)`

**Request Body:**
```json
{
  "confirmDatabaseName": "string"
}
```

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `confirmDatabaseName` | string | Yes | Type-to-confirm token: must equal the setup's **actual** database name exactly, or the operation refuses with 400 and nothing is dropped |

**Behaviour:** drains live connections (`DROP DATABASE … WITH (FORCE)`), then detaches the now-dead
in-memory binding. "Recreate" is a sequence — this operation followed by create — never a create
argument.

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "message": "Database dropped successfully"
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | `confirmDatabaseName` does not match the setup's database name — **nothing dropped** |
| `404 Not Found` | Unknown setup |
| `503 Service Unavailable` | Drop failed (infrastructure error) |

---

### Get Setup Status

Retrieves the current status of a database setup. Use this to check if a setup has finished initializing or to monitor setup health.

**Use Cases:**
- Poll after creating a setup to wait for ACTIVE status
- Health monitoring dashboards
- Debugging setup initialization issues

**Endpoint:** `GET /api/v1/database-setup/:setupId/status`
**Alternative:** `GET /api/v1/setups/:setupId/status`
**Handler:** `DatabaseSetupHandler.getSetupStatus()`
**Service:** `DatabaseSetupService.getSetupStatus()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The unique identifier of the setup |

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED"
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `setupId` | string | The setup identifier |
| `status` | enum | `CREATING` - Setup is initializing tables and connections. `ACTIVE` - Setup is ready for use. `FAILED` - Setup initialization failed (check logs). |

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

### List Queues in Setup

Lists all queues in a specific setup.

**Endpoint:** `GET /api/v1/setups/:setupId/queues`
**Handler:** `DatabaseSetupHandler.listQueues()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`
```json
{
  "count": 0,
  "queues": ["string"],
  "queueDetails": [
    {
      "name": "string",
      "implementationType": "string"
    }
  ]
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |

---

### List Event Stores in Setup

Lists all event stores in a specific setup.

**Endpoint:** `GET /api/v1/setups/:setupId/eventstores`
**Handler:** `DatabaseSetupHandler.listEventStores()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`
```json
{
  "count": 0,
  "eventStores": ["string"]
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup does not exist |

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
  "count": 0,
  "setupIds": ["string"]
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `count` | integer | Total number of active setups |
| `setupIds` | array | Flat list of setup ID strings |

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

**Use Cases:**
- Monitoring queue health and availability
- Checking queue implementation type
- Alerting on queue depth thresholds
- Performance analysis

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
  "inFlightMessages": 0,
  "deadLetteredMessages": 0,
  "messagesPerSecond": 0.0,
  "avgProcessingTimeMs": 0.0,
  "successRatePercent": 0.0,
  "firstMessageAt": "ISO-8601 timestamp",
  "lastMessageAt": "ISO-8601 timestamp",
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
| `totalMessages` | integer | Total messages in queue |
| `pendingMessages` | integer | Messages waiting to be consumed |
| `processedMessages` | integer | Messages successfully processed |
| `inFlightMessages` | integer | Messages currently being processed |
| `deadLetteredMessages` | integer | Messages moved to dead letter queue |
| `messagesPerSecond` | number | Current message throughput rate |
| `avgProcessingTimeMs` | number | Average message processing time in milliseconds |
| `successRatePercent` | number | Percentage of messages successfully processed |
| `firstMessageAt` | string | ISO-8601 timestamp of the first message (optional, present when messages exist) |
| `lastMessageAt` | string | ISO-8601 timestamp of the most recent message (optional, present when messages exist) |
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

### Pause Queue

Pauses message delivery for a queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/pause`  
**Handler:** `ManagementApiHandler.pauseQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`

---

### Resume Queue

Resumes message delivery for a paused queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/resume`  
**Handler:** `ManagementApiHandler.resumeQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`

---

### Delete Queue (by name)

Deletes a queue using separate path parameters (Standard REST pattern).

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName`  
**Handler:** `ManagementApiHandler.deleteQueueByName()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`

---

### Publish Message (alias)

Alternative endpoint for sending a message to a queue. Functionally identical to Send Message.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/publish`  
**Handler:** `QueueHandler.sendMessage()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

See [Send Message](#send-message) for request/response details.

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

> **⚠️ NOT IMPLEMENTED** — This endpoint is planned but does not currently exist in the codebase. Use SSE streaming (`GET /api/v1/queues/:setupId/:queueName/stream`), WebSocket (`ws://localhost:8080/ws/queues/:setupId/:queueName`), or Webhook subscriptions for message consumption.

---

### Get Multiple Messages (Management Browsing)

Gets messages from a queue for management/browsing purposes. This is **not** a consumer polling endpoint — messages retrieved here are not locked or hidden from other consumers.

**Use Cases:**
- Management UI message browsing
- Debugging queue contents
- Inspecting message payloads

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages`
**Handler:** `ManagementApiHandler.getQueueMessages()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `queueName` | string | Yes | The queue name |

**Response:** `200 OK`

---

### Acknowledge Message

> **⚠️ NOT IMPLEMENTED** — This endpoint is planned but does not currently exist in the codebase. Message acknowledgment is handled automatically by consumer groups, SSE streams, and webhook subscriptions.

---

## Consumer Group Endpoints

### Create Consumer Group

Creates a new consumer group for a queue. Consumer groups enable coordinated message consumption across multiple consumers with load balancing.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/consumer-groups`
**Handler:** `ConsumerGroupHandler.createConsumerGroup()`
**Service:** `DatabaseSetupService.getSetupResult()`, `QueueFactory.createConsumerGroup()`, `SubscriptionService.subscribe()`

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
  "sessionTimeout": 30000,
  "subscriptionOptions": {
    "startPosition": "FROM_NOW|FROM_BEGINNING|FROM_MESSAGE_ID|FROM_TIMESTAMP",
    "startFromMessageId": 12345,
    "startFromTimestamp": "2025-11-23T00:00:00Z",
    "heartbeatIntervalSeconds": 60,
    "heartbeatTimeoutSeconds": 300
  }
}
```

**Field Descriptions:**
- `groupName` (string, required): Unique name for the consumer group
- `maxMembers` (number, optional): Maximum number of concurrent consumers (default: 10, range: 1-100)
- `loadBalancingStrategy` (string, optional): Load balancing strategy (default: "ROUND_ROBIN")
- `sessionTimeout` (number, optional): Session timeout in milliseconds (default: 30000, range: 5000-300000)
- `subscriptionOptions` (object, optional): **NEW** - Subscription configuration for the consumer group
  - `startPosition` (string, optional): Where consumers should start reading messages (default: "FROM_NOW")
    - `FROM_NOW`: Start from next new message
    - `FROM_BEGINNING`: Start from oldest available message (backfill scenario)
    - `FROM_MESSAGE_ID`: Start from specific message ID (requires `startFromMessageId`)
    - `FROM_TIMESTAMP`: Start from specific timestamp (requires `startFromTimestamp`)
  - `startFromMessageId` (number, optional): Message ID to start from when `startPosition=FROM_MESSAGE_ID`
  - `startFromTimestamp` (string, optional): ISO-8601 timestamp when `startPosition=FROM_TIMESTAMP`
  - `heartbeatIntervalSeconds` (number, optional): Seconds between heartbeats (default: 60, range: 10-300)
  - `heartbeatTimeoutSeconds` (number, optional): Seconds before consumer considered dead (default: 300, range: 60-3600)

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
  "subscriptionConfigured": true,
  "implementationType": "native",
  "timestamp": 0
}
```

**Response Fields:**
- `subscriptionConfigured` (boolean): `true` if subscription options were provided and configured, `false` otherwise
- `implementationType` (string): Queue implementation type ("native" or "outbox")

**Example: Create Consumer Group with Backfill**
```bash
curl -X POST http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups \
  -H "Content-Type: application/json" \
  -d '{
    "groupName": "analytics-team",
    "subscriptionOptions": {
      "startPosition": "FROM_BEGINNING",
      "heartbeatIntervalSeconds": 45
    }
  }'
```

**Example: Create Consumer Group with Time-Based Replay**
```bash
curl -X POST http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups \
  -H "Content-Type: application/json" \
  -d '{
    "groupName": "incident-recovery",
    "subscriptionOptions": {
      "startPosition": "FROM_TIMESTAMP",
      "startFromTimestamp": "2025-12-22T10:00:00Z"
    }
  }'
```

**Notes:**
- If `subscriptionOptions` is not provided, the consumer group is created without subscription configuration
- Subscription options can be set later using `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`
- If subscription creation fails, the consumer group is still created (subscription can be configured later)
- See "Consumer Group Subscription Options" section for detailed subscription options documentation

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

**Response:** `204 No Content`

No response body. The subscription has been deleted and message delivery stopped.

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
  "reason": "Fixed consumer bug in version 2.1"
}
```

**Request Body Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `reason` | string | No | null | Human-readable reason for reprocessing (for audit) |

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

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `reason` | string | No | null | Optional reason for deletion (audit) |

**Response:** `204 No Content`

No response body. The message has been permanently deleted.

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
  "uniqueTopics": 0,
  "uniqueTables": 0,
  "averageRetryCount": 0.0,
  "oldestFailure": "ISO-8601 timestamp",
  "newestFailure": "ISO-8601 timestamp"
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

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `retentionDays` | integer | No | 30 | Delete messages older than this many days |

**Response:** `200 OK`
```json
{
  "success": true,
  "messagesDeleted": 0,
  "retentionDays": 30
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

### Create Subscription

Creates a new subscription for a consumer group on a topic. This registers the consumer group with the subscription service for lifecycle management.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic`
**Handler:** `SubscriptionHandler.createSubscription()`
**Service:** `SubscriptionService.subscribe()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |

**Request Body:**
```json
{
  "groupName": "string",
  "startPosition": "FROM_NOW|FROM_BEGINNING|FROM_MESSAGE_ID|FROM_TIMESTAMP",
  "startFromMessageId": 12345,
  "startFromTimestamp": "2025-11-23T00:00:00Z",
  "heartbeatIntervalSeconds": 60,
  "heartbeatTimeoutSeconds": 300
}
```

**Response:** `201 Created`
```json
{
  "success": true,
  "topic": "string",
  "groupName": "string",
  "action": "subscribed",
  "subscription": {}
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `400 Bad Request` | Missing groupName or invalid parameters |
| `409 Conflict` | Subscription already exists |

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

### Force Remove Consumer Group

Forcefully removes a consumer group and cleans up orphaned resources. Use when a consumer group is in an inconsistent state and normal deletion fails.

**Endpoint:** `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove`
**Handler:** `SubscriptionHandler.forceRemoveConsumerGroup()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "success": true,
  "topic": "string",
  "groupName": "string",
  "action": "force-removed",
  "previousStatus": "string",
  "messagesDecremented": 0,
  "orphanRowsRemoved": 0,
  "messagesAutoCompleted": 0,
  "totalActions": 0
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |
| `409 Conflict` | Consumer group is still active |

---

### Start Backfill

Starts a backfill operation to replay historical messages to a consumer group.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`
**Handler:** `SubscriptionHandler.startBackfill()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Request Body (optional):**
```json
{
  "backfillScope": "PENDING_ONLY|ALL_RETAINED"
}
```

**Response:** `200 OK`

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Subscription does not exist |
| `409 Conflict` | Backfill already in progress |
| `501 Not Implemented` | Backfill not supported for this queue type |

---

### Get Backfill Progress

Gets the current progress of a backfill operation.

**Endpoint:** `GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`
**Handler:** `SubscriptionHandler.getBackfillProgress()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "topic": "string",
  "groupName": "string",
  "backfillStatus": "IN_PROGRESS|COMPLETED|CANCELLED|NOT_STARTED",
  "processedMessages": 0,
  "totalMessages": 0,
  "checkpointId": "string",
  "startedAt": "ISO-8601 timestamp",
  "completedAt": "ISO-8601 timestamp",
  "percentComplete": 0.0
}
```

---

### Cancel Backfill

Cancels an in-progress backfill operation.

**Endpoint:** `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`
**Handler:** `SubscriptionHandler.cancelBackfill()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Response:** `200 OK`
```json
{
  "success": true,
  "topic": "string",
  "groupName": "string",
  "action": "backfill_cancelled"
}
```

---

### Join Partitioned Group

Joins a partitioned consumer group (OFFSET_WATERMARK mode). Returns partition assignments for this instance.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/join`
**Handler:** `SubscriptionHandler.joinPartitionedGroup()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Request Body:**
```json
{
  "instanceId": "string"
}
```

**Response:** `200 OK` — JSON array of partition assignment objects

---

### Leave Partitioned Group

Leaves a partitioned consumer group and releases partition assignments.

**Endpoint:** `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/leave`
**Handler:** `SubscriptionHandler.leavePartitionedGroup()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Query Parameters:**
- `instanceId` (string, required): The instance ID to leave

**Response:** `200 OK`
```json
{
  "success": true,
  "topic": "string",
  "groupName": "string",
  "instanceId": "string",
  "action": "left"
}
```

---

### Get Partition Assignments

Gets current partition assignments for a consumer group instance.

**Endpoint:** `GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions`
**Handler:** `SubscriptionHandler.getPartitionAssignments()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Query Parameters:**
- `instanceId` (string, required): The instance ID

**Response:** `200 OK` — JSON array of partition assignment objects

---

### Fetch Partitioned Messages

Fetches messages from assigned partitions.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/fetch`
**Handler:** `SubscriptionHandler.fetchPartitioned()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Request Body:**
```json
{
  "partitionKey": "string",
  "batchSize": 10,
  "generation": 0
}
```

**Response:** `200 OK` — JSON array of messages

---

### Commit Partitioned Offset

Commits an offset for a partition, marking messages as processed up to that point.

**Endpoint:** `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/commit`
**Handler:** `SubscriptionHandler.commitPartitionedOffset()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |
| `topic` | string | Yes | The queue name |
| `groupName` | string | Yes | The consumer group name |

**Request Body:**
```json
{
  "partitionKey": "string",
  "offset": 0,
  "generation": 0
}
```

**Response:** `200 OK`
```json
{
  "committed": true,
  "topic": "string",
  "groupName": "string",
  "partitionKey": "string",
  "offset": 0
}
```

---

## Consumer Alert Endpoints

Consumer alerting endpoints provide dead consumer detection and blocked message statistics.

### List Dead Subscriptions

Lists consumer group subscriptions that have missed their heartbeat timeout and are considered dead.

**Endpoint:** `GET /api/v1/setups/:setupId/consumer-alerts/dead`
**Handler:** `ConsumerAlertHandler.listDeadSubscriptions()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`
```json
{
  "deadSubscriptions": [
    {
      "topic": "string",
      "groupName": "string",
      "state": "string",
      "heartbeatTimeoutSeconds": 300,
      "lastHeartbeatAt": "ISO-8601 timestamp",
      "lastActiveAt": "ISO-8601 timestamp"
    }
  ],
  "totalDead": 0
}
```

---

### Get Consumer Health Summary

Gets a summary of consumer health across all subscriptions for a setup.

**Endpoint:** `GET /api/v1/setups/:setupId/consumer-alerts/summary`
**Handler:** `ConsumerAlertHandler.getHealthSummary()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`

---

### Get Blocked Message Statistics

Gets statistics about blocked messages across consumer groups.

**Endpoint:** `GET /api/v1/setups/:setupId/consumer-alerts/blocked`
**Handler:** `ConsumerAlertHandler.getBlockedStats()`
**Service:** `SubscriptionService`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID |

**Response:** `200 OK`

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

### Get Unique Aggregates

Gets a list of unique aggregate IDs with summary statistics for each.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/aggregates`  
**Handler:** `EventStoreHandler.getUniqueAggregates()`  
**Service:** `BiTemporalEventStore`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `eventType` | string | No | all | Filter by event type |
| `limit` | integer | No | 1000 | Maximum aggregates to return (max: 1000) |
| `offset` | integer | No | 0 | Offset for pagination |
| `source` | string | No | auto | Data source: `log` (event log scan) or `summary` (aggregate summary table) |

**Response:** `200 OK`
```json
{
  "aggregates": [
    {
      "aggregateId": "string",
      "eventCount": 0,
      "firstEventTime": "ISO-8601 timestamp",
      "lastEventTime": "ISO-8601 timestamp",
      "eventTypes": ["string"]
    }
  ],
  "count": 0,
  "totalCount": 0,
  "truncated": false,
  "limit": 1000,
  "offset": 0
}
```

**Error Responses:**

| Status | Condition |
|:-------|:----------|
| `404 Not Found` | Setup or event store does not exist |

---

### Reconcile Aggregate Summary

Verifies or rebuilds the aggregate summary table against the event log.

**Endpoint:** `POST /api/v1/eventstores/:setupId/:eventStoreName/aggregate-summary/reconcile`  
**Handler:** `EventStoreHandler.reconcileAggregateSummary()`  
**Service:** `BiTemporalEventStore`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|:----------|:-----|:---------|:--------|:------------|
| `mode` | string | No | verify | `verify` (report mismatches) or `rebuild` (fix mismatches) |

**Response:** `200 OK`
```json
{
  "mode": "verify|rebuild",
  "aggregatesChecked": 0,
  "missingInSummary": 0,
  "staleInSummary": 0,
  "orphanedInSummary": 0,
  "repaired": 0,
  "sampleMismatches": [],
  "timestamp": 0
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `aggregatesChecked` | integer | Number of aggregates checked |
| `missingInSummary` | integer | Aggregates in event log but not in summary |
| `staleInSummary` | integer | Summary entries with outdated counts |
| `orphanedInSummary` | integer | Summary entries with no corresponding events |
| `repaired` | integer | Number of repairs made (only in `rebuild` mode) |
| `sampleMismatches` | array | Sample of mismatches found (for debugging) |

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

### Delete Event Store (Standard REST API)

Deletes an event store from a setup using the Standard REST API pattern with separate path parameters. This is the recommended approach for programmatic access.

**What This Endpoint Does:**
1. Stops any active event processing for the event store
2. Removes the event store from the setup's active configuration
3. Marks the event store table for cleanup (table remains but is no longer accessible)
4. Returns success immediately - background cleanup may continue

**Important Notes:**
- This does NOT drop the PostgreSQL table immediately (for safety)
- The event store becomes inaccessible immediately after deletion
- For Management UI/BFF usage, use `DELETE /api/v1/management/event-stores/{setupId-storeName}` instead
- Both endpoints perform the same underlying operation

**Endpoint:** `DELETE /api/v1/eventstores/:setupId/:eventStoreName`
**Handler:** `ManagementApiHandler.deleteEventStoreByName()`
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `setupId` | string | Yes | The setup ID (can contain hyphens) |
| `eventStoreName` | string | Yes | The event store name |

**Response:** `200 OK`
```json
{
  "message": "Event store 'order_events' deleted successfully from setup 'production'",
  "setupId": "production",
  "storeName": "order_events",
  "storeId": "production-order_events",
  "note": "Event store and associated data have been removed",
  "timestamp": 1767340616246
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `message` | string | Confirmation message with setup and store name |
| `setupId` | string | The setup ID (parsed from request) |
| `storeName` | string | The event store name (parsed from request) |
| `storeId` | string | Composite ID in format `setupId-storeName` |
| `timestamp` | integer | Unix timestamp in milliseconds |

**Error Responses:**

| Status | Condition | Example Response |
|:-------|:----------|:-----------------|
| `404 Not Found` | Setup does not exist | `{"error": "Setup not found: production", "timestamp": 1767340616246}` |
| `404 Not Found` | Event store does not exist in setup | `{"error": "Event store not found: order_events", "timestamp": 1767340616246}` |
| `404 Not Found` | Setup not active | `{"error": "Setup not found or not active: production", "timestamp": 1767340616246}` |
| `400 Bad Request` | Invalid setupId or storeName | `{"error": "Invalid request format: ...", "timestamp": 1767340616246}` |

**Example Usage:**

```bash
# Delete event store using Standard REST API
curl -X DELETE "http://localhost:8080/api/v1/eventstores/production/order_events"

# Response
{
  "message": "Event store 'order_events' deleted successfully from setup 'production'",
  "setupId": "production",
  "storeName": "order_events",
  "storeId": "production-order_events",
  "timestamp": 1767340616246
}
```

**Comparison with Management API:**

| Aspect | Standard REST API | Management API |
|:-------|:------------------|:---------------|
| **Endpoint** | `DELETE /api/v1/eventstores/{setupId}/{eventStoreName}` | `DELETE /api/v1/management/event-stores/{setupId-storeName}` |
| **Parameters** | Separate path parameters | Composite ID (setupId-storeName) |
| **Use Case** | Programmatic/API clients | Management UI/BFF layer |
| **Hyphen Handling** | No parsing required | Uses `lastIndexOf('-')` to handle hyphens in setupId |
| **Response** | Includes `setupId` and `storeName` separately | Includes composite `storeId` |

**When to Use:**
- ✅ Use this endpoint for programmatic access (REST clients, CLIs, automation)
- ✅ Use when you have setupId and storeName as separate values
- ✅ Use for consistency with other Standard REST CRUD operations
- ❌ Don't use from Management UI (use Management API instead)

---

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

**Endpoint:** `PUT /api/v1/management/queues/:setupId/:queueName`  
**Handler:** `ManagementApiHandler.updateQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

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

**Endpoint:** `DELETE /api/v1/management/queues/:setupId/:queueName`  
**Handler:** `ManagementApiHandler.deleteQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

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

**Endpoint:** `DELETE /api/v1/management/consumer-groups/:setupId/:queueName/:groupName`  
**Handler:** `ManagementApiHandler.deleteConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

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

### Pause Consumer Group (Management)

Pauses a consumer group via management API.

**Endpoint:** `POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause`  
**Handler:** `ManagementApiHandler.pauseConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`

---

### Resume Consumer Group (Management)

Resumes a paused consumer group via management API.

**Endpoint:** `POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume`  
**Handler:** `ManagementApiHandler.resumeConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`

---

### Backfill Consumer Group (Management)

Triggers a backfill operation for a consumer group via management API.

**Endpoint:** `POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill`  
**Handler:** `ManagementApiHandler.backfillConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`

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

### Delete Event Store (Management API)

Deletes an event store from a setup using the Management API pattern with a composite ID. This is the recommended endpoint for Management UI and BFF (Backend-for-Frontend) implementations.

**What This Endpoint Does:**
1. Parses the composite `storeId` to extract setupId and storeName
2. Stops any active event processing for the event store
3. Removes the event store from the setup's active configuration
4. Returns success immediately - background cleanup may continue

**Important Notes:**
- Uses composite ID format: `setupId-storeName` (e.g., "production-order_events")
- Handles setupId with hyphens correctly (e.g., "prod-region-us-order_events" → setupId="prod-region-us", storeName="order_events")
- For programmatic/API client usage, prefer `DELETE /api/v1/eventstores/{setupId}/{eventStoreName}` instead
- Both endpoints perform the same underlying operation

**Endpoint:** `DELETE /api/v1/management/event-stores/:storeId`  
**Handler:** `ManagementApiHandler.deleteEventStore()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**

| Parameter | Type | Required | Description |
|:----------|:-----|:---------|:------------|
| `storeId` | string | Yes | Composite store ID in format `setupId-storeName`. The LAST hyphen separates setupId from storeName to handle hyphens in setupId correctly. |

**Composite ID Parsing:**
- Format: `setupId-storeName`
- Parsing strategy: Uses `lastIndexOf('-')` to handle hyphens in setupId
- Examples:
  - `production-order_events` → setupId=`production`, storeName=`order_events`
  - `prod-us-east-order_events` → setupId=`prod-us-east`, storeName=`order_events`
  - `test-setup-with-hyphens-my_store` → setupId=`test-setup-with-hyphens`, storeName=`my_store`

**Response:** `200 OK`
```json
{
  "message": "Event store 'order_events' deleted successfully from setup 'production'",
  "storeId": "production-order_events",
  "setupId": "production",
  "storeName": "order_events",
  "note": "Event store and associated data have been removed",
  "timestamp": 1767340616246
}
```

**Response Fields:**

| Field | Type | Description |
|:------|:-----|:------------|
| `message` | string | Confirmation message with setup and store name |
| `storeId` | string | Composite ID in format `setupId-storeName` |
| `setupId` | string | The setup ID (parsed from composite storeId) |
| `storeName` | string | The event store name (parsed from composite storeId) |
| `timestamp` | integer | Unix timestamp in milliseconds |

**Error Responses:**

| Status | Condition | Example Response |
|:-------|:----------|:-----------------|
| `404 Not Found` | Setup does not exist | `{"error": "Setup not found: production", "timestamp": 1767340616246}` |
| `404 Not Found` | Event store does not exist | `{"error": "Event store not found: order_events", "timestamp": 1767340616246}` |
| `400 Bad Request` | Invalid storeId format | `{"error": "Invalid store ID format. Expected: setupId-storeName", "timestamp": 1767340616246}` |

**Example Usage:**

```bash
# Delete event store using Management API (composite ID)
curl -X DELETE "http://localhost:8080/api/v1/management/event-stores/production-order_events"

# Response
{
  "message": "Event store 'order_events' deleted successfully from setup 'production'",
  "storeId": "production-order_events",
  "setupId": "production",
  "storeName": "order_events",
  "timestamp": 1767340616246
}
```

**Comparison with Standard REST API:**

| Aspect | Management API (BFF) | Standard REST API |
|:-------|:---------------------|:------------------|
| **Endpoint** | `DELETE /api/v1/management/event-stores/{setupId-storeName}` | `DELETE /api/v1/eventstores/{setupId}/{eventStoreName}` |
| **Parameters** | Composite ID (setupId-storeName) | Separate path parameters |
| **Use Case** | Management UI/BFF layer | Programmatic/API clients |
| **Hyphen Handling** | Uses `lastIndexOf('-')` to parse | No parsing required |
| **Response** | Includes composite `storeId` | Includes `setupId` and `storeName` separately |

**When to Use:**
- ✅ Use this endpoint from Management UI components
- ✅ Use when you have a composite storeId from a list/grid view
- ✅ Use for simplified BFF (Backend-for-Frontend) integration
- ❌ Don't use for programmatic access (use Standard REST API instead)

**See Also:**
- Standard REST API: `DELETE /api/v1/eventstores/{setupId}/{eventStoreName}` (documented in Event Store Endpoints section)

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
**Handler:** Inline handler in `PeeGeeQRestServer.createRouter()`  
**Service:** Internal system information

**Response:** `200 OK`
```json
{
  "status": "UP",
  "timestamp": 0,
  "uptime": 0,
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

### SSE Queue Updates

Streams real-time queue update events for a setup via Server-Sent Events.

**Endpoint:** `GET /api/v1/sse/queues/:setupId`  
**Handler:** `ServerSentEventsHandler.handleQueueUpdates()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID

**Response:** `200 OK` (Content-Type: text/event-stream)

---

### SSE Health Check

Health check endpoint for SSE connectivity.

**Endpoint:** `GET /api/v1/sse/health`  
**Handler:** Inline handler

**Response:** `200 OK` (Content-Type: text/event-stream)

Sends a single health event and closes the connection:
```
data: {"status":"UP","type":"sse","timestamp":1234567890}
```

---

### SSE Metrics Stream

Streams real-time system metrics via Server-Sent Events.

**Endpoint:** `GET /api/v1/sse/metrics`  
**Alternative:** `GET /sse/metrics` (legacy unversioned path)  
**Handler:** `SystemMonitoringHandler.handleSSEMetrics()`  
**Service:** Internal metrics registry

**Response:** `200 OK` (Content-Type: text/event-stream)

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

### WebSocket System Monitoring

Streams real-time system monitoring metrics via WebSocket.

**Endpoint:** `ws://localhost:8080/ws/monitoring`  
**Handler:** `SystemMonitoringHandler.handleWebSocketMonitoring()`  
**Service:** Internal metrics registry

---

### WebSocket Health Check

One-shot WebSocket health check that sends a health frame and closes.

**Endpoint:** `ws://localhost:8080/ws/health`  
**Handler:** Inline handler

**Response:** Sends single frame and closes:
```json
{
  "status": "UP",
  "type": "websocket",
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

**Status:** ✅ **IMPLEMENTED** (December 22, 2025)

**REST API** now exposes all QueueConfig parameters:
- `queueName` (String) - Queue name (required)
- `maxRetries` (Integer, default: 3) - Maximum retry attempts
- `visibilityTimeoutSeconds` (Integer, default: 300) - Visibility timeout in seconds ✅
- `visibilityTimeout` (String) - ISO-8601 duration format (alternative) ✅
- `deadLetterEnabled` (Boolean, default: true) - Enable dead letter queue
- `batchSize` (Integer, default: 10) - Batch processing size ✅ **NEW**
- `pollingIntervalSeconds` (Integer, default: 5) - Polling frequency in seconds ✅ **NEW**
- `pollingInterval` (String) - ISO-8601 duration format (alternative) ✅ **NEW**
- `fifoEnabled` (Boolean, default: false) - FIFO queue ordering ✅ **NEW**
- `deadLetterQueueName` (String, optional) - Custom dead letter queue name ✅ **NEW**

**Implementation:** `ManagementApiHandler.createQueue()` now uses comprehensive `parseQueueConfig()` method that supports all parameters.

**Example Request:**
```json
POST /api/v1/management/queues
{
  "name": "orders",
  "setup": "production-setup",
  "maxRetries": 5,
  "visibilityTimeoutSeconds": 60,
  "deadLetterEnabled": true,
  "batchSize": 25,
  "pollingIntervalSeconds": 2,
  "fifoEnabled": true,
  "deadLetterQueueName": "orders_dlq"
}
```

**Example Response:**
```json
{
  "message": "Queue created successfully",
  "queueName": "orders",
  "setupId": "production-setup",
  "queueId": "production-setup-orders",
  "maxRetries": 5,
  "visibilityTimeoutSeconds": 60,
  "deadLetterEnabled": true,
  "batchSize": 25,
  "pollingIntervalSeconds": 2,
  "fifoEnabled": true,
  "deadLetterQueueName": "orders_dlq",
  "timestamp": 1766402310770
}
```

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

#### 2.1 Pull-Based Polling Not Implemented

**Status:** ⚠️ **DESIGN DECISION**

**Core API:** `MessageConsumer` provides push-based subscription with automatic message delivery via `MessageHandler`.

**REST API:** Pull-based polling endpoints (`getNextMessage`, `getMessages`, `acknowledgeMessage`) were **never implemented**. The REST API uses push-based delivery exclusively:
- **SSE streaming** (`GET /api/v1/queues/:setupId/:queueName/stream`) — recommended for browser clients
- **WebSocket** (`ws://localhost:8080/ws/queues/:setupId/:queueName`) — for bidirectional communication
- **Webhook subscriptions** (`POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions`) — recommended for production

**Impact:** No HTTP polling-based consumption. All consumption is push-based.

**Note:** The `GET /api/v1/queues/:setupId/:queueName/messages` route exists but serves a different purpose — it's a management/browsing endpoint (via `ManagementApiHandler.getQueueMessages()`) for inspecting queue contents, not for consumer polling.

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

#### 3.1 SubscriptionOptions Support

**Status:** ✅ **IMPLEMENTED** (as of December 22, 2025)

**Core API:** `ConsumerGroup.start(SubscriptionOptions)` supports:
- `startPosition` (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
- `startFromMessageId` (Long) - Start from specific message
- `startFromTimestamp` (Instant) - Start from specific time
- `heartbeatIntervalSeconds` (Integer, default: 60)
- `heartbeatTimeoutSeconds` (Integer, default: 300)

**REST API Implementation:**

**Pattern 1: Two-Step (Separate Calls)**
```bash
# Step 1: Create consumer group
POST /api/v1/queues/:setupId/:queueName/consumer-groups
{ "groupName": "my-group" }

# Step 2: Set subscription options
POST /api/v1/consumer-groups/:setupId/:queueName/my-group/subscription
{ "startPosition": "FROM_BEGINNING" }
```

**Pattern 2: Single-Step (Convenience Method)**
```bash
# Create consumer group with subscription options in one call
POST /api/v1/queues/:setupId/:queueName/consumer-groups
{
  "groupName": "my-group",
  "subscriptionOptions": {
    "startPosition": "FROM_BEGINNING",
    "heartbeatIntervalSeconds": 60
  }
}
```

**Available Endpoints:**
- `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` - Set/update subscription options
- `GET /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` - Get subscription options
- `DELETE /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` - Delete subscription options
- `GET /api/v1/queues/:setupId/:queueName/stream?consumerGroup={groupName}` - SSE streaming with subscription options

**Features:**
- ✅ Full support for all start positions (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
- ✅ Configurable heartbeat intervals and timeouts
- ✅ SSE streaming automatically applies subscription options
- ✅ Graceful fallback to defaults for non-existent groups
- ✅ Comprehensive integration tests

**See:** Section "Consumer Group Subscription Options" (lines 1230-1651) for complete API documentation.

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

**Status:** ✅ **IMPLEMENTED** (December 22, 2025)

**REST API** now exposes all EventStoreConfig parameters:
- `eventStoreName` (String) - Event store name
- `tableName` (String) - Database table name
- `biTemporalEnabled` (Boolean, default: true) - Enable bi-temporal support
- `notificationPrefix` (String, default: "peegeeq_events_") - Notification channel prefix
- `queryLimit` (Integer, default: 1000) - Max query results ✅ **NEW**
- `metricsEnabled` (Boolean, default: true) - Enable metrics collection ✅ **NEW**
- `partitionStrategy` (String, default: "monthly") - Table partitioning strategy ✅ **NEW**

**Note:** `eventType` (Class<?>) is not exposed via REST API as it requires Java class references. REST clients should use JSON payloads which are automatically handled.

**Implementation:** `ManagementApiHandler.createEventStore()` now accepts and validates all parameters.

**Example Request:**
```json
POST /api/v1/management/event-stores
{
  "name": "order-events",
  "setup": "production-setup",
  "tableName": "order_events",
  "biTemporalEnabled": true,
  "notificationPrefix": "order_notify_",
  "queryLimit": 500,
  "metricsEnabled": false,
  "partitionStrategy": "daily"
}
```

**Example Response:**
```json
{
  "message": "Event store created successfully",
  "eventStoreName": "order-events",
  "setupId": "production-setup",
  "tableName": "order_events",
  "biTemporalEnabled": true,
  "notificationPrefix": "order_notify_",
  "queryLimit": 500,
  "metricsEnabled": false,
  "partitionStrategy": "daily",
  "storeId": "production-setup-order-events",
  "timestamp": 1766401814536
}
```

---

#### 4.2 Missing Event Store Methods

**Status:** ⚠️ **PARTIAL IMPLEMENTATION**

**Core API** provides extensive event store operations:

**Missing from REST API:**
- `append()` variations with different parameter combinations
- `appendInTransaction()` - Transaction-aware event appending (4 variations) ⚠️ **CANNOT BE IMPLEMENTED** (requires server-side SqlConnection)
- `appendBatch()` - Atomic batch operations ⚠️ **SHOULD BE IMPLEMENTED** (could expose as `/events/batch` endpoint)
- `subscribe()` methods - Real-time event subscription (2 variations)
- `unsubscribe()` - Unsubscribe from events

**✅ Implemented in REST API:**
- ~~`appendCorrection()`~~ - Bi-temporal corrections with `correctionReason` ✅ **IMPLEMENTED**
  - Endpoint: `POST /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections`
  - Supports full metadata (correlationId, causationId, headers)
  - Validates required fields (eventData, correctionReason)
  - Returns correction event ID and version
- ~~`getById(String eventId)`~~ - Get specific event ✅ **IMPLEMENTED**
  - Endpoint: `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId`
- ~~`getAllVersions(String eventId)`~~ - Get all versions including corrections ✅ **IMPLEMENTED**
  - Endpoint: `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions`
- ~~`getAsOfTransactionTime(String eventId, Instant asOfTransactionTime)`~~ - Point-in-time query ✅ **IMPLEMENTED**
  - Endpoint: `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at?transactionTime=...`

**REST API** provides:
- `storeEvent()` - Event appending with full metadata support
- `queryEvents()` - Comprehensive query with bi-temporal filters ✅ **ENHANCED** (Dec 22, 2025)
- `getEvent()` - Get single event by ID ✅ **IMPLEMENTED**
- `getAllVersions()` - Get version history ✅ **IMPLEMENTED**
- `getAsOfTransactionTime()` - Point-in-time queries ✅ **IMPLEMENTED**
- `appendCorrection()` - Bi-temporal corrections ✅ **IMPLEMENTED**
- `getStats()` - Basic statistics (placeholder implementation)

**Impact:**
- ~~Cannot create bi-temporal corrections via REST API~~ ✅ **RESOLVED**
- Cannot participate in transactions (still missing)
- ~~No version history queries~~ ✅ **RESOLVED**
- No real-time event subscription (except via WebSocket/SSE which are separate)

**Recommendation:** Add transaction support for event appending.

---

#### 4.3 Missing EventQuery Parameters

**Status:** ✅ **IMPLEMENTED**

**REST API** query parameters now support:

**Basic Filters:**
- `eventType` (String) - Filter by event type
- `aggregateId` (String) - Group related events
- `correlationId` (String) - Filter by correlation ID
- `causationId` (String) - Filter by causation ID (event causality tracking)

**Valid Time Range (Business Time):**
- `validTimeFrom` (ISO-8601) - Start of valid time range
- `validTimeTo` (ISO-8601) - End of valid time range
- `fromTime` (ISO-8601) - Legacy alias for validTimeFrom
- `toTime` (ISO-8601) - Legacy alias for validTimeTo

**Transaction Time Range (System Time - Bi-Temporal):**
- `transactionTimeFrom` (ISO-8601) - Start of transaction time range
- `transactionTimeTo` (ISO-8601) - End of transaction time range
- `asOfTransactionTime` (ISO-8601) - Point-in-time query

**Sorting and Filtering:**
- `sortOrder` (Enum) - VALID_TIME_ASC, VALID_TIME_DESC, TRANSACTION_TIME_ASC, TRANSACTION_TIME_DESC, VERSION_ASC, VERSION_DESC
- `includeCorrections` (Boolean, default: true) - Include/exclude corrections
- `minVersion` (Long) - Minimum version number
- `maxVersion` (Long) - Maximum version number

**Pagination:**
- `limit` (Integer, default: 100, max: 1000) - Max query results
- `offset` (Integer, default: 0) - Pagination offset

**Example Request:**
```http
GET /api/v1/eventstores/{setupId}/{eventStoreName}/events?validTimeFrom=2025-12-22T10:00:00Z&validTimeTo=2025-12-22T12:00:00Z&sortOrder=VALID_TIME_ASC&includeCorrections=true
```

**Example Response:**
```json
{
  "message": "Events retrieved successfully",
  "eventStoreName": "test_events",
  "setupId": "my_setup",
  "eventCount": 2,
  "limit": 100,
  "offset": 0,
  "hasMore": false,
  "filters": {
    "validTimeFrom": "2025-12-22T10:00:00Z",
    "validTimeTo": "2025-12-22T12:00:00Z",
    "sortOrder": "VALID_TIME_ASC",
    "includeCorrections": true
  },
  "events": [
    {
      "id": "event-uuid-1",
      "eventType": "OrderUpdated",
      "eventData": {"orderId": "ORDER-001", "amount": 150.0},
      "validFrom": 1766399469.293421,
      "validTo": null,
      "transactionTime": 1766403069.408205,
      "correlationId": "correlation-uuid",
      "causationId": null,
      "version": 1,
      "metadata": {}
    }
  ],
  "timestamp": 1766403069459
}
```

**Not Supported (Core API Only):**
- `headerFilters` (Map<String, String>) - Requires complex JSON structure, not suitable for query parameters

**Impact:** Full bi-temporal query capabilities now available via REST API!

---

#### 4.4 Placeholder Implementations

**Status:** ✅ **RESOLVED** (June 2026)

~~These event store methods were placeholder implementations with no actual database queries:~~

- ~~`queryEvents()` - Returns sample/mock events~~ ✅ Now queries BiTemporalEventStore
- ~~`getEvent()` - Returns sample event or null~~ ✅ Now queries BiTemporalEventStore
- ~~`getStats()` - Returns sample statistics~~ ✅ Now queries BiTemporalEventStore

All event store REST API endpoints are now backed by real database queries.

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

**Status:** ✅ **IMPLEMENTED**

**Core API:** `MessageFilter` interface for filtering messages

**REST API:** Full message filtering support for consumer groups

**Implementation:**

1. **Group-Level Filters** - Applied when creating a consumer group:
   ```json
   POST /api/v1/queues/{setupId}/{queueName}/consumer-groups
   {
     "groupName": "my-group",
     "groupFilter": {
       "type": "header",
       "headerKey": "region",
       "headerValue": "US"
     }
   }
   ```

2. **Per-Consumer Filters** - Applied when joining a consumer group:
   ```json
   POST /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members
   {
     "memberName": "my-consumer",
     "messageFilter": {
       "type": "priority",
       "minPriority": "HIGH"
     }
   }
   ```

**Supported Filter Types:**

- **header** - Filter by exact header value
  - Required: `headerKey`, `headerValue`
  - Example: `{"type": "header", "headerKey": "region", "headerValue": "US"}`

- **headerIn** - Filter by header value in set
  - Required: `headerKey`, `allowedValues` (array)
  - Example: `{"type": "headerIn", "headerKey": "region", "allowedValues": ["US", "EU"]}`

- **region** - Filter by region header (convenience method)
  - Required: `allowedValues` (array)
  - Example: `{"type": "region", "allowedValues": ["US", "EU", "APAC"]}`

- **priority** - Filter by minimum priority level
  - Required: `minPriority` (HIGH, NORMAL, or LOW)
  - Example: `{"type": "priority", "minPriority": "HIGH"}`

- **and** - Combine multiple filters with AND logic
  - Required: `filters` (array of filter objects)
  - Example: `{"type": "and", "filters": [{"type": "header", ...}, {"type": "priority", ...}]}`

**Filter Application Order:**
1. Group-level filter is applied first (if configured)
2. Per-consumer filter is applied second (if configured)
3. Only messages passing both filters are delivered to the consumer

---

#### 8.2 Message Handlers

**Status:** ⚠️ **NOT APPLICABLE**

**Core API:** `MessageHandler<T>` for push-based async message processing

**REST API:** Uses pull-based HTTP polling - handlers not applicable

**Decision:** This is an expected difference between push and pull models.

---

#### 8.3 Transaction Participation

**Status:** 🔴 **NOT AVAILABLE** (Architectural Limitation)

**Core API:**
- `EventStore.appendInTransaction()` - 4 variations for ACID guarantees
  - Requires `io.vertx.sqlclient.SqlConnection` parameter (server-side only)
  - Allows events to participate in existing database transactions
  - Ensures ACID guarantees between business operations and event logging
- `EventStore.appendBatch()` - Atomic batch operations
  - Appends multiple events in a single transaction
  - Provides atomicity for multi-event operations
  - Does not require external connection parameter

**REST API:**
- ❌ No `appendInTransaction()` support (cannot expose SqlConnection over HTTP)
- ❌ No `appendBatch()` endpoint (could be implemented)

**Impact:**
- Cannot ensure ACID guarantees between business operations and event logging via REST API
- Cannot atomically append multiple events in a single request
- Each REST API call creates its own transaction

**Recommendation:**
1. **For cross-service transactions**: This is a fundamental limitation of REST APIs. REST is stateless and cannot maintain database connections/transactions across HTTP requests. Document that transactional operations require direct SDK usage.
2. **For atomic multi-event operations**: Implement `POST /api/v1/eventstores/:setupId/:eventStoreName/events/batch` endpoint to expose `appendBatch()` functionality. This would allow clients to atomically append multiple events in a single HTTP request.

**Workaround:**
- Use correlation IDs to link related events
- Implement compensating transactions for rollback scenarios
- Use batch endpoint (once implemented) for atomic multi-event operations

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

1. ~~**Message consumption endpoints are placeholders**~~ — Polling endpoints (getNextMessage, acknowledgeMessage) were **never implemented**. Consumption is via SSE, WebSocket, or Webhook push delivery. ⚠️ **DESIGN DECISION** — not a bug.
2. ~~**Event store query is placeholder**~~ ✅ **IMPLEMENTED** — Real database queries now power `queryEvents()` and `getStats()`
3. **All real-time statistics return zero** - Management overview metrics (`messagesPerSecond`, `activeConnections`) are not yet backed by real data

#### ⚠️ High Priority (Significant Feature Gaps)

1. ~~Missing QueueConfig parameters (batchSize, pollingInterval, fifoEnabled, deadLetterQueueName)~~ ✅ **IMPLEMENTED** (Dec 22, 2025)
2. ~~Missing SubscriptionOptions in consumer groups (start position, heartbeats)~~ ✅ **IMPLEMENTED** (Dec 22, 2025)
3. ~~Missing EventStoreConfig parameters (queryLimit, metricsEnabled, partitionStrategy)~~ ✅ **IMPLEMENTED** (Dec 22, 2025)
4. ~~Missing bi-temporal query capabilities (temporal ranges, corrections)~~ ✅ **IMPLEMENTED** (Dec 22, 2025)
5. ~~Missing consumer group filtering (per-consumer and group-level)~~ ✅ **IMPLEMENTED** (Dec 22, 2025)
6. ~~Missing event correction endpoints (appendCorrection)~~ ✅ **ALREADY IMPLEMENTED**
7. ~~No transaction support for event appending~~ ⚠️ **ARCHITECTURAL LIMITATION** (Dec 22, 2025)
   - `appendInTransaction()` cannot be exposed via REST (requires server-side SqlConnection)
   - `appendBatch()` could be implemented as `/events/batch` endpoint for atomic multi-event operations

#### ℹ️ Medium Priority (Nice to Have)

1. Consumer configuration support
2. Extended statistics and metrics
3. CloudEvents explicit support
4. Negative acknowledgment (NACK) support

#### ✅ Low Priority (Acceptable As-Is)

1. Reactive API methods (internal use only)
2. Type conversions (Duration, Instant, Enum) - standard REST practice
3. Message handlers (push vs pull model difference)
4. Transaction participation (SDK-only feature)

---

### 11. Alignment Recommendations

#### Phase 1: es (Blockers)
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

**Document Version:** 1.1  
**Last Updated:** 2026-06-17  
**Author:** Mark Andrew Ray-Smith Cityline Ltd
