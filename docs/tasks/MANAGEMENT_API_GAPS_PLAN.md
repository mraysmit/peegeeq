# Addressing Management API Gaps

The goal is to resolve urgent functional gaps in the PeeGeeQ Management API, specifically missing implementation for message browsing, aggregate counts, and consumer configuration.

## User Review Required

> [!IMPORTANT]
> This plan introduces a new `QueueBrowser` interface to `peegeeq-api` and extends `QueueFactory`. This is a minor API addition but requires implementation in all backend modules (`peegeeq-native`, `peegeeq-outbox`).

## Proposed Changes

### peegeeq-api
#### [MODIFY] [EventStore.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-api/src/main/java/dev/mars/peegeeq/api/EventStore.java)
- Add `long getUniqueAggregateCount()` to `EventStoreStats` interface.

#### [NEW] [QueueBrowser.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueBrowser.java)
- Define new interface for browsing messages:
    ```java
    public interface QueueBrowser<T> extends AutoCloseable {
        CompletableFuture<List<Message<T>>> browse(int limit, int offset);
        // Maybe peek/browse specific messages?
    }
    ```

#### [MODIFY] [QueueFactory.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueFactory.java)
- Add method `<T> QueueBrowser<T> createBrowser(String topic, Class<T> payloadType);`

### peegeeq-bitemporal
#### [MODIFY] [PgBiTemporalEventStore.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStore.java)
- Implement `getUniqueAggregateCount()` in internal `EventStoreStats` implementation (SQL: `SELECT COUNT(DISTINCT aggregate_id) ...`).

### peegeeq-native
#### [MODIFY] [PgNativeQueueFactory.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java)
- Implement `createBrowser`.
- Implement `PgNativeQueueBrowser` (runs `SELECT * FROM queue_table ORDER BY id DESC LIMIT ? OFFSET ?`).

### peegeeq-outbox
#### [MODIFY] [OutboxFactory.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java)
- Implement `createBrowser`.
- Implement `OutboxQueueBrowser`.
- **FIX**: Override `createConsumer(String topic, Class<T> payloadType, Object consumerConfig)` to actually use the config.

### peegeeq-rest
#### [MODIFY] [ManagementApiHandler.java](file:///c:/Users/markr/dev/java/corejava/peegeeq/peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java)
- Update `getRealAggregateCount` to calls `getStats().getUniqueAggregateCount()`.
- Update `getRealMessages` to create a `QueueBrowser` and call `browse()`.
- Update `getRecentActivity` to us `EventStore.query()` (Query for all types, sorted by transaction time desc, limit 20).

## Verification Plan

### Automated Tests
- **New Test**: `RestManagementIntegrationTest.java` (inheriting from `RestClientIntegrationTest` or similar infrastructure) to verify:
    - `getRealAggregateCount` returns > 0 after appending events.
    - `getRealMessages` returns messages after sending.
    - `getRecentActivity` returns events.
    
    *Command*: `mvn clean test -pl peegeeq-rest -Dtest=RestManagementIntegrationTest`

### Manual Verification
- None required if integration tests pass.
