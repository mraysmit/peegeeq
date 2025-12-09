# PeeGeeQ API Module Guide

**Last Updated:** 2025-12-09

This document provides comprehensive documentation for the `peegeeq-api` module, the foundational contracts layer of the PeeGeeQ messaging system.

## 1. Overview

The `peegeeq-api` module is the **pure contracts layer** of PeeGeeQ. It contains only interfaces, DTOs, configuration classes, and value objects - no implementations. This module defines the API contracts that all other PeeGeeQ modules depend on.

**Key Characteristics:**
- **Zero implementation code** - Only interfaces and data classes
- **Foundation of the hexagonal architecture** - All other modules depend on this
- **Stable API surface** - Changes here affect the entire system
- **Dual async API** - Both `CompletableFuture<T>` and Vert.x `Future<T>` patterns

## 2. Architecture Position

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CLIENT LAYER                                       │
│  ┌─────────────────────────┐    ┌─────────────────────────────────────────┐ │
│  │   peegeeq-client        │    │   peegeeq-management-ui                 │ │
│  │   (Java REST Client)    │    │   (TypeScript Web UI)                   │ │
│  └───────────┬─────────────┘    └───────────────────┬─────────────────────┘ │
└──────────────┼──────────────────────────────────────┼───────────────────────┘
               │ HTTP/REST                            │ HTTP/REST
               ▼                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           peegeeq-rest                                       │
│                      (HTTP Handlers Layer)                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ uses
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          peegeeq-runtime                                     │
│                     (Composition/Wiring Layer)                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ composes
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      IMPLEMENTATION LAYER                                    │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐  │
│  │ peegeeq-native  │  │ peegeeq-outbox  │  │ peegeeq-bitemporal          │  │
│  │ (LISTEN/NOTIFY) │  │ (Transactional) │  │ (Event Store)               │  │
│  └────────┬────────┘  └────────┬────────┘  └──────────────┬──────────────┘  │
└───────────┼────────────────────┼──────────────────────────┼─────────────────┘
            │                    │                          │
            │ implements         │ implements               │ implements
            ▼                    ▼                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           peegeeq-db                                         │
│                    (Database Layer + Services)                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ implements
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ★ peegeeq-api ★                                     │
│                    (Pure Contracts - THIS MODULE)                            │
│                                                                              │
│  Interfaces: ReactiveQueue, EventStore, MessageProducer, MessageConsumer,   │
│              QueueFactory, DatabaseSetupService, SubscriptionService,       │
│              DeadLetterService, HealthService, ConnectionProvider           │
│                                                                              │
│  DTOs: BiTemporalEvent, Message, EventQuery, DatabaseConfig, QueueConfig,   │
│        SubscriptionInfo, DeadLetterMessageInfo, HealthStatusInfo            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Dependency Rules

| Module | Can Depend On | Cannot Depend On |
|--------|---------------|------------------|
| `peegeeq-api` | External libs only (Vert.x, Jackson) | Any other peegeeq module |
| `peegeeq-db` | `peegeeq-api` | Implementation modules |
| `peegeeq-native/outbox/bitemporal` | `peegeeq-api`, `peegeeq-db` | Each other, runtime, rest |
| `peegeeq-runtime` | All implementation modules | `peegeeq-rest` |
| `peegeeq-rest` | `peegeeq-api`, `peegeeq-runtime` | Implementation modules directly |
| `peegeeq-client` | `peegeeq-api` | All other modules |

## 3. Module Structure

