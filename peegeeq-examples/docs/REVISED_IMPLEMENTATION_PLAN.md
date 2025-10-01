# Revised Implementation Plan: Spring Boot Examples with Correct PeeGeeQ API Usage

**Date**: 2025-10-02  
**Status**: Ready for Implementation  
**Total Estimated Effort**: 27-37 hours

---

## 🎯 Objective

Fix both `springboot` and `springboot2` examples to demonstrate the **CORRECT** way to use PeeGeeQ APIs in Spring Boot applications.

### Key Principles

1. **Use PeeGeeQ's provided infrastructure** - Don't create separate connection pools
2. **Use DatabaseService and ConnectionProvider** - The proper API entry points
3. **Remove R2DBC completely** - It creates incompatible connection pools
4. **Demonstrate transactional consistency** - All operations in one transaction

### Required Reading

Before implementing, read:
- `CORRECT_PEEGEEQ_API_USAGE.md` - Comprehensive API guide
- `WRONG_VS_CORRECT_PATTERNS.md` - Side-by-side comparisons

---

## 📊 Current Status

### What's Been Done (But Needs Revision)

Phase 1 has partial implementation that uses **INCORRECT API patterns**:

✅ **Correct**:
- Database schema created (`schema-springboot.sql`)
- OrderRepository accepts `SqlConnection` parameter
- OrderItemRepository accepts `SqlConnection` parameter
- Code compiles

❌ **Incorrect** (Needs Revision):
- PeeGeeQConfig creates separate `Pool` bean (bypasses API)
- OrderService injects `Pool` directly (should use `DatabaseService`)
- Uses `pool.withTransaction()` directly (should use `ConnectionProvider.withTransaction()`)

### What Needs to Be Done

- Phase 1: Revise to use correct API (4-6 hours)
- Phase 2: Fix springboot2 example (17-23 hours)
- Phase 3: Update documentation (6-8 hours)

---

## Phase 1: Fix springboot (Non-Reactive) Example

**Goal**: Revise implementation to use correct PeeGeeQ API patterns

### Task 1.1: Revise PeeGeeQConfig.java (1 hour)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot/config/PeeGeeQConfig.java`

**Changes Required**:

1. **Remove the `vertxPool()` bean** (lines 155-170):
   ```java
   // ❌ DELETE THIS
   @Bean
   public Pool vertxPool(PeeGeeQManager manager) {
       Pool pool = manager.getClientFactory()
           .getConnectionManager()
           .getOrCreateReactivePool(...);
       return pool;
   }
   ```

2. **Add `databaseService()` bean instead**:
   ```java
   // ✅ ADD THIS
   @Bean
   public DatabaseService databaseService(PeeGeeQManager manager) {
       log.info("Creating DatabaseService bean");
       return new PgDatabaseService(manager);
   }
   ```

3. **Update imports**:
   ```java
   // Add:
   import dev.mars.peegeeq.api.database.DatabaseService;
   import dev.mars.peegeeq.db.provider.PgDatabaseService;
   
   // Remove (if not used elsewhere):
   import io.vertx.sqlclient.Pool;
   ```

**Verification**: Code should still compile after this change.

---

### Task 1.2: Revise OrderService.java (2-3 hours)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot/service/OrderService.java`

**Changes Required**:

1. **Update constructor to inject `DatabaseService` instead of `Pool`**:
   ```java
   // ❌ OLD
   private final Pool pool;
   
   public OrderService(Pool pool, ...) {
       this.pool = pool;
   }
   
   // ✅ NEW
   private final DatabaseService databaseService;
   
   public OrderService(DatabaseService databaseService, ...) {
       this.databaseService = databaseService;
   }
   ```

2. **Update `createOrder()` method**:
   ```java
   public CompletableFuture<String> createOrder(CreateOrderRequest request) {
       // ✅ Get ConnectionProvider from DatabaseService
       ConnectionProvider connectionProvider = databaseService.getConnectionProvider();
       
       // ✅ Use ConnectionProvider.withTransaction() with client ID
       return connectionProvider.withTransaction("peegeeq-main", connection -> {
           Order order = new Order(request);
           String orderId = order.getId();
           
           // Step 1: Send outbox event
           return Future.fromCompletionStage(
               orderEventProducer.sendInTransaction(new OrderCreatedEvent(request), connection)
           )
           // Step 2: Save order
           .compose(v -> orderRepository.save(order, connection))
           
           // Step 3: Save order items
           .compose(savedOrder -> orderItemRepository.saveAll(orderId, request.getItems(), connection))
           
           // Step 4: Send validation event
           .compose(v -> Future.fromCompletionStage(
               orderEventProducer.sendInTransaction(new OrderValidatedEvent(orderId), connection)
           ))
           
           .map(v -> orderId);
           
       }).toCompletionStage().toCompletableFuture();
   }
   ```

