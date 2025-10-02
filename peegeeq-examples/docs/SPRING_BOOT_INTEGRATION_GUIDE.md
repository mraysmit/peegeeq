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
8. [Example Use Cases](#example-use-cases)
9. [Common Mistakes](#common-mistakes)
10. [Testing](#testing)
11. [Migration Guide](#migration-guide)

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

## Example Use Cases

This section demonstrates various use cases for PeeGeeQ with Spring Boot, showing that you can handle **all types of database operations** without needing R2DBC. The examples are organized by category to help you find patterns relevant to your application.

### Category 1: Pure CRUD Operations (No Messaging)

These examples show standard database operations **without** using the outbox pattern or messaging. Use these patterns when you just need regular data access.

#### Example 1A: Customer Management Service (Non-Reactive)

**Purpose**: Standard CRUD operations without any messaging

**Components**:
- `Customer.java` - Plain POJO (no R2DBC annotations)
- `CustomerRepository.java` - Vert.x SQL Client CRUD operations
- `CustomerService.java` - Standard service layer

**Operations**:
```java
// Create
public CompletableFuture<Customer> createCustomer(Customer customer) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "INSERT INTO customers (id, name, email, created_at) VALUES ($1, $2, $3, $4)";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(customer.getId(), customer.getName(),
                             customer.getEmail(), customer.getCreatedAt()))
            .map(result -> customer);
    }).toCompletionStage().toCompletableFuture();
}

// Read by ID
public CompletableFuture<Optional<Customer>> findById(String id) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "SELECT * FROM customers WHERE id = $1";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) return Optional.empty();
                return Optional.of(mapRowToCustomer(rowSet.iterator().next()));
            });
    }).toCompletionStage().toCompletableFuture();
}

// Update
public CompletableFuture<Customer> updateCustomer(Customer customer) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "UPDATE customers SET name = $1, email = $2, updated_at = $3 WHERE id = $4";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(customer.getName(), customer.getEmail(),
                             Instant.now(), customer.getId()))
            .map(result -> customer);
    }).toCompletionStage().toCompletableFuture();
}

// Delete
public CompletableFuture<Void> deleteCustomer(String id) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "DELETE FROM customers WHERE id = $1";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .mapEmpty();
    }).toCompletionStage().toCompletableFuture();
}

// List with pagination
public CompletableFuture<List<Customer>> findAll(int page, int size) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "SELECT * FROM customers ORDER BY created_at DESC LIMIT $1 OFFSET $2";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(size, page * size))
            .map(rowSet -> {
                List<Customer> customers = new ArrayList<>();
                rowSet.forEach(row -> customers.add(mapRowToCustomer(row)));
                return customers;
            });
    }).toCompletionStage().toCompletableFuture();
}

// Search by name
public CompletableFuture<List<Customer>> searchByName(String name) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "SELECT * FROM customers WHERE name ILIKE $1 ORDER BY name";
        return connection.preparedQuery(sql)
            .execute(Tuple.of("%" + name + "%"))
            .map(rowSet -> {
                List<Customer> customers = new ArrayList<>();
                rowSet.forEach(row -> customers.add(mapRowToCustomer(row)));
                return customers;
            });
    }).toCompletionStage().toCompletableFuture();
}

// Check existence
public CompletableFuture<Boolean> existsByEmail(String email) {
    ConnectionProvider cp = databaseService.getConnectionProvider();
    return cp.withConnection("peegeeq-main", connection -> {
        String sql = "SELECT COUNT(*) FROM customers WHERE email = $1";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(email))
            .map(rowSet -> rowSet.iterator().next().getLong(0) > 0);
    }).toCompletionStage().toCompletableFuture();
}
```

**REST Endpoints**:
```java
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    @PostMapping
    public CompletableFuture<ResponseEntity<Customer>> create(@RequestBody Customer customer) {
        return customerService.createCustomer(customer)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public CompletableFuture<ResponseEntity<Customer>> getById(@PathVariable String id) {
        return customerService.findById(id)
            .thenApply(opt -> opt.map(ResponseEntity::ok)
                                 .orElse(ResponseEntity.notFound().build()));
    }

    @PutMapping("/{id}")
    public CompletableFuture<ResponseEntity<Customer>> update(
            @PathVariable String id, @RequestBody Customer customer) {
        customer.setId(id);
        return customerService.updateCustomer(customer)
            .thenApply(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<Void>> delete(@PathVariable String id) {
        return customerService.deleteCustomer(id)
            .thenApply(v -> ResponseEntity.noContent().build());
    }

    @GetMapping
    public CompletableFuture<ResponseEntity<List<Customer>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.findAll(page, size)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/search")
    public CompletableFuture<ResponseEntity<List<Customer>>> search(
            @RequestParam String name) {
        return customerService.searchByName(name)
            .thenApply(ResponseEntity::ok);
    }
}
```

**Key Points**:
- ✅ No outbox events needed for simple CRUD
- ✅ Use `withConnection()` for single operations (auto-commits)
- ✅ Use `withTransaction()` for multi-step updates
- ✅ Pagination using LIMIT/OFFSET
- ✅ Full-text search with ILIKE
- ✅ No R2DBC required

---

#### Example 1B: Product Catalog Service (Reactive)

**Purpose**: Reactive CRUD operations without messaging

**Components**:
- `Product.java` and `Category.java` - Plain POJOs
- `ProductRepository.java` and `CategoryRepository.java` - Vert.x SQL Client
- `ProductService.java` - Reactive service layer

**Operations**:
```java
@Service
public class ProductService {

    private final DatabaseService databaseService;
    private final ReactiveOutboxAdapter adapter;

    // Create product - returns Mono
    public Mono<Product> createProduct(Product product) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = "INSERT INTO products (id, name, category_id, price) VALUES ($1, $2, $3, $4)";
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(product.getId(), product.getName(),
                                     product.getCategoryId(), product.getPrice()))
                    .map(result -> product);
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Find with category (JOIN) - returns Mono
    public Mono<ProductWithCategory> findByIdWithCategory(String id) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = """
                    SELECT p.*, c.name as category_name
                    FROM products p
                    LEFT JOIN categories c ON p.category_id = c.id
                    WHERE p.id = $1
                    """;
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(id))
                    .map(rowSet -> {
                        if (rowSet.size() == 0) return null;
                        return mapRowToProductWithCategory(rowSet.iterator().next());
                    });
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // List all products - returns Flux
    public Flux<Product> findAllProducts() {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> {
                    String sql = "SELECT * FROM products ORDER BY name";
                    return connection.preparedQuery(sql)
                        .execute()
                        .map(rowSet -> {
                            List<Product> products = new ArrayList<>();
                            rowSet.forEach(row -> products.add(mapRowToProduct(row)));
                            return products;
                        });
                }).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }

    // Batch insert products
    public Mono<Integer> importProducts(List<Product> products) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withTransaction("peegeeq-main", connection -> {
                String sql = "INSERT INTO products (id, name, category_id, price) VALUES ($1, $2, $3, $4)";

                List<Tuple> batch = products.stream()
                    .map(p -> Tuple.of(p.getId(), p.getName(), p.getCategoryId(), p.getPrice()))
                    .collect(Collectors.toList());

                return connection.preparedQuery(sql)
                    .executeBatch(batch)
                    .map(result -> products.size());
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Conditional update - only if price changed
    public Mono<Boolean> updatePriceIfChanged(String id, BigDecimal newPrice) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withTransaction("peegeeq-main", connection -> {
                String sql = """
                    UPDATE products
                    SET price = $1, updated_at = $2
                    WHERE id = $3 AND price != $1
                    """;
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(newPrice, Instant.now(), id))
                    .map(result -> result.rowCount() > 0);
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Soft delete
    public Mono<Void> deactivateProduct(String id) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMonoVoid(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = "UPDATE products SET active = false, updated_at = $1 WHERE id = $2";
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(Instant.now(), id))
                    .mapEmpty();
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Count by category
    public Mono<Map<String, Long>> countByCategory() {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = """
                    SELECT c.name, COUNT(p.id) as product_count
                    FROM categories c
                    LEFT JOIN products p ON c.id = p.category_id
                    GROUP BY c.name
                    ORDER BY product_count DESC
                    """;
                return connection.preparedQuery(sql)
                    .execute()
                    .map(rowSet -> {
                        Map<String, Long> counts = new HashMap<>();
                        rowSet.forEach(row -> counts.put(
                            row.getString("name"),
                            row.getLong("product_count")
                        ));
                        return counts;
                    });
            }).toCompletionStage().toCompletableFuture()
        );
    }
}
```

**Reactive Controller**:
```java
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @PostMapping
    public Mono<ResponseEntity<Product>> create(@RequestBody Product product) {
        return productService.createProduct(product)
            .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ProductWithCategory>> getById(@PathVariable String id) {
        return productService.findByIdWithCategory(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Flux<Product> listAll() {
        return productService.findAllProducts();
    }

    @PostMapping("/import")
    public Mono<ResponseEntity<ImportResult>> importBatch(@RequestBody List<Product> products) {
        return productService.importProducts(products)
            .map(count -> ResponseEntity.ok(new ImportResult(count)));
    }

    @GetMapping("/stats/by-category")
    public Mono<ResponseEntity<Map<String, Long>>> getStatsByCategory() {
        return productService.countByCategory()
            .map(ResponseEntity::ok);
    }
}
```

**Key Points**:
- ✅ Wrap `CompletableFuture` in `Mono`/`Flux` for Spring WebFlux
- ✅ No R2DBC needed for reactive operations
- ✅ Complex queries with JOINs using Vert.x SQL Client
- ✅ Batch operations with `executeBatch()`
- ✅ Aggregations and GROUP BY queries
- ✅ Conditional updates and soft deletes

---

### Category 2: Read-Heavy Operations

These examples show complex queries, aggregations, and reporting without messaging.

#### Example 2A: Reporting Service (Non-Reactive)

**Purpose**: Complex queries, aggregations, and reporting

**Components**:
- `OrderSummary.java`, `SalesReport.java`, `CustomerStats.java` - DTOs
- `ReportingRepository.java` - Complex queries
- `ReportingService.java` - Report generation

**Operations**:
```java
@Service
public class ReportingService {

    private final DatabaseService databaseService;

    // Sales summary by month
    public CompletableFuture<List<MonthlySales>> getSalesByMonth(
            LocalDate startDate, LocalDate endDate) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT
                    DATE_TRUNC('month', created_at) as month,
                    COUNT(*) as order_count,
                    SUM(amount) as total_sales,
                    AVG(amount) as avg_order_value
                FROM orders
                WHERE created_at >= $1 AND created_at < $2
                GROUP BY DATE_TRUNC('month', created_at)
                ORDER BY month DESC
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(startDate, endDate))
                .map(rowSet -> {
                    List<MonthlySales> results = new ArrayList<>();
                    rowSet.forEach(row -> results.add(mapRowToMonthlySales(row)));
                    return results;
                });
        }).toCompletionStage().toCompletableFuture();
    }

    // Top customers with order details
    public CompletableFuture<List<CustomerStats>> getTopCustomers(int limit) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = """
                WITH customer_totals AS (
                    SELECT customer_id,
                           COUNT(*) as order_count,
                           SUM(amount) as total_spent
                    FROM orders
                    GROUP BY customer_id
                )
                SELECT c.id, c.name, c.email,
                       ct.order_count, ct.total_spent
                FROM customers c
                JOIN customer_totals ct ON c.id = ct.customer_id
                ORDER BY ct.total_spent DESC
                LIMIT $1
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(limit))
                .map(rowSet -> {
                    List<CustomerStats> results = new ArrayList<>();
                    rowSet.forEach(row -> results.add(mapRowToCustomerStats(row)));
                    return results;
                });
        }).toCompletionStage().toCompletableFuture();
    }

    // Order details with items (multi-table join)
    public CompletableFuture<OrderWithDetails> getOrderDetails(String orderId) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT
                    o.id, o.customer_id, o.amount, o.status, o.created_at,
                    c.name as customer_name, c.email as customer_email,
                    oi.id as item_id, oi.product_id, oi.quantity, oi.price
                FROM orders o
                JOIN customers c ON o.customer_id = c.id
                LEFT JOIN order_items oi ON o.id = oi.order_id
                WHERE o.id = $1
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(orderId))
                .map(rowSet -> mapRowSetToOrderWithDetails(rowSet));
        }).toCompletionStage().toCompletableFuture();
    }

    // Running totals with window functions
    public CompletableFuture<List<DailySalesWithRunningTotal>> getDailySalesWithRunningTotal(
            LocalDate startDate, LocalDate endDate) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT
                    DATE(created_at) as sale_date,
                    SUM(amount) as daily_total,
                    SUM(SUM(amount)) OVER (ORDER BY DATE(created_at)) as running_total
                FROM orders
                WHERE created_at >= $1 AND created_at < $2
                GROUP BY DATE(created_at)
                ORDER BY sale_date
                """;
            return connection.preparedQuery(sql)
                .execute(Tuple.of(startDate, endDate))
                .map(rowSet -> {
                    List<DailySalesWithRunningTotal> results = new ArrayList<>();
                    rowSet.forEach(row -> results.add(mapRowToDailySales(row)));
                    return results;
                });
        }).toCompletionStage().toCompletableFuture();
    }

    // Product performance ranking
    public CompletableFuture<List<ProductRanking>> getProductRankings() {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT
                    p.id, p.name,
                    COUNT(oi.id) as times_ordered,
                    SUM(oi.quantity) as total_quantity,
                    SUM(oi.quantity * oi.price) as total_revenue,
                    RANK() OVER (ORDER BY SUM(oi.quantity * oi.price) DESC) as revenue_rank
                FROM products p
                LEFT JOIN order_items oi ON p.id = oi.product_id
                GROUP BY p.id, p.name
                ORDER BY revenue_rank
                """;
            return connection.preparedQuery(sql)
                .execute()
                .map(rowSet -> {
                    List<ProductRanking> results = new ArrayList<>();
                    rowSet.forEach(row -> results.add(mapRowToProductRanking(row)));
                    return results;
                });
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**REST Endpoints**:
```java
@RestController
@RequestMapping("/api/reports")
public class ReportingController {

    @GetMapping("/sales/monthly")
    public CompletableFuture<ResponseEntity<List<MonthlySales>>> getMonthlySales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportingService.getSalesByMonth(startDate, endDate)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/customers/top")
    public CompletableFuture<ResponseEntity<List<CustomerStats>>> getTopCustomers(
            @RequestParam(defaultValue = "10") int limit) {
        return reportingService.getTopCustomers(limit)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/orders/{id}/details")
    public CompletableFuture<ResponseEntity<OrderWithDetails>> getOrderDetails(
            @PathVariable String id) {
        return reportingService.getOrderDetails(id)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/sales/daily-running-total")
    public CompletableFuture<ResponseEntity<List<DailySalesWithRunningTotal>>> getDailyRunningTotal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return reportingService.getDailySalesWithRunningTotal(startDate, endDate)
            .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/products/rankings")
    public CompletableFuture<ResponseEntity<List<ProductRanking>>> getProductRankings() {
        return reportingService.getProductRankings()
            .thenApply(ResponseEntity::ok);
    }
}
```

**Key Points**:
- ✅ Complex SQL with aggregations (SUM, AVG, COUNT, GROUP BY)
- ✅ Common Table Expressions (CTEs) for complex queries
- ✅ Window functions (RANK, SUM OVER) for analytics
- ✅ Multi-table JOINs for comprehensive reports
- ✅ Date range queries with proper indexing
- ✅ Read-only operations use `withConnection()` (no transaction overhead)
- ✅ Efficient result mapping to DTOs
- ✅ No JPA/Hibernate needed for reporting

---

#### Example 2B: Search Service (Reactive)

**Purpose**: Full-text search and dynamic filtering

**Components**:
- `SearchCriteria.java` - Filter parameters
- `SearchResult.java` - Paginated results
- `SearchRepository.java` - Dynamic query builder
- `SearchService.java` - Search logic

**Operations**:
```java
@Service
public class SearchService {

    private final DatabaseService databaseService;
    private final ReactiveOutboxAdapter adapter;

    // Full-text search with PostgreSQL tsvector
    public Mono<SearchResult<Product>> searchProducts(String query, int page, int size) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = """
                    SELECT *,
                           ts_rank(search_vector, plainto_tsquery('english', $1)) as rank
                    FROM products
                    WHERE search_vector @@ plainto_tsquery('english', $1)
                    ORDER BY rank DESC
                    LIMIT $2 OFFSET $3
                    """;
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(query, size, page * size))
                    .compose(rowSet -> {
                        List<Product> products = new ArrayList<>();
                        rowSet.forEach(row -> products.add(mapRowToProduct(row)));

                        // Get total count
                        String countSql = """
                            SELECT COUNT(*)
                            FROM products
                            WHERE search_vector @@ plainto_tsquery('english', $1)
                            """;
                        return connection.preparedQuery(countSql)
                            .execute(Tuple.of(query))
                            .map(countRowSet -> {
                                long total = countRowSet.iterator().next().getLong(0);
                                return new SearchResult<>(products, total, page, size);
                            });
                    });
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Dynamic filtering with multiple criteria
    public Mono<List<Product>> filterProducts(SearchCriteria criteria) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                // Build dynamic query based on criteria
                StringBuilder sql = new StringBuilder("SELECT * FROM products WHERE 1=1");
                List<Object> params = new ArrayList<>();
                int paramIndex = 1;

                if (criteria.getCategoryId() != null) {
                    sql.append(" AND category_id = $").append(paramIndex++);
                    params.add(criteria.getCategoryId());
                }

                if (criteria.getMinPrice() != null) {
                    sql.append(" AND price >= $").append(paramIndex++);
                    params.add(criteria.getMinPrice());
                }

                if (criteria.getMaxPrice() != null) {
                    sql.append(" AND price <= $").append(paramIndex++);
                    params.add(criteria.getMaxPrice());
                }

                if (criteria.getActive() != null) {
                    sql.append(" AND active = $").append(paramIndex++);
                    params.add(criteria.getActive());
                }

                if (criteria.getNamePattern() != null) {
                    sql.append(" AND name ILIKE $").append(paramIndex++);
                    params.add("%" + criteria.getNamePattern() + "%");
                }

                sql.append(" ORDER BY ").append(criteria.getSortBy())
                   .append(" ").append(criteria.getSortDirection());
                sql.append(" LIMIT $").append(paramIndex++);
                params.add(criteria.getLimit());

                return connection.preparedQuery(sql.toString())
                    .execute(Tuple.from(params))
                    .map(rowSet -> {
                        List<Product> products = new ArrayList<>();
                        rowSet.forEach(row -> products.add(mapRowToProduct(row)));
                        return products;
                    });
            }).toCompletionStage().toCompletableFuture()
        );
    }

    // Autocomplete suggestions
    public Flux<String> getAutocompleteSuggestions(String prefix, int limit) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> {
                    String sql = """
                        SELECT DISTINCT name
                        FROM products
                        WHERE name ILIKE $1
                        ORDER BY name
                        LIMIT $2
                        """;
                    return connection.preparedQuery(sql)
                        .execute(Tuple.of(prefix + "%", limit))
                        .map(rowSet -> {
                            List<String> suggestions = new ArrayList<>();
                            rowSet.forEach(row -> suggestions.add(row.getString("name")));
                            return suggestions;
                        });
                }).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }

    // Faceted search (aggregations for filters)
    public Mono<SearchFacets> getSearchFacets() {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> {
                String sql = """
                    SELECT
                        c.id as category_id,
                        c.name as category_name,
                        COUNT(p.id) as product_count,
                        MIN(p.price) as min_price,
                        MAX(p.price) as max_price
                    FROM categories c
                    LEFT JOIN products p ON c.id = p.category_id
                    WHERE p.active = true
                    GROUP BY c.id, c.name
                    ORDER BY c.name
                    """;
                return connection.preparedQuery(sql)
                    .execute()
                    .map(rowSet -> {
                        SearchFacets facets = new SearchFacets();
                        rowSet.forEach(row -> facets.addCategoryFacet(
                            row.getString("category_id"),
                            row.getString("category_name"),
                            row.getLong("product_count"),
                            row.getBigDecimal("min_price"),
                            row.getBigDecimal("max_price")
                        ));
                        return facets;
                    });
            }).toCompletionStage().toCompletableFuture()
        );
    }
}
```

**Reactive Controller**:
```java
@RestController
@RequestMapping("/api/search")
public class SearchController {

