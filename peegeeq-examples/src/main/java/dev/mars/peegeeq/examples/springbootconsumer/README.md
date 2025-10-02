# Spring Boot Consumer Example

This example demonstrates the **CORRECT** way to implement message consumers in a Spring Boot application using PeeGeeQ's transactional outbox pattern.

## Overview

This example shows:
- **Consumer Groups** - Multiple consumer instances processing messages in parallel
- **Message Filtering** - Selective message consumption based on message properties
- **Message Acknowledgment** - Proper message acknowledgment patterns
- **Consumer Monitoring** - REST endpoints for health and metrics
- **Transactional Processing** - Database operations coordinated with message consumption

## Architecture

```
┌─────────────┐         ┌──────────────┐         ┌──────────────┐
│   Producer  │────────>│ Outbox Queue │────────>│   Consumer   │
│  (External) │         │  (PeeGeeQ)   │         │  (This App)  │
└─────────────┘         └──────────────┘         └──────────────┘
                                                          │
                                                          ▼
                                                   ┌──────────────┐
                                                   │   Database   │
                                                   │   (Orders)   │
                                                   └──────────────┘
```

## Key Features

### 1. Consumer Groups
Multiple consumer instances can process messages from the same queue in parallel:
- Each consumer has a unique instance ID
- Messages are distributed across consumers
- Automatic load balancing

### 2. Message Filtering
Consumers can filter messages based on status or other criteria:
```yaml
consumer:
  filtering:
    enabled: true
    allowed-statuses: PENDING,CONFIRMED
```

### 3. Transactional Processing
All database operations happen in a single transaction:
- Message consumption
- Order storage
- Consumer metrics update
- Automatic rollback on failure

### 4. Monitoring
REST endpoints provide real-time consumer metrics:
- `/api/consumer/health` - Consumer health status
- `/api/consumer/metrics` - Processing metrics
- `/api/consumer/status` - Current status

## Configuration

### Application Properties

```yaml
# Consumer Configuration
consumer:
  instance-id: consumer-1  # Unique per instance
  queue-name: order-events
  filtering:
    enabled: true
    allowed-statuses: PENDING,CONFIRMED
  group:
    enabled: true
    concurrency: 2

# PeeGeeQ Configuration
peegeeq:
  database:
    host: localhost
    port: 5432
    name: peegeeq_consumer_example
  queue:
    polling-interval: PT0.5S
    max-retries: 3
    batch-size: 10
```

## Running the Example

### Prerequisites
- Java 17 or higher
- PostgreSQL 15 or higher
- Maven 3.8 or higher

### Start PostgreSQL
```bash
docker run -d \
  --name peegeeq-postgres \
  -e POSTGRES_DB=peegeeq_consumer_example \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  postgres:15-alpine
```

### Run the Application
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=springboot-consumer
```

### Run Multiple Consumers
Terminal 1:
```bash
CONSUMER_INSTANCE_ID=consumer-1 mvn spring-boot:run
```

Terminal 2:
```bash
CONSUMER_INSTANCE_ID=consumer-2 SERVER_PORT=8084 mvn spring-boot:run
```

## Testing

### Run Tests
```bash
mvn test -Dtest=OrderConsumerServiceTest
```

### Send Test Messages
You can use the `springboot` or `springboot2` producer examples to send messages:

```bash
# In another terminal, run the producer example
cd peegeeq-examples
mvn spring-boot:run -Dspring-boot.run.profiles=springboot

# Create an order (this will send a message to the queue)
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-123",
    "amount": 99.99,
    "items": [
      {"productId": "prod-1", "quantity": 2, "price": 49.99}
    ]
  }'
```

### Check Consumer Status
```bash
# Health check
curl http://localhost:8083/api/consumer/health

# Metrics
curl http://localhost:8083/api/consumer/metrics

