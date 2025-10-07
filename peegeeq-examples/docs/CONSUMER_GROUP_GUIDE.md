# Consumer Group Pattern Guide

**Example**: `springboot-consumer`
**Use Case**: Trade settlement processing with competing consumers
**Focus**: Message consumption, consumer groups, header-based filtering

---

## What This Guide Covers

This guide demonstrates how to consume messages from PeeGeeQ outbox queues using the **competing consumers pattern**. Multiple consumer instances compete for messages from the same queue, enabling parallel processing and horizontal scaling.

**Key Patterns**:
- Competing consumers (multiple instances processing same queue)
- Header-based message filtering
- Consumer health monitoring
- Transactional message processing
- Consumer lifecycle management

**What This Guide Does NOT Cover**:
- Message production (see PRIORITY_FILTERING_GUIDE.md)
- Retry and DLQ handling (see DLQ_RETRY_GUIDE.md)
- Bi-temporal event storage (see REACTIVE_BITEMPORAL_GUIDE.md)

---

## Core Concepts

### Competing Consumers Pattern

**Definition**: Multiple consumer instances compete for messages from the same queue. Each message is processed by exactly one consumer.

**How It Works**:
1. Multiple consumers subscribe to same queue
2. PeeGeeQ uses database locking (`FOR UPDATE SKIP LOCKED`)
3. Each message locked by one consumer
4. Other consumers skip locked messages
5. Message deleted after successful processing

**Benefits**:
- Horizontal scaling (add more consumers = higher throughput)
- Load balancing (work distributed automatically)
- Fault tolerance (if one consumer fails, others continue)
- No message duplication (each message processed once)

### Message Filtering

**Application-Level Filtering**: Filter messages inside the message handler based on headers.

**When to Use**:
- Different consumers need different subsets of messages
- Filtering logic is business-specific
- Headers contain routing information

**Pattern**:
```java
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    // Check headers
    if (!shouldProcessMessage(message.getPayload())) {
        // Skip this message
        return CompletableFuture.completedFuture(null);
    }

    // Process message
    return processOrder(message.getPayload());
}
```

---

## Consumer Implementation

### Basic Consumer Setup

**Step 1: Create Consumer Bean**

```java
@Bean
public MessageConsumer<OrderEvent> orderEventConsumer(QueueFactory factory) {
    return factory.createConsumer("order-events", OrderEvent.class);
}
```

**Step 2: Subscribe to Messages**

```java
@Service
public class OrderConsumerService {

    private final MessageConsumer<OrderEvent> consumer;

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        consumer.subscribe(this::processMessage);
    }

    private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
        OrderEvent event = message.getPayload();

        // Process the order
        return processOrder(event);
    }
}
```

**Key Points**:
- Use `@EventListener(ApplicationReadyEvent.class)` to start after schema initialization
- `subscribe()` takes a function: `Message<T> -> CompletableFuture<Void>`
- Return `CompletableFuture<Void>` for acknowledgment
- Message deleted automatically after successful completion

### Transactional Processing

**Pattern**: Use `ConnectionProvider.withTransaction()` for transactional message processing.

```java
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    OrderEvent event = message.getPayload();

    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            // Create order from event
            Order order = new Order(
                event.getOrderId(),
                event.getCustomerId(),
                event.getAmount(),
                event.getStatus()
            );

            // Insert into database
            String sql = "INSERT INTO orders (id, customer_id, amount, status, created_at) " +
                        "VALUES ($1, $2, $3, $4, $5)";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(
                    order.getId(),
                    order.getCustomerId(),
                    order.getAmount(),
                    order.getStatus(),
                    LocalDateTime.now(ZoneOffset.UTC)
                ))
                .map(result -> null);
        })
        .toCompletionStage()
        .toCompletableFuture()
        .thenApply(v -> (Void) null);
}
```

**Transaction Guarantees**:
- Order inserted and message deleted in same transaction
- If insert fails, message NOT deleted (will be retried)
- If transaction commits, message deleted automatically
- No duplicate processing

---

## Message Filtering Patterns

### Header-Based Filtering