    @GetMapping("/products")
    public Mono<ResponseEntity<SearchResult<Product>>> searchProducts(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return searchService.searchProducts(q, page, size)
            .map(ResponseEntity::ok);
    }

    @PostMapping("/products/filter")
    public Mono<ResponseEntity<List<Product>>> filterProducts(
            @RequestBody SearchCriteria criteria) {
        return searchService.filterProducts(criteria)
            .map(ResponseEntity::ok);
    }

    @GetMapping("/autocomplete")
    public Flux<String> autocomplete(@RequestParam String prefix) {
        return searchService.getAutocompleteSuggestions(prefix, 10);
    }

    @GetMapping("/facets")
    public Mono<ResponseEntity<SearchFacets>> getFacets() {
        return searchService.getSearchFacets()
            .map(ResponseEntity::ok);
    }
}
```

**Key Points**:
- ✅ Full-text search with PostgreSQL `tsvector` and `tsquery`
- ✅ Dynamic query building based on filter criteria
- ✅ Autocomplete with prefix matching
- ✅ Faceted search for filter aggregations
- ✅ Pagination with total count
- ✅ Ranking results by relevance
- ✅ No need for Elasticsearch for basic search

---

### Category 3: Batch Operations and Data Import

These examples show bulk operations and data processing without messaging.

#### Example 3A: Bulk Data Import (Non-Reactive)

**Purpose**: Efficient batch operations for data import/export

**Operations**:
```java
@Service
public class DataImportService {

