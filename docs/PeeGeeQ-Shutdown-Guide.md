# PeeGeeQ Application Shutdown Guide

## Overview

This guide explains how to properly shut down PeeGeeQ applications to ensure all resources are released cleanly and no background threads are left running.

## Critical Shutdown Requirements

### 1. PeeGeeQManager Shutdown

**ALWAYS** call `close()` on `PeeGeeQManager` during application shutdown. This is the most critical step.

```java
PeeGeeQManager manager = new PeeGeeQManager(configuration, meterRegistry);
try {
    manager.start();
    // ... application logic ...
} finally {
    manager.close();  // CRITICAL: Always close the manager
}
```

**What `PeeGeeQManager.close()` does:**
- Stops all background services (health checks, stuck message recovery, etc.)
- Closes the PgClientFactory and all database connection pools
- Closes shared Vert.x instances from OutboxProducer, OutboxConsumer, and VertxPoolAdapter
- Closes the main Vert.x instance and all event loop threads
- Releases all system resources

### 2. Consumer and Producer Shutdown

**ALWAYS** close consumers and producers before closing the manager:

```java
MessageProducer<OrderEvent> producer = factory.createProducer("orders", OrderEvent.class);
MessageConsumer<OrderEvent> consumer = factory.createConsumer("orders", OrderEvent.class);

try {
    // ... use producer and consumer ...
} finally {
    // Close in reverse order of creation
    if (consumer != null) consumer.close();
    if (producer != null) producer.close();
    if (manager != null) manager.close();
}
```

**What `consumer.close()` does:**
- Unsubscribes from message processing
- Shuts down the polling scheduler (waits up to 5 seconds)
- Shuts down the message processing executor (waits up to 10 seconds)
- Closes the reactive Vert.x pool

**What `producer.close()` does:**
- Closes the reactive Vert.x pool
- Marks the producer as closed

### 3. Shared Vert.x Instance Cleanup

PeeGeeQ uses **static shared Vert.x instances** in three modules for proper transaction context management:
- `OutboxProducer` - for outbox pattern message production
- `OutboxConsumer` - for outbox pattern message consumption
- `VertxPoolAdapter` - for bi-temporal event store operations

**These are automatically closed by `PeeGeeQManager.close()`** using reflection to avoid compile-time dependencies.

If you're using these modules **without** PeeGeeQManager, you must manually close them:

```java
// Only needed if NOT using PeeGeeQManager
OutboxProducer.closeSharedVertx();
OutboxConsumer.closeSharedVertx();
VertxPoolAdapter.closeSharedVertx();
```

## Shutdown Patterns

### Pattern 1: Standalone Application

```java
public class MyApplication {
    private static PeeGeeQManager manager;
    
    public static void main(String[] args) {
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down application...");
            if (manager != null) {
                manager.close();
            }
            logger.info("Application shutdown complete");
        }));
        
        // Initialize and start
        manager = new PeeGeeQManager(configuration, meterRegistry);
        manager.start();
        
        // ... application logic ...
    }
}
```

### Pattern 2: Spring Boot Application

```java
@Configuration
public class PeeGeeQConfig {
    
    @Bean(destroyMethod = "close")
    public PeeGeeQManager peeGeeQManager(
            PeeGeeQProperties properties,
            MeterRegistry meterRegistry) {
        
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getEnvironment());
        // ... configure ...
        
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();
        return manager;
    }
}
```

**Important:** Use `destroyMethod = "close"` (not `"stop"`) to ensure proper cleanup.

### Pattern 3: Service with Consumers

```java
@Service
public class OrderProcessingService {
    private final MessageConsumer<OrderEvent> consumer;
    
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        consumer.subscribe(this::handleOrder);
    }
    
    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.close();  // Properly shuts down scheduler and executor
        }
    }
    
    private void handleOrder(Message<OrderEvent> message) {
        // ... process order ...
    }
}
```

### Pattern 4: Test Cleanup

```java
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MyIntegrationTest {
    
    @Autowired
    private PeeGeeQManager manager;
    
    @Autowired
    private MessageConsumer<String> consumer;
    
    @AfterEach
    void tearDown() throws Exception {
        // Close consumers first
        if (consumer != null) {
            consumer.close();
        }
    }
    
    // @DirtiesContext ensures Spring calls destroyMethod="close" on manager
}
```

**Important:** Use `@DirtiesContext` to force Spring to destroy the application context after tests, which calls all `@PreDestroy` methods and `destroyMethod` callbacks.

## Common Shutdown Issues

