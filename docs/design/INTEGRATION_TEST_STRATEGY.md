# Design Recommendation: Integration Test Strategy

## 1. Problem Statement
While `peegeeq-rest` contains integration tests like `PeeGeeQRestServerTest.java` and `QueueFactorySystemIntegrationTest.java`, there is a specific gap in verifying the complete "Call Propagation" flow described in `PEEGEEQ_CALL_PROPAGATION.md`.

*   **`QueueFactorySystemIntegrationTest.java`**: Tests the Java components (Manager -> Factory -> Producer) but bypasses the HTTP layer (`QueueHandler`).
*   **`PeeGeeQRestServerTest.java`**: Sets up the correct infrastructure (Testcontainers + Vert.x WebClient) and tests `database-setup/create`, but its `testSendMessage` method only asserts error responses (400/404) for non-existent setups. It does not verify that a *valid* message sent via REST is correctly persisted to the DB or received by a consumer.
*   **`EndToEndValidationTest.java`**: Focuses on management endpoints (`/health`, `/metrics`, `/overview`) and does not test the core messaging data plane.

Therefore, the "Happy Path" of sending a message via HTTP and having it propagate to the database and consumer is currently untested in an automated fashion.

## 2. Proposed Solution: New Integration Test Class

Instead of creating a new module, we recommend adding a new test class **`CallPropagationIntegrationTest.java`** within the existing `peegeeq-rest` module. This leverages the existing test infrastructure (Testcontainers, Vert.x JUnit 5) while filling the coverage gap.

### 2.1 Test Structure
**Class:** `dev.mars.peegeeq.rest.CallPropagationIntegrationTest`

**Dependencies:**
*   Inherits the `Testcontainers` and `VertxExtension` setup from `PeeGeeQRestServerTest`.
*   Uses `WebClient` for HTTP requests.
*   Uses `PgNativeQueueConsumer` (from `peegeeq-native`) for verification.

### 2.2 Test Scenarios

The new test class will implement the following scenarios:

### Scenario A: REST-to-Database Propagation
**Goal:** Verify that an HTTP POST request results in a correctly formatted row in the `queue_messages` table.
1.  **Setup:**
    *   Start Postgres container.
    *   Deploy `PeeGeeQRestServer`.
    *   Call `POST /api/v1/database-setup/create` to initialize the DB and create a queue named `orders`.
2.  **Action:**
    *   Send `POST /api/v1/queues/orders/messages` with payload `{"id": 123}`.
3.  **Verification:**
    *   Assert HTTP 200 OK.
    *   Query DB: `SELECT payload FROM queue_messages WHERE topic='orders'`.
    *   Assert payload is `{"id": 123}`.

### Scenario B: REST-to-Consumer Propagation
**Goal:** Verify that a message sent via REST triggers the `NOTIFY` mechanism and is received by a live consumer.
1.  **Setup:**
    *   (Same as above).
    *   Instantiate a `PgNativeQueueConsumer` (Java) subscribed to `orders`.
2.  **Action:**
    *   Send `POST /api/v1/queues/orders/messages`.
3.  **Verification:**
    *   Use `CompletableFuture` or `Awaitility` to wait for the consumer to receive the message.
    *   Assert the received message matches the sent payload.

## 3. Implementation Plan

1.  **Create Class**: `src/test/java/dev/mars/peegeeq/rest/CallPropagationIntegrationTest.java`.
2.  **Setup Logic**: Copy the `@Container` and `deployVerticle` logic from `PeeGeeQRestServerTest`.
3.  **Helper Methods**: Extract the "Create Setup" logic into a reusable helper method (or use the one from `PeeGeeQRestServerTest` if made protected).
4.  **Test Methods**: Implement `testRestToDatabase` and `testRestToConsumer`.

## 4. Benefits
*   **Low Friction**: No new module or build configuration required.
*   **Direct Verification**: Directly proves the claims made in `PEEGEEQ_CALL_PROPAGATION.md`.
*   **Regression Safety**: Ensures that future changes to `QueueHandler` or `PgNativeQueueProducer` do not break the core data flow.
