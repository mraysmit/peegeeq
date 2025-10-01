# Spring Boot Reactive Integration for PeeGeeQ Outbox Pattern

This package contains a complete Spring Boot **reactive** application demonstrating the PeeGeeQ Transactional Outbox Pattern integration using **Spring WebFlux** and **R2DBC**.

## Overview

This Spring Boot reactive application demonstrates:

- **Fully Reactive Stack** - Spring WebFlux with Netty server (non-blocking)
- **Reactive Database Access** - Spring Data R2DBC with PostgreSQL
- **Project Reactor** - Mono and Flux for reactive streams
- **Zero Vert.x Exposure** - Spring Boot developers never interact with Vert.x APIs directly
- **Transactional Consistency** - ACID guarantees with automatic rollback
- **Production-Ready Configuration** - Comprehensive configuration and monitoring

## Technology Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Web Framework | Spring WebFlux | Reactive, non-blocking HTTP |
| Server | Netty | Event-loop based server |
| Database Access | Spring Data R2DBC | Reactive database operations |
| Database Driver | R2DBC PostgreSQL | Reactive PostgreSQL driver |
| Reactive Streams | Project Reactor | Mono/Flux reactive types |
| Messaging | PeeGeeQ Outbox | Transactional outbox pattern |
| Monitoring | Spring Boot Actuator | Health checks, metrics |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  Spring Boot Reactive App                    │
├─────────────────────────────────────────────────────────────┤
│  OrderController (WebFlux)                                   │
│    ↓ Mono/Flux                                              │
│  OrderService (Reactive)                                     │
│    ↓ Mono/Flux              ↓ Mono (via adapter)           │
│  OrderRepository (R2DBC)    OutboxProducer (PeeGeeQ)        │
│    ↓                         ↓                               │
│  PostgreSQL (R2DBC)         PostgreSQL (Vert.x)             │
└─────────────────────────────────────────────────────────────┘
```

## Project Structure

```
springboot2/
├── SpringBootReactiveOutboxApplication.java  # Main application
├── adapter/
│   └── ReactiveOutboxAdapter.java           # CompletableFuture → Mono/Flux
├── config/
│   ├── PeeGeeQReactiveConfig.java          # PeeGeeQ configuration
│   ├── PeeGeeQProperties.java              # Configuration properties
│   └── R2dbcConfig.java                    # R2DBC configuration
├── controller/
│   └── OrderController.java                # WebFlux REST endpoints
├── service/
│   └── OrderService.java                   # Reactive business logic
├── repository/
│   ├── OrderRepository.java                # R2DBC repository
│   └── OrderItemRepository.java            # R2DBC repository
└── model/
    ├── Order.java                          # Domain model with R2DBC annotations
    └── OrderItem.java                      # Domain model with R2DBC annotations
```

## Key Differences from Traditional Spring Boot (springboot)

| Aspect | springboot (Traditional) | springboot2 (Reactive) |
|--------|-------------------------|------------------------|
| Web Framework | Spring MVC (Servlet) | Spring WebFlux (Reactive) |
| Server | Tomcat (thread-per-request) | Netty (event-loop) |
| Database Access | In-memory Map | R2DBC PostgreSQL |
| Return Types | `CompletableFuture<T>` | `Mono<T>` / `Flux<T>` |
| Repository | Custom `@Repository` | `ReactiveCrudRepository` |
| Testing | MockMvc | WebTestClient |
| Thread Model | Thread pool | Event loop |
| Backpressure | No | Yes (native) |
| Server Port | 8080 | 8081 |

## Running the Application

### Prerequisites

- Java 21+
- PostgreSQL 15+
- Maven 3.8+

### Database Setup

```sql
CREATE DATABASE peegeeq_reactive;
CREATE USER peegeeq_user WITH PASSWORD 'peegeeq_password';
GRANT ALL PRIVILEGES ON DATABASE peegeeq_reactive TO peegeeq_user;
```

The schema will be automatically initialized from `schema-springboot2.sql`.

### Run with Maven

```bash
# Run with default profile (production)
mvn spring-boot:run -Dspring-boot.run.profiles=springboot2

# Run with development profile
mvn spring-boot:run -Dspring-boot.run.profiles=springboot2,development

# Run with custom database
mvn spring-boot:run \
  -Dspring-boot.run.profiles=springboot2 \
  -Dspring-boot.run.arguments="--DB_HOST=localhost --DB_PORT=5432 --DB_NAME=mydb"
```

### Run as JAR

```bash
# Build
mvn clean package

# Run
java -jar target/peegeeq-examples-1.0-SNAPSHOT.jar \
  --spring.profiles.active=springboot2 \
  --DB_HOST=localhost \
  --DB_PORT=5432 \
  --DB_NAME=peegeeq_reactive
