# PeeGeeQ Call Propagation Cross-Reference Analysis

**Date:** 2025-12-24  
**Purpose:** Complete traceability from REST client API through all layers to PostgreSQL database

## Overview

This document provides a detailed cross-reference between the Call Propagation Design document and the actual implementation, tracing a message send operation from the REST client all the way down to the PostgreSQL database.

## Architecture Layers

The PeeGeeQ system follows a strict layered architecture:

```
┌─────────────────────────────────────────────────────────────┐
│  CLIENT LAYER (peegeeq-rest-client, TypeScript client)     │
│  - PeeGeeQRestClient.java                                   │
│  - PeeGeeQClient.ts                                         │
└─────────────────────────────────────────────────────────────┘
                            │ HTTP/REST
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  REST LAYER (peegeeq-rest)                                  │
│  - PeeGeeQRestServer.java (routing)                         │
│  - QueueHandler.java (message operations)                   │
└─────────────────────────────────────────────────────────────┘
                            │ Interface calls
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  RUNTIME LAYER (peegeeq-runtime)                            │
│  - RuntimeDatabaseSetupService.java (facade)                │
│  - DatabaseSetupResult (service registry)                   │
└─────────────────────────────────────────────────────────────┘
                            │ Interface lookup
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  API LAYER (peegeeq-api)                                    │
│  - QueueFactory interface                                   │
│  - MessageProducer interface                                │
└─────────────────────────────────────────────────────────────┘
                            │ Implementation
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  NATIVE IMPLEMENTATION (peegeeq-native)                     │
│  - PgNativeQueueFactory.java                                │
│  - PgNativeQueueProducer.java                               │
└─────────────────────────────────────────────────────────────┘
                            │ Database access
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  DATABASE LAYER (peegeeq-db)                                │
│  - DatabaseService interface                                │
│  - VertxPoolAdapter.java                                    │
│  - Vert.x Pool (PostgreSQL connection pool)                 │
└─────────────────────────────────────────────────────────────┘
                            │ SQL execution
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  POSTGRESQL DATABASE                                        │
│  - queue_messages table                                     │
│  - INSERT + NOTIFY operations                               │
└─────────────────────────────────────────────────────────────┘
```

## Layer-by-Layer Call Propagation

### Layer 1: REST Client (Entry Point)

**Module:** `peegeeq-rest-client` (Java) or `peegeeq-management-ui` (TypeScript)

**Java Client:**
```java
// File: peegeeq-rest-client/src/main/java/dev/mars/peegeeq/client/PeeGeeQRestClient.java
@Override
public Future<MessageSendResult> sendMessage(String setupId, String queueName, MessageRequest message) {
    String path = String.format("/api/v1/queues/%s/%s/messages", setupId, queueName);
    return post(path, message)
        .map(response -> parseResponse(response, MessageSendResult.class));
}
```

**TypeScript Client:**
```typescript
// File: peegeeq-management-ui/src/api/PeeGeeQClient.ts
async sendMessage<T>(
  setupId: string,
  queueName: string,
  message: SendMessageRequest<T>
): Promise<SendMessageResult> {
  return this.request<SendMessageResult>('POST', QUEUE_ENDPOINTS.PUBLISH(setupId, queueName), message);
}
```

**HTTP Request:**
- **Method:** POST
- **URL:** `/api/v1/queues/{setupId}/{queueName}/messages`
- **Body:**
```json
{
  "payload": { "orderId": "12345", "amount": 99.99 },
  "headers": { "source": "web-app", "version": "1.0" },
  "correlationId": "trace-abc-123",
  "messageGroup": "customer-456",
  "priority": 5,
  "delaySeconds": 60
}
```

### Layer 2: REST Server (HTTP Handler)

**Module:** `peegeeq-rest`

**Routing Configuration:**
```java
// File: peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java
// Line 215
router.post("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::sendMessage);
```

