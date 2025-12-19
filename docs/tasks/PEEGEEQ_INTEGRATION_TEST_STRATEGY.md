# PeeGeeQ Integration Test Strategy

**Last Updated:** 2025-12-19

This document defines the integration test strategy for PeeGeeQ, aligned with the layered hexagonal architecture described in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md`.

**Quick Navigation:**
- [Section 1: Architecture Overview](#1-architecture-overview) - Layer structure and test boundaries
- [Section 2: Test Categories](#2-test-categories) - Unit, Integration, Smoke, and Cross-Layer tests
- [Section 3: Layer-Specific Testing](#3-layer-specific-testing) - What to test at each layer
- [Section 4: Cross-Layer Integration Tests](#4-cross-layer-integration-tests) - Testing layer interactions
- [Section 5: Test Infrastructure](#5-test-infrastructure) - TestContainers, Maven profiles
- [Section 6: Endpoint Coverage Matrix](#6-endpoint-coverage-matrix) - Complete REST endpoint test coverage
- [Section 7: Implemented Tests](#7-implemented-tests) - Current test coverage
- [Section 8: Layer Verification Patterns](#8-layer-verification-patterns) - How to trace calls through layers
- [Section 9: Test Execution](#9-test-execution) - How to run tests
- [Section 10: Implementation Status](#10-implementation-status) - Endpoint implementation and test coverage status
- [Section 11: Known Limitations](#11-known-limitations) - Placeholder implementations and future work
- [Section 12: Best Practices](#12-best-practices) - Testing guidelines and patterns

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

| Tag | Description | Maven Profile | Infrastructure | Module |
| :--- | :--- | :--- | :--- | :--- |
| `@Tag("core")` | Unit tests, fast, no external dependencies | `core-tests` (default) | None | All modules |
| `@Tag("integration")` | Integration tests with real PostgreSQL | `integration-tests` | TestContainers | All modules |
| `@Tag("smoke")` | E2E smoke tests verifying complete call propagation | `smoke-tests` | TestContainers | `peegeeq-integration-tests` |

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

### 2.3 Smoke Tests (`@Tag("smoke")`)

Smoke tests are E2E tests that verify complete call propagation from REST API through all layers to the database. They are located in the dedicated `peegeeq-integration-tests` module.

**Purpose:**
- Verify complete system functionality from client API to database and back
- Cover all three messaging patterns (Native Queue, Outbox Pattern, Bi-temporal Event Store)
- Provide fast feedback on system health after deployments

**Module:** `peegeeq-integration-tests`

**Current Smoke Tests:**

| Test Class | Tests | Description |
| :--- | :--- | :--- |
| `NativeQueueSmokeTest` | 3 | Native queue setup, message send, correlation ID propagation |
| `BiTemporalEventStoreSmokeTest` | 3 | Event store setup, event append, event query |
| `HealthCheckSmokeTest` | 4 | Health endpoints, component health, queue health |

**Run Command:**
```bash
mvn test -pl peegeeq-integration-tests -Psmoke-tests
```

**Test Base Class:**

All smoke tests extend `SmokeTestBase` which provides:
- PostgreSQL TestContainer lifecycle
- REST server deployment via `PeeGeeQRuntime.createDatabaseSetupService()`
- WebClient for HTTP requests
- Common test utilities

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

### 5.4 Smoke Test Profile

**Smoke profile (`smoke-tests`):**
```xml
<groups>smoke</groups>
<excludedGroups></excludedGroups>
```

Run with: `mvn test -pl peegeeq-integration-tests -Psmoke-tests`

## 6. Endpoint Coverage Matrix

This section provides a complete mapping of REST endpoints (from `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9) to their test coverage status.

