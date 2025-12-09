# PeeGeeQ REST Module Guide

## 1. Overview

The `peegeeq-rest` module provides the HTTP REST API layer for PeeGeeQ. It exposes all PeeGeeQ functionality through a comprehensive RESTful interface, enabling clients to interact with queues, event stores, consumer groups, and system management features via standard HTTP requests.

### Key Characteristics

| Characteristic | Description |
|----------------|-------------|
| **Framework** | Vert.x 5.x Web (non-blocking, reactive) |
| **Architecture** | Hexagonal - depends only on `peegeeq-api` interfaces |
| **Composition** | Uses `peegeeq-runtime` for wiring implementations |
| **Streaming** | SSE (Server-Sent Events) and WebSocket support |
| **Push Delivery** | Webhook subscriptions for scalable message delivery |
| **Metrics** | Micrometer with Prometheus registry |

### Module Purpose

```
+------------------+     HTTP/REST      +------------------+
|  External        | -----------------> |  peegeeq-rest    |
|  Clients         | <----------------- |  (This Module)   |
+------------------+                    +------------------+
                                               |
                                               | Uses interfaces only
                                               v
                                        +------------------+
                                        |  peegeeq-api     |
                                        |  (Contracts)     |
                                        +------------------+
                                               ^
                                               | Composed by
                                        +------------------+
                                        |  peegeeq-runtime |
                                        |  (Wiring)        |
                                        +------------------+
```

## 2. Architecture Position

### Dependency Rules

| Module | Allowed Dependencies | Forbidden Dependencies |
|--------|---------------------|------------------------|
| `peegeeq-rest` | `peegeeq-api`, `peegeeq-runtime` | `peegeeq-native`, `peegeeq-outbox`, `peegeeq-bitemporal` |

The REST module follows strict hexagonal architecture principles:
- **All handlers receive `DatabaseSetupService` interface** (never implementation)
- **No direct access to implementation modules** - communicates via interfaces
- **Runtime composition** - `peegeeq-runtime` wires implementations at startup

### Module Structure

```
peegeeq-rest/
├── pom.xml
├── docs/
│   └── PEEGEEQ_REST_MODULE_GUIDE.md (this file)
└── src/
    ├── main/java/dev/mars/peegeeq/rest/
    │   ├── PeeGeeQRestServer.java          # Main Vert.x verticle (335 lines)
    │   ├── StartRestServer.java            # Entry point
    │   ├── handlers/
    │   │   ├── DatabaseSetupHandler.java   # Setup CRUD (581 lines)
    │   │   ├── QueueHandler.java           # Message operations (599 lines)
    │   │   ├── EventStoreHandler.java      # Bi-temporal events (1432 lines)
    │   │   ├── ConsumerGroupHandler.java   # Consumer groups (728 lines)
    │   │   ├── ServerSentEventsHandler.java # SSE streaming (663 lines)
    │   │   ├── WebSocketHandler.java       # WebSocket streaming (307 lines)
    │   │   ├── DeadLetterHandler.java      # DLQ operations (329 lines)
    │   │   ├── SubscriptionHandler.java    # Subscription lifecycle (311 lines)
    │   │   ├── HealthHandler.java          # Health monitoring (215 lines)
    │   │   ├── ManagementApiHandler.java   # Management UI support (1585 lines)
    │   │   ├── SSEConnection.java          # SSE connection wrapper
    │   │   ├── ConsumerGroup.java          # Consumer group model
    │   │   ├── LoadBalancingStrategy.java  # Load balancing enum
    │   │   └── SubscriptionManagerFactory.java
    │   ├── webhook/
    │   │   ├── WebhookSubscription.java
    │   │   ├── WebhookSubscriptionHandler.java (378 lines)
    │   │   └── WebhookSubscriptionStatus.java
    │   └── setup/
    │       └── RestDatabaseSetupService.java
    └── test/java/dev/mars/peegeeq/rest/
        └── (33 test classes)
```

## 3. Core Server Class

### PeeGeeQRestServer

The main server class extends `AbstractVerticle` and orchestrates all REST endpoints:

```java
public class PeeGeeQRestServer extends AbstractVerticle {
    
    // Constructor requires DatabaseSetupService interface (not implementation)
    public PeeGeeQRestServer(int port, DatabaseSetupService setupService) {
        this.setupService = Objects.requireNonNull(setupService,
            "DatabaseSetupService must be provided - this module depends only on peegeeq-api interfaces");
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        // Creates router, configures handlers, starts HTTP server
    }
}
```

### Handler Initialization Pattern

All handlers are created with interface-only dependencies:

