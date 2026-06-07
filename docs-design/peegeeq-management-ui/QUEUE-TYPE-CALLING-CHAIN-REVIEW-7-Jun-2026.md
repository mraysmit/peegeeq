# Queue Type Calling Chain Review
## peegeeq-native vs peegeeq-outbox: Configuration and Management Layers

---

## Overview

This document traces how each queue type (`native` and `outbox`) is configured, resolved, and managed across every layer of the PeeGeeQ stack — from the core Java factories through the REST API, RTK Query client, and management UI.

---

## Layer 1: Core Java Backend — Factories and Configuration

### Queue Type Identity

Both implementations satisfy the `QueueFactory` interface and report their type via a single method:

```java
// QueueFactory.java
String getImplementationType();  // returns "native" or "outbox"
```

| | Native | Outbox |
|---|---|---|
| **Factory class** | `PgNativeQueueFactory` | `OutboxFactory` |
| **Producer class** | `PgNativeQueueProducer<T>` | `OutboxProducer<T>` |
| **Consumer class** | `PgNativeQueueConsumer<T>` | `OutboxConsumer<T>` |
| **Consumer group class** | `PgNativeConsumerGroup<T>` | `OutboxConsumerGroup<T>` |
| **Browser class** | `PgNativeQueueBrowser<T>` | `OutboxQueueBrowser<T>` |
| **Consumer config** | `ConsumerConfig` | `OutboxConsumerConfig` |
| **Returns** | `"native"` | `"outbox"` |

**Files:**
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueFactory.java`

### Consumer Configuration Differences

**Native — `ConsumerConfig`** supports three delivery modes:
- `LISTEN_NOTIFY_ONLY` — pure event-driven via PostgreSQL NOTIFY, lowest latency
- `POLLING_ONLY` — periodic table scan, works with unreliable connections
- `HYBRID` — both mechanisms combined for maximum reliability

**Outbox — `OutboxConsumerConfig`** supports:
- `pollingInterval` — how frequently the background processor scans (default: 1 second)
- `batchSize` — messages fetched per poll cycle

### Queue Config Domain Object

```java
// QueueConfig.java — peegeeq-api
public class QueueConfig {
    private String implementationType;  // "native", "outbox", or null (auto-detect)
    // Builder pattern:
    QueueConfig.Builder().implementationType("native")
}
```

**File:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/QueueConfig.java`

---

## Layer 2: Factory Registration and Resolution

### Registrar Pattern

Each implementation self-registers under its type key at startup:

```java
// PgNativeFactoryRegistrar.java
PgNativeFactoryRegistrar.registerWith(registrar);  // registers key "native"

// OutboxFactoryRegistrar.java
OutboxFactoryRegistrar.registerWith(registrar);    // registers key "outbox"
```

**Files:**
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeFactoryRegistrar.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactoryRegistrar.java`

### Factory Resolution Logic

`PeeGeeQDatabaseSetupService.createQueueFactories()` resolves the type for each queue at setup initialisation:

```
1. Read queueConfig.getImplementationType()
2. If explicitly set:
     → validate it is in the supported-types registry
     → throw IllegalArgumentException if not found
3. If null/not set:
     → fall back to queueFactoryProvider.getBestAvailableType()
4. If no implementations on classpath:
     → skip queue (backward compatible)
5. Call queueFactoryProvider.createFactory(type, databaseService, factoryConfig)
```

**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java` (method `createQueueFactories`, around line 828)

### Database Tables

| | Native | Outbox |
|---|---|---|
| **Table** | `queue_messages` | `outbox` |
| **Lock mechanism** | PostgreSQL advisory lock (`lock_id`, `lock_until`) | Optimistic locking (`version` column) |
| **Message states** | `AVAILABLE → LOCKED → (deleted)` | `PENDING → PROCESSING → COMPLETED / FAILED → DEAD_LETTER` |
| **Retry scheduling** | Lock expiry (30 s) | `next_retry_at` column with exponential backoff |
| **Fanout support** | No | Yes (`required_consumer_groups`, `completed_groups_bitmap`) |
| **Retention** | Deleted on success | Row retained with final status |

**SQL schema files:**
- `peegeeq-db/src/main/resources/db/templates/base/04b-core-table-queue-messages.sql`
- `peegeeq-db/src/main/resources/db/templates/base/04a-core-table-outbox.sql`