    private final DatabaseService databaseService;

    // Batch insert with transaction
    public CompletableFuture<ImportResult> importCustomers(List<Customer> customers) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withTransaction("peegeeq-main", connection -> {
            String sql = "INSERT INTO customers (id, name, email, created_at) VALUES ($1, $2, $3, $4)";

            // Create batch of tuples
            List<Tuple> batch = customers.stream()
                .map(c -> Tuple.of(c.getId(), c.getName(), c.getEmail(), c.getCreatedAt()))
                .collect(Collectors.toList());

            return connection.preparedQuery(sql)
                .executeBatch(batch)
                .map(result -> new ImportResult(customers.size(), 0));
        }).toCompletionStage().toCompletableFuture();
    }

    // Upsert (INSERT ... ON CONFLICT UPDATE)
    public CompletableFuture<Integer> upsertProducts(List<Product> products) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withTransaction("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO products (id, name, category_id, price, updated_at)
                VALUES ($1, $2, $3, $4, $5)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    category_id = EXCLUDED.category_id,
                    price = EXCLUDED.price,
                    updated_at = EXCLUDED.updated_at
                """;

            List<Tuple> batch = products.stream()
                .map(p -> Tuple.of(p.getId(), p.getName(), p.getCategoryId(),
                                  p.getPrice(), Instant.now()))
                .collect(Collectors.toList());

            return connection.preparedQuery(sql)
                .executeBatch(batch)
                .map(result -> products.size());
        }).toCompletionStage().toCompletableFuture();
    }

    // Bulk update with WHERE IN
    public CompletableFuture<Integer> deactivateProducts(List<String> productIds) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withTransaction("peegeeq-main", connection -> {
            // Build dynamic IN clause
            String placeholders = IntStream.range(1, productIds.size() + 1)
                .mapToObj(i -> "$" + i)
                .collect(Collectors.joining(", "));

            String sql = "UPDATE products SET active = false WHERE id IN (" + placeholders + ")";

            return connection.preparedQuery(sql)
                .execute(Tuple.from(productIds))
                .map(result -> result.rowCount());
        }).toCompletionStage().toCompletableFuture();
    }

    // Bulk delete with transaction
    public CompletableFuture<Integer> deleteOrders(List<String> orderIds) {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withTransaction("peegeeq-main", connection -> {
            // Delete order items first (foreign key constraint)
            String deleteItemsSql = "DELETE FROM order_items WHERE order_id = ANY($1)";

            return connection.preparedQuery(deleteItemsSql)
                .execute(Tuple.of(orderIds.toArray(new String[0])))
                .compose(itemsResult -> {
                    // Then delete orders
                    String deleteOrdersSql = "DELETE FROM orders WHERE id = ANY($1)";
                    return connection.preparedQuery(deleteOrdersSql)
                        .execute(Tuple.of(orderIds.toArray(new String[0])))
                        .map(ordersResult -> ordersResult.rowCount());
                });
        }).toCompletionStage().toCompletableFuture();
    }

    // Export data to CSV format
    public CompletableFuture<String> exportCustomersToCSV() {
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return cp.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT id, name, email, created_at FROM customers ORDER BY created_at";

            return connection.preparedQuery(sql)
                .execute()
                .map(rowSet -> {
                    StringBuilder csv = new StringBuilder();
                    csv.append("id,name,email,created_at\n");

                    rowSet.forEach(row -> {
                        csv.append(row.getString("id")).append(",")
                           .append(row.getString("name")).append(",")
                           .append(row.getString("email")).append(",")
                           .append(row.getLocalDateTime("created_at")).append("\n");
                    });

                    return csv.toString();
                });
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Key Points**:
- ✅ Batch operations with `executeBatch()` for performance
- ✅ UPSERT with `ON CONFLICT DO UPDATE`
- ✅ Bulk updates with `WHERE IN` or `ANY()`
- ✅ Cascading deletes with proper transaction handling
- ✅ Data export to various formats
- ✅ All operations use transactions for consistency

---

### Category 4: Complex Business Logic

These examples show multi-step business operations with proper transaction management.

#### Example 4A: Order Fulfillment Workflow (Non-Reactive)

**Purpose**: Multi-step business process with rollback on failure

**Operations**:
```java
@Service
public class OrderFulfillmentService {

    private final DatabaseService databaseService;

    // Complete order fulfillment workflow
    public CompletableFuture<FulfillmentResult> fulfillOrder(String orderId) {
        ConnectionProvider cp = databaseService.getConnectionProvider();

        return cp.withTransaction("peegeeq-main", connection -> {
            // Step 1: Validate order exists and is pending
            String validateSql = "SELECT * FROM orders WHERE id = $1 AND status = 'PENDING' FOR UPDATE";

            return connection.preparedQuery(validateSql)
                .execute(Tuple.of(orderId))
                .compose(orderRowSet -> {
                    if (orderRowSet.size() == 0) {
                        return Future.failedFuture(new IllegalStateException("Order not found or not pending"));
                    }

                    // Step 2: Check inventory for all items
                    String inventorySql = """
                        SELECT oi.product_id, oi.quantity, i.available_quantity
                        FROM order_items oi
                        JOIN inventory i ON oi.product_id = i.product_id
                        WHERE oi.order_id = $1
                        """;

                    return connection.preparedQuery(inventorySql)
                        .execute(Tuple.of(orderId))
                        .compose(inventoryRowSet -> {
                            // Validate sufficient inventory
                            for (Row row : inventoryRowSet) {
                                int required = row.getInteger("quantity");
                                int available = row.getInteger("available_quantity");
                                if (available < required) {
                                    return Future.failedFuture(new IllegalStateException(
                                        "Insufficient inventory for product: " + row.getString("product_id")
                                    ));
                                }
                            }

                            // Step 3: Reserve inventory
                            String reserveSql = """
                                UPDATE inventory i
                                SET available_quantity = available_quantity - oi.quantity,
                                    reserved_quantity = reserved_quantity + oi.quantity
                                FROM order_items oi
                                WHERE i.product_id = oi.product_id AND oi.order_id = $1
                                """;

                            return connection.preparedQuery(reserveSql)
                                .execute(Tuple.of(orderId))
                                .compose(reserveResult -> {
                                    // Step 4: Update order status
                                    String updateOrderSql = """
                                        UPDATE orders
                                        SET status = 'FULFILLED', fulfilled_at = $1
                                        WHERE id = $2
                                        """;

                                    return connection.preparedQuery(updateOrderSql)
                                        .execute(Tuple.of(Instant.now(), orderId))
                                        .compose(updateResult -> {
                                            // Step 5: Create fulfillment record
                                            String fulfillmentSql = """
                                                INSERT INTO fulfillments (id, order_id, fulfilled_at, status)
                                                VALUES ($1, $2, $3, 'COMPLETED')
                                                """;

                                            String fulfillmentId = UUID.randomUUID().toString();
                                            return connection.preparedQuery(fulfillmentSql)
                                                .execute(Tuple.of(fulfillmentId, orderId, Instant.now()))
                                                .map(fulfillmentResult ->
                                                    new FulfillmentResult(orderId, fulfillmentId, "SUCCESS")
                                                );
                                        });
                                });
                        });
                });
        }).toCompletionStage().toCompletableFuture();
    }

    // Cancel order and restore inventory
    public CompletableFuture<Void> cancelOrder(String orderId) {
        ConnectionProvider cp = databaseService.getConnectionProvider();

        return cp.withTransaction("peegeeq-main", connection -> {
            // Step 1: Validate order can be cancelled
            String validateSql = """
                SELECT * FROM orders
                WHERE id = $1 AND status IN ('PENDING', 'FULFILLED')
                FOR UPDATE
                """;

            return connection.preparedQuery(validateSql)
                .execute(Tuple.of(orderId))
                .compose(orderRowSet -> {
                    if (orderRowSet.size() == 0) {
                        return Future.failedFuture(new IllegalStateException("Order cannot be cancelled"));
                    }

                    String status = orderRowSet.iterator().next().getString("status");

                    // Step 2: Restore inventory if order was fulfilled
                    if ("FULFILLED".equals(status)) {
                        String restoreSql = """
                            UPDATE inventory i
                            SET available_quantity = available_quantity + oi.quantity,
                                reserved_quantity = reserved_quantity - oi.quantity
                            FROM order_items oi
                            WHERE i.product_id = oi.product_id AND oi.order_id = $1
                            """;

                        return connection.preparedQuery(restoreSql)
                            .execute(Tuple.of(orderId))
                            .compose(restoreResult -> {
                                // Step 3: Update order status
                                String updateSql = """
                                    UPDATE orders
                                    SET status = 'CANCELLED', cancelled_at = $1
                                    WHERE id = $2
                                    """;
                                return connection.preparedQuery(updateSql)
                                    .execute(Tuple.of(Instant.now(), orderId))
                                    .mapEmpty();
                            });
                    } else {
                        // Just update status for pending orders
                        String updateSql = """
                            UPDATE orders
                            SET status = 'CANCELLED', cancelled_at = $1
                            WHERE id = $2
                            """;
                        return connection.preparedQuery(updateSql)
                            .execute(Tuple.of(Instant.now(), orderId))
                            .mapEmpty();
                    }
                });
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Key Points**:
- ✅ Multi-step business logic in single transaction
- ✅ Validation at each step with proper error handling
- ✅ `FOR UPDATE` for row-level locking
- ✅ Automatic rollback on any failure
- ✅ Complex state transitions
- ✅ Inventory management with reservations
- ✅ All-or-nothing consistency

---

### Summary: When to Use Each Pattern

| Use Case | Pattern | Transaction Type | Example |
|----------|---------|------------------|---------|
| Simple CRUD | `withConnection()` | Auto-commit | Customer management |
| Multi-step updates | `withTransaction()` | Explicit transaction | Order fulfillment |
| Read-only queries | `withConnection()` | No transaction needed | Reports, search |
| Batch operations | `withTransaction()` + `executeBatch()` | Bulk transaction | Data import |
| Complex business logic | `withTransaction()` + composition | Multi-step transaction | Order workflow |
| Reactive operations | Wrap in `Mono`/`Flux` | Same as above | Product catalog |

**Key Takeaway**: PeeGeeQ's `DatabaseService` and `ConnectionProvider` handle **all database operations** - you don't need R2DBC, JPA, or any other data access framework. The outbox pattern is **optional** and only needed when you want transactional messaging.

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