```java
private Router createRouter() {
    // Create handlers - all use DatabaseSetupService interface
    DatabaseSetupHandler setupHandler = new DatabaseSetupHandler(setupService, objectMapper);
    QueueHandler queueHandler = new QueueHandler(setupService, objectMapper);
    EventStoreHandler eventStoreHandler = new EventStoreHandler(setupService, objectMapper, vertx);
    ConsumerGroupHandler consumerGroupHandler = new ConsumerGroupHandler(setupService, objectMapper, subscriptionManagerFactory);
    ServerSentEventsHandler sseHandler = new ServerSentEventsHandler(setupService, objectMapper, vertx, consumerGroupHandler);
    // ... more handlers
}
```

## 4. REST API Reference

### 4.1 Database Setup Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| GET | `/api/v1/setups` | `listSetups` | List all database setups |
| POST | `/api/v1/setups` | `createSetup` | Create a new setup |
| GET | `/api/v1/setups/:setupId` | `getSetupDetails` | Get setup details |
| GET | `/api/v1/setups/:setupId/status` | `getSetupStatus` | Get setup status |
| DELETE | `/api/v1/setups/:setupId` | `deleteSetup` | Delete a setup |
| POST | `/api/v1/setups/:setupId/queues` | `addQueue` | Add queue to setup |
| POST | `/api/v1/setups/:setupId/eventstores` | `addEventStore` | Add event store to setup |

### 4.2 Queue Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| POST | `/api/v1/queues/:setupId/:queueName/messages` | `sendMessage` | Send a single message |
| POST | `/api/v1/queues/:setupId/:queueName/messages/batch` | `sendMessages` | Send batch of messages |
| GET | `/api/v1/queues/:setupId/:queueName/stats` | `getQueueStats` | Get queue statistics |
| GET | `/api/v1/queues/:setupId/:queueName/stream` | `handleQueueStream` | SSE message stream |
| GET | `/api/v1/queues/:setupId/:queueName` | `getQueueDetails` | Get queue details |
| POST | `/api/v1/queues/:setupId/:queueName/purge` | `purgeQueue` | Purge queue messages |

### 4.3 Consumer Group Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `createConsumerGroup` | Create consumer group |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `listConsumerGroups` | List consumer groups |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `getConsumerGroup` | Get consumer group |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `deleteConsumerGroup` | Delete consumer group |
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | `joinConsumerGroup` | Join consumer group |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | `leaveConsumerGroup` | Leave consumer group |

### 4.4 Consumer Group Subscription Options

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| POST | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `updateSubscriptionOptions` | Update subscription options |
| GET | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `getSubscriptionOptions` | Get subscription options |
| DELETE | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `deleteSubscriptionOptions` | Delete subscription options |

### 4.5 Event Store Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `storeEvent` | Store a new event |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `queryEvents` | Query events with filters |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/stream` | `handleEventStream` | SSE event stream |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId` | `getEvent` | Get specific event |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions` | `getAllVersions` | Get all event versions |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at` | `getAsOfTransactionTime` | Bi-temporal query |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections` | `appendCorrection` | Append correction |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/stats` | `getStats` | Get event store stats |

### 4.6 Dead Letter Queue Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| GET | `/api/v1/setups/:setupId/deadletter/messages` | `listMessages` | List DLQ messages |
| GET | `/api/v1/setups/:setupId/deadletter/messages/:messageId` | `getMessage` | Get specific DLQ message |
| POST | `/api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | `reprocessMessage` | Reprocess DLQ message |
| DELETE | `/api/v1/setups/:setupId/deadletter/messages/:messageId` | `deleteMessage` | Delete DLQ message |
| GET | `/api/v1/setups/:setupId/deadletter/stats` | `getStats` | Get DLQ statistics |
| POST | `/api/v1/setups/:setupId/deadletter/cleanup` | `cleanup` | Cleanup old DLQ messages |

### 4.7 Subscription Lifecycle Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| GET | `/api/v1/setups/:setupId/subscriptions/:topic` | `listSubscriptions` | List subscriptions for topic |
| GET | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `getSubscription` | Get specific subscription |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause` | `pauseSubscription` | Pause subscription |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume` | `resumeSubscription` | Resume subscription |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat` | `updateHeartbeat` | Update heartbeat |
| DELETE | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `cancelSubscription` | Cancel subscription |

### 4.8 Health Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| GET | `/api/v1/setups/:setupId/health` | `getOverallHealth` | Get overall health status |
| GET | `/api/v1/setups/:setupId/health/components` | `listComponentHealth` | List component health |
| GET | `/api/v1/setups/:setupId/health/components/:name` | `getComponentHealth` | Get specific component health |

