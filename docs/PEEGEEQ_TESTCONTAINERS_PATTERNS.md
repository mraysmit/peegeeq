# Testcontainers Usage Patterns in PeeGeeQ

## Overview
This document describes the different Testcontainers patterns used across the PeeGeeQ codebase for PostgreSQL integration testing.

## Container Lifecycle Patterns

### 1. JUnit 5 Extension Pattern (peegeeq-db)
**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java`

**Characteristics**:
- Single shared container for ALL tests in the module
- Schema initialized ONCE using double-checked locking
- Thread-safe for parallel test execution
- Container reuse enabled (`withReuse(true)`)
- Database: `peegeeq_test` / User: `peegeeq_test`
- Max connections: 200, Shared memory: 256MB

**Usage**:
```java
@ExtendWith(SharedPostgresTestExtension.class)
public abstract class BaseIntegrationTest {
    protected PostgreSQLContainer<?> getPostgres() {
        return SharedPostgresTestExtension.getContainer();
    }
}
```

### 2. Shared Static Container Pattern (Examples Modules)
**Location**: `peegeeq-examples`, `peegeeq-examples-spring`

**Characteristics**:
- Lazy-initialized shared container with `synchronized` method
- Container started immediately when first accessed
- Container reuse enabled (`withReuse(true)`)
- Database: `peegeeq_shared_test` / User: `peegeeq_test`
- Max connections: 2000, Shared memory: 512MB
- Spring `@DynamicPropertySource` support

**Usage**:
```java
@Container
static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    SharedTestContainers.configureSharedProperties(registry);
}
```

### 3. Per-Test Container Pattern (Isolation Required)
**Characteristics**:
- Fresh container for each test class
- Container reuse disabled (`withReuse(false)`)
- Used for tests that modify global state
- Explicit `start()` and `stop()` calls in try-finally

**Usage**:
```java
PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
    .withDatabaseName("peegeeq_demo")
    .withReuse(false);
try {
    postgres.start();
    // test code
} finally {
    postgres.stop();
}
```

### 4. @Testcontainers Annotation Pattern (Standard JUnit 5)
**Characteristics**:
- Automatic lifecycle management by JUnit
- No manual start/stop needed
- Can use shared or dedicated containers

**Usage**:
```java
@Testcontainers
class MyTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = 
        new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
}
```

## PostgreSQL Configuration Comparison

| Pattern | Max Connections | Shared Memory | Reuse | Performance Tuning |
|---------|----------------|---------------|-------|-------------------|
| SharedPostgresTestExtension | 200 | 256MB | ✅ | Moderate (fsync=off, synchronous_commit=off) |
| SharedTestContainers | 2000 | 512MB | ✅ | High (extensive tuning) |
| Dedicated Containers | 200 | 256MB | ❌ | Moderate |
| High-Performance | 500 | 1GB | ❌ | Maximum (full_page_writes=off) |

## Database Naming Conventions

| Module | Database Name | Username | Password |
|--------|--------------|----------|----------|
| peegeeq-db | `peegeeq_test` | `peegeeq_test` | `peegeeq_test` |
| peegeeq-examples | `peegeeq_shared_test` | `peegeeq_test` | `peegeeq_test` |
| peegeeq-examples-spring | `peegeeq_shared_test` | `peegeeq_test` | `peegeeq_test` |
| Dedicated/Isolated | Custom (e.g., `peegeeq_demo`) | `peegeeq_test` | `peegeeq_test` |

## Schema Initialization Strategies

### One-Time Schema Init (peegeeq-db)
- Schema created ONCE by `SharedPostgresTestExtension`
- Uses JDBC for synchronous execution
- Thread-safe with double-checked locking
- Creates all tables: outbox, queue_messages, dead_letter_queue, consumer groups, etc.
- Migrations DISABLED in tests (`peegeeq.migration.enabled=false`)

### Per-Test Migration (Examples)
- Migrations ENABLED (`peegeeq.migration.enabled=true`)
- Auto-migrate on startup (`peegeeq.migration.auto-migrate=true`)
- Each test may run migrations independently

### Manual Schema Setup
- Some tests manually create tables using JDBC
- Used for specific test scenarios

## Spring Integration

### @DynamicPropertySource Pattern
Used in Spring tests to configure properties dynamically:

```java
@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    SharedTestContainers.configureSharedProperties(registry);
}
```

**Properties Configured**:
- Standard PeeGeeQ properties (`peegeeq.database.*`)
- R2DBC properties (`spring.r2dbc.*`)
- Bi-temporal properties
- Dead Letter Queue properties
- Consumer properties
- Environment variables (`DB_HOST`, `DB_PORT`, etc.)

**Note**: System properties are NOT used in Spring tests to avoid race conditions in parallel execution.

## Module Comparison

| Aspect | peegeeq-db | peegeeq-examples | peegeeq-examples-spring |
|--------|-----------|------------------|------------------------|
| Container Management | JUnit Extension | Static Shared | Static Shared |
| Lifecycle | Automatic | Manual/Automatic | Automatic |
| Schema Init | One-time (JDBC) | Per-test (migrations) | Per-test (migrations) |
| Parallel Safety | ✅ Thread-safe | ✅ Thread-safe | ✅ Thread-safe |
| Configuration | System properties | System properties | @DynamicPropertySource |
| Max Connections | 200 | 2000 | 2000 |
| Reuse | Yes | Yes | Yes |
| Migrations | Disabled | Enabled | Enabled |

## Best Practices

1. ✅ Use shared containers for faster test execution
2. ✅ Enable container reuse (`withReuse(true)`) when possible
3. ✅ Use JUnit Extension for sophisticated lifecycle management
4. ✅ Disable fsync and synchronous_commit for test performance
5. ✅ Use @DynamicPropertySource in Spring tests (not system properties)
6. ✅ Initialize schema once for parallel test execution
7. ✅ Use dedicated containers only when isolation is required
8. ✅ Configure high max_connections for parallel tests
9. ✅ Use thread-safe initialization (double-checked locking or synchronized)
10. ✅ Add shutdown hooks for cleanup

## When to Use Each Pattern

- **JUnit Extension**: Best for module-wide shared container with one-time schema setup
- **Shared Static Container**: Good for examples/demos with migration-based schema
- **Per-Test Container**: Only when test isolation is absolutely required
- **@Testcontainers**: Standard approach for simple test classes