```
peegeeq-api/
├── src/main/java/dev/mars/peegeeq/api/
│   ├── BiTemporalEvent.java          # Bi-temporal event interface
│   ├── EventQuery.java               # Event query builder
│   ├── EventStore.java               # Event store interface
│   ├── EventStoreFactory.java        # Event store factory interface
│   ├── QueueConfiguration.java       # Queue configuration
│   ├── QueueFactoryProvider.java     # Factory provider interface
│   ├── QueueFactoryRegistrar.java    # Factory registration interface
│   ├── ReactiveQueue.java            # Core reactive queue interface
│   ├── SchemaVersion.java            # Schema versioning
│   ├── SimpleBiTemporalEvent.java    # Default BiTemporalEvent implementation
│   ├── TemporalRange.java            # Temporal range for queries
│   │
│   ├── database/                     # Database configuration & services
│   │   ├── ConnectionPoolConfig.java
│   │   ├── ConnectionProvider.java
│   │   ├── DatabaseConfig.java
│   │   ├── DatabaseService.java
│   │   ├── EventStoreConfig.java
│   │   ├── MetricsProvider.java
│   │   └── QueueConfig.java
│   │
│   ├── deadletter/                   # Dead letter queue contracts
│   │   ├── DeadLetterMessageInfo.java
│   │   ├── DeadLetterService.java
│   │   └── DeadLetterStatsInfo.java
│   │
│   ├── health/                       # Health monitoring contracts
│   │   ├── ComponentHealthState.java
│   │   ├── HealthService.java
│   │   ├── HealthStatusInfo.java
│   │   └── OverallHealthInfo.java
│   │
│   ├── lifecycle/                    # Lifecycle management
│   │   ├── LifecycleHookRegistrar.java
│   │   └── PeeGeeQCloseHook.java
│   │
│   ├── messaging/                    # Core messaging contracts
│   │   ├── ConsumerGroup.java
│   │   ├── ConsumerGroupMember.java
│   │   ├── ConsumerGroupStats.java
│   │   ├── ConsumerMemberStats.java
│   │   ├── Message.java
│   │   ├── MessageConsumer.java
│   │   ├── MessageFilter.java
│   │   ├── MessageHandler.java
│   │   ├── MessageProducer.java
│   │   ├── QueueFactory.java
│   │   ├── SimpleMessage.java
│   │   ├── StartPosition.java
│   │   └── SubscriptionOptions.java
│   │
│   ├── setup/                        # Database setup contracts
│   │   ├── DatabaseSetupRequest.java
│   │   ├── DatabaseSetupResult.java
│   │   ├── DatabaseSetupService.java
│   │   ├── DatabaseSetupStatus.java
│   │   └── ServiceProvider.java
│   │
│   └── subscription/                 # Subscription management
│       ├── SubscriptionInfo.java
│       ├── SubscriptionService.java
│       └── SubscriptionState.java
│
└── src/test/java/                    # Unit tests for DTOs and value objects
```

## 4. Core Interfaces

### 4.1 Messaging Interfaces

#### ReactiveQueue<T>

The foundational queue interface for reactive message processing.

```java
public interface ReactiveQueue<T> {
    Future<Void> send(T message);
    ReadStream<T> receive();
    Future<Void> acknowledge(String messageId);
    Future<Void> close();
}
```

#### MessageProducer<T>

Interface for producing messages to a queue with dual async API.

| Method | Description |
|--------|-------------|
| `send(T payload)` | Sends a message with payload only |
| `send(T payload, Map<String, String> headers)` | Sends with headers |
| `send(T payload, headers, correlationId)` | Sends with correlation ID |
| `send(T payload, headers, correlationId, messageGroup)` | Sends with message group for ordering |
| `sendReactive(...)` | Vert.x `Future<T>` versions of all above |

#### MessageConsumer<T>

Interface for consuming messages from a queue.

| Method | Description |
|--------|-------------|
| `subscribe(MessageHandler<T> handler)` | Subscribes to messages |
| `unsubscribe()` | Stops receiving messages |
| `close()` | Releases resources |

#### ConsumerGroup<T>

Interface for managing groups of consumers with load balancing.

| Method | Description |
|--------|-------------|
| `addConsumer(consumerId, handler)` | Adds a consumer to the group |
| `addConsumer(consumerId, handler, filter)` | Adds with message filtering |
| `removeConsumer(consumerId)` | Removes a consumer |
| `start()` | Starts message processing |
| `start(SubscriptionOptions)` | Starts with subscription options |
| `stop()` | Stops message processing |
| `setMessageHandler(handler)` | Convenience for single-consumer groups |
| `setGroupFilter(filter)` | Sets group-level message filter |
| `getStats()` | Gets consumer group statistics |

#### QueueFactory

Factory interface for creating producers and consumers.

| Method | Description |
|--------|-------------|
| `createProducer(topic, payloadType)` | Creates a message producer |
| `createConsumer(topic, payloadType)` | Creates a message consumer |
| `createConsumer(topic, payloadType, config)` | Creates with custom config |
| `createConsumerGroup(groupName, topic, payloadType)` | Creates a consumer group |
| `getImplementationType()` | Returns "native" or "outbox" |
| `isHealthy()` | Health check |