---

## Layer 3: REST API

### Queue Creation — `POST /api/v1/management/queues`

**Handler:** `ManagementApiHandler.createQueue()` — `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java`

**Request body** (either field name is accepted):
```json
{
  "setup": "default",
  "name": "my-queue",
  "type": "native",
  "implementationType": "native"
}
```

**Parsing and validation** — `ConfigParser.parseQueueConfig()`:
```java
// Accepts both "type" and "implementationType" field names
// Normalises to lowercase, validates:
if (!normalized.equals("native") && !normalized.equals("outbox")) {
    throw new IllegalArgumentException(
        "Invalid implementationType '" + implementationType +
        "'. Supported values are: native, outbox");
}
```

**File:** `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ConfigParser.java` (lines 68–83)

**Response body** — all config fields are returned regardless of whether they were supplied in the request. The backend fills in defaults from `QueueConfig` and echoes the full resolved config back to the caller (`ManagementApiHandler.java` line 1074):
```json
{
  "message": "Queue 'my-queue' created successfully in setup 'default'",
  "queueName": "my-queue",
  "setupId": "default",
  "queueId": "default-my-queue",
  "id": "default-my-queue",
  "implementationType": "native",
  "visibilityTimeoutSeconds": 30,
  "maxRetries": 3,
  "deadLetterEnabled": false,
  "batchSize": 10,
  "pollingIntervalSeconds": 1,
  "fifoEnabled": false,
  "deadLetterQueueName": null,
  "timestamp": 1234567890
}
```

### Queue Stats — `GET /api/v1/queues/:setupId/:queueName`

**Handler:** `QueueHandler` — calls `queueFactory.getImplementationType()` to embed the type in the response.

**Response:**
```json
{
  "queueName": "my-queue",
  "setupId": "default",
  "implementationType": "native",
  "messages": 5,
  "consumers": 2,
  "messageRate": 1.2,
  "status": "active"
}
```

### Queue List — `GET /api/v1/management/queues`

**Response (each item):**
```json
{
  "name": "my-queue",
  "setup": "default",
  "implementationType": "native",
  "messages": 5,
  "messageRate": 1.2,
  "status": "active"
}
```

---

## Layer 4: Frontend RTK Query API

**File:** `peegeeq-management-ui/src/store/api/queuesApi.ts`

### Endpoint Definitions

| Endpoint | RTK method | Direction | Type field |
|---|---|---|---|
| List queues | `getQueues` | `GET /management/queues` | filters by `type?: QueueType[]` |
| Queue details | `getQueueDetails` | `GET /queues/:setupId/:queueName` | reads `implementationType` from response |
| Create queue | `createQueue` | `POST /management/queues` | sends `type` in request body |

### Field Name Mapping (Backend → Frontend)

The backend returns `implementationType`; the frontend interface uses `type`. The `transformResponse` in `getQueueDetails` handles the mapping:

```typescript
// queuesApi.ts — transformResponse in getQueueDetails
type: raw.implementationType ?? raw.type ?? 'native',
```

### Create Queue Request Body

```typescript
// createQueue mutation — body sent to the backend
{ setup: setupId, name, type }   // "type" not "implementationType"
```

---

## Layer 5: TypeScript Types

**File:** `peegeeq-management-ui/src/types/queue.ts`

### Union Type

```typescript
export type QueueType = 'native' | 'outbox' | 'bitemporal';
```

### Type-Specific Config Interfaces

```typescript
// Shared base
interface QueueConfigBase {
    type: QueueType;
    retentionPeriod?: string;
    maxSize?: number;
    deadLetterEnabled?: boolean;
}

// Native-only fields
interface NativeQueueConfig extends QueueConfigBase {
    type: 'native';
    consumerMode: ConsumerMode;   // LISTEN_NOTIFY_ONLY | POLLING_ONLY | HYBRID
    pollingInterval?: number;
    batchSize?: number;
    connectionPoolSize?: number;
    enableNotifications?: boolean;
    consumerThreads?: number;
    prefetchCount?: number;
}

// Outbox-only fields
interface OutboxQueueConfig extends QueueConfigBase {
    type: 'outbox';
    visibilityTimeout?: number;
    maxRetries?: number;
    retryBackoffMultiplier?: number;
    stuckMessageThreshold?: number;
    cleanupInterval?: number;
}
```

