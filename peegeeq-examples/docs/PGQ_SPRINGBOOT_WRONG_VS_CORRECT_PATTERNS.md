# Wrong vs Correct PeeGeeQ Usage Patterns

**Date**: 2025-10-02  
**Purpose**: Side-by-side comparison of incorrect and correct PeeGeeQ API usage

---

## Pattern 1: Spring Configuration

### ❌ WRONG: Creating Separate Pool

```java
@Configuration
public class PeeGeeQConfig {
    
    @Bean
    public PeeGeeQManager peeGeeQManager(MeterRegistry meterRegistry) {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();
        return manager;
    }
    
    // ❌ WRONG: Creating a separate pool bypasses PeeGeeQ's infrastructure
    @Bean
    public Pool vertxPool(PeeGeeQManager manager) {
        // This creates a SECOND pool - PeeGeeQ already has one!
        Pool pool = manager.getClientFactory()
            .getConnectionManager()
            .getOrCreateReactivePool("peegeeq-main", ...);
        return pool;
    }
    
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        return (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
    }
}
```

### ✅ CORRECT: Using DatabaseService

```java
@Configuration
public class PeeGeeQConfig {
    
    @Bean
    public PeeGeeQManager peeGeeQManager(MeterRegistry meterRegistry) {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();
        return manager;
    }
    
    // ✅ CORRECT: Expose DatabaseService - it provides ConnectionProvider
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        return new PgDatabaseService(manager);
    }
    
    @Bean
    public QueueFactory outboxFactory(DatabaseService databaseService) {
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        return provider.createFactory("outbox", databaseService);
    }
    
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        return (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
    }
}
```

---

## Pattern 2: Service Layer Transactions

### ❌ WRONG: Using Pool Directly

```java
@Service
public class OrderService {
    
    private final Pool pool;  // ❌ WRONG: Using Pool directly
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        // ❌ WRONG: Using pool.withTransaction() directly
        return pool.withTransaction(connection -> {
            Order order = new Order(request);
            
            return Future.fromCompletionStage(
                orderEventProducer.sendInTransaction(new OrderCreatedEvent(request), connection)
            )
            .compose(v -> orderRepository.save(order, connection))
            .map(v -> order.getId());
            
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Why this is wrong:**
- Bypasses PeeGeeQ's API layer
- Directly uses internal Pool
- Doesn't use ConnectionProvider abstraction

### ✅ CORRECT: Using ConnectionProvider

```java
@Service
public class OrderService {
    
    private final DatabaseService databaseService;  // ✅ CORRECT: Use DatabaseService
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        // ✅ CORRECT: Get ConnectionProvider from DatabaseService
        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();
        
