# PeeGeeQ Integration Test Strategy

**Last Updated:** 2025-12-11

This document defines the integration test strategy for PeeGeeQ, aligned with the layered hexagonal architecture described in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md`.

**Quick Navigation:**
- [Section 1: Architecture Overview](#1-architecture-overview) - Layer structure and test boundaries
- [Section 2: Test Categories](#2-test-categories) - Unit, Integration, and Cross-Layer tests
- [Section 3: Layer-Specific Testing](#3-layer-specific-testing) - What to test at each layer
- [Section 4: Cross-Layer Integration Tests](#4-cross-layer-integration-tests) - Testing layer interactions
- [Section 5: Test Infrastructure](#5-test-infrastructure) - TestContainers, Maven profiles
- [Section 6: Implemented Tests](#6-implemented-tests) - Current test coverage
- [Section 7: Test Execution](#7-test-execution) - How to run tests

## 1. Architecture Overview

PeeGeeQ follows a strict layered hexagonal architecture with clear separation between layers:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         peegeeq-management-ui                            │
│                    (React/TypeScript - HTTP client)                      │
└─────────────────────────────────────────────────────────────────────────┘
                                   │ HTTP/REST
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            peegeeq-rest                                  │
│                     (HTTP handlers, routing, SSE)                        │
│         Uses: peegeeq-api (types) + peegeeq-runtime (services)          │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          peegeeq-runtime                                 │
│                   (Composition + Facade Layer)                           │
│    Wires: peegeeq-db, peegeeq-native, peegeeq-outbox, peegeeq-bitemporal│
└─────────────────────────────────────────────────────────────────────────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│ peegeeq-native  │      │ peegeeq-outbox  │      │peegeeq-bitemporal│
│ (Native queues) │      │ (Outbox pattern)│      │ (Event store)    │
└─────────────────┘      └─────────────────┘      └─────────────────┘
          │                        │                        │
          └────────────────────────┼────────────────────────┘
                                   ▼
                         ┌─────────────────┐
                         │   peegeeq-db    │
                         │ (DB services)   │
                         └─────────────────┘
                                   ▲
                                   │ implements
                         ┌─────────────────┐
                         │  peegeeq-api    │
                         │ (pure contracts)│
                         └─────────────────┘
```

### 1.1 Layer Dependency Rules

| Module | Allowed Dependencies | Forbidden Dependencies |
| :--- | :--- | :--- |
| `peegeeq-api` | None (pure contracts) | All other peegeeq modules |
| `peegeeq-db` | `peegeeq-api` | `peegeeq-rest`, `peegeeq-runtime`, adapters |
| `peegeeq-native` | `peegeeq-api`, `peegeeq-db` | `peegeeq-rest`, `peegeeq-runtime` |
| `peegeeq-outbox` | `peegeeq-api`, `peegeeq-db` | `peegeeq-rest`, `peegeeq-runtime` |
| `peegeeq-bitemporal` | `peegeeq-api`, `peegeeq-db` | `peegeeq-rest`, `peegeeq-runtime` |
| `peegeeq-runtime` | `peegeeq-api`, `peegeeq-db`, all adapters | `peegeeq-rest` |
| `peegeeq-rest` | `peegeeq-api`, `peegeeq-runtime` | `peegeeq-db`, adapters (direct) |

### 1.2 Test Boundary Implications

The strict layer separation has important implications for testing:

1. **peegeeq-rest tests** should NOT directly instantiate `PgNativeQueueFactory` or `OutboxFactory`
2. **peegeeq-rest tests** obtain services via `PeeGeeQRuntime.createDatabaseSetupService()`
3. **Adapter tests** (native, outbox, bitemporal) can test their implementations directly
4. **Cross-layer tests** verify the complete flow through all layers

## 2. Test Categories

PeeGeeQ uses JUnit 5 tags to categorize tests:

| Tag | Description | Maven Profile | Infrastructure |
| :--- | :--- | :--- | :--- |
| `@Tag("core")` | Unit tests, fast, no external dependencies | `core-tests` (default) | None |
| `@Tag("integration")` | Integration tests with real PostgreSQL | `integration-tests` | TestContainers |

### 2.1 Unit Tests (`@Tag("core")`)

- Test individual classes in isolation
- Mock external dependencies
- Fast execution (< 1 second per test)
- Run by default with `mvn test`

### 2.2 Integration Tests (`@Tag("integration")`)

- Test layer interactions with real infrastructure
- Use TestContainers for PostgreSQL
- Slower execution (seconds to minutes)
- Run with `mvn test -Pintegration-tests`

## 3. Layer-Specific Testing

### 3.1 peegeeq-api (Contracts Layer)