### Queue and QueueDetails Interfaces

```typescript
interface Queue {
    setupId: string;
    queueName: string;
    type: QueueType;           // native | outbox | bitemporal
    status: QueueStatus;
    messageCount: number;
    consumerCount: number;
    messagesPerSecond: number;
    errorRate: number;
    createdAt: string;
    updatedAt: string;
}

interface QueueDetails extends Queue {
    config: QueueConfig;       // NativeQueueConfig | OutboxQueueConfig
    statistics: QueueStatistics;
    consumers: QueueConsumer[];
    description?: string;
    tags?: string[];
}
```

---

## Layer 6: Management UI React Components

### Queue List Page — `QueuesEnhanced.tsx`

**File:** `peegeeq-management-ui/src/pages/QueuesEnhanced.tsx`

**Type filtering:**
```typescript
handleTypeFilter(value: string | string[]) {
    setFilters({ ...prev, type: types as QueueType[] })
}
```

**Type display in table — colour-coded tags:**
```typescript
<Tag color={
    record.type === 'native'  ? 'green'  :
    record.type === 'outbox'  ? 'orange' :
    'purple'                               // bitemporal
}>
    {String(record.type || 'unknown').toUpperCase()}
</Tag>
```

**Create queue modal** — currently hardcoded to `'native'`:
```typescript
const requestBody = { setup: values.setup, name: values.name, type: 'native' };
```

> **Gap:** The create-queue form does not yet expose a type selector. All queues are created as `native` from the UI. To create an `outbox` queue, the REST API must be called directly.

### Queue Details Page — `QueueDetailsEnhanced.tsx`

- Calls `useGetQueueDetailsQuery({ setupId, queueName })`
- Displays `details.type` as a tag in the page header
- Overview tab shows Queue Information card (type tag) and Performance Metrics card
- Type badge colours follow the same green/orange/purple convention

---

## Full Request/Response Flow

```
User clicks "Create Queue" in the UI
    │
    ▼
QueuesEnhanced.tsx → handleModalOk()
    body: { setup, name, type: 'native' | 'outbox' }
    │
    ▼
RTK Query: createQueue mutation
    POST /api/v1/management/queues
    │
    ▼
ManagementApiHandler.createQueue()
    │
    ▼
ConfigParser.parseQueueConfig()
    validates: "native" | "outbox" only
    normalises: lowercase trim
    │
    ▼
PeeGeeQDatabaseSetupService.addQueue()
    │
    ▼
createQueueFactories()
    resolves implementationType:
      explicit? → validate → use
      null?     → getBestAvailableType()
    │
    ▼
QueueFactoryProvider.createFactory(type, databaseService, config)
    │
    ├─ "native" → PgNativeFactoryRegistrar → PgNativeQueueFactory
    │                 uses LISTEN/NOTIFY + advisory locks
    │                 table: queue_messages
    │
    └─ "outbox" → OutboxFactoryRegistrar   → OutboxFactory
                      uses polling + optimistic locks
                      table: outbox
    │
    ▼
Response: { queueName, setupId, implementationType: "native"|"outbox", ... }
    │
    ▼
RTK Query transformResponse
    maps: implementationType → type
    │
    ▼
Queue Details Page
    header tag: <Tag color="green">NATIVE</Tag>
    overview: Queue Information card shows type
```

---

## Identified Gaps

| Gap | Detail |
|---|---|
| **UI type selection missing** | The create-queue modal hardcodes `type: 'native'`. There is no UI control to create an outbox queue. |
| **Type-specific config not exposed** | Native consumer mode (`LISTEN_NOTIFY_ONLY` / `POLLING_ONLY` / `HYBRID`) and outbox fields (`visibilityTimeout`, `stuckMessageThreshold`, etc.) are defined in TypeScript types but are not rendered or editable in the UI. |
| **`bitemporal` type** | Referenced in the TypeScript union type and shown in purple in the UI, but there is no corresponding factory or backend implementation visible in the codebase. |
| **Field name mismatch** | Backend uses `implementationType`; frontend interface uses `type`. The `transformResponse` in `getQueueDetails` handles this, but the create mutation sends `type` and some list responses use `implementationType` — dual aliasing is required throughout. |
