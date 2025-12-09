# PeeGeeQ Native Module Guide

## 1. Overview

The `peegeeq-native` module provides the core PostgreSQL-based message queue implementation for PeeGeeQ. It leverages PostgreSQL's native features - LISTEN/NOTIFY for real-time notifications and advisory locks for reliable message processing - to deliver a production-ready, database-backed message queue without external dependencies.

### Key Characteristics

| Characteristic | Description |
|----------------|-------------|
| **Real-time Delivery** | PostgreSQL LISTEN/NOTIFY for instant message notifications |
| **Reliable Processing** | Advisory locks and FOR UPDATE SKIP LOCKED for concurrent safety |
| **Flexible Modes** | LISTEN_NOTIFY_ONLY, POLLING_ONLY, or HYBRID consumer modes |
| **Consumer Groups** | Load balancing and message filtering across multiple consumers |
| **Dead Letter Queue** | Automatic retry with configurable max retries and DLQ support |
| **Metrics** | Integration with PeeGeeQMetrics for monitoring |

### Module Purpose

```
+------------------+                    +------------------+
|  Application     |                    |  peegeeq-native  |
|  Code            | -----------------> |  (This Module)   |
+------------------+                    +------------------+
                                               |
                                               | Implements
                                               v
                                        +------------------+
                                        |  peegeeq-api     |
                                        |  (Interfaces)    |
                                        +------------------+
                                               |
                                               | Uses
                                               v
                                        +------------------+
                                        |  peegeeq-db      |
                                        |  (DB Access)     |
                                        +------------------+
                                               |
                                               | LISTEN/NOTIFY
                                               | Advisory Locks
                                               v
                                        +------------------+
                                        |  PostgreSQL      |
                                        +------------------+
```

## 2. Architecture Position

### Dependency Rules

| Module | Allowed Dependencies | Forbidden Dependencies |
|--------|---------------------|------------------------|
| `peegeeq-native` | `peegeeq-api`, `peegeeq-db` | `peegeeq-rest`, `peegeeq-outbox`, `peegeeq-bitemporal` |

The native module is an **implementation module** that:
- **Implements interfaces from `peegeeq-api`** (QueueFactory, MessageProducer, MessageConsumer, ConsumerGroup)
- **Uses `peegeeq-db`** for database access (PgClientFactory, connection management)
- **Is composed by `peegeeq-runtime`** for wiring into the application

### Module Structure

```
peegeeq-native/
├── pom.xml
├── docs/
│   ├── PEEGEEQ_NATIVE_MODULE_GUIDE.md (this file)
│   ├── MISSING_TEST_COVERAGE_ANALYSIS.md
│   └── TEST-CATEGORIZATION-GUIDE.md
└── src/
    ├── main/java/dev/mars/peegeeq/pgqueue/
    │   ├── PgNativeQueueFactory.java      # Factory for producers/consumers (386 lines)
    │   ├── PgNativeQueueProducer.java     # Message producer (321 lines)
    │   ├── PgNativeQueueConsumer.java     # Message consumer (1176 lines)
    │   ├── PgNativeConsumerGroup.java     # Consumer group (370 lines)
    │   ├── PgNativeConsumerGroupMember.java # Group member
    │   ├── PgNativeQueue.java             # ReactiveQueue implementation (249 lines)
    │   ├── PgNativeMessage.java           # Message implementation
    │   ├── PgNotificationStream.java      # ReadStream for notifications (169 lines)
    │   ├── VertxPoolAdapter.java          # Pool adapter (232 lines)
    │   ├── ConsumerConfig.java            # Consumer configuration (148 lines)
    │   ├── ConsumerMode.java              # Consumer mode enum (44 lines)
    │   ├── EmptyReadStream.java           # Empty stream implementation
    │   └── PgNativeFactoryRegistrar.java  # Factory registration
    └── test/java/dev/mars/peegeeq/pgqueue/
        └── (49 test classes)
```

## 3. Core Components

### 3.1 PgNativeQueueFactory

The factory class implements `QueueFactory` interface and creates producers, consumers, and consumer groups.