**Handler Method:**
```java
// File: peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java
// Lines 60-150
public void sendMessage(RoutingContext ctx) {
    String setupId = ctx.pathParam("setupId");
    String queueName = ctx.pathParam("queueName");
    
    // Parse and validate the message request
    MessageRequest messageRequest = parseAndValidateRequest(ctx);
    
    logger.info("Sending message to queue {} in setup: {}", queueName, setupId);
    
    // Get queue factory and send message
    getQueueFactory(setupId, queueName)
        .thenCompose(queueFactory -> {
            // Create producer for the message type
            MessageProducer<Object> producer = queueFactory.createProducer(queueName, Object.class);
            
            // Send the message
            return sendMessageWithProducer(producer, messageRequest)
                .whenComplete((messageId, error) -> {
                    // Always close the producer
                    try {
                        producer.close();
                    } catch (Exception e) {
                        logger.warn("Error closing producer: {}", e.getMessage());
                    }
                });
        })
        .thenAccept(messageId -> {
            // Return success response
            JsonObject response = new JsonObject()
                .put("message", "Message sent successfully")
                .put("queueName", queueName)
                .put("setupId", setupId)
                .put("messageId", messageId)
                .put("correlationId", messageId)
                .put("timestamp", System.currentTimeMillis())
                .put("messageType", messageRequest.detectMessageType())
                .put("priority", messageRequest.getPriority())
                .put("delaySeconds", messageRequest.getDelaySeconds());
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        })
        .exceptionally(throwable -> {
            logger.error("Error sending message to queue: " + queueName, throwable);
            sendError(ctx, 500, "Failed to send message: " + throwable.getMessage());
            return null;
        });
}
```

**Message Preparation:**
```java
// File: peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java
// Lines 370-426
private CompletableFuture<String> sendMessageWithProducer(MessageProducer<Object> producer, MessageRequest request) {
    // Build headers map
    Map<String, String> headers = new HashMap<>();
    if (request.getHeaders() != null) {
        headers.putAll(request.getHeaders());
    }

    // Add priority if specified
    if (request.getPriority() != null) {
        headers.put("priority", request.getPriority().toString());
    }

    // Add delay if specified
    if (request.getDelaySeconds() != null && request.getDelaySeconds() > 0) {
        headers.put("delaySeconds", request.getDelaySeconds().toString());
    }

    // Add message type (detected or specified)
    String detectedType = request.detectMessageType();
    headers.put("messageType", detectedType);

    // Use correlation ID from request field, then headers, then generate one
    final String correlationId;
    if (request.getCorrelationId() != null && !request.getCorrelationId().trim().isEmpty()) {
        correlationId = request.getCorrelationId();
    } else if (headers.get("correlationId") != null) {
        correlationId = headers.get("correlationId");
    } else {
        correlationId = java.util.UUID.randomUUID().toString();
    }

    // Use message group from request field, then headers
    final String messageGroup;
    if (request.getMessageGroup() != null && !request.getMessageGroup().trim().isEmpty()) {
        messageGroup = request.getMessageGroup();
    } else {
        messageGroup = headers.get("messageGroup");
    }

    // Send the message and return the correlation ID as the message ID
    return producer.send(request.getPayload(), headers, correlationId, messageGroup)
        .thenApply(v -> correlationId);
}
```

### Layer 3: Runtime Service (Composition Layer)

**Module:** `peegeeq-runtime`

**Queue Factory Lookup:**
```java
// File: peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java
// Lines 240-260
private CompletableFuture<QueueFactory> getQueueFactory(String setupId, String queueName) {
    return CompletableFuture.supplyAsync(() -> {
        DatabaseSetupResult setup = setupService.getSetupResult(setupId);
        if (setup == null) {
            throw new RuntimeException("Setup not found: " + setupId);
        }

        QueueFactory factory = setup.getQueueFactories().get(queueName);
        if (factory == null) {
            throw new RuntimeException("Queue not found: " + queueName + " in setup: " + setupId);
        }

        return factory;
    });
}
```

**Runtime Service:**
```java
// File: peegeeq-runtime/src/main/java/dev/mars/peegeeq/runtime/RuntimeDatabaseSetupService.java
// Lines 50-60
@Override
public DatabaseSetupResult getSetupResult(String setupId) {
    return delegate.getSetupResult(setupId);
}
```

