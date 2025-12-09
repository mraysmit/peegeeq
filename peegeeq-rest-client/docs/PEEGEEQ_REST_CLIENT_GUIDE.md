# PeeGeeQ Java REST Client Guide

**Last Updated:** 2025-12-09

This document provides comprehensive documentation for the `peegeeq-client` module, a Java REST client for programmatic access to the PeeGeeQ messaging system.

## 1. Overview

The `peegeeq-rest-client` module provides a non-blocking, reactive Java client for the PeeGeeQ REST API. Built on Vert.x WebClient, it offers type-safe access to all PeeGeeQ operations with proper error handling and connection pooling.

**Key Features:**
- Non-blocking async operations using Vert.x `Future<T>`
- Type-safe API using DTOs from `peegeeq-api`
- Configurable connection pooling, timeouts, and retries
- SSL/TLS support with certificate validation options
- Comprehensive error handling with typed exceptions

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT APPLICATIONS                              │
│                    (Your Java/Vert.x application)                        │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ uses
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          peegeeq-rest-client                                  │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │ PeeGeeQClient (interface)                                        │    │
│  │   └── PeeGeeQRestClient (implementation)                         │    │
│  │         └── Uses: Vert.x WebClient for HTTP                      │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │ DTOs: MessageRequest, AppendEventRequest, CorrectionRequest,    │    │
│  │       QueueStats, ConsumerGroupInfo, EventQueryResult, etc.     │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │ Exceptions: PeeGeeQApiException, PeeGeeQNetworkException,       │    │
│  │             PeeGeeQClientException                               │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ HTTP/REST
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           peegeeq-rest                                   │
│                      (PeeGeeQ REST Server)                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Module Dependencies

| Dependency | Purpose |
|------------|---------|
| `peegeeq-api` | Core DTOs and types (`BiTemporalEvent`, `DatabaseSetupRequest`, etc.) |
| `vertx-web-client` | HTTP client for REST API calls |
| `vertx-core` | Vert.x runtime and `Future<T>` support |
| `jackson-databind` | JSON serialization/deserialization |

### 2.2 Dependency Rules

| Allowed Dependencies | Forbidden Dependencies |
|---------------------|------------------------|
| `peegeeq-api` | `peegeeq-db`, `peegeeq-native`, `peegeeq-outbox`, `peegeeq-bitemporal`, `peegeeq-runtime`, `peegeeq-rest` |

The client communicates with PeeGeeQ exclusively via HTTP REST. It has no direct access to database or implementation modules.

## 3. Quick Start

### 3.1 Maven Dependency

```xml
<dependency>
    <groupId>dev.mars</groupId>
    <artifactId>peegeeq-rest-client</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 3.2 Basic Usage

```java
import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import io.vertx.core.Vertx;

// Create Vert.x instance
Vertx vertx = Vertx.vertx();

// Create client with default configuration (localhost:8080)
PeeGeeQClient client = PeeGeeQRestClient.create(vertx);

// Or with custom configuration
ClientConfig config = ClientConfig.builder()
    .baseUrl("http://peegeeq-server:8080")
    .timeout(Duration.ofSeconds(30))
    .maxRetries(3)
    .poolSize(20)
    .build();

PeeGeeQClient client = PeeGeeQRestClient.create(vertx, config);
```

### 3.3 Sending Messages

```java
import dev.mars.peegeeq.client.dto.MessageRequest;
import dev.mars.peegeeq.client.dto.MessageSendResult;

// Create message request
MessageRequest message = new MessageRequest();
message.setPayload(Map.of("orderId", "12345", "amount", 99.99));
message.setHeaders(Map.of("source", "web-app"));
message.setCorrelationId("trace-abc-123");
message.setMessageGroup("customer-456");
message.setPriority(5);
message.setDelaySeconds(60);

// Send message
client.sendMessage("my-setup", "orders", message)
    .onSuccess(result -> {
        System.out.println("Message sent: " + result.getMessageId());
    })
    .onFailure(err -> {
        System.err.println("Failed to send: " + err.getMessage());
    });
```

### 3.4 Working with Event Stores

```java
import dev.mars.peegeeq.client.dto.AppendEventRequest;
import dev.mars.peegeeq.api.BiTemporalEvent;