### 4.9 Webhook Subscription Routes (Recommended for Push Delivery)

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| POST | `/api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | `createSubscription` | Create webhook subscription |
| GET | `/api/v1/webhook-subscriptions/:subscriptionId` | `getSubscription` | Get webhook subscription |
| DELETE | `/api/v1/webhook-subscriptions/:subscriptionId` | `deleteSubscription` | Delete webhook subscription |

### 4.10 Management API Routes

| Method | Endpoint | Handler | Description |
|--------|----------|---------|-------------|
| GET | `/api/v1/health` | `getHealth` | Global health check |
| GET | `/api/v1/management/overview` | `getSystemOverview` | System dashboard overview |
| GET | `/api/v1/management/queues` | `getQueues` | List all queues |
| POST | `/api/v1/management/queues` | `createQueue` | Create queue via management |
| PUT | `/api/v1/management/queues/:queueId` | `updateQueue` | Update queue |
| DELETE | `/api/v1/management/queues/:queueId` | `deleteQueue` | Delete queue |
| GET | `/api/v1/management/consumer-groups` | `getConsumerGroups` | List all consumer groups |
| GET | `/api/v1/management/event-stores` | `getEventStores` | List all event stores |
| GET | `/api/v1/management/messages` | `getMessages` | Get recent messages |
| GET | `/api/v1/management/metrics` | `getMetrics` | Get system metrics |

### 4.11 Static and Utility Routes

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ui/*` | Static file serving for Management UI |
| GET | `/` | Redirect to `/ui/` |
| GET | `/health` | Simple health check |
| GET | `/metrics` | Prometheus metrics endpoint |
| WS | `/ws/queues/:setupId/:queueName` | WebSocket message streaming |

## 5. Handler Documentation

### 5.1 QueueHandler

Handles message sending operations with support for single and batch messages.

**Key Methods:**

```java
// Send a single message
public void sendMessage(RoutingContext ctx)

// Send batch of messages
public void sendMessages(RoutingContext ctx)

// Get queue statistics
public void getQueueStats(RoutingContext ctx)
```

**Request Format (Single Message):**

```json
{
  "payload": { "orderId": "12345", "amount": 99.99 },
  "messageType": "OrderCreated",
  "headers": { "priority": "high" },
  "correlationId": "corr-123",
  "messageGroup": "order-12345"
}
```

**Request Format (Batch):**

```json
{
  "messages": [
    { "payload": {...}, "messageType": "OrderCreated" },
    { "payload": {...}, "messageType": "OrderUpdated" }
  ],
  "failOnError": true
}
```

### 5.2 EventStoreHandler

Handles bi-temporal event store operations including append, query, and corrections.

**Key Methods:**

```java
// Store a new event
public void storeEvent(RoutingContext ctx)

// Query events with filters
public void queryEvents(RoutingContext ctx)

// Get all versions of an event (bi-temporal)
public void getAllVersions(RoutingContext ctx)

// Get event as of transaction time (bi-temporal query)
public void getAsOfTransactionTime(RoutingContext ctx)

// Append correction to existing event
public void appendCorrection(RoutingContext ctx)
```

**Event Request Format:**

```json
{
  "eventType": "OrderCreated",
  "eventData": { "orderId": "12345", "amount": 99.99 },
  "validFrom": "2025-07-01T10:00:00Z",
  "correlationId": "corr-123",
  "causationId": "order-12345",
  "metadata": { "source": "web-api" }
}
```

**Correction Request Format:**

```json
{
  "eventType": "OrderUpdated",
  "eventData": { "orderId": "12345", "amount": 109.99 },
  "validFrom": "2025-07-01T10:00:00Z",
  "correctionReason": "Price was incorrect",
  "correlationId": "corr-123"
}
```

### 5.3 ServerSentEventsHandler

Provides real-time message streaming via Server-Sent Events (SSE).

**SSE Connection URL:**
```
GET /api/v1/queues/:setupId/:queueName/stream
```

**Query Parameters:**

| Parameter | Description | Default |
|-----------|-------------|---------|
| `consumerGroup` | Consumer group name | None |
| `batchSize` | Messages per batch | 1 |
| `maxWait` | Max wait time (ms) | 5000 |
| `messageType` | Filter by message type | None |
| `header.*` | Filter by header value | None |

**SSE Event Types:**

| Event Type | Description |
|------------|-------------|
| `connection` | Initial connection confirmation |
| `configured` | Subscription configuration applied |
| `message` | Single message delivery |
| `batch` | Batch of messages |
| `heartbeat` | Keep-alive heartbeat |
| `error` | Error notification |