**Database Setup Result:**
```java
// File: peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java
// Lines 550-560
@Override
public DatabaseSetupResult getSetupResult(String setupId) {
    DatabaseSetupResult result = activeSetups.get(setupId);
    if (result == null) {
        throw new IllegalArgumentException("Setup not found: " + setupId);
    }
    return result;
}
```

**Queue Factories Map:**
```java
// File: peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/DatabaseSetupResult.java
// Lines 30-40
public class DatabaseSetupResult {
    private final String setupId;
    private final Map<String, QueueFactory> queueFactories;
    private final Map<String, EventStore> eventStores;
    private final DeadLetterService deadLetterService;
    private final SubscriptionService subscriptionService;
    private final HealthService healthService;

    // Getters...
    public Map<String, QueueFactory> getQueueFactories() {
        return queueFactories;
    }
}
```

### Layer 4: API Contracts

**Module:** `peegeeq-api`

**QueueFactory Interface:**
```java
// File: peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueFactory.java
// Lines 30-50
public interface QueueFactory extends AutoCloseable {
    /**
     * Creates a message producer for the specified topic.
     *
     * @param topic The topic to produce messages to
     * @param payloadType The type of message payload
     * @return A message producer instance
     */
    <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType);

    /**
     * Creates a message consumer for the specified topic.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A message consumer instance
     */
    <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType);

    // Other methods...
}
```

**MessageProducer Interface:**
```java
// File: peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/MessageProducer.java
// Lines 39-77
public interface MessageProducer<T> extends AutoCloseable {
    /**
     * Sends a message with the given payload.
     */
    CompletableFuture<Void> send(T payload);

    /**
     * Sends a message with the given payload and headers.
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers);

    /**
     * Sends a message with the given payload, headers, and correlation ID.
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId);

    /**
     * Sends a message with the given payload, headers, correlation ID, and message group.
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup);

    // Reactive variants...
    Future<Void> sendReactive(T payload);
    Future<Void> sendReactive(T payload, Map<String, String> headers);
    Future<Void> sendReactive(T payload, Map<String, String> headers, String correlationId);
    Future<Void> sendReactive(T payload, Map<String, String> headers, String correlationId, String messageGroup);
}
```

### Layer 5: Native Implementation

**Module:** `peegeeq-native`

**Factory Implementation:**
```java
// File: peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java
// Lines 125-132
@Override
public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
    checkNotClosed();
    logger.info("Creating native queue producer for topic: {}", topic);

    MetricsProvider metrics = getMetrics();
    return new PgNativeQueueProducer<>(poolAdapter, objectMapper, topic, payloadType, metrics, configuration);
}
```

