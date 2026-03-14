# Plugin Model for Transactional Patterns

**Document ID:** DESIGN-2025-002
**Version:** 1.0
**Date:** December 23, 2025
**Author:** Mark Andrew Ray-Smith, Cityline Ltd
**Related:** DESIGN-2025-001 (Transactional REST API Design)

---

## Table of Contents

1. [Summary](#summary)
2. [Problem Statement](#problem-statement)
3. [Solution Design](#solution-design)
4. [Architecture](#architecture)
5. [Module Structure](#module-structure)
6. [API Design](#api-design)
7. [Plugin Implementations](#plugin-implementations)
8. [Configuration](#configuration)
9. [Implementation Plan](#implementation-plan)
10. [Benefits](#benefits)

---

## Summary

### Overview

This design specifies a **plugin-based architecture** for implementing the four transactional patterns defined in DESIGN-2025-001. The plugin model enables:
- Clean separation of concerns
- Independent development and testing of each pattern
- Runtime pattern selection based on configuration
- Extensibility for future patterns

### Problem

The four transactional patterns (Callback, Saga, Reservation, Inversion) share common concerns but have different execution strategies. Without a plugin model:
- ❌ Code duplication across patterns
- ❌ Tight coupling between pattern implementations
- ❌ Difficult to add new patterns
- ❌ Hard to test patterns in isolation
- ❌ Complex runtime pattern selection logic

### Solution

Create a **generic transactional coordination framework** where different execution strategies are pluggable implementations of a common interface:

- ✅ **Core Abstraction** - `TransactionalExecutor` interface
- ✅ **Plugin Registry** - Runtime pattern selection
- ✅ **Modular Design** - Each pattern in separate module
- ✅ **Configuration-Driven** - Pattern selection per deployment
- ✅ **Extensible** - Add new patterns without changing core code

---

## Problem Statement

### Current Challenge

The Transactional REST API Design (DESIGN-2025-001) defines four distinct patterns:

1. **Pattern 1: Callback Hook** - HTTP callback within transaction
2. **Pattern 2: Saga Orchestration** - Multi-step saga with compensation
3. **Pattern 3: Reservation** - Async two-phase with pending command log
4. **Pattern 4: Inversion** - Domain-first with PeeGeeQ as participant

Each pattern has:
- **Common concerns:** Transaction coordination, event store append, outbox send
- **Different execution strategies:** Synchronous vs async, ACID vs eventual consistency
- **Different deployment requirements:** Same database vs separate databases

### Without Plugin Model

```java
// Monolithic approach - all patterns in one class
public class TransactionalService {
    public Future<Result> execute(Request request) {
        if (request.pattern.equals("callback")) {
            // Callback logic here
        } else if (request.pattern.equals("saga")) {
            // Saga logic here
        } else if (request.pattern.equals("reservation")) {
            // Reservation logic here
        } else if (request.pattern.equals("inversion")) {
            // Inversion logic here
        }
    }
}
```

**Problems:**
- ❌ Violates Single Responsibility Principle
- ❌ Difficult to test individual patterns
- ❌ Hard to add new patterns
- ❌ Complex conditional logic

---

## Solution Design

### Core Abstraction

Define a common interface that all transactional patterns implement:

```java
/**
 * Core abstraction for transactional execution patterns.
 * Each pattern (Callback, Saga, Reservation, Inversion) implements this interface.
 */
public interface TransactionalExecutor {

    /**
     * Execute a transactional operation using this pattern's strategy.
     *
     * @param request The transaction request containing domain operation, event, and outbox
     * @return Future containing the transaction result
     */
    Future<TransactionResult> execute(TransactionRequest request);

    /**
     * Get the pattern name (e.g., "callback", "saga", "reservation", "inversion")
     */
    String getPatternName();

    /**
     * Check if this executor supports the given transaction context.
     * Used for auto-selection of appropriate pattern.
     *
     * @param context Transaction context (database topology, consistency requirements, etc.)
     * @return true if this executor can handle the context
     */
    boolean supports(TransactionContext context);

    /**
     * Get pattern metadata (consistency guarantees, performance characteristics, etc.)
     */
    PatternMetadata getMetadata();
}
```

### Transaction Request (Generic)

```java
/**
 * Generic transaction request that works for all patterns.
 * PeeGeeQ remains 100% generic - no domain-specific concepts.
 */
public class TransactionRequest {

    /** Setup ID for database connection */
    String setupId;

    /** Domain operation (generic representation) */
    DomainOperation domainOperation;

    /** Event store operation */
    EventStoreOperation eventOperation;

    /** Outbox operation */
    OutboxOperation outboxOperation;

    /** Pattern-specific metadata */
    Map<String, Object> metadata;
}
```

### Transaction Context

```java
/**
 * Context information used for pattern selection.
 */
public class TransactionContext {

    /** Database topology */
    DatabaseTopology topology;  // SAME_DATABASE, SEPARATE_DATABASES

    /** Consistency requirements */
    ConsistencyLevel consistencyLevel;  // STRONG_ACID, EVENTUAL

    /** Performance requirements */
    PerformanceProfile performanceProfile;  // LOW_LATENCY, HIGH_THROUGHPUT

    /** Deployment configuration */
    DeploymentConfig deploymentConfig;
}
```

---

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ REST API Layer (peegeeq-rest)                                   │
│                                                                 │
│  POST /api/v1/transactional/execute                            │
│  POST /api/v1/transactional/execute?pattern=callback           │
│  POST /api/v1/transactional/execute?pattern=saga               │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ Plugin Registry (peegeeq-transactional-runtime)                 │
│                                                                 │
│  - Pattern selection (auto or explicit)                        │
│  - Plugin lifecycle management                                 │
│  - Configuration loading                                       │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ TransactionalExecutor Interface (peegeeq-transactional-api)    │
│                                                                 │
│  - execute(TransactionRequest)                                 │
│  - supports(TransactionContext)                                │
│  - getPatternName()                                            │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┬───────────────┐
         │               │               │               │
         ▼               ▼               ▼               ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│  Callback   │ │    Saga     │ │ Reservation │ │  Inversion  │
│   Plugin    │ │   Plugin    │ │   Plugin    │ │   Plugin    │
│             │ │             │ │             │ │             │
│  Pattern 1  │ │  Pattern 2  │ │  Pattern 3  │ │  Pattern 4  │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
```

### Plugin Registry

```java
/**
 * Central registry for transactional executor plugins.
 * Manages plugin lifecycle and pattern selection.
 */
public class TransactionalExecutorRegistry {

    private final Map<String, TransactionalExecutor> executors = new ConcurrentHashMap<>();
    private final PatternSelectionStrategy selectionStrategy;

    /**
     * Register a transactional executor plugin.
     */
    public void register(TransactionalExecutor executor) {
        String patternName = executor.getPatternName();
        if (executors.containsKey(patternName)) {
            throw new IllegalStateException("Executor already registered: " + patternName);
        }
        executors.put(patternName, executor);
        logger.info("Registered transactional executor: {}", patternName);
    }

    /**
     * Get executor by explicit pattern name.
     */
    public TransactionalExecutor getExecutor(String patternName) {
        TransactionalExecutor executor = executors.get(patternName);
        if (executor == null) {
            throw new IllegalArgumentException("Unknown pattern: " + patternName);
        }
        return executor;
    }

    /**
     * Auto-select executor based on transaction context.
     */
    public TransactionalExecutor selectExecutor(TransactionContext context) {
        return executors.values().stream()
            .filter(executor -> executor.supports(context))
            .max(Comparator.comparing(e -> e.getMetadata().getPriority()))
            .orElseThrow(() -> new IllegalStateException(
                "No executor supports context: " + context
            ));
    }

    /**
     * Get all registered executors.
     */
    public Collection<TransactionalExecutor> getAllExecutors() {
        return Collections.unmodifiableCollection(executors.values());
    }
}
```

### Pattern Selection Strategy

```java
/**
 * Strategy for selecting the appropriate pattern.
 */
public interface PatternSelectionStrategy {

    /**
     * Select the best pattern for the given context.
     */
    TransactionalExecutor select(
        TransactionContext context,
        Collection<TransactionalExecutor> availableExecutors
    );
}

/**
 * Default selection strategy based on database topology and consistency requirements.
 */
public class DefaultPatternSelectionStrategy implements PatternSelectionStrategy {

    @Override
    public TransactionalExecutor select(
        TransactionContext context,
        Collection<TransactionalExecutor> availableExecutors
    ) {
        // Priority order:
        // 1. Same DB + Strong Consistency → Inversion (Pattern 4)
        // 2. Same DB + Eventual Consistency → Reservation (Pattern 3)
        // 3. Separate DB + Eventual Consistency → Saga (Pattern 2)
        // 4. Fallback → Callback (Pattern 1)

        if (context.getTopology() == DatabaseTopology.SAME_DATABASE) {
            if (context.getConsistencyLevel() == ConsistencyLevel.STRONG_ACID) {
                return findExecutor(availableExecutors, "inversion");
            } else {
                return findExecutor(availableExecutors, "reservation");
            }
        } else {
            return findExecutor(availableExecutors, "saga");
        }
    }

    private TransactionalExecutor findExecutor(
        Collection<TransactionalExecutor> executors,
        String patternName
    ) {
        return executors.stream()
            .filter(e -> e.getPatternName().equals(patternName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Pattern not available: " + patternName
            ));
    }
}
```

---



## Module Structure

### Layered Module Architecture

```
peegeeq-transactional/
│
├── peegeeq-transactional-api/
│   ├── src/main/java/dev/mars/peegeeq/transactional/api/
│   │   ├── TransactionalExecutor.java          # Core interface
│   │   ├── TransactionRequest.java             # Generic request
│   │   ├── TransactionResult.java              # Generic result
│   │   ├── TransactionContext.java             # Context for selection
│   │   ├── DomainOperation.java                # Domain operation abstraction
│   │   ├── EventStoreOperation.java            # Event store operation
│   │   ├── OutboxOperation.java                # Outbox operation
│   │   ├── PatternMetadata.java                # Pattern characteristics
│   │   └── exceptions/
│   │       ├── TransactionExecutionException.java
│   │       └── PatternNotSupportedException.java
│   └── pom.xml
│
├── peegeeq-transactional-callback/              # Pattern 1: Callback Hook
│   ├── src/main/java/dev/mars/peegeeq/transactional/callback/
│   │   ├── CallbackExecutor.java               # Callback implementation
│   │   ├── CallbackHttpClient.java             # HTTP client for callbacks
│   │   └── CallbackConfig.java                 # Callback configuration
│   └── pom.xml
│
├── peegeeq-transactional-saga/                  # Pattern 2: Saga Orchestration
│   ├── src/main/java/dev/mars/peegeeq/transactional/saga/
│   │   ├── SagaExecutor.java                   # Saga implementation
│   │   ├── SagaOrchestrator.java               # Saga orchestration logic
│   │   ├── SagaStep.java                       # Saga step abstraction
│   │   ├── CompensationHandler.java            # Compensation logic
│   │   └── SagaConfig.java                     # Saga configuration
│   └── pom.xml
│
├── peegeeq-transactional-reservation/           # Pattern 3: Reservation (Async)
│   ├── src/main/java/dev/mars/peegeeq/transactional/reservation/
│   │   ├── ReservationExecutor.java            # Reservation implementation
│   │   ├── CommandLog.java                     # Pending command log
│   │   ├── AsyncWorker.java                    # Background worker
│   │   └── ReservationConfig.java              # Reservation configuration
│   └── pom.xml
│
├── peegeeq-transactional-inversion/             # Pattern 4: Inversion (Domain-First)
│   ├── src/main/java/dev/mars/peegeeq/transactional/inversion/
│   │   ├── InversionExecutor.java              # Inversion implementation
│   │   ├── TransactionParticipant.java         # PeeGeeQ as participant
│   │   └── InversionConfig.java                # Inversion configuration
│   └── pom.xml
│
└── peegeeq-transactional-runtime/               # Runtime + Registry
    ├── src/main/java/dev/mars/peegeeq/transactional/runtime/
    │   ├── TransactionalExecutorRegistry.java  # Plugin registry
    │   ├── PatternSelectionStrategy.java       # Selection strategy
    │   ├── DefaultPatternSelectionStrategy.java
    │   ├── TransactionalService.java           # Service facade
    │   └── config/
    │       └── TransactionalConfig.java        # Runtime configuration
    └── pom.xml
```

### Module Dependencies

```
peegeeq-transactional-api (Pure interfaces, no dependencies)
    ↑
    ├── peegeeq-transactional-callback (depends on api)
    ├── peegeeq-transactional-saga (depends on api)
    ├── peegeeq-transactional-reservation (depends on api)
    ├── peegeeq-transactional-inversion (depends on api)
    └── peegeeq-transactional-runtime (depends on api + all plugins)
```

### POM Configuration

**Parent POM (peegeeq-transactional/pom.xml):**
```xml
<project>
    <groupId>dev.mars.peegeeq</groupId>
    <artifactId>peegeeq-transactional</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>peegeeq-transactional-api</module>
        <module>peegeeq-transactional-callback</module>
        <module>peegeeq-transactional-saga</module>
        <module>peegeeq-transactional-reservation</module>
        <module>peegeeq-transactional-inversion</module>
        <module>peegeeq-transactional-runtime</module>
    </modules>
</project>
```

---

## API Design

### REST Endpoints

#### 1. Execute with Explicit Pattern

```http
POST /api/v1/transactional/execute?pattern=inversion
Content-Type: application/json

{
  "setupId": "peegeeq-main",
  "domainOperation": {
    "type": "HTTP_CALL",
    "url": "https://domain-app.com/api/orders",
    "method": "POST",
    "payload": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    }
  },
  "eventStore": {
    "eventStoreName": "order_events",
    "eventType": "OrderCreated",
    "eventData": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    },
    "validFrom": "2025-12-23T10:00:00Z"
  },
  "outbox": {
    "queueName": "orders",
    "message": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    }
  }
}
```

**Response:**
```json
{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "patternUsed": "inversion",
  "transactionTime": "2025-12-23T12:00:00.123Z",
  "domainResult": {
    "orderId": "ORD-001"
  },
  "eventId": "evt-789",
  "outboxMessageId": "msg-456"
}
```

#### 2. Execute with Auto-Selection

```http
POST /api/v1/transactional/execute
Content-Type: application/json

{
  "setupId": "peegeeq-main",
  "context": {
    "topology": "SAME_DATABASE",
    "consistencyLevel": "STRONG_ACID",
    "performanceProfile": "LOW_LATENCY"
  },
  "domainOperation": { ... },
  "eventStore": { ... },
  "outbox": { ... }
}
```

**Response:**
```json
{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "patternUsed": "inversion",
  "patternAutoSelected": true,
  "transactionTime": "2025-12-23T12:00:00.123Z",
  "domainResult": { ... },
  "eventId": "evt-789",
  "outboxMessageId": "msg-456"
}
```

#### 3. List Available Patterns

```http
GET /api/v1/transactional/patterns
```

**Response:**
```json
{
  "patterns": [
    {
      "name": "callback",
      "description": "HTTP callback within transaction",
      "consistency": "WEAK",
      "performance": "LOW",
      "topology": "SAME_DATABASE",
      "useCase": "Low-volume prototypes",
      "recommended": false
    },
    {
      "name": "saga",
      "description": "Saga orchestration with compensation",
      "consistency": "EVENTUAL",
      "performance": "MEDIUM",
      "topology": "SEPARATE_DATABASES",
      "useCase": "Separate databases",
      "recommended": true
    },
    {
      "name": "reservation",
      "description": "Async two-phase with pending command log",
      "consistency": "EVENTUAL",
      "performance": "HIGH",
      "topology": "ANY",
      "useCase": "High-throughput microservices",
      "recommended": true
    },
    {
      "name": "inversion",
      "description": "Domain-first with PeeGeeQ as participant",
      "consistency": "STRONG_ACID",
      "performance": "BEST",
      "topology": "SAME_DATABASE",
      "useCase": "Shared database (RECOMMENDED)",
      "recommended": true
    }
  ]
}
```

---

## Plugin Implementations

### Pattern 1: Callback Executor

```java
/**
 * Callback pattern implementation.
 * Executes HTTP callback within database transaction.
 */
public class CallbackExecutor implements TransactionalExecutor {

    private final ConnectionProvider connectionProvider;
    private final EventStore eventStore;
    private final OutboxProducer outboxProducer;
    private final CallbackHttpClient httpClient;

    @Override
    public Future<TransactionResult> execute(TransactionRequest request) {
        return connectionProvider.withTransaction(request.getSetupId(), connection -> {

            // Step 1: Execute HTTP callback to domain service
            return httpClient.post(
                request.getDomainOperation().getUrl(),
                request.getDomainOperation().getPayload()
            )

            // Step 2: Append to event store (same transaction)
            .compose(domainResult ->
                eventStore.appendInTransaction(
                    request.getEventOperation().getEventType(),
                    request.getEventOperation().getEventData(),
                    request.getEventOperation().getValidFrom(),
                    connection
                )
                .map(eventId -> Pair.of(domainResult, eventId))
            )

            // Step 3: Send to outbox (same transaction)
            .compose(pair ->
                outboxProducer.sendInTransaction(
                    request.getOutboxOperation().getMessage(),
                    connection
                )
                .map(messageId -> new TransactionResult(
                    pair.getLeft(),  // domain result
                    pair.getRight(), // event ID
                    messageId        // outbox message ID
                ))
            );
        });
    }

    @Override
    public String getPatternName() {
        return "callback";
    }

    @Override
    public boolean supports(TransactionContext context) {
        return context.getTopology() == DatabaseTopology.SAME_DATABASE;
    }

    @Override
    public PatternMetadata getMetadata() {
        return PatternMetadata.builder()
            .name("callback")
            .consistency(ConsistencyLevel.WEAK)
            .performance(PerformanceLevel.LOW)
            .priority(1)  // Lowest priority
            .build();
    }
}
```

### Pattern 2: Saga Executor

```java
/**
 * Saga pattern implementation.
 * Orchestrates multi-step saga with compensation.
 */
public class SagaExecutor implements TransactionalExecutor {

    private final SagaOrchestrator orchestrator;
    private final EventStore eventStore;
    private final OutboxProducer outboxProducer;

    @Override
    public Future<TransactionResult> execute(TransactionRequest request) {

        // Build saga steps
        List<SagaStep> steps = List.of(
            // Step 1: Domain operation
            new HttpCallStep(
                request.getDomainOperation().getUrl(),
                request.getDomainOperation().getPayload()
            ),
            // Step 2: Event store append
            new EventStoreStep(
                eventStore,
                request.getEventOperation()
            ),
            // Step 3: Outbox send
            new OutboxStep(
                outboxProducer,
                request.getOutboxOperation()
            )
        );

        // Execute saga
        return orchestrator.execute(steps);
    }

    @Override
    public String getPatternName() {
        return "saga";
    }

    @Override
    public boolean supports(TransactionContext context) {
        return context.getTopology() == DatabaseTopology.SEPARATE_DATABASES;
    }

    @Override
    public PatternMetadata getMetadata() {
        return PatternMetadata.builder()
            .name("saga")
            .consistency(ConsistencyLevel.EVENTUAL)
            .performance(PerformanceLevel.MEDIUM)
            .priority(3)
            .build();
    }
}
```

### Pattern 3: Reservation Executor

```java
/**
 * Reservation pattern implementation.
 * Uses pending command log to decouple HTTP calls from DB transactions.
 */
public class ReservationExecutor implements TransactionalExecutor {

    private final CommandLog commandLog;
    private final AsyncWorker asyncWorker;

    @Override
    public Future<TransactionResult> execute(TransactionRequest request) {

        // Phase 1: Reserve (fast DB commit)
        return commandLog.reserve(request)
            .compose(commandId -> {

                // Phase 2: Trigger async execution
                asyncWorker.submit(commandId);

                // Return immediately with 202 Accepted
                return Future.succeededFuture(
                    TransactionResult.accepted(commandId)
                );
            });
    }

    @Override
    public String getPatternName() {
        return "reservation";
    }

    @Override
    public boolean supports(TransactionContext context) {
        return context.getPerformanceProfile() == PerformanceProfile.HIGH_THROUGHPUT;
    }

    @Override
    public PatternMetadata getMetadata() {
        return PatternMetadata.builder()
            .name("reservation")
            .consistency(ConsistencyLevel.EVENTUAL)
            .performance(PerformanceLevel.HIGH)
            .priority(4)
            .build();
    }
}
```

### Pattern 4: Inversion Executor

```java
/**
 * Inversion pattern implementation.
 * Domain application manages transaction, PeeGeeQ participates.
 */
public class InversionExecutor implements TransactionalExecutor {

    private final TransactionParticipant participant;

    @Override
    public Future<TransactionResult> execute(TransactionRequest request) {

        // This pattern is inverted - domain app calls PeeGeeQ
        // This executor provides the "participation" API

        return participant.participate(
            request.getEventOperation(),
            request.getOutboxOperation()
        );
    }

    @Override
    public String getPatternName() {
        return "inversion";
    }

    @Override
    public boolean supports(TransactionContext context) {
        return context.getTopology() == DatabaseTopology.SAME_DATABASE
            && context.getConsistencyLevel() == ConsistencyLevel.STRONG_ACID;
    }

    @Override
    public PatternMetadata getMetadata() {
        return PatternMetadata.builder()
            .name("inversion")
            .consistency(ConsistencyLevel.STRONG_ACID)
            .performance(PerformanceLevel.BEST)
            .priority(5)  // Highest priority
            .build();
    }
}
```

---

## Configuration

### Runtime Configuration

```yaml
# application.yml
peegeeq:
  transactional:
    # Pattern selection strategy
    selection-strategy: auto  # auto, explicit

    # Default pattern (when explicit)
    default-pattern: inversion

    # Enabled patterns
    enabled-patterns:
      - callback
      - saga
      - reservation
      - inversion

    # Pattern-specific configuration
    callback:
      timeout-ms: 5000
      retry-attempts: 3

    saga:
      max-steps: 10
      compensation-timeout-ms: 10000

    reservation:
      command-log-table: command_log
      worker-threads: 4
      poll-interval-ms: 100

    inversion:
      allow-external-transactions: true
```

### Setup-Specific Configuration

```json
{
  "setupId": "peegeeq-main",
  "transactional": {
    "preferredPattern": "inversion",
    "topology": "SAME_DATABASE",
    "consistencyLevel": "STRONG_ACID",
    "performanceProfile": "LOW_LATENCY"
  }
}
```

---

## Implementation Plan

### Phase 1: Core Infrastructure (Week 1-2)

**Tasks:**
1. Create `peegeeq-transactional-api` module
   - Define `TransactionalExecutor` interface
   - Define `TransactionRequest`, `TransactionResult`, `TransactionContext`
   - Define exception hierarchy

2. Create `peegeeq-transactional-runtime` module
   - Implement `TransactionalExecutorRegistry`
   - Implement `DefaultPatternSelectionStrategy`
   - Implement `TransactionalService` facade

3. Add REST endpoints to `peegeeq-rest`
   - `POST /api/v1/transactional/execute`
   - `GET /api/v1/transactional/patterns`

**Deliverables:**
- ✅ Core plugin infrastructure
- ✅ Pattern registry and selection
- ✅ REST API endpoints

### Phase 2: Pattern Implementations (Week 3-6)

**Week 3: Pattern 4 (Inversion)**
- Create `peegeeq-transactional-inversion` module
- Implement `InversionExecutor`
- Integration tests with Spring Boot example

**Week 4: Pattern 3 (Reservation)**
- Create `peegeeq-transactional-reservation` module
- Implement `ReservationExecutor`
- Implement command log and async worker
- Integration tests

**Week 5: Pattern 2 (Saga)**
- Create `peegeeq-transactional-saga` module
- Implement `SagaExecutor` and `SagaOrchestrator`
- Implement compensation logic
- Integration tests

**Week 6: Pattern 1 (Callback)**
- Create `peegeeq-transactional-callback` module
- Implement `CallbackExecutor`
- Integration tests

**Deliverables:**
- ✅ All four pattern implementations
- ✅ Comprehensive integration tests
- ✅ Pattern comparison benchmarks

### Phase 3: Documentation and Examples (Week 7)

**Tasks:**
1. Update design documents
2. Create example applications for each pattern
3. Performance benchmarking
4. Migration guide

**Deliverables:**
- ✅ Complete documentation
- ✅ Working examples
- ✅ Performance analysis

---

## Benefits

### Architectural Benefits

✅ **Separation of Concerns**
- Each pattern isolated in separate module
- Clear boundaries and responsibilities
- Independent testing and deployment

✅ **Extensibility**
- Add new patterns without changing core code
- Plugin-based architecture supports future patterns
- Open/Closed Principle compliance

✅ **Testability**
- Each pattern tested in isolation
- Mock implementations for testing
- Integration tests per pattern

✅ **Maintainability**
- Clear module structure
- Single Responsibility Principle
- Easy to understand and modify

### Operational Benefits

✅ **Flexibility**
- Runtime pattern selection
- Configuration-driven deployment
- Support multiple patterns simultaneously

✅ **Performance**
- Choose optimal pattern per use case
- Pattern-specific optimizations
- Performance profiling per pattern

✅ **Reliability**
- Isolated failure domains
- Pattern-specific error handling
- Graceful degradation

### Developer Benefits

✅ **Clear API**
- Single interface for all patterns
- Consistent request/response format
- Auto-selection simplifies client code

✅ **Documentation**
- Pattern comparison table
- Use case guidance
- Example implementations

✅ **Migration Path**
- Start with simple pattern
- Migrate to optimal pattern
- No breaking changes

---

## Conclusion

The plugin model provides a **clean, extensible architecture** for implementing the four transactional patterns. Key advantages:

1. **Modularity** - Each pattern in separate module
2. **Flexibility** - Runtime pattern selection
3. **Extensibility** - Easy to add new patterns
4. **Testability** - Isolated testing per pattern
5. **Performance** - Optimal pattern per use case

This design maintains **PeeGeeQ's 100% generic nature** while providing powerful transactional coordination capabilities through a plugin-based architecture.

---

