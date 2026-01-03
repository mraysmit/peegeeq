User: peegeeq is a complex distributed messaging ststem. the module peegeeq-integration-tests is a key integration and smoke testing components review the code against the other code modules such as peegeeq-db and peegeeq-bitemporal and give me a detailed analysis of test coverage gaps in this critical verification module

Workspace: Collecting workspace informationBased on the analysis of the peegeeq-integration-tests module structure, the provided strategy documents, and the source code of core modules like peegeeq-db and peegeeq-bitemporal, here is a detailed analysis of the test coverage gaps.

### Executive Summary

The peegeeq-integration-tests module currently excels at **"Happy Path" verification**—ensuring that the REST API correctly wires together the Runtime, Native, and Database layers to perform basic operations (Create Setup, Send Message, Store Event).

However, significant gaps exist regarding **Resilience**, **Background Operations**, and **Advanced Temporal Features**. While peegeeq-db and peegeeq-bitemporal have unit/integration tests for these features internally, they are not adequately verified from an End-to-End (E2E) perspective in the smoke test suite.

---

### 1. Gap Analysis: peegeeq-db vs. Integration Tests

The peegeeq-db module contains critical infrastructure logic. The current smoke tests verify that the database *connects*, but they miss the operational complexities handled by this module.

#### A. Resilience & Circuit Breaking (Critical Gap)
*   **Source Context:** peegeeq-db contains `CircuitBreakerManager` and `BackpressureManager`.
*   **Current Coverage:** peegeeq-db has isolated tests (`CircuitBreakerManagerTest`), but peegeeq-integration-tests assumes a healthy database.
*   **The Gap:** There are no smoke tests verifying API behavior when the database is under stress or momentarily unavailable.
*   **Missing Scenario:**
    *   **Chaos Smoke Test:** Simulate a database connection failure (e.g., using a TestContainer proxy or pausing the container) and verify the REST API returns a `503 Service Unavailable` rather than hanging or returning a `500 Internal Server Error`.

#### B. Resource Cleanup & Lifecycle
*   **Source Context:** peegeeq-db contains `CleanupService` and logic in PeeGeeQDatabaseSetupService.java (lines 380-420) regarding `destroySetup` and resource cleanup on failure.
*   **Current Coverage:** Smoke tests create setups and delete them on success.
*   **The Gap:** The "Cleanup on Failure" logic in `PeeGeeQDatabaseSetupService` is complex. If a setup fails halfway (e.g., template creation succeeds but queue creation fails), the system attempts to rollback. This is not verified via the API.
*   **Missing Scenario:**
    *   **Setup Failure Recovery:** Send a `POST /setups` request with a valid database config but an invalid Queue config (triggering a failure). Verify that the API returns a 400/500 and, crucially, that **no zombie database schemas** remain in the PostgreSQL container.

#### C. Connection Pool Starvation
*   **Source Context:** peegeeq-db manages `PgPool` and connection limits.
*   **The Gap:** Smoke tests run sequentially. They do not verify that the `PeeGeeQRuntime` correctly configures the pool size exposed in peegeeq-db to handle concurrent REST requests without starving.

---

### 2. Gap Analysis: peegeeq-bitemporal vs. Integration Tests

The peegeeq-bitemporal module implements complex event sourcing logic. The current smoke tests treat it as a simple "Log Store" rather than a bi-temporal system.

#### A. Bi-Temporal Querying ("Time Travel")
*   **Source Context:** `PgBiTemporalEventStore` supports `valid_time` (business time) and `transaction_time` (system time).
*   **Current Coverage:** `BiTemporalEventStoreSmokeTest` likely performs a `findAll` or `findById`.
*   **The Gap:** The core value proposition of this module—querying the state of an entity "As Of" a specific time—is not verified in the integration suite.
*   **Missing Scenario:**
    *   **As-Of Query Test:**
        1. Append Event A (Version 1) at T1.
        2. Append Event A (Version 2 - Correction) at T2.
        3. Query the REST API for Event A with `?validTime=T1`.
        4. Verify the API returns Version 1, not Version 2.

#### B. Reactive Notifications (SSE)
*   **Source Context:** `ReactiveNotificationHandler` in peegeeq-bitemporal and `EventStoreIntegrationTest` in peegeeq-rest.
*   **The Gap:** While peegeeq-rest has an integration test for SSE, the *Smoke Test* suite (which is the primary deployment gate) often skips this due to the difficulty of testing streaming HTTP.
*   **Missing Scenario:**
    *   **Real-time Propagation:** A smoke test that opens an SSE connection to `/event-stores/{id}/events/stream`, posts an event via a separate client, and asserts the event appears in the stream within X milliseconds.

---

### 3. Gap Analysis: peegeeq-rest vs. Integration Tests

#### A. Input Validation & Error Handling
*   **Source Context:** PeeGeeQDatabaseSetupService.java (lines 145-165) has explicit validation for schema names (regex `^[a-zA-Z_][a-zA-Z0-9_]*$`) and reserved names (`pg_`, `information_schema`).
*   **The Gap:** Integration tests typically use valid inputs (e.g., `test_schema`).
*   **Missing Scenario:**
    *   **Security/Validation Smoke Test:** Attempt to create a setup with schema name `pg_hack` or `drop table`. Verify the API returns a structured validation error (400 Bad Request) as defined in the Service logic, ensuring the validation layer in peegeeq-db is correctly wired to the REST exception handler.

---

### 4. Recommended New Test Classes

To close these gaps, I recommend adding the following classes to peegeeq-integration-tests:

#### 1. `ResilienceSmokeTest.java`
*   **Goal:** Verify system behavior under partial failure.
*   **Tests:**
    *   `testDatabaseConnectionLossReturns503`: Pause DB container, call health check.
    *   `testCircuitBreakerOpen`: Spam invalid requests, verify circuit breaker opens (fast failure).

#### 2. `BiTemporalQuerySmokeTest.java`
*   **Goal:** Verify advanced temporal features via REST.
*   **Tests:**
    *   `testPointInTimeQuery`: Verify `valid_time` filtering works via API parameters.
    *   `testTransactionTimeQuery`: Verify audit history retrieval.

#### 3. `SetupFailureRecoverySmokeTest.java`
*   **Goal:** Verify the `PeeGeeQDatabaseSetupService` rollback logic via API.
*   **Tests:**
    *   `testInvalidSchemaNameRejected`: Verify regex validation.
    *   `testPartialSetupCleanup`: Force a failure during Queue creation (e.g., by mocking a DB error if possible, or using a configuration that exceeds limits) and verify the schema is dropped.

### Summary of Coverage Matrix

| Feature Area | peegeeq-db / `bitemporal` Unit Tests | Current `integration-tests` | Status |
| :--- | :--- | :--- | :--- |
| **Basic CRUD** | ✅ High Coverage | ✅ High Coverage | **Strong** |
| **Schema Validation** | ✅ High Coverage | ❌ Minimal/None | **Gap** |
| **Cleanup/Rollback** | ✅ High Coverage | ❌ None | **Critical Gap** |
| **Bi-Temporal Queries**| ✅ High Coverage | ⚠️ Basic Only | **Gap** |
| **Resilience (503s)** | ✅ High Coverage | ❌ None | **Critical Gap** |

User: look at the other modules in the system peegeeq-native and peegeeq-outbox

GitHub Copilot: Based on the architectural definitions in PEEGEEQ_INTEGRATION_TEST_STRATEGY.md and the standard complexity of PostgreSQL-backed messaging patterns, here is the gap analysis for the peegeeq-native and peegeeq-outbox modules.

### Executive Summary

*   **peegeeq-native**: The current tests verify the *happy path* of sending and receiving. They fail to verify the **concurrency guarantees** (PostgreSQL `SKIP LOCKED`) and **connection resilience** (`LISTEN/NOTIFY` stability) which are the primary reasons to use this module over a simple table poll.
*   **peegeeq-outbox**: This appears to be a **major blind spot**. There is no dedicated `OutboxSmokeTest` listed in the strategy. The core value proposition of the Outbox pattern—**transactional atomicity** (message only sends if transaction commits)—is currently unverified in the E2E suite.

---

### 1. Gap Analysis: peegeeq-native vs. Integration Tests

The peegeeq-native module typically implements high-performance queues using PostgreSQL's `SKIP LOCKED` for concurrent fetching and `LISTEN/NOTIFY` for low-latency signaling.

#### A. Concurrency & `SKIP LOCKED` Verification
*   **The Mechanism:** Multiple consumers in the same group should be able to fetch messages from the same table without blocking each other or receiving duplicates.
*   **Current Coverage:** `NativeQueueSmokeTest` likely sends a message and receives it with a single consumer.
*   **The Gap:** There is no "Thundering Herd" test.
*   **Missing Scenario:**
    *   **Consumer Group Contention:** Spin up 5 concurrent consumers in the same group. Publish 100 messages. Verify that exactly 100 messages are processed in total (no duplicates) and that the distribution is roughly even. This verifies the `SKIP LOCKED` clause is actually working and preventing race conditions.

#### B. Notification Resilience (`LISTEN/NOTIFY`)
*   **The Mechanism:** The application holds a JDBC connection open to receive asynchronous notifications from the DB.
*   **The Gap:** `LISTEN/NOTIFY` connections are fragile. If the connection drops (e.g., DB restart, network blip), the application must reconnect and re-issue the `LISTEN` command.
*   **Missing Scenario:**
    *   **Notification Recovery:**
        1. Start a consumer.
        2. Kill the TCP connection to Postgres (using `ToxicProxy` or `TestContainers` pause).
        3. Publish a message.
        4. Restore the connection.
        5. Verify the consumer eventually receives the message (either via reconnection + notification or fallback polling).

#### C. Delivery Guarantees (At-Least-Once)
*   **The Gap:** What happens if a consumer crashes *after* receiving a message but *before* acknowledging it?
*   **Missing Scenario:**
    *   **Crash-Processing:**
        1. Consumer A picks up Message X.
        2. Consumer A "crashes" (closes connection without ACK).
        3. Wait for visibility timeout.
        4. Verify Consumer B can now pick up Message X.

---

### 2. Gap Analysis: peegeeq-outbox vs. Integration Tests

The peegeeq-outbox module implements the Transactional Outbox pattern. Its entire purpose is to couple message publishing with database transaction commits.

#### A. Missing Smoke Test Suite
*   **Observation:** The PEEGEEQ_INTEGRATION_TEST_STRATEGY.md lists `NativeQueueSmokeTest` and `BiTemporalEventStoreSmokeTest`, but **no `OutboxSmokeTest`**.
*   **The Gap:** Users relying on the Outbox pattern have no E2E verification that the outbox processor is actually running and forwarding messages in the deployed environment.

#### B. Transactional Atomicity (The "Why")
*   **The Mechanism:** If a business transaction inserts a record but then rolls back, the associated outbox message must also disappear.
*   **The Gap:** Current tests likely only test "Commit Success".
*   **Missing Scenario:**
    *   **Rollback Verification:**
        1. Call a test endpoint that opens a transaction.
        2. "Send" an outbox message.
        3. Throw an exception to force a rollback.
        4. Verify the message **never** appears in the destination queue.