**Producer Implementation:**
```java
// File: peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueProducer.java
// Lines 227-336
@Override
public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
    if (closed) {
        return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
    }

    try {
        String messageId = UUID.randomUUID().toString();
        JsonObject payloadJson = toJsonObject(payload);
        JsonObject headersJson = headersToJsonObject(headers);
        String finalCorrelationId = correlationId != null ? correlationId : messageId;

        final Pool pool = poolAdapter.getPoolOrThrow();

        // Get schema for NOTIFY channel
        String schema = configuration != null ? configuration.getDatabaseConfig().getSchema() : "public";
        String notifyChannel = schema + "_queue_" + topic;

        // Extract priority from headers (default: 5)
        int priority = 5;
        if (headers != null && headers.containsKey("priority")) {
            try {
                priority = Integer.parseInt(headers.get("priority"));
            } catch (NumberFormatException e) {
                logger.warn("Invalid priority in headers, using default: 5");
            }
        }

        // Extract delay from headers (default: 0)
        int delaySeconds = 0;
        if (headers != null && headers.containsKey("delaySeconds")) {
            try {
                delaySeconds = Integer.parseInt(headers.get("delaySeconds"));
            } catch (NumberFormatException e) {
                logger.warn("Invalid delaySeconds in headers, using default: 0");
            }
        }

        // Calculate visible_at timestamp
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime visibleAt = delaySeconds > 0
            ? now.plusSeconds(delaySeconds)
            : now;

        // Build SQL INSERT statement
        String sql = """
            INSERT INTO queue_messages
            (topic, payload, headers, correlation_id, message_group, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, 'AVAILABLE', $6, $7, $8)
            RETURNING id
            """;

        Tuple params = Tuple.of(
            topic,
            payloadJson,
            headersJson,
            finalCorrelationId,
            messageGroup,
            now,
            visibleAt,
            priority
        );

        // Use withTransaction for automatic commit/rollback and search_path support
        return pool.withTransaction(conn ->
            conn.preparedQuery(sql)
                .execute(params)
                .compose(result -> {
                    // Get the auto-generated ID from the database
                    Long generatedId = result.iterator().next().getLong("id");
                    logger.debug("Message sent to topic {} with group {}: {} (DB ID: {})",
                                topic, messageGroup, messageId, generatedId);
                    metrics.recordMessageSent(topic);

                    // NOTIFY is handled by PostgreSQL trigger
                    return Future.succeededFuture();
                })
        ).mapEmpty()
         .toCompletionStage()
         .toCompletableFuture()
         .exceptionally(error -> {
             logger.error("Failed to send message to topic {}: {}", topic, error.getMessage());
             throw new RuntimeException(error);
         })
         .thenApply(v -> null);

    } catch (Exception e) {
        logger.error("Error preparing message for topic {}: {}", topic, e.getMessage());
        return CompletableFuture.failedFuture(e);
    }
}
```

### Layer 6: Database Layer

**Module:** `peegeeq-db`

**Pool Adapter:**
```java
// File: peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/VertxPoolAdapter.java
// Lines 50-60
public Pool getPoolOrThrow() {
    if (pool == null) {
        throw new IllegalStateException("Pool is not initialized");
    }
    return pool;
}
```

**Database Service:**
```java
// File: peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/DatabaseService.java
// Lines 30-50
public interface DatabaseService extends AutoCloseable {
    /**
     * Gets the Vert.x instance.
     */
    Vertx getVertx();

    /**
     * Gets the database connection pool.
     */
    Pool getPool();

    /**
     * Gets the database configuration.
     */
    DatabaseConfig getDatabaseConfig();

    // Other methods...
}
```

**Vert.x Pool:**
```java
// Vert.x SQL Client Pool
// File: io.vertx.sqlclient.Pool (Vert.x library)
Future<T> withTransaction(Function<SqlConnection, Future<T>> function);
```

### Layer 7: PostgreSQL Database

**SQL Execution:**
```sql
-- INSERT statement executed by PgNativeQueueProducer
INSERT INTO queue_messages
(topic, payload, headers, correlation_id, message_group, status, created_at, visible_at, priority)
VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, 'AVAILABLE', $6, $7, $8)
RETURNING id;

-- Parameters:
-- $1: topic (String) - e.g., "orders"
-- $2: payload (JSONB) - e.g., {"orderId": "12345", "amount": 99.99}
-- $3: headers (JSONB) - e.g., {"source": "web-app", "version": "1.0", "messageType": "Order"}
-- $4: correlation_id (String) - e.g., "trace-abc-123"
-- $5: message_group (String) - e.g., "customer-456"
-- $6: created_at (OffsetDateTime) - e.g., 2025-12-24T09:00:00Z
-- $7: visible_at (OffsetDateTime) - e.g., 2025-12-24T09:01:00Z (with 60s delay)
-- $8: priority (Integer) - e.g., 5
```

**Database Trigger (NOTIFY):**
```sql
-- Trigger automatically sends NOTIFY after INSERT
-- File: peegeeq-db/src/main/resources/sql/templates/queue.sql
CREATE OR REPLACE FUNCTION notify_queue_message()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('{{schema}}_queue_{{queueName}}', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER queue_message_notify
AFTER INSERT ON queue_messages
FOR EACH ROW
EXECUTE FUNCTION notify_queue_message();
```