**What to test:**
- DTO serialization/deserialization
- Builder patterns
- Validation logic in value objects
- Enum behavior

**What NOT to test:**
- Interface definitions (no implementation to test)
- Nothing that requires infrastructure

**Test type:** Unit tests only

### 3.2 peegeeq-db (Database Layer)

**What to test:**
- `DeadLetterQueueManager` operations
- `SubscriptionManager` lifecycle
- `HealthCheckManager` component checks
- `PeeGeeQDatabaseSetupService` setup flow
- SQL query correctness

**Test type:** Integration tests with TestContainers

**Example test classes:**
- `DeadLetterQueueManagerTest`
- `SubscriptionManagerTest`
- `HealthCheckManagerTest`

### 3.3 peegeeq-native / peegeeq-outbox / peegeeq-bitemporal (Adapter Layer)

**What to test:**
- `QueueFactory` implementation
- `MessageProducer` send operations
- `MessageConsumer` receive operations
- `EventStore` append/query operations
- LISTEN/NOTIFY mechanism

**Test type:** Integration tests with TestContainers

**Example test classes:**
- `PgNativeQueueFactoryTest`
- `OutboxFactoryTest`
- `PgBiTemporalEventStoreTest`

### 3.4 peegeeq-runtime (Composition Layer)

**What to test:**
- `PeeGeeQRuntime.createDatabaseSetupService()` wiring
- `RuntimeDatabaseSetupService` delegation
- Factory registration mechanism
- `RuntimeConfig` options

**Test type:** Integration tests with TestContainers

**Example test classes:**
- `PeeGeeQRuntimeTest`

### 3.5 peegeeq-rest (REST Layer)

**What to test:**
- HTTP request/response handling
- Route configuration
- Request validation
- Error response formatting
- SSE streaming
- WebSocket handling

**Test type:** Integration tests with TestContainers + Vert.x HTTP server

**Key principle:** Tests obtain services via `PeeGeeQRuntime.createDatabaseSetupService()`, NOT by directly instantiating implementation classes.

**Example test classes:**
- `CallPropagationIntegrationTest`
- `CrossLayerPropagationIntegrationTest`
- `EventStoreIntegrationTest`
- `SSEStreamingIntegrationTest`

## 4. Cross-Layer Integration Tests

Cross-layer tests verify the complete flow through all architectural layers. These are the most important tests for ensuring the system works as documented.

### 4.1 Test Flow Categories

| Flow | Layers Involved | Test Class |
| :--- | :--- | :--- |
| Message Production | REST → Runtime → Native/Outbox → DB | `CallPropagationIntegrationTest` |
| Message Consumption | DB → Native/Outbox → SSE Handler → Client | `CrossLayerPropagationIntegrationTest` |
| Event Store Operations | REST → Runtime → Bitemporal → DB | `CallPropagationIntegrationTest` |
| DLQ Operations | REST → Runtime → DB → DLQ Manager | `CrossLayerPropagationIntegrationTest` |
| Health Checks | REST → Runtime → DB → Health Manager | `CrossLayerPropagationIntegrationTest` |
| Subscription Lifecycle | REST → Runtime → DB → Subscription Manager | `CrossLayerPropagationIntegrationTest` |

### 4.2 Cross-Layer Test Pattern

All cross-layer tests follow this pattern:

```java
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class CrossLayerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");

    private DatabaseSetupService setupService;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        // Use PeeGeeQRuntime - respects layer boundaries
        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy REST server with the setup service
        PeeGeeQRestServer server = new PeeGeeQRestServer(TEST_PORT, setupService);
        vertx.deployVerticle(server)
            .onSuccess(id -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testCrossLayerFlow(Vertx vertx, VertxTestContext testContext) {
        // 1. Create setup via REST API
        // 2. Perform operation via REST API
        // 3. Verify result via REST API or direct DB query
    }
}
```

### 4.3 What Cross-Layer Tests Verify

1. **REST → Database Propagation**
   - HTTP request is correctly parsed
   - Request is delegated to runtime services
   - Data is persisted to PostgreSQL
   - Response contains correct data

2. **Database → Consumer Propagation**
   - PostgreSQL NOTIFY is triggered
   - Consumer receives message via SSE/WebSocket
   - Message payload is correctly serialized

3. **Error Handling Flow**
   - Errors are correctly propagated through layers
   - DLQ receives failed messages
   - Error responses have correct format

4. **Feature Propagation**
   - correlationId flows through all layers
   - messageGroup flows through all layers
   - priority flows through all layers
   - headers flow through all layers

## 5. Test Infrastructure

### 5.1 TestContainers Configuration