3. **Update all other methods** that use transactions (same pattern)

4. **Update imports**:
   ```java
   // Add:
   import dev.mars.peegeeq.api.database.DatabaseService;
   import dev.mars.peegeeq.api.database.ConnectionProvider;
   
   // Remove:
   import io.vertx.sqlclient.Pool;
   ```

**Verification**: Code should compile and run.

---

### Task 1.3: Update Schema Initialization (30 minutes)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot/config/PeeGeeQConfig.java`

**Changes Required**:

Update the `initializeSchema()` method to use `DatabaseService`:

```java
@EventListener(ApplicationReadyEvent.class)
public void initializeSchema(DatabaseService databaseService) {
    log.info("Initializing database schema");
    
    ConnectionProvider connectionProvider = databaseService.getConnectionProvider();
    
    try {
        // Read schema file
        String schema = new String(
            getClass().getResourceAsStream("/schema-springboot.sql").readAllBytes()
        );
        
        // Execute schema
        connectionProvider.withConnection("peegeeq-main", connection -> {
            return connection.query(schema).execute().mapEmpty();
        }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        
        log.info("Database schema initialized successfully");
    } catch (Exception e) {
        log.error("Failed to initialize schema", e);
        throw new RuntimeException("Schema initialization failed", e);
    }
}
```

---

### Task 1.4: Update Tests (1-2 hours)

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/springboot/TransactionalConsistencyTest.java`

**Changes Required**:

1. **Inject `DatabaseService` instead of creating separate pool**
2. **Add database verification queries**:
   ```java
   @Test
   void testRollbackOnValidationFailure() throws Exception {
       // Arrange
       CreateOrderRequest request = createInvalidRequest();
       
       // Act
       CompletableFuture<String> future = orderService.createOrderWithValidation(request);
       
       // Assert - should throw exception
       assertThrows(ValidationException.class, () -> future.get());
       
       // ✅ Verify database state - orders table
       ConnectionProvider cp = databaseService.getConnectionProvider();
       Integer orderCount = cp.withConnection("peegeeq-main", conn -> {
           return conn.preparedQuery("SELECT COUNT(*) FROM orders WHERE customer_id = $1")
               .execute(Tuple.of(request.getCustomerId()))
               .map(rs -> rs.iterator().next().getInteger(0));
       }).toCompletionStage().toCompletableFuture().get();
       
       assertEquals(0, orderCount, "Order should NOT exist after rollback");
       
       // ✅ Verify database state - outbox table
       Integer outboxCount = cp.withConnection("peegeeq-main", conn -> {
           return conn.preparedQuery("SELECT COUNT(*) FROM outbox WHERE topic = 'orders'")
               .execute()
               .map(rs -> rs.iterator().next().getInteger(0));
       }).toCompletionStage().toCompletableFuture().get();
       
       assertEquals(0, outboxCount, "Outbox events should NOT exist after rollback");
   }
   ```

3. **Add similar verification to all rollback tests**

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/springboot/OrderServiceTest.java`

**Changes Required**:
- Update to work with async Future-based operations
- Add database state verification

---

### Task 1.5: Run and Verify Tests (30 minutes)

**Commands**:
```bash
mvn clean test -pl peegeeq-examples -Dtest=TransactionalConsistencyTest
mvn clean test -pl peegeeq-examples -Dtest=OrderServiceTest
```

**Success Criteria**:
- ✅ All tests pass
- ✅ Database verification confirms rollback works
- ✅ No orphaned records in database

**Estimated Time for Phase 1**: 4-6 hours

---

## Phase 2: Fix springboot2 (Reactive) Example

**Goal**: Remove R2DBC and use PeeGeeQ's infrastructure for ALL database operations

### Task 2.1: Remove R2DBC Dependencies (30 minutes)

**File**: `peegeeq-examples/pom.xml`

**Changes Required**:

Remove these dependencies:
```xml
<!-- ❌ DELETE THESE -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>io.r2dbc</groupId>
    <artifactId>r2dbc-pool</artifactId>
</dependency>
```

