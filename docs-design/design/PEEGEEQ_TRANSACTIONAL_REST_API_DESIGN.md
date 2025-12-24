# Transactional REST API Design

**Document ID:** DESIGN-2025-001
**Version:** 1.0
**Date:** December 22, 2025
**Author:** Mark Andrew Ray-Smith Cityline Ltd

---

## Table of Contents

1. [Summary](#executive-summary)
2. [Pattern Overview](#pattern-overview)
3. [Problem Statement](#problem-statement)
4. [Solution Design](#solution-design)
5. [Architecture](#architecture)
6. [API Design](#api-design)
7. [Implementation Plan](#implementation-plan)
8. [Testing Strategy](#testing-strategy)
9. [Security Considerations](#security-considerations)
10. [Performance Analysis](#performance-analysis)
11. [Migration Path](#migration-path)
12. [Alternatives Considered](#alternatives-considered)
13. [Decision Points](#decision-points)
14. [References](#references)
15. [Appendices](#appendices)

---

## Summary

### Overview

This design specifies **four transactional patterns** that enable clients to execute business operations and event logging in coordinated transactions. The design maintains PeeGeeQ's 100% generic nature while supporting different deployment architectures and consistency requirements.

### Problem

Current REST API requires separate HTTP calls for business operations and event logging, resulting in:
- ❌ No ACID guarantees between operations
- ❌ Potential for partial failures and data inconsistency
- ❌ Complex client-side coordination logic
- ❌ Network failures can cause orphaned data

### Solution

Four transactional patterns based on database deployment architecture and consistency requirements:

**Pattern 1: Callback Hook (Same Database)**
- Domain tables and PeeGeeQ tables in same PostgreSQL database
- Domain application exposes REST callback endpoint
- PeeGeeQ calls callback within transaction
- Single ACID transaction across all operations
- **Use Case:** Domain and PeeGeeQ tables co-located

**Pattern 2: Saga Orchestration (Separate Databases)**
- Domain tables and PeeGeeQ tables in separate databases
- Saga orchestration with automatic compensation
- Eventual consistency guarantees
- **Use Case:** Domain and PeeGeeQ tables separated

**Pattern 3: Reservation (Async Two-Phase)**
- Uses a "pending command" log to decouple HTTP calls from DB transactions
- Prevents connection starvation
- Eventual consistency
- **Use Case:** High-throughput, separate databases

**Pattern 4: Inversion (Domain-First / Shared DB)**
- Domain application manages the transaction
- PeeGeeQ is used as a library/sidecar within the domain transaction
- True ACID, best performance
- **Use Case:** Shared database, strong consistency requirement

### Benefits

- ✅ **PeeGeeQ Remains Generic** - No domain-specific concepts in core system
- ✅ **ACID Guarantees** - Pattern 4 provides true ACID with best performance
- ✅ **Eventual Consistency** - Patterns 2 & 3 provide saga-based coordination
- ✅ **Flexible Deployment** - Supports co-located and separated architectures
- ✅ **Pure REST API** - Patterns 1, 2, 3 accessible via HTTP (no SDK required)
- ✅ **Technology Agnostic** - Works with any client technology
- ✅ **Audit Trail** - Bi-temporal event store captures complete history
- ✅ **Automatic Compensation** - Pattern 2 handles rollback via saga compensation
- ✅ **High Throughput** - Pattern 3 prevents connection starvation
- ✅ **Simple Integration** - Pattern 4 uses library/sidecar for domain-first approach

---

## Pattern Overview

### Architectural Foundation

This design aligns with the PeeGeeQ layered architecture documented in `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md`:
- ✅ **peegeeq-api** - Pure contracts layer (interfaces: `ConnectionProvider`, `EventStore`, `OutboxProducer`)
- ✅ **peegeeq-runtime** - Composition layer that wires together all implementations
- ✅ **peegeeq-rest** - REST layer that exposes `peegeeq-runtime` services over HTTP
- ✅ **Transaction Participation** - Uses existing `appendInTransaction()` and `sendInTransaction()` methods that accept `SqlConnection` parameter
- ✅ **Vert.x 5.x Native** - Uses Vert.x `Future` and `SqlConnection` types as primary API (not implementation details)

### Pattern Overview

This design specifies **four transactional patterns** determined by **database deployment architecture** and **consistency requirements**:

---

## Core Architectural Decision: Database Schema Separation and Consistency Requirements

The design accounts for **four deployment patterns** based on where domain tables reside and consistency requirements:

**Pattern 1: Callback Hook (Same Database)**
- Domain tables and PeeGeeQ tables in same PostgreSQL database
- Domain application exposes REST callback endpoint
- PeeGeeQ calls callback within transaction
- Single ACID transaction across all operations
- **Use Case:** Domain and PeeGeeQ tables co-located

**Pattern 2: Saga Orchestration (Separate Databases)**
- Domain tables in **separate database** from PeeGeeQ tables
- Saga orchestration with automatic compensation
- Eventual consistency guarantees
- **Use Case:** Domain and PeeGeeQ tables separated

**Pattern 3: Reservation (Async Two-Phase)**
- Uses a "pending command" log to decouple HTTP calls from DB transactions
- Prevents connection starvation
- Eventual consistency
- **Use Case:** High-throughput, separate databases

**Pattern 4: Inversion (Domain-First / Shared DB)**
- Domain application manages the transaction
- PeeGeeQ is used as a library/sidecar within the domain transaction
- True ACID, best performance
- **Use Case:** Shared database, strong consistency requirement

---

### Pattern 1: Callback Hook (Same Database)

#### Deployment Architecture

```
┌─────────────────────────────────────────────────────────┐
│ PostgreSQL Database (Single Instance)                   │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │ Domain Schema (Customer Tables)                    │ │
│  │ - orders                                           │ │
│  │ - customers                                        │ │
│  │ - inventory                                        │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │ PeeGeeQ Schema (Infrastructure Tables)             │ │
│  │ - event_store                                      │ │
│  │ - outbox                                           │ │
│  │ - consumer_groups                                  │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  Single Transaction Boundary (ACID)                     │
└─────────────────────────────────────────────────────────┘
```

#### Generic Callback Hook Pattern

**PeeGeeQ provides generic endpoint:**

```http
POST /api/v1/transactional/execute-with-callback
Content-Type: application/json

{
  "callbackUrl": "https://customer-app.com/api/order-handler",
  "callbackMethod": "POST",
  "callbackPayload": {
    "orderId": "ORD-001",
    "customerId": "CUST-001",
    "amount": 99.99,
    "items": [...]
  },
  "eventStore": {
    "setupId": "peegeeq-main",
    "eventStoreName": "order_events",
    "eventType": "OrderCreated",
    "eventData": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    },
    "validFrom": "2025-12-22T10:00:00Z"
  },
  "outbox": {
    "setupId": "peegeeq-main",
    "queueName": "orders",
    "message": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    }
  }
}
```

**Transaction Flow:**
```
BEGIN TRANSACTION (Single PostgreSQL Transaction)
  ├─► Step 1: HTTP POST to customer callback URL
  │   └─► Customer handler: INSERT INTO orders (...)
  │   └─► Customer returns: { "success": true, "orderId": "ORD-001" }
  │
  ├─► Step 2: PeeGeeQ appends to event_store
  │   └─► INSERT INTO event_store (...)
  │
  ├─► Step 3: PeeGeeQ sends to outbox
  │   └─► INSERT INTO outbox (...)
  │
COMMIT (all succeed) OR ROLLBACK (any step fails)
```

**Response (Success):**
```json
{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "transactionTime": "2025-12-22T12:00:00.123Z",
  "callbackResponse": {
    "success": true,
    "orderId": "ORD-001"
  },
  "eventId": "evt-789",
  "outboxMessageId": "msg-456"
}
```

**Characteristics:**
- ✅ **ACID Guarantees** - Single transaction across domain + PeeGeeQ tables
- ✅ **PeeGeeQ Remains Generic** - No domain knowledge (uses callbacks)
- ✅ **Strong Consistency** - All operations succeed or all fail
- ✅ **Synchronous** - Client receives immediate confirmation
- ⚠️ **Callback in Transaction** - Anti-pattern but necessary for ACID
- ⚠️ **Timeout Risk** - Callback must complete quickly

---

### Pattern 2: Saga Orchestration (Separate Databases)

#### Deployment Architecture

```
┌─────────────────────────────────────┐  ┌─────────────────────────────────────┐
│ Domain Database                     │  │ PeeGeeQ Database                    │
│                                     │  │                                     │
│  ┌───────────────────────────────┐ │  │  ┌───────────────────────────────┐ │
│  │ Domain Tables                 │ │  │  │ PeeGeeQ Tables                │ │
│  │ - orders                      │ │  │  │ - event_store                 │ │
│  │ - customers                   │ │  │  │ - outbox                      │ │
│  │ - inventory                   │ │  │  │ - consumer_groups             │ │
│  └───────────────────────────────┘ │  │  └───────────────────────────────┘ │
│                                     │  │                                     │
│  Transaction Boundary 1             │  │  Transaction Boundary 2             │
└─────────────────────────────────────┘  └─────────────────────────────────────┘
         ↑                                         ↑
         └─────────── No Shared Transaction ──────┘
```

#### Saga Orchestration Pattern

**PeeGeeQ provides saga orchestration endpoint:**
```http
POST /api/v1/saga/execute
Content-Type: application/json

{
  "sagaId": "saga-abc123",
  "steps": [
    {
      "stepId": "create-order",
      "type": "HTTP_CALL",
      "url": "https://domain-app.com/api/orders",
      "method": "POST",
      "payload": {
        "orderId": "ORD-001",
        "customerId": "CUST-001",
        "amount": 99.99
      },
      "compensationUrl": "https://domain-app.com/api/orders/ORD-001",
      "compensationMethod": "DELETE"
    },
    {
      "stepId": "append-event",
      "type": "EVENT_STORE_APPEND",
      "setupId": "peegeeq-main",
      "eventStoreName": "order_events",
      "eventType": "OrderCreated",
      "eventData": {
        "orderId": "ORD-001",
        "customerId": "CUST-001",
        "amount": 99.99
      },
      "validFrom": "2025-12-22T10:00:00Z"
    },
    {
      "stepId": "send-outbox",
      "type": "OUTBOX_SEND",
      "setupId": "peegeeq-main",
      "queueName": "orders",
      "message": {
        "orderId": "ORD-001",
        "customerId": "CUST-001",
        "amount": 99.99
      }
    }
  ]
}
```

**Saga Execution Flow (Success):**
```
Step 1: Create Order (Domain Database Transaction)
  POST https://domain-app.com/api/orders
  → Domain DB: BEGIN; INSERT INTO orders; COMMIT
  → Success ✅

Step 2: Append Event (PeeGeeQ Database Transaction)
  → PeeGeeQ DB: BEGIN; INSERT INTO event_store; COMMIT
  → Success ✅

Step 3: Send Outbox (PeeGeeQ Database Transaction)
  → PeeGeeQ DB: BEGIN; INSERT INTO outbox; COMMIT
  → Success ✅

Saga Status: COMPLETED
```

**Saga Execution Flow (Failure with Compensation):**
```
Step 1: Create Order (Domain Database Transaction)
  POST https://domain-app.com/api/orders
  → Domain DB: BEGIN; INSERT INTO orders; COMMIT
  → Success ✅

Step 2: Append Event (PeeGeeQ Database Transaction)
  → PeeGeeQ DB: BEGIN; INSERT INTO event_store; COMMIT
  → Success ✅

Step 3: Send Outbox (PeeGeeQ Database Transaction)
  → PeeGeeQ DB: BEGIN; INSERT INTO outbox; COMMIT
  → Failure ❌

Compensation (Reverse Order):
  Step 2 Compensation: (Event store append - no compensation needed)
  Step 1 Compensation:
    DELETE https://domain-app.com/api/orders/ORD-001
    → Domain DB: BEGIN; DELETE FROM orders; COMMIT
    → Success ✅

Saga Status: COMPENSATED (rolled back)
```

**Response (Success):**
```json
{
  "sagaId": "saga-abc123",
  "status": "COMPLETED",
  "completedAt": "2025-12-22T12:00:00.123Z",
  "steps": [
    {
      "stepId": "create-order",
      "status": "COMPLETED",
      "response": { "orderId": "ORD-001" }
    },
    {
      "stepId": "append-event",
      "status": "COMPLETED",
      "eventId": "evt-789"
    },
    {
      "stepId": "send-outbox",
      "status": "COMPLETED",
      "messageId": "msg-456"
    }
  ]
}
```

**Characteristics:**
- ✅ **Eventual Consistency** - Saga guarantees eventual state
- ✅ **PeeGeeQ Remains Generic** - No domain knowledge
- ✅ **Automatic Compensation** - Rollback via compensation logic
- ✅ **Works Across Databases** - No shared transaction required
- ⚠️ **Not ACID** - Intermediate states visible
- ⚠️ **Complex** - Saga orchestration overhead

---

### Pattern 3: Reservation (Async Two-Phase)

#### Problem
Pattern 1 (Callback) holds a database connection open while waiting for an external HTTP response. This causes **connection starvation** under load. Pattern 3 solves this by splitting the work into fast database commits and background HTTP calls.

#### The Flow
1.  **Phase 1: Reserve (PeeGeeQ)**
    *   **Request:** Client sends `POST /api/v1/commands/execute`.
    *   **Action:** PeeGeeQ writes a record to `command_log` table with `status=PENDING` and the full payload.
    *   **DB:** Fast single-record insert. Commit immediately. Connection released.
    *   **Response:** Returns `202 Accepted` with `commandId`.

2.  **Phase 2: Execute (Async Worker)**
    *   **Trigger:** A background poller (or `NOTIFY`) picks up the `PENDING` command.
    *   **Action:** Worker makes the **HTTP Call** to the Domain Service.
    *   **Result:** Domain Service returns `200 OK` (and commits its own data).

3.  **Phase 3: Finalize (PeeGeeQ)**
    *   **Action:** Worker receives 200 OK.
    *   **DB:** Opens new connection. Writes `event_store` + `outbox`. Updates `command_log` to `COMPLETED`. Commit.

#### Characteristics
- ✅ **High Performance** - No holding DB connections during HTTP calls.
- ✅ **Reliability** - If server crashes, `PENDING` commands are retried.
- ✅ **Decoupled** - Infrastructure scales independently of domain service latency.
- ⚠️ **Eventual Consistency** - Not ACID.
- ⚠️ **Complexity** - Requires background worker and retry logic.

---

### Pattern 4: Inversion (Domain-First)

#### Problem Solved
When the Database is Shared (Co-located), Pattern 1 attempts to coordinate a transaction from the "Outside-In" (PeeGeeQ calling Domain), which is difficult to do safely. Pattern 4 "Inverts" control: The **Domain App** owns the transaction and calls PeeGeeQ as a participant.

#### The Flow
1.  **Step 1:** Client calls **Domain App** directly (not PeeGeeQ).
    *   `POST /orders` -> Domain App.

2.  **Step 2:** Domain App starts Transaction.
    *   `BEGIN;`
    *   `INSERT INTO orders ...;`

3.  **Step 3:** Domain App calls PeeGeeQ to "participate".
    *   **Option A (Library):** Domain App uses `peegeeq-client-java` to append event/outbox in the *same* `SqlConnection`.
    *   **Option B (Sidecar REST):** Domain App calls `POST /peegeeq/generate-event-sql`. PeeGeeQ returns the SQL/Data. Domain App executes it.

4.  **Step 4:** Data is Committed.
    *   `COMMIT;`

#### Characteristics
- ✅ **True ACID** - Single atomic commit of Order + Event.
- ✅ **Best Performance** - Zero "coordination" overhead or distributed locks.
- ✅ **Simple Failure Model** - If anything fails, the database rolls back everything.
- ❌ **Coupling** - Domain App must have permissions to write to PeeGeeQ tables (in shared DB).

---

### Pattern Comparison

| Aspect | Pattern 1: Callback (Original) | Pattern 2: Saga | Pattern 3: Reservation | Pattern 4: Inversion |
|--------|----------------------|-------------------------|------------------------|----------------------|
| **Database** | Shared DB | Separate DBs | Separate or Shared | Shared DB |
| **ACID?** | ❌ NO (Technically separate Tx) | ❌ NO (Eventual) | ❌ NO (Eventual) | ✅ YES (True ACID) |
| **Performance Risk** | 🔴 HIGH (Connection Starvation) | 🟡 MEDIUM (Latency) | 🟢 LOW (Async) | 🟢 LOW (Direct DB) |
| **Complexity** | Low | High | Medium | Low (Code-level) |
| **Consistency** | Weak (Window of failure) | Eventual | Eventual | Strong |
| **Rec. Use Case** | **Low-volume, simple prototypes** | **Legacy integration** | **High-scale Microservices** | **Standard Monolith / Shared DB** |

### Existing Implementation Evidence

#### Spring Boot Integrated Example

**Location:** `peegeeq-examples-spring/src/main/java/dev/mars/peegeeq/examples/springbootintegrated/`

**Controller:** `OrderController.java`
```java
@PostMapping("/orders")
public CompletableFuture<ResponseEntity<String>> createOrder(@RequestBody CreateOrderRequest request) {
    // Delegates to OrderService which coordinates transaction
    return orderService.createOrder(request)
        .thenApply(orderId -> ResponseEntity.ok(orderId));
}
```

**Service:** `OrderService.java` (lines 136-165)
```java
return connectionProvider.withTransaction("peegeeq-main", connection -> {
    // Step 1: Save order to database
    return orderRepository.save(order, connection)

    // Step 2: Send to outbox (for immediate processing)
    .compose(v -> Future.fromCompletionStage(
        orderEventProducer.sendInTransaction(event, connection)
    ))

    // Step 3: Append to bi-temporal event store (for historical queries)
    .compose(v -> Future.fromCompletionStage(
        orderEventStore.appendInTransaction(
            "OrderCreated",
            event,
            validTime,
            connection  // SAME connection throughout
        )
    ))

    .map(v -> orderId);
}).toCompletionStage().toCompletableFuture();
```

**Key Components:**
- `ConnectionProvider.withTransaction()` - Manages transaction lifecycle
- `orderRepository.save()` - Persists business entity
- `orderEventProducer.sendInTransaction()` - Sends to outbox
- `orderEventStore.appendInTransaction()` - Appends to bi-temporal event store
- All operations use **same SqlConnection** - ensuring single transaction

### Investigation Conclusion

**Verdict:** The pattern is **production-ready** and **battle-tested** in Spring Boot examples. All required infrastructure exists:
- ✅ `ConnectionProvider.withTransaction()` for transaction management
- ✅ `EventStore.appendInTransaction()` for bi-temporal event storage
- ✅ `OutboxProducer.sendInTransaction()` for outbox pattern
- ✅ Proven pattern in `peegeeq-examples-spring`

This design exposes this proven pattern through the core REST API to provide transactional capabilities to all REST clients.

**Note on Transaction Participation:** The `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md` document states that `appendInTransaction()` is "intentionally internal for coordinating with other database operations within a single transaction. Not a REST gap." This design **extends** that capability by creating generic REST endpoints that coordinate transactions server-side, making the transactional pattern accessible to REST clients without exposing raw `SqlConnection` objects over HTTP. This is a **new capability**, not a gap closure.

---

## Problem Statement

### Current State

**REST API Architecture:**
```
Client                          Domain Application              PeeGeeQ REST API
  │
  ├─► POST /domain/orders        ─► INSERT INTO orders
  │   (Business Operation)           ✅ Committed (Domain DB)
  │
  ├─► POST /api/v1/eventstores   ─► INSERT INTO event_store
  │   (Event Logging)                ❌ FAILS (PeeGeeQ DB)
  │
  └─► Result: Order exists but no event logged!
```

**Problems:**

1. **No ACID Guarantees**
   - Business operation and event logging are in separate transactions
   - If event logging fails, business operation is already committed
   - No way to rollback business operation if event fails
   - Problem exists whether databases are co-located or separated

2. **Partial Failures**
   - Network failures between calls can leave data inconsistent
   - Client crashes between calls leave orphaned data
   - Retry logic is complex and error-prone
   - No automatic compensation mechanism

3. **Client Complexity**
   - Clients must implement coordination logic
   - Clients must handle partial failure scenarios
   - Clients must implement retry and compensation logic
   - Different strategies needed for co-located vs. separated databases

4. **Data Inconsistency**
   - Business data exists without corresponding events
   - Events exist without corresponding business data
   - Audit trail is incomplete
   - No way to query consistent state

### Impact

**For REST API Clients:**
- Cannot achieve ACID guarantees (same database) or eventual consistency (separate databases)
- Must implement complex error handling
- Risk of data inconsistency
- Difficult to maintain audit compliance

**For PeeGeeQ System:**
- Incomplete audit trails
- Data integrity issues
- Support burden for client coordination problems
- No generic solution for transactional coordination

**For Domain Applications:**
- Must implement custom transaction coordination
- No reusable pattern for common use cases
- Inconsistent approaches across different applications

### Requirements

1. **Generic Design** - PeeGeeQ must remain 100% generic (no domain-specific concepts)
2. **Two Deployment Patterns** - Support both co-located and separated database architectures
3. **ACID Compliance** - All operations must succeed or fail together (Pattern 1)
4. **Eventual Consistency** - Saga-based coordination with compensation (Pattern 2)
5. **Single Request** - Client sends one HTTP request to initiate coordination
6. **Automatic Rollback/Compensation** - Any failure triggers appropriate recovery
7. **REST Compatible** - Stateless, standard HTTP semantics
8. **Secure** - Authorization, validation, no arbitrary SQL execution
9. **Performant** - Transaction duration < 50ms for Pattern 1, < 500ms for Pattern 2
10. **Auditable** - Complete bi-temporal event trail

---

## Proposed Solution

### High-Level Design

**Two Generic Transactional Patterns Based on Database Architecture:**

#### Pattern 1: Same Database (ACID Transaction)
```
Client                          PeeGeeQ Generic API
  │
  └─► POST /api/v1/transactional/execute-with-callback
      {
        "callbackUrl": "https://domain-app.com/api/order-handler",
        "callbackPayload": {...},
        "eventStore": {...},
        "outbox": {...}
      }
      │
      └─► BEGIN TRANSACTION (Single PostgreSQL DB)
          ├─► HTTP POST to callback (domain logic)
          ├─► INSERT INTO event_store
          ├─► INSERT INTO outbox
          └─► COMMIT (or ROLLBACK on any failure)

      Result: All committed together or all rolled back (ACID)!
```

#### Pattern 2: Separate Databases (Saga Orchestration)
```
Client                          PeeGeeQ Saga API
  │
  └─► POST /api/v1/saga/execute
      {
        "sagaId": "saga-123",
        "steps": [
          { "type": "HTTP_CALL", "url": "...", "compensationUrl": "..." },
          { "type": "EVENT_STORE_APPEND", ... },
          { "type": "OUTBOX_SEND", ... }
        ]
      }
      │
      └─► Execute Saga Steps:
          ├─► Step 1: POST to domain app (Domain DB Transaction)
          ├─► Step 2: Append event (PeeGeeQ DB Transaction)
          ├─► Step 3: Send outbox (PeeGeeQ DB Transaction)
          └─► On failure: Execute compensation in reverse order

      Result: All steps complete or compensated (Eventual Consistency)!
```

### Key Principles

1. **PeeGeeQ Remains 100% Generic**
   - No domain-specific concepts (no "orders", "trades", etc.)
   - Generic callback/saga orchestration mechanisms
   - Domain logic implemented by customers

2. **Two Deployment Patterns**
   - **Pattern 1:** Co-located databases → ACID transaction via callback
   - **Pattern 2:** Separated databases → Saga orchestration with compensation

3. **Server-Side Coordination**
   - **Pattern 1:** Single transaction via `ConnectionProvider.withTransaction()`
   - **Pattern 2:** Saga state machine with automatic compensation

4. **Composable Operations**
   - Domain entity persistence (via callback or HTTP)
   - Bi-temporal event store append
   - Outbox message send (optional)
   - All coordinated by PeeGeeQ

5. **Vert.x 5.x Reactive Patterns**
   - Non-blocking I/O
   - Composable `Future` chains
   - Event loop efficiency

### Architecture Pattern

**Pattern 1 Implementation (Callback Hook):**

```java
// PeeGeeQ Generic Handler
public Future<TransactionalResponse> executeWithCallback(TransactionalCallbackRequest request) {
    return connectionProvider.withTransaction(request.getSetupId(), connection -> {

        // Step 1: Call customer's callback (domain logic)
        return httpClient.post(request.getCallbackUrl())
            .sendJson(request.getCallbackPayload())
            .compose(callbackResponse -> {
                if (!callbackResponse.isSuccess()) {
                    return Future.failedFuture("Callback failed");
                }

                // Step 2: Append to event store (if configured)
                if (request.getEventStore() != null) {
                    return Future.fromCompletionStage(
                        eventStore.appendInTransaction(
                            request.getEventStore().getEventType(),
                            request.getEventStore().getEventData(),
                            request.getEventStore().getValidFrom(),
                            connection  // SAME connection
                        )
                    );
                }
                return Future.succeededFuture();
            })

            // Step 3: Send to outbox (if configured)
            .compose(v -> {
                if (request.getOutbox() != null) {
                    return Future.fromCompletionStage(
                        outboxProducer.sendInTransaction(
                            request.getOutbox().getMessage(),
                            connection  // SAME connection
                        )
                    );
                }
                return Future.succeededFuture();
            })

            .map(v -> buildResponse(request));
    });
}
```

**Pattern 2 Implementation (Saga Orchestration):**

```java
// PeeGeeQ Saga Orchestrator
public Future<SagaResponse> executeSaga(SagaRequest request) {
    SagaState state = new SagaState(request.getSagaId());

    return executeStepsSequentially(request.getSteps(), state)
        .recover(error -> {
            // On failure, execute compensation in reverse order
            return compensateSteps(state.getCompletedSteps())
                .compose(v -> Future.failedFuture(error));
        })
        .map(v -> buildSagaResponse(state));
}

private Future<Void> executeStepsSequentially(List<SagaStep> steps, SagaState state) {
    Future<Void> chain = Future.succeededFuture();

    for (SagaStep step : steps) {
        chain = chain.compose(v -> executeStep(step, state));
    }

    return chain;
}

private Future<Void> executeStep(SagaStep step, SagaState state) {
    return switch (step.getType()) {
        case HTTP_CALL -> executeHttpCall(step);
        case EVENT_STORE_APPEND -> executeEventStoreAppend(step);
        case OUTBOX_SEND -> executeOutboxSend(step);
    }.onSuccess(v -> state.markStepCompleted(step));
}
```

**Both patterns maintain PeeGeeQ's generic nature while providing transactional coordination.**

---

## Architecture

### Architectural Compliance

This design strictly adheres to the PeeGeeQ layered architecture principles documented in `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md`:

**1. peegeeq-api (Pure Contracts)** - No changes required
- Existing interfaces: `ConnectionProvider`, `EventStore`, `OutboxProducer`
- Existing transaction methods: `appendInTransaction()`, `sendInTransaction()`
- These interfaces already support transaction participation via `SqlConnection` parameter

**2. peegeeq-runtime (Composition Layer)** - No changes required
- Already provides `DatabaseSetupService` facade
- Already wires together all implementations
- Already provides access to `ConnectionProvider`, `EventStore`, `OutboxProducer`

**3. peegeeq-rest (REST Layer)** - New handlers added
- New domain-specific handlers (e.g., `TransactionalOrderHandler`)
- Handlers use `ConnectionProvider.withTransaction()` to coordinate server-side transactions
- Handlers call `appendInTransaction()` and `sendInTransaction()` with same `SqlConnection`
- **No changes to existing REST endpoints** - backward compatible

**4. Dependency Rules Compliance**
- ✅ `peegeeq-rest` depends only on `peegeeq-api` and `peegeeq-runtime`
- ✅ No direct access to `peegeeq-db`, `peegeeq-native`, `peegeeq-outbox`, `peegeeq-bitemporal`
- ✅ All types referenced in REST handlers are from `peegeeq-api`
- ✅ Actual implementations obtained via `peegeeq-runtime` services

**5. Vert.x 5.x as Primary Interface**
- ✅ Uses Vert.x `Future<T>` for reactive composition
- ✅ Uses Vert.x `SqlConnection` for transaction participation
- ✅ Follows Vert.x 5.x composable patterns (`.compose()`, `.onSuccess()`, `.onFailure()`)
- ✅ Vert.x types are the primary API, not implementation details

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ REST Client (Any Language)                                  │
│ - JavaScript, Python, Go, etc.                              │
└────────────────────┬────────────────────────────────────────┘
                     │ HTTP POST
                     │ { order: {...}, event: {...} }
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ PeeGeeQ REST API (peegeeq-rest)                             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ TransactionalOrderHandler                              │ │
│  │ - Parse request                                        │ │
│  │ - Validate inputs                                      │ │
│  │ - Coordinate transaction                               │ │
│  └────────────────────┬───────────────────────────────────┘ │
│                       │                                      │
└───────────────────────┼──────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│ Transaction Coordinator (peegeeq-db)                        │
│                                                              │
│  ConnectionProvider.withTransaction(clientId, conn -> {     │
│    // All operations use SAME connection                    │
│  })                                                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ PostgreSQL Database                                         │
│                                                              │
│  BEGIN TRANSACTION;                                         │
│    INSERT INTO orders (...);          -- Business data      │
│    INSERT INTO order_events (...);    -- Bi-temporal        │
│    INSERT INTO outbox (...);          -- Real-time          │
│  COMMIT;  (or ROLLBACK on any failure)                     │
└─────────────────────────────────────────────────────────────┘
```

### Transaction Flow

**Success Path:**
```
1. Client sends HTTP POST request
   ↓
2. Handler validates request
   ↓
3. ConnectionProvider.withTransaction() begins
   ↓
4. INSERT INTO orders (...)              ✅ Success
   ↓
5. INSERT INTO order_events (...)        ✅ Success
   ↓
6. INSERT INTO outbox (...)              ✅ Success
   ↓
7. COMMIT TRANSACTION                    ✅ All committed atomically
   ↓
8. Return 201 Created with response
```

**Failure Path:**
```
1. Client sends HTTP POST request
   ↓
2. Handler validates request
   ↓
3. ConnectionProvider.withTransaction() begins
   ↓
4. INSERT INTO orders (...)              ✅ Success
   ↓
5. INSERT INTO order_events (...)        ❌ CONSTRAINT VIOLATION
   ↓
6. ROLLBACK TRANSACTION                  ✅ All rolled back
   ↓
7. Return 500 Internal Server Error
```

### Key Components

#### 1. ConnectionProvider

**Interface:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/ConnectionProvider.java`

```java
<T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation);
```

**Responsibilities:**
- Acquire connection from pool
- Begin transaction
- Execute operation with connection
- Commit on success
- Rollback on failure
- Release connection to pool

#### 2. EventStore.appendInTransaction()

**Interface:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/EventStore.java`

```java
CompletableFuture<BiTemporalEvent<T>> appendInTransaction(
    String eventType,
    T payload,
    Instant validTime,
    SqlConnection connection
);
```

**Responsibilities:**
- Accept existing connection
- Participate in caller's transaction
- Insert event into bi-temporal store
- Return event with transaction time

#### 3. OutboxProducer.sendInTransaction()

**Interface:** `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxProducer.java`

```java
CompletableFuture<Void> sendInTransaction(T message, SqlConnection connection);
```

**Responsibilities:**
- Accept existing connection
- Participate in caller's transaction
- Insert message into outbox table
- Enable real-time processing

#### 4. Repository Pattern

**Example:** `OrderRepository.save(Order, SqlConnection)`

```java
Future<Order> save(Order order, SqlConnection connection) {
    String sql = """
        INSERT INTO orders (id, customer_id, amount, status, created_at)
        VALUES ($1, $2, $3, $4, $5)
        """;

    return connection.preparedQuery(sql)
        .execute(Tuple.of(
            order.getId(),
            order.getCustomerId(),
            order.getAmount(),
            order.getStatus(),
            order.getCreatedAt()
        ))
        .map(result -> order);
}
```

**Responsibilities:**
- Accept existing connection
- Participate in caller's transaction
- Insert/update business entity
- Return persisted entity

---

## API Design

### Relationship to Existing REST API

This design **extends** the existing PeeGeeQ REST API documented in `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9 (Call Propagation Paths Grid).

**Existing REST API (49 endpoints across 10 categories):**
- Section 9.1: Setup Operations (7 endpoints)
- Section 9.2: Queue Operations (4 endpoints)
- Section 9.3: Consumer Group Operations (6 endpoints)
- Section 9.4: Event Store Operations (8 endpoints)
- Section 9.5: Dead Letter Queue Operations (6 endpoints)
- Section 9.6: Subscription Lifecycle Operations (6 endpoints)
- Section 9.7: Health Check Operations (3 endpoints)
- Section 9.8: Management API Operations (6 endpoints)
- Section 9.10: Webhook Subscription Operations (3 endpoints)

**New Generic Transactional API (this design):**
- **Section 9.11:** Generic Transactional Operations (2 endpoints)
- **Purpose:** Coordinate domain operations + event + outbox via generic mechanisms
- **Pattern 1:** Callback hook pattern for same database (ACID)
- **Pattern 2:** Saga orchestration for separate databases (eventual consistency)
- **Backward Compatibility:** ✅ No changes to existing 49 endpoints
- **Generic Design:** ✅ No domain-specific concepts in PeeGeeQ

**Key Difference:**
- **Existing API:** Separate endpoints for business operations and event logging (no ACID guarantees across calls)
- **New Generic Transactional API:** Generic coordination endpoints (ACID or eventual consistency via callbacks/sagas)

---

### Pattern 1 API: Callback Hook (Same Database)

#### Endpoint

```
POST /api/v1/transactional/execute-with-callback
```

#### Request Schema

```json
{
  "callbackUrl": "string (required)",
  "callbackMethod": "POST | PUT | PATCH (default: POST)",
  "callbackPayload": {
    // Domain-specific data sent to callback
    // Customer defines structure
  },
  "callbackHeaders": {
    // Optional headers to send with callback
    "Authorization": "Bearer ...",
    "X-Custom-Header": "value"
  },
  "eventStore": {
    "setupId": "string (required)",
    "eventStoreName": "string (required)",
    "eventType": "string (required)",
    "eventData": {
      // Event payload (customer defines structure)
    },
    "validFrom": "ISO-8601 timestamp (required)",
    "correlationId": "string (optional)",
    "causationId": "string (optional)",
    "metadata": {}
  },
  "outbox": {
    "setupId": "string (required)",
    "queueName": "string (required)",
    "message": {
      // Message payload (customer defines structure)
    }
  }
}
```

#### Response Schema (Success)

```json
{
  "transactionId": "string",
  "status": "COMMITTED",
  "transactionTime": "ISO-8601 timestamp",
  "callbackResponse": {
    // Response from customer's callback
  },
  "eventId": "string (if eventStore configured)",
  "outboxMessageId": "string (if outbox configured)"
}
```

#### Response Schema (Failure)

```json
{
  "transactionId": "string",
  "status": "ROLLED_BACK",
  "transactionTime": "ISO-8601 timestamp",
  "error": {
    "code": "CALLBACK_FAILED | EVENT_STORE_FAILED | OUTBOX_FAILED",
    "message": "string",
    "failedStep": "CALLBACK | EVENT_STORE | OUTBOX"
  }
}
```

---

### Pattern 2 API: Saga Orchestration (Separate Databases)

#### Endpoint

```
POST /api/v1/saga/execute
```

#### Request Schema

```json
{
  "sagaId": "string (required, unique)",
  "steps": [
    {
      "stepId": "string (required, unique within saga)",
      "type": "HTTP_CALL | EVENT_STORE_APPEND | OUTBOX_SEND",

      // For HTTP_CALL type:
      "url": "string (required for HTTP_CALL)",
      "method": "POST | PUT | PATCH | DELETE",
      "payload": {},
      "headers": {},
      "compensationUrl": "string (required for HTTP_CALL)",
      "compensationMethod": "DELETE | POST | PUT",
      "compensationPayload": {},

      // For EVENT_STORE_APPEND type:
      "setupId": "string (required for EVENT_STORE_APPEND)",
      "eventStoreName": "string (required for EVENT_STORE_APPEND)",
      "eventType": "string (required for EVENT_STORE_APPEND)",
      "eventData": {},
      "validFrom": "ISO-8601 timestamp (required for EVENT_STORE_APPEND)",

      // For OUTBOX_SEND type:
      "setupId": "string (required for OUTBOX_SEND)",
      "queueName": "string (required for OUTBOX_SEND)",
      "message": {}
    }
  ]
}
```

#### Response Schema (Success)

```json
{
  "sagaId": "string",
  "status": "COMPLETED",
  "completedAt": "ISO-8601 timestamp",
  "steps": [
    {
      "stepId": "string",
      "status": "COMPLETED",
      "response": {
        // Step-specific response
      }
    }
  ]
}
```

#### Response Schema (Failure with Compensation)

```json
{
  "sagaId": "string",
  "status": "COMPENSATED",
  "failedAt": "ISO-8601 timestamp",
  "compensatedAt": "ISO-8601 timestamp",
  "steps": [
    {
      "stepId": "string",
      "status": "COMPLETED | FAILED | COMPENSATED",
      "error": {
        "code": "string",
        "message": "string"
      }
    }
  ]
}
```

---

### Example 1: Pattern 1 (Callback Hook) - Order Creation

**Request:**
```http
POST /api/v1/transactional/execute-with-callback HTTP/1.1
Content-Type: application/json
Authorization: Bearer <token>

{
  "callbackUrl": "https://domain-app.com/api/order-handler",
  "callbackMethod": "POST",
  "callbackPayload": {
    "orderId": "ORD-2025-001",
    "customerId": "CUST-12345",
    "amount": 1299.99,
    "currency": "USD",
    "items": [
      {
        "productId": "PROD-001",
        "quantity": 2,
        "unitPrice": 649.99
      }
    ]
  },
  "eventStore": {
    "setupId": "peegeeq-main",
    "eventStoreName": "order_events",
    "eventType": "OrderCreated",
    "eventData": {
      "orderId": "ORD-2025-001",
      "customerId": "CUST-12345",
      "totalAmount": 1299.99,
      "itemCount": 2
    },
    "validFrom": "2025-12-22T10:30:00Z",
    "correlationId": "order-flow-abc123"
  },
  "outbox": {
    "setupId": "peegeeq-main",
    "queueName": "orders",
    "message": {
      "orderId": "ORD-2025-001",
      "customerId": "CUST-12345",
      "amount": 1299.99
    }
  }
}
```

**Response (Success - 200 OK):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "transactionTime": "2025-12-22T10:30:00.123456Z",
  "callbackResponse": {
    "success": true,
    "orderId": "ORD-2025-001"
  },
  "eventId": "evt-550e8400-e29b-41d4-a716-446655440000",
  "outboxMessageId": "msg-789"
}
```

**Response (Failure - 500 Internal Server Error):**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "transactionId": "txn-abc123",
  "status": "ROLLED_BACK",
  "transactionTime": "2025-12-22T10:30:00.123456Z",
  "error": {
    "code": "EVENT_STORE_FAILED",
    "message": "Constraint violation on event_store table",
    "failedStep": "EVENT_STORE"
  }
}
```

---

### Example 2: Pattern 2 (Saga) - Order Creation

**Request:**
```http
POST /api/v1/saga/execute HTTP/1.1
Content-Type: application/json
Authorization: Bearer <token>

{
  "sagaId": "saga-order-abc123",
  "steps": [
    {
      "stepId": "create-order",
      "type": "HTTP_CALL",
      "url": "https://domain-app.com/api/orders",
      "method": "POST",
      "payload": {
        "orderId": "ORD-2025-001",
        "customerId": "CUST-12345",
        "amount": 1299.99
      },
      "compensationUrl": "https://domain-app.com/api/orders/ORD-2025-001",
      "compensationMethod": "DELETE"
    },
    {
      "stepId": "append-event",
      "type": "EVENT_STORE_APPEND",
      "setupId": "peegeeq-main",
      "eventStoreName": "order_events",
      "eventType": "OrderCreated",
      "eventData": {
        "orderId": "ORD-2025-001",
        "customerId": "CUST-12345",
        "totalAmount": 1299.99
      },
      "validFrom": "2025-12-22T10:30:00Z"
    },
    {
      "stepId": "send-outbox",
      "type": "OUTBOX_SEND",
      "setupId": "peegeeq-main",
      "queueName": "orders",
      "message": {
        "orderId": "ORD-2025-001",
        "customerId": "CUST-12345",
        "amount": 1299.99
      }
    }
  ]
}
```

**Response (Success - 200 OK):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "sagaId": "saga-order-abc123",
  "status": "COMPLETED",
  "completedAt": "2025-12-22T10:30:01.500Z",
  "steps": [
    {
      "stepId": "create-order",
      "status": "COMPLETED",
      "response": {
        "orderId": "ORD-2025-001"
      }
    },
    {
      "stepId": "append-event",
      "status": "COMPLETED",
      "response": {
        "eventId": "evt-550e8400-e29b-41d4-a716-446655440000"
      }
    },
    {
      "stepId": "send-outbox",
      "status": "COMPLETED",
      "response": {
        "messageId": "msg-789"
      }
    }
  ]
}
```

**Response (Failure with Compensation - 500 Internal Server Error):**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "sagaId": "saga-order-abc123",
  "status": "COMPENSATED",
  "failedAt": "2025-12-22T10:30:01.300Z",
  "compensatedAt": "2025-12-22T10:30:01.800Z",
  "steps": [
    {
      "stepId": "create-order",
      "status": "COMPENSATED",
      "response": {
        "orderId": "ORD-2025-001"
      }
    },
    {
      "stepId": "append-event",
      "status": "COMPLETED",
      "response": {
        "eventId": "evt-550e8400-e29b-41d4-a716-446655440000"
      }
    },
    {
      "stepId": "send-outbox",
      "status": "FAILED",
      "error": {
        "code": "QUEUE_NOT_FOUND",
        "message": "Queue 'orders' does not exist"
      }
    }
  ]
}
```

### Response Codes

| Code | Status | Description |
|------|--------|-------------|
| 201 | Created | Transaction committed successfully |
| 400 | Bad Request | Invalid request data or validation failure |
| 401 | Unauthorized | Missing or invalid authentication |
| 403 | Forbidden | Insufficient permissions |
| 409 | Conflict | Business constraint violation (e.g., duplicate order ID) |
| 500 | Internal Server Error | Transaction failed and rolled back |
| 503 | Service Unavailable | Database unavailable or connection pool exhausted |

### Request Validation

**Pre-Transaction Validation:**
1. **Authentication** - Valid bearer token
2. **Authorization** - User has permission for operation
3. **Schema Validation** - Request matches expected structure
4. **Business Rules** - Domain-specific validation (e.g., amount > 0)
5. **Required Fields** - All mandatory fields present

**In-Transaction Validation:**
1. **Database Constraints** - Foreign keys, unique constraints
2. **Event Store Validation** - Event type, payload structure
3. **Outbox Validation** - Topic exists, message format

---

## Implementation Plan

### Phase 1: Foundation (Week 1-2)

**Goal:** Implement single transactional endpoint as proof of concept

**Tasks:**
1. ✅ Create `TransactionalOrderHandler` class
2. ✅ Implement request/response DTOs
3. ✅ Wire up endpoint in `PeeGeeQRestServer`
4. ✅ Implement transaction coordination logic
5. ✅ Add integration tests
6. ✅ Document API in OpenAPI spec

**Deliverables:**
- Working `/api/v1/transactional/orders` endpoint
- Integration tests with TestContainers
- API documentation
- Performance baseline
- **Update `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md`** to add Section 9.11 (Transactional Operations)

### Phase 2: Core Endpoints (Week 3-4)

**Goal:** Expand to common business domains

**Tasks:**
1. ✅ Implement `/api/v1/transactional/trades` endpoint
2. ✅ Implement `/api/v1/transactional/inventory-reservations` endpoint
3. ✅ Standardize request/response patterns
4. ✅ Add comprehensive error handling
5. ✅ Add validation framework
6. ✅ Update OpenAPI documentation

**Deliverables:**
- 3 transactional endpoints
- Standardized patterns
- Comprehensive error handling
- Updated documentation

### Phase 3: Advanced Features (Week 5-6)

**Goal:** Add production-ready features

**Tasks:**
1. ✅ Implement idempotency support (transaction IDs)
2. ✅ Add batch transactional operations
3. ✅ Add transaction status queries
4. ✅ Add monitoring and metrics
5. ✅ Add distributed tracing support
6. ✅ Performance optimization

**Deliverables:**
- Idempotency support
- Batch operations
- Monitoring integration
- Performance tuning

### Phase 4: Production Readiness (Week 7-8)

**Goal:** Prepare for production deployment

**Tasks:**
1. ✅ Load testing (1000+ concurrent transactions)
2. ✅ Security audit
3. ✅ Documentation review
4. ✅ Client SDK updates
5. ✅ Migration guide
6. ✅ Release notes

**Deliverables:**
- Load test results
- Security audit report
- Complete documentation
- Updated client SDKs
- Release package

---

## Testing Strategy

### Unit Tests

**Scope:** Individual handler methods and DTOs

**Example Tests:**
```java
@Test
void testTransactionalOrderRequest_Validation() {
    TransactionalOrderRequest request = new TransactionalOrderRequest();
    request.setOrder(null); // Invalid

    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("order is required");
}

@Test
void testTransactionalOrderHandler_ParseRequest() {
    String json = """
        {
          "order": {"orderId": "ORD-001", "customerId": "CUST-001", "amount": 99.99},
          "event": {"eventType": "OrderCreated", "eventData": {}}
        }
        """;

    TransactionalOrderRequest request = handler.parseRequest(json);

    assertThat(request.getOrder().getOrderId()).isEqualTo("ORD-001");
    assertThat(request.getEvent().getEventType()).isEqualTo("OrderCreated");
}
```

### Integration Tests

**Scope:** Full transaction flow with real database

**Test Categories:**

1. **Success Path Tests**
```java
@Test
void testCreateOrder_Success() {
    // Given
    TransactionalOrderRequest request = createValidOrderRequest();

    // When
    CompletableFuture<TransactionalOrderResponse> result =
        handler.createOrder(request);

    // Then
    assertThat(result).isCompletedWithValueMatching(
        response -> response.getStatus().equals("COMMITTED")
    );

    // Verify all operations committed
    assertThat(orderRepository.findById(orderId)).isPresent();
    assertThat(eventStore.findById(eventId)).isPresent();
    assertThat(outbox.findByCorrelationId(correlationId)).isPresent();
}
```

2. **Rollback Tests**
```java
@Test
void testCreateOrder_RollbackOnEventFailure() {
    // Given
    TransactionalOrderRequest request = createRequestWithInvalidEvent();

    // When
    CompletableFuture<TransactionalOrderResponse> result =
        handler.createOrder(request);

    // Then
    assertThat(result).isCompletedExceptionally();

    // Verify complete rollback
    assertThat(orderRepository.findById(orderId)).isEmpty();
    assertThat(eventStore.findById(eventId)).isEmpty();
    assertThat(outbox.findByCorrelationId(correlationId)).isEmpty();
}
```

3. **Constraint Violation Tests**
```java
@Test
void testCreateOrder_DuplicateOrderId() {
    // Given
    TransactionalOrderRequest request = createValidOrderRequest();
    handler.createOrder(request).join(); // First creation succeeds

    // When
    CompletableFuture<TransactionalOrderResponse> result =
        handler.createOrder(request); // Duplicate

    // Then
    assertThat(result).isCompletedExceptionally();
    assertThat(result)
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(ConstraintViolationException.class);
}
```

4. **Concurrent Transaction Tests**
```java
@Test
void testConcurrentOrderCreation() {
    // Given
    List<TransactionalOrderRequest> requests =
        IntStream.range(0, 100)
            .mapToObj(i -> createOrderRequest("ORD-" + i))
            .collect(Collectors.toList());

    // When
    List<CompletableFuture<TransactionalOrderResponse>> futures =
        requests.stream()
            .map(handler::createOrder)
            .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Then
    assertThat(orderRepository.count()).isEqualTo(100);
    assertThat(eventStore.count()).isEqualTo(100);
    assertThat(outbox.count()).isEqualTo(100);
}
```

### Performance Tests

**Scope:** Transaction throughput and latency

**Metrics:**
- Transaction duration (target: < 50ms p95)
- Throughput (target: > 1000 tx/sec)
- Connection pool utilization
- Database CPU/memory usage

**Test Scenarios:**
```java
@Test
void testTransactionPerformance() {
    // Warmup
    for (int i = 0; i < 100; i++) {
        handler.createOrder(createOrderRequest("WARMUP-" + i)).join();
    }

    // Measure
    long startTime = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
        handler.createOrder(createOrderRequest("PERF-" + i)).join();
    }
    long endTime = System.nanoTime();

    double avgDurationMs = (endTime - startTime) / 1_000_000.0 / 1000;
    double throughput = 1000.0 / (avgDurationMs / 1000.0);

    assertThat(avgDurationMs).isLessThan(50.0);
    assertThat(throughput).isGreaterThan(1000.0);
}
```

### Load Tests

**Scope:** System behavior under sustained load

**Tools:** JMeter, Gatling, or k6

**Scenarios:**
1. **Sustained Load** - 1000 tx/sec for 10 minutes
2. **Spike Load** - Ramp from 100 to 5000 tx/sec
3. **Stress Test** - Increase load until failure
4. **Endurance Test** - 500 tx/sec for 24 hours

---

## Security Considerations

### 1. Authentication

**Requirement:** All transactional endpoints require authentication

**Implementation:**
```java
public void createOrder(RoutingContext ctx) {
    // Extract and validate bearer token
    String token = ctx.request().getHeader("Authorization");
    if (token == null || !token.startsWith("Bearer ")) {
        sendError(ctx, 401, "Missing or invalid authentication");
        return;
    }

    // Validate token
    User user = authService.validateToken(token.substring(7));
    if (user == null) {
        sendError(ctx, 401, "Invalid token");
        return;
    }

    // Continue with request processing
}
```

### 2. Authorization

**Requirement:** Users must have permission for specific operations

**Implementation:**
```java
// Check permissions before transaction
if (!authService.hasPermission(user, "CREATE_ORDER")) {
    sendError(ctx, 403, "Insufficient permissions");
    return;
}
```

**Permission Model:**
- `CREATE_ORDER` - Create new orders
- `CREATE_TRADE` - Create new trades
- `RESERVE_INVENTORY` - Reserve inventory
- `ADMIN` - All operations

### 3. Input Validation

**Requirement:** Validate all inputs before transaction

**Validation Layers:**

1. **Schema Validation**
```java
// Validate JSON structure
try {
    TransactionalOrderRequest request =
        objectMapper.readValue(body, TransactionalOrderRequest.class);
} catch (JsonProcessingException e) {
    sendError(ctx, 400, "Invalid JSON: " + e.getMessage());
    return;
}
```

2. **Business Rule Validation**
```java
// Validate business rules
if (request.getOrder().getAmount().compareTo(BigDecimal.ZERO) <= 0) {
    sendError(ctx, 400, "Order amount must be positive");
    return;
}

if (request.getOrder().getItems().isEmpty()) {
    sendError(ctx, 400, "Order must contain at least one item");
    return;
}
```

3. **Data Sanitization**
```java
// Sanitize string inputs
String sanitizedDescription =
    StringEscapeUtils.escapeHtml4(request.getOrder().getDescription());
```

### 4. SQL Injection Prevention

**Requirement:** Use parameterized queries exclusively

**Implementation:**
```java
// ✅ SAFE - Parameterized query
String sql = "INSERT INTO orders (id, customer_id, amount) VALUES ($1, $2, $3)";
connection.preparedQuery(sql)
    .execute(Tuple.of(orderId, customerId, amount));

// ❌ UNSAFE - String concatenation (NEVER DO THIS)
String sql = "INSERT INTO orders (id, customer_id, amount) VALUES ('"
    + orderId + "', '" + customerId + "', " + amount + ")";
```

### 5. Rate Limiting

**Requirement:** Prevent abuse and DoS attacks

**Implementation:**
```java
// Check rate limit before processing
if (rateLimiter.isRateLimited(user.getId())) {
    sendError(ctx, 429, "Rate limit exceeded");
    return;
}
```

**Limits:**
- 100 requests per minute per user
- 1000 requests per minute per IP
- 10,000 requests per minute globally

### 6. Audit Logging

**Requirement:** Log all transactional operations

**Implementation:**
```java
auditLogger.log(AuditEvent.builder()
    .userId(user.getId())
    .operation("CREATE_ORDER")
    .orderId(orderId)
    .timestamp(Instant.now())
    .ipAddress(ctx.request().remoteAddress().host())
    .userAgent(ctx.request().getHeader("User-Agent"))
    .status("SUCCESS")
    .build());
```

---

## Performance Analysis

### Transaction Duration Breakdown

**Typical Order Creation Transaction:**

| Operation | Duration | Percentage |
|-----------|----------|------------|
| Request parsing | 1-2ms | 5% |
| Validation | 1-2ms | 5% |
| Connection acquisition | 1-2ms | 5% |
| BEGIN TRANSACTION | < 1ms | 2% |
| INSERT INTO orders | 3-5ms | 15% |
| INSERT INTO order_events | 3-5ms | 15% |
| INSERT INTO outbox | 3-5ms | 15% |
| COMMIT | 1-2ms | 5% |
| Response serialization | 1-2ms | 5% |
| Network I/O | 5-10ms | 28% |
| **Total** | **20-40ms** | **100%** |

### Optimization Strategies

1. **Connection Pooling**
   - Pool size: 16-32 connections
   - Max wait time: 5 seconds
   - Idle timeout: 10 minutes

2. **Prepared Statement Caching**
   - Cache size: 100 statements per connection
   - Reduces parsing overhead

3. **Batch Operations**
   - Combine multiple inserts when possible
   - Use PostgreSQL `executeBatch()`

4. **Async Processing**
   - Non-blocking I/O with Vert.x event loop
   - No thread blocking

5. **Database Indexes**
   - Index on `orders.id` (primary key)
   - Index on `order_events.event_id` (primary key)
   - Index on `outbox.correlation_id`

### Scalability Analysis

**Single Instance Capacity:**
- **Throughput:** 1000-2000 tx/sec
- **Concurrent Users:** 10,000+
- **Database Connections:** 16-32
- **Memory:** 2-4 GB

**Horizontal Scaling:**
- **Load Balancer:** Distribute across multiple instances
- **Database:** PostgreSQL read replicas for queries
- **Connection Pool:** Per-instance pools

**Vertical Scaling:**
- **CPU:** More cores = more event loops
- **Memory:** Larger connection pools
- **Database:** More powerful PostgreSQL instance

---

## Migration Path

### For Domain Applications

**Pattern 1: Same Database (Callback Hook)**

**Step 1: Implement Callback Handler**

Domain application creates a callback endpoint:

```java
// Domain Application: OrderCallbackHandler.java
@RestController
public class OrderCallbackHandler {

    @Inject OrderRepository orderRepository;

    @PostMapping("/api/order-handler")
    public CompletableFuture<CallbackResponse> handleOrderCreation(
        @RequestBody OrderCallbackPayload payload,
        @RequestHeader("X-Transaction-Connection") String connectionId) {

        // This executes within PeeGeeQ's transaction
        return orderRepository.save(payload.toOrder(), connectionId)
            .thenApply(order -> new CallbackResponse(true, order.getId()));
    }
}
```

**Step 2: Call PeeGeeQ Generic Endpoint**

```javascript
// Client calls PeeGeeQ generic endpoint
const result = await POST('/api/v1/transactional/execute-with-callback', {
    callbackUrl: 'https://domain-app.com/api/order-handler',
    callbackPayload: {
        orderId: 'ORD-001',
        customerId: 'CUST-001',
        amount: 99.99
    },
    eventStore: {
        setupId: 'peegeeq-main',
        eventStoreName: 'order_events',
        eventType: 'OrderCreated',
        eventData: { orderId: 'ORD-001', ... },
        validFrom: '2025-12-22T10:00:00Z'
    },
    outbox: {
        setupId: 'peegeeq-main',
        queueName: 'orders',
        message: { orderId: 'ORD-001', ... }
    }
});

// Result: All operations committed in single transaction
```

---

**Pattern 2: Separate Databases (Saga Orchestration)**

**Step 1: Implement Domain Endpoints with Compensation**

Domain application creates endpoints for both forward and compensation operations:

```java
// Domain Application: OrderController.java
@RestController
public class OrderController {

    @PostMapping("/api/orders")
    public CompletableFuture<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        return orderRepository.save(request.toOrder())
            .thenApply(order -> new OrderResponse(order.getId()));
    }

    @DeleteMapping("/api/orders/{orderId}")
    public CompletableFuture<Void> deleteOrder(@PathVariable String orderId) {
        // Compensation logic
        return orderRepository.delete(orderId);
    }
}
```

**Step 2: Call PeeGeeQ Saga Endpoint**

```javascript
// Client calls PeeGeeQ saga endpoint
const result = await POST('/api/v1/saga/execute', {
    sagaId: 'saga-abc123',
    steps: [
        {
            stepId: 'create-order',
            type: 'HTTP_CALL',
            url: 'https://domain-app.com/api/orders',
            method: 'POST',
            payload: { orderId: 'ORD-001', customerId: 'CUST-001', amount: 99.99 },
            compensationUrl: 'https://domain-app.com/api/orders/ORD-001',
            compensationMethod: 'DELETE'
        },
        {
            stepId: 'append-event',
            type: 'EVENT_STORE_APPEND',
            setupId: 'peegeeq-main',
            eventStoreName: 'order_events',
            eventType: 'OrderCreated',
            eventData: { orderId: 'ORD-001', ... },
            validFrom: '2025-12-22T10:00:00Z'
        },
        {
            stepId: 'send-outbox',
            type: 'OUTBOX_SEND',
            setupId: 'peegeeq-main',
            queueName: 'orders',
            message: { orderId: 'ORD-001', ... }
        }
    ]
});

// Result: All steps complete or compensated
```

---

### For SDK Users

**No Changes Required**

SDK users already have access to `appendInTransaction()` and can continue using it directly:

```java
connectionProvider.withTransaction("client-id", connection -> {
    return orderRepository.save(order, connection)
        .compose(v -> eventStore.appendInTransaction(..., connection));
});
```

**Benefit:** Generic transactional REST API provides same capabilities to non-JVM clients and REST-based integrations.

---

### Backward Compatibility

**Guarantee:** Existing endpoints remain unchanged

- ✅ All existing PeeGeeQ REST endpoints continue to work
- ✅ `/api/v1/eventstores/.../events` - Still works
- ✅ `/api/v1/queues/.../messages` - Still works
- ✅ All existing client code continues to function

**New Endpoints:** Additive only

- ✅ `/api/v1/transactional/execute-with-callback` - New generic endpoint (Pattern 1)
- ✅ `/api/v1/saga/execute` - New generic endpoint (Pattern 2)
- ✅ No breaking changes to existing APIs
- ✅ PeeGeeQ remains 100% generic

---

## Alternatives Considered

### Alternative 1: Arbitrary SQL Execution Endpoint

**Design:**
```
POST /api/v1/transactional/execute
{
  "operations": [
    {"type": "SQL_EXECUTE", "sql": "INSERT INTO orders ...", "params": [...]},
    {"type": "APPEND_EVENT", "eventStore": "order_events", "event": {...}},
    {"type": "SEND_OUTBOX", "topic": "orders", "message": {...}}
  ]
}
```

**Pros:**
- ✅ Highly flexible
- ✅ Single endpoint for all domains

**Cons:**
- ❌ **CRITICAL SECURITY RISK:** Arbitrary SQL from clients
- ❌ SQL injection vulnerability
- ❌ Exposes internal database structure
- ❌ No type safety
- ❌ Violates PeeGeeQ's generic infrastructure principle

**Decision:** **REJECTED** - Unacceptable security risk

---

### Alternative 2: Two-Phase Commit Protocol

**Design:**
```
POST /api/v1/transactions/begin
POST /api/v1/orders (with transaction ID)
POST /api/v1/eventstores/.../events (with transaction ID)
POST /api/v1/transactions/{id}/commit
```

**Pros:**
- ✅ Familiar pattern from distributed systems
- ✅ Explicit transaction control

**Cons:**
- ❌ Violates REST statelessness
- ❌ Requires server-side transaction state
- ❌ Complex error handling
- ❌ Network failures between phases
- ❌ Resource locking issues
- ❌ Doesn't work across separate databases

**Decision:** **REJECTED** - Violates REST principles, too complex

---

### Alternative 3: Generic Transactional Coordinator (Event Store + Outbox Only)

**Design:**
```
POST /api/v1/transactional/coordinate
{
  "operations": [
    {"type": "EVENT_STORE_APPEND", ...},
    {"type": "OUTBOX_SEND", ...}
  ]
}
```

**Pros:**
- ✅ PeeGeeQ remains generic
- ✅ No HTTP callbacks

**Cons:**
- ❌ **Two separate transactions** (customer's and PeeGeeQ's)
- ❌ No ACID guarantees across domain + PeeGeeQ operations
- ❌ Partial failure possible (order created but event/outbox fails)
- ❌ Doesn't solve the original problem

**Decision:** **REJECTED** - Doesn't provide ACID guarantees or eventual consistency

---

### Alternative 4: Batch Endpoint

**Design:**
```
POST /api/v1/batch
{
  "requests": [
    {"method": "POST", "path": "/api/v1/orders", "body": {...}},
    {"method": "POST", "path": "/api/v1/eventstores/.../events", "body": {...}}
  ]
}
```

**Pros:**
- ✅ Reuses existing endpoints
- ✅ Flexible

**Cons:**
- ❌ No transaction guarantees
- ❌ Just batching, not transactional
- ❌ Doesn't solve the core problem

**Decision:** **REJECTED** - Doesn't provide ACID guarantees

---

### Alternative 5: Domain-Specific Transactional Endpoints

**Design:**
```
POST /api/v1/transactional/orders
{
  "order": {...},
  "event": {...}
}
```

**Pros:**
- ✅ Type-safe and domain-specific
- ✅ Clear semantics
- ✅ Easy to validate
- ✅ Good developer experience

**Cons:**
- ❌ **Violates PeeGeeQ's generic infrastructure principle**
- ❌ Pollutes PeeGeeQ with domain concepts ("orders", "trades", etc.)
- ❌ Not reusable across different domains
- ❌ Requires PeeGeeQ to know about customer business entities

**Decision:** **REJECTED** - Violates core architectural principle (PeeGeeQ must remain 100% generic)

---

### Alternative 6: Domain Tables in Same Database (Direct SQL Execution via REST)

**Design:**

When domain tables (e.g., `orders`, `customers`, `inventory`) are in the **same PostgreSQL database** as PeeGeeQ infrastructure tables (event store, outbox), clients can send domain SQL statements directly to PeeGeeQ's REST API to execute all operations in a single ACID transaction.

**Architecture:**
```
┌─────────────────────────────────────────────────────────┐
│ PostgreSQL Database (Single Instance)                   │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │ Domain Schema (Customer Tables)                    │ │
│  │ - orders                                           │ │
│  │ - customers                                        │ │
│  │ - inventory                                        │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │ PeeGeeQ Schema (Infrastructure Tables)             │ │
│  │ - event_store                                      │ │
│  │ - outbox                                           │ │
│  │ - consumer_offsets                                 │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  Single ACID Transaction Across All Tables             │
└─────────────────────────────────────────────────────────┘
```

**REST API (PeeGeeQ):**

```http
POST /api/v1/transactional/execute
Content-Type: application/json

{
  "setupId": "peegeeq-main",
  "domainSql": {
    "statements": [
      {
        "sql": "INSERT INTO orders (order_id, customer_id, amount, status, created_at) VALUES ($1, $2, $3, $4, $5)",
        "params": ["ORD-001", "CUST-001", 99.99, "PENDING", "2025-12-22T10:00:00Z"]
      }
    ]
  },
  "eventStore": {
    "eventStoreName": "order_events",
    "eventType": "OrderCreated",
    "eventData": {
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99,
      "status": "PENDING"
    },
    "validFrom": "2025-12-22T10:00:00Z"
  },
  "outbox": {
    "queueName": "orders",
    "message": {
      "eventType": "OrderCreated",
      "orderId": "ORD-001",
      "customerId": "CUST-001",
      "amount": 99.99
    }
  }
}
```

**Response (Success):**

```json
{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "transactionTime": "2025-12-22T12:00:00.123Z",
  "domainSqlResults": [
    {
      "rowsAffected": 1
    }
  ],
  "eventId": "evt-789",
  "outboxMessageId": "msg-456"
}
```

**Client Usage (JavaScript):**

```javascript
// Client calls PeeGeeQ REST endpoint directly
const result = await POST('https://peegeeq.com/api/v1/transactional/execute', {
    setupId: 'peegeeq-main',
    domainSql: {
        statements: [{
            sql: 'INSERT INTO orders (order_id, customer_id, amount, status, created_at) VALUES ($1, $2, $3, $4, $5)',
            params: ['ORD-001', 'CUST-001', 99.99, 'PENDING', '2025-12-22T10:00:00Z']
        }]
    },
    eventStore: {
        eventStoreName: 'order_events',
        eventType: 'OrderCreated',
        eventData: { orderId: 'ORD-001', customerId: 'CUST-001', amount: 99.99 },
        validFrom: '2025-12-22T10:00:00Z'
    },
    outbox: {
        queueName: 'orders',
        message: { eventType: 'OrderCreated', orderId: 'ORD-001' }
    }
});

// Result: Order + Event + Outbox all committed in single ACID transaction
```

**Pros:**
- ✅ **True ACID guarantees** (single database transaction)
- ✅ **Pure REST API** (no SDK required, no callbacks)
- ✅ **Best performance** (single HTTP call, no callback overhead)
- ✅ **Technology agnostic** (any client can send SQL via REST)
- ✅ **Simplest client** (single POST request with SQL + event + outbox)
- ✅ **PeeGeeQ remains generic** (executes arbitrary SQL)
- ✅ **No anti-patterns** (no HTTP callbacks in transaction)

**Cons:**
- ⚠️ **Requires same database** (domain and PeeGeeQ tables co-located)
- ⚠️ **SQL exposure** (client must construct SQL statements)
- ⚠️ **Limited to simple operations** (complex business logic requires Pattern 2)
- ⚠️ **No type safety** (SQL as strings in JSON)

**When to Use:**
- ✅ Domain tables co-located with PeeGeeQ tables
- ✅ Simple CRUD operations (INSERT, UPDATE, DELETE)
- ✅ ACID guarantees required
- ✅ Client can construct SQL statements
- ❌ Complex business logic (use Pattern 2 instead)

**Decision:** **SELECTED as Pattern 1** - Best solution for simple transactional operations when domain tables are in same database

---

### Selected Alternatives: Pattern 1 (Direct SQL) + Pattern 2 (Callback Hook) + Pattern 3 (Saga Orchestration)

**Pattern 1: Generic Callback Hook Pattern (Same Database)**

**Design:**
```
POST /api/v1/transactional/execute-with-callback
{
  "callbackUrl": "https://customer-app.com/api/order-handler",
  "callbackPayload": {...},
  "eventStore": {...},
  "outbox": {...}
}
```

**Pros:**
- ✅ **PeeGeeQ remains 100% generic** (no domain knowledge)
- ✅ ACID guarantees (single transaction)
- ✅ Customer controls business logic via callback
- ✅ Works for any domain

**Cons:**
- ⚠️ HTTP callback within database transaction (anti-pattern but necessary)
- ⚠️ Timeout risk (callback must complete quickly)

**Decision:** **SELECTED for Pattern 1** - Only way to achieve ACID with generic design

---

**Pattern 2: Saga Orchestration Pattern (Separate Databases)**

**Design:**
```
POST /api/v1/saga/execute
{
  "sagaId": "saga-123",
  "steps": [
    {"type": "HTTP_CALL", "url": "...", "compensationUrl": "..."},
    {"type": "EVENT_STORE_APPEND", ...},
    {"type": "OUTBOX_SEND", ...}
  ]
}
```

**Pros:**
- ✅ **PeeGeeQ remains 100% generic** (no domain knowledge)
- ✅ Works across separate databases
- ✅ Automatic compensation on failure
- ✅ Eventual consistency guarantees

**Cons:**
- ⚠️ Eventual consistency (not ACID)
- ⚠️ Complex saga orchestration

**Decision:** **SELECTED for Pattern 2** - Industry-standard solution for distributed transactions

---

## Decision Points

### Decision 1: Domain-Specific vs. Generic Endpoints

**Question:** Should we provide a single generic endpoint or domain-specific endpoints?

**Context:**

This is a fundamental architectural decision that affects API usability, security, maintainability, and developer experience. The choice impacts how clients interact with the API and how the server validates and processes requests.

**Options:**

#### Option A: Single Generic Endpoint

**Design:**
```http
POST /api/v1/transactional/execute
Content-Type: application/json

{
  "operations": [
    {
      "type": "INSERT_ENTITY",
      "table": "orders",
      "schema": "public",
      "data": {
        "id": "ORD-001",
        "customer_id": "CUST-001",
        "amount": 99.99,
        "status": "CREATED"
      }
    },
    {
      "type": "APPEND_EVENT",
      "eventStore": "order_events",
      "setupId": "peegeeq-main",
      "event": {
        "eventType": "OrderCreated",
        "eventData": {...},
        "validFrom": "2025-12-22T10:00:00Z"
      }
    },
    {
      "type": "SEND_OUTBOX",
      "topic": "orders",
      "message": {...}
    }
  ],
  "transactionId": "txn-abc123"
}
```

**Pros:**
- ✅ Single endpoint to maintain
- ✅ Highly flexible - supports any combination of operations
- ✅ Extensible - new operation types can be added without new endpoints
- ✅ Powerful - clients can compose complex transactions

**Cons:**
- ❌ **Security Risk:** Clients can specify arbitrary tables and SQL operations
- ❌ **Complex Validation:** Must validate each operation type differently
- ❌ **Poor Type Safety:** Generic structure makes compile-time validation impossible
- ❌ **Difficult to Document:** OpenAPI spec becomes complex and unclear
- ❌ **Error-Prone:** Clients must know internal table structures and schemas
- ❌ **SQL Injection Risk:** Table/column names from client input
- ❌ **Authorization Complexity:** Hard to enforce fine-grained permissions
- ❌ **Poor Developer Experience:** Requires deep knowledge of PeeGeeQ internals

**Example Security Issue:**
```json
{
  "operations": [
    {
      "type": "INSERT_ENTITY",
      "table": "users; DROP TABLE orders; --",  // SQL injection attempt
      "data": {...}
    }
  ]
}
```

#### Option B: Domain-Specific Endpoints

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json

{
  "order": {
    "orderId": "ORD-001",
    "customerId": "CUST-001",
    "amount": 99.99,
    "items": [...]
  },
  "event": {
    "eventType": "OrderCreated",
    "eventData": {...},
    "validFrom": "2025-12-22T10:00:00Z"
  },
  "options": {
    "sendToOutbox": true,
    "appendToEventStore": true
  }
}
```

**Pros:**
- ✅ **Type Safe:** Strong typing for each domain entity
- ✅ **Secure:** No arbitrary table/SQL access
- ✅ **Clear Semantics:** Endpoint name describes exactly what it does
- ✅ **Easy Validation:** Domain-specific validation rules
- ✅ **Better Documentation:** Clear OpenAPI specs per endpoint
- ✅ **Fine-Grained Authorization:** Permission per domain operation
- ✅ **Excellent Developer Experience:** Self-documenting, IDE autocomplete
- ✅ **Maintainable:** Each endpoint has focused responsibility

**Cons:**
- ⚠️ **More Endpoints:** Requires one endpoint per domain entity
- ⚠️ **Less Flexible:** Cannot compose arbitrary operations
- ⚠️ **More Code:** Separate handler per domain

**Decision:** **Option B - Domain-Specific Endpoints**

**Detailed Rationale:**

1. **Security First**
   - Generic endpoints expose internal database structure
   - Risk of SQL injection through table/column names
   - Difficult to prevent unauthorized data access
   - Domain-specific endpoints hide implementation details

2. **Type Safety and Validation**
   - Domain-specific DTOs enable compile-time validation
   - JSON schema validation is straightforward
   - Business rules are domain-specific and easier to enforce
   - Generic validation is complex and error-prone

3. **Developer Experience**
   - Clear, self-documenting API
   - IDE autocomplete for request structure
   - OpenAPI documentation is clear and specific
   - Easier to understand and use correctly

4. **Authorization Model**
   - Fine-grained permissions: `CREATE_ORDER`, `CREATE_TRADE`, etc.
   - Easy to implement role-based access control
   - Audit logs are more meaningful
   - Generic endpoint requires complex permission logic

5. **Maintainability**
   - Each handler has single responsibility
   - Easy to add domain-specific logic
   - Testing is straightforward
   - Code is more readable and maintainable

6. **Industry Best Practices**
   - RESTful APIs favor resource-specific endpoints
   - Domain-driven design principles
   - Follows established patterns (Stripe, GitHub, AWS APIs)

**Trade-offs Accepted:**

- **More Endpoints:** This is acceptable because:
  - Each endpoint is focused and maintainable
  - Number of core business domains is limited (orders, trades, inventory, etc.)
  - Benefits far outweigh the cost of additional endpoints

- **Less Flexibility:** This is acceptable because:
  - Clients shouldn't need arbitrary transaction composition
  - Common patterns are well-supported
  - SDK users can still use `appendInTransaction()` directly for custom needs

**Implementation Strategy:**

1. **Start with Core Domains:**
   - `/api/v1/transactional/orders`
   - `/api/v1/transactional/trades`
   - `/api/v1/transactional/inventory-reservations`

2. **Standardize Patterns:**
   - Common request structure: `{ entity, event, options }`
   - Common response structure: `{ entityId, eventId, transactionTime, status }`
   - Shared validation framework
   - Shared error handling

3. **Extensibility:**
   - New domains added as needed
   - Template handler for rapid development
   - Code generation for boilerplate

**Comparison Table:**

| Aspect | Generic Endpoint | Domain-Specific Endpoints |
|--------|------------------|---------------------------|
| Security | ⚠️ High risk | ✅ Secure |
| Type Safety | ❌ None | ✅ Strong |
| Validation | ❌ Complex | ✅ Simple |
| Documentation | ⚠️ Difficult | ✅ Clear |
| Developer Experience | ⚠️ Poor | ✅ Excellent |
| Authorization | ❌ Complex | ✅ Fine-grained |
| Maintainability | ⚠️ Difficult | ✅ Easy |
| Flexibility | ✅ High | ⚠️ Limited |
| Number of Endpoints | ✅ One | ⚠️ Multiple |

**Conclusion:** Domain-specific endpoints provide superior security, developer experience, and maintainability at the acceptable cost of additional endpoints.

### Decision 2: Synchronous vs. Asynchronous Response

**Question:** Should the endpoint wait for transaction commit or return immediately?

**Context:**

This decision affects client complexity, user experience, system architecture, and error handling. The choice between synchronous and asynchronous responses has significant implications for how clients interact with the API and how the system handles failures.

**Options:**

#### Option A: Synchronous Response

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json

{
  "order": {...},
  "event": {...}
}

# Client waits...

HTTP/1.1 201 Created
Location: /api/v1/orders/ORD-001

{
  "orderId": "ORD-001",
  "eventId": "evt-123",
  "transactionTime": "2025-12-22T10:30:00.123Z",
  "status": "COMMITTED"
}
```

**Flow:**
```
Client                          Server
  │                               │
  ├─► POST /transactional/orders  │
  │                               ├─► BEGIN TRANSACTION
  │                               ├─► INSERT orders
  │                               ├─► INSERT events
  │                               ├─► INSERT outbox
  │                               ├─► COMMIT
  │   ◄─────────────────────────┤
  │   201 Created                │
  │   { orderId, eventId, ... }  │
```

**Pros:**
- ✅ **Simple Client Code:** Single request, immediate result
- ✅ **Immediate Confirmation:** Client knows transaction succeeded
- ✅ **No Polling Required:** No additional requests needed
- ✅ **Clear Error Handling:** Errors returned immediately in response
- ✅ **REST Best Practice:** Standard pattern for resource creation (POST → 201)
- ✅ **Transactional Semantics:** Client knows exact transaction outcome
- ✅ **No State Management:** Server doesn't track pending transactions
- ✅ **Easier Testing:** Synchronous tests are simpler

**Cons:**
- ⚠️ **Client Waits:** HTTP connection held during transaction
- ⚠️ **Timeout Risk:** Long transactions could timeout
- ⚠️ **Connection Resources:** Holds connection during processing

**Performance Analysis:**
```
Typical Transaction Duration:
- Request parsing:        1-2ms
- Validation:            1-2ms
- Connection acquire:    1-2ms
- BEGIN TRANSACTION:     <1ms
- INSERT orders:         3-5ms
- INSERT events:         3-5ms
- INSERT outbox:         3-5ms
- COMMIT:                1-2ms
- Response serialize:    1-2ms
- Network I/O:           5-10ms
─────────────────────────────
Total:                   20-40ms (p95: <50ms)
```

**Verdict:** 20-40ms is well within acceptable HTTP response time.

#### Option B: Asynchronous Response

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json

{
  "order": {...},
  "event": {...}
}

# Immediate response

HTTP/1.1 202 Accepted
Location: /api/v1/transactions/txn-abc123

{
  "transactionId": "txn-abc123",
  "status": "PENDING",
  "statusUrl": "/api/v1/transactions/txn-abc123/status"
}

# Client must poll for status

GET /api/v1/transactions/txn-abc123/status

HTTP/1.1 200 OK

{
  "transactionId": "txn-abc123",
  "status": "COMMITTED",
  "orderId": "ORD-001",
  "eventId": "evt-123",
  "transactionTime": "2025-12-22T10:30:00.123Z"
}
```

**Flow:**
```
Client                          Server
  │                               │
  ├─► POST /transactional/orders  │
  │   ◄─────────────────────────┤
  │   202 Accepted               │
  │   { transactionId, ... }     │
  │                               ├─► BEGIN TRANSACTION (async)
  │                               ├─► INSERT orders
  │                               ├─► INSERT events
  │                               ├─► INSERT outbox
  │                               ├─► COMMIT
  │                               │
  ├─► GET /transactions/txn-123/status
  │   ◄─────────────────────────┤
  │   200 OK                     │
  │   { status: "COMMITTED" }    │
```

**Pros:**
- ✅ **Immediate Response:** Client doesn't wait for transaction
- ✅ **No Timeout Risk:** Long transactions won't timeout HTTP connection
- ✅ **Better for Long Operations:** Suitable for complex transactions

**Cons:**
- ❌ **Complex Client Code:** Must implement polling logic
- ❌ **Multiple Requests:** Initial request + polling requests
- ❌ **State Management:** Server must track pending transactions
- ❌ **Eventual Consistency:** Client doesn't know immediate outcome
- ❌ **Error Handling Complexity:** Errors discovered during polling
- ❌ **Resource Overhead:** Must store transaction state
- ❌ **Polling Inefficiency:** Wastes network/server resources
- ❌ **Timeout Ambiguity:** Client doesn't know if transaction succeeded after timeout

**Client Code Comparison:**

**Synchronous (Simple):**
```javascript
try {
  const response = await fetch('/api/v1/transactional/orders', {
    method: 'POST',
    body: JSON.stringify({ order, event })
  });

  if (response.ok) {
    const result = await response.json();
    console.log('Order created:', result.orderId);
    // Done! Transaction committed.
  } else {
    console.error('Order failed:', await response.text());
    // Transaction rolled back.
  }
} catch (error) {
  console.error('Network error:', error);
  // Unknown state - may need to check if order exists.
}
```

**Asynchronous (Complex):**
```javascript
// Step 1: Submit transaction
const submitResponse = await fetch('/api/v1/transactional/orders', {
  method: 'POST',
  body: JSON.stringify({ order, event })
});

const { transactionId, statusUrl } = await submitResponse.json();

// Step 2: Poll for status
let status = 'PENDING';
let attempts = 0;
const maxAttempts = 30;

while (status === 'PENDING' && attempts < maxAttempts) {
  await sleep(1000); // Wait 1 second

  const statusResponse = await fetch(statusUrl);
  const statusData = await statusResponse.json();
  status = statusData.status;
  attempts++;

  if (status === 'COMMITTED') {
    console.log('Order created:', statusData.orderId);
    // Done! Transaction committed.
    break;
  } else if (status === 'FAILED') {
    console.error('Order failed:', statusData.error);
    // Transaction rolled back.
    break;
  }
}

if (attempts >= maxAttempts) {
  console.error('Timeout waiting for transaction');
  // Unknown state - transaction may still be processing.
}
```

**Decision:** **Option A - Synchronous Response**

**Detailed Rationale:**

1. **Transaction Duration is Acceptable**
   - Measured performance: 20-40ms typical, <50ms p95
   - Well within acceptable HTTP response time (<200ms)
   - No need for async complexity

2. **Simpler Client Code**
   - Single request, immediate result
   - No polling logic required
   - Easier error handling
   - Better developer experience

3. **REST Best Practices**
   - POST for resource creation typically returns 201 Created
   - Synchronous response is standard for CRUD operations
   - Async (202 Accepted) reserved for long-running operations

4. **Transactional Semantics**
   - Client knows exact transaction outcome immediately
   - No ambiguity about transaction state
   - Clear success/failure indication

5. **No State Management**
   - Server doesn't need to track pending transactions
   - No cleanup of completed transaction records
   - Simpler server architecture

6. **Industry Patterns**
   - Stripe API: Synchronous for payment creation
   - GitHub API: Synchronous for repository creation
   - AWS API: Synchronous for most resource creation

**When Async Would Be Appropriate:**

Asynchronous responses are appropriate for:
- Long-running operations (>5 seconds)
- Batch processing (thousands of records)
- Complex workflows with multiple external dependencies
- Operations that may take unpredictable time

**Our Use Case:**
- Transaction duration: 20-40ms (predictable)
- Single database transaction (no external dependencies)
- ACID guarantees (immediate commit/rollback)

**Conclusion:** Synchronous response is the right choice for this use case.

**Fallback Strategy:**

If future use cases require longer transactions:
1. Add async endpoints separately: `/api/v1/transactional/async/orders`
2. Keep sync endpoints as default
3. Let clients choose based on their needs

**Performance Monitoring:**

Monitor transaction duration and add async support if:
- p95 latency exceeds 200ms
- Timeout errors become common
- Client feedback indicates need for async

### Decision 3: Outbox Inclusion

**Question:** Should outbox send be mandatory or optional?

**Context:**

The outbox pattern enables real-time event processing by downstream consumers. However, not all use cases require immediate event distribution. This decision affects transaction performance, system coupling, and use case flexibility.

**Background: Outbox Pattern**

The outbox pattern ensures reliable event delivery:
```
BEGIN TRANSACTION
  INSERT INTO orders (...)           -- Business data
  INSERT INTO order_events (...)     -- Bi-temporal audit trail
  INSERT INTO outbox (...)           -- Real-time event distribution
COMMIT

# Outbox processor polls outbox table
# Delivers events to consumers (queues, webhooks, etc.)
# Marks events as processed
```

**Use Cases:**

**Use Case 1: Real-Time Order Processing**
```
Order Created → Outbox → Consumers:
  - Inventory Service (reserve stock)
  - Payment Service (charge customer)
  - Notification Service (send email)
  - Analytics Service (update metrics)
```
**Needs Outbox:** ✅ Yes - Immediate downstream processing required

**Use Case 2: Historical Data Import**
```
Importing 10,000 historical orders from legacy system:
  - Need orders in database
  - Need bi-temporal audit trail
  - Don't need to trigger real-time workflows
  - Don't want to spam downstream services
```
**Needs Outbox:** ❌ No - Historical data, no real-time processing

**Use Case 3: Batch Reconciliation**
```
End-of-day reconciliation:
  - Correcting order amounts
  - Updating order statuses
  - Recording corrections in event store
  - No need to trigger workflows
```
**Needs Outbox:** ❌ No - Batch operation, no real-time processing

**Use Case 4: Testing/Development**
```
Integration tests:
  - Creating test orders
  - Verifying bi-temporal queries
  - Don't want to trigger real consumers
  - Faster tests without outbox overhead
```
**Needs Outbox:** ❌ No - Test isolation

**Options:**

#### Option A: Mandatory Outbox

**Design:**
```json
{
  "order": {...},
  "event": {...}
  // No options - outbox always included
}
```

**Pros:**
- ✅ **Consistent Behavior:** Every transaction includes outbox
- ✅ **Simpler API:** No options to configure
- ✅ **Guaranteed Event Distribution:** Events always reach consumers

**Cons:**
- ❌ **No Flexibility:** Cannot disable for special cases
- ❌ **Performance Overhead:** Extra INSERT even when not needed
- ❌ **Testing Complexity:** Tests trigger real consumers
- ❌ **Batch Import Issues:** Historical imports spam consumers
- ❌ **Unnecessary Coupling:** Forces event distribution even when not wanted

**Performance Impact:**
```
With Outbox:    20-40ms (includes outbox INSERT)
Without Outbox: 15-30ms (25% faster)
```

#### Option B: Optional Outbox

**Design:**
```json
{
  "order": {...},
  "event": {...},
  "options": {
    "sendToOutbox": true  // or false
  }
}
```

**Pros:**
- ✅ **Flexibility:** Clients choose based on use case
- ✅ **Performance:** Skip outbox when not needed
- ✅ **Testing:** Easier test isolation
- ✅ **Batch Operations:** No consumer spam during imports
- ✅ **Use Case Specific:** Different needs, different configurations

**Cons:**
- ⚠️ **More Complex API:** Additional option to understand
- ⚠️ **Risk of Misconfiguration:** Client might forget to enable outbox

**Decision:** **Option B - Optional with Smart Default**

**Detailed Rationale:**

1. **Flexibility for Different Use Cases**
   - Real-time processing: `sendToOutbox: true`
   - Historical import: `sendToOutbox: false`
   - Batch reconciliation: `sendToOutbox: false`
   - Testing: `sendToOutbox: false`

2. **Performance Optimization**
   - Outbox INSERT adds 5-10ms to transaction
   - For batch operations (1000s of records), savings are significant
   - Historical imports don't need real-time distribution

3. **Testing Benefits**
   - Integration tests can disable outbox
   - No need to mock outbox consumers
   - Faster test execution
   - Better test isolation

4. **Batch Import Scenario**
   ```javascript
   // Importing 10,000 historical orders
   for (const order of historicalOrders) {
     await createOrder({
       order: order,
       event: createEvent(order),
       options: {
         sendToOutbox: false,        // Don't spam consumers
         appendToEventStore: true    // Keep audit trail
       }
     });
   }
   ```

5. **Industry Patterns**
   - AWS EventBridge: Optional event publishing
   - Kafka: Optional message production
   - RabbitMQ: Optional exchange routing

**Default Value: `sendToOutbox: true`**

**Rationale for Default:**
- Most common use case is real-time processing
- Opt-out (not opt-in) for safety
- Prevents accidental event loss
- Explicit `false` required to disable

**Configuration Examples:**

**Example 1: Real-Time Order (Default)**
```json
{
  "order": {...},
  "event": {...}
  // sendToOutbox defaults to true
}
```

**Example 2: Historical Import (Explicit)**
```json
{
  "order": {...},
  "event": {...},
  "options": {
    "sendToOutbox": false,
    "appendToEventStore": true
  }
}
```

**Example 3: Test Data (Explicit)**
```json
{
  "order": {...},
  "event": {...},
  "options": {
    "sendToOutbox": false,
    "appendToEventStore": false  // Skip both for speed
  }
}
```

**Validation Rules:**

1. **At Least One Operation Required:**
   ```javascript
   if (!options.sendToOutbox && !options.appendToEventStore) {
     // Warning: Only business entity will be persisted
     // No events distributed or stored
   }
   ```

2. **Outbox Requires Event:**
   ```javascript
   if (options.sendToOutbox && !event) {
     throw new ValidationError("sendToOutbox requires event data");
   }
   ```

**Monitoring and Metrics:**

Track usage patterns:
```
Metric: transactional_orders_outbox_enabled
  - true: 95%   (most requests use outbox)
  - false: 5%   (batch imports, tests)

Metric: transactional_orders_duration_ms
  - with_outbox: p95=45ms
  - without_outbox: p95=35ms
```

**Documentation Guidance:**

```markdown
## Options: sendToOutbox

**Default:** `true`

**When to use `true` (default):**
- Real-time order processing
- Events need immediate distribution to consumers
- Standard operational workflow

**When to use `false`:**
- Historical data import
- Batch reconciliation
- Integration testing
- Performance-critical batch operations

**Example:**
```json
{
  "options": {
    "sendToOutbox": false  // Skip outbox for historical import
  }
}
```
```

**Conclusion:** Optional outbox with `true` default provides flexibility while maintaining safety for the common case.

### Decision 4: Event Store Inclusion

**Question:** Should event store append be mandatory or optional?

**Context:**

The bi-temporal event store provides complete audit trail, historical queries, and regulatory compliance. However, not all use cases require bi-temporal event storage. This decision affects transaction performance, storage costs, and compliance capabilities.

**Background: Bi-Temporal Event Store**

The bi-temporal event store captures:
- **Valid Time:** When the event occurred in the real world (business time)
- **Transaction Time:** When the event was recorded in the database (system time)
- **Event Versioning:** Support for corrections and amendments
- **Complete Audit Trail:** Immutable history of all events

**Capabilities:**
```sql
-- Query events as of specific business time
SELECT * FROM order_events
WHERE valid_time <= '2025-01-15'
  AND (valid_until IS NULL OR valid_until > '2025-01-15');

-- Query events as of specific system time
SELECT * FROM order_events
WHERE transaction_time <= '2025-01-15T10:00:00';

-- Find all corrections to an event
SELECT * FROM order_events
WHERE original_event_id = 'evt-123'
ORDER BY version;
```

**Use Cases:**

**Use Case 1: Financial Trading (Regulatory Compliance)**
```
Requirements:
  - MiFID II compliance (EU financial regulation)
  - Complete audit trail required
  - Must track all corrections
  - Historical queries for investigations
```
**Needs Event Store:** ✅ Yes - Regulatory requirement

**Use Case 2: E-Commerce Orders (Standard Operations)**
```
Requirements:
  - Track order history
  - Support customer inquiries
  - Analyze order patterns
  - Compliance with consumer protection laws
```
**Needs Event Store:** ✅ Yes - Business and compliance need

**Use Case 3: Session Tracking (Ephemeral Data)**
```
Requirements:
  - Track user sessions
  - Store in database for active sessions
  - No need for historical queries
  - No regulatory requirements
  - Sessions expire after 24 hours
```
**Needs Event Store:** ❌ No - Ephemeral data, no audit trail needed

**Use Case 4: Cache Warming (Operational)**
```
Requirements:
  - Pre-populate cache with data
  - Store in database for cache coherence
  - No business value in history
  - No compliance requirements
```
**Needs Event Store:** ❌ No - Operational data, no audit trail needed

**Use Case 5: Development/Testing**
```
Requirements:
  - Create test data
  - Verify business logic
  - No need for bi-temporal queries
  - Faster test execution
```
**Needs Event Store:** ❌ No - Test isolation and performance

**Options:**

#### Option A: Mandatory Event Store

**Design:**
```json
{
  "order": {...},
  "event": {...}
  // Event store always included
}
```

**Pros:**
- ✅ **Complete Audit Trail:** Every transaction recorded
- ✅ **Regulatory Compliance:** Always compliant
- ✅ **Simpler API:** No options to configure
- ✅ **Historical Queries:** Always available

**Cons:**
- ❌ **No Flexibility:** Cannot disable for special cases
- ❌ **Performance Overhead:** Extra INSERT even when not needed
- ❌ **Storage Costs:** Stores events even for ephemeral data
- ❌ **Testing Overhead:** Tests slower due to event store writes
- ❌ **Unnecessary for Some Use Cases:** Not all data needs audit trail

**Performance Impact:**
```
With Event Store:    20-40ms (includes event store INSERT)
Without Event Store: 12-25ms (40% faster)
```

**Storage Impact:**
```
1 million orders/month:
  - With event store: ~500MB/month (order + event)
  - Without event store: ~200MB/month (order only)
  - Difference: ~300MB/month (~3.6GB/year)
```

#### Option B: Optional Event Store

**Design:**
```json
{
  "order": {...},
  "event": {...},
  "options": {
    "appendToEventStore": true  // or false
  }
}
```

**Pros:**
- ✅ **Flexibility:** Clients choose based on use case
- ✅ **Performance:** Skip event store when not needed
- ✅ **Cost Optimization:** Reduce storage for ephemeral data
- ✅ **Testing:** Faster tests without event store
- ✅ **Use Case Specific:** Different needs, different configurations

**Cons:**
- ⚠️ **More Complex API:** Additional option to understand
- ⚠️ **Risk of Misconfiguration:** Client might forget to enable for compliance-critical data
- ⚠️ **Incomplete Audit Trail:** If disabled incorrectly

**Decision:** **Option B - Optional with Smart Default**

**Detailed Rationale:**

1. **Flexibility for Different Use Cases**
   - Financial trades: `appendToEventStore: true` (compliance)
   - E-commerce orders: `appendToEventStore: true` (business value)
   - Session tracking: `appendToEventStore: false` (ephemeral)
   - Cache warming: `appendToEventStore: false` (operational)
   - Testing: `appendToEventStore: false` (performance)

2. **Performance Optimization**
   - Event store INSERT adds 3-5ms to transaction
   - For high-volume ephemeral data, savings are significant
   - Testing is 40% faster without event store

3. **Cost Optimization**
   - Storage costs for bi-temporal data can be significant
   - Ephemeral data doesn't need long-term storage
   - Reduces database size and backup costs

4. **Testing Benefits**
   - Integration tests run faster
   - No need to clean up event store after tests
   - Better test isolation

5. **Industry Patterns**
   - Event sourcing: Optional event store per aggregate
   - CQRS: Separate write and read models
   - Audit logging: Configurable per entity type

**Default Value: `appendToEventStore: true`**

**Rationale for Default:**
- Most business operations need audit trail
- Opt-out (not opt-in) for safety
- Prevents accidental compliance violations
- Explicit `false` required to disable

**Configuration Examples:**

**Example 1: Financial Trade (Compliance-Critical)**
```json
{
  "trade": {...},
  "event": {...}
  // appendToEventStore defaults to true
  // Regulatory compliance ensured
}
```

**Example 2: Session Tracking (Ephemeral)**
```json
{
  "session": {...},
  "event": {...},
  "options": {
    "appendToEventStore": false,  // No audit trail needed
    "sendToOutbox": true          // But notify session manager
  }
}
```

**Example 3: Cache Warming (Operational)**
```json
{
  "cacheEntry": {...},
  "event": {...},
  "options": {
    "appendToEventStore": false,  // No audit trail needed
    "sendToOutbox": false         // No event distribution needed
  }
}
```

**Example 4: Test Data**
```json
{
  "order": {...},
  "event": {...},
  "options": {
    "appendToEventStore": false,  // Skip for speed
    "sendToOutbox": false         // Skip for isolation
  }
}
```

**Validation Rules:**

1. **At Least One Operation Required:**
   ```javascript
   if (!options.sendToOutbox && !options.appendToEventStore) {
     logger.warn("Only business entity will be persisted - no events");
     // This is valid but unusual
   }
   ```

2. **Event Store Requires Event:**
   ```javascript
   if (options.appendToEventStore && !event) {
     throw new ValidationError("appendToEventStore requires event data");
   }
   ```

3. **Compliance Warning:**
   ```javascript
   if (isComplianceCritical(entityType) && !options.appendToEventStore) {
     logger.warn("Compliance-critical entity without event store - verify this is intentional");
   }
   ```

**Compliance Safeguards:**

For compliance-critical domains, enforce event store:
```java
public class TransactionalTradeHandler {
    public void createTrade(RoutingContext ctx) {
        TransactionalTradeRequest request = parseRequest(ctx);

        // Enforce event store for trades (regulatory requirement)
        if (!request.getOptions().isAppendToEventStore()) {
            sendError(ctx, 400,
                "Event store is mandatory for trades (MiFID II compliance)");
            return;
        }

        // Continue with transaction...
    }
}
```

**Monitoring and Metrics:**

Track usage patterns:
```
Metric: transactional_orders_event_store_enabled
  - true: 98%   (most requests use event store)
  - false: 2%   (tests, ephemeral data)

Metric: transactional_orders_duration_ms
  - with_event_store: p95=45ms
  - without_event_store: p95=28ms

Metric: event_store_size_gb
  - Track growth over time
  - Alert if growth exceeds projections
```

**Documentation Guidance:**

```markdown
## Options: appendToEventStore

**Default:** `true`

**When to use `true` (default):**
- Business-critical operations (orders, trades, payments)
- Regulatory compliance requirements
- Need for historical queries and audit trail
- Event corrections and amendments required

**When to use `false`:**
- Ephemeral data (sessions, cache entries)
- Operational data without business value
- Integration testing (performance)
- High-volume data without audit requirements

**⚠️ Warning:** Disabling event store means:
- No bi-temporal queries
- No historical reconstruction
- No audit trail for compliance
- No support for event corrections

**Example:**
```json
{
  "options": {
    "appendToEventStore": false  // Only for ephemeral data
  }
}
```
```

**Performance Comparison:**

| Configuration | Duration | Storage | Use Case |
|---------------|----------|---------|----------|
| Both enabled | 40ms | 500MB/M | Standard operations |
| Event store only | 35ms | 300MB/M | No real-time processing |
| Outbox only | 30ms | 200MB/M | Real-time without audit |
| Both disabled | 25ms | 200MB/M | Testing, ephemeral data |

**Conclusion:** Optional event store with `true` default provides flexibility while maintaining compliance and audit trail for the common case.

### Decision 5: Idempotency Support

**Question:** Should we support idempotency keys?

**Context:**

Network failures, client retries, and distributed systems can cause duplicate requests. Idempotency ensures that processing the same request multiple times has the same effect as processing it once. This is critical for financial transactions and other operations where duplicates cause serious problems.

**Problem: Duplicate Transactions**

**Scenario 1: Network Timeout**
```
Client                          Server
  │                               │
  ├─► POST /transactional/orders  │
  │                               ├─► BEGIN TRANSACTION
  │                               ├─► INSERT order (ORD-001)
  │                               ├─► INSERT event
  │                               ├─► COMMIT ✅
  │   ◄─────────────────────────┤
  │   (network timeout)           │
  │                               │
  │   Client thinks it failed!    │
  │   Retries...                  │
  │                               │
  ├─► POST /transactional/orders  │
  │   (same order data)           │
  │                               ├─► BEGIN TRANSACTION
  │                               ├─► INSERT order (ORD-001)
  │                               ├─► ❌ DUPLICATE KEY ERROR
  │                               ├─► ROLLBACK
  │   ◄─────────────────────────┤
  │   500 Internal Server Error   │
```

**Result:** Order was created, but client thinks it failed!

**Scenario 2: Client Crash and Restart**
```
Client creates order → Crashes before receiving response
Client restarts → Retries order creation
Result: Duplicate order or error
```

**Scenario 3: User Double-Click**
```
User clicks "Place Order" button
User clicks again (impatient)
Result: Two orders created
```

**Impact:**
- Duplicate orders charged to customer
- Duplicate inventory reservations
- Duplicate payments processed
- Customer complaints and refunds
- Data integrity issues

**Options:**

#### Option A: No Idempotency Support

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json

{
  "order": {...},
  "event": {...}
}
```

**Pros:**
- ✅ **Simpler Implementation:** No idempotency logic needed
- ✅ **No Storage Overhead:** No need to cache responses

**Cons:**
- ❌ **Duplicate Risk:** Retries create duplicates
- ❌ **Poor User Experience:** Errors on legitimate retries
- ❌ **Financial Risk:** Duplicate charges
- ❌ **Data Integrity:** Duplicate records
- ❌ **Not Production-Ready:** Unsuitable for real-world use

**Verdict:** Not acceptable for production systems.

#### Option B: Optional Idempotency Key in Header

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json
Idempotency-Key: order-2025-12-22-abc123

{
  "order": {...},
  "event": {...}
}
```

**Server Logic:**
```java
String idempotencyKey = ctx.request().getHeader("Idempotency-Key");

if (idempotencyKey != null) {
    // Check if already processed
    CachedResponse cached = idempotencyCache.get(idempotencyKey);
    if (cached != null) {
        // Return cached response (same status code, body)
        ctx.response()
            .setStatusCode(cached.getStatusCode())
            .end(cached.getBody());
        return;
    }
}

// Process transaction
TransactionalOrderResponse response = executeTransaction(request);

if (idempotencyKey != null) {
    // Cache response for future retries
    idempotencyCache.put(idempotencyKey, new CachedResponse(201, response));
}
```

**Pros:**
- ✅ **Standard HTTP Pattern:** `Idempotency-Key` header is industry standard
- ✅ **Optional:** Clients choose when to use it
- ✅ **Backward Compatible:** Existing clients continue to work
- ✅ **Prevents Duplicates:** Retries return cached response
- ✅ **Safe Retries:** Clients can retry without fear

**Cons:**
- ⚠️ **Storage Overhead:** Must cache responses
- ⚠️ **Cache Management:** Need TTL and cleanup
- ⚠️ **Optional:** Clients might forget to use it

**Industry Examples:**
- **Stripe:** `Idempotency-Key` header (optional)
- **PayPal:** `PayPal-Request-Id` header (optional)
- **Square:** `idempotency_key` in request body (optional)

#### Option C: Mandatory Transaction ID in Request Body

**Design:**
```http
POST /api/v1/transactional/orders
Content-Type: application/json

{
  "transactionId": "order-2025-12-22-abc123",  // REQUIRED
  "order": {...},
  "event": {...}
}
```

**Pros:**
- ✅ **Always Idempotent:** Every request has transaction ID
- ✅ **No Duplicates:** Guaranteed idempotency

**Cons:**
- ❌ **Breaking Change:** Requires all clients to provide transaction ID
- ❌ **Client Burden:** Clients must generate unique IDs
- ❌ **Not Backward Compatible:** Existing clients break
- ❌ **Verbose:** Adds required field to every request

**Decision:** **Option B - Optional Idempotency Key in Header**

**Detailed Rationale:**

1. **Industry Standard Pattern**
   - `Idempotency-Key` header is widely adopted
   - Stripe, PayPal, Square all use similar patterns
   - Well-documented and understood
   - HTTP header is the right place for metadata

2. **Backward Compatibility**
   - Optional header doesn't break existing clients
   - Clients can adopt gradually
   - No migration required

3. **Client Control**
   - Clients decide when idempotency is needed
   - Simple requests don't need overhead
   - Critical requests (payments) use idempotency

4. **Clean API Design**
   - Idempotency is orthogonal to business logic
   - Header keeps request body focused on business data
   - Follows separation of concerns

5. **Flexibility**
   - Clients can generate keys however they want
   - UUID, timestamp-based, business-key-based
   - No server-imposed format

**Implementation Details:**

**Idempotency Key Format:**
```
Format: UUID v4 or client-generated unique string
Examples:
  - "550e8400-e29b-41d4-a716-446655440000"
  - "order-2025-12-22-customer-123-abc"
  - "payment-txn-20251222103000-xyz"

Requirements:
  - Max length: 255 characters
  - Allowed characters: alphanumeric, dash, underscore
  - Must be unique per client
```

**Cache Storage:**

**Option 1: In-Memory Cache (Simple)**
```java
// Caffeine cache with TTL
Cache<String, CachedResponse> idempotencyCache = Caffeine.newBuilder()
    .expireAfterWrite(24, TimeUnit.HOURS)
    .maximumSize(100_000)
    .build();
```

**Option 2: Redis Cache (Distributed)**
```java
// Redis for multi-instance deployments
String key = "idempotency:" + idempotencyKey;
String cachedJson = redis.get(key);
if (cachedJson != null) {
    return deserialize(cachedJson);
}

// After processing
redis.setex(key, 86400, serialize(response)); // 24 hour TTL
```

**Option 3: PostgreSQL Table (Persistent)**
```sql
CREATE TABLE idempotency_cache (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    status_code INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_idempotency_expires ON idempotency_cache(expires_at);
```

**Implementation:** Start with in-memory (Option 1), migrate to Redis (Option 2) for multi-instance.

**Cache TTL:**
```
Default: 24 hours

Rationale:
  - Covers most retry scenarios
  - Balances storage vs. safety
  - Clients should retry within 24 hours
  - After 24 hours, assume client abandoned request
```

**Handling Concurrent Requests:**

**Problem:** Two requests with same idempotency key arrive simultaneously.

**Solution:** Use distributed lock or database constraint.

```java
public void createOrder(RoutingContext ctx) {
    String idempotencyKey = ctx.request().getHeader("Idempotency-Key");

    if (idempotencyKey != null) {
        // Try to acquire lock
        if (!acquireLock(idempotencyKey)) {
            // Another request with same key is processing
            // Wait and retry, or return 409 Conflict
            sendError(ctx, 409, "Request with same idempotency key is processing");
            return;
        }

        try {
            // Check cache
            CachedResponse cached = idempotencyCache.get(idempotencyKey);
            if (cached != null) {
                returnCachedResponse(ctx, cached);
                return;
            }

            // Process transaction
            TransactionalOrderResponse response = executeTransaction(request);

            // Cache response
            idempotencyCache.put(idempotencyKey, new CachedResponse(201, response));

            // Return response
            ctx.response().setStatusCode(201).end(Json.encode(response));

        } finally {
            releaseLock(idempotencyKey);
        }
    } else {
        // No idempotency key - process normally
        executeTransaction(request);
    }
}
```

**Response Behavior:**

**First Request:**
```http
POST /api/v1/transactional/orders
Idempotency-Key: abc123

HTTP/1.1 201 Created
{
  "orderId": "ORD-001",
  "eventId": "evt-123",
  "status": "COMMITTED"
}
```

**Retry (Same Key):**
```http
POST /api/v1/transactional/orders
Idempotency-Key: abc123

HTTP/1.1 201 Created  (same response)
{
  "orderId": "ORD-001",
  "eventId": "evt-123",
  "status": "COMMITTED"
}
```

**Note:** Status code and body are identical to first request.

**Error Handling:**

**Question:** Should we cache error responses?

**Answer:** Yes, for client errors (4xx), No for server errors (5xx).

```java
if (statusCode >= 400 && statusCode < 500) {
    // Cache client errors (bad request, validation failure)
    // Client will get same error on retry
    idempotencyCache.put(idempotencyKey, new CachedResponse(statusCode, errorBody));
} else if (statusCode >= 500) {
    // Don't cache server errors
    // Client should retry and might succeed
}
```

**Monitoring:**

```
Metric: idempotency_cache_hits
  - Tracks how often cached responses are returned
  - High hit rate indicates retry behavior

Metric: idempotency_cache_size
  - Tracks cache memory usage
  - Alert if approaching limit

Metric: idempotency_concurrent_conflicts
  - Tracks concurrent requests with same key
  - Indicates client retry logic issues
```

**Documentation Guidance:**

```markdown
## Idempotency

**Header:** `Idempotency-Key` (optional)

**Purpose:** Prevents duplicate transactions when retrying requests.

**How it works:**
1. Client generates unique idempotency key
2. Client includes key in `Idempotency-Key` header
3. Server caches response for 24 hours
4. Retries with same key return cached response

**When to use:**
- Payment processing
- Order creation
- Any operation where duplicates cause problems
- Network timeout scenarios

**Example:**
```http
POST /api/v1/transactional/orders
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "order": {...},
  "event": {...}
}
```

**Key Requirements:**
- Max length: 255 characters
- Unique per client
- Format: UUID v4

**Retry Behavior:**
- Same key + same request = same response (cached)
- Same key + different request = 409 Conflict
- Different key = new transaction
```

**Conclusion:** Optional idempotency key in header provides industry-standard duplicate prevention while maintaining backward compatibility.

### Decision 6: Error Response Format

**Question:** What format should error responses use?

**Context:**

Error responses must provide enough information for clients to understand what went wrong, why it failed, and how to fix it. For transactional operations, it's critical to communicate whether the transaction was rolled back and which operation failed.

**Requirements:**

1. **Transaction Status:** Did the transaction commit or rollback?
2. **Failed Operation:** Which step in the transaction failed?
3. **Error Reason:** Why did it fail?
4. **Actionable Information:** How can the client fix it?
5. **Machine-Readable:** Error codes for programmatic handling
6. **Human-Readable:** Clear messages for debugging
7. **Consistency:** Compatible with existing PeeGeeQ error format

**Options:**

#### Option A: Simple String Message

**Design:**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: text/plain

Transaction failed: Constraint violation on order_events table
```

**Pros:**
- ✅ **Simple:** Easy to implement
- ✅ **Human-Readable:** Clear message

**Cons:**
- ❌ **Not Machine-Readable:** Can't parse programmatically
- ❌ **No Structure:** Missing critical information
- ❌ **No Transaction Status:** Client doesn't know if rolled back
- ❌ **No Failed Operation:** Can't identify which step failed
- ❌ **Poor Client Experience:** Hard to handle errors programmatically

**Example Client Code:**
```javascript
// Client must parse string to understand error
if (error.includes("Constraint violation")) {
  // Maybe a duplicate? Not sure...
}
```

**Verdict:** Not suitable for production APIs.

#### Option B: RFC 7807 Problem Details

**Design:**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/problem+json

{
  "type": "https://peegeeq.dev/errors/transaction-failed",
  "title": "Transaction Failed",
  "status": 500,
  "detail": "Event store append failed due to constraint violation",
  "instance": "/api/v1/transactional/orders/ORD-001"
}
```

**RFC 7807 Specification:**
- `type`: URI identifying the problem type
- `title`: Short, human-readable summary
- `status`: HTTP status code
- `detail`: Human-readable explanation
- `instance`: URI identifying the specific occurrence

**Pros:**
- ✅ **Industry Standard:** RFC 7807 is widely adopted
- ✅ **Structured:** Well-defined format
- ✅ **Extensible:** Can add custom fields
- ✅ **Machine-Readable:** Clients can parse programmatically

**Cons:**
- ⚠️ **Generic:** Doesn't capture transaction-specific information
- ⚠️ **No Transaction Status:** Doesn't indicate rollback
- ⚠️ **No Failed Operation:** Doesn't identify which step failed
- ⚠️ **Requires Documentation:** Need to document all problem types
- ⚠️ **Content-Type:** Requires `application/problem+json`

**Extension for Transaction Details:**
```json
{
  "type": "https://peegeeq.dev/errors/transaction-failed",
  "title": "Transaction Failed",
  "status": 500,
  "detail": "Event store append failed due to constraint violation",
  "instance": "/api/v1/transactional/orders/ORD-001",
  "transactionRolledBack": true,
  "failedOperation": "EVENT_STORE_APPEND",
  "errorCode": "CONSTRAINT_VIOLATION"
}
```

**Verdict:** Good standard, but needs extensions for transaction details.

#### Option C: Custom Error Format (with RFC 7807 Inspiration)

**Design:**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "error": "TRANSACTION_FAILED",
  "message": "Transaction failed: Event store append failed due to constraint violation",
  "details": {
    "failedOperation": "EVENT_STORE_APPEND",
    "reason": "Duplicate event ID",
    "transactionRolledBack": true,
    "orderId": "ORD-001",
    "attemptedEventId": "evt-123"
  },
  "timestamp": "2025-12-22T10:30:00.123456Z",
  "requestId": "req-550e8400-e29b-41d4-a716-446655440000"
}
```

**Pros:**
- ✅ **Transaction-Specific:** Captures rollback status
- ✅ **Failed Operation:** Identifies which step failed
- ✅ **Detailed Context:** Includes relevant IDs and data
- ✅ **Machine-Readable:** Error codes for programmatic handling
- ✅ **Human-Readable:** Clear messages for debugging
- ✅ **Compatible:** Matches existing PeeGeeQ error format
- ✅ **Standard Content-Type:** Uses `application/json`

**Cons:**
- ⚠️ **Not RFC Standard:** Custom format
- ⚠️ **Requires Documentation:** Need to document error codes

**Decision:** **Option C - Custom Error Format (with RFC 7807 Inspiration)**

**Detailed Rationale:**

1. **Transaction-Specific Information**
   - `transactionRolledBack`: Critical for client to know transaction state
   - `failedOperation`: Helps identify which step failed
   - `reason`: Explains why it failed
   - This information is essential for transactional APIs

2. **Compatibility with Existing PeeGeeQ Format**
   - Maintains consistency across PeeGeeQ APIs
   - Clients already familiar with format
   - No migration needed for existing error handling

3. **Machine-Readable Error Codes**
   - `error` field contains enum-like error code
   - Clients can switch on error code
   - Enables programmatic error handling

4. **Human-Readable Messages**
   - `message` field provides clear explanation
   - Useful for logging and debugging
   - Helps developers understand issues

5. **Contextual Details**
   - `details` object contains operation-specific information
   - Includes relevant IDs (orderId, eventId, etc.)
   - Helps with troubleshooting

6. **Request Tracing**
   - `requestId` enables correlation across logs
   - Useful for distributed tracing
   - Helps support team investigate issues

**Error Response Structure:**

```typescript
interface ErrorResponse {
  // Machine-readable error code (enum-like)
  error: string;

  // Human-readable error message
  message: string;

  // Operation-specific details
  details: {
    // Which operation failed (if transaction)
    failedOperation?: string;

    // Why it failed
    reason?: string;

    // Was transaction rolled back?
    transactionRolledBack?: boolean;

    // Additional context (operation-specific)
    [key: string]: any;
  };

  // When the error occurred
  timestamp: string;  // ISO-8601

  // Request ID for tracing
  requestId: string;
}
```

**Error Codes:**

**Transaction Errors:**
```
TRANSACTION_FAILED          - Generic transaction failure
TRANSACTION_TIMEOUT         - Transaction exceeded timeout
TRANSACTION_DEADLOCK        - Database deadlock detected
TRANSACTION_SERIALIZATION   - Serialization failure (retry)
```

**Validation Errors:**
```
VALIDATION_FAILED           - Request validation failed
INVALID_ORDER_DATA          - Order data is invalid
INVALID_EVENT_DATA          - Event data is invalid
MISSING_REQUIRED_FIELD      - Required field missing
```

**Business Logic Errors:**
```
DUPLICATE_ORDER             - Order ID already exists
INSUFFICIENT_INVENTORY      - Not enough inventory
INVALID_CUSTOMER            - Customer doesn't exist
PAYMENT_FAILED              - Payment processing failed
```

**Database Errors:**
```
CONSTRAINT_VIOLATION        - Database constraint violated
FOREIGN_KEY_VIOLATION       - Foreign key constraint violated
UNIQUE_VIOLATION            - Unique constraint violated
```

**System Errors:**
```
DATABASE_UNAVAILABLE        - Database connection failed
CONNECTION_POOL_EXHAUSTED   - No available connections
INTERNAL_SERVER_ERROR       - Unexpected server error
```

**Error Response Examples:**

**Example 1: Duplicate Order**
```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "error": "DUPLICATE_ORDER",
  "message": "Order with ID 'ORD-001' already exists",
  "details": {
    "failedOperation": "ORDER_INSERT",
    "reason": "Unique constraint violation on orders.id",
    "transactionRolledBack": true,
    "orderId": "ORD-001",
    "existingOrderCreatedAt": "2025-12-22T09:00:00Z"
  },
  "timestamp": "2025-12-22T10:30:00.123456Z",
  "requestId": "req-abc123"
}
```

**Example 2: Event Store Constraint Violation**
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "error": "TRANSACTION_FAILED",
  "message": "Transaction failed: Event store append failed due to constraint violation",
  "details": {
    "failedOperation": "EVENT_STORE_APPEND",
    "reason": "Duplicate event ID 'evt-123'",
    "transactionRolledBack": true,
    "orderId": "ORD-001",
    "attemptedEventId": "evt-123",
    "conflictingEventId": "evt-123"
  },
  "timestamp": "2025-12-22T10:30:00.123456Z",
  "requestId": "req-def456"
}
```

**Example 3: Validation Error**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed: Order amount must be positive",
  "details": {
    "failedOperation": "REQUEST_VALIDATION",
    "reason": "Order amount must be greater than zero",
    "transactionRolledBack": false,
    "field": "order.amount",
    "providedValue": -99.99,
    "constraint": "amount > 0"
  },
  "timestamp": "2025-12-22T10:30:00.123456Z",
  "requestId": "req-ghi789"
}
```

**Example 4: Database Unavailable**
```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "error": "DATABASE_UNAVAILABLE",
  "message": "Database connection failed: Connection timeout",
  "details": {
    "failedOperation": "CONNECTION_ACQUIRE",
    "reason": "Connection pool exhausted - all connections in use",
    "transactionRolledBack": false,
    "poolSize": 32,
    "activeConnections": 32,
    "waitingRequests": 15
  },
  "timestamp": "2025-12-22T10:30:00.123456Z",
  "requestId": "req-jkl012"
}
```

**Client Error Handling:**

```javascript
async function createOrder(orderData, eventData) {
  try {
    const response = await fetch('/api/v1/transactional/orders', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': generateIdempotencyKey()
      },
      body: JSON.stringify({ order: orderData, event: eventData })
    });

    if (response.ok) {
      const result = await response.json();
      return { success: true, data: result };
    } else {
      const error = await response.json();

      // Handle specific error codes
      switch (error.error) {
        case 'DUPLICATE_ORDER':
          // Order already exists - maybe fetch existing order
          return { success: false, reason: 'duplicate', orderId: error.details.orderId };

        case 'VALIDATION_FAILED':
          // Show validation error to user
          return { success: false, reason: 'validation', message: error.message };

        case 'TRANSACTION_FAILED':
          // Transaction rolled back - safe to retry
          if (error.details.transactionRolledBack) {
            return { success: false, reason: 'transaction_failed', canRetry: true };
          }
          break;

        case 'DATABASE_UNAVAILABLE':
          // System issue - retry with backoff
          return { success: false, reason: 'system_error', canRetry: true };

        default:
          // Unknown error
          return { success: false, reason: 'unknown', error: error };
      }
    }
  } catch (networkError) {
    // Network error - unknown state
    return { success: false, reason: 'network_error', canRetry: true };
  }
}
```

**Logging and Monitoring:**

```java
// Server-side logging
logger.error("Transaction failed: {}",
    Map.of(
        "error", "TRANSACTION_FAILED",
        "failedOperation", "EVENT_STORE_APPEND",
        "orderId", orderId,
        "requestId", requestId,
        "transactionRolledBack", true
    ),
    exception
);

// Metrics
metrics.counter("transaction_errors",
    Tags.of(
        "error_code", "TRANSACTION_FAILED",
        "failed_operation", "EVENT_STORE_APPEND"
    )
).increment();
```

**Documentation:**

```markdown
## Error Responses

All error responses follow this format:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable message",
  "details": {
    "failedOperation": "OPERATION_NAME",
    "reason": "Detailed reason",
    "transactionRolledBack": true,
    ...
  },
  "timestamp": "ISO-8601 timestamp",
  "requestId": "Unique request ID"
}
```

### Error Codes

| Code | HTTP Status | Description | Retry? |
|------|-------------|-------------|--------|
| DUPLICATE_ORDER | 409 | Order already exists | No |
| VALIDATION_FAILED | 400 | Invalid request data | No |
| TRANSACTION_FAILED | 500 | Transaction rolled back | Yes |
| DATABASE_UNAVAILABLE | 503 | Database connection failed | Yes |

### Transaction Rollback

When `transactionRolledBack: true`:
- All operations were rolled back
- No data was persisted
- Safe to retry the request
- No cleanup needed
```

**Conclusion:** Custom error format with transaction-specific fields provides the best balance of machine-readability, human-readability, and transaction semantics while maintaining compatibility with existing PeeGeeQ APIs.

---

## References

### Architecture Documentation

1. **PeeGeeQ Call Propagation Design**
   - **Path:** `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md`
   - **Content:** Layered architecture rules, module responsibilities, dependency rules, call propagation paths
   - **Relevance:** Defines the architectural principles this design adheres to
   - **Key Sections:**
     - Section 1: Layered Architecture Rules
     - Section 1.2: Dependency Rules
     - Section 1.6: Vert.x 5.x as the Primary Interface
     - Section 8.2: Bitemporal Core (notes on `appendInTransaction` being internal)

### Existing Implementations

2. **Spring Boot Integrated Example**
   - **Path:** `peegeeq-examples-spring/src/main/java/dev/mars/peegeeq/examples/springbootintegrated/`
   - **Pattern:** Order + Outbox + Event Store in single transaction
   - **Status:** Production-ready, battle-tested
   - **Key File:** `OrderService.java` (demonstrates `ConnectionProvider.withTransaction()` pattern)

3. **Spring Boot Bi-Temporal TX Example**
   - **Path:** `peegeeq-examples-spring/src/main/java/dev/mars/peegeeq/examples/springbootbitemporaltx/`
   - **Pattern:** Multi-store transaction coordination
   - **Status:** Advanced use case demonstration

4. **Integrated Pattern Guide**
   - **Path:** `peegeeq-examples/docs/INTEGRATED_PATTERN_GUIDE.md`
   - **Content:** Comprehensive guide to transaction patterns
   - **Status:** Complete documentation

### API Interfaces

1. **ConnectionProvider**
   - **Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/ConnectionProvider.java`
   - **Method:** `withTransaction(String clientId, Function<SqlConnection, Future<T>> operation)`
   - **Purpose:** Transaction coordination

2. **EventStore**
   - **Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/EventStore.java`
   - **Method:** `appendInTransaction(String eventType, T payload, Instant validTime, SqlConnection connection)`
   - **Purpose:** Bi-temporal event storage in transaction

3. **OutboxProducer**
   - **Path:** `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxProducer.java`
   - **Method:** `sendInTransaction(T message, SqlConnection connection)`
   - **Purpose:** Outbox pattern in transaction

### Related Documentation

1. **REST API Reference**
   - **Path:** `docs/PEEGEEQ_REST_API_REFERENCE.md`
   - **Section:** 8.3 Transaction Participation
   - **Status:** Documents current limitation

2. **Investigation Report**
   - **Path:** `docs/TRANSACTIONAL_REST_API_INVESTIGATION.md`
   - **Content:** Detailed technical analysis
   - **Status:** Complete

### External References

1. **PostgreSQL ACID Transactions**
   - https://www.postgresql.org/docs/current/tutorial-transactions.html

2. **REST API Best Practices**
   - https://restfulapi.net/

3. **Vert.x 5.x Transaction Patterns**
   - https://vertx.io/docs/vertx-pg-client/java/

4. **Idempotency in REST APIs**
   - https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header

---

## Appendix A: Handler Implementation Example

### Complete Handler Implementation

```java
package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.outbox.OutboxProducer;
import dev.mars.peegeeq.rest.dto.TransactionalOrderRequest;
import dev.mars.peegeeq.rest.dto.TransactionalOrderResponse;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class TransactionalOrderHandler {
    private static final Logger logger = LoggerFactory.getLogger(TransactionalOrderHandler.class);

    private final ConnectionProvider connectionProvider;
    private final EventStore<Object> eventStore;
    private final OutboxProducer<Object> outboxProducer;
    private final ObjectMapper objectMapper;

    public TransactionalOrderHandler(
            ConnectionProvider connectionProvider,
            EventStore<Object> eventStore,
            OutboxProducer<Object> outboxProducer,
            ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider;
        this.eventStore = eventStore;
        this.outboxProducer = outboxProducer;
        this.objectMapper = objectMapper;
    }

    public void createOrder(RoutingContext ctx) {
        try {
            // Parse request
            String body = ctx.body().asString();
            TransactionalOrderRequest request =
                objectMapper.readValue(body, TransactionalOrderRequest.class);

            // Validate request
            validateRequest(request);

            // Execute transaction
            executeTransaction(request)
                .thenAccept(response -> {
                    logger.info("Transaction committed successfully: orderId={}, eventId={}",
                        response.getOrderId(), response.getEventId());

                    ctx.response()
                        .setStatusCode(201)
                        .putHeader("Content-Type", "application/json")
                        .putHeader("Location", "/api/v1/orders/" + response.getOrderId())
                        .end(Json.encode(response));
                })
                .exceptionally(error -> {
                    logger.error("Transaction failed: {}", error.getMessage(), error);
                    sendError(ctx, 500, "Transaction failed: " + error.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Request processing failed: {}", e.getMessage(), e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }

    private CompletableFuture<TransactionalOrderResponse> executeTransaction(
            TransactionalOrderRequest request) {

        return connectionProvider.withTransaction("peegeeq-main", connection -> {
            logger.debug("Starting transaction for order: {}", request.getOrder().getOrderId());

            // Step 1: Insert order
            return insertOrder(request.getOrder(), connection)

            // Step 2: Append event to bi-temporal store (if requested)
            .compose(order -> {
                if (request.getOptions().isAppendToEventStore()) {
                    return Future.fromCompletionStage(
                        eventStore.appendInTransaction(
                            request.getEvent().getEventType(),
                            request.getEvent().getEventData(),
                            request.getEvent().getValidFrom(),
                            connection
                        )
                    ).map(event -> order);
                }
                return Future.succeededFuture(order);
            })

            // Step 3: Send to outbox (if requested)
            .compose(order -> {
                if (request.getOptions().isSendToOutbox()) {
                    return Future.fromCompletionStage(
                        outboxProducer.sendInTransaction(
                            request.getEvent().getEventData(),
                            connection
                        )
                    ).map(v -> order);
                }
                return Future.succeededFuture(order);
            })

            // Step 4: Build response
            .map(order -> TransactionalOrderResponse.builder()
                .orderId(order.getId())
                .eventId(/* event ID from step 2 */)
                .transactionTime(Instant.now())
                .status("COMMITTED")
                .build());

        }).toCompletionStage().toCompletableFuture();
    }

    private Future<Order> insertOrder(OrderData orderData, SqlConnection connection) {
        String sql = """
            INSERT INTO orders (id, customer_id, amount, status, created_at)
            VALUES ($1, $2, $3, $4, $5)
            """;

        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                orderData.getOrderId(),
                orderData.getCustomerId(),
                orderData.getAmount(),
                "CREATED",
                Instant.now()
            ))
            .map(result -> new Order(orderData));
    }

    private void validateRequest(TransactionalOrderRequest request) {
        if (request.getOrder() == null) {
            throw new IllegalArgumentException("order is required");
        }
        if (request.getEvent() == null) {
            throw new IllegalArgumentException("event is required");
        }
        // Additional validation...
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(Json.encode(Map.of("error", message)));
    }
}
```

---

## Appendix B: Request/Response DTOs

### Request DTOs

```java
public class TransactionalOrderRequest {
    private OrderData order;
    private EventData event;
    private TransactionOptions options;

    // Getters/setters/builder
}

public class OrderData {
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private List<OrderItem> items;
    private Address shippingAddress;

    // Getters/setters/builder
}

public class EventData {
    private String eventType;
    private Object eventData;
    private Instant validFrom;
    private String correlationId;
    private String causationId;
    private Map<String, String> metadata;

    // Getters/setters/builder
}

public class TransactionOptions {
    private boolean sendToOutbox = true;
    private boolean appendToEventStore = true;
    private String outboxTopic;

    // Getters/setters/builder
}
```

### Response DTOs

```java
public class TransactionalOrderResponse {
    private String orderId;
    private String eventId;
    private Instant transactionTime;
    private String status;
    private OperationStatus operations;

    // Getters/setters/builder
}

public class OperationStatus {
    private boolean orderCreated;
    private boolean eventStored;
    private boolean outboxSent;

    // Getters/setters/builder
}
```

---

## Appendix C: Detailed Technical Investigation

This appendix contains the complete technical investigation that informed this design.

### Investigation Scope

**Date:** December 22, 2025
**Investigator:** AI Assistant
**Objective:** Analyze transactional REST API pattern where business entity + event are sent in single request and executed in same database transaction server-side.

### Technical Architecture Analysis

#### Transaction Coordination Flow

```
┌─────────────────────────────────────────────────────────────┐
│ REST Client                                                  │
│ POST /api/v1/transactional/orders                           │
│ { businessEntity: {...}, event: {...} }                     │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ REST Handler (Server-Side)                                  │
│ - Parse request                                             │
│ - Validate business entity                                  │
│ - Validate event                                            │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ Transaction Coordinator                                     │
│ connectionProvider.withTransaction("client-id", conn -> {   │
│   // All operations use SAME connection                     │
│ })                                                          │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ PostgreSQL Transaction                                      │
│ BEGIN;                                                      │
│   INSERT INTO orders (...);              -- Business data   │
│   INSERT INTO order_events (...);        -- Bi-temporal     │
│   INSERT INTO outbox (...);              -- Real-time       │
│ COMMIT;  (or ROLLBACK on any failure)                      │
└─────────────────────────────────────────────────────────────┘
```

#### Key Components Identified

**1. ConnectionProvider** (`peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/ConnectionProvider.java`)
```java
<T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation);
```
- Manages transaction lifecycle
- Provides SqlConnection to all operations
- Automatic commit/rollback

**2. EventStore.appendInTransaction()** (`peegeeq-api/src/main/java/dev/mars/peegeeq/api/EventStore.java`)
```java
CompletableFuture<BiTemporalEvent<T>> appendInTransaction(
    String eventType, T payload, Instant validTime, SqlConnection connection);
```
- Participates in existing transaction
- Uses provided connection
- No separate transaction created

**3. Repository Pattern** (e.g., `OrderRepository.save(Order, SqlConnection)`)
```java
Future<Order> save(Order order, SqlConnection connection);
```
- Accepts connection parameter
- Participates in caller's transaction
- Standard pattern across all examples

### Use Case Analysis

#### Use Case 1: Order Processing
**Request:**
```json
{
  "order": {
    "orderId": "ORD-001",
    "customerId": "CUST-123",
    "items": [...]
  },
  "event": {
    "eventType": "OrderCreated",
    "eventData": {...}
  }
}
```

**Transaction:**
- Insert order into `orders` table
- Insert event into `order_events` bi-temporal store
- Insert event into `outbox` for real-time processing

#### Use Case 2: Trade Capture (Financial)
**Request:**
```json
{
  "trade": {
    "tradeId": "TRD-001",
    "symbol": "AAPL",
    "quantity": 100,
    "price": 150.00
  },
  "event": {
    "eventType": "TradeCaptured",
    "eventData": {...}
  }
}
```

**Transaction:**
- Insert trade into `trades` table
- Insert event into `trade_events` bi-temporal store
- Trigger settlement workflow via outbox

#### Use Case 3: Inventory Management
**Request:**
```json
{
  "reservation": {
    "productId": "PROD-001",
    "quantity": 5,
    "orderId": "ORD-001"
  },
  "event": {
    "eventType": "InventoryReserved",
    "eventData": {...}
  }
}
```

**Transaction:**
- Update inventory in `inventory` table
- Insert event into `inventory_events` bi-temporal store
- Notify warehouse via outbox

### Benefits Analysis

#### 1. ACID Guarantees
- ✅ All operations succeed together or fail together
- ✅ No partial updates
- ✅ Database ensures consistency
- ✅ Automatic rollback on any failure

#### 2. Simplified Client Code
**Before (Multiple Requests):**
```javascript
// Step 1: Create order
const orderResponse = await fetch('/api/v1/orders', {
  method: 'POST',
  body: JSON.stringify(order)
});

if (!orderResponse.ok) {
  throw new Error('Order creation failed');
}

// Step 2: Log event
const eventResponse = await fetch('/api/v1/eventstores/orders/events', {
  method: 'POST',
  body: JSON.stringify(event)
});

if (!eventResponse.ok) {
  // Order exists but event failed!
  // Need compensation logic...
  await fetch(`/api/v1/orders/${orderId}`, { method: 'DELETE' });
  throw new Error('Event logging failed');
}
```

**After (Single Request):**
```javascript
const response = await fetch('/api/v1/transactional/orders', {
  method: 'POST',
  body: JSON.stringify({ order, event })
});

if (!response.ok) {
  // Transaction rolled back - nothing persisted
  throw new Error('Transaction failed');
}

// Success - both order and event persisted
```

#### 3. Data Consistency
- ✅ Business data and events always in sync
- ✅ Complete audit trail guaranteed
- ✅ No orphaned records
- ✅ Regulatory compliance easier

#### 4. Performance
- ✅ Single network round-trip
- ✅ Single database transaction
- ✅ Reduced latency (no multiple HTTP calls)
- ✅ Lower network overhead

### Comparison with Existing Patterns

| Aspect | Current REST API | Proposed Transactional API | Spring Boot Example |
|--------|------------------|---------------------------|---------------------|
| **Requests** | Multiple (2-3) | Single | Single (internal) |
| **ACID** | ❌ No | ✅ Yes | ✅ Yes |
| **Client Complexity** | ⚠️ High | ✅ Low | ✅ Low (client) |
| **Partial Failures** | ⚠️ Possible | ❌ No | ❌ No |
| **Network Overhead** | ⚠️ High | ✅ Low | ✅ Low |
| **Data Consistency** | ⚠️ At risk | ✅ Guaranteed | ✅ Guaranteed |
| **Audit Trail** | ⚠️ Incomplete | ✅ Complete | ✅ Complete |
| **Implementation** | ✅ Exists | ⏳ Needed | ✅ Exists |

### Implementation Analysis

#### Required Components (All Exist)
- ✅ `ConnectionProvider.withTransaction()` - Transaction management
- ✅ `EventStore.appendInTransaction()` - Bi-temporal event storage
- ✅ `OutboxProducer.sendInTransaction()` - Outbox pattern
- ✅ Vert.x 5.x reactive patterns - Composable Futures
- ✅ PostgreSQL ACID transactions - Database support

#### New Components Needed
- ⏳ REST handler for transactional endpoints
- ⏳ Request/Response DTOs
- ⏳ Validation logic
- ⏳ Error handling
- ⏳ Integration tests

#### Estimated Effort
- **Phase 1 (POC):** 1-2 weeks - Single endpoint (orders)
- **Phase 2 (Production):** 2-3 weeks - Multiple endpoints, error handling
- **Phase 3 (Complete):** 2-3 weeks - Testing, documentation, deployment

**Total:** 5-8 weeks

### Risk Analysis

#### Low Risk
- ✅ Pattern proven in Spring Boot examples
- ✅ All infrastructure exists
- ✅ PostgreSQL ACID guarantees well-understood
- ✅ Vert.x 5.x patterns established

#### Medium Risk
- ⚠️ REST API changes require client updates (mitigated: new endpoints, backward compatible)
- ⚠️ Transaction duration must be monitored (mitigated: typical <50ms)
- ⚠️ Error handling complexity (mitigated: clear rollback semantics)

#### Mitigation Strategies
1. **Backward Compatibility:** New endpoints, existing endpoints unchanged
2. **Performance Monitoring:** Track transaction duration, alert on anomalies
3. **Clear Documentation:** Error codes, rollback behavior, retry guidance
4. **Gradual Rollout:** Start with single endpoint, expand based on feedback

### Investigation Conclusion

**Status:** ✅ **PROVEN AND READY FOR IMPLEMENTATION**

**Key Findings:**
1. Pattern is **already implemented** in Spring Boot examples
2. All required infrastructure **exists and is production-ready**
3. Provides **significant value** to REST API clients
4. **Low risk** with clear mitigation strategies
5. **Competitive advantage** over other message queue systems

**Priority:** **HIGH**

**Rationale:**
- Solves fundamental REST API limitation
- Enables ACID guarantees for REST clients
- Simplifies client-side code significantly
- Leverages existing, proven infrastructure
- Provides competitive advantage

---

## Appendix D: Proposed Call Propagation Grid Entry

This appendix shows how the transactional REST API endpoints will be documented in `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md` Section 9.11.

### 9.11 Transactional Operations

**Purpose:** Server-side transaction coordination for business entity + event + outbox operations in single ACID transaction.

**Pattern:** Domain-specific endpoints using `ConnectionProvider.withTransaction()` to coordinate multiple operations.

**Status:** All endpoints fully implemented and tested.

| REST Endpoint | REST Handler | Interface API | Core Implementation | Module | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST /api/v1/transactional/orders` | `TransactionalOrderHandler.createOrder()` | `ConnectionProvider.withTransaction()` + `EventStore.appendInTransaction()` + `OutboxProducer.sendInTransaction()` | `PgConnectionProvider` + `PgBiTemporalEventStore` + `OutboxProducer` | `peegeeq-rest` + `peegeeq-db` + `peegeeq-bitemporal` + `peegeeq-outbox` | **IMPLEMENTED** |
| `POST /api/v1/transactional/trades` | `TransactionalTradeHandler.createTrade()` | `ConnectionProvider.withTransaction()` + `EventStore.appendInTransaction()` + `OutboxProducer.sendInTransaction()` | `PgConnectionProvider` + `PgBiTemporalEventStore` + `OutboxProducer` | `peegeeq-rest` + `peegeeq-db` + `peegeeq-bitemporal` + `peegeeq-outbox` | **IMPLEMENTED** |
| `POST /api/v1/transactional/inventory-reservations` | `TransactionalInventoryHandler.reserveInventory()` | `ConnectionProvider.withTransaction()` + `EventStore.appendInTransaction()` + `OutboxProducer.sendInTransaction()` | `PgConnectionProvider` + `PgBiTemporalEventStore` + `OutboxProducer` | `peegeeq-rest` + `peegeeq-db` + `peegeeq-bitemporal` + `peegeeq-outbox` | **IMPLEMENTED** |

**Implementation Notes:**

All transactional endpoints follow the same pattern:

1. **Handler Layer** (`peegeeq-rest`)
   - Parse and validate HTTP request
   - Extract business entity data and event data
   - Call `ConnectionProvider.withTransaction()` to begin server-side transaction

2. **Transaction Coordination** (`peegeeq-db`)
   - `ConnectionProvider.withTransaction()` acquires connection from pool
   - Begins PostgreSQL transaction
   - Passes same `SqlConnection` to all operations
   - Commits on success, rolls back on any failure

3. **Business Entity Persistence** (Domain-specific repository)
   - Accepts `SqlConnection` parameter
   - Participates in caller's transaction
   - Inserts/updates business entity (e.g., orders, trades, inventory)

4. **Event Store Append** (`peegeeq-bitemporal`)
   - `EventStore.appendInTransaction()` accepts `SqlConnection` parameter
   - Participates in caller's transaction
   - Inserts bi-temporal event with valid time and transaction time

5. **Outbox Send** (`peegeeq-outbox`)
   - `OutboxProducer.sendInTransaction()` accepts `SqlConnection` parameter
   - Participates in caller's transaction
   - Inserts message into outbox for real-time processing

**Key Architectural Points:**

- ✅ **Adheres to Layered Architecture:** `peegeeq-rest` depends only on `peegeeq-api` and `peegeeq-runtime`
- ✅ **Uses Existing Interfaces:** No new interfaces required in `peegeeq-api`
- ✅ **Transaction Participation:** Uses existing `appendInTransaction()` and `sendInTransaction()` methods
- ✅ **Vert.x 5.x Native:** Uses Vert.x `Future` and `SqlConnection` types
- ✅ **Backward Compatible:** No changes to existing 49 REST endpoints

**Example Transaction Flow:**

```java
// TransactionalOrderHandler.java
return connectionProvider.withTransaction("peegeeq-main", connection -> {
    // Step 1: Insert order into database
    return insertOrder(request.getOrder(), connection)

    // Step 2: Append event to bi-temporal store (if requested)
    .compose(order -> {
        if (request.getOptions().isAppendToEventStore()) {
            return Future.fromCompletionStage(
                eventStore.appendInTransaction(
                    request.getEvent().getEventType(),
                    request.getEvent().getEventData(),
                    request.getEvent().getValidFrom(),
                    connection  // SAME connection
                )
            ).map(event -> order);
        }
        return Future.succeededFuture(order);
    })

    // Step 3: Send to outbox (if requested)
    .compose(order -> {
        if (request.getOptions().isSendToOutbox()) {
            return Future.fromCompletionStage(
                outboxProducer.sendInTransaction(
                    request.getEvent().getEventData(),
                    connection  // SAME connection
                )
            ).map(v -> order);
        }
        return Future.succeededFuture(order);
    })

    // Step 4: Build response
    .map(order -> buildResponse(order));

}).toCompletionStage().toCompletableFuture();
```

**Relationship to Existing Endpoints:**

This is a **new capability** that extends the existing REST API:

- **Existing Pattern:** Separate endpoints for business operations and event logging (no ACID guarantees across calls)
  - `POST /api/v1/queues/:setupId/:queueName/messages` (Section 9.2)
  - `POST /api/v1/eventstores/:setupId/:eventStoreName/events` (Section 9.4)

- **New Transactional Pattern:** Single endpoint for coordinated operations (ACID guarantees via server-side transaction)
  - `POST /api/v1/transactional/orders` (Section 9.11)
  - `POST /api/v1/transactional/trades` (Section 9.11)
  - `POST /api/v1/transactional/inventory-reservations` (Section 9.11)

**Note on `appendInTransaction()`:**

The Call Propagation Design document (Section 8.2) states that `appendInTransaction()` is "intentionally internal for coordinating with other database operations within a single transaction. Not a REST gap."

This transactional REST API **extends** that capability by:
- Creating domain-specific REST endpoints that coordinate transactions server-side
- Making the transactional pattern accessible to REST clients (JavaScript, Python, Go, etc.)
- **Not exposing** raw `SqlConnection` objects over HTTP (which would be impossible)
- Providing the same ACID guarantees to REST clients that SDK users already have

This is a **new capability**, not a gap closure. SDK users continue to use `appendInTransaction()` directly, while REST clients gain equivalent transactional capabilities through these new endpoints.

---

**Document Status:** READY FOR REVIEW
**Next Steps:** Review by stakeholders, approval, implementation
**Contact:** Mark Andrew Ray-Smith, Cityline Ltd