### 4.2 Event Store Interfaces

#### EventStore<T>

Interface for bi-temporal event storage with append-only semantics.

**Append Operations:**

| Method | Description |
|--------|-------------|
| `append(eventType, payload, validTime)` | Appends an event |
| `append(eventType, payload, validTime, headers)` | Appends with headers |
| `append(eventType, payload, validTime, headers, correlationId, aggregateId)` | Full metadata |
| `appendCorrection(originalEventId, eventType, payload, validTime, reason)` | Appends a correction |
| `appendInTransaction(...)` | Appends within existing transaction |

**Query Operations:**

| Method | Description |
|--------|-------------|
| `query(EventQuery)` | Queries events by criteria |
| `getById(eventId)` | Gets event by ID |
| `getAllVersions(eventId)` | Gets all versions of an event |
| `getAsOfTransactionTime(eventId, asOf)` | Point-in-time query |

**Subscription Operations:**

| Method | Description |
|--------|-------------|
| `subscribe(eventType, handler)` | Subscribes to real-time events |
| `subscribe(eventType, aggregateId, handler)` | Subscribes with aggregate filter |
| `subscribeReactive(eventType, handler)` | Vert.x Future version |
| `unsubscribe()` | Stops subscription |

#### BiTemporalEvent<T>

Interface representing a bi-temporal event with two time dimensions.

| Property | Description |
|----------|-------------|
| `eventId` | Unique event identifier |
| `eventType` | Event type for deserialization |
| `payload` | Event data |
| `validTime` | When the event happened (business time) |
| `transactionTime` | When the event was recorded (system time) |
| `version` | Event version number |
| `previousVersionId` | ID of previous version (for corrections) |
| `headers` | Event metadata |
| `correlationId` | For tracking related events |
| `aggregateId` | For grouping related events |
| `isCorrection` | Whether this is a correction |
| `correctionReason` | Reason for correction |

### 4.3 Service Interfaces

#### DatabaseSetupService

Service for creating and managing database setups.

| Method | Description |
|--------|-------------|
| `createCompleteSetup(request)` | Creates a new database setup |
| `destroySetup(setupId)` | Destroys a setup |
| `getSetupStatus(setupId)` | Gets setup status |
| `getSetupResult(setupId)` | Gets setup result |
| `addQueue(setupId, queueConfig)` | Adds a queue to setup |
| `addEventStore(setupId, eventStoreConfig)` | Adds an event store |
| `getAllActiveSetupIds()` | Lists all active setups |

#### SubscriptionService

Service for managing consumer group subscriptions.

| Method | Description |
|--------|-------------|
| `subscribe(topic, groupName)` | Creates a subscription |
| `subscribe(topic, groupName, options)` | Creates with options |
| `pause(topic, groupName)` | Pauses a subscription |
| `resume(topic, groupName)` | Resumes a subscription |
| `cancel(topic, groupName)` | Cancels a subscription |
| `updateHeartbeat(topic, groupName)` | Updates heartbeat |
| `getSubscription(topic, groupName)` | Gets subscription info |
| `listSubscriptions(topic)` | Lists subscriptions for topic |

#### DeadLetterService

Service for dead letter queue operations.

| Method | Description |
|--------|-------------|
| `getDeadLetterMessages(topic, limit, offset)` | Lists DLQ messages |
| `getAllDeadLetterMessages(limit, offset)` | Lists all DLQ messages |
| `getDeadLetterMessage(id)` | Gets a specific message |
| `reprocessDeadLetterMessage(id, reason)` | Reprocesses a message |
| `deleteDeadLetterMessage(id, reason)` | Deletes a message |
| `getStatistics()` | Gets DLQ statistics |
| `cleanupOldMessages(retentionDays)` | Cleans up old messages |

#### HealthService

Service for health monitoring.

| Method | Description |
|--------|-------------|
| `getOverallHealth()` | Gets overall system health |
| `getComponentHealth(componentName)` | Gets component health |
| `isHealthy()` | Quick health check |
| `isRunning()` | Checks if service is running |

#### ConnectionProvider

Interface for database connection management.