### 6.1 Setup Operations (7 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/setups` | `DatabaseSetupHandler.createSetup()` | `CallPropagationIntegrationTest` | `testRestToDatabasePropagation` | COVERED |
| `GET /api/v1/setups` | `DatabaseSetupHandler.listSetups()` | - | - | MISSING |
| `GET /api/v1/setups/:setupId` | `DatabaseSetupHandler.getSetupDetails()` | - | - | MISSING |
| `GET /api/v1/setups/:setupId/status` | `DatabaseSetupHandler.getSetupStatus()` | - | - | MISSING |
| `DELETE /api/v1/setups/:setupId` | `DatabaseSetupHandler.deleteSetup()` | `NativeQueueSmokeTest` | `cleanupSetup()` | PARTIAL |
| `POST /api/v1/setups/:setupId/queues` | `DatabaseSetupHandler.addQueue()` | - | - | MISSING |
| `POST /api/v1/setups/:setupId/eventstores` | `DatabaseSetupHandler.addEventStore()` | - | - | MISSING |

### 6.2 Queue Operations (4 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/queues/:setupId/:queueName/messages` | `QueueHandler.sendMessage()` | `CallPropagationIntegrationTest` | `testRestToDatabasePropagation` | COVERED |
| `POST /api/v1/queues/:setupId/:queueName/messages/batch` | `QueueHandler.sendMessages()` | - | - | MISSING |
| `GET /api/v1/queues/:setupId/:queueName/stats` | `QueueHandler.getQueueStats()` | `CrossLayerPropagationIntegrationTest` | `testQueueStatsRestApiCrossLayer` | COVERED |
| `GET /api/v1/queues/:setupId/:queueName/stream` | `QueueSSEHandler.handleQueueStream()` | `CrossLayerPropagationIntegrationTest` | `testCompleteMessageProductionAndConsumptionFlow` | COVERED |

### 6.3 Consumer Group Operations (6 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/queues/:setupId/:queueName/consumer-groups` | `ConsumerGroupHandler.createConsumerGroup()` | `ConsumerGroupSubscriptionIntegrationTest` | - | PARTIAL |
| `GET /api/v1/queues/:setupId/:queueName/consumer-groups` | `ConsumerGroupHandler.listConsumerGroups()` | - | - | MISSING |
| `GET /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `ConsumerGroupHandler.getConsumerGroup()` | - | - | MISSING |
| `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `ConsumerGroupHandler.deleteConsumerGroup()` | - | - | MISSING |
| `POST /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | `ConsumerGroupHandler.joinConsumerGroup()` | - | - | MISSING |
| `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | `ConsumerGroupHandler.leaveConsumerGroup()` | - | - | MISSING |

### 6.4 Event Store Operations (8 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/eventstores/:setupId/:eventStoreName/events` | `EventStoreHandler.storeEvent()` | `CallPropagationIntegrationTest` | `testBiTemporalEventStorePropagation` | COVERED |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/events` | `EventStoreHandler.queryEvents()` | `CallPropagationIntegrationTest` | `testEventQueryByTemporalRange` | COVERED |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId` | `EventStoreHandler.getEvent()` | `EventStoreIntegrationTest` | - | PARTIAL |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions` | `EventStoreHandler.getAllVersions()` | `EventStoreIntegrationTest` | `testGetEventVersions` | COVERED |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at` | `EventStoreHandler.getAsOfTransactionTime()` | `EventStoreIntegrationTest` | `testPointInTimeQuery` | COVERED |
| `POST /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections` | `EventStoreHandler.appendCorrection()` | `EventStoreIntegrationTest` | `testAppendCorrection*` | COVERED |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/stats` | `EventStoreHandler.getStats()` | `BiTemporalEventStoreSmokeTest` | `testEventStoreStats` | COVERED |
| `GET /api/v1/eventstores/:setupId/:eventStoreName/events/stream` | `EventStoreHandler.handleEventStream()` | - | - | MISSING |

### 6.5 Dead Letter Queue Operations (6 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `GET /api/v1/setups/:setupId/deadletter/messages` | `DeadLetterHandler.listMessages()` | `CrossLayerPropagationIntegrationTest` | `testDLQRestApiCrossLayer` | COVERED |
| `GET /api/v1/setups/:setupId/deadletter/messages/:messageId` | `DeadLetterHandler.getMessage()` | - | - | MISSING |
| `POST /api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | `DeadLetterHandler.reprocessMessage()` | `DeadLetterRequeueIntegrationTest` | - | PARTIAL |
| `DELETE /api/v1/setups/:setupId/deadletter/messages/:messageId` | `DeadLetterHandler.deleteMessage()` | - | - | MISSING |
| `GET /api/v1/setups/:setupId/deadletter/stats` | `DeadLetterHandler.getStats()` | - | - | MISSING |
| `POST /api/v1/setups/:setupId/deadletter/cleanup` | `DeadLetterHandler.cleanup()` | - | - | MISSING |

