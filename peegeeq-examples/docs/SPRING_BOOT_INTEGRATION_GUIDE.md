# PeeGeeQ Spring Boot Integration Guide

**Version**: 2.0
**Date**: 2025-10-02
**Purpose**: Comprehensive guide for integrating PeeGeeQ with Spring Boot applications

---

## Table of Contents

1. [Overview](#overview)
2. [Core Principles](#core-principles)
3. [Dependencies](#dependencies)
4. [Configuration](#configuration)
5. [Repository Layer](#repository-layer)
6. [Service Layer](#service-layer)
7. [Reactive Spring Boot](#reactive-spring-boot)
8. [Common Mistakes](#common-mistakes)
9. [Testing](#testing)
10. [Migration Guide](#migration-guide)

---

## Overview

PeeGeeQ provides a complete database infrastructure including:
- Connection pool management (via Vert.x)
- Transaction management
- Transactional outbox pattern
- Schema migrations
- Health checks and metrics

**Key Principle**: Your Spring Boot application should **host** PeeGeeQ, not create parallel database infrastructure.

---

## Core Principles

### 1. Use PeeGeeQ's Public API

✅ **CORRECT** - Use these public API classes:
- `DatabaseService` - Entry point for database operations
- `ConnectionProvider` - Manages connections and transactions
- `OutboxProducer` - Sends events to outbox
- `QueueFactory` - Creates producers and consumers

❌ **WRONG** - Don't use internal implementation classes:
- `Pool` (internal Vert.x pool)
- `PgClientFactory` (internal factory)
- Direct access to connection manager

### 2. Single Connection Pool

✅ **CORRECT**: Use only PeeGeeQ's connection pool
- All database operations go through `ConnectionProvider`
- All transactions use `ConnectionProvider.withTransaction()`
- All outbox events use `sendInTransaction(connection)`

❌ **WRONG**: Don't create separate connection pools
- No R2DBC connection pools
- No separate Vert.x pools
- No JDBC connection pools for application data

### 3. Transactional Consistency

✅ **CORRECT**: Share the same `SqlConnection` across all operations
```java
connectionProvider.withTransaction("client-id", connection -> {
    // All operations use this connection
    return orderRepository.save(order, connection)
        .compose(v -> Future.fromCompletionStage(
            producer.sendInTransaction(event, connection)
        ));
});
```

❌ **WRONG**: Using separate transactions
```java
// This creates TWO separate transactions - NO consistency!
producer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
    .thenCompose(v -> orderRepository.save(order));
```

---

## Dependencies

### Maven Configuration

```xml
<dependencies>
    <!-- PeeGeeQ Core -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-outbox</artifactId>
        <version>${peegeeq.version}</version>
    </dependency>

    <!-- Spring Boot Starter Web (for non-reactive) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- OR Spring Boot Starter WebFlux (for reactive) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>

    <!-- Micrometer for metrics (optional) -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

### ❌ DO NOT Include R2DBC

```xml
<!-- ❌ WRONG - Do NOT include these -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
```

**Why?** R2DBC creates a separate connection pool that cannot share transactions with PeeGeeQ.

---

## Configuration

### Spring Configuration Class

```java
@Configuration
public class PeeGeeQConfig {

    private static final Logger log = LoggerFactory.getLogger(PeeGeeQConfig.class);

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Create and start PeeGeeQ Manager.
     * This initializes the connection pool, migrations, health checks, etc.
     */
    @Bean
    public PeeGeeQManager peeGeeQManager(
            @Value("${spring.profiles.active:default}") String profile,
            MeterRegistry meterRegistry) {

        log.info("Creating PeeGeeQ Manager with profile: {}", profile);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(profile);
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();

        log.info("PeeGeeQ Manager started successfully");
        return manager;
    }

    /**
     * ✅ CORRECT: Expose DatabaseService for application use.
     * This is the entry point for all database operations.
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        log.info("Creating DatabaseService bean");
        DatabaseService service = new PgDatabaseService(manager);
        log.info("DatabaseService created successfully");
        return service;
    }

    /**
     * Create outbox factory for producing/consuming events.
     */
    @Bean
    public QueueFactory outboxFactory(DatabaseService databaseService) {
        log.info("Creating outbox factory");

        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        QueueFactory factory = provider.createFactory("outbox", databaseService);

        log.info("Outbox factory created successfully");
        return factory;
    }

    /**
     * Create producer for order events.
     */
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        log.info("Creating order event producer");
        OutboxProducer<OrderEvent> producer =
            (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
        log.info("Order event producer created successfully");
        return producer;
    }

    /**
     * Initialize database schema on application startup.
     * Uses ApplicationContext to avoid circular dependency.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeSchema() {
        log.info("Initializing database schema");

        try {
            // Load schema SQL from classpath
            ClassPathResource resource = new ClassPathResource("schema.sql");
            String schemaSql;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                schemaSql = reader.lines().collect(Collectors.joining("\n"));
            }

            // Get DatabaseService from context (avoids circular dependency)
            DatabaseService databaseService = applicationContext.getBean(DatabaseService.class);
            ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

            // Execute schema using PeeGeeQ's connection
            connectionProvider.withConnection("peegeeq-main", connection ->
                connection.query(schemaSql).execute().mapEmpty()
            )
            .onSuccess(result -> log.info("Database schema initialized successfully"))
            .onFailure(error -> log.error("Failed to initialize schema: {}", error.getMessage(), error))
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        } catch (Exception e) {
            log.error("Error initializing database schema: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }
}
```

### Configuration Properties

```properties
# application.properties
spring.application.name=my-peegeeq-app
spring.profiles.active=development

# PeeGeeQ will load from peegeeq-{profile}.properties
# Example: peegeeq-development.properties
```

```properties
# peegeeq-development.properties
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.name=myapp_dev
peegeeq.database.user=postgres
peegeeq.database.password=postgres
peegeeq.database.pool.maxSize=20
```

---

## Repository Layer

### ✅ CORRECT: Repository Using SqlConnection

```java
@Repository
public class OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    /**
     * Save order using the provided connection.
     * This ensures the operation participates in the caller's transaction.
     */
    public Future<Order> save(Order order, SqlConnection connection) {
        String sql = """
            INSERT INTO orders (id, customer_id, amount, status, created_at)
            VALUES ($1, $2, $3, $4, $5)
            """;

        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                order.getId(),
                order.getCustomerId(),
                order.getAmount(),
                order.getStatus(),
                order.getCreatedAt()
            ))
            .map(result -> {
                log.info("Order saved successfully: {}", order.getId());
                return order;
            });
    }

    /**
     * Find order by ID using the provided connection.
     */
    public Future<Optional<Order>> findById(String id, SqlConnection connection) {
        String sql = "SELECT * FROM orders WHERE id = $1";

        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    return Optional.empty();
                }
                Row row = rowSet.iterator().next();
                return Optional.of(mapRowToOrder(row));
            });
    }

    private Order mapRowToOrder(Row row) {
        Order order = new Order();
        order.setId(row.getString("id"));
        order.setCustomerId(row.getString("customer_id"));
        order.setAmount(row.getBigDecimal("amount"));
        order.setStatus(row.getString("status"));
        order.setCreatedAt(row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC));
        return order;
    }
}
```

### ❌ WRONG: R2DBC Repository

```java
// ❌ WRONG - Don't use R2DBC repositories
@Repository
public interface OrderRepository extends R2dbcRepository<Order, String> {
    // This uses a SEPARATE connection pool from PeeGeeQ!
}
```

---

## Service Layer

### ✅ CORRECT: Service Using ConnectionProvider

```java
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String CLIENT_ID = "peegeeq-main";

    private final DatabaseService databaseService;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderService(
            DatabaseService databaseService,
            OutboxProducer<OrderEvent> orderEventProducer,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository) {
        this.databaseService = databaseService;
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    /**
     * ✅ CORRECT: Create order with transactional outbox pattern.
     *
     * All operations use the SAME connection from a SINGLE transaction.
     */
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerId());

        // Get ConnectionProvider from DatabaseService
        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        // Create a single transaction
        return connectionProvider.withTransaction(CLIENT_ID, connection -> {
            Order order = new Order(request);
            String orderId = order.getId();

            // Step 1: Send outbox event (uses this connection)
            return Future.fromCompletionStage(
                orderEventProducer.sendInTransaction(
                    new OrderCreatedEvent(request),
                    connection  // ✅ Same connection
                )
            )
            // Step 2: Save order (uses this connection)
            .compose(v -> orderRepository.save(order, connection))

            // Step 3: Save order items (uses this connection)
            .compose(savedOrder ->
                orderItemRepository.saveAll(orderId, request.getItems(), connection)
            )

            // Step 4: Send additional events (uses this connection)
            .compose(v -> Future.fromCompletionStage(
                orderEventProducer.sendInTransaction(
                    new OrderValidatedEvent(orderId),
                    connection  // ✅ Same connection
                )
            ))
            .map(v -> orderId)
            .onSuccess(id -> log.info("✅ Order {} created successfully", id))
            .onFailure(error -> log.error("❌ Order creation failed: {}", error.getMessage()));

        }).toCompletionStage().toCompletableFuture();
    }
}
```

### Key Points

1. **Inject `DatabaseService`** - Not `Pool` or internal classes
2. **Get `ConnectionProvider`** - From `DatabaseService.getConnectionProvider()`
3. **Use `withTransaction()`** - Creates a single transaction
4. **Pass `connection`** - To all repository methods and `sendInTransaction()`
5. **Chain with `compose()`** - For sequential operations in same transaction
6. **Return `CompletableFuture`** - Convert from Vert.x `Future`

---

## Reactive Spring Boot

For reactive Spring Boot applications (WebFlux), wrap PeeGeeQ's `CompletableFuture` in `Mono`.

### ReactiveOutboxAdapter

```java
@Component
public class ReactiveOutboxAdapter {

    /**
     * Convert CompletableFuture to Mono.
     */
    public <T> Mono<T> toMono(CompletableFuture<T> future) {
        return Mono.fromFuture(future);
    }

    /**
     * Convert CompletableFuture<Void> to Mono<Void>.
     */
    public Mono<Void> toMonoVoid(CompletableFuture<Void> future) {
        return Mono.fromFuture(future);
    }
}
```

### Reactive Service Example

```java
@Service
public class OrderService {

    private final DatabaseService databaseService;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    private final ReactiveOutboxAdapter adapter;

    /**
     * ✅ CORRECT: Reactive service using PeeGeeQ.
     * Wraps CompletableFuture in Mono for Spring WebFlux compatibility.
     */
    public Mono<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        // Same pattern as non-reactive, but wrapped in Mono
        return adapter.toMono(
            connectionProvider.withTransaction("peegeeq-main", connection -> {
                Order order = new Order(request);

                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(
                        new OrderCreatedEvent(request),
                        connection
                    )
                )
                .compose(v -> orderRepository.save(order, connection))
                .map(v -> order.getId());

            }).toCompletionStage().toCompletableFuture()
        );
    }
}
```

### Reactive Controller Example

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public Mono<ResponseEntity<OrderResponse>> createOrder(
            @RequestBody CreateOrderRequest request) {

        return orderService.createOrder(request)
            .map(orderId -> ResponseEntity.ok(new OrderResponse(orderId)))
            .onErrorResume(error ->
                Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
            );
    }
}
```