| Method | Description |
|--------|-------------|
| `getReactivePool(clientId)` | Gets a connection pool |
| `getConnection(clientId)` | Gets a single connection |
| `withConnection(clientId, operation)` | Executes with connection |
| `withTransaction(clientId, operation)` | Executes in transaction |
| `hasClient(clientId)` | Checks if client exists |
| `isHealthy()` | Health check |
| `isClientHealthy(clientId)` | Client-specific health check |

## 5. Configuration Classes

### 5.1 DatabaseConfig

Configuration for PostgreSQL database connections.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `host` | `String` | `localhost` | Database host |
| `port` | `int` | `5432` | Database port |
| `databaseName` | `String` | - | Database name |
| `username` | `String` | - | Database username |
| `password` | `String` | - | Database password |
| `schema` | `String` | `peegeeq` | Schema name |
| `sslEnabled` | `boolean` | `false` | Enable SSL |
| `templateDatabase` | `String` | - | Template database |
| `encoding` | `String` | `UTF8` | Character encoding |
| `poolConfig` | `ConnectionPoolConfig` | - | Connection pool settings |

**Builder Pattern:**
```java
DatabaseConfig config = new DatabaseConfig.Builder()
    .host("localhost")
    .port(5432)
    .databaseName("myapp")
    .username("user")
    .password("secret")
    .schema("peegeeq")
    .sslEnabled(true)
    .poolConfig(new ConnectionPoolConfig())
    .build();
```

### 5.2 ConnectionPoolConfig

Configuration for connection pooling.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxSize` | `int` | `10` | Maximum pool size |
| `minSize` | `int` | `1` | Minimum pool size |
| `maxWaitQueueSize` | `int` | `100` | Max waiting connections |
| `idleTimeout` | `Duration` | `10 min` | Idle connection timeout |
| `connectionTimeout` | `Duration` | `30 sec` | Connection timeout |

### 5.3 QueueConfig

Configuration for queue creation.

| Property | Type | Description |
|----------|------|-------------|
| `queueName` | `String` | Queue name |
| `implementationType` | `String` | "native" or "outbox" |
| `maxRetries` | `int` | Maximum retry attempts |
| `retryDelayMs` | `long` | Delay between retries |
| `visibilityTimeoutMs` | `long` | Message visibility timeout |

### 5.4 EventStoreConfig

Configuration for event store creation.

| Property | Type | Description |
|----------|------|-------------|
| `storeName` | `String` | Event store name |
| `tableName` | `String` | Database table name |
| `enableNotifications` | `boolean` | Enable LISTEN/NOTIFY |
| `retentionDays` | `int` | Event retention period |

## 6. Query API

### 6.1 EventQuery

Builder-pattern class for constructing bi-temporal event queries.

**Factory Methods:**
```java
// Query all events
EventQuery.all();

// Query by event type
EventQuery.forEventType("OrderCreated");

// Query by aggregate
EventQuery.forAggregate("ORDER-12345");

// Query by aggregate and type
EventQuery.forAggregateAndType("ORDER-12345", "OrderCreated");

// Point-in-time queries
EventQuery.asOfValidTime(Instant.parse("2025-01-01T00:00:00Z"));
EventQuery.asOfTransactionTime(Instant.parse("2025-01-01T00:00:00Z"));
```

**Builder API:**
```java
EventQuery query = EventQuery.builder()
    .eventType("OrderCreated")
    .aggregateId("ORDER-12345")
    .correlationId("trace-abc-123")
    .validTimeRange(TemporalRange.between(start, end))
    .transactionTimeRange(TemporalRange.until(asOf))
    .headerFilters(Map.of("source", "web-app"))
    .limit(100)
    .offset(0)
    .sortOrder(EventQuery.SortOrder.VALID_TIME_DESC)
    .includeCorrections(true)
    .versionRange(1L, 10L)
    .build();
```

**Sort Orders:**
| Value | Description |
|-------|-------------|
| `VALID_TIME_ASC` | Oldest valid time first |
| `VALID_TIME_DESC` | Newest valid time first |
| `TRANSACTION_TIME_ASC` | Oldest transaction time first |
| `TRANSACTION_TIME_DESC` | Newest transaction time first |
| `VERSION_ASC` | Lowest version first |
| `VERSION_DESC` | Highest version first |

### 6.2 TemporalRange

Value class for specifying time ranges in queries.

```java
// All time
TemporalRange.all();

