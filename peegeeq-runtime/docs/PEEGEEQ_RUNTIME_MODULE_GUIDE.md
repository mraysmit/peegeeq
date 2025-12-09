# PeeGeeQ Runtime Module Guide

## 1. Overview

The `peegeeq-runtime` module is the **composition layer** for PeeGeeQ. It wires together all implementation modules (`peegeeq-native`, `peegeeq-outbox`, `peegeeq-bitemporal`) and provides a single entry point for bootstrapping the complete PeeGeeQ system.

### Key Characteristics

| Characteristic | Description |
|----------------|-------------|
| **Single Entry Point** | `PeeGeeQRuntime` class provides factory methods for all services |
| **Feature Toggles** | `RuntimeConfig` enables/disables native queues, outbox, bi-temporal |
| **Composition** | Wires together all implementation modules without tight coupling |
| **Context Container** | `PeeGeeQContext` holds all bootstrapped services |
| **Facade Pattern** | `RuntimeDatabaseSetupService` delegates to underlying implementation |

### Module Purpose

```
+------------------+
|  Application     |
|  Code            |
+------------------+
        |
        | PeeGeeQRuntime.bootstrap()
        v
+------------------+
|  peegeeq-runtime |  <-- This Module (Composition Layer)
|  (This Module)   |
+------------------+
        |
        | Wires together
        v
+-------+--------+------------+------+
|       |        |            |
v       v        v            v
native  outbox   bitemporal   db
```

## 2. Architecture Position

### Dependency Rules

| Module | Allowed Dependencies | Forbidden Dependencies |
|--------|---------------------|------------------------|
| `peegeeq-runtime` | `peegeeq-api`, `peegeeq-db`, `peegeeq-native`, `peegeeq-outbox`, `peegeeq-bitemporal` | `peegeeq-rest`, `peegeeq-client` |

The runtime module is the **composition root** that:
- **Depends on all implementation modules** to wire them together
- **Exposes only API interfaces** to consumers
- **Is used by `peegeeq-rest`** to bootstrap the server

### Module Structure

```
peegeeq-runtime/
├── pom.xml
├── docs/
│   └── PEEGEEQ_RUNTIME_MODULE_GUIDE.md (this file)
└── src/
    ├── main/java/dev/mars/peegeeq/runtime/
    │   ├── PeeGeeQRuntime.java              # Main factory class (148 lines)
    │   ├── PeeGeeQContext.java              # Context container (111 lines)
    │   ├── RuntimeConfig.java               # Configuration (151 lines)
    │   └── RuntimeDatabaseSetupService.java # Facade service (153 lines)
    └── test/java/dev/mars/peegeeq/runtime/
        ├── PeeGeeQRuntimeTest.java          # Runtime tests
        └── RuntimeConfigTest.java           # Config tests
```

## 3. Core Components

### 3.1 PeeGeeQRuntime

The main factory class providing static methods for bootstrapping PeeGeeQ.

```java
public final class PeeGeeQRuntime {

    // Create DatabaseSetupService with all features enabled (native queues, outbox, bi-temporal)
    public static DatabaseSetupService createDatabaseSetupService() { ... }

    // Create DatabaseSetupService with custom configuration
    // RuntimeConfig controls which features are enabled:
    //   - enableNativeQueues: PostgreSQL LISTEN/NOTIFY real-time messaging
    //   - enableOutboxQueues: Transactional outbox pattern for guaranteed delivery
    //   - enableBiTemporalEventStore: Bi-temporal event sourcing with point-in-time queries
    public static DatabaseSetupService createDatabaseSetupService(RuntimeConfig config) { ... }

    // Bootstrap complete context with all services based on config
    // Returns PeeGeeQContext containing the DatabaseSetupService and RuntimeConfig
    public static PeeGeeQContext bootstrap(RuntimeConfig config) { ... }

    // Bootstrap with default configuration (all features enabled)
    public static PeeGeeQContext bootstrap() { ... }
}
```

**Usage Examples:**

```java
// Example 1: Simple - All features enabled
// Use this when you want the full PeeGeeQ functionality including
// native queues, outbox pattern, and bi-temporal event store.
// This is the recommended approach for most production applications.
DatabaseSetupService simpleService = PeeGeeQRuntime.createDatabaseSetupService();

// Example 2: Custom configuration - Only native queues enabled
// Use this when you only need PostgreSQL LISTEN/NOTIFY messaging
// without the transactional outbox or bi-temporal event store.
// This reduces overhead when you don't need guaranteed delivery or event sourcing.
RuntimeConfig config = RuntimeConfig.builder()
    .enableNativeQueues(true)
    .enableOutboxQueues(false)
    .enableBiTemporalEventStore(false)
    .build();
DatabaseSetupService customService = PeeGeeQRuntime.createDatabaseSetupService(config);

// Example 3: Full context bootstrap
// Use this when you need access to both the DatabaseSetupService
// and the RuntimeConfig for feature availability checks.
// The context provides a single object to pass around your application.
PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
DatabaseSetupService setupService = context.getDatabaseSetupService();
RuntimeConfig runtimeConfig = context.getConfig();
```