```

## API Endpoints

### Create Order
```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-001",
    "amount": 299.99,
    "items": [
      {
        "productId": "PROD-001",
        "name": "Premium Widget",
        "quantity": 2,
        "price": 149.99
      }
    ]
  }'
```

### Create Order with Validation
```bash
curl -X POST http://localhost:8081/api/orders/with-validation \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-002",
    "amount": 99.99,
    "items": [...]
  }'
```

### Get Order by ID
```bash
curl http://localhost:8081/api/orders/{orderId}
```

### Get Orders by Customer
```bash
curl http://localhost:8081/api/orders/customer/CUST-001
```

### Stream Recent Orders (SSE)
```bash
curl -N http://localhost:8081/api/orders/stream
```

### Validate Order
```bash
curl -X POST http://localhost:8081/api/orders/{orderId}/validate
```

### Health Check
```bash
curl http://localhost:8081/api/orders/health
curl http://localhost:8081/actuator/health
```

## Reactive Patterns Demonstrated

### 1. Mono for Single Values
```java
public Mono<String> createOrder(CreateOrderRequest request) {
    return adapter.toMonoVoid(outboxProducer.sendWithTransaction(...))
        .then(orderRepository.save(order))
        .map(Order::getId);
}
```

### 2. Flux for Multiple Values
```java
public Flux<Order> findByCustomerId(String customerId) {
    return orderRepository.findByCustomerId(customerId)
        .flatMap(order -> loadItems(order));
}
```

### 3. Server-Sent Events (SSE)
```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<Order> streamRecentOrders() {
    return orderService.streamRecentOrders(Instant.now().minus(Duration.ofHours(1)));
}
```

### 4. Error Handling
```java
return orderService.createOrder(request)
    .map(orderId -> ResponseEntity.ok(new CreateOrderResponse(orderId)))
    .onErrorResume(error -> 
        Mono.just(ResponseEntity.status(500).body(errorResponse))
    );
```

### 5. Reactive Transactions
```java
return adapter.toMonoVoid(
    orderEventProducer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
)
.then(orderRepository.save(order))
.flatMap(saved -> publishAdditionalEvents(saved));
```

## Configuration

### Application Properties

Key configuration in `application-springboot2.yml`:

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/peegeeq_reactive
    pool:
      initial-size: 5
      max-size: 20

server:
  port: 8081

peegeeq:
  database:
    host: localhost
    port: 5432
    name: peegeeq_reactive
```

### Environment Variables

- `DB_HOST` - Database host (default: localhost)
- `DB_PORT` - Database port (default: 5432)
- `DB_NAME` - Database name (default: peegeeq_reactive)
- `DB_USERNAME` - Database username (default: peegeeq_user)
- `DB_PASSWORD` - Database password
- `SERVER_PORT` - Server port (default: 8081)

## Testing

Tests use:
- `@SpringBootTest` with `WebEnvironment.RANDOM_PORT`
- `WebTestClient` for reactive endpoint testing
- `StepVerifier` for reactive stream testing
- TestContainers for PostgreSQL

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=SpringBootReactiveOutboxApplicationTest
```

## Performance Characteristics

### Reactive Advantages
- **Non-blocking I/O** - Better resource utilization
- **Backpressure** - Automatic flow control
- **Scalability** - Handle more concurrent requests with fewer threads
- **Streaming** - Efficient handling of large datasets

### When to Use Reactive
- High concurrency requirements
- I/O-bound operations
- Streaming data
- Microservices with many external calls

### When to Use Traditional (springboot)
- CPU-bound operations
- Simple CRUD applications
- Team unfamiliar with reactive programming
- Blocking libraries required

## Monitoring

### Actuator Endpoints
- `/actuator/health` - Application health
- `/actuator/metrics` - Application metrics
- `/actuator/prometheus` - Prometheus metrics

### Metrics Available
- HTTP request metrics
- R2DBC connection pool metrics
- PeeGeeQ outbox metrics
- JVM metrics

## Troubleshooting

### Common Issues

**Issue**: R2DBC connection failures
```
Solution: Check database is running and credentials are correct
```

**Issue**: Blocking calls in reactive chain
```
Solution: Use Mono.fromCallable(() -> blockingCall()).subscribeOn(Schedulers.boundedElastic())
```

**Issue**: Backpressure errors
```
Solution: Add .onBackpressureBuffer() or .onBackpressureDrop() operators
```

## References

- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Spring Data R2DBC](https://spring.io/projects/spring-data-r2dbc)
- [Project Reactor](https://projectreactor.io/)
- [R2DBC Specification](https://r2dbc.io/)
- PeeGeeQ Transactional Outbox Patterns Guide

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0