// Append an event
AppendEventRequest eventRequest = new AppendEventRequest();
eventRequest.setEventType("OrderCreated");
eventRequest.setPayload(Map.of("orderId", "12345", "customerId", "cust-789"));
eventRequest.setValidFrom(Instant.now());
eventRequest.setCorrelationId("order-flow-123");

client.appendEvent("my-setup", "order-events", eventRequest)
    .onSuccess(event -> {
        System.out.println("Event appended: " + event.getEventId());
        System.out.println("Transaction time: " + event.getTransactionTime());
    });

// Query events
EventQuery query = EventQuery.builder()
    .eventType("OrderCreated")
    .limit(100)
    .build();

client.queryEvents("my-setup", "order-events", query)
    .onSuccess(result -> {
        result.getEvents().forEach(event ->
            System.out.println("Event: " + event.getEventId()));
    });
```

## 4. Configuration Reference

### 4.1 ClientConfig Options

| Option | Default | Description |
|--------|---------|-------------|
| `baseUrl` | `http://localhost:8080` | Base URL for the PeeGeeQ REST API |
| `timeout` | `30 seconds` | Request timeout duration |
| `maxRetries` | `3` | Maximum retry attempts for failed requests |
| `retryDelay` | `500 ms` | Delay between retry attempts |
| `poolSize` | `10` | HTTP connection pool size |
| `sslEnabled` | `false` | Enable SSL/TLS for HTTPS connections |
| `trustAllCertificates` | `false` | Trust all certificates (development only) |

### 4.2 Configuration Examples

**Production Configuration:**
```java
ClientConfig config = ClientConfig.builder()
    .baseUrl("https://peegeeq.production.example.com")
    .timeout(Duration.ofSeconds(60))
    .maxRetries(5)
    .retryDelay(Duration.ofSeconds(1))
    .poolSize(50)
    .sslEnabled(true)
    .build();
```

**Development Configuration:**
```java
ClientConfig config = ClientConfig.builder()
    .baseUrl("https://localhost:8443")
    .timeout(Duration.ofSeconds(10))
    .maxRetries(1)
    .poolSize(5)
    .sslEnabled(true)
    .trustAllCertificates(true)  // Only for development!
    .build();
```

## 5. API Reference

### 5.1 Setup Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `createSetup(DatabaseSetupRequest)` | Creates a new database setup | `Future<DatabaseSetupResult>` |
| `listSetups()` | Lists all active setups | `Future<List<DatabaseSetupResult>>` |
| `getSetup(setupId)` | Gets details for a setup | `Future<DatabaseSetupResult>` |
| `deleteSetup(setupId)` | Deletes a setup | `Future<Void>` |

### 5.2 Queue Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `sendMessage(setupId, queueName, message)` | Sends a message | `Future<MessageSendResult>` |
| `sendBatch(setupId, queueName, messages)` | Sends multiple messages | `Future<List<MessageSendResult>>` |
| `getQueueStats(setupId, queueName)` | Gets queue statistics | `Future<QueueStats>` |

### 5.3 Consumer Group Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `createConsumerGroup(setupId, queueName, groupName)` | Creates a consumer group | `Future<ConsumerGroupInfo>` |
| `listConsumerGroups(setupId, queueName)` | Lists consumer groups | `Future<List<ConsumerGroupInfo>>` |
| `getConsumerGroup(setupId, queueName, groupName)` | Gets consumer group details | `Future<ConsumerGroupInfo>` |
| `deleteConsumerGroup(setupId, queueName, groupName)` | Deletes a consumer group | `Future<Void>` |

### 5.4 Dead Letter Queue Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `listDeadLetters(setupId, page, pageSize)` | Lists dead letter messages | `Future<DeadLetterListResponse>` |
| `getDeadLetter(setupId, messageId)` | Gets a dead letter message | `Future<DeadLetterMessageInfo>` |
| `reprocessDeadLetter(setupId, messageId)` | Reprocesses a message | `Future<Void>` |
| `deleteDeadLetter(setupId, messageId)` | Deletes a message | `Future<Void>` |
| `getDeadLetterStats(setupId)` | Gets DLQ statistics | `Future<DeadLetterStatsInfo>` |

### 5.5 Subscription Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `listSubscriptions(setupId, topic)` | Lists subscriptions | `Future<List<SubscriptionInfo>>` |
| `getSubscription(setupId, topic, groupName)` | Gets subscription details | `Future<SubscriptionInfo>` |
| `pauseSubscription(setupId, topic, groupName)` | Pauses a subscription | `Future<Void>` |
| `resumeSubscription(setupId, topic, groupName)` | Resumes a subscription | `Future<Void>` |
| `cancelSubscription(setupId, topic, groupName)` | Cancels a subscription | `Future<Void>` |

