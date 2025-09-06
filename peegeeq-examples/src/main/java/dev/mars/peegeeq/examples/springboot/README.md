# Spring Boot Integration for PeeGeeQ Outbox Pattern

This package contains a complete Spring Boot application demonstrating the PeeGeeQ Transactional Outbox Pattern integration as described in the **PeeGeeQ-Transactional-Outbox-Patterns-Guide.md**.

## Overview

This Spring Boot application demonstrates:

- **Zero Vert.x Exposure** - Spring Boot developers never interact with Vert.x APIs directly
- **Standard Spring Boot Patterns** - Familiar annotations and development patterns
- **Reactive Operations** - CompletableFuture-based reactive programming
- **Transactional Consistency** - ACID guarantees with automatic rollback
- **Production-Ready Configuration** - Comprehensive configuration and monitoring

## Architecture

```
src/main/java/dev/mars/peegeeq/examples/springboot/
├── SpringBootOutboxApplication.java     # Main Spring Boot application
├── config/
│   ├── PeeGeeQConfig.java              # PeeGeeQ Spring configuration
│   └── PeeGeeQProperties.java          # Configuration properties
├── controller/
│   └── OrderController.java            # REST controllers
├── service/
│   └── OrderService.java               # Business services with outbox pattern
├── model/
│   ├── Order.java                      # Domain models
│   ├── OrderItem.java                  # Order item model
│   ├── CreateOrderRequest.java         # Request DTOs
│   └── CreateOrderResponse.java        # Response DTOs
├── repository/
│   └── OrderRepository.java            # Data repositories
└── events/
    ├── OrderEvent.java                 # Base event class
    ├── OrderCreatedEvent.java          # Order creation events
    ├── OrderValidatedEvent.java        # Order validation events
    ├── InventoryReservedEvent.java     # Inventory reservation events
    └── PaymentEvent.java               # Payment-related events
```

## Key Features Demonstrated

### 1. Three Reactive Approaches

The application demonstrates all three reactive approaches from the guide:

1. **Basic Reactive Operations** (`send()`)
2. **Transaction Participation** (`sendInTransaction()`)
3. **Automatic Transaction Management** (`sendWithTransaction()`)

### 2. Complete Transaction Management

```java
@Service
@Transactional
public class OrderService {
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        return orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Business logic - all in same transaction
            Order order = new Order(request);
            Order savedOrder = orderRepository.save(order);
            
            // Multiple events in same transaction
            return CompletableFuture.allOf(
                orderEventProducer.sendWithTransaction(
                    new OrderValidatedEvent(savedOrder.getId()),
                    TransactionPropagation.CONTEXT
                ),
                orderEventProducer.sendWithTransaction(
                    new InventoryReservedEvent(savedOrder.getId(), request.getItems()),
                    TransactionPropagation.CONTEXT
                )
            ).thenApply(ignored -> savedOrder.getId());
        });
    }
}
```

### 3. RESTful API Design

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    @PostMapping
    public CompletableFuture<ResponseEntity<CreateOrderResponse>> createOrder(
            @RequestBody CreateOrderRequest request) {
        
        return orderService.createOrder(request)
            .thenApply(orderId -> ResponseEntity.ok(new CreateOrderResponse(orderId)))
            .exceptionally(error -> ResponseEntity.status(500)
                .body(new CreateOrderResponse(null, "Order creation failed")));
    }
}
```

## Running the Application

### Prerequisites

1. **Java 17+** - Required for Spring Boot 3.x
2. **PostgreSQL** - Database server running and accessible
3. **Maven** - For building and running

### Database Setup

Create a PostgreSQL database and user:

```sql
-- Create database and user
CREATE DATABASE peegeeq_example;
CREATE USER peegeeq_user WITH PASSWORD 'peegeeq_password';
GRANT ALL PRIVILEGES ON DATABASE peegeeq_example TO peegeeq_user;
```

### Configuration

The application uses `application-springboot.yml` for configuration. Key settings:

```yaml
peegeeq:
  profile: production
  database:
    host: ${DB_HOST:localhost}
    port: ${DB_PORT:5432}
    name: ${DB_NAME:peegeeq_example}
    username: ${DB_USERNAME:peegeeq_user}
    password: ${DB_PASSWORD:peegeeq_password}
```

### Running with Maven

```bash
# From the peegeeq-examples directory
mvn spring-boot:run -Dspring-boot.run.main-class="dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication" -Dspring-boot.run.profiles=springboot

# Or with environment variables
DB_HOST=localhost DB_PORT=5432 DB_NAME=peegeeq_example DB_USERNAME=peegeeq_user DB_PASSWORD=peegeeq_password \
mvn spring-boot:run -Dspring-boot.run.main-class="dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication" -Dspring-boot.run.profiles=springboot
```

### Running as JAR

```bash
# Build the JAR
mvn clean package -pl peegeeq-examples