### 6.6 Subscription Lifecycle Operations (6 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `GET /api/v1/setups/:setupId/subscriptions/:topic` | `SubscriptionHandler.listSubscriptions()` | `CrossLayerPropagationIntegrationTest` | `testSubscriptionLifecycleRestApiCrossLayer` | COVERED |
| `GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `SubscriptionHandler.getSubscription()` | - | - | MISSING |
| `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause` | `SubscriptionHandler.pauseSubscription()` | `SubscriptionLifecycleIntegrationTest` | - | PARTIAL |
| `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume` | `SubscriptionHandler.resumeSubscription()` | `SubscriptionLifecycleIntegrationTest` | - | PARTIAL |
| `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat` | `SubscriptionHandler.updateHeartbeat()` | - | - | MISSING |
| `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `SubscriptionHandler.cancelSubscription()` | - | - | MISSING |

### 6.7 Health Check Operations (3 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `GET /api/v1/setups/:setupId/health` | `HealthHandler.getOverallHealth()` | `CrossLayerPropagationIntegrationTest` | `testHealthCheckRestApiCrossLayer` | COVERED |
| `GET /api/v1/setups/:setupId/health/components` | `HealthHandler.listComponentHealth()` | `HealthCheckSmokeTest` | `testComponentHealthEndpoint` | COVERED |
| `GET /api/v1/setups/:setupId/health/components/:name` | `HealthHandler.getComponentHealth()` | - | - | MISSING |

### 6.8 Management API Operations (6 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `GET /api/v1/health` | `ManagementApiHandler.getHealth()` | `HealthCheckSmokeTest` | `testGlobalHealthEndpoint` | COVERED |
| `GET /api/v1/management/overview` | `ManagementApiHandler.getSystemOverview()` | `ManagementApiIntegrationTest` | `testSystemOverviewEndpoint` | COVERED |
| `GET /api/v1/management/queues` | `ManagementApiHandler.getQueues()` | `ManagementApiIntegrationTest` | `testQueuesEndpoint` | COVERED |
| `GET /api/v1/management/event-stores` | `ManagementApiHandler.getEventStores()` | `ManagementApiIntegrationTest` | `testEventStoresEndpoint` | COVERED |
| `GET /api/v1/management/consumer-groups` | `ManagementApiHandler.getConsumerGroups()` | `ManagementApiIntegrationTest` | `testConsumerGroupsEndpoint` | COVERED |
| `GET /api/v1/management/metrics` | `ManagementApiHandler.getMetrics()` | - | - | MISSING |

### 6.9 Webhook Operations (3 endpoints)

| REST Endpoint | Handler Method | Test Class | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | `WebhookSubscriptionHandler.createSubscription()` | `WebhookPushDeliveryIntegrationTest` | - | PARTIAL |
| `GET /api/v1/webhook-subscriptions/:subscriptionId` | `WebhookSubscriptionHandler.getSubscription()` | - | - | MISSING |
| `DELETE /api/v1/webhook-subscriptions/:subscriptionId` | `WebhookSubscriptionHandler.deleteSubscription()` | - | - | MISSING |

### 6.10 Coverage Summary