---

## Common Mistakes

### ❌ Mistake 1: Using R2DBC

**Problem:**
```java
// ❌ WRONG - R2DBC creates separate connection pool
@Repository
public interface OrderRepository extends R2dbcRepository<Order, String> {}

@Service
public class OrderService {
    public Mono<String> createOrder(CreateOrderRequest request) {
        // These use DIFFERENT connection pools - NO transaction consistency!
        return orderRepository.save(new Order(request))
            .flatMap(order -> Mono.fromFuture(
                producer.send(new OrderCreatedEvent(request))
            ));
    }
}
```

**Solution:**
```java
// ✅ CORRECT - Use PeeGeeQ's ConnectionProvider
@Repository
public class OrderRepository {
    public Future<Order> save(Order order, SqlConnection connection) {
        // Uses PeeGeeQ's connection
    }
}

@Service
public class OrderService {
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        return connectionProvider.withTransaction("client-id", connection -> {
            // All operations use SAME connection
            return orderRepository.save(order, connection)
                .compose(v -> Future.fromCompletionStage(
                    producer.sendInTransaction(event, connection)
                ));
        }).toCompletionStage().toCompletableFuture();
    }
}
```

### ❌ Mistake 2: Using sendWithTransaction()

**Problem:**
```java
// ❌ WRONG - Creates separate transaction
producer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
    .thenCompose(v -> orderRepository.save(order));
```