        // ✅ CORRECT: Use ConnectionProvider.withTransaction()
        return connectionProvider.withTransaction("peegeeq-main", connection -> {
            Order order = new Order(request);
            
            return Future.fromCompletionStage(
                orderEventProducer.sendInTransaction(new OrderCreatedEvent(request), connection)
            )
            .compose(v -> orderRepository.save(order, connection))
            .map(v -> order.getId());
            
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Why this is correct:**
- Uses PeeGeeQ's public API (ConnectionProvider)
- Proper abstraction layer
- Follows intended architecture

---

## Pattern 3: Using R2DBC (Spring Boot 2 Reactive)

### ❌ WRONG: Mixing R2DBC with PeeGeeQ

```java
// ❌ WRONG: pom.xml with R2DBC
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>

// ❌ WRONG: R2DBC Configuration
@Configuration
public class R2dbcConfig {
    @Bean
    public ConnectionFactory connectionFactory() {
        return ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(DRIVER, "postgresql")
                .option(HOST, "localhost")
                .option(PORT, 5432)
                .build()
        );
    }
}

// ❌ WRONG: R2DBC Repository
@Repository
public interface OrderRepository extends R2dbcRepository<Order, String> {
    // This uses R2DBC connection pool - SEPARATE from PeeGeeQ!
}

// ❌ WRONG: Service mixing R2DBC and PeeGeeQ
@Service
public class OrderService {
    private final OrderRepository orderRepository;  // R2DBC
    private final OutboxProducer<OrderEvent> producer;  // PeeGeeQ
    
    public Mono<String> createOrder(CreateOrderRequest request) {
        // These use DIFFERENT connection pools - NO TRANSACTION CONSISTENCY!
        return orderRepository.save(new Order(request))
            .flatMap(order -> Mono.fromFuture(
                producer.send(new OrderCreatedEvent(request))
            ))
            .map(Order::getId);
    }
}
```

**Why this is wrong:**
- R2DBC creates a SEPARATE connection pool
- R2DBC and PeeGeeQ use DIFFERENT connections
- Cannot share transactions
- Rollback in one doesn't affect the other

### ✅ CORRECT: Using Only PeeGeeQ

```java
// ✅ CORRECT: pom.xml WITHOUT R2DBC
<dependencies>
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-outbox</artifactId>
    </dependency>
    <!-- NO R2DBC dependencies -->
</dependencies>

// ✅ CORRECT: PeeGeeQ Configuration
@Configuration
public class PeeGeeQConfig {
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        return new PgDatabaseService(manager);
    }
    
    @Bean
    public OutboxProducer<OrderEvent> producer(QueueFactory factory) {
        return (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
    }
}

// ✅ CORRECT: Repository using Vert.x SqlConnection
@Repository
public class OrderRepository {
    public Future<Order> save(Order order, SqlConnection connection) {
        String sql = "INSERT INTO orders (id, customer_id, amount) VALUES ($1, $2, $3)";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(order.getId(), order.getCustomerId(), order.getAmount()))
            .map(result -> order);
    }
}

// ✅ CORRECT: Service using ConnectionProvider
@Service
public class OrderService {
    private final DatabaseService databaseService;
    private final OrderRepository orderRepository;
    private final OutboxProducer<OrderEvent> producer;
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        return cp.withTransaction("peegeeq-main", connection -> {
            Order order = new Order(request);
            
            // Both use the SAME connection - SAME transaction!
            return orderRepository.save(order, connection)
                .compose(saved -> Future.fromCompletionStage(
                    producer.sendInTransaction(new OrderCreatedEvent(request), connection)
                ))
                .map(v -> order.getId());
                
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Why this is correct:**
- Single connection pool (PeeGeeQ's)
- All operations use same connection
- True transactional consistency
- Rollback affects everything

---

## Pattern 4: Transaction Propagation

### ❌ WRONG: Using sendWithTransaction()

```java
@Service
public class OrderService {
    private final OutboxProducer<OrderEvent> producer;
    
    public CompletableFuture<Void> processOrder(Order order) {
        // ❌ WRONG: This creates a NEW transaction
        return producer.sendWithTransaction(
            new OrderCreatedEvent(order),
            TransactionPropagation.CONTEXT
        ).thenCompose(v -> {
            // ❌ This runs in a DIFFERENT transaction!
            return saveOrderToDatabase(order);
        });
    }
}
```

**Why this is wrong:**
- `sendWithTransaction()` creates its OWN transaction
- Business logic runs in a DIFFERENT transaction
- No consistency between outbox and business data

### ✅ CORRECT: Using sendInTransaction()

```java
@Service
public class OrderService {
    private final DatabaseService databaseService;
    private final OutboxProducer<OrderEvent> producer;
    private final OrderRepository orderRepository;
    
    public CompletableFuture<Void> processOrder(Order order) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        // ✅ CORRECT: Create ONE transaction
        return cp.withTransaction("peegeeq-main", connection -> {
            
            // ✅ Save order (uses this connection)
            return orderRepository.save(order, connection)
                .compose(saved -> {
                    // ✅ Send event (joins this transaction)
                    return Future.fromCompletionStage(
                        producer.sendInTransaction(new OrderCreatedEvent(order), connection)
                    );
                });
                
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Why this is correct:**
- ONE transaction created by `withTransaction()`
- Both operations use the SAME connection
- True atomic consistency

---

## Summary Table

| Aspect | ❌ WRONG | ✅ CORRECT |
|--------|---------|-----------|
| **Pool Creation** | Create separate Pool bean | Use DatabaseService |
| **Transaction Management** | `pool.withTransaction()` | `ConnectionProvider.withTransaction()` |
| **Database Access** | R2DBC or separate pool | PeeGeeQ's ConnectionProvider |
| **Outbox Events** | `sendWithTransaction()` | `sendInTransaction(connection)` |
| **Dependencies** | R2DBC, separate Vert.x | Only PeeGeeQ modules |
| **Imports** | Internal implementation classes | Public API packages |
| **Connection Pools** | Multiple (R2DBC + PeeGeeQ) | Single (PeeGeeQ only) |
| **Transaction Consistency** | ❌ No guarantee | ✅ Guaranteed |

---

## Key Takeaways

1. **PeeGeeQ provides ALL infrastructure** - Don't create your own
2. **Use DatabaseService** - It's the entry point for database operations
3. **Use ConnectionProvider** - It manages connections and transactions
4. **Use sendInTransaction()** - It joins existing transactions
5. **Remove R2DBC** - It creates incompatible connection pools
6. **Import from API packages** - Don't use internal implementation classes

**The examples should demonstrate how to HOST PeeGeeQ in Spring Boot, not how to create parallel database infrastructure.**