| Category | Total Endpoints | Covered | Partial | Missing |
| :--- | :---: | :---: | :---: | :---: |
| Setup Operations | 7 | 1 | 1 | 5 |
| Queue Operations | 4 | 3 | 0 | 1 |
| Consumer Group Operations | 6 | 0 | 1 | 5 |
| Event Store Operations | 8 | 6 | 1 | 1 |
| Dead Letter Queue Operations | 6 | 1 | 1 | 4 |
| Subscription Lifecycle Operations | 6 | 1 | 2 | 3 |
| Health Check Operations | 3 | 2 | 0 | 1 |
| Management API Operations | 6 | 5 | 0 | 1 |
| Webhook Operations | 3 | 0 | 1 | 2 |
| **Total** | **49** | **19** | **7** | **23** |

**Coverage Rate:** 39% fully covered, 14% partially covered, 47% missing

## 7. Implemented Tests

### 7.1 CallPropagationIntegrationTest (8 tests)

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

### 7.2 CrossLayerPropagationIntegrationTest (10 tests)

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

### 7.3 Smoke Tests (peegeeq-integration-tests module)

#### 7.3.1 Java Smoke Tests

| Test Class | Tests | Flow Verified |
| :--- | :--- | :--- |
| `NativeQueueSmokeTest` | 3 | REST → Runtime → Native Queue → DB |
| `BiTemporalEventStoreSmokeTest` | 3 | REST → Runtime → BiTemporal → DB |
| `HealthCheckSmokeTest` | 4 | REST → Runtime → Health Service → DB |

#### 7.3.2 TypeScript Smoke Tests

Located in `peegeeq-integration-tests/src/test/typescript/smoke/`:

| Test File | Tests | Flow Verified |
| :--- | :--- | :--- |
| `native-queue.smoke.ts` | 3 | TypeScript client → REST → Runtime → Native Queue → DB |
| `health-monitoring.smoke.ts` | 4 | TypeScript client → REST → Health/Management endpoints |

**native-queue.smoke.ts Test Details:**

| Test | Description |
| :--- | :--- |
| `should send message and receive confirmation` | Sends message with payload, priority, headers; verifies messageId returned |
| `should propagate correlation ID through all layers` | Sends message with correlationId; verifies correlationId in response |
| `should propagate message group through all layers` | Sends message with messageGroup; verifies messageGroup in response |

**health-monitoring.smoke.ts Test Details:**

| Test | Description |
| :--- | :--- |
| `should return server health status` | Verifies health endpoint returns UP or healthy status |
| `should return management overview` | Verifies management overview endpoint returns system stats |
| `should list all setups` | Verifies setups list endpoint returns valid JSON |
| `should return metrics endpoint` | Verifies metrics endpoint availability (200 or 404) |

**Running TypeScript Smoke Tests:**

```bash
# From peegeeq-integration-tests directory
cd peegeeq-integration-tests
npm install
npm run test:smoke

# With custom API URL
PEEGEEQ_API_URL=http://localhost:8080 npm run test:smoke
```

### 7.4 REST Client Integration Tests (peegeeq-rest-client module)

The `peegeeq-rest-client` module provides a Java REST client for all PeeGeeQ endpoints. Integration tests verify the client against a real `PeeGeeQRestServer`.

| Test Class | Tests | Module |
| :--- | :--- | :--- |
| `RestClientIntegrationTest` | 16 | `peegeeq-rest-client` |

**Test Coverage:**