#### C. Ordering & FIFO Compliance
*   **The Mechanism:** The Outbox processor must read messages in the order they were committed (usually via sequence ID).
*   **The Gap:** Multi-threaded outbox processors can easily break ordering.
*   **Missing Scenario:**
    *   **Strict Ordering:** Publish messages 1 through 50 rapidly. Verify the consumer receives them strictly as 1..50.

---

### 3. Recommended New Test Classes

To close these specific gaps, I recommend adding the following to peegeeq-integration-tests:

#### 1. `OutboxSmokeTest.java` (New Class)
*   **Goal:** Parity with Native and BiTemporal smoke tests.
*   **Tests:**
    *   `testOutboxPublishAndDelivery`: Basic E2E flow.
    *   `testOutboxOrdering`: Verify FIFO.

#### 2. `NativeConcurrencySmokeTest.java`
*   **Goal:** Stress test the `SKIP LOCKED` mechanism.
*   **Tests:**
    *   `testConsumerGroupLoadBalancing`: 5 consumers, 100 messages. Assert `sum(consumed) == 100` and `distinct(messages) == 100`.

#### 3. `TransactionalIntegrityTest.java`
*   **Goal:** Verify the "Atomic" part of ACID for the Outbox.
*   **Tests:**
    *   `testRollbackPreventsPublishing`: Verify that failed transactions do not leak ghost messages.

### Updated Coverage Matrix

| Feature | Module | Current Status | Risk Level |
| :--- | :--- | :--- | :--- |
| **Native Basic Send/Recv** | peegeeq-native | ✅ Covered | Low |
| **Native Concurrency** | peegeeq-native | ❌ Missing | **High** (Race conditions) |
| **Native Reconnection** | peegeeq-native | ❌ Missing | Medium |
| **Outbox Basic Flow** | peegeeq-outbox | ❌ Missing | **Critical** (No E2E verification) |
| **Outbox Atomicity** | peegeeq-outbox | ❌ Missing | **High** (Data consistency) |


User: peegeeq is a complex distributed messaging ststem. the module peegeeq-integration-tests is a key integration and smoke testing components review the code against the other code modules such as peegeeq-db and peegeeq-bitemporal and give me a detailed analysis of test coverage gaps in this critical verification module

Workspace: Collecting workspace informationBased on the analysis of the peegeeq-integration-tests module structure, the provided strategy documents, and the source code of core modules like peegeeq-db and peegeeq-bitemporal, here is a detailed analysis of the test coverage gaps.

### Executive Summary

The peegeeq-integration-tests module currently excels at **"Happy Path" verification**—ensuring that the REST API correctly wires together the Runtime, Native, and Database layers to perform basic operations (Create Setup, Send Message, Store Event).

However, significant gaps exist regarding **Resilience**, **Background Operations**, and **Advanced Temporal Features**. While peegeeq-db and peegeeq-bitemporal have unit/integration tests for these features internally, they are not adequately verified from an End-to-End (E2E) perspective in the smoke test suite.

---

### 1. Gap Analysis: peegeeq-db vs. Integration Tests

The peegeeq-db module contains critical infrastructure logic. The current smoke tests verify that the database *connects*, but they miss the operational complexities handled by this module.

#### A. Resilience & Circuit Breaking (Critical Gap)
*   **Source Context:** peegeeq-db contains `CircuitBreakerManager` and `BackpressureManager`.
*   **Current Coverage:** peegeeq-db has isolated tests (`CircuitBreakerManagerTest`), but peegeeq-integration-tests assumes a healthy database.
*   **The Gap:** There are no smoke tests verifying API behavior when the database is under stress or momentarily unavailable.
*   **Missing Scenario:**
    *   **Chaos Smoke Test:** Simulate a database connection failure (e.g., using a TestContainer proxy or pausing the container) and verify the REST API returns a `503 Service Unavailable` rather than hanging or returning a `500 Internal Server Error`.

#### B. Resource Cleanup & Lifecycle
*   **Source Context:** peegeeq-db contains `CleanupService` and logic in PeeGeeQDatabaseSetupService.java (lines 380-420) regarding `destroySetup` and resource cleanup on failure.
*   **Current Coverage:** Smoke tests create setups and delete them on success.
*   **The Gap:** The "Cleanup on Failure" logic in `PeeGeeQDatabaseSetupService` is complex. If a setup fails halfway (e.g., template creation succeeds but queue creation fails), the system attempts to rollback. This is not verified via the API.
*   **Missing Scenario:**
    *   **Setup Failure Recovery:** Send a `POST /setups` request with a valid database config but an invalid Queue config (triggering a failure). Verify that the API returns a 400/500 and, crucially, that **no zombie database schemas** remain in the PostgreSQL container.

#### C. Connection Pool Starvation
*   **Source Context:** peegeeq-db manages `PgPool` and connection limits.
*   **The Gap:** Smoke tests run sequentially. They do not verify that the `PeeGeeQRuntime` correctly configures the pool size exposed in peegeeq-db to handle concurrent REST requests without starving.

---

### 2. Gap Analysis: peegeeq-bitemporal vs. Integration Tests

The peegeeq-bitemporal module implements complex event sourcing logic. The current smoke tests treat it as a simple "Log Store" rather than a bi-temporal system.