**SSE Reconnection Support:**
- Uses `Last-Event-ID` header for reconnection
- Messages include `id:` field for resume point tracking

### 5.4 WebSocketHandler

Provides bidirectional real-time communication via WebSocket.

**WebSocket URL:**
```
ws://host:port/ws/queues/:setupId/:queueName
```

**Client Message Types:**

| Type | Description |
|------|-------------|
| `ping` | Keep-alive ping (server responds with `pong`) |
| `subscribe` | Configure subscription options |
| `unsubscribe` | Stop receiving messages |
| `configure` | Update connection configuration |

**Server Message Types:**

| Type | Description |
|------|-------------|
| `welcome` | Initial connection confirmation |
| `pong` | Response to ping |
| `message` | Queue message delivery |
| `error` | Error notification |

### 5.5 DeadLetterHandler

Manages dead letter queue operations for failed messages.

**Key Methods:**

```java
// List DLQ messages with optional topic filter
public void listMessages(RoutingContext ctx)

// Reprocess a failed message
public void reprocessMessage(RoutingContext ctx)

// Delete a DLQ message
public void deleteMessage(RoutingContext ctx)

// Get DLQ statistics
public void getStats(RoutingContext ctx)

// Cleanup old messages
public void cleanup(RoutingContext ctx)
```

### 5.6 HealthHandler

Provides health monitoring endpoints for setup components.

**Response Format (Overall Health):**

```json
{
  "status": "HEALTHY",
  "timestamp": "2025-07-01T10:00:00Z",
  "componentCount": 5,
  "healthyCount": 4,
  "degradedCount": 1,
  "unhealthyCount": 0,
  "components": {
    "database": { "component": "database", "state": "HEALTHY", "message": "Connected" },
    "queue-orders": { "component": "queue-orders", "state": "HEALTHY" }
  }
}
```

## 6. Streaming Capabilities

### 6.1 Server-Sent Events (SSE)

SSE provides one-way server-to-client streaming over HTTP:

```
Client                                    Server
  |                                         |
  |  GET /api/v1/queues/.../stream          |
  | --------------------------------------> |
  |                                         |
  |  event: connection                      |
  |  data: {"connectionId":"sse-1",...}     |
  | <-------------------------------------- |
  |                                         |
  |  event: message                         |
  |  id: msg-123                            |
  |  data: {"payload":{...},...}            |
  | <-------------------------------------- |
  |                                         |
  |  event: heartbeat                       |
  |  data: {"uptime":30000,...}             |
  | <-------------------------------------- |
```

**Features:**
- Automatic reconnection with `Last-Event-ID`
- Message batching with configurable batch size
- Message filtering by type and headers
- Consumer group integration
- Heartbeat keep-alive

### 6.2 WebSocket

WebSocket provides bidirectional communication:

```
Client                                    Server
  |                                         |
  |  WS /ws/queues/:setupId/:queueName      |
  | <=====================================> |
  |                                         |
  |  {"type":"welcome",...}                 |
  | <-------------------------------------- |
  |                                         |
  |  {"type":"subscribe","consumerGroup":..}|
  | --------------------------------------> |
  |                                         |
  |  {"type":"message","payload":{...}}     |
  | <-------------------------------------- |
  |                                         |
  |  {"type":"ping"}                        |
  | --------------------------------------> |
  |                                         |
  |  {"type":"pong"}                        |
  | <-------------------------------------- |
```

### 6.3 Webhook Subscriptions (Push Delivery)

Webhook subscriptions provide push-based message delivery:

```
Client                                    PeeGeeQ                              Webhook URL
  |                                         |                                      |
  |  POST /webhook-subscriptions            |                                      |
  |  {"webhookUrl":"https://..."}           |                                      |
  | --------------------------------------> |                                      |
  |                                         |                                      |
  |  {"subscriptionId":"sub-123",...}       |                                      |
  | <-------------------------------------- |                                      |
  |                                         |                                      |
  |                                         |  POST https://...                    |
  |                                         |  {"messageId":"msg-1","payload":{...}}|
  |                                         | -----------------------------------> |
  |                                         |                                      |
  |                                         |  200 OK                              |
  |                                         | <----------------------------------- |
```

**Webhook Request Format:**

```json
{
  "webhookUrl": "https://example.com/webhook",
  "headers": { "Authorization": "Bearer token" },
  "filters": { "messageType": "OrderCreated" }
}
```

## 7. Error Handling

### HTTP Status Codes