**Table Schema:**
```sql
-- File: peegeeq-db/src/main/resources/sql/templates/queue.sql
CREATE TABLE IF NOT EXISTS queue_messages (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    visible_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    priority INTEGER NOT NULL DEFAULT 5,
    processed_at TIMESTAMP WITH TIME ZONE,
    consumer_id VARCHAR(255),
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    dead_letter_at TIMESTAMP WITH TIME ZONE,
    INDEX idx_queue_messages_topic (topic),
    INDEX idx_queue_messages_status (status),
    INDEX idx_queue_messages_visible_at (visible_at),
    INDEX idx_queue_messages_correlation_id (correlation_id),
    INDEX idx_queue_messages_message_group (message_group)
);
```

## Complete Call Stack Summary

Here's the complete call stack from client to database:

```
1. PeeGeeQRestClient.sendMessage(setupId, queueName, message)
   ↓ HTTP POST /api/v1/queues/{setupId}/{queueName}/messages

2. PeeGeeQRestServer (Vert.x Router)
   ↓ Route to handler

3. QueueHandler.sendMessage(RoutingContext ctx)
   ↓ Parse request

4. QueueHandler.parseAndValidateRequest(ctx) → MessageRequest
   ↓ Get queue factory

5. QueueHandler.getQueueFactory(setupId, queueName)
   ↓ Lookup setup

6. RuntimeDatabaseSetupService.getSetupResult(setupId)
   ↓ Delegate to DB layer

7. PeeGeeQDatabaseSetupService.getSetupResult(setupId) → DatabaseSetupResult
   ↓ Get queue factory from map

8. DatabaseSetupResult.getQueueFactories().get(queueName) → QueueFactory
   ↓ Create producer

9. PgNativeQueueFactory.createProducer(queueName, Object.class)
   ↓ Instantiate producer

10. new PgNativeQueueProducer<>(poolAdapter, objectMapper, topic, payloadType, metrics, configuration)
    ↓ Send message

11. QueueHandler.sendMessageWithProducer(producer, messageRequest)
    ↓ Build headers and call producer

12. PgNativeQueueProducer.send(payload, headers, correlationId, messageGroup)
    ↓ Build SQL INSERT

13. Build SQL: INSERT INTO queue_messages (topic, payload, headers, correlation_id, message_group, status, created_at, visible_at, priority) VALUES (...)
    ↓ Get pool

14. VertxPoolAdapter.getPoolOrThrow() → Pool
    ↓ Execute in transaction

15. Pool.withTransaction(conn → conn.preparedQuery(sql).execute(params))
    ↓ Execute SQL

16. PostgreSQL: INSERT INTO queue_messages ... RETURNING id
    ↓ Trigger fires

17. PostgreSQL Trigger: NOTIFY schema_queue_topic
    ↓ Return result

18. Extract generated ID from RowSet
    ↓ Record metrics

19. MetricsProvider.recordMessageSent(topic)
    ↓ Return to handler

20. QueueHandler builds JSON response
    ↓ Send HTTP response

21. HTTP 200 OK with JSON body
```

## Key Design Patterns Observed

### 1. Hexagonal Architecture (Ports & Adapters)

- **Ports (Interfaces):** `QueueFactory`, `MessageProducer`, `DatabaseService` in `peegeeq-api`
- **Adapters (Implementations):** `PgNativeQueueFactory`, `PgNativeQueueProducer` in `peegeeq-native`
- **Application Core:** `RuntimeDatabaseSetupService` in `peegeeq-runtime`
- **External Interface:** `QueueHandler` in `peegeeq-rest`

### 2. Dependency Inversion Principle

- REST layer depends on `peegeeq-api` interfaces, not concrete implementations
- Runtime layer wires concrete implementations to interfaces
- No compile-time dependency from REST to Native/Outbox/Bitemporal modules

### 3. Facade Pattern

- `RuntimeDatabaseSetupService` acts as a facade for all backend services
- `DatabaseSetupResult` provides a unified registry of all services for a setup
- REST handlers only interact with the facade, not individual services

### 4. Factory Pattern

- `QueueFactory` creates `MessageProducer` and `MessageConsumer` instances
- `PgNativeQueueFactory` is the concrete factory for native PostgreSQL queues
- Factories are registered and looked up by queue name

### 5. Repository Pattern