**Solution:**
```java
// ✅ CORRECT - Joins existing transaction
connectionProvider.withTransaction("client-id", connection -> {
    return Future.fromCompletionStage(
        producer.sendInTransaction(event, connection)
    )
    .compose(v -> orderRepository.save(order, connection));
});
```

### ❌ Mistake 3: Creating Separate Pool

**Problem:**
```java
// ❌ WRONG - Creating separate pool
@Bean
public Pool vertxPool(PeeGeeQManager manager) {
    return manager.getClientFactory()
        .getConnectionManager()
        .getOrCreateReactivePool("my-pool", ...);
}
```

**Solution:**
```java
// ✅ CORRECT - Use DatabaseService
@Bean
public DatabaseService databaseService(PeeGeeQManager manager) {
    return new PgDatabaseService(manager);
}
```

### ❌ Mistake 4: Using Pool Directly

**Problem:**
```java
// ❌ WRONG - Using Pool directly
@Service
public class OrderService {
    private final Pool pool;  // Internal implementation class

    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        return pool.withTransaction(connection -> {
            // Bypasses PeeGeeQ's API layer
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Solution:**
```java
// ✅ CORRECT - Using ConnectionProvider
@Service
public class OrderService {
    private final DatabaseService databaseService;  // Public API

    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withTransaction("client-id", connection -> {
            // Uses public API
        }).toCompletionStage().toCompletableFuture();
    }
}
```

### ❌ Mistake 5: Model Classes with R2DBC Annotations

**Problem:**
```java
// ❌ WRONG - R2DBC annotations
@Table("orders")
public class Order {
    @Id
    private String id;