| Code | Meaning | Example |
|------|---------|---------|
| 200 | Success | Message sent, query completed |
| 201 | Created | Setup created, subscription created |
| 204 | No Content | Delete successful |
| 400 | Bad Request | Invalid JSON, missing required field |
| 404 | Not Found | Setup not found, queue not found |
| 409 | Conflict | Consumer group already exists |
| 500 | Server Error | Database error, internal failure |
| 503 | Service Unavailable | Health check failed |

### Error Response Format

```json
{
  "error": "Setup not found: my-setup",
  "timestamp": 1719828000000
}
```

### Expected vs Unexpected Errors

The handlers distinguish between expected and unexpected errors:

```java
// Expected error - logged at DEBUG level, no stack trace
if (isSetupNotFoundError(cause)) {
    logger.debug("EXPECTED: Setup not found for queue: {} (setup: {})", queueName, setupId);
    sendError(ctx, 404, "Setup not found: " + setupId);
} else {
    // Unexpected error - logged at ERROR level with stack trace
    logger.error("Error processing request: " + queueName, throwable);
    sendError(ctx, 500, "Failed to process: " + throwable.getMessage());
}
```

## 8. Configuration

### Server Configuration

The server is configured via constructor parameters:

```java
PeeGeeQRestServer server = new PeeGeeQRestServer(
    8080,                    // Port
    databaseSetupService     // Injected service (interface)
);
```

### CORS Configuration

CORS is configured to allow cross-origin requests:

```java
CorsHandler.create()
    .allowedMethod(HttpMethod.GET)
    .allowedMethod(HttpMethod.POST)
    .allowedMethod(HttpMethod.PUT)
    .allowedMethod(HttpMethod.DELETE)
    .allowedMethod(HttpMethod.OPTIONS)
    .allowedHeader("Content-Type")
    .allowedHeader("Authorization");
```

### Jackson Configuration

Jackson is configured with Java Time module for `Instant` serialization:

```java
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JavaTimeModule());
DatabindCodec.mapper().registerModule(new JavaTimeModule());
```

## 9. Dependencies

### Maven Dependencies

```xml
<dependencies>
    <!-- PeeGeeQ Modules -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-api</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-runtime</artifactId>
    </dependency>

    <!-- Vert.x -->
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-web-client</artifactId>
    </dependency>

    <!-- Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>

    <!-- Metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
</dependencies>
```

## 10. Testing

### Test Categories

The module uses Maven profiles for different test categories:

| Profile | Description | Timeout |
|---------|-------------|---------|
| `core-tests` | Fast unit tests | 60s |
| `integration-tests` | Integration tests | 300s |
| `performance-tests` | Performance tests | 600s |
| `smoke-tests` | Quick validation | 120s |
| `slow-tests` | Long-running tests | 600s |
| `all-tests` | All tests | 600s |

### Running Tests

```bash
# Run core tests only
mvn test -Pcore-tests

# Run integration tests
mvn test -Pintegration-tests

# Run all tests
mvn test -Pall-tests
```

### Test Files

The module includes 33 test classes covering:
- Handler unit tests
- Integration tests with TestContainers
- SSE streaming tests
- WebSocket tests
- Webhook subscription tests
- Phase-specific tests (Phase 2, 3, 4)

## 11. Design Patterns

### Handler Pattern

Each REST resource has a dedicated handler class:

```java
public class QueueHandler {
    private final DatabaseSetupService setupService;  // Interface only
    private final ObjectMapper objectMapper;

    public QueueHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }

    public void sendMessage(RoutingContext ctx) {
        // Handle request
    }
}
```

### Async Pattern

All handlers use `CompletableFuture` chains for async operations:

```java
setupService.getSetupResult(setupId)
    .thenAccept(setupResult -> {
        // Process result
        ctx.response().end(response.encode());
    })
    .exceptionally(throwable -> {
        sendError(ctx, 500, "Failed: " + throwable.getMessage());
        return null;
    });
```

### Connection Management Pattern

Streaming handlers maintain connection state:

```java
private final Map<String, SSEConnection> activeConnections = new ConcurrentHashMap<>();
private final AtomicLong connectionIdCounter = new AtomicLong(0);

public void handleQueueStream(RoutingContext ctx) {
    String connectionId = "sse-" + connectionIdCounter.incrementAndGet();
    SSEConnection connection = new SSEConnection(connectionId, response, setupId, queueName);
    activeConnections.put(connectionId, connection);

    ctx.request().connection().closeHandler(v -> handleConnectionClose(connection));
}
```

## 12. Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-07-18 | Initial REST API with setup and queue operations |
| 2.0 | 2025-07-19 | Added SSE, WebSocket, consumer groups |
| 2.1 | 2025-11-22 | Added webhook subscriptions |
| 2.2 | 2025-12-05 | Added dead letter, subscription lifecycle, health APIs |