```java
public class PgNativeQueueFactory implements QueueFactory {
    
    // Create with DatabaseService (new interface)
    public PgNativeQueueFactory(DatabaseService databaseService) { ... }
    
    // Create with PgClientFactory (legacy)
    public PgNativeQueueFactory(PgClientFactory clientFactory) { ... }
    
    // Create producer for a topic
    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) { ... }
    
    // Create consumer for a topic
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) { ... }
    
    // Create consumer with custom configuration
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, 
                                                  Object consumerConfig) { ... }
    
    // Create consumer group
    @Override
    public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, 
                                                     Class<T> payloadType) { ... }
    
    @Override
    public String getImplementationType() { return "native"; }
    
    @Override
    public boolean isHealthy() { ... }
}
```

### 3.2 PgNativeQueueProducer

Sends messages to PostgreSQL queue with NOTIFY for real-time delivery.

```java
public class PgNativeQueueProducer<T> implements MessageProducer<T> {
    
    // Send message with optional headers, correlationId, and messageGroup
    @Override
    public CompletableFuture<Void> send(T payload) { ... }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers) { ... }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, 
                                        String correlationId) { ... }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, 
                                        String correlationId, String messageGroup) { ... }
}
```

**Message Storage:**
- Inserts into `queue_messages` table with JSONB payload
- Sends `pg_notify()` to wake up consumers immediately
- Supports priority (1-10), delay (via `visible_at`), and message groups

### 3.3 PgNativeQueueConsumer

Consumes messages using LISTEN/NOTIFY and/or polling with configurable modes.

```java
public class PgNativeQueueConsumer<T> implements MessageConsumer<T> {

    // Start consuming with handler
    @Override
    public CompletableFuture<Void> consume(Consumer<Message<T>> handler) { ... }

    // Consume with filter
    @Override
    public CompletableFuture<Void> consume(Consumer<Message<T>> handler,
                                           Predicate<Message<T>> filter) { ... }

    // Acknowledge message (delete from queue)
    @Override
    public CompletableFuture<Void> acknowledge(Message<T> message) { ... }

    // Reject message (retry or move to DLQ)
    @Override
    public CompletableFuture<Void> reject(Message<T> message, boolean requeue) { ... }

    // Stop consuming
    @Override
    public CompletableFuture<Void> stop() { ... }
}
```

**Message Processing Flow:**

```
1. LISTEN/NOTIFY triggers immediate processing
   OR
   Polling timer fires at configured interval
                    |
                    v
2. Claim messages with FOR UPDATE SKIP LOCKED
   (Atomic, concurrent-safe)
                    |
                    v
3. Process message via handler
                    |
                    v
4. On success: DELETE from queue_messages
   On failure: Increment retry_count
               If retry_count >= max_retries: Move to dead_letter_queue
               Else: Update visible_at for retry delay
```

## 4. Consumer Modes

The module supports three consumer modes via `ConsumerMode` enum:

| Mode | LISTEN/NOTIFY | Polling | Best For |
|------|---------------|---------|----------|
| `LISTEN_NOTIFY_ONLY` | Yes | No | Real-time apps, reliable connections |
| `POLLING_ONLY` | No | Yes | Batch processing, unreliable networks |
| `HYBRID` (default) | Yes | Yes | Critical apps requiring guaranteed delivery |

### ConsumerConfig

```java
ConsumerConfig config = ConsumerConfig.builder()
    .mode(ConsumerMode.HYBRID)           // Default: HYBRID
    .pollingInterval(Duration.ofSeconds(1)) // Default: 1 second
    .enableNotifications(true)           // Default: true
    .batchSize(10)                       // Default: 10
    .consumerThreads(1)                  // Default: 1
    .build();

MessageConsumer<MyMessage> consumer = factory.createConsumer(
    "my-topic",
    MyMessage.class,
    config
);
```

### Mode Selection Guide

```
                    Need real-time delivery?
                           |
              +------------+------------+
              |                         |
             Yes                        No
              |                         |
    Connection reliable?          POLLING_ONLY
              |
    +---------+---------+
    |                   |
   Yes                  No
    |                   |
LISTEN_NOTIFY_ONLY    HYBRID
```

## 5. Consumer Groups

Consumer groups enable load balancing across multiple consumers.

```java
// Create consumer group
ConsumerGroup<OrderEvent> group = factory.createConsumerGroup(
    "order-processors",  // Group name
    "orders",            // Topic
    OrderEvent.class     // Payload type
);

// Add members
group.addMember("member-1");
group.addMember("member-2");
group.addMember("member-3");

// Start consuming
group.consume(message -> {
    // Process message
    // Messages are distributed across members
});
```