#### A. Bi-Temporal Querying ("Time Travel")
*   **Source Context:** `PgBiTemporalEventStore` supports `valid_time` (business time) and `transaction_time` (system time).
*   **Current Coverage:** `BiTemporalEventStoreSmokeTest` likely performs a `findAll` or `findById`.
*   **The Gap:** The core value proposition of this module—querying the state of an entity "As Of" a specific time—is not verified in the integration suite.
*   **Missing Scenario:**
    *   **As-Of Query Test:**
        1. Append Event A (Version 1) at T1.
        2. Append Event A (Version 2 - Correction) at T2.
        3. Query the REST API for Event A with `?validTime=T1`.
        4. Verify the API returns Version 1, not Version 2.

#### B. Reactive Notifications (SSE)
*   **Source Context:** `ReactiveNotificationHandler` in peegeeq-bitemporal and `EventStoreIntegrationTest` in peegeeq-rest.
*   **The Gap:** While peegeeq-rest has an integration test for SSE, the *Smoke Test* suite (which is the primary deployment gate) often skips this due to the difficulty of testing streaming HTTP.
*   **Missing Scenario:**
    *   **Real-time Propagation:** A smoke test that opens an SSE connection to `/event-stores/{id}/events/stream`, posts an event via a separate client, and asserts the event appears in the stream within X milliseconds.

---

### 3. Gap Analysis: peegeeq-rest vs. Integration Tests

#### A. Input Validation & Error Handling
*   **Source Context:** PeeGeeQDatabaseSetupService.java (lines 145-165) has explicit validation for schema names (regex `^[a-zA-Z_][a-zA-Z0-9_]*$`) and reserved names (`pg_`, `information_schema`).
*   **The Gap:** Integration tests typically use valid inputs (e.g., `test_schema`).
*   **Missing Scenario:**
    *   **Security/Validation Smoke Test:** Attempt to create a setup with schema name `pg_hack` or `drop table`. Verify the API returns a structured validation error (400 Bad Request) as defined in the Service logic, ensuring the validation layer in peegeeq-db is correctly wired to the REST exception handler.

---

### 4. Recommended New Test Classes

To close these gaps, I recommend adding the following classes to peegeeq-integration-tests:

#### 1. `ResilienceSmokeTest.java`
*   **Goal:** Verify system behavior under partial failure.
*   **Tests:**
    *   `testDatabaseConnectionLossReturns503`: Pause DB container, call health check.
    *   `testCircuitBreakerOpen`: Spam invalid requests, verify circuit breaker opens (fast failure).

#### 2. `BiTemporalQuerySmokeTest.java`
*   **Goal:** Verify advanced temporal features via REST.
*   **Tests:**
    *   `testPointInTimeQuery`: Verify `valid_time` filtering works via API parameters.
    *   `testTransactionTimeQuery`: Verify audit history retrieval.

#### 3. `SetupFailureRecoverySmokeTest.java`
*   **Goal:** Verify the `PeeGeeQDatabaseSetupService` rollback logic via API.
*   **Tests:**
    *   `testInvalidSchemaNameRejected`: Verify regex validation.
    *   `testPartialSetupCleanup`: Force a failure during Queue creation (e.g., by mocking a DB error if possible, or using a configuration that exceeds limits) and verify the schema is dropped.

### Summary of Coverage Matrix

| Feature Area | peegeeq-db / `bitemporal` Unit Tests | Current `integration-tests` | Status |
| :--- | :--- | :--- | :--- |
| **Basic CRUD** | ✅ High Coverage | ✅ High Coverage | **Strong** |
| **Schema Validation** | ✅ High Coverage | ❌ Minimal/None | **Gap** |
| **Cleanup/Rollback** | ✅ High Coverage | ❌ None | **Critical Gap** |
| **Bi-Temporal Queries**| ✅ High Coverage | ⚠️ Basic Only | **Gap** |
| **Resilience (503s)** | ✅ High Coverage | ❌ None | **Critical Gap** |

User: look at the other modules in the system peegeeq-native and peegeeq-outbox

GitHub Copilot: Based on the architectural definitions in PEEGEEQ_INTEGRATION_TEST_STRATEGY.md and the standard complexity of PostgreSQL-backed messaging patterns, here is the gap analysis for the peegeeq-native and peegeeq-outbox modules.

### Executive Summary

*   **peegeeq-native**: The current tests verify the *happy path* of sending and receiving. They fail to verify the **concurrency guarantees** (PostgreSQL `SKIP LOCKED`) and **connection resilience** (`LISTEN/NOTIFY` stability) which are the primary reasons to use this module over a simple table poll.
*   **peegeeq-outbox**: This appears to be a **major blind spot**. There is no dedicated `OutboxSmokeTest` listed in the strategy. The core value proposition of the Outbox pattern—**transactional atomicity** (message only sends if transaction commits)—is currently unverified in the E2E suite.

---

### 1. Gap Analysis: peegeeq-native vs. Integration Tests

The peegeeq-native module typically implements high-performance queues using PostgreSQL's `SKIP LOCKED` for concurrent fetching and `LISTEN/NOTIFY` for low-latency signaling.

#### A. Concurrency & `SKIP LOCKED` Verification
*   **The Mechanism:** Multiple consumers in the same group should be able to fetch messages from the same table without blocking each other or receiving duplicates.
*   **Current Coverage:** `NativeQueueSmokeTest` likely sends a message and receives it with a single consumer.
*   **The Gap:** There is no "Thundering Herd" test.
*   **Missing Scenario:**
    *   **Consumer Group Contention:** Spin up 5 concurrent consumers in the same group. Publish 100 messages. Verify that exactly 100 messages are processed in total (no duplicates) and that the distribution is roughly even. This verifies the `SKIP LOCKED` clause is actually working and preventing race conditions.

