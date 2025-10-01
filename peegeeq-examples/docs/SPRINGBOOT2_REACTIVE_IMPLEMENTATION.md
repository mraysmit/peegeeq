# Spring Boot 2 Reactive Implementation

## Overview

The `springboot2` example is a fully reactive implementation of the PeeGeeQ transactional outbox pattern using Spring Boot 3.3.5 with WebFlux and R2DBC. This implementation is an exact copy of the `springboot` example but exclusively uses reactive Spring patterns.

## Key Technologies

- **Spring Boot 3.3.5**: Application framework
- **Spring WebFlux**: Reactive web framework (Netty-based)
- **Spring Data R2DBC**: Reactive database access
- **R2DBC PostgreSQL 1.0.7.RELEASE**: Reactive PostgreSQL driver
- **Project Reactor**: Reactive programming library (Mono/Flux)
- **PeeGeeQ**: Transactional outbox pattern with Vert.x 5.0.4
- **TestContainers**: Integration testing with PostgreSQL

## Architecture

### Reactive Stack

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring WebFlux (Netty)                   │
│                  Reactive REST Controllers                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                          │
│              Mono/Flux Reactive Operations                   │
└─────────────────────────────────────────────────────────────┘
                              │
                ┌─────────────┴─────────────┐
                ▼                           ▼
┌───────────────────────────┐   ┌───────────────────────────┐
│   Spring Data R2DBC       │   │  PeeGeeQ Outbox (Vert.x)  │
│   Reactive Repositories   │   │  ReactiveOutboxAdapter    │
└───────────────────────────┘   └───────────────────────────┘
                │                           │
                └─────────────┬─────────────┘
                              ▼
                    ┌──────────────────┐
                    │   PostgreSQL     │
                    │   (TestContainer)│
                    └──────────────────┘
```

### Package Structure

```
dev.mars.peegeeq.examples.springboot2/
├── SpringBootReactiveOutboxApplication.java    # Main application
├── adapter/
│   └── ReactiveOutboxAdapter.java              # CompletableFuture → Mono/Flux adapter
├── config/
│   ├── PeeGeeQReactiveConfig.java              # PeeGeeQ configuration
│   └── R2dbcConfig.java                        # R2DBC configuration
├── controller/
│   └── OrderController.java                    # Reactive REST endpoints
├── events/
│   ├── OrderEvent.java                         # Base event class
│   ├── OrderCreatedEvent.java                  # Order creation event
│   ├── OrderValidatedEvent.java                # Order validation event
│   └── InventoryReservedEvent.java             # Inventory reservation event
├── model/
│   ├── Order.java                              # R2DBC entity
│   └── OrderItem.java                          # R2DBC entity
├── repository/
│   ├── OrderRepository.java                    # Reactive repository
│   └── OrderItemRepository.java                # Reactive repository
└── service/
    └── OrderService.java                       # Reactive business logic
