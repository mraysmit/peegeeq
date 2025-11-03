# Spring Boot Integrated Outbox + Bi-Temporal Example

**Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd**

## Overview

This example demonstrates the **complete integration pattern** that combines:
- **Database Operations** - Store order data in PostgreSQL
- **Outbox Pattern** - Send events for immediate real-time processing
- **Bi-Temporal Event Store** - Maintain complete historical audit trail

**All three operations participate in a SINGLE database transaction**, ensuring complete consistency and reliability.

## Business Scenario

**Order Management System** where:
1. Customer places an order
2. Order is saved to database
3. Event is sent to outbox → triggers shipping workflow
4. Event is appended to event store → creates audit trail
5. **All in a single transaction** - if any fails, all rollback

## Core Pattern

### The Integration Pattern

```java
@Service
public class OrderService {
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        // ONE transaction coordinates ALL operations
        return cp.withTransaction("peegeeq-integrated", connection -> {
            
            // Step 1: Save order to database
            return orderRepository.save(order, connection)
            
            // Step 2: Send to outbox (for immediate processing)
            .compose(v -> Future.fromCompletionStage(
                orderEventProducer.sendInTransaction(event, connection)
            ))
            
            // Step 3: Append to event store (for historical queries)
            .compose(v -> Future.fromCompletionStage(
                orderEventStore.appendInTransaction(
                    "OrderCreated",
                    event,
                    validTime,
                    connection  // SAME connection throughout
                )
            ))
            
            .map(v -> orderId);
        }).toCompletionStage().toCompletableFuture();
    }
}
```

### Key Points