**Scenario**: Different consumers process different order statuses.

**Producer Side** (attach headers):
```java
Map<String, String> headers = new HashMap<>();
headers.put("status", order.getStatus());  // "NEW", "AMENDED", "CANCELLED"

producer.send(orderEvent, headers);
```

**Consumer Side** (filter by headers):
```java
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    OrderEvent event = message.getPayload();
    Map<String, String> headers = message.getHeaders();

    // Filter by status header
    String status = headers.get("status");
    if (!allowedStatuses.contains(status)) {
        // Skip this message
        messagesFiltered.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    // Process message
    return processOrder(event);
}
```

### Configurable Filtering

**Configuration** (`application.yml`):
```yaml
consumer:
  instance-id: consumer-1
  filtering:
    enabled: true
    allowed-statuses: NEW,AMENDED
```

**Service Implementation**:
```java
@Service
public class OrderConsumerService {

    private final boolean filteringEnabled;
    private final List<String> allowedStatuses;

    public OrderConsumerService(
            MessageConsumer<OrderEvent> consumer,
            @Value("${consumer.filtering.enabled:false}") boolean filteringEnabled,
            @Value("${consumer.filtering.allowed-statuses:}") String allowedStatusesStr) {

        this.filteringEnabled = filteringEnabled;
        this.allowedStatuses = allowedStatusesStr.isEmpty() ?
            List.of() : Arrays.asList(allowedStatusesStr.split(","));
    }

    private boolean shouldProcessMessage(OrderEvent event) {
        if (!filteringEnabled || allowedStatuses.isEmpty()) {
            return true;
        }
        return allowedStatuses.contains(event.getStatus());
    }
}
```

---

## Consumer Lifecycle Management

### Startup Sequence

**Correct Order**:
1. Spring Boot application starts
2. PeeGeeQ Manager initialized
3. Database schema created
4. `ApplicationReadyEvent` fired
5. Consumer starts subscribing

**Implementation**:
```java
@Service
public class OrderConsumerService {

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        log.info("Starting message consumption");

        // Subscribe to messages
        consumer.subscribe(this::processMessage);

        // Update consumer status
        updateConsumerStatus("RUNNING");
    }

    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping message consumption");

        // Update status
        updateConsumerStatus("STOPPED");

        // Close consumer
        consumer.close();
    }
}
```

### Consumer Health Monitoring

**Database Table** (`consumer_status`):
```sql
CREATE TABLE IF NOT EXISTS consumer_status (
    consumer_id VARCHAR(255) PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    messages_processed BIGINT DEFAULT 0,
    last_message_at TIMESTAMP,
    started_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

**Update Status**:
```java
private void updateConsumerStatus(String status) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            String sql = "INSERT INTO consumer_status " +
                        "(consumer_id, status, messages_processed, started_at, updated_at) " +
                        "VALUES ($1, $2, $3, $4, $5) " +
                        "ON CONFLICT (consumer_id) DO UPDATE SET " +
                        "status = EXCLUDED.status, " +
                        "messages_processed = EXCLUDED.messages_processed, " +
                        "updated_at = EXCLUDED.updated_at";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(
                    consumerInstanceId,
                    status,
                    messagesProcessed.get(),
                    now,
                    now
                ));
        })
        .toCompletionStage()
        .toCompletableFuture()
        .join();
}
```

**Message Flow**:

1. Producer sends message with `status=NEW` header
2. Consumer 1, 2, 3 all compete for message
3. Consumer 1 wins (locks message with `FOR UPDATE SKIP LOCKED`)
4. Consumer 1 checks filter: `NEW` in `[NEW, AMENDED]` → PROCESS
5. Consumer 1 processes and deletes message
6. Consumer 2 and 3 skip to next message

**Benefits**:
- Load balancing (work distributed across consumers)
- Specialization (consumers handle specific statuses)
- Fault tolerance (if Consumer 1 fails, Consumer 3 processes NEW messages)

---

## Metrics and Monitoring

### Consumer Metrics

**Track Key Metrics**:
```java
@Service
public class OrderConsumerService {

    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