#### B. Notification Resilience (`LISTEN/NOTIFY`)
*   **The Mechanism:** The application holds a JDBC connection open to receive asynchronous notifications from the DB.
*   **The Gap:** `LISTEN/NOTIFY` connections are fragile. If the connection drops (e.g., DB restart, network blip), the application must reconnect and re-issue the `LISTEN` command.
*   **Missing Scenario:**
    *   **Notification Recovery:**
        1. Start a consumer.
        2. Kill the TCP connection to Postgres (using `ToxicProxy` or `TestContainers` pause).
        3. Publish a message.
        4. Restore the connection.
        5. Verify the consumer eventually receives the message (either via reconnection + notification or fallback polling).

#### C. Delivery Guarantees (At-Least-Once)
*   **The Gap:** What happens if a consumer crashes *after* receiving a message but *before* acknowledging it?
*   **Missing Scenario:**
    *   **Crash-Processing:**
        1. Consumer A picks up Message X.
        2. Consumer A "crashes" (closes connection without ACK).
        3. Wait for visibility timeout.
        4. Verify Consumer B can now pick up Message X.

---

### 2. Gap Analysis: peegeeq-outbox vs. Integration Tests

The peegeeq-outbox module implements the Transactional Outbox pattern. Its entire purpose is to couple message publishing with database transaction commits.

#### A. Missing Smoke Test Suite
*   **Observation:** The PEEGEEQ_INTEGRATION_TEST_STRATEGY.md lists `NativeQueueSmokeTest` and `BiTemporalEventStoreSmokeTest`, but **no `OutboxSmokeTest`**.
*   **The Gap:** Users relying on the Outbox pattern have no E2E verification that the outbox processor is actually running and forwarding messages in the deployed environment.

#### B. Transactional Atomicity (The "Why")
*   **The Mechanism:** If a business transaction inserts a record but then rolls back, the associated outbox message must also disappear.
*   **The Gap:** Current tests likely only test "Commit Success".
*   **Missing Scenario:**
    *   **Rollback Verification:**
        1. Call a test endpoint that opens a transaction.
        2. "Send" an outbox message.
        3. Throw an exception to force a rollback.
        4. Verify the message **never** appears in the destination queue.

#### C. Ordering & FIFO Compliance
*   **The Mechanism:** The Outbox processor must read messages in the order they were committed (usually via sequence ID).
*   **The Gap:** Multi-threaded outbox processors can easily break ordering.
*   **Missing Scenario:**
    *   **Strict Ordering:** Publish messages 1 through 50 rapidly. Verify the consumer receives them strictly as 1..50.

---

### 3. Recommended New Test Classes

To close these specific gaps, I recommend adding the following to peegeeq-integration-tests:

#### 1. `OutboxSmokeTest.java` (New Class)
*   **Goal:** Parity with Native and BiTemporal smoke tests.
*   **Tests:**
    *   `testOutboxPublishAndDelivery`: Basic E2E flow.
    *   `testOutboxOrdering`: Verify FIFO.

#### 2. `NativeConcurrencySmokeTest.java`
*   **Goal:** Stress test the `SKIP LOCKED` mechanism.
*   **Tests:**
    *   `testConsumerGroupLoadBalancing`: 5 consumers, 100 messages. Assert `sum(consumed) == 100` and `distinct(messages) == 100`.

#### 3. `TransactionalIntegrityTest.java`
*   **Goal:** Verify the "Atomic" part of ACID for the Outbox.
*   **Tests:**
    *   `testRollbackPreventsPublishing`: Verify that failed transactions do not leak ghost messages.

### Updated Coverage Matrix

| Feature | Module | Current Status | Risk Level |
| :--- | :--- | :--- | :--- |
| **Native Basic Send/Recv** | peegeeq-native | ✅ Covered | Low |
| **Native Concurrency** | peegeeq-native | ❌ Missing | **High** (Race conditions) |
| **Native Reconnection** | peegeeq-native | ❌ Missing | Medium |
| **Outbox Basic Flow** | peegeeq-outbox | ❌ Missing | **Critical** (No E2E verification) |
| **Outbox Atomicity** | peegeeq-outbox | ❌ Missing | **High** (Data consistency) |


User: peegeeq is a complex distributed messaging ststem. the module peegeeq-integration-tests is a key integration and smoke testing components review the code against the other code modules such as peegeeq-db and peegeeq-bitemporal and give me a detailed analysis of test coverage gaps in this critical verification module

Workspace: Collecting workspace informationBased on the analysis of the peegeeq-integration-tests module structure, the provided strategy documents, and the source code of core modules like peegeeq-db and peegeeq-bitemporal, here is a detailed analysis of the test coverage gaps.

### Executive Summary

The peegeeq-integration-tests module currently excels at **"Happy Path" verification**—ensuring that the REST API correctly wires together the Runtime, Native, and Database layers to perform basic operations (Create Setup, Send Message, Store Event).

However, significant gaps exist regarding **Resilience**, **Background Operations**, and **Advanced Temporal Features**. While peegeeq-db and peegeeq-bitemporal have unit/integration tests for these features internally, they are not adequately verified from an End-to-End (E2E) perspective in the smoke test suite.

---

### 1. Gap Analysis: peegeeq-db vs. Integration Tests

The peegeeq-db module contains critical infrastructure logic. The current smoke tests verify that the database *connects*, but they miss the operational complexities handled by this module.

