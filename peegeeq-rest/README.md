# PeeGeeQ REST API

A Vert.x-based REST API for PeeGeeQ database setup and management operations. This module provides HTTP endpoints for creating and managing database setups, including template-based database creation, queue management, and event store configuration.

## Features

- **Non-Spring Architecture**: Built with Vert.x to maintain consistency with PeeGeeQ's existing architecture
- **Database Setup Management**: Create databases from PostgreSQL templates
- **Queue Operations**: Send messages and retrieve queue statistics
- **Event Store Operations**: Store and query bi-temporal events
- **Health Checks**: Built-in health and metrics endpoints
- **CORS Support**: Cross-origin resource sharing for web applications

## API Endpoints

### Database Setup

- `POST /api/v1/database-setup/create` - Create a complete database setup
- `DELETE /api/v1/database-setup/{setupId}` - Destroy a database setup
- `GET /api/v1/database-setup/{setupId}/status` - Get setup status
- `POST /api/v1/database-setup/{setupId}/queues` - Add queue to setup
- `POST /api/v1/database-setup/{setupId}/eventstores` - Add event store to setup

### Queue Operations

- `POST /api/v1/queues/{setupId}/{queueName}/messages` - Send message to queue
- `GET /api/v1/queues/{setupId}/{queueName}/stats` - Get queue statistics

### Event Store Operations

- `POST /api/v1/eventstores/{setupId}/{eventStoreName}/events` - Store event
- `GET /api/v1/eventstores/{setupId}/{eventStoreName}/events` - Query events
- `GET /api/v1/eventstores/{setupId}/{eventStoreName}/events/{eventId}` - Get specific event
- `GET /api/v1/eventstores/{setupId}/{eventStoreName}/stats` - Get event store statistics

### System Endpoints

- `GET /health` - Health check
- `GET /metrics` - Metrics endpoint

## Usage

### Starting the Server

```java
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import io.vertx.core.Vertx;

// Start with default port (8080)
Vertx vertx = Vertx.vertx();
vertx.deployVerticle(new PeeGeeQRestServer());

// Start with custom port
vertx.deployVerticle(new PeeGeeQRestServer(9090));
```

### Command Line

```bash
# Run with default port
java -cp target/classes:target/lib/* dev.mars.peegeeq.rest.PeeGeeQRestServer

# Run with custom port
java -cp target/classes:target/lib/* dev.mars.peegeeq.rest.PeeGeeQRestServer 9090
```

### Example Requests

#### Create Database Setup

```bash
curl -X POST http://localhost:8080/api/v1/database-setup/create \
  -H "Content-Type: application/json" \
  -d '{
    "setupId": "my-setup",
    "databaseConfig": {
      "host": "localhost",
      "port": 5432,
      "databaseName": "my_app_db",
      "username": "postgres",
      "password": "password",
      "schema": "public"
    },
    "queues": [
      {
        "queueName": "orders",
        "maxRetries": 3,
        "visibilityTimeoutSeconds": 30
      }
    ],
    "eventStores": [
      {
        "eventStoreName": "order-events",
        "tableName": "order_events",
        "biTemporalEnabled": true
      }
    ]
  }'
```

#### Send Message to Queue

```bash
curl -X POST http://localhost:8080/api/v1/queues/my-setup/orders/messages \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "orderId": "12345",
      "customerId": "67890",
      "amount": 99.99
    },
    "priority": 5
  }'
```

#### Store Event

```bash
curl -X POST http://localhost:8080/api/v1/eventstores/my-setup/order-events/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "OrderCreated",
    "eventData": {
      "orderId": "12345",
      "customerId": "67890",
      "amount": 99.99
    },
    "correlationId": "correlation-123"
  }'
```

## Configuration

The REST server uses the existing PeeGeeQ configuration system. No additional configuration is required beyond what's already set up for PeeGeeQ.

## Testing

Run the integration tests:

```bash
mvn test
```

The tests use Vert.x test framework and verify:
- Server startup and shutdown
- Endpoint availability
- Request/response handling
- CORS functionality

## Architecture

The REST API is built using:

- **Vert.x**: Non-blocking HTTP server and routing
- **Jackson**: JSON serialization/deserialization
- **Existing PeeGeeQ Services**: Database setup and management
- **Micrometer**: Metrics collection

This maintains consistency with PeeGeeQ's existing architecture without introducing Spring dependencies.

## Testing

The REST API includes comprehensive test coverage using TestContainers for real PostgreSQL integration testing:

### Test Classes

1. **PeeGeeQRestServerTest** - Complete REST server integration tests
2. **DatabaseSetupServiceIntegrationTest** - Database setup functionality tests
3. **SqlTemplateProcessorTest** - SQL template processing tests
4. **DatabaseTemplateManagerTest** - PostgreSQL template database tests
5. **PeeGeeQRestPerformanceTest** - Performance and load tests

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=PeeGeeQRestServerTest

# Run test suite
mvn test -Dtest=PeeGeeQRestTestSuite

# Run performance tests
mvn test -Dtest=PeeGeeQRestPerformanceTest

# Run with debug logging
mvn test -Dpeegeeq.logging.level=DEBUG
```

### Test Coverage

- **API Endpoints**: All REST endpoints tested end-to-end
- **Database Operations**: Template creation, schema migration, table creation
- **Error Handling**: Invalid requests, database errors, network failures
- **Performance**: Concurrent operations, high-volume throughput
- **Integration**: Real PostgreSQL database operations via TestContainers

### Prerequisites for Testing

- Docker (for TestContainers)
- Java 21+
- Maven 3.8+

## Dependencies

- Vert.x Core and Web
- PeeGeeQ API, DB, and Bi-temporal modules
- Jackson for JSON processing
- SLF4J for logging
- Micrometer for metrics