    private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
        // Filter check
        if (!shouldProcessMessage(message.getPayload())) {
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        // Process message
        return processOrder(message.getPayload())
            .thenApply(v -> {
                messagesProcessed.incrementAndGet();
                return null;
            })
            .exceptionally(ex -> {
                messagesFailed.incrementAndGet();
                throw new RuntimeException(ex);
            });
    }

    // Expose metrics
    public long getMessagesProcessed() { return messagesProcessed.get(); }
    public long getMessagesFiltered() { return messagesFiltered.get(); }
    public long getMessagesFailed() { return messagesFailed.get(); }
}
```

### Monitoring Endpoints

**REST Controller**:
```java
@RestController
@RequestMapping("/api/consumer")
public class ConsumerMonitoringController {

    private final OrderConsumerService consumerService;

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return Map.of(
            "consumerId", consumerService.getConsumerInstanceId(),
            "messagesProcessed", consumerService.getMessagesProcessed(),
            "messagesFiltered", consumerService.getMessagesFiltered(),
            "messagesFailed", consumerService.getMessagesFailed()
        );
    }

    @GetMapping("/health")
    public Map<String, String> getHealth() {
        return Map.of("status", "UP");
    }
}
```

**Query Consumer Status**:
```sql
-- Get all consumer status
SELECT
    consumer_id,
    status,
    messages_processed,
    last_message_at,
    updated_at
FROM consumer_status
ORDER BY consumer_id;

-- Find inactive consumers (no activity in 5 minutes)
SELECT consumer_id, last_message_at
FROM consumer_status
WHERE updated_at < NOW() - INTERVAL '5 minutes'
  AND status = 'RUNNING';
```

---

## Error Handling

### Transient Failures

**Pattern**: Let message retry automatically.

```java
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    return processOrder(message.getPayload())
        .exceptionally(ex -> {
            log.error("Failed to process message: {}", message.getPayload().getOrderId(), ex);
            messagesFailed.incrementAndGet();

            // Rethrow to trigger retry
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new RuntimeException("Message processing failed", ex);
        });
}
```

**Behavior**:
- Exception thrown → transaction rolled back
- Message NOT deleted → remains in queue
- Next poll picks up message again
- Automatic retry

### Permanent Failures

**Pattern**: Move to dead letter queue (see DLQ_RETRY_GUIDE.md).

```java
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    return processOrder(message.getPayload())
        .exceptionally(ex -> {
            if (isPermanentFailure(ex)) {
                // Move to DLQ
                return moveToDLQ(message, ex);
            } else {
                // Retry
                throw new RuntimeException(ex);
            }
        });
}
```

---

## Best Practices

### 1. Use ApplicationReadyEvent

**Why**: Ensures schema initialized before consuming.

```java
@EventListener(ApplicationReadyEvent.class)
public void startConsuming() {
    consumer.subscribe(this::processMessage);
}
```

### 2. Return CompletableFuture<Void>

**Why**: Enables proper acknowledgment and error handling.

```java
// ✅ CORRECT
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    return processOrder(message.getPayload())
        .thenApply(v -> (Void) null);
}

// ❌ WRONG - void return
private void processMessage(Message<OrderEvent> message) {
    processOrder(message.getPayload());  // No acknowledgment!
}
```

### 3. Use Transactions

**Why**: Ensures message processing and database updates are atomic.

```java
// ✅ CORRECT - transactional
return databaseService.getConnectionProvider()
    .withTransaction("peegeeq-main", connection -> {
        return saveOrder(connection, order);
    })
    .toCompletionStage()
    .toCompletableFuture();

// ❌ WRONG - non-transactional
saveOrderDirectly(order);  // Not atomic with message deletion!
return CompletableFuture.completedFuture(null);
```

### 4. Filter Inside Handler

**Why**: Allows competing consumers to skip unwanted messages.

```java
// ✅ CORRECT - filter inside handler
private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    if (!shouldProcessMessage(message.getPayload())) {
        return CompletableFuture.completedFuture(null);  // Skip
    }
    return processOrder(message.getPayload());
}