### 5.6 Health Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `getHealth(setupId)` | Gets overall health status | `Future<OverallHealthInfo>` |
| `listComponentHealth(setupId)` | Lists component health | `Future<List<HealthStatusInfo>>` |
| `getComponentHealth(setupId, componentName)` | Gets component health | `Future<HealthStatusInfo>` |

### 5.7 Event Store Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `appendEvent(setupId, storeName, request)` | Appends an event | `Future<BiTemporalEvent>` |
| `queryEvents(setupId, storeName, query)` | Queries events | `Future<EventQueryResult>` |
| `getEvent(setupId, storeName, eventId)` | Gets an event by ID | `Future<BiTemporalEvent>` |
| `getEventVersions(setupId, storeName, eventId)` | Gets all versions | `Future<List<BiTemporalEvent>>` |
| `appendCorrection(setupId, storeName, eventId, request)` | Appends a correction | `Future<BiTemporalEvent>` |

### 5.8 Streaming Operations

| Method | Description | Returns |
|--------|-------------|---------|
| `streamEvents(setupId, storeName, options)` | Streams events via SSE | `ReadStream<BiTemporalEvent>` |

**Note:** SSE streaming is not yet fully implemented. See Section 8 for roadmap.

## 6. Error Handling

### 6.1 Exception Hierarchy

```
PeeGeeQClientException (base class)
├── PeeGeeQApiException      - HTTP 4xx/5xx errors from the server
└── PeeGeeQNetworkException  - Network/connection errors
```

### 6.2 PeeGeeQApiException

Thrown when the server returns an HTTP error response (4xx or 5xx).

```java
client.getSetup("non-existent-setup")
    .onFailure(err -> {
        if (err instanceof PeeGeeQApiException apiErr) {
            System.out.println("Status code: " + apiErr.getStatusCode());
            System.out.println("Error code: " + apiErr.getErrorCode());
            System.out.println("Message: " + apiErr.getMessage());
        }
    });
```

**Properties:**
| Property | Type | Description |
|----------|------|-------------|
| `statusCode` | `int` | HTTP status code (e.g., 404, 500) |
| `errorCode` | `String` | Application error code (if provided) |
| `message` | `String` | Error message from server |

### 6.3 PeeGeeQNetworkException

Thrown when a network error occurs (connection refused, timeout, DNS failure).

```java
client.sendMessage("setup", "queue", message)
    .onFailure(err -> {
        if (err instanceof PeeGeeQNetworkException netErr) {
            System.out.println("Host: " + netErr.getHost());
            System.out.println("Port: " + netErr.getPort());
            System.out.println("Timeout: " + netErr.isTimeout());
        }
    });
```

**Properties:**
| Property | Type | Description |
|----------|------|-------------|
| `host` | `String` | Target host |
| `port` | `int` | Target port |
| `isTimeout` | `boolean` | Whether the error was a timeout |

### 6.4 Error Handling Best Practices

```java
client.sendMessage(setupId, queueName, message)
    .onSuccess(result -> {
        logger.info("Message sent: {}", result.getMessageId());
    })
    .onFailure(err -> {
        if (err instanceof PeeGeeQApiException apiErr) {
            if (apiErr.getStatusCode() == 404) {
                logger.warn("Queue not found: {}", queueName);
            } else if (apiErr.getStatusCode() >= 500) {
                logger.error("Server error, will retry: {}", apiErr.getMessage());
                // Implement retry logic
            }
        } else if (err instanceof PeeGeeQNetworkException netErr) {
            if (netErr.isTimeout()) {
                logger.warn("Request timed out, will retry");
            } else {
                logger.error("Network error: {}", netErr.getMessage());
            }
        } else {
            logger.error("Unexpected error: {}", err.getMessage(), err);
        }
    });
```

## 7. DTO Reference

### 7.1 Request DTOs