All integration tests use TestContainers for PostgreSQL:

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
    .withDatabaseName("peegeeq_test")
    .withUsername("peegeeq_test")
    .withPassword("peegeeq_test")
    .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
    .withReuse(false);
```

### 5.2 Maven Profiles

**Default profile (`core-tests`):**
```xml
<groups>core</groups>
<excludedGroups>integration</excludedGroups>
```

**Integration profile (`integration-tests`):**
```xml
<groups>integration</groups>
<excludedGroups></excludedGroups>
```

### 5.3 Test Constants

Use `PostgreSQLTestConstants` for consistent configuration:
- `POSTGRES_IMAGE` - PostgreSQL Docker image
- `DEFAULT_SHARED_MEMORY_SIZE` - Shared memory for container

## 6. Implemented Tests

### 6.1 CallPropagationIntegrationTest (8 tests)

| Test | Flow Verified |
| :--- | :--- |
| `testRestToDatabasePropagation` | REST → Producer → Database |
| `testMessagePriorityPropagation` | Priority field propagation |
| `testMessageDelayPropagation` | Delay field propagation |
| `testBiTemporalEventStorePropagation` | REST → EventStore → Database |
| `testEventQueryByTemporalRange` | Event query with temporal filters |
| `testCorrelationIdPropagation` | correlationId field propagation |
| `testMessageGroupPropagation` | messageGroup field propagation |
| `testCorrelationIdAndMessageGroupCombined` | Combined fields propagation |

### 6.2 CrossLayerPropagationIntegrationTest (10 tests)

| Test | Flow Verified |
| :--- | :--- |
| `testCompleteMessageProductionAndConsumptionFlow` | REST → Producer → DB → SSE Consumer |
| `testDLQRestApiCrossLayer` | REST → DLQ Service → Database |
| `testMultipleSSEConsumersMessageDistribution` | SSE broadcast to multiple consumers |
| `testQueueStatsRestApiCrossLayer` | REST → Queue Stats → Database |
| `testPriorityMessageSendingViaRest` | Priority via REST API |
| `testHealthCheckRestApiCrossLayer` | REST → Health Service → Database |
| `testSubscriptionLifecycleRestApiCrossLayer` | REST → Subscription Service → Database |
| `testCorrelationIdPropagationViaRest` | correlationId via REST API |
| `testMessageGroupPropagationViaRest` | messageGroup via REST API |
| `testMessageHeadersPropagationViaRest` | headers via REST API |

### 6.3 Other Integration Tests

| Test Class | Focus Area |
| :--- | :--- |
| `EventStoreIntegrationTest` | Event store REST API |
| `SSEStreamingIntegrationTest` | SSE streaming functionality |
| `ConsumerGroupSubscriptionIntegrationTest` | Consumer group operations |
| `SubscriptionLifecycleIntegrationTest` | Subscription lifecycle |
| `DeadLetterRequeueIntegrationTest` | DLQ reprocessing |
| `WebhookPushDeliveryIntegrationTest` | Webhook delivery |

## 7. Test Execution

### 7.1 Running Unit Tests

```bash
# Default - runs core tests only
mvn test

# Specific module
mvn test -pl peegeeq-rest
```

### 7.2 Running Integration Tests

```bash
# All integration tests
mvn test -Pintegration-tests

# Specific test class
mvn test -Pintegration-tests -Dtest=CrossLayerPropagationIntegrationTest

# Specific module
mvn test -Pintegration-tests -pl peegeeq-rest
```

### 7.3 Running All Tests

```bash
# Both core and integration tests
mvn test -Pintegration-tests -Dtest.groups=core,integration -Dtest.excludedGroups=
```

### 7.4 Test Reports

JaCoCo coverage reports are generated in:
```
target/site/jacoco/index.html
```

## 8. Best Practices

### 8.1 Respecting Layer Boundaries in Tests

**DO:**
```java
// Use PeeGeeQRuntime to obtain services
DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
```

**DON'T:**
```java
// Don't directly instantiate implementation classes in peegeeq-rest tests
PgNativeQueueFactory factory = new PgNativeQueueFactory(...);
```

### 8.2 Test Isolation

- Each test class should use a unique `setupId` and `databaseName`
- Use `@TestInstance(Lifecycle.PER_CLASS)` for shared setup
- Clean up resources in `@AfterAll`

### 8.3 Async Testing with Vert.x

- Use `VertxTestContext` for async assertions
- Use `CountDownLatch` for waiting on async events
- Set appropriate timeouts (15-60 seconds for integration tests)

### 8.4 Logging

- Log test progress with clear markers (`=== Test N: Description ===`)
- Log verification results with checkmarks (`✅ Verification complete`)
- Log errors with context for debugging