    @Column("customer_id")
    private String customerId;
}
```

**Solution:**
```java
// ✅ CORRECT - Plain POJO
public class Order {
    private String id;
    private String customerId;

    // No annotations needed - manual SQL mapping in repository
}
```

---

## Testing

### Test Configuration

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.profiles.active=test",
    // Exclude R2DBC auto-configuration (if Spring Boot tries to configure it)
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
})
public class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private DatabaseService databaseService;

    @BeforeEach
    public void setup() {
        // Clean database before each test
        ConnectionProvider cp = databaseService.getConnectionProvider();
        cp.withConnection("peegeeq-main", connection ->
            connection.query("TRUNCATE TABLE orders, order_items CASCADE")
                .execute()
                .mapEmpty()
        ).toCompletionStage().toCompletableFuture().join();
    }
}
```

### Test Transactional Rollback

```java
@Test
public void testBusinessValidationRollback() {
    CreateOrderRequest request = new CreateOrderRequest();
    request.setCustomerId("INVALID_CUSTOMER");
    request.setAmount(new BigDecimal("15000")); // Exceeds limit

    // Should throw exception and rollback
    assertThrows(CompletionException.class, () -> {
        orderService.createOrderWithValidation(request)
            .join();
    });

    // Verify nothing was saved (transaction rolled back)
    ConnectionProvider cp = databaseService.getConnectionProvider();
    Long count = cp.withConnection("peegeeq-main", connection ->
        connection.query("SELECT COUNT(*) FROM orders")
            .execute()
            .map(rowSet -> rowSet.iterator().next().getLong(0))
    ).toCompletionStage().toCompletableFuture().join();

    assertEquals(0L, count, "Order should not exist after rollback");
}
```

---

## Migration Guide

### Migrating from R2DBC to PeeGeeQ

If you have an existing Spring Boot application using R2DBC, follow these steps:

#### Step 1: Remove R2DBC Dependencies

```xml
<!-- Remove these from pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
```

#### Step 2: Remove R2DBC Configuration

Delete `R2dbcConfig.java` or any R2DBC configuration classes.

#### Step 3: Update Model Classes

Remove R2DBC annotations:
```java
// Before
@Table("orders")
public class Order {
    @Id
    private String id;
    @Column("customer_id")
    private String customerId;
}

// After
public class Order {
    private String id;
    private String customerId;
}
```

#### Step 4: Rewrite Repositories

