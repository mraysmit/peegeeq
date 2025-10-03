# Migration Script for Test Files

## Status: ✅ 19 of 19 files migrated (100% COMPLETE)

### All Migrations Completed ✅

#### Core Tests
1. ✅ BaseIntegrationTest.java
2. ✅ PgClientTest.java
3. ✅ SchemaMigrationManagerTest.java
4. ✅ PeeGeeQDatabaseSetupServiceEnhancedTest.java
5. ✅ PgConnectionManagerTest.java
6. ✅ PgListenerConnectionTest.java
7. ✅ DeadLetterQueueManagerTest.java (with @ResourceLock + data cleanup)
8. ✅ HealthCheckManagerTest.java (with @ResourceLock + data cleanup)
9. ✅ PeeGeeQMetricsTest.java (with @ResourceLock + data cleanup)
10. ✅ PgTransactionManagerTest.java (with @ResourceLock)
11. ✅ PeeGeeQPerformanceTest.java

#### Example Tests
12. ✅ examples/AdvancedConfigurationExampleTest.java
13. ✅ examples/MultiConfigurationExampleTest.java
14. ✅ examples/PeeGeeQExampleTest.java
15. ✅ examples/PeeGeeQSelfContainedDemoTest.java
16. ✅ examples/PerformanceComparisonExampleTest.java
17. ✅ examples/PerformanceTuningExampleTest.java
18. ✅ examples/SecurityConfigurationExampleTest.java
19. ✅ examples/SimpleConsumerGroupTestTest.java

## Migration Pattern

For each file, follow these steps:

### Step 1: Update Imports
**Remove:**
```java
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
```

**Add:**
```java
import dev.mars.peegeeq.db.SharedPostgresExtension;
import org.junit.jupiter.api.extension.ExtendWith;
```

### Step 2: Update Class Annotation
**Replace:**
```java
@Testcontainers
class MyTest {
```

**With:**
```java
@ExtendWith(SharedPostgresExtension.class)
class MyTest {
```

### Step 3: Remove @Container Field
**Remove:**
```java
@Container
@SuppressWarnings("resource")
private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("my_test")
        .withUsername("test_user")
        .withPassword("test_pass");
```

### Step 4: Remove @BeforeAll Schema Initialization (if present)
**Remove:**
```java
private static final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

@BeforeAll
static void initializeSchema() throws Exception {
    if (schemaInitialized.compareAndSet(false, true)) {
        // Schema creation code...
    }
}
```

### Step 5: Update All References to `postgres`
**In @BeforeEach or test methods, replace:**
```java
postgres.getHost()
postgres.getFirstMappedPort()
postgres.getDatabaseName()
postgres.getUsername()
postgres.getPassword()
postgres.getJdbcUrl()
```

**With:**
```java
PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
// Then use postgres.getHost(), etc.
```

### Step 6: Add @ResourceLock if Needed
If the test modifies database schema (like migration tests), add:
```java
@ExtendWith(SharedPostgresExtension.class)
@ResourceLock("database-schema")
class MyTest {
```

## Example: Complete Migration

### Before:
```java
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.atomic.AtomicBoolean;

@Testcontainers
class HealthCheckManagerTest {
    
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("health_test")
            .withUsername("test_user")
            .withPassword("test_pass");
    
    private static final AtomicBoolean schemaInitialized = new AtomicBoolean(false);
    
    @BeforeAll
    static void initializeSchema() throws Exception {
        if (schemaInitialized.compareAndSet(false, true)) {
            String jdbcUrl = postgres.getJdbcUrl();
            // Create tables...
        }
    }
    
    @BeforeEach
    void setUp() {
        PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
    }
}
```

### After:
```java
import dev.mars.peegeeq.db.SharedPostgresExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;

@ExtendWith(SharedPostgresExtension.class)
class HealthCheckManagerTest {
    
    @BeforeEach
    void setUp() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        
        PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
    }
}
```

## Testing After Migration

After migrating each file, run:
```bash
mvn test -pl peegeeq-db -Dtest=YourTestClass
```

To verify:
1. Tests compile successfully
2. Tests run in parallel (check for ForkJoinPool-1-worker-X in logs)
3. No schema conflicts or duplicate key errors
4. All tests pass

## Batch Testing

Test multiple migrated files at once:
```bash
mvn test -pl peegeeq-db -Dtest=Test1,Test2,Test3
```

## Full Module Test

After all migrations:
```bash
mvn test -pl peegeeq-db
```

Expected improvements:
- Faster test execution (2-4x speedup)
- Parallel execution visible in logs
- No schema conflicts
- All tests passing

## Notes

- Schema is initialized once by SharedPostgresExtension
- Tests can run in parallel safely
- No need to recreate tables in individual tests
- Clean data in @BeforeEach, don't recreate schema
- Use @ResourceLock for tests that modify schema