### Load Balancing

Messages are distributed using round-robin based on message ID hash:

```
Message ID: 12345
Members: [member-1, member-2, member-3]
Target: members[12345 % 3] = member-1
```

### Message Filtering

```java
// Group-level filter
group.setFilter(msg -> msg.getPayload().getPriority() > 5);

// Member-level filter
group.getMember("member-1").setFilter(msg ->
    msg.getPayload().getRegion().equals("US")
);
```

## 6. PostgreSQL LISTEN/NOTIFY

The module uses PostgreSQL's native pub/sub mechanism for real-time message delivery.

### How It Works

```
Producer                    PostgreSQL                    Consumer
   |                            |                            |
   | INSERT INTO queue_messages |                            |
   |--------------------------->|                            |
   |                            |                            |
   | SELECT pg_notify('topic')  |                            |
   |--------------------------->|                            |
   |                            | NOTIFY 'topic'             |
   |                            |--------------------------->|
   |                            |                            |
   |                            |                            | Process message
```

### Dedicated Connection

LISTEN requires a dedicated, non-pooled connection:

```java
// VertxPoolAdapter provides dedicated connections
Future<PgConnection> dedicatedConn = adapter.connectDedicated();

dedicatedConn.onSuccess(conn -> {
    conn.notificationHandler(notification -> {
        // Handle notification
    });
    conn.query("LISTEN my_topic").execute();
});
```

### Reconnection with Exponential Backoff

```
Connection lost
      |
      v
Wait 1 second --> Reconnect attempt
      |
   Failed?
      |
      v
Wait 2 seconds --> Reconnect attempt
      |
   Failed?
      |
      v
Wait 4 seconds --> ... (capped at 30 seconds)
```

## 7. Advisory Locks

The module uses PostgreSQL advisory locks for reliable message claiming.

### Transaction-Level Locks

```sql
-- Claim message with advisory lock
SELECT * FROM queue_messages
WHERE topic = 'my-topic'
  AND visible_at <= NOW()
  AND pg_try_advisory_xact_lock(id)
ORDER BY priority DESC, created_at ASC
LIMIT 10
FOR UPDATE SKIP LOCKED;
```

**Key Properties:**
- `pg_try_advisory_xact_lock(id)` - Non-blocking, returns false if locked
- Lock automatically released when transaction ends
- Combined with `FOR UPDATE SKIP LOCKED` for concurrent safety

## 8. Dead Letter Queue

Failed messages are moved to the dead letter queue after max retries.

```
Message processing fails
         |
         v
Increment retry_count
         |
         v
retry_count >= max_retries?
         |
    +----+----+
    |         |
   Yes        No
    |         |
    v         v
Move to      Update visible_at
dead_letter  (exponential backoff)
_queue
```

### DLQ Table Structure

```sql
CREATE TABLE dead_letter_queue (
    id BIGSERIAL PRIMARY KEY,
    original_message_id BIGINT,
    topic VARCHAR(255),
    payload JSONB,
    headers JSONB,
    error_message TEXT,
    retry_count INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
);
```

## 9. Error Handling

### Expected Errors During Shutdown

The consumer handles these errors gracefully during shutdown:

| Error | Cause | Handling |
|-------|-------|----------|
| `Pool closed` | Pool closed before operation | Logged at DEBUG, ignored |
| `Connection terminated` | Connection closed | Logged at DEBUG, ignored |
| `RejectedExecutionException` | Executor shutdown | Logged at DEBUG, ignored |

### Retry Logic

```java
// withRetry() method for transient errors
private <R> Future<R> withRetry(Supplier<Future<R>> operation, int maxRetries) {
    return operation.get()
        .recover(err -> {
            if (isTransientError(err) && retries < maxRetries) {
                return withRetry(operation, maxRetries - 1);
            }
            return Future.failedFuture(err);
        });
}
```

## 10. Graceful Shutdown

The consumer tracks pending operations for clean shutdown:

```java
// Shutdown sequence
1. Set closed = true (prevent new operations)
2. Cancel polling timer
3. Close LISTEN connection
4. Wait for pendingLockOperations to reach 0
5. Wait for inFlightOperations to reach 0
6. Complete shutdown future
```