```java
// Before (R2DBC)
public interface OrderRepository extends R2dbcRepository<Order, String> {
    Mono<Order> findByCustomerId(String customerId);
}

// After (PeeGeeQ)
@Repository
public class OrderRepository {
    public Future<Order> save(Order order, SqlConnection connection) {
        String sql = "INSERT INTO orders (id, customer_id) VALUES ($1, $2)";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(order.getId(), order.getCustomerId()))
            .map(result -> order);
    }

    public Future<Optional<Order>> findByCustomerId(String customerId, SqlConnection connection) {
        String sql = "SELECT * FROM orders WHERE customer_id = $1";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(customerId))
            .map(rowSet -> {
                if (rowSet.size() == 0) return Optional.empty();
                return Optional.of(mapRowToOrder(rowSet.iterator().next()));
            });
    }
}
```

#### Step 5: Update Service Layer

```java
// Before (R2DBC)
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final OutboxProducer<OrderEvent> producer;

    public Mono<String> createOrder(CreateOrderRequest request) {
        return orderRepository.save(new Order(request))
            .flatMap(order -> Mono.fromFuture(
                producer.send(new OrderCreatedEvent(request))
            ))
            .map(Order::getId);
    }
}

// After (PeeGeeQ)
@Service
public class OrderService {
    private final DatabaseService databaseService;
    private final OrderRepository orderRepository;
    private final OutboxProducer<OrderEvent> producer;

    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        ConnectionProvider cp = databaseService.getConnectionProvider();

        return cp.withTransaction("peegeeq-main", connection -> {
            Order order = new Order(request);

            return orderRepository.save(order, connection)
                .compose(v -> Future.fromCompletionStage(
                    producer.sendInTransaction(new OrderCreatedEvent(request), connection)
                ))
                .map(v -> order.getId());

        }).toCompletionStage().toCompletableFuture();
    }
}
```

#### Step 6: Update Tests

Add R2DBC exclusion to test configuration:
```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
})
public class OrderServiceTest {
    // Tests...
}
```

---

## Summary Checklist

### Configuration
- [ ] Create `PeeGeeQManager` bean
- [ ] Create `DatabaseService` bean (not `Pool`)
- [ ] Create `QueueFactory` bean
- [ ] Create `OutboxProducer` beans
- [ ] Initialize schema using `ConnectionProvider`
- [ ] Avoid circular dependencies (use `ApplicationContext`)

### Dependencies
- [ ] Include `peegeeq-outbox` dependency
- [ ] Include Spring Boot starter (web or webflux)
- [ ] **DO NOT** include R2DBC dependencies
- [ ] **DO NOT** include separate Vert.x dependencies

### Repository Layer
- [ ] Use `SqlConnection` parameter in all methods
- [ ] Return Vert.x `Future` (not `Mono` or `Flux`)
- [ ] Use manual SQL mapping (no annotations)
- [ ] **DO NOT** extend R2DBC repositories

### Service Layer
- [ ] Inject `DatabaseService` (not `Pool`)
- [ ] Get `ConnectionProvider` from `DatabaseService`
- [ ] Use `ConnectionProvider.withTransaction()`
- [ ] Pass `connection` to all repository methods
- [ ] Use `sendInTransaction(event, connection)`
- [ ] **DO NOT** use `sendWithTransaction()`

### Reactive Applications
- [ ] Create `ReactiveOutboxAdapter` component
- [ ] Wrap `CompletableFuture` in `Mono`
- [ ] Return `Mono` from service methods
- [ ] **DO NOT** use R2DBC

### Testing
- [ ] Exclude R2DBC auto-configuration
- [ ] Test transactional rollback scenarios
- [ ] Verify database state after rollback
- [ ] Clean database between tests

---

## Additional Resources

- **Working Examples**: See `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot`
- **Reactive Example**: See `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2`
- **Test Examples**: See `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/springboot`

---

## Questions?

If you encounter issues:
1. Check that you're using `DatabaseService` (not `Pool`)
2. Verify all operations use the same `SqlConnection`
3. Ensure you're using `sendInTransaction()` (not `sendWithTransaction()`)
4. Confirm R2DBC dependencies are removed
5. Review the working examples in `peegeeq-examples`

**Remember**: PeeGeeQ provides complete database infrastructure. Your Spring Boot application should host it, not create parallel infrastructure.