```

## Key Components

### 1. ReactiveOutboxAdapter

Bridges PeeGeeQ's `CompletableFuture` API with Spring's reactive types:

```java
public Mono<Void> toMonoVoid(CompletableFuture<?> future)
public <T> Mono<T> toMono(CompletableFuture<T> future)
public <T> Flux<T> toFlux(CompletableFuture<List<T>> future)
```

### 2. OrderController (Reactive)

All endpoints return `Mono` or `Flux`:

- `POST /api/orders` - Create order
- `POST /api/orders/with-validation` - Create with business validation
- `POST /api/orders/with-constraints` - Create with database constraints
- `POST /api/orders/with-multiple-events` - Create with multiple events
- `GET /api/orders/{id}` - Get order by ID
- `GET /api/orders/customer/{customerId}` - Get orders by customer
- `GET /api/orders/stream` - Stream recent orders (SSE)
- `POST /api/orders/{id}/validate` - Validate order
- `GET /api/orders/health` - Health check

### 3. OrderService (Reactive)

All methods return `Mono` or `Flux`:

```java
public Mono<String> createOrder(CreateOrderRequest request)
public Mono<String> createOrderWithValidation(CreateOrderRequest request)
public Mono<String> createOrderWithDatabaseConstraints(CreateOrderRequest request)
public Mono<String> createOrderWithMultipleEvents(CreateOrderRequest request)
public Mono<Order> findById(String id)
public Flux<Order> findByCustomerId(String customerId)
public Flux<Order> streamRecentOrders()
public Mono<Void> validateOrder(String orderId)
```

### 4. Event Classes

All event classes are in the `springboot2.events` package:

- **OrderEvent**: Abstract base class with Jackson polymorphic serialization
- **OrderCreatedEvent**: Published when order is created
- **OrderValidatedEvent**: Published when order is validated
- **InventoryReservedEvent**: Published when inventory is reserved

## Transactional Outbox Pattern

### How It Works

1. **Business Operation**: Service method performs database operation using R2DBC
2. **Event Publishing**: Service publishes event to PeeGeeQ outbox using ReactiveOutboxAdapter
3. **Atomic Commit**: Both operations commit together in the same transaction
4. **Rollback**: If either operation fails, both roll back together

### Example: Create Order with Validation

```java
public Mono<String> createOrderWithValidation(CreateOrderRequest request) {
    return adapter.toMonoVoid(
        orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT
        )
    )
    .then(Mono.defer(() -> {
        // Business validation
        if (request.getAmount().compareTo(new BigDecimal("10000")) > 0) {
            return Mono.error(new RuntimeException("Amount exceeds limit"));
        }
        
        // Create and save order
        Order order = new Order(request);
        return orderRepository.save(order);
    }))
    .flatMap(savedOrder -> saveOrderItems(savedOrder).thenReturn(savedOrder.getId()))
    .doOnSuccess(orderId -> log.info("✅ Order {} created and committed", orderId))
    .doOnError(error -> log.error("❌ Transaction rolled back: {}", error.getMessage()));
}
```

## SCRAM Version Conflict Resolution

The implementation successfully resolves SCRAM authentication library conflicts between R2DBC and Vert.x by using MD5 authentication in TestContainers. See [SCRAM_VERSION_CONFLICT_RESOLUTION.md](SCRAM_VERSION_CONFLICT_RESOLUTION.md) for details.

## Testing

All tests pass successfully:

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Test coverage includes:
- Application context loading
- Reactive components configuration
- Database connectivity (R2DBC + Vert.x)
- Application lifecycle

## Differences from Non-Reactive Example

| Aspect | springboot (Non-Reactive) | springboot2 (Reactive) |
|--------|---------------------------|------------------------|
| Web Framework | Spring MVC (Tomcat) | Spring WebFlux (Netty) |
| Database Access | JDBC / JPA | R2DBC |
| Return Types | `CompletableFuture<T>` | `Mono<T>` / `Flux<T>` |
| Threading Model | Thread-per-request | Event loop (non-blocking) |
| Repositories | JpaRepository | ReactiveCrudRepository |
| Transaction Management | @Transactional (JDBC) | R2DBC transactions |
| Streaming | Not supported | Server-Sent Events (SSE) |

## Running the Example

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker (for TestContainers)

### Run Tests

```bash
mvn test -pl peegeeq-examples -Dtest=SpringBootReactiveOutboxApplicationTest
```

### Run Application

```bash
mvn spring-boot:run -pl peegeeq-examples \
  -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springboot2.SpringBootReactiveOutboxApplication
```

## Configuration

### Application Properties

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/peegeeq_reactive
    username: postgres
    password: postgres
  
  webflux:
    base-path: /api

peegeeq:
  profile: production
  database:
    host: localhost
    port: 5432
    database: peegeeq_reactive
```

## Best Practices

1. **Always use ReactiveOutboxAdapter** to convert PeeGeeQ's CompletableFuture to Mono/Flux
2. **Use TransactionPropagation.CONTEXT** for transactional outbox operations
3. **Handle errors with onErrorResume/onErrorMap** for proper reactive error handling
4. **Use doOnSuccess/doOnError** for logging without affecting the reactive chain
5. **Avoid blocking operations** in reactive chains (no `.block()` calls)
6. **Use Mono.defer()** for lazy evaluation of operations
7. **Compose operations with flatMap/map/then** instead of nested callbacks

## Known Limitations

1. **SCRAM Authentication**: Must use MD5 authentication in TestContainers due to version conflicts
2. **Transaction Coordination**: R2DBC and Vert.x use separate connection pools (no XA transactions)
3. **Event Ordering**: Events are published in order but may be processed out of order by consumers

## Future Enhancements

- [ ] Add reactive consumer examples
- [ ] Implement reactive saga pattern
- [ ] Add reactive metrics and monitoring
- [ ] Implement backpressure handling
- [ ] Add reactive circuit breaker integration
- [ ] Implement reactive retry policies

## References

- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Spring Data R2DBC Documentation](https://docs.spring.io/spring-data/r2dbc/reference/)
- [Project Reactor Documentation](https://projectreactor.io/docs)
- [PeeGeeQ Documentation](../README.md)
- [SCRAM Conflict Resolution](SCRAM_VERSION_CONFLICT_RESOLUTION.md)

