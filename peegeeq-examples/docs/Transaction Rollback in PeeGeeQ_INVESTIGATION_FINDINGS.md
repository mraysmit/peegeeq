# Investigation Findings: Transaction Rollback in PeeGeeQ Examples

## Date: 2025-10-01

## Summary

This document summarizes the findings from a comprehensive investigation into transaction rollback behavior in the PeeGeeQ examples, specifically the `springboot` and `springboot2` implementations.

---

## Critical Discoveries

### 1. The springboot Example Uses In-Memory Storage (NOT Real Database)

**Finding**: The non-reactive `springboot` example does NOT use a real PostgreSQL database for Order/OrderItem storage.

**Evidence**:
- `OrderRepository.java` uses `ConcurrentHashMap<String, Order>` for storage
- `OrderItemRepository.java` uses `ConcurrentHashMap<String, OrderItem>` for storage
- No SQL INSERT/UPDATE statements for business data
- Only outbox events go to PostgreSQL

**Impact**:
- The "rollback tests" in `TransactionalConsistencyTest.java` are misleading
- They test in-memory rollback, not actual PostgreSQL transaction rollback
- This example does NOT demonstrate the core promise of the transactional outbox pattern
- Developers copying this pattern will NOT get real transactional consistency

**Code Reference**:
```java
// peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot/repository/OrderRepository.java
@Repository
public class OrderRepository {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    
    public Order save(Order order) {
        // This is IN-MEMORY, not PostgreSQL!
        orders.put(order.getId(), order);
        return order;
    }
}
```

---

### 2. The springboot2 Example Has Two Separate Database Connections

**Finding**: The reactive `springboot2` example uses TWO separate PostgreSQL connections that CANNOT share a transaction.

**Evidence**:
- R2DBC connection for Order/OrderItem (via `R2dbcConfig.java`)
- Vert.x connection for outbox events (via PeeGeeQManager)
- These are completely separate connection pools
- No transaction coordination between them

**Impact**:
- When `createOrderWithValidation()` fails, the outbox event may rollback but the Order/OrderItem may commit (or vice versa)
- This violates the transactional outbox pattern guarantee
- Data inconsistency is possible in production

**Code Reference**:
```java
// peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/service/OrderService.java
public Mono<String> createOrder(CreateOrderRequest request) {
    return adapter.toMonoVoid(
        orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT  // Vert.x transaction
        )
    )
    .then(Mono.defer(() -> {
        Order order = new Order(request);
        return orderRepository.save(order);  // R2DBC transaction - DIFFERENT CONNECTION!
    }))
}
```

---

### 3. TransactionPropagation.CONTEXT Does NOT Share Transactions Across Separate pool.withTransaction() Calls

**Finding**: The documentation and code comments suggest that `TransactionPropagation.CONTEXT` allows multiple `sendWithTransaction()` calls to share the same transaction. This is **MISLEADING**.

**Evidence from Vert.x Documentation**:
- `TransactionPropagation.CONTEXT` only shares transactions within the SAME `pool.withTransaction()` block
- Each call to `pool.withTransaction()` creates its OWN transaction
- The "context" refers to the Vert.x execution context, not a shared database transaction

**What Actually Happens**:
```java
// This creates TWO separate transactions, NOT one shared transaction
orderEventProducer.sendWithTransaction(event1, TransactionPropagation.CONTEXT)  // Transaction 1
    .thenCompose(v -> {
        orderEventProducer.sendWithTransaction(event2, TransactionPropagation.CONTEXT)  // Transaction 2
    });
```

**The ONLY way to share a transaction**:
```java
// This creates ONE transaction shared by all operations
pool.withTransaction(connection -> {
    return Future.fromCompletionStage(
        producer.sendInTransaction(event1, connection)  // Uses same connection
    ).compose(v -> {
        return Future.fromCompletionStage(
            producer.sendInTransaction(event2, connection)  // Uses same connection
        );
    });
});
```

---

### 4. Working Examples Exist in the Codebase

**Finding**: The correct pattern IS demonstrated in `TransactionParticipationIntegrationTest.java` in the `peegeeq-bitemporal` module.

**Evidence**:
```java
// peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/TransactionParticipationIntegrationTest.java
@Test
void testBusinessTableEventLogConsistency() throws Exception {
    BiTemporalEvent<TestEvent> event = pool.withTransaction(connection -> {
        // Step 1: Insert business data
        String insertBusinessSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
        return connection.preparedQuery(insertBusinessSql)
            .execute(Tuple.of(businessName, businessValue))
            .compose(businessResult -> {
                int businessId = businessResult.iterator().next().getInteger("id");
                
                // Step 2: Append bitemporal event in SAME transaction
                CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                    eventStore.appendInTransaction(eventType, eventPayload, validTime, connection);
                
                return Future.fromCompletionStage(eventFuture);
            });
    }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    
    // Verify both business data and event exist
    // ... database verification queries ...
}
```