### Shutdown Counters

| Counter | Purpose |
|---------|---------|
| `closed` | Prevents new operations from starting |
| `pendingLockOperations` | Tracks in-progress lock acquisitions |
| `inFlightOperations` | Tracks messages being processed |
| `processingInFlight` | Bounds concurrent processing |

## 11. Configuration

### PeeGeeQConfiguration Integration

The module integrates with `PeeGeeQConfiguration` from `peegeeq-db`:

```java
// Configuration properties
peegeeq.consumer.mode=HYBRID
peegeeq.consumer.polling.interval=1000
peegeeq.consumer.batch.size=10
peegeeq.consumer.threads=1
peegeeq.consumer.max.retries=3
peegeeq.consumer.visibility.timeout=30000
```

### VertxPoolAdapter

Adapts `PgClientFactory` configuration to Vert.x Pool:

```java
VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, clientFactory, "native-queue");

// Get pool (reuses factory-managed pool)
Pool pool = adapter.getPoolOrThrow();

// Create dedicated connection for LISTEN
Future<PgConnection> conn = adapter.connectDedicated();
```

## 12. Dependencies

### Maven Dependencies

```xml
<dependencies>
    <!-- PeeGeeQ modules -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-api</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-db</artifactId>
    </dependency>

    <!-- Vert.x -->
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-pg-client</artifactId>
    </dependency>

    <!-- Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- PostgreSQL SCRAM authentication -->
    <dependency>
        <groupId>com.ongres.scram</groupId>
        <artifactId>scram-client</artifactId>
    </dependency>

    <!-- Optional: CloudEvents -->
    <dependency>
        <groupId>io.cloudevents</groupId>
        <artifactId>cloudevents-json-jackson</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

## 13. Testing

### Test Categories

| Profile | Description | Parallelism |
|---------|-------------|-------------|
| `core-tests` | Fast unit tests | 4 threads |
| `integration-tests` | Database integration | 2 threads |
| `performance-tests` | Performance benchmarks | 1 thread |
| `smoke-tests` | Quick validation | 4 threads |
| `slow-tests` | Long-running tests | 1 thread |
| `full-tests` | All tests | 2 threads |

### Running Tests

```bash
# Run core tests only
mvn test -pl peegeeq-native -Pcore-tests

# Run integration tests
mvn test -pl peegeeq-native -Pintegration-tests

# Run all tests
mvn test -pl peegeeq-native -Pfull-tests
```

### Key Test Classes

| Test Class | Purpose |
|------------|---------|
| `ConsumerModeIntegrationTest` | Tests all three consumer modes |
| `PgNativeQueueConsumerClaimIT` | Tests message claiming with locks |
| `PgNativeQueueConcurrentClaimIT` | Tests concurrent message processing |
| `ConsumerGroupTest` | Tests consumer group load balancing |
| `ListenReconnectFaultInjectionIT` | Tests LISTEN reconnection |
| `MemoryAndResourceLeakTest` | Tests resource cleanup |

## 14. Design Patterns

### Factory Pattern

`PgNativeQueueFactory` creates all queue components:

```java
QueueFactory factory = new PgNativeQueueFactory(databaseService);
MessageProducer<Order> producer = factory.createProducer("orders", Order.class);
MessageConsumer<Order> consumer = factory.createConsumer("orders", Order.class);
```

### Builder Pattern

`ConsumerConfig` uses fluent builder:

```java
ConsumerConfig config = ConsumerConfig.builder()
    .mode(ConsumerMode.POLLING_ONLY)
    .pollingInterval(Duration.ofSeconds(5))
    .batchSize(100)
    .build();
```

### ReadStream Pattern

`PgNotificationStream` implements Vert.x `ReadStream<T>`:

```java
ReadStream<Message<T>> stream = queue.receive();
stream.handler(message -> { ... })
      .exceptionHandler(err -> { ... })
      .endHandler(v -> { ... });
```

## 15. Version History

| Version | Changes |
|---------|---------|
| 1.0.0 | Initial release with HYBRID mode only |
| 1.1.0 | Added ConsumerMode enum (LISTEN_NOTIFY_ONLY, POLLING_ONLY, HYBRID) |
| 1.2.0 | Added ConsumerConfig builder, consumer groups |
| 1.3.0 | Added dead letter queue support, metrics integration |