// ❌ WRONG - can't filter in subscribe
consumer.subscribe(message -> {
    // No way to filter here without processing
});
```

### 5. Track Metrics

**Why**: Enables monitoring and troubleshooting.

```java
private final AtomicLong messagesProcessed = new AtomicLong(0);
private final AtomicLong messagesFiltered = new AtomicLong(0);
private final AtomicLong messagesFailed = new AtomicLong(0);

// Update metrics in handler
messagesProcessed.incrementAndGet();
```

### 6. Graceful Shutdown

**Why**: Ensures clean consumer shutdown.

```java
@PreDestroy
public void stopConsuming() {
    updateConsumerStatus("STOPPED");
    consumer.close();
}
```

---

## Trade Settlement Use Case

### Scenario

**Back Office Trade Settlement**:
- Multiple settlement workers process trade confirmations
- Different workers specialize in different trade types
- Need parallel processing for high throughput
- Need health monitoring for operational oversight

### Implementation

**Producer** (Trade Capture System):
```java
// Send trade confirmation with status header
Map<String, String> headers = Map.of(
    "status", trade.getStatus(),           // "NEW", "AMENDED", "CANCELLED"
    "tradeType", trade.getType(),          // "EQUITY", "FX", "BOND"
    "priority", trade.getPriority()        // "CRITICAL", "HIGH", "NORMAL"
);

orderProducer.send(orderEvent, headers);
```

**Consumer 1** (New Trades):
```yaml
consumer:
  instance-id: settlement-worker-1
  filtering:
    enabled: true
    allowed-statuses: NEW
```

**Consumer 2** (Amendments):
```yaml
consumer:
  instance-id: settlement-worker-2
  filtering:
    enabled: true
    allowed-statuses: AMENDED
```

**Consumer 3** (Cancellations):
```yaml
consumer:
  instance-id: settlement-worker-3
  filtering:
    enabled: true
    allowed-statuses: CANCELLED
```

**Processing Flow**:
1. Trade captured → message sent to `order-events` queue
2. Three consumers compete for message
3. Consumer locks message based on status filter
4. Consumer processes trade (validate, enrich, send to custodian)
5. Consumer stores result in database
6. Message deleted (transaction commits)
7. Consumer metrics updated

**Benefits**:
- Parallel processing (3 workers = 3x throughput)
- Specialization (each worker handles specific trade types)
- Load balancing (work distributed automatically)
- Fault tolerance (if one worker fails, others continue)
- Monitoring (track each worker's health and metrics)

---

## Summary

**Key Takeaways**:

1. **Competing Consumers** - Multiple consumers compete for messages from same queue
2. **Database Locking** - PeeGeeQ uses `FOR UPDATE SKIP LOCKED` for message locking
3. **Header-Based Filtering** - Filter messages inside handler based on headers
4. **Transactional Processing** - Use `withTransaction()` for atomic processing
5. **Lifecycle Management** - Use `ApplicationReadyEvent` and `@PreDestroy`
6. **Health Monitoring** - Track consumer status and metrics in database
7. **Error Handling** - Throw exceptions to trigger retry, or move to DLQ

**Related Guides**:
- **PRIORITY_FILTERING_GUIDE.md** - Priority-based message routing
- **DLQ_RETRY_GUIDE.md** - Retry strategies and dead letter queues
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal patterns

---

## Competing Consumers Example

### Scenario: Trade Settlement Processing

**Use Case**: Multiple back office workers process trade confirmations in parallel.

**Setup**: 3 consumer instances processing same queue.

**Consumer 1** (processes NEW and AMENDED):
```yaml
consumer:
  instance-id: consumer-1
  queue-name: order-events
  filtering:
    enabled: true
    allowed-statuses: NEW,AMENDED
```

**Consumer 2** (processes CANCELLED):
```yaml
consumer:
  instance-id: consumer-2
  queue-name: order-events
  filtering:
    enabled: true
    allowed-statuses: CANCELLED
```

**Consumer 3** (processes all):
```yaml
consumer:
  instance-id: consumer-3
  queue-name: order-events
  filtering:
    enabled: false
```

**Message Flow**:

