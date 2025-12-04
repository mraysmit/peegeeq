ok does the code align? especially the peegeeq-api coverage of the underlying services native, outbox and bitemporal. the peegeeq-api should be the only public interface for consuming these services. Likewise the REST API layes only talks the the peegeeq-api. So the layers from the RET API downwards need to expose the required functionality. Check this in the code.


Let me investigate the architectural layering to verify that the code aligns with the expected design.

Good, I can see the REST layer structure. Now let me check the peegeeq-api module to understand its interfaces and how it covers native, outbox, and bitemporal:

Now let me check the key API interfaces and how the implementation modules (native, outbox, bitemporal) implement them:

Now let me check how the implementation modules (native, outbox, bitemporal) implement these interfaces:

Now let me check the key implementations to verify they implement the API interfaces:

Good, I can see the implementations. Now let me check the REST layer to see if it properly uses only the peegeeq-api interfaces:

Now let me check the ConsumerGroupHandler and see if there are any direct dependencies on implementation modules:

The search returned empty, which is good - no direct imports from implementation modules. But I noticed the ConsumerGroupHandler imports from `peegeeq-db`. Let me check what the REST layer imports from peegeeq-db:

Now let me check the peegeeq-api module to see if it has the necessary interfaces that should be used instead of peegeeq-db classes:

Now let me check if there's a SubscriptionManager interface in peegeeq-api or if it's only in peegeeq-db:

Now I have a clear picture. Let me create a comprehensive analysis:

## Architecture Analysis: Does the Code Align?

### Summary

The architecture is **mostly aligned** but has some **layer violations** that should be addressed.

### What's Correct

1. **peegeeq-api defines the public interfaces:**
   - `QueueFactory` - factory for creating producers/consumers
   - `MessageProducer<T>` - for sending messages
   - `MessageConsumer<T>` - for receiving messages
   - `ConsumerGroup<T>` - for consumer group management
   - `EventStore<T>` - for bi-temporal event storage
   - `DatabaseSetupService` - for database setup operations

2. **Implementation modules implement the API interfaces:**
   - `peegeeq-native`: `PgNativeQueueFactory implements QueueFactory`
   - `peegeeq-outbox`: `OutboxFactory implements QueueFactory`
   - `peegeeq-bitemporal`: `PgBiTemporalEventStore implements EventStore`

3. **REST layer correctly uses API interfaces for core operations:**
   - `QueueHandler` uses `QueueFactory`, `MessageProducer` from peegeeq-api
   - `EventStoreHandler` uses `EventStore`, `BiTemporalEvent` from peegeeq-api
   - Implementation modules are only `test` scope dependencies in peegeeq-rest

### Layer Violations Found

**peegeeq-rest directly imports from peegeeq-db:**

| File | Violation |
|------|-----------|
| `ConsumerGroupHandler.java` | Imports `SubscriptionManager`, `Subscription`, `SubscriptionStatus` from peegeeq-db |
| `SubscriptionManagerFactory.java` | Imports `PeeGeeQManager`, `SubscriptionManager` from peegeeq-db |
| `FactoryAwarePeeGeeQManager.java` | Imports `PeeGeeQManager`, `PeeGeeQConfiguration` from peegeeq-db |
| `RestDatabaseSetupService.java` | Imports `PeeGeeQDatabaseSetupService`, `PeeGeeQManager` from peegeeq-db |

### Missing API Interfaces

The following functionality is used by peegeeq-rest but lacks corresponding interfaces in peegeeq-api:

1. **SubscriptionManager** - No interface in peegeeq-api for subscription management
2. **Subscription** - No interface/DTO in peegeeq-api for subscription data
3. **SubscriptionStatus** - No enum in peegeeq-api for subscription states
4. **PeeGeeQManager** - No interface in peegeeq-api for the core manager

### Recommendations

To fully align with the intended architecture:

1. **Add subscription management interfaces to peegeeq-api:**
   - `SubscriptionService` interface
   - `SubscriptionInfo` DTO
   - `SubscriptionState` enum

2. **Add manager interface to peegeeq-api:**
   - `PeeGeeQManagerService` interface (or similar)

3. **Update peegeeq-rest to use only peegeeq-api interfaces**

Would you like me to create these missing interfaces in peegeeq-api to complete the architectural alignment?