**Verification**: Run `mvn clean compile` - should still compile (may have errors in springboot2 code, that's expected)

---

### Task 2.2: Delete R2dbcConfig.java (5 minutes)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/config/R2dbcConfig.java`

**Action**: Delete this entire file

---

### Task 2.3: Add DatabaseService to PeeGeeQReactiveConfig (30 minutes)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/config/PeeGeeQReactiveConfig.java`

**Changes Required**:

Add `databaseService()` bean:
```java
@Bean
public DatabaseService databaseService(PeeGeeQManager manager) {
    log.info("Creating DatabaseService bean for reactive application");
    return new PgDatabaseService(manager);
}
```

Add import:
```java
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
```

---

### Task 2.4: Replace OrderRepository (3-4 hours)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/repository/OrderRepository.java`

**Changes Required**:

1. **Remove R2DBC interface**:
   ```java
   // ❌ DELETE
   public interface OrderRepository extends ReactiveCrudRepository<Order, String> {
   }
   ```

2. **Create Vert.x-based implementation**:
   ```java
   @Repository
   public class OrderRepository {
       private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);
       
       public Mono<Order> save(Order order, SqlConnection connection) {
           String sql = "INSERT INTO orders (id, customer_id, amount, status, created_at) " +
                       "VALUES ($1, $2, $3, $4, $5)";
           
           Tuple params = Tuple.of(
               order.getId(),
               order.getCustomerId(),
               order.getAmount(),
               order.getStatus().toString(),
               LocalDateTime.now()
           );
           
           Future<Order> future = connection.preparedQuery(sql)
               .execute(params)
               .map(result -> order);
           
           return Mono.fromCompletionStage(future.toCompletionStage());
       }
       
       public Mono<Order> findById(String orderId, SqlConnection connection) {
           String sql = "SELECT * FROM orders WHERE id = $1";
           
           Future<Order> future = connection.preparedQuery(sql)
               .execute(Tuple.of(orderId))
               .map(rowSet -> {
                   if (rowSet.size() == 0) {
                       throw new OrderNotFoundException(orderId);
                   }
                   Row row = rowSet.iterator().next();
                   return mapRowToOrder(row);
               });
           
           return Mono.fromCompletionStage(future.toCompletionStage());
       }
       
       private Order mapRowToOrder(Row row) {
           return new Order(
               row.getString("id"),
               row.getString("customer_id"),
               row.getBigDecimal("amount"),
               List.of()  // Items loaded separately
           );
       }
   }
   ```

---

### Task 2.5: Replace OrderItemRepository (2-3 hours)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/repository/OrderItemRepository.java`

**Changes Required**: Same pattern as OrderRepository (Vert.x-based with Mono wrappers)

---

### Task 2.6: Update OrderService (4-5 hours)

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2/service/OrderService.java`

**Changes Required**:

```java
@Service
public class OrderService {
    private final DatabaseService databaseService;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    
    public Mono<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        // Wrap Vert.x Future in Mono
        return Mono.fromCompletionStage(
            cp.withTransaction("peegeeq-main", connection -> {
                Order order = new Order(request);
                String orderId = order.getId();
                
                // All operations use same connection
                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(new OrderCreatedEvent(request), connection)
                        .toCompletableFuture()
                )
                .compose(v -> orderRepository.save(order, connection).toFuture())
                .compose(v -> orderItemRepository.saveAll(orderId, request.getItems(), connection).toFuture())
                .map(v -> orderId);
                
            }).toCompletionStage()
        );
    }
}
```

---

### Task 2.7: Create/Update Tests (4-5 hours)

Create comprehensive tests similar to springboot example but using reactive patterns with `StepVerifier`.

---

### Task 2.8: Update Schema Initialization (1-2 hours)

Remove R2DBC-based initialization and add Vert.x-based initialization similar to springboot example.

**Estimated Time for Phase 2**: 17-23 hours

---

## Phase 3: Update Documentation

### Task 3.1: Update Example READMEs (2 hours)
- Document correct patterns
- Explain R2DBC removal

### Task 3.2: Update Architecture Documentation (2-3 hours)
- Clarify API usage
- Update transaction patterns

### Task 3.3: Create Migration Guide (2 hours)
- Guide for migrating from R2DBC to PeeGeeQ

**Estimated Time for Phase 3**: 6-8 hours

---

## Total Effort Summary

| Phase | Tasks | Estimated Time |
|-------|-------|----------------|
| **Phase 1** | Revise springboot example | 4-6 hours |
| **Phase 2** | Fix springboot2 example | 17-23 hours |
| **Phase 3** | Update documentation | 6-8 hours |
| **TOTAL** | | **27-37 hours** |

---

## Success Criteria

- ✅ No separate connection pools created
- ✅ All database operations use `ConnectionProvider`
- ✅ R2DBC completely removed
- ✅ Tests verify actual database state
- ✅ Rollback works correctly (verified by tests)
- ✅ Code follows patterns in `CORRECT_PEEGEEQ_API_USAGE.md`
- ✅ Documentation is accurate and complete

---

## Next Steps

1. **Review this plan** - Confirm approach is correct
2. **Start with Phase 1, Task 1.1** - Revise PeeGeeQConfig.java
3. **Test after each task** - Ensure nothing breaks
4. **Proceed sequentially** - Don't skip ahead

**Ready to begin implementation?**

