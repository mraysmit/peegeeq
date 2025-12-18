# Addressing Management API Gaps

The goal is to resolve urgent functional gaps in the PeeGeeQ Management API, specifically missing implementation for message browsing, aggregate counts, and consumer configuration.

## Implementation Status: COMPLETE

> [!NOTE]
> All planned changes have been implemented and verified. Initial implementation completed on 2025-12-16, with deferred items completed on 2025-12-18.

## Changes Implemented

### peegeeq-api
#### [COMPLETE] EventStore.java
- Added `long getUniqueAggregateCount()` to `EventStoreStats` interface.

#### [COMPLETE] QueueBrowser.java (NEW FILE)
- Created new interface for browsing messages:
    ```java
    public interface QueueBrowser<T> extends AutoCloseable {
        CompletableFuture<List<Message<T>>> browse(int limit, int offset);
        default CompletableFuture<List<Message<T>>> browse(int limit);
        String getTopic();
        void close();
    }
    ```

#### [COMPLETE] QueueFactory.java
- Added method `<T> QueueBrowser<T> createBrowser(String topic, Class<T> payloadType);`

### peegeeq-bitemporal
#### [COMPLETE] PgBiTemporalEventStore.java
- Implemented `getUniqueAggregateCount()` in `EventStoreStatsImpl` class.
- Updated SQL query in `getStatsReactive()` to include `COUNT(DISTINCT aggregate_id) as unique_aggregate_count`.

### peegeeq-native
#### [COMPLETE] PgNativeQueueFactory.java
- Implemented `createBrowser()` method.

#### [COMPLETE] PgNativeQueueBrowser.java (NEW FILE)
- Created implementation that queries `peegeeq.queue_messages` table with `ORDER BY id DESC LIMIT ? OFFSET ?`.

### peegeeq-outbox
#### [COMPLETE] OutboxFactory.java
- Implemented `createBrowser()` method.
- Implemented `createConsumer(String topic, Class<T> payloadType, Object consumerConfig)` to properly use consumer configuration.

#### [COMPLETE] OutboxQueueBrowser.java (NEW FILE)
- Created implementation that queries `peegeeq.outbox` table with `ORDER BY id DESC LIMIT ? OFFSET ?`.

#### [COMPLETE] OutboxConsumerConfig.java (NEW FILE)
- Created configuration class with builder pattern for per-consumer configuration:
    ```java
    public class OutboxConsumerConfig {
        private final Duration pollInterval;
        private final int batchSize;
        private final int maxRetries;
        // Builder pattern for configuration
    }
    ```

### peegeeq-rest
#### [COMPLETE] ManagementApiHandler.java
- Updated `getRealAggregateCount()` to call `getStats().getUniqueAggregateCount()`.
- Updated `getRealMessages()` to create a `QueueBrowser` and call `browse()`.
- Updated `getRecentActivity()` to query real events from event stores using `EventStore.query()`.

#### [COMPLETE] ManagementApiIntegrationTest.java
- Added comprehensive tests for QueueBrowser and aggregate count functionality.
- Added tests verifying the new REST endpoints work end-to-end.

## Verification Results

### Build Status
- `mvn clean compile` - PASSED
- `mvn clean install -DskipTests` - PASSED

### Tests Executed
- `PgBiTemporalEventStoreTest` - PASSED
- `NativeQueueIntegrationTest` - PASSED
- `OutboxFactoryIntegrationTest` - PASSED
- `ManagementApiHandlerTest` - PASSED
- `ManagementApiIntegrationTest` - PASSED
- All `*EventStore*` tests in bitemporal module - PASSED
- All 210 tests in peegeeq-rest module - PASSED

## Additional Fixes (2025-12-18)

### Consumer Lifecycle Management Fix
Fixed a potential production bug where consumer polling threads continued running after server shutdown:

- **PeeGeeQRestServer.java**: Updated `stop()` method to close `WebhookSubscriptionHandler` and `ServerSentEventsHandler` before closing the HTTP server.
- **ServerSentEventsHandler.java**: Added `close()` method to properly clean up all active SSE connections and their consumers.

This ensures graceful shutdown without thread leaks or "Connection refused" errors.

### Test Assertion Fixes
Fixed 16 pre-existing test failures where tests incorrectly expected HTTP 200 for POST operations instead of HTTP 201 (Created):
- `EventStoreIntegrationTest.java` - 12 assertions fixed
- `EventStoreEnhancementTest.java` - 3 assertions fixed
- `CallPropagationIntegrationTest.java` - 2 assertions fixed