#### A. Resilience & Circuit Breaking (Critical Gap)
*   **Source Context:** peegeeq-db contains `CircuitBreakerManager` and `BackpressureManager`.
*   **Current Coverage:** peegeeq-db has isolated tests (`CircuitBreakerManagerTest`), but peegeeq-integration-tests assumes a healthy database.
*   **The Gap:** There are no smoke tests verifying API behavior when the database is under stress or momentarily unavailable.
*   **Missing Scenario:**
    *   **Chaos Smoke Test:** Simulate a database connection failure (e.g., using a TestContainer proxy or pausing the container) and verify the REST API returns a `503 Service Unavailable` rather than hanging or returning a `500 Internal Server Error`.

#### B. Resource Cleanup & Lifecycle
*   **Source Context:** peegeeq-db contains `CleanupService` and logic in PeeGeeQDatabaseSetupService.java (lines 380-420) regarding `destroySetup` and resource cleanup on failure.
*   **Current Coverage:** Smoke tests create setups and delete them on success.
*   **The Gap:** The "Cleanup on Failure" logic in `PeeGeeQDatabaseSetupService` is complex. If a setup fails halfway (e.g., template creation succeeds but queue creation fails), the system attempts to rollback. This is not verified via the API.
*   **Missing Scenario:**
    *   **Setup Failure Recovery:** Send a `POST /setups` request with a valid database config but an invalid Queue config (triggering a failure). Verify that the API returns a 400/500 and, crucially, that **no zombie database schemas** remain in the PostgreSQL container.

#### C. Connection Pool Starvation
*   **Source Context:** peegeeq-db manages `PgPool` and connection limits.
*   **The Gap:** Smoke tests run sequentially. They do not verify that the `PeeGeeQRuntime` correctly configures the pool size exposed in peegeeq-db to handle concurrent REST requests without starving.

---

### 2. Gap Analysis: peegeeq-bitemporal vs. Integration Tests

The peegeeq-bitemporal module implements complex event sourcing logic. The current smoke tests treat it as a simple "Log Store" rather than a bi-temporal system.

#### A. Bi-Temporal Querying ("Time Travel")
*   **Source Context:** `PgBiTemporalEventStore` supports `valid_time` (business time) and `transaction_time` (system time).
*   **Current Coverage:** `BiTemporalEventStoreSmokeTest` likely performs a `findAll` or `findById`.
*   **The Gap:** The core value proposition of this module—querying the state of an entity "As Of" a specific time—is not verified in the integration suite.
*   **Missing Scenario:**
    *   **As-Of Query Test:**
        1. Append Event A (Version 1) at T1.
        2. Append Event A (Version 2 - Correction) at T2.
        3. Query the REST API for Event A with `?validTime=T1`.
        4. Verify the API returns Version 1, not Version 2.

#### B. Reactive Notifications (SSE)
*   **Source Context:** `ReactiveNotificationHandler` in peegeeq-bitemporal and `EventStoreIntegrationTest` in peegeeq-rest.
*   **The Gap:** While peegeeq-rest has an integration test for SSE, the *Smoke Test* suite (which is the primary deployment gate) often skips this due to the difficulty of testing streaming HTTP.
*   **Missing Scenario:**
    *   **Real-time Propagation:** A smoke test that opens an SSE connection to `/event-stores/{id}/events/stream`, posts an event via a separate client, and asserts the event appears in the stream within X milliseconds.

---

### 3. Gap Analysis: peegeeq-rest vs. Integration Tests

#### A. Input Validation & Error Handling
*   **Source Context:** PeeGeeQDatabaseSetupService.java (lines 145-165) has explicit validation for schema names (regex `^[a-zA-Z_][a-zA-Z0-9_]*$`) and reserved names (`pg_`, `information_schema`).
*   **The Gap:** Integration tests typically use valid inputs (e.g., `test_schema`).
*   **Missing Scenario:**
    *   **Security/Validation Smoke Test:** Attempt to create a setup with schema name `pg_hack` or `drop table`. Verify the API returns a structured validation error (400 Bad Request) as defined in the Service logic, ensuring the validation layer in peegeeq-db is correctly wired to the REST exception handler.

---

### 4. Recommended New Test Classes

To close these gaps, I recommend adding the following classes to peegeeq-integration-tests:

#### 1. `ResilienceSmokeTest.java`
*   **Goal:** Verify system behavior under partial failure.
*   **Tests:**
    *   `testDatabaseConnectionLossReturns503`: Pause DB container, call health check.
    *   `testCircuitBreakerOpen`: Spam invalid requests, verify circuit breaker opens (fast failure).

#### 2. `BiTemporalQuerySmokeTest.java`
*   **Goal:** Verify advanced temporal features via REST.
*   **Tests:**
    *   `testPointInTimeQuery`: Verify `valid_time` filtering works via API parameters.
    *   `testTransactionTimeQuery`: Verify audit history retrieval.

#### 3. `SetupFailureRecoverySmokeTest.java`
*   **Goal:** Verify the `PeeGeeQDatabaseSetupService` rollback logic via API.
*   **Tests:**
    *   `testInvalidSchemaNameRejected`: Verify regex validation.
    *   `testPartialSetupCleanup`: Force a failure during Queue creation (e.g., by mocking a DB error if possible, or using a configuration that exceeds limits) and verify the schema is dropped.

### Summary of Coverage Matrix