// From a specific time onwards
TemporalRange.from(startTime);

// Up to a specific time
TemporalRange.until(endTime);

// Between two times
TemporalRange.between(startTime, endTime);
```

## 7. Dual Async API Pattern

The `peegeeq-api` module provides two async patterns for all operations:

### 7.1 CompletableFuture (Primary)

Used by non-Vert.x consumers and for interoperability.

```java
// Using CompletableFuture
eventStore.append("OrderCreated", payload, Instant.now())
    .thenAccept(event -> System.out.println("Stored: " + event.getEventId()))
    .exceptionally(err -> {
        System.err.println("Failed: " + err.getMessage());
        return null;
    });
```

### 7.2 Vert.x Future (Reactive)

Used by Vert.x consumers for composable async operations.

```java
// Using Vert.x Future
eventStore.appendReactive("OrderCreated", payload, Instant.now())
    .onSuccess(event -> System.out.println("Stored: " + event.getEventId()))
    .onFailure(err -> System.err.println("Failed: " + err.getMessage()));
```

### 7.3 Default Method Pattern

Reactive methods are implemented as default methods that convert from CompletableFuture:

```java
public interface MessageProducer<T> {
    // Primary method
    CompletableFuture<Void> send(T payload);

    // Reactive convenience method
    default Future<Void> sendReactive(T payload) {
        return Future.fromCompletionStage(send(payload));
    }
}
```

## 8. Dependencies

### 8.1 Maven Dependencies

```xml
<dependencies>
    <!-- JSON Processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Vert.x -->
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-sql-client</artifactId>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </dependency>
</dependencies>
```

### 8.2 Why These Dependencies?

| Dependency | Reason |
|------------|--------|
| `jackson-databind` | JSON serialization for DTOs (used in REST layer) |
| `vertx-core` | `Future<T>` type for reactive API |
| `vertx-sql-client` | `SqlConnection` type for transaction participation |
| `slf4j-api` | Logging abstraction |

## 9. Testing

### 9.1 Test Categories

The module uses JUnit 5 tags for test categorization:

| Tag | Description | Timeout |
|-----|-------------|---------|
| `core` | Fast unit tests | 10s per method |
| `integration` | Tests with real infrastructure | 3m per method |
| `performance` | Load and throughput tests | 5m per method |
| `smoke` | Ultra-fast basic verification | 5s per method |
| `slow` | Long-running comprehensive tests | 15m per method |

### 9.2 Running Tests

```bash
# Run core tests (default)
mvn test -pl peegeeq-api

# Run integration tests
mvn test -pl peegeeq-api -Pintegration-tests

# Run all tests except flaky
mvn test -pl peegeeq-api -Pall-tests
```

### 9.3 Test Files

| Test Class | Purpose |
|------------|---------|
| `EventQueryTest` | Tests for EventQuery builder |
| `SimpleBiTemporalEventTest` | Tests for BiTemporalEvent implementation |
| `MessageTest` | Tests for Message interface |
| `QueueFactoryProviderTest` | Tests for factory provider |
| `MessageFilterDebugTest` | Tests for message filtering |

## 10. Design Principles

### 10.1 Interface Segregation

Each interface has a single responsibility:
- `MessageProducer` - Only produces messages
- `MessageConsumer` - Only consumes messages
- `EventStore` - Only manages events
- `HealthService` - Only health checks

### 10.2 Immutability

All DTOs and value objects are immutable:
- `BiTemporalEvent` - Immutable event record
- `EventQuery` - Immutable query specification
- `TemporalRange` - Immutable time range
- `DatabaseConfig` - Immutable configuration

### 10.3 Builder Pattern

Complex objects use the builder pattern:
- `EventQuery.builder()`
- `DatabaseConfig.Builder`
- `SubscriptionOptions.builder()`

### 10.4 Factory Pattern

Object creation is abstracted through factories:
- `QueueFactory` - Creates producers and consumers
- `EventStoreFactory` - Creates event stores
- `QueueFactoryRegistrar` - Registers factory implementations

## 11. Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-07-13 | Initial release with core messaging interfaces |
| 1.1 | 2025-07-15 | Added bi-temporal event store interfaces |
| 1.2 | 2025-08-21 | Added factory registration pattern |
| 1.3 | 2025-12-05 | Added service provider interfaces for REST layer |