### 3.2 RuntimeConfig

Configuration holder for feature toggles.

```java
public final class RuntimeConfig {
    
    // Feature flags
    public boolean isNativeQueuesEnabled() { ... }
    public boolean isOutboxQueuesEnabled() { ... }
    public boolean isBiTemporalEventStoreEnabled() { ... }
    
    // Factory methods
    public static Builder builder() { ... }
    public static RuntimeConfig defaults() { ... }  // All enabled
}
```

**Configuration Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `enableNativeQueues` | `true` | Enable PostgreSQL LISTEN/NOTIFY queue |
| `enableOutboxQueues` | `true` | Enable transactional outbox pattern |
| `enableBiTemporalEventStore` | `true` | Enable bi-temporal event store |

### 3.3 PeeGeeQContext

Immutable container for all bootstrapped services.

```java
public final class PeeGeeQContext {
    
    // Access services
    public DatabaseSetupService getDatabaseSetupService() { ... }
    public RuntimeConfig getConfig() { ... }
    
    // Feature availability checks
    public boolean hasNativeQueues() { ... }
    public boolean hasOutboxQueues() { ... }
    public boolean hasBiTemporalEventStore() { ... }
}
```

### 3.4 RuntimeDatabaseSetupService

Facade that delegates to the underlying `PeeGeeQDatabaseSetupService` and manages factory registrations.

```java
public class RuntimeDatabaseSetupService implements DatabaseSetupService {
    
    // Factory registration
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) { ... }
    
    // Delegated operations
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) { ... }
    public CompletableFuture<Void> destroySetup(String setupId) { ... }
    public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) { ... }
    // ... all DatabaseSetupService methods
    
    // Service access
    public SubscriptionService getSubscriptionServiceForSetup(String setupId) { ... }
    public DeadLetterService getDeadLetterServiceForSetup(String setupId) { ... }
    public HealthService getHealthServiceForSetup(String setupId) { ... }
    public QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) { ... }
}
```

## 4. Bootstrap Flow

The bootstrap process wires together all implementation modules:

```
PeeGeeQRuntime.bootstrap(config)
              |
              v
1. Create PeeGeeQDatabaseSetupService delegate
   - If biTemporalEnabled: inject BiTemporalEventStoreFactory
              |
              v
2. Wrap with RuntimeDatabaseSetupService facade
              |
              v
3. Register queue factories based on config:
   - If nativeQueuesEnabled: PgNativeFactoryRegistrar.registerWith()
   - If outboxQueuesEnabled: OutboxFactoryRegistrar.registerWith()
              |
              v
4. Create PeeGeeQContext with services and config
              |
              v
5. Return context to caller
```

### Factory Registration Pattern

Queue factories are registered using the registrar pattern to avoid circular dependencies:

```java
// In PeeGeeQRuntime.createDatabaseSetupService()
if (config.isNativeQueuesEnabled()) {
    setupService.addFactoryRegistration(PgNativeFactoryRegistrar::registerWith);
}

if (config.isOutboxQueuesEnabled()) {
    setupService.addFactoryRegistration(OutboxFactoryRegistrar::registerWith);
}
```

Each registrar registers a factory creator with the `QueueFactoryRegistrar`:

```java
// PgNativeFactoryRegistrar
public static void registerWith(QueueFactoryRegistrar registrar) {
    registrar.registerFactory("native", new NativeFactoryCreator());
}

// OutboxFactoryRegistrar
public static void registerWith(QueueFactoryRegistrar registrar) {
    registrar.registerFactory("outbox", new OutboxFactoryCreator());
}
```

## 5. Integration with Other Modules

### 5.1 How peegeeq-rest Uses Runtime

The REST server uses the runtime to bootstrap services:

```java
// In REST server initialization
PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
DatabaseSetupService setupService = context.getDatabaseSetupService();

// Create handlers with the setup service
DatabaseSetupHandler handler = new DatabaseSetupHandler(setupService);
```

### 5.2 How Factories Are Created

When a setup is created, the registered factories become available:

```java
// 1. Create setup
DatabaseSetupResult result = setupService.createCompleteSetup(request).get();

// 2. Get factory provider for the setup
QueueFactoryProvider provider = setupService.getQueueFactoryProviderForSetup(setupId);

// 3. Create specific factory type
QueueFactory nativeFactory = provider.createFactory("native", databaseService);
QueueFactory outboxFactory = provider.createFactory("outbox", databaseService);

// 4. Create producers/consumers
MessageProducer<Order> producer = nativeFactory.createProducer("orders", Order.class);
```

## 6. Configuration Scenarios

### 6.1 Production: All Features

```java
// Default configuration enables everything
PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
```

### 6.2 Lightweight: Native Only

```java
RuntimeConfig config = RuntimeConfig.builder()
    .enableNativeQueues(true)
    .enableOutboxQueues(false)
    .enableBiTemporalEventStore(false)
    .build();

PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);
```

### 6.3 Transactional: Outbox Only

```java
RuntimeConfig config = RuntimeConfig.builder()
    .enableNativeQueues(false)
    .enableOutboxQueues(true)
    .enableBiTemporalEventStore(false)
    .build();

PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);
```

### 6.4 Event Sourcing: Bi-Temporal Focus

```java
RuntimeConfig config = RuntimeConfig.builder()
    .enableNativeQueues(false)
    .enableOutboxQueues(true)
    .enableBiTemporalEventStore(true)
    .build();

PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);
```

## 7. Dependencies

### Maven Dependencies

```xml
<dependencies>
    <!-- API contracts -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-api</artifactId>
    </dependency>

    <!-- Database layer -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-db</artifactId>
    </dependency>

    <!-- Implementation modules -->
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-native</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-outbox</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq-bitemporal</artifactId>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </dependency>
</dependencies>
```

### Dependency Graph

```
peegeeq-runtime
    |
    +-- peegeeq-api (interfaces)
    |
    +-- peegeeq-db (database access)
    |       |
    |       +-- peegeeq-api
    |
    +-- peegeeq-native (LISTEN/NOTIFY queue)
    |       |
    |       +-- peegeeq-api
    |       +-- peegeeq-db
    |
    +-- peegeeq-outbox (transactional outbox)
    |       |
    |       +-- peegeeq-api
    |       +-- peegeeq-db
    |
    +-- peegeeq-bitemporal (event store)
            |
            +-- peegeeq-api
            +-- peegeeq-db
```

## 8. Testing

### Test Classes

| Test Class | Purpose |
|------------|---------|
| `PeeGeeQRuntimeTest` | Tests factory methods and bootstrap |
| `RuntimeConfigTest` | Tests configuration builder and defaults |

### Running Tests

```bash
# Run all runtime tests
mvn test -pl peegeeq-runtime

# Run specific test
mvn test -pl peegeeq-runtime -Dtest=PeeGeeQRuntimeTest
```

### Test Coverage

| Scenario | Test |
|----------|------|
| Default config creates service | `createDatabaseSetupService_withDefaults_returnsRuntimeDatabaseSetupService` |
| Custom config creates service | `createDatabaseSetupService_withCustomConfig_returnsConfiguredService` |
| Null config throws exception | `createDatabaseSetupService_withNullConfig_throwsNullPointerException` |
| Bootstrap returns context | `bootstrap_withDefaults_returnsPeeGeeQContext` |
| Bootstrap respects config | `bootstrap_withCustomConfig_returnsPeeGeeQContextWithConfig` |

## 9. Design Patterns

### Factory Pattern

`PeeGeeQRuntime` is a static factory that creates configured services:

```java
// Factory method pattern
DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService(config);
```

### Builder Pattern

`RuntimeConfig` uses fluent builder for configuration:

```java
RuntimeConfig config = RuntimeConfig.builder()
    .enableNativeQueues(true)
    .enableOutboxQueues(false)
    .build();
```

### Facade Pattern

`RuntimeDatabaseSetupService` is a facade that:
- Delegates all operations to the underlying implementation
- Manages factory registrations
- Provides a unified interface

### Composition Root Pattern

The runtime module is the composition root where all dependencies are wired together:

```
Application
    |
    v
PeeGeeQRuntime (Composition Root)
    |
    +-- Creates PeeGeeQDatabaseSetupService
    +-- Wraps with RuntimeDatabaseSetupService
    +-- Registers PgNativeFactoryRegistrar
    +-- Registers OutboxFactoryRegistrar
    +-- Injects BiTemporalEventStoreFactory
    |
    v
PeeGeeQContext (Fully Wired System)
```

## 10. Best Practices

### 1. Bootstrap Once

Create the context once at application startup:

```java
// Application startup
private static PeeGeeQContext context;

public static void main(String[] args) {
    context = PeeGeeQRuntime.bootstrap();
    // Use context throughout application
}
```

### 2. Use Feature Checks

Check feature availability before using:

```java
if (context.hasNativeQueues()) {
    // Use native queue features
}

if (context.hasBiTemporalEventStore()) {
    // Use bi-temporal features
}
```

### 3. Pass Context, Not Individual Services

Pass the context to components that need multiple services:

```java
// Good: Pass context
public class MyHandler {
    public MyHandler(PeeGeeQContext context) {
        this.setupService = context.getDatabaseSetupService();
        this.config = context.getConfig();
    }
}

// Avoid: Pass individual services
public class MyHandler {
    public MyHandler(DatabaseSetupService service, RuntimeConfig config) { ... }
}
```

## 11. Version History

| Version | Changes |
|---------|---------|
| 1.0.0 | Initial release with PeeGeeQRuntime, RuntimeConfig, PeeGeeQContext |
| 1.1.0 | Added RuntimeDatabaseSetupService facade |
| 1.2.0 | Added factory registration pattern for native and outbox |