1. **Single Transaction** - `withTransaction()` creates ONE transaction
2. **Same Connection** - All operations use the SAME `connection` parameter
3. **Atomic Commit** - All succeed together or all fail together
4. **No Partial Updates** - Impossible to have inconsistent state

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  OrderController (REST API)                                  │
│         ↓                                                     │
│  OrderService                                                │
│         ↓                                                     │
│  ConnectionProvider.withTransaction("client-id", conn -> {   │
│         ↓                                                     │
│    ┌────────────────────────────────────────────┐           │
│    │  SINGLE TRANSACTION (same connection)      │           │
│    ├────────────────────────────────────────────┤           │
│    │                                             │           │
│    │  1. OrderRepository.save(order, conn)      │           │
│    │     ↓                                       │           │
│    │  2. Producer.sendInTransaction(event,conn) │           │
│    │     ↓                                       │           │
│    │  3. EventStore.appendInTransaction(...)    │           │
│    │                                             │           │
│    └────────────────────────────────────────────┘           │
│         ↓                                                     │
│    PostgreSQL (orders table + outbox + event_store)         │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

## Components

### Configuration (`IntegratedConfig.java`)

Creates all required beans:
- `PeeGeeQManager` - Foundation
- `DatabaseService` - Connection management
- `QueueFactory` - Outbox factory
- `MessageProducer<OrderEvent>` - Send to outbox
- `BiTemporalEventStoreFactory` - Event store factory
- `EventStore<OrderEvent>` - Bi-temporal storage

### Service Layer (`OrderService.java`)

Coordinates all operations in single transaction:
- `createOrder()` - Integrated transaction pattern
- `getOrderHistory()` - Query from event store
- `getCustomerOrders()` - Customer order report
- `getOrdersAsOfTime()` - Point-in-time queries

### Repository (`OrderRepository.java`)

Database operations:
- `save(order, connection)` - Save order data
- `findById(orderId, connection)` - Find order

**Key Pattern**: All methods accept `SqlConnection` parameter to participate in caller's transaction.

### REST API (`OrderController.java`)

Endpoints:
- `POST /api/orders` - Create order
- `GET /api/orders/{orderId}/history` - Order history
- `GET /api/customers/{customerId}/orders` - Customer orders
- `GET /api/orders?asOf=<timestamp>` - Point-in-time query

## Running the Example

### Prerequisites

1. PostgreSQL 12+ running
2. Database configured in `~/.peegeeq/development.properties`:
   ```properties
   db.host=localhost
   db.port=5432
   db.database=peegeeq
   db.username=postgres
   db.password=postgres
   ```

### Start the Application

```bash
mvn spring-boot:run -pl peegeeq-examples \
  -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootintegrated.SpringBootIntegratedApplication \
  -Dspring-boot.run.profiles=integrated
```

The application starts on port **8084**.

### Test the API

#### 1. Create an Order

```bash
curl -X POST http://localhost:8084/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-001",
    "amount": "1500.00",
    "description": "Laptop purchase"
  }'
```

Response: `"550e8400-e29b-41d4-a716-446655440000"` (order ID)

#### 2. Query Order History

```bash
curl http://localhost:8084/api/orders/550e8400-e29b-41d4-a716-446655440000/history
```

Response shows all events for the order from bi-temporal event store.

#### 3. Query Customer Orders

```bash
curl http://localhost:8084/api/customers/CUST-001/orders
```

Response shows all orders for the customer.

#### 4. Point-in-Time Query

```bash
curl "http://localhost:8084/api/orders?asOf=2025-10-01T12:00:00Z"
```

Response shows orders as they existed at that point in time.

## What Happens When You Create an Order

### Step-by-Step Flow

1. **REST Request** → `OrderController.createOrder()`
2. **Service Layer** → `OrderService.createOrder()`
3. **Transaction Start** → `ConnectionProvider.withTransaction()`
4. **Database Save** → `OrderRepository.save(order, connection)`
   - Order saved to `orders` table
5. **Outbox Send** → `MessageProducer.sendInTransaction(event, connection)`
   - Event saved to `outbox` table
   - Outbox consumer will process immediately
6. **Event Store Append** → `EventStore.appendInTransaction(..., connection)`
   - Event saved to `event_store` table
   - Available for historical queries
7. **Transaction Commit** → All three operations committed together
8. **Response** → Order ID returned to client

### Database State After Commit

```sql
-- orders table
INSERT INTO orders (id, customer_id, amount, status, description, created_at)
VALUES ('550e8400...', 'CUST-001', 1500.00, 'CREATED', 'Laptop purchase', NOW());

-- outbox table (for immediate processing)
INSERT INTO outbox (id, topic, payload, created_at)
VALUES ('uuid', 'orders', '{"orderId":"550e8400...","customerId":"CUST-001",...}', NOW());

-- event_store table (for historical queries)
INSERT INTO event_store (event_type, aggregate_id, payload, valid_time, transaction_time)
VALUES ('OrderCreated', '550e8400...', '{"orderId":"550e8400...","customerId":"CUST-001",...}', NOW(), NOW());
```

**All three inserts are in the SAME transaction** - they all succeed or all fail together.

## Key Benefits

### 1. Consistency
- Single transaction ensures all operations succeed or fail together
- No partial updates
- No inconsistent state

### 2. Real-Time Processing
- Outbox consumers process events immediately
- Trigger downstream workflows (shipping, billing, notifications)
- Update external systems

### 3. Historical Audit Trail
- Bi-temporal store maintains complete history
- Query order state at any point in time
- Regulatory compliance (SOX, GDPR, MiFID II)

### 4. Reliability
- No lost events
- No duplicate processing (if consumer is idempotent)
- Guaranteed delivery

### 5. Scalability
- PostgreSQL handles high throughput
- Connection pooling for efficiency
- Non-blocking I/O with Vert.x

## Testing

Run the integration test:

```bash
mvn test -pl peegeeq-examples \
  -Dtest=SpringBootIntegratedApplicationTest
```

The test verifies:
- Order creation with integrated transaction
- Order history queries
- Customer order queries
- Point-in-time queries
- Transaction rollback on failure

## Troubleshooting

### Issue: Transaction Not Rolling Back

**Symptom**: Some operations succeed even when others fail.

**Cause**: Not using the same `connection` parameter for all operations.

**Solution**: Verify all operations receive the `connection` parameter:
```java
orderRepository.save(order, connection)  // ← connection
producer.sendInTransaction(event, connection)  // ← connection
eventStore.appendInTransaction(..., connection)  // ← connection
```

### Issue: Events Not Appearing in Outbox

**Symptom**: Events saved to event store but not processed by consumers.

**Cause**: Using `producer.send()` instead of `producer.sendInTransaction()`.

**Solution**: Always use `sendInTransaction()` with the connection:
```java
producer.sendInTransaction(event, connection)  // ✅ Correct
```

### Issue: Historical Queries Return No Results

**Symptom**: Events not appearing in bi-temporal queries.

**Cause**: Using `eventStore.append()` instead of `appendInTransaction()`.

**Solution**: Always use `appendInTransaction()` with the connection:
```java
eventStore.appendInTransaction("OrderCreated", event, validTime, connection)  // ✅ Correct
```

## Related Examples

- **springboot** - Basic outbox pattern
- **springboot-bitemporal** - Basic bi-temporal pattern
- **springboot-bitemporal-tx** - Transactional bi-temporal
- **springboot-consumer** - Consumer groups

## Documentation

- [Spring Boot Integration Guide](../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md)
- [Additional Examples Proposal](../../docs/ADDITIONAL_EXAMPLES_PROPOSAL.md)

## License

Apache License 2.0 - See LICENSE file for details.