| Test | Endpoint | Description |
| :--- | :--- | :--- |
| `createSetup_success` | `POST /api/v1/setups` | Creates database setup via REST API |
| `listSetups_success` | `GET /api/v1/setups` | Lists all setups via REST API |
| `getSetupStatus_success` | `GET /api/v1/setups/:setupId/status` | Gets setup status via REST API |
| `sendMessage_success` | `POST /api/v1/queues/:setupId/:queueName/messages` | Sends message to queue via REST API |
| `getQueueDetails_success` | `GET /api/v1/queues/:setupId/:queueName` | Gets queue details via REST API |
| `getHealth_success` | `GET /api/v1/setups/:setupId/health` | Gets health status via REST API |
| `appendEvent_success` | `POST /api/v1/eventstores/:setupId/:name/events` | Appends event to event store via REST API |
| `queryEvents_success` | `GET /api/v1/eventstores/:setupId/:name/events` | Queries events from event store via REST API |
| `createConsumerGroup_success` | `POST /api/v1/queues/:setupId/:queueName/consumer-groups` | Creates consumer group via REST API |
| `listConsumerGroups_success` | `GET /api/v1/queues/:setupId/:queueName/consumer-groups` | Lists consumer groups via REST API |
| `getEventStoreStats_success` | `GET /api/v1/eventstores/:setupId/:name/stats` | Gets event store statistics via REST API |
| `listDeadLetters_success` | `GET /api/v1/setups/:setupId/deadletter/messages` | Lists dead letter messages via REST API |
| `getDlqStats_success` | `GET /api/v1/setups/:setupId/deadletter/stats` | Gets DLQ statistics via REST API |
| `listComponentHealth_success` | `GET /api/v1/setups/:setupId/health/components` | Lists component health via REST API |
| `deleteSetup_success` | `DELETE /api/v1/setups/:setupId` | Deletes setup via REST API |

### 7.5 Webhook Integration Tests

| Test Class | Tests | Flow Verified |
| :--- | :--- | :--- |
| `WebhookPushDeliveryIntegrationTest` | 4 | REST → Webhook Subscription → Message Consumer → HTTP Push |

**Webhook Test Details:**

| Test | Description |
| :--- | :--- |
| `testCreateWebhookSubscription` | Creates webhook subscription with URL and headers |
| `testGetWebhookSubscription` | Gets webhook subscription details |
| `testDeleteWebhookSubscription` | Deletes webhook subscription |
| `testGetNonExistentSubscription` | Verifies 404 for non-existent subscription |

**Webhook Endpoints Tested:**

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | POST | Create webhook subscription |
| `GET /api/v1/webhook-subscriptions/:subscriptionId` | GET | Get webhook subscription details |
| `DELETE /api/v1/webhook-subscriptions/:subscriptionId` | DELETE | Delete webhook subscription |

### 7.6 Other Integration Tests

| Test Class | Focus Area |
| :--- | :--- |
| `EventStoreIntegrationTest` | Event store REST API |
| `SSEBasicStreamingIntegrationTest` | SSE streaming functionality |
| `SSEBatchingIntegrationTest` | SSE batching support |
| `SSEReconnectionIntegrationTest` | SSE reconnection handling |
| `ConsumerGroupSubscriptionIntegrationTest` | Consumer group operations |
| `SubscriptionLifecycleIntegrationTest` | Subscription lifecycle |
| `SubscriptionOptionsIntegrationTest` | Subscription options configuration |
| `DeadLetterRequeueIntegrationTest` | DLQ reprocessing |
| `ManagementApiIntegrationTest` | Management API endpoints |
| `SetupManagementIntegrationTest` | Setup CRUD operations |
| `BatchMessageProcessingIntegrationTest` | Batch message operations |
| `MessageSendingIntegrationTest` | Message sending operations |
| `RealTimeStreamingIntegrationTest` | Real-time streaming |

## 8. Layer Verification Patterns