# Run the JAR
java -jar peegeeq-examples/target/peegeeq-examples-*.jar \
  --spring.profiles.active=springboot \
  --peegeeq.database.host=localhost \
  --peegeeq.database.port=5432 \
  --peegeeq.database.name=peegeeq_example \
  --peegeeq.database.username=peegeeq_user \
  --peegeeq.database.password=peegeeq_password
```

## API Endpoints

Once running, the application provides these endpoints:

### Order Management

- **POST /api/orders** - Create a new order
- **POST /api/orders/{id}/validate** - Validate an existing order
- **GET /api/orders/health** - Health check

### Monitoring

- **GET /actuator/health** - Spring Boot health check
- **GET /actuator/metrics** - Application metrics
- **GET /actuator/prometheus** - Prometheus metrics

## Testing the API

### Transactional Rollback Demonstration

The application includes comprehensive endpoints to **prove transactional consistency** between database operations and outbox events. Use the provided test scripts to demonstrate rollback scenarios:

#### Automated Test Suite

```bash
# Linux/macOS - Comprehensive rollback testing
./peegeeq-examples/test-transactional-rollback.sh

# Windows - Comprehensive rollback testing
peegeeq-examples\test-transactional-rollback.bat
```

These scripts test all rollback scenarios and prove that:
- ✅ **Database operations and outbox events are synchronized**
- ✅ **When business logic fails, both database and outbox roll back**
- ✅ **When database operations fail, outbox events also roll back**
- ✅ **Successful operations commit both database and outbox together**

#### Manual Testing

### 1. Successful Transaction with Multiple Events

```bash
curl -X POST http://localhost:8080/api/orders/with-multiple-events \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-SUCCESS",
    "amount": 99.98,
    "items": [
      {
        "productId": "PROD-001",
        "name": "Premium Widget",
        "quantity": 2,
        "price": 49.99
      }
    ]
  }'
```

**Expected**: HTTP 200 - Order record + 3 outbox events committed together

### 2. Business Validation Failure (Amount Too High)

```bash
curl -X POST http://localhost:8080/api/orders/with-validation \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-HIGH-AMOUNT",
    "amount": 15000.00,
    "items": [
      {
        "productId": "PROD-EXPENSIVE",
        "name": "Expensive Item",
        "quantity": 1,
        "price": 15000.00
      }
    ]
  }'
```

**Expected**: HTTP 500 - Both order record AND outbox event rolled back

### 3. Business Validation Failure (Invalid Customer)

```bash
curl -X POST http://localhost:8080/api/orders/with-validation \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "INVALID_CUSTOMER",
    "amount": 50.00,
    "items": [
      {
        "productId": "PROD-002",
        "name": "Standard Widget",
        "quantity": 1,
        "price": 50.00
      }
    ]
  }'
```

**Expected**: HTTP 500 - Complete transaction rollback

### 4. Database Constraint Violation

```bash
curl -X POST http://localhost:8080/api/orders/with-constraints \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "DUPLICATE_ORDER",
    "amount": 75.50,
    "items": [
      {
        "productId": "PROD-003",
        "name": "Duplicate Test Item",
        "quantity": 1,
        "price": 75.50
      }
    ]
  }'
```

**Expected**: HTTP 500 - Database failure triggers outbox rollback

### 5. Standard Order Creation

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-12345",
    "amount": 99.98,
    "items": [
      {
        "productId": "PROD-001",
        "name": "Premium Widget",
        "quantity": 2,
        "price": 49.99
      }
    ]
  }'
```

**Expected**: HTTP 200 - Standard successful order creation

### 6. Order Validation

```bash
curl -X POST http://localhost:8080/api/orders/ORDER-12345-67890/validate
```

**Expected**: HTTP 200 - Order validation with outbox event

## Key Benefits

1. **Zero Vert.x Exposure** - Developers work with familiar Spring Boot patterns
2. **Reactive Benefits** - Non-blocking operations with CompletableFuture
3. **Transactional Consistency** - ACID guarantees across database and message operations
4. **Production Ready** - Comprehensive monitoring, health checks, and error handling
5. **Easy Integration** - Drop-in replacement for traditional Spring Boot messaging

## Related Documentation

- [PeeGeeQ Transactional Outbox Patterns Guide](../../../../../docs/PeeGeeQ-Transactional-Outbox-Patterns-Guide.md)
- [Main Examples README](../../../README.md)
- [Integration Test](../../../../test/java/dev/mars/peegeeq/examples/SpringBootOutboxIntegrationTest.java)