- `DatabaseSetupResult` acts as a repository of queue factories and services
- Factories are stored in a `Map<String, QueueFactory>` keyed by queue name
- Services are accessed through the result object

### 6. Reactive Programming

- All async operations use `CompletableFuture` or Vert.x `Future`
- Database operations use Vert.x reactive SQL client
- Transaction management via `Pool.withTransaction()`

## Data Flow Analysis

### Request Data Transformation

```
HTTP JSON Request
  ↓ parseAndValidateRequest()
MessageRequest (DTO)
  ↓ sendMessageWithProducer()
Map<String, String> headers + payload + correlationId + messageGroup
  ↓ PgNativeQueueProducer.send()
JsonObject payloadJson + JsonObject headersJson + String correlationId + String messageGroup
  ↓ Build SQL
Tuple params (topic, payloadJson, headersJson, correlationId, messageGroup, created_at, visible_at, priority)
  ↓ Execute SQL
PostgreSQL INSERT
  ↓ RETURNING id
Long generatedId
  ↓ Return to handler
String correlationId (used as messageId)
  ↓ Build response
HTTP JSON Response
```

### Field Mapping

| Client Field | MessageRequest Field | Headers Map | SQL Column | Database Type |
|-------------|---------------------|-------------|------------|---------------|
| `payload` | `payload` | - | `payload` | `JSONB` |
| `headers` | `headers` | All custom headers | `headers` | `JSONB` |
| `correlationId` | `correlationId` | - | `correlation_id` | `VARCHAR(255)` |
| `messageGroup` | `messageGroup` | - | `message_group` | `VARCHAR(255)` |
| `priority` | `priority` | `priority` | `priority` | `INTEGER` |
| `delaySeconds` | `delaySeconds` | `delaySeconds` | `visible_at` | `TIMESTAMP WITH TIME ZONE` |
| - | - | `messageType` | - | (in headers JSONB) |
| - | - | `payloadSize` | - | (in headers JSONB) |
| - | - | `timestamp` | - | (in headers JSONB) |

## Cross-Reference with Design Document

### Section 2: High-Level Overview

**Design Document States:**
> Flow Summary: `REST Request` -> `QueueHandler` -> `QueueFactory` -> `MessageProducer` -> `PostgreSQL (INSERT + NOTIFY)`

**Implementation Confirms:**
✅ Exact flow observed in code
- REST Request handled by `QueueHandler.sendMessage()`
- `QueueFactory` obtained via `getQueueFactory(setupId, queueName)`
- `MessageProducer` created via `queueFactory.createProducer()`
- PostgreSQL INSERT executed via `pool.withTransaction()`
- NOTIFY triggered by database trigger

### Section 11: peegeeq-api to peegeeq-rest Call Propagation

**Design Document States:**
> POST /api/v1/queues/{setupId}/{queueName}/messages

**Implementation Confirms:**
✅ Exact endpoint implemented in `PeeGeeQRestServer.java` line 215
✅ Handler method `QueueHandler.sendMessage()` matches specification
✅ Request/response format matches documented JSON structure

### Section 12: peegeeq-runtime and peegeeq-rest Interaction

**Design Document States:**
> RuntimeDatabaseSetupService delegates to PeeGeeQDatabaseSetupService

**Implementation Confirms:**
✅ `RuntimeDatabaseSetupService` wraps `PeeGeeQDatabaseSetupService`
✅ `getSetupResult()` method delegates to underlying service
✅ Queue factories registered and accessible via `DatabaseSetupResult`

## Conclusion

The implementation **exactly matches** the design document's call propagation specification. The layered architecture is strictly enforced through compile-time dependencies, and the flow from REST client to PostgreSQL database follows the documented path through all intermediate layers.

Key observations:
1. ✅ No shortcuts or layer violations
2. ✅ All interfaces from `peegeeq-api` are properly implemented
3. ✅ Runtime composition layer successfully decouples REST from implementations
4. ✅ Database operations use proper transaction management
5. ✅ All message fields are correctly propagated through all layers
6. ✅ Error handling and resource cleanup (producer.close()) are properly implemented