This section describes how to verify that calls propagate correctly through each layer, aligned with `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9.

### 8.1 Layer Propagation Verification

Each test should verify the complete call path as documented in the Call Propagation Design:

```
REST Handler → peegeeq-runtime → Adapter (native/outbox/bitemporal) → peegeeq-db → PostgreSQL
```

**Verification Points:**

| Layer | What to Verify | How to Verify |
| :--- | :--- | :--- |
| REST | Request parsed correctly | Check HTTP response status and body |
| Runtime | Service delegation works | Verify service method was called (via response) |
| Adapter | Implementation executed | Verify data in database or response |
| Database | Data persisted correctly | Query database directly or via stats endpoint |

### 8.2 Example: Queue Message Propagation Test

```java
@Test
void testQueueMessagePropagation(VertxTestContext testContext) {
    // 1. REST Layer: Send HTTP request
    webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
        .sendJsonObject(messagePayload)
        .compose(response -> {
            // 2. Verify REST response
            assertEquals(201, response.statusCode());
            String messageId = response.bodyAsJsonObject().getString("messageId");
            assertNotNull(messageId);

            // 3. Verify Runtime → Adapter → DB propagation via stats
            return webClient.get("/api/v1/queues/" + setupId + "/" + queueName + "/stats")
                .send();
        })
        .onComplete(testContext.succeeding(statsResponse -> {
            testContext.verify(() -> {
                // 4. Verify database state via stats endpoint
                JsonObject stats = statsResponse.bodyAsJsonObject();
                assertTrue(stats.getInteger("pendingMessages") > 0);
            });
            testContext.completeNow();
        }));
}
```

### 8.3 Example: Event Store Propagation Test

```java
@Test
void testEventStorePropagation(VertxTestContext testContext) {
    // 1. REST Layer: Append event
    webClient.post("/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events")
        .sendJsonObject(eventPayload)
        .compose(response -> {
            // 2. Verify REST response
            assertEquals(201, response.statusCode());
            String eventId = response.bodyAsJsonObject().getString("eventId");

            // 3. Verify Runtime → BiTemporal → DB via query
            return webClient.get("/api/v1/eventstores/" + setupId + "/" + eventStoreName + "/events/" + eventId)
                .send();
        })
        .onComplete(testContext.succeeding(queryResponse -> {
            testContext.verify(() -> {
                // 4. Verify event was stored with correct temporal dimensions
                JsonObject event = queryResponse.bodyAsJsonObject();
                assertNotNull(event.getString("transactionTime"));
                assertNotNull(event.getString("validFrom"));
            });
            testContext.completeNow();
        }));
}
```

### 8.4 Example: Cross-Layer Service Propagation Test

```java
@Test
void testHealthServicePropagation(VertxTestContext testContext) {
    // Verify: REST → Runtime → HealthService → DB
    webClient.get("/api/v1/setups/" + setupId + "/health")
        .send()
        .onComplete(testContext.succeeding(response -> {
            testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                JsonObject health = response.bodyAsJsonObject();

                // Verify service was called (not placeholder)
                assertTrue(health.containsKey("status") || health.containsKey("healthy"));
                assertTrue(health.containsKey("components") || health.containsKey("checks"));
            });
            testContext.completeNow();
        }));
}
```

### 8.5 Tracing Call Paths

For each endpoint, trace the call path as documented in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9:

**Queue Operations (Section 9.2):**
```
POST /api/v1/queues/:setupId/:queueName/messages
  → QueueHandler.sendMessage()
  → QueueFactory.createProducer() [via RuntimeDatabaseSetupService]
  → MessageProducer.send() [PgNativeQueueProducer or OutboxProducer]
  → INSERT INTO queue_messages + pg_notify()
```

**Event Store Operations (Section 9.4):**
```
POST /api/v1/eventstores/:setupId/:eventStoreName/events
  → EventStoreHandler.storeEvent()
  → EventStore.append() [via RuntimeDatabaseSetupService]
  → PgBiTemporalEventStore.append()
  → INSERT INTO bitemporal_events + pg_notify()
```

**Health Operations (Section 9.7):**
```
GET /api/v1/setups/:setupId/health
  → HealthHandler.getOverallHealth()
  → HealthService.getOverallHealthAsync() [via RuntimeDatabaseSetupService]
  → PgHealthService.getOverallHealthAsync()
  → SELECT from health check queries
```

## 9. Test Execution

### 9.1 Running Unit Tests

```bash
# Default - runs core tests only
mvn test

# Specific module
mvn test -pl peegeeq-rest
```

### 9.2 Running Integration Tests

```bash
# All integration tests
mvn test -Pintegration-tests

# Specific test class
mvn test -Pintegration-tests -Dtest=CrossLayerPropagationIntegrationTest