| Feature Area | peegeeq-db / `bitemporal` Unit Tests | Current `integration-tests` | Status |
| :--- | :--- | :--- | :--- |
| **Basic CRUD** | ✅ High Coverage | ✅ High Coverage | **Strong** |
| **Schema Validation** | ✅ High Coverage | ❌ Minimal/None | **Gap** |
| **Cleanup/Rollback** | ✅ High Coverage | ❌ None | **Critical Gap** |
| **Bi-Temporal Queries**| ✅ High Coverage | ⚠️ Basic Only | **Gap** |
| **Resilience (503s)** | ✅ High Coverage | ❌ None | **Critical Gap** |

User: look at the other modules in the system peegeeq-native and peegeeq-outbox

GitHub Copilot: Based on the architectural definitions in PEEGEEQ_INTEGRATION_TEST_STRATEGY.md and the standard complexity of PostgreSQL-backed messaging patterns, here is the gap analysis for the peegeeq-native and peegeeq-outbox modules.

### Executive Summary

*   **peegeeq-native**: The current tests verify the *happy path* of sending and receiving. They fail to verify the **concurrency guarantees** (PostgreSQL `SKIP LOCKED`) and **connection resilience** (`LISTEN/NOTIFY` stability) which are the primary reasons to use this module over a simple table poll.
*   **peegeeq-outbox**: This appears to be a **major blind spot**. There is no dedicated `OutboxSmokeTest` listed in the strategy. The core value proposition of the Outbox pattern—**transactional atomicity** (message only sends if transaction commits)—is currently unverified in the E2E suite.

---

### 1. Gap Analysis: peegeeq-native vs. Integration Tests

The peegeeq-native module typically implements high-performance queues using PostgreSQL's `SKIP LOCKED` for concurrent fetching and `LISTEN/NOTIFY` for low-latency signaling.

#### A. Concurrency & `SKIP LOCKED` Verification
*   **The Mechanism:** Multiple consumers in the same group should be able to fetch messages from the same table without blocking each other or receiving duplicates.
*   **Current Coverage:** `NativeQueueSmokeTest` likely sends a message and receives it with a single consumer.
*   **The Gap:** There is no "Thundering Herd" test.
*   **Missing Scenario:**
    *   **Consumer Group Contention:** Spin up 5 concurrent consumers in the same group. Publish 100 messages. Verify that exactly 100 messages are processed in total (no duplicates) and that the distribution is roughly even. This verifies the `SKIP LOCKED` clause is actually working and preventing race conditions.

#### B. Notification Resilience (`LISTEN/NOTIFY`)
*   **The Mechanism:** The application holds a JDBC connection open to receive asynchronous notifications from the DB.
*   **The Gap:** `LISTEN/NOTIFY` connections are fragile. If the connection drops (e.g., DB restart, network blip), the application must reconnect and re-issue the `LISTEN` command.
*   **Missing Scenario:**
    *   **Notification Recovery:**
        1. Start a consumer.
        2. Kill the TCP connection to Postgres (using `ToxicProxy` or `TestContainers` pause).
        3. Publish a message.
        4. Restore the connection.
        5. Verify the consumer eventually receives the message (either via reconnection + notification or fallback polling).

#### C. Delivery Guarantees (At-Least-Once)
*   **The Gap:** What happens if a consumer crashes *after* receiving a message but *before* acknowledging it?
*   **Missing Scenario:**
    *   **Crash-Processing:**
        1. Consumer A picks up Message X.
        2. Consumer A "crashes" (closes connection without ACK).
        3. Wait for visibility timeout.
        4. Verify Consumer B can now pick up Message X.

---

### 2. Gap Analysis: peegeeq-outbox vs. Integration Tests

The peegeeq-outbox module implements the Transactional Outbox pattern. Its entire purpose is to couple message publishing with database transaction commits.

#### A. Missing Smoke Test Suite
*   **Observation:** The PEEGEEQ_INTEGRATION_TEST_STRATEGY.md lists `NativeQueueSmokeTest` and `BiTemporalEventStoreSmokeTest`, but **no `OutboxSmokeTest`**.
*   **The Gap:** Users relying on the Outbox pattern have no E2E verification that the outbox processor is actually running and forwarding messages in the deployed environment.

#### B. Transactional Atomicity (The "Why")
*   **The Mechanism:** If a business transaction inserts a record but then rolls back, the associated outbox message must also disappear.
*   **The Gap:** Current tests likely only test "Commit Success".
*   **Missing Scenario:**
    *   **Rollback Verification:**
        1. Call a test endpoint that opens a transaction.
        2. "Send" an outbox message.
        3. Throw an exception to force a rollback.
        4. Verify the message **never** appears in the destination queue.

#### C. Ordering & FIFO Compliance
*   **The Mechanism:** The Outbox processor must read messages in the order they were committed (usually via sequence ID).
*   **The Gap:** Multi-threaded outbox processors can easily break ordering.
*   **Missing Scenario:**
    *   **Strict Ordering:** Publish messages 1 through 50 rapidly. Verify the consumer receives them strictly as 1..50.

---

### 3. Recommended New Test Classes

To close these specific gaps, I recommend adding the following to peegeeq-integration-tests:

#### 1. `OutboxSmokeTest.java` (New Class)
*   **Goal:** Parity with Native and BiTemporal smoke tests.
*   **Tests:**
    *   `testOutboxPublishAndDelivery`: Basic E2E flow.
    *   `testOutboxOrdering`: Verify FIFO.

#### 2. `NativeConcurrencySmokeTest.java`
*   **Goal:** Stress test the `SKIP LOCKED` mechanism.
*   **Tests:**
    *   `testConsumerGroupLoadBalancing`: 5 consumers, 100 messages. Assert `sum(consumed) == 100` and `distinct(messages) == 100`.