### Issue 1: "Connection refused" errors during shutdown

**Symptom:** Stack traces showing connection errors during application shutdown.

**Cause:** Background threads trying to reconnect while database is shutting down.

**Solution:** Ensure proper shutdown order:
1. Close consumers (stops polling)
2. Close producers
3. Close manager (stops all background services and Vert.x)

### Issue 2: Application hangs during shutdown

**Symptom:** Application doesn't exit cleanly, requires force kill.

**Cause:** Vert.x event loop threads still running.

**Solution:** Ensure `PeeGeeQManager.close()` is called. This closes all Vert.x instances.

### Issue 3: Test context caching prevents cleanup

**Symptom:** Tests pass but logs show errors from previous tests.

**Cause:** Spring Boot caches test contexts, so `@PreDestroy` methods aren't called between test classes.

**Solution:** Add `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)` to test classes.

### Issue 4: Shared Vert.x instances not closed

**Symptom:** Memory leak, Vert.x threads accumulate over time.

**Cause:** Static shared Vert.x instances in OutboxProducer/OutboxConsumer/VertxPoolAdapter not closed.

**Solution:** Call `PeeGeeQManager.close()` which automatically closes these shared instances.

## Shutdown Checklist

- [ ] All `MessageConsumer` instances have `close()` called
- [ ] All `MessageProducer` instances have `close()` called
- [ ] `PeeGeeQManager.close()` is called (either explicitly or via Spring `destroyMethod`)
- [ ] Shutdown hook registered for standalone applications
- [ ] Spring Boot configs use `destroyMethod = "close"` (not `"stop"`)
- [ ] Services use `@PreDestroy` to close consumers
- [ ] Tests use `@DirtiesContext` to force context cleanup
- [ ] No "Connection refused" errors in shutdown logs
- [ ] Application exits cleanly without hanging

## Monitoring Shutdown

Enable INFO logging to verify proper shutdown:

```properties
logging.level.dev.mars.peegeeq=INFO
```

Expected shutdown log messages:

```
INFO  d.m.p.db.PeeGeeQManager - Stopping PeeGeeQ Manager
INFO  d.m.p.db.PeeGeeQManager - Closing shared Vert.x instances from outbox and bi-temporal modules
INFO  d.m.p.outbox.OutboxProducer - Closed shared Vertx instance for OutboxProducer
INFO  d.m.p.outbox.OutboxConsumer - Closed shared Vertx instance for OutboxConsumer
INFO  d.m.p.bitemporal.VertxPoolAdapter - Closed shared Vertx instance for bi-temporal event store
INFO  d.m.p.db.PeeGeeQManager - Shared Vert.x instances cleanup completed
INFO  d.m.p.db.PeeGeeQManager - Closing Vert.x instance
INFO  d.m.p.db.PeeGeeQManager - Vert.x instance closed successfully
```

## Best Practices

1. **Always use try-finally or try-with-resources** for resource management
2. **Close resources in reverse order** of creation (consumers → producers → manager)
3. **Use Spring lifecycle methods** (`@PreDestroy`, `destroyMethod`) for automatic cleanup
4. **Register shutdown hooks** for standalone applications
5. **Use `@DirtiesContext`** in integration tests to ensure proper cleanup
6. **Monitor shutdown logs** to verify clean shutdown
7. **Wait for graceful shutdown** - don't force kill unless necessary

## Architecture Notes

### Why Shared Vert.x Instances?

PeeGeeQ uses shared static Vert.x instances in OutboxProducer, OutboxConsumer, and VertxPoolAdapter to ensure proper transaction context propagation when using `TransactionPropagation.CONTEXT`. This allows multiple instances to share the same Vert.x context for coordinated transaction management.

### Why Reflection for Cleanup?

`PeeGeeQManager` uses reflection to close shared Vert.x instances to avoid compile-time dependencies on optional modules (peegeeq-outbox, peegeeq-bitemporal). This allows applications to use only the modules they need without forcing all dependencies.

### Shutdown Timeout Values

- Consumer scheduler shutdown: 5 seconds
- Consumer message processing executor shutdown: 10 seconds
- Vert.x instance close: 10 seconds

These timeouts are designed to allow graceful completion of in-flight operations while preventing indefinite hangs.

## See Also

- [PeeGeeQ Architecture & API Reference](PeeGeeQ-Architecture-API-Reference.md)
- [PeeGeeQ Coding Principles](devtest/pgq-coding-principles.md)
- [Spring Boot Examples](../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/)