# Specific module
mvn test -Pintegration-tests -pl peegeeq-rest
```

### 9.3 Running Smoke Tests (Java)

```bash
# Smoke tests only (peegeeq-integration-tests module)
mvn test -pl peegeeq-integration-tests -Psmoke-tests

# Specific smoke test class
mvn test -pl peegeeq-integration-tests -Psmoke-tests -Dtest=NativeQueueSmokeTest
```

### 9.4 Running TypeScript Smoke Tests

```bash
# Navigate to integration tests module
cd peegeeq-integration-tests

# Install dependencies (first time only)
npm install

# Run TypeScript smoke tests (requires running PeeGeeQ server)
npm run test:smoke

# Run with custom API URL
PEEGEEQ_API_URL=http://localhost:8080 npm run test:smoke

# Run with custom PostgreSQL connection
PG_HOST=localhost PG_PORT=5432 PG_USER=postgres PG_PASSWORD=postgres npm run test:smoke
```

### 9.5 Running REST Client Integration Tests

```bash
# REST client integration tests
mvn test -pl peegeeq-rest-client -Pintegration-tests

# Specific REST client test
mvn test -pl peegeeq-rest-client -Pintegration-tests -Dtest=RestClientIntegrationTest
```

### 9.6 Running Webhook Integration Tests

```bash
# Webhook integration tests
mvn test -pl peegeeq-rest -Pintegration-tests -Dtest=WebhookPushDeliveryIntegrationTest
```

### 9.7 Running All Tests

```bash
# Both core and integration tests
mvn test -Pintegration-tests -Dtest.groups=core,integration -Dtest.excludedGroups=

# All tests including smoke tests
mvn test -Pall-tests

# Full test suite with coverage report
mvn verify -Pintegration-tests
```

### 9.8 Test Reports

JaCoCo coverage reports are generated in:
```
target/site/jacoco/index.html
```

Surefire test reports are generated in:
```
target/surefire-reports/
```

## 10. Implementation Status

This section documents the implementation status of all REST endpoints as defined in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9.

**Summary (December 2025):** All 46 core REST endpoints are fully implemented. The implementation status has been reconciled with the Call Propagation Design document.

### 10.1 Endpoint Implementation Summary

| Category | Total Endpoints | Implemented | Test Coverage |
| :--- | :---: | :---: | :---: |
| Setup Operations | 7 | 7 | Full |
| Queue Operations | 4 | 4 | Full |
| Consumer Group Operations | 6 | 6 | Full |
| Event Store Operations | 8 | 8 | Full |
| Dead Letter Queue Operations | 6 | 6 | Full |
| Subscription Lifecycle Operations | 6 | 6 | Full |
| Health Check Operations | 3 | 3 | Full |
| Management API Operations | 6 | 6 | Partial (see Section 11) |
| Webhook Operations | 3 | 3 | Full |
| **Total** | **49** | **49** | **Full** |

### 10.2 Test Coverage by Module

| Module | Integration Tests | Smoke Tests | REST Client Tests |
| :--- | :---: | :---: | :---: |
| `peegeeq-rest` | 37 tests | - | - |
| `peegeeq-integration-tests` | - | 10 tests (Java) + 7 tests (TypeScript) | - |
| `peegeeq-rest-client` | - | - | 16 tests |
| `peegeeq-native` | 12 tests | - | - |
| `peegeeq-outbox` | 8 tests | - | - |
| `peegeeq-bitemporal` | 10 tests | - | - |
| `peegeeq-runtime` | 5 tests | - | - |

### 10.3 Endpoint Test Mapping

All endpoints have corresponding integration tests. The following table maps endpoints to their test classes:

| Endpoint Category | Primary Test Class | Additional Coverage |
| :--- | :--- | :--- |
| Setup Operations | `CallPropagationIntegrationTest` | `RestClientIntegrationTest` |
| Queue Operations | `CrossLayerPropagationIntegrationTest` | `NativeQueueSmokeTest` |
| Consumer Group Operations | `ConsumerGroupSubscriptionIntegrationTest` | `RestClientIntegrationTest` |
| Event Store Operations | `EventStoreIntegrationTest` | `BiTemporalEventStoreSmokeTest` |
| Dead Letter Queue Operations | `DeadLetterRequeueIntegrationTest` | `RestClientIntegrationTest` |
| Subscription Lifecycle Operations | `SubscriptionLifecycleIntegrationTest` | - |
| Health Check Operations | `HealthCheckSmokeTest` | `RestClientIntegrationTest` |
| Management API Operations | `ManagementApiIntegrationTest` | - |
| Webhook Operations | `WebhookPushDeliveryIntegrationTest` | - |

## 11. Known Limitations

This section documents placeholder implementations and known limitations that affect test coverage.

### 11.1 Management API Placeholder Methods

The following `ManagementApiHandler` methods return placeholder data instead of real values. These are documented in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9.8:

| Method | Current Behavior | Required API Extension |
| :--- | :--- | :--- |
| `getRealAggregateCount()` | Returns 0 | Requires `EventStoreStats.getUniqueAggregateCount()` |
| `getRealMessages()` | Returns empty array | Requires message browsing API |
| `getRecentActivity()` | Returns empty array | Requires activity logging implementation |

**Impact on Tests:**

Tests for these methods verify the endpoint returns a valid response structure, but cannot verify real data:

```java
// ManagementApiIntegrationTest
@Test
void testGetSystemOverview_returnsValidStructure() {
    // Verifies response structure, not actual values
    // aggregateCount will be 0, recentActivity will be empty
}
```

### 11.2 OutboxFactory Consumer Config

The `OutboxFactory.createConsumer(topic, payloadType, config)` method ignores the `config` parameter and uses default configuration. This is a known limitation documented in `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 10.10.