#### 3. `TransactionalIntegrityTest.java`
*   **Goal:** Verify the "Atomic" part of ACID for the Outbox.
*   **Tests:**
    *   `testRollbackPreventsPublishing`: Verify that failed transactions do not leak ghost messages.

### Updated Coverage Matrix

| Feature | Module | Current Status | Risk Level |
| :--- | :--- | :--- | :--- |
| **Native Basic Send/Recv** | peegeeq-native | ✅ Covered | Low |
| **Native Concurrency** | peegeeq-native | ❌ Missing | **High** (Race conditions) |
| **Native Reconnection** | peegeeq-native | ❌ Missing | Medium |
| **Outbox Basic Flow** | peegeeq-outbox | ❌ Missing | **Critical** (No E2E verification) |
| **Outbox Atomicity** | peegeeq-outbox | ❌ Missing | **High** (Data consistency) |

User: review the document INTERATION_TEST_EVALUATION and give me a structured integration test implementation plan

GitHub Copilot: Based on the gap analysis in INTEGRATION_TEST_EVALUATION.md, here is a structured implementation plan to bring the peegeeq-integration-tests module up to full coverage.

This plan prioritizes **missing functional coverage** (Outbox) and **data integrity** (Transactions/Concurrency) over advanced features.

### Phase 1: Critical Gaps (Outbox & Transactional Integrity)
**Objective:** Establish baseline E2E coverage for the peegeeq-outbox module, which is currently completely unverified in the smoke suite.

| Priority | Test Class | Scenarios to Implement | Rationale |
| :--- | :--- | :--- | :--- |
| **P0** | `OutboxSmokeTest.java` | 1. `testOutboxPublishAndDelivery`<br>2. `testOutboxOrdering` (FIFO) | Users relying on Outbox currently have no verification that the background processor works in a deployed environment. |
| **P0** | `TransactionalIntegrityTest.java` | 1. `testRollbackPreventsPublishing` | Verifies the core promise of the Outbox pattern: if the business transaction rolls back, the message must not be sent. |

### Phase 2: Operational Stability & Lifecycle
**Objective:** Ensure the system handles setup failures gracefully without leaving "zombie" schemas, and validates inputs correctly.

| Priority | Test Class | Scenarios to Implement | Rationale |
| :--- | :--- | :--- | :--- |
| **P1** | `SetupFailureRecoverySmokeTest.java` | 1. `testPartialSetupCleanup` (Force failure, verify schema drop)<br>2. `testInvalidSchemaNameRejected` | Prevents resource leaks in production/CI environments when dynamic setups fail. |
| **P1** | `ResilienceSmokeTest.java` | 1. `testDatabaseConnectionLossReturns503` | Ensures the API fails safely (Circuit Breaker) rather than hanging or throwing 500s when DB is down. |

### Phase 3: Concurrency & Native Queue Robustness
**Objective:** Verify that PostgreSQL `SKIP LOCKED` works correctly under load and that consumer groups do not process duplicate messages.

| Priority | Test Class | Scenarios to Implement | Rationale |
| :--- | :--- | :--- | :--- |
| **P2** | `NativeConcurrencySmokeTest.java` | 1. `testConsumerGroupLoadBalancing` (5 consumers, 100 msgs)<br>2. `testNotificationRecovery` (Reconnect after network blip) | Verifies the "Thundering Herd" protection and connection resilience of the native module. |

### Phase 4: Advanced Bitemporal Features
**Objective:** Verify the "Time Travel" capabilities of the event store, which are currently only tested at the unit level.

| Priority | Test Class | Scenarios to Implement | Rationale |
| :--- | :--- | :--- | :--- |
| **P3** | `BiTemporalQuerySmokeTest.java` | 1. `testPointInTimeQuery` (`?validTime=T1`)<br>2. `testTransactionTimeQuery` (Audit history) | Exposes the unique value proposition of the bitemporal module to the REST API. |

### Implementation Guide

#### 1. Infrastructure Updates
To support **Phase 2 (Resilience)** and **Phase 3 (Concurrency)**, the `SmokeTestBase` class may need updates:
*   **Fault Injection:** Prefer **Application-Level Fault Injection** (e.g., via a "Poison Pill" header or test-only configuration) to simulate failures. Use `Testcontainers ToxicProxy` only if necessary for network-level simulation, as it adds complexity.
*   **Concurrent Client Wrapper:** A utility to spawn multiple `WebClient` instances easily for the `testConsumerGroupLoadBalancing` test.

#### 2. Execution Strategy
*   **Step 1:** Create the empty test classes defined in Phase 1.
*   **Step 2:** Implement the "Happy Path" for Outbox first.
*   **Step 3:** Implement the "Rollback" test. Avoid modifying production code; instead, use a **Test-Only Controller** or a `peegeeq-test-support` plugin to inject the failure.
*   **Step 4:** Proceed to Phase 2.

### Proposed File Structure
```text
peegeeq-integration-tests/
└── src/
    └── test/
        └── java/
            └── dev/
                └── mars/
                    └── peegeeq/
                        └── integration/
                            ├── outbox/
                            │   ├── OutboxSmokeTest.java
                            │   └── TransactionalIntegrityTest.java
                            ├── native/
                            │   └── NativeConcurrencySmokeTest.java
                            ├── resilience/
                            │   ├── ResilienceSmokeTest.java
                            │   └── SetupFailureRecoverySmokeTest.java
                            └── bitemporal/
                                └── BiTemporalQuerySmokeTest.java