| DTO | Purpose | Key Fields |
|-----|---------|------------|
| `MessageRequest` | Send a message | `payload`, `headers`, `correlationId`, `messageGroup`, `priority`, `delaySeconds`, `messageType` |
| `AppendEventRequest` | Append an event | `eventType`, `payload`, `validTime`, `headers`, `correlationId`, `aggregateId` |
| `CorrectionRequest` | Append a correction | `eventData`, `correctionReason`, `validFrom`, `correlationId`, `metadata` |
| `StreamOptions` | Configure SSE streaming | `eventType`, `aggregateId`, `lastEventId` |

### 7.2 Response DTOs

| DTO | Purpose | Key Fields |
|-----|---------|------------|
| `MessageSendResult` | Message send confirmation | `messageId`, `queueName`, `timestamp` |
| `QueueStats` | Queue statistics | `messageCount`, `consumerCount`, `pendingCount` |
| `ConsumerGroupInfo` | Consumer group details | `groupName`, `memberCount`, `status` |
| `DeadLetterListResponse` | Paginated DLQ messages | `messages`, `totalCount`, `page`, `pageSize` |
| `EventQueryResult` | Event query results | `events`, `totalCount`, `hasMore` |

### 7.3 Fluent Builder API

All request DTOs support a fluent builder pattern for cleaner code:

```java
// Using fluent API
MessageRequest message = new MessageRequest()
    .withPayload(Map.of("orderId", "12345"))
    .withHeader("source", "web-app")
    .withCorrelationId("trace-123")
    .withMessageGroup("customer-456")
    .withPriority(5)
    .withDelaySeconds(60L);

AppendEventRequest event = new AppendEventRequest()
    .withEventType("OrderCreated")
    .withPayload(Map.of("orderId", "12345"))
    .withValidTime(Instant.now())
    .withCorrelationId("order-flow-123")
    .withAggregateId("ORDER-12345")
    .withHeader("source", "order-service");
```

## 8. Roadmap and Known Limitations

### 8.1 Current Limitations

| Feature | Status | Notes |
|---------|--------|-------|
| SSE Streaming | Not Implemented | `streamEvents()` throws `UnsupportedOperationException` |
| Retry Logic | Configured but not used | `maxRetries` and `retryDelay` are in config but not applied |
| WebSocket Support | Not Implemented | Only REST endpoints are supported |

### 8.2 Future Enhancements

1. **SSE Streaming Implementation** - Full support for `streamEvents()` using Vert.x SSE client
2. **Automatic Retry** - Implement exponential backoff retry for transient failures
3. **Circuit Breaker** - Add circuit breaker pattern for resilience
4. **Metrics Integration** - Add Micrometer metrics for monitoring
5. **Reactive Streams** - Add `Flow.Publisher` adapter for Java 9+ reactive streams

## 9. Comparison with TypeScript Client

The `peegeeq-rest-client` module is the Java equivalent of the TypeScript REST client in `peegeeq-management-ui/src/api/`.

| Aspect | Java Client (`peegeeq-rest-client`) | TypeScript Client (`peegeeq-management-ui`) |
|--------|-------------------------------------|---------------------------------------------|
| Runtime | Vert.x                              | Browser/Node.js |
| Async Model | `Future<T>`                         | `Promise<T>` |
| HTTP Client | Vert.x WebClient                    | Fetch API |
| Type Safety | Compile-time (Java generics)        | Compile-time (TypeScript) |
| SSE Support | Not yet implemented                 | Implemented |
| Retry Logic | Configured                          | Implemented with exponential backoff |

## 10. Testing

### 10.1 Unit Tests

The module includes unit tests in `src/test/java/`:

| Test Class | Purpose |
|------------|---------|
| `PeeGeeQRestClientTest` | Tests for REST client operations |
| `ClientConfigTest` | Tests for configuration builder |
| `ExceptionTest` | Tests for exception classes |

### 10.2 Running Tests

```bash
# Run all tests
mvn test -pl peegeeq-rest-client

# Run specific test class
mvn test -pl peegeeq-rest-client -Dtest=PeeGeeQRestClientTest
```

### 10.3 Integration Testing

For integration tests against a running PeeGeeQ server:

```java
@Test
void testSendMessage() {
    Vertx vertx = Vertx.vertx();
    PeeGeeQClient client = PeeGeeQRestClient.create(vertx,
        ClientConfig.builder()
            .baseUrl("http://localhost:8080")
            .build());

    MessageRequest message = new MessageRequest();
    message.setPayload(Map.of("test", "data"));

    client.sendMessage("test-setup", "test-queue", message)
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
}
```