**This test demonstrates**:
- Using `pool.withTransaction()` to create ONE transaction
- Passing the `connection` to both business SQL and `appendInTransaction()`
- Verifying database state after commit
- This is the CORRECT pattern

---

## Recommendations

### Immediate Actions Required

1. **Fix springboot example**:
   - Replace `ConcurrentHashMap` with real PostgreSQL tables
   - Use Vert.x SQL Client for Order/OrderItem operations
   - Update `OrderService` to use `pool.withTransaction()` pattern
   - Update tests to verify actual database state

2. **Fix springboot2 example**:
   - Remove R2DBC completely
   - Use Vert.x SQL Client for ALL database operations
   - Update `OrderService` to use `pool.withTransaction()` pattern
   - Create reactive wrappers that return `Mono<T>`

3. **Update documentation**:
   - Clarify `TransactionPropagation.CONTEXT` behavior
   - Remove misleading examples
   - Document the two correct patterns (see below)

### The Two Correct Patterns

**Pattern A: Using pool.withTransaction() with sendInTransaction()**
```java
public CompletableFuture<String> createOrder(CreateOrderRequest request) {
    return pool.withTransaction(connection -> {
        String orderId = UUID.randomUUID().toString();
        
        return Future.fromCompletionStage(
            producer.sendInTransaction(new OrderCreatedEvent(request), connection)
        )
        .compose(v -> {
            String sql = "INSERT INTO orders (id, customer_id, amount) VALUES ($1, $2, $3)";
            return connection.preparedQuery(sql).execute(Tuple.of(orderId, request.getCustomerId(), request.getAmount()));
        })
        .compose(v -> {
            String sql = "INSERT INTO order_items (id, order_id, product_id, quantity) VALUES ($1, $2, $3, $4)";
            return connection.preparedQuery(sql).executeBatch(itemBatches);
        })
        .map(v -> orderId);
    }).toCompletionStage().toCompletableFuture();
}
```

**Pattern B: Using pool.getConnection() with manual transaction control**
```java
public CompletableFuture<String> createOrder(CreateOrderRequest request) {
    return pool.getConnection()
        .compose(connection -> {
            return connection.begin()
                .compose(transaction -> {
                    String orderId = UUID.randomUUID().toString();
                    
                    return Future.fromCompletionStage(
                        producer.sendInTransaction(new OrderCreatedEvent(request), connection)
                    )
                    .compose(v -> {
                        String sql = "INSERT INTO orders...";
                        return connection.preparedQuery(sql).execute(params);
                    })
                    .compose(v -> {
                        String sql = "INSERT INTO order_items...";
                        return connection.preparedQuery(sql).executeBatch(itemBatches);
                    })
                    .compose(v -> transaction.commit())
                    .map(v -> orderId);
                })
                .eventually(() -> connection.close());
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

---

## Impact Assessment

### Current State

- ❌ springboot example: Misleading (in-memory storage)
- ❌ springboot2 example: Broken (two separate connections)
- ❌ Documentation: Misleading (TransactionPropagation.CONTEXT)
- ✅ Core PeeGeeQ library: Correct (provides the right tools)
- ✅ Bitemporal tests: Correct (demonstrates the right pattern)

### Risk to Users

**HIGH RISK**: Developers copying the springboot/springboot2 examples will:
1. Believe they have transactional consistency when they don't
2. Experience data inconsistency in production
3. Lose trust in the PeeGeeQ library

### Effort to Fix

- springboot example: 12-16 hours
- springboot2 example: 16-20 hours
- Documentation: 6-8 hours
- **Total: 34-44 hours**

---

## Conclusion

The investigation revealed that both example implementations have critical flaws that violate the transactional outbox pattern guarantees. However, the core PeeGeeQ library provides the correct tools (`sendInTransaction()` and `pool.withTransaction()`), and working examples exist in the bitemporal tests.

The fix is straightforward but requires significant refactoring of both examples to use the correct pattern demonstrated in `TransactionParticipationIntegrationTest.java`.

**Next Steps**: Proceed with the implementation plan in `SPRINGBOOT2_TRANSACTION_IMPLEMENTATION_PLAN.md`.