# Status
curl http://localhost:8083/api/consumer/status
```

## Code Structure

```
springbootconsumer/
├── config/
│   ├── PeeGeeQConsumerConfig.java      # PeeGeeQ configuration
│   └── PeeGeeQConsumerProperties.java  # Configuration properties
├── controller/
│   └── ConsumerMonitoringController.java # REST endpoints
├── events/
│   └── OrderEvent.java                  # Event model
├── model/
│   └── Order.java                       # Domain model
├── service/
│   └── OrderConsumerService.java        # Consumer service
└── SpringBootConsumerApplication.java   # Main application
```

## Key Implementation Details

### 1. Configuration (PeeGeeQConsumerConfig.java)

Uses PeeGeeQ's public API:
```java
@Bean
public DatabaseService databaseService(PeeGeeQManager manager) {
    return new PgDatabaseService(manager);
}

@Bean
public MessageConsumer<OrderEvent> orderEventConsumer(QueueFactory factory) {
    return factory.createConsumer("order-events", OrderEvent.class);
}
```

### 2. Consumer Service (OrderConsumerService.java)

Demonstrates correct message processing:
```java
@PostConstruct
public void startConsuming() {
    consumer.subscribe(this::processMessage);
}

private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
    return databaseService.getConnectionProvider()
        .withTransaction(CLIENT_ID, connection -> {
            // Store order in database
            // Update metrics
            // All in one transaction
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### 3. Message Filtering

Filter messages based on criteria:
```java
private boolean shouldProcessMessage(OrderEvent event) {
    if (!filteringEnabled || allowedStatuses.isEmpty()) {
        return true;
    }
    return allowedStatuses.contains(event.getStatus());
}
```

## Common Patterns

### Pattern 1: Basic Consumption
```java
consumer.subscribe(message -> {
    OrderEvent event = message.getPayload();
    // Process event
    return CompletableFuture.completedFuture(null);
});
```

### Pattern 2: Transactional Processing
```java
databaseService.getConnectionProvider()
    .withTransaction(CLIENT_ID, connection -> {
        // All operations in one transaction
        return saveOrder(order, connection)
            .compose(v -> updateMetrics(connection));
    });
```

### Pattern 3: Error Handling
```java
processMessage(message)
    .exceptionally(ex -> {
        log.error("Failed to process message", ex);
        // Message will be retried automatically
        throw new RuntimeException("Processing failed", ex);
    });
```

## Monitoring

### Metrics Available
- `messagesProcessed` - Total messages successfully processed
- `messagesFiltered` - Total messages filtered out
- `messagesFailed` - Total messages that failed processing
- `successRate` - Percentage of successful processing

### Health Checks
The `/api/consumer/health` endpoint returns:
```json
{
  "status": "UP",
  "consumerInstanceId": "consumer-1",
  "messagesProcessed": 42,
  "messagesFiltered": 5,
  "messagesFailed": 0
}
```

## Troubleshooting

### Consumer Not Receiving Messages
1. Check queue name matches producer
2. Verify database connection
3. Check consumer status in logs
4. Verify PeeGeeQ Manager started

### Messages Being Filtered
1. Check filtering configuration
2. Verify message status matches allowed statuses
3. Check logs for filter decisions

### Database Connection Issues
1. Verify PostgreSQL is running
2. Check connection properties
3. Verify schema is initialized

## Best Practices

1. **Use Unique Consumer IDs** - Each instance should have a unique ID
2. **Configure Appropriate Batch Size** - Balance throughput and latency
3. **Monitor Consumer Metrics** - Track processing rates and failures
4. **Handle Errors Gracefully** - Let PeeGeeQ retry failed messages
5. **Use Transactional Processing** - Keep database and message consumption consistent

## Related Examples

- **springboot** - Producer example (non-reactive)
- **springboot2** - Producer example (reactive)
- **springboot-dlq** - Dead letter queue handling
- **springboot-retry** - Retry strategies

## References

- [Spring Boot Integration Guide](../../../../../../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md)
- [PeeGeeQ Documentation](https://github.com/mars-research/peegeeq)