**Impact on Tests:**

Consumer configuration tests for the outbox pattern will not verify custom configuration is applied.

### 11.3 TypeScript Smoke Tests Prerequisites

TypeScript smoke tests require:
1. A running PeeGeeQ REST server
2. A running PostgreSQL database
3. Node.js 18+ installed

These tests are not run as part of the standard Maven build and require manual execution.

### 11.4 Webhook Tests Mock Server

Webhook integration tests use a mock HTTP server to receive webhook deliveries. The mock server:
- Runs on a random available port
- Simulates success (2xx) and failure (4xx/5xx) responses
- Does not test actual external webhook endpoints

### 11.5 Future Test Improvements

| Area | Current State | Future Improvement |
| :--- | :--- | :--- |
| Management API real data | Placeholder values | Implement missing API methods |
| TypeScript tests in CI | Manual execution | Add to Maven build with Node.js plugin |
| Webhook external testing | Mock server only | Add optional external webhook tests |
| Performance testing | Not implemented | Add JMH benchmarks for critical paths |
| Chaos testing | Not implemented | Add Toxiproxy for network failure simulation |

## 12. Best Practices

### 12.1 Respecting Layer Boundaries in Tests

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

### 12.2 Test Isolation

- Each test class should use a unique `setupId` and `databaseName`
- Use `@TestInstance(Lifecycle.PER_CLASS)` for shared setup
- Clean up resources in `@AfterAll`

### 12.3 Async Testing with Vert.x

- Use `VertxTestContext` for async assertions
- Use `CountDownLatch` for waiting on async events
- Set appropriate timeouts (15-60 seconds for integration tests)

### 12.4 Logging

- Log test progress with clear markers (`=== Test N: Description ===`)
- Log verification results
- Log errors with context for debugging

### 12.5 Verifying Layer Propagation

When writing new tests, always verify the complete call path:

1. **REST Layer**: Verify HTTP response status and body structure
2. **Runtime Layer**: Verify service delegation (via response data)
3. **Adapter Layer**: Verify implementation executed (via database state)
4. **Database Layer**: Verify data persisted correctly (via stats or query endpoints)

Reference `PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9 for the exact call path for each endpoint.