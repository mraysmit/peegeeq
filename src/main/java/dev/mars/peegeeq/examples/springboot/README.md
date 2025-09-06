# Spring Boot Integration Examples

This directory contains a complete Spring Boot integration example for PeeGeeQ, demonstrating the Transactional Outbox Pattern as described in the **PeeGeeQ-Transactional-Outbox-Patterns-Guide.md**.

## Package Organization

### Current Structure
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

## Implementation Status

✅ **COMPLETE** - Full Spring Boot integration example implemented

## Key Features Demonstrated

This implementation demonstrates all the patterns from the **PeeGeeQ-Transactional-Outbox-Patterns-Guide.md**:

### 1. **Zero Vert.x Exposure**
- Spring Boot developers never interact with Vert.x APIs directly
- All reactive complexity is handled internally by PeeGeeQ
- Standard Spring Boot patterns and annotations work normally

### 2. **Three Reactive Approaches**
- **Basic Reactive Operations** (`send()`) - Non-blocking operations without transaction management
- **Transaction Participation** (`sendInTransaction()`) - Participate in existing transactions
- **Automatic Transaction Management** (`sendWithTransaction()`) - Automatic transaction creation and management

### 3. **Complete Spring Boot Integration**
- Spring Boot configuration with `@ConfigurationProperties`
- Automatic bean creation and lifecycle management
- Production-ready monitoring with Actuator endpoints
- Environment-specific configuration profiles

### 4. **RESTful API Design**
- Reactive REST controllers with `CompletableFuture`
- Comprehensive error handling and validation
- Proper HTTP status codes and response formats

## Running the Example

See the detailed [README](README.md) in this package for complete instructions on:
- Database setup and configuration
- Running with Maven or as a JAR
- API endpoint documentation
- Testing examples

## Benefits of This Implementation

1. **Production Ready**: Complete configuration, monitoring, and error handling
2. **Developer Friendly**: Familiar Spring Boot patterns and zero learning curve
3. **Reactive Benefits**: Non-blocking operations with transactional consistency
4. **Easy Integration**: Drop-in replacement for traditional Spring Boot messaging
5. **Comprehensive**: Demonstrates all three reactive approaches from the guide

## Related Examples

- **Main Examples**: `src/main/java/dev/mars/peegeeq/examples/*.java`
- **Integration Test**: `src/test/java/dev/mars/peegeeq/examples/SpringBootOutboxIntegrationTest.java`
- **Configuration**: `src/main/resources/application-springboot.yml`

## Integration with Main Examples

This Spring Boot example complements the main PeeGeeQ examples:

- **Main Examples**: Core PeeGeeQ patterns and usage
- **Spring Boot Example**: Framework-specific integration patterns
- **Integration Tests**: Comprehensive testing with TestContainers
