# PeeGeeQ Coding Principles & Standards

Based on refactoring lessons learned, strictly adhere to the following principles.

## **🚨 CRITICAL: TestContainers Mandatory Policy**

### **Principle: "Database-Centric Systems Require Real Databases"**

**PeeGeeQ is a PostgreSQL queue system. Almost zero operations do not involve the database.**

- ✅ **MANDATORY**: Use TestContainers for ALL tests involving database operations
- ❌ **FORBIDDEN**: Mocking database connections, repositories, or SQL operations  
- ❌ **FORBIDDEN**: Using H2, HSQLDB, or in-memory databases as PostgreSQL substitutes
- ❌ **FORBIDDEN**: Skipping database tests because "TestContainers is slow"

**No Exceptions. No Excuses.**

```java
// ✅ CORRECT: Any database operation uses TestContainers
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerCoreTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
    
    @Test
    void testSendMessage() {
        // Tests real database writes through OutboxProducer
    }
}

// ✅ CORRECT: Pure logic test with NO database
@Tag(TestCategories.CORE)
class CircuitBreakerRecoveryTest {
    // NO @Testcontainers needed - tests pure state machine logic
    // No database operations = no TestContainers required
    
    @Test
    void testStateTransitions() {
        // Tests circuit breaker CLOSED → OPEN → HALF_OPEN logic
    }
}

// ❌ WRONG: Database test without TestContainers
@Tag(TestCategories.CORE)
class SomeTest {
    @Test
    void testDatabaseOperation() {
        OutboxFactory factory = ... // ← This will access database
        factory.createProducer(...); // ← Database operation!
        // MUST use @Tag(TestCategories.INTEGRATION) and @Testcontainers
    }
}
```

**When to Use Each Tag:**
- `@Tag(TestCategories.CORE)`: Pure logic tests with **ZERO** database operations
  - Examples: Circuit breaker logic, filter predicates, error handling paths, config validation
  - Run in default Maven profile for fast feedback
  
- `@Tag(TestCategories.INTEGRATION)`: **ANY** test that touches the database
  - Examples: Producer.send(), Consumer.poll(), Factory creation, message persistence
  - Requires `@Testcontainers` with PostgreSQL container
  - Run with `-Pintegration-tests` profile

**If you're unsure whether your test needs TestContainers, ask:**
1. Does this test create OutboxFactory/OutboxProducer/OutboxConsumer?
2. Does this test send, receive, or query messages?
3. Does this test involve PeeGeeQManager or DatabaseService?

**If YES to any → Use INTEGRATION + TestContainers. No exceptions.**

---

## **Investigation Before Implementation**

### **Principle: "Understand Before You Change"**
- **My Mistake**: I initially added graceful error handling to `SystemPropertiesIntegrationTest` without investigating why it was failing
- **Better Approach**: Always investigate the root cause first
- **Code Practice**:
  ```java
  // BAD: Catching and hiding errors without understanding
  try {
      // database operation
  } catch (Exception e) {
      logger.warn("Expected failure, skipping...");
      return; // Hide the real problem
  }
  
  // GOOD: Investigate and fix the root cause
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
      .withDatabaseName("test_db");
  ```

## **Learn From Existing Patterns**

### **Principle: "Follow Established Conventions"**
- **My Mistake**: I didn't check how other integration tests in the project were structured
- **Better Approach**: Always examine existing patterns before creating new ones
- **Code Practice**:
  ```java
  // Research existing patterns first
  @Testcontainers  // ← Found this pattern in other tests
  class MyIntegrationTest {
      @Container
      static PostgreSQLContainer<?> postgres = // ← Consistent setup
  }
  ```

## **Verify Assumptions**

### **Principle: "Test Your Understanding"**
- **My Mistake**: I assumed the test was "working as intended" without carefully reading the logs
- **Better Approach**: Always verify that tests are actually doing what you think they're doing
- **Code Practice**:
  ```java
  // Don't assume - verify with explicit logging
  @Test
  void testDatabaseConnection() {
      logger.info("Testing with database: {}:{}", 
          postgres.getHost(), postgres.getFirstMappedPort());
      // Explicit verification of what's happening
  }
  ```

## **Precise Problem Identification**

### **Principle: "Fix the Cause, Not the Symptom"**
- **My Mistake**: I treated database connection failures as "expected behavior" instead of missing TestContainers setup
- **Better Approach**: Distinguish between legitimate failures and configuration issues
- **Code Practice**:
  ```java
  // BAD: Masking configuration problems
  if (databaseConnectionFailed) {
      logger.warn("Expected failure in test environment");
      return; // Wrong - this hides real issues
  }
  
  // GOOD: Proper test infrastructure
  @BeforeEach
  void configureDatabase() {
      System.setProperty("db.host", postgres.getHost());
      System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
  }
  ```

## **Clear Documentation Standards**

### **Principle: "Document Intent, Not Just Implementation"**
- **My Mistake**: I wrote misleading comments about "expected behavior" when the real issue was missing setup
- **Better Approach**: Document the actual purpose and requirements
- **Code Practice**:
  ```java
  /**
   * Integration test that validates system properties with a real database.
   * Uses TestContainers to provide PostgreSQL for testing.
   * 
   * Requirements:
   * - Docker must be available for TestContainers
   * - Test validates actual database connectivity
   */
  @Testcontainers
  class SystemPropertiesIntegrationTest {
  ```

## **Iterative Validation**

### **Principle: "Validate Each Step"**
- **My Mistake**: I made multiple changes without validating each one individually
- **Better Approach**: Make small changes and verify each step works
- **Code Practice**:
  ```java
  // Step 1: Add TestContainers - verify it starts
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
  
  // Step 2: Configure connection - verify it connects
  System.setProperty("db.host", postgres.getHost());
  
  // Step 3: Test actual functionality - verify it works
  ```

## **Test Classification**

### **Principle: "Clearly Distinguish Test Types"**
- **My Mistake**: I confused integration tests (which should have real infrastructure) with unit tests (which can mock)
- **Better Approach**: Be explicit about what each test requires
- **Code Practice**:
  ```java
  // Unit Test (CORE) - Pure logic tests with NO database operations
  // Use @Tag(TestCategories.CORE) for fast-running tests that test pure logic
  // Examples: Circuit breaker state transitions, filter logic, error handling paths
  @Tag(TestCategories.CORE)
  class CircuitBreakerRecoveryTest {
      // NO @Testcontainers - this tests pure logic, not database operations
      // Use real objects or simple manual stubs, NOT Mockito
      
      @Test
      void testCircuitBreakerTransitions() {
          // Test circuit breaker logic directly without database
      }
  }
  
  // Integration Test (INTEGRATION) - ANY test involving database operations
  // Use @Tag(TestCategories.INTEGRATION) for ALL tests that touch the database
  // MANDATORY: Use TestContainers for real PostgreSQL - NO EXCEPTIONS
  @Tag(TestCategories.INTEGRATION)
  @Testcontainers
  class OutboxProducerCoreTest {
      @Container
      static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
      
      @Test
      void testProducerSendsMessage() {
          // Any test that reads/writes database MUST use TestContainers
      }
  }
  
  // STRICT RULES FOR PEEGEEQ (Database-Centric System):
  // 1. NO Mockito or Reflection - EVER
  // 2. ANY test involving database operations MUST use TestContainers
  // 3. TestContainers is NOT optional - this is a database-centric system
  // 4. Only tag tests as CORE if they test pure logic with ZERO database operations
  // 5. When in doubt, use INTEGRATION with TestContainers
  ```

### **TestContainers: Mandatory for Database-Centric Systems**

**CRITICAL**: PeeGeeQ is a **database-centric PostgreSQL queue system**. Almost every operation involves database reads/writes. Therefore:

- ✅ **REQUIRED**: Use TestContainers for ANY test that touches the database
- ❌ **FORBIDDEN**: Mocking database connections, repositories, or SQL operations
- ❌ **FORBIDDEN**: Using H2, HSQLDB, or any in-memory database as PostgreSQL substitutes
- ✅ **CORRECT**: Real PostgreSQL via TestContainers for authentic behavior

**When to Use CORE vs INTEGRATION Tags:**

```java
// ✅ CORE: Pure logic, no database
@Tag(TestCategories.CORE)
class CircuitBreakerLogicTest {
    // Tests state transitions: CLOSED → OPEN → HALF_OPEN
    // No database needed - pure in-memory logic
}

// ✅ CORE: Pure logic, no database
@Tag(TestCategories.CORE)
class FilterValidationTest {
    // Tests message filtering predicates
    // No database needed - pure function logic
}

// ✅ INTEGRATION: Uses database
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerTest {
    @Container
    static PostgreSQLContainer<?> postgres = // REQUIRED
    
    // Tests producer.send() which writes to database
    // Database operation = MUST use TestContainers
}

// ✅ INTEGRATION: Uses database
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxConsumerTest {
    @Container
    static PostgreSQLContainer<?> postgres = // REQUIRED
    
    // Tests consumer.poll() which reads from database
    // Database operation = MUST use TestContainers
}

// ❌ WRONG: Database test without TestContainers
@Tag(TestCategories.CORE)  // ← WRONG TAG
class OutboxFactoryTest {
    @Test
    void testCreateProducer() {
        // This creates a producer that will access database
        // MUST be @Tag(TestCategories.INTEGRATION)
        // MUST use @Testcontainers
    }
}
```

**No Excuses Policy:**
- "It's too slow" → Use INTEGRATION profile for CI, CORE for quick feedback
- "TestContainers is complex" → We have established patterns (see OutboxBasicTest)
- "Docker not available" → Fix your development environment
- "Just testing configuration" → If config affects database behavior, use TestContainers

**Summary: If it touches PostgreSQL, it MUST use TestContainers. Period.**

## **Honest Error Handling**

### **Principle: "Fail Fast, Fail Clearly"**
- **My Mistake**: I tried to make tests "pass gracefully" when they should have been fixed to work properly
- **Better Approach**: Let tests fail when there are real problems, fix the problems
- **Code Practice**:
  ```java
  // BAD: Hiding real failures
  try {
      realOperation();
  } catch (Exception e) {
      logger.warn("Skipping due to environment");
      return; // Test "passes" but doesn't test anything
  }
  
  // GOOD: Proper setup so tests can succeed
  @Container
  static PostgreSQLContainer<?> postgres = // Provide real infrastructure
  ```

## **Log Analysis Skills**

### **Principle: "Read Logs Carefully"**
- **My Mistake**: I didn't carefully analyze what the error logs were actually telling me
- **Better Approach**: Parse error messages to understand the real problem
- **Code Practice**:
  ```java
  // When you see: "UnknownHostException: test-host"
  // Don't think: "Expected failure in test environment"
  // Think: "This test needs a real database host"
  
  // Solution: Provide the real host via TestContainers
  System.setProperty("db.host", postgres.getHost());
  ```

## **Modern Vert.x 5.x Composable Future Patterns**

### **Principle: "Use Composable Futures, Not Callbacks"**
- **Requirement**: All asynchronous operations must use Vert.x 5.x composable Future patterns
- **Better Approach**: Use `.compose()` chains instead of nested callbacks for better readability and error handling
- **Code Practice**:
  ```java
  // BAD: Old callback style (avoid)
  server.listen(8080, ar -> {
      if (ar.succeeded()) {
          doWarmup(warmupResult -> {
              if (warmupResult.succeeded()) {
                  registerWithRegistry(registryResult -> {
                      // Callback hell...
                  });
              }
          });
      }
  });

  // GOOD: Modern Vert.x 5.x composable style (required)
  server.listen(8080)
      .compose(s -> doWarmup())           // returns Future<Void>
      .compose(v -> registerWithRegistry()) // returns Future<Void>
      .onSuccess(v -> System.out.println("Server is ready"))
      .onFailure(Throwable::printStackTrace);

  // GOOD: Log terminal error, propagate unchanged
  primaryOperation()
      .onFailure(throwable -> logger.warn("Primary failed: {}", throwable.getMessage()))
      .onSuccess(result -> handleResult(result));

  // NOTE: .recover() is BANNED. For optional steps where failure should not stop
  // the chain, use .transform(). For real retry/failover, use circuit breaker middleware.

  // BAD: Old .onComplete(ar -> { if (ar.succeeded()) ... }) pattern
  queue.send(message)
      .onComplete(ar -> {
          if (ar.succeeded()) {
              latch.countDown();
          } else {
              fail("Failed: " + ar.cause().getMessage());
          }
      });

  // GOOD: Modern .onSuccess()/.onFailure() pattern
  queue.send(message)
      .onSuccess(v -> latch.countDown())
      .onFailure(throwable -> fail("Failed: " + throwable.getMessage()));
  ```

## **Forbidden Reactive Patterns (Hard Rules)**

These patterns are banned everywhere in PeeGeeQ — production code, tests, and examples.
They appear as "convenient" shorthands but all hide failures or block threads.

### ❌ `.recover()` — Zero Legitimate Uses

`.recover()` has been audited across 117 instances in the codebase. All 117 were wrong.
The correct replacements are listed in Best Practice 2 above.

```java
// ALL of these are BANNED
future.recover(e -> Future.succeededFuture());               // error silencing
future.recover(e -> { logger.warn(...); return Future.succeededFuture(); }); // ERASURE-IN-SHUTDOWN
future.recover(e -> fallback());                             // use circuit breaker / retry instead
```

### ❌ `.onComplete(ar -> { if (ar.succeeded()) ... })` — Use `.onSuccess()`/`.onFailure()`

```java
// ❌ BANNED
future.onComplete(ar -> {
    if (ar.succeeded()) { doSuccess(ar.result()); }
    else { logger.error("failed", ar.cause()); }
});

// ✅ CORRECT
future
    .onSuccess(result -> doSuccess(result))
    .onFailure(e -> logger.error("failed", e));
```

### ❌ Fire-and-Forget Futures

```java
// ❌ BANNED — send failure is silently lost
producer.send("message");

// ✅ CORRECT — chain or observe
producer.send("message")
    .onFailure(testContext::failNow);
```

### ❌ Blocking Waits on the Event Loop

```java
// ALL of these are BANNED on the Vert.x event loop thread
Thread.sleep(500);
LockSupport.parkNanos(200_000_000L);
future.toCompletionStage().toCompletableFuture().get();
future.toCompletionStage().toCompletableFuture().join();

// ✅ CORRECT — event-driven delay
vertx.timer(500).compose(v -> nextStep());
```

### ❌ `setTimer` as a Readiness Guard

```java
// ❌ BANNED — timers mask races; they do not guarantee readiness
vertx.setTimer(100, id -> testContext.completeNow());

// ✅ CORRECT — chain directly off the async operation
deployVerticle().compose(v -> doWork()).onSuccess(v -> testContext.completeNow());
```

### ❌ Raw JDBC in Tests

```java
// ❌ BANNED
Connection conn = DriverManager.getConnection(url, user, pass);
PreparedStatement ps = conn.prepareStatement("SELECT ...");

// ✅ CORRECT — use Vert.x PgClient
pool.withConnection(conn -> conn.preparedQuery("SELECT ...").execute(Tuple.of(...)));
```

---

## **Database Write Operations**

### **Principle: "Writes Require Transactions"**

**ALL DML operations (`INSERT`, `UPDATE`, `DELETE`) must use `pool.withTransaction()`,
never `pool.withConnection()`.**

`pool.withConnection()` borrows a connection from the pool but does **not** begin a
transaction. Writes execute in auto-commit mode only if the driver defaults to it;
failures leave partial state with no rollback.

```java
// ❌ WRONG — no transaction; write may not commit; no rollback on failure
return pool.withConnection(conn ->
    conn.preparedQuery("DELETE FROM outbox_messages WHERE id = $1")
        .execute(Tuple.of(id)));

// ✅ CORRECT — commit on success, rollback on failure
return pool.withTransaction(conn ->
    conn.preparedQuery("DELETE FROM outbox_messages WHERE id = $1")
        .execute(Tuple.of(id)));
```

Use `pool.withConnection()` only for read-only queries where auto-commit semantics are
acceptable and no transactional guarantee is required.

---

## **Test Infrastructure Integrity**

### **Principle: "Test Setup Must Be Verified by Tests"**

Test infrastructure code — property writes, pool configuration, container setup — must
itself have contract tests. A misconfigured test base class silently degrades every
downstream test in the project without any individual test failing for the right reason.

#### Rule 1: Property keys must exactly match what the loader reads

```java
// ❌ WRONG — Duration string form; loader reads millisecond long form
System.setProperty("peegeeq.database.pool.idle-timeout", "PT10S");

// ✅ CORRECT — matches the key name in PeeGeeQConfiguration.getPoolConfig()
System.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
```

#### Rule 2: Test pools must use `shared=false`

With `shared=true` (the production default), `Pool.close()` uses reference counting
and does NOT deterministically release TCP sockets. Integration tests need deterministic
teardown.

```java
// ❌ WRONG — inherits shared=true; Pool.close() is non-deterministic
// (property not set → production default applies)

// ✅ CORRECT — always set explicitly in test setup
System.setProperty("peegeeq.database.pool.shared", "false");
```

#### Rule 3: Secondary `PgConnectionManager` fields must be closed in `@AfterEach`

```java
// ❌ WRONG — leaked pool; base class only closes its own manager
@BeforeEach void setUp() {
    connectionManager = new PgConnectionManager(manager.getVertx(), null);
    connectionManager.getOrCreateReactivePool("peegeeq-main", cfg, poolCfg);
    // never closed
}

// ✅ CORRECT
@AfterEach void tearDown() throws Exception {
    if (connectionManager != null) {
        connectionManager.close();
    }
}
```

#### Rule 4: Test infrastructure must have `@Tag(CORE)` contract tests

```java
// Every test base class that sets pool system properties must have a contract test:
@Tag(TestCategories.CORE)
class BaseIntegrationTestPoolConfigContractCoreTest {
    @BeforeEach void setUp() { BaseIntegrationTest.setupTestConfiguration(); }
    @AfterEach void tearDown() {
        System.clearProperty("peegeeq.database.pool.idle-timeout-ms");
        // clear all properties set in setUp
    }

    @Test void poolIdleTimeoutIsShort() {
        assertThat(new PeeGeeQConfiguration("test").getPoolConfig().getIdleTimeout())
            .isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
    // one assertion per documented property
}
```

This test runs on every `mvn test` (no `-Pintegration-tests` required) and catches
property-key regressions before they silently exhaust the connection pool.

---

## **Summary: Core Principles**

1. **Investigate First**: Understand the problem before implementing solutions
2. **Follow Patterns**: Learn from existing code in the same project
3. **Verify Assumptions**: Don't assume tests are working - check the logs
4. **Fix Root Causes**: Address configuration issues, don't mask them
5. **Document Honestly**: Write comments that reflect actual behavior
6. **Validate Incrementally**: Test each change before moving to the next
7. **Classify Tests Clearly**: Know whether you're writing unit or integration tests
8. **Fail Honestly**: Let tests fail when there are real problems to fix
9. **Read Logs Carefully**: Error messages usually tell you exactly what's wrong
10. **Use Modern Vert.x 5.x Patterns**: Always use composable Futures (`.compose()`, `.onSuccess()`, `.onFailure()`) instead of callback-style programming

Do not reinvent the wheel as you are to do. Work incrementally and test after each small incremental change. When testing make sure you scan the test logs properly for test errors, do not rely on the exit code as that is largely meaningless.

## **Critical Test Execution Validation**

### **Principle: "Verify Test Methods Are Actually Executing"**
- **Critical Discovery**: Maven can report "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" even when test methods never execute
- **Root Cause**: TestContainers initialization failures can silently prevent test method execution
- **Detection Method**: Use Maven debug mode (`-X`) and explicit diagnostic logging to verify test method execution

### **Maven Test Profile Configuration (CRITICAL)**

**PeeGeeQ uses Maven profiles to separate fast CORE tests from slower INTEGRATION tests.**

- ❌ **WRONG**: `mvn test` (runs CORE tests only, excludes INTEGRATION)
- ❌ **WRONG**: `mvn test -Dtest=OutboxProducerCoreTest` (test won't run if tagged INTEGRATION)
- ✅ **CORRECT**: `mvn test -Pintegration-tests` (runs all INTEGRATION tests)
- ✅ **CORRECT**: `mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests` (runs specific INTEGRATION test)

**Why This Matters:**
```bash
# This looks like success but NO TESTS RAN
$ mvn test -Dtest=OutboxProducerCoreTest
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# This actually runs the test
$ mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Profile Configuration (from pom.xml):**
```xml
<!-- Default profile: CORE tests only (fast) -->
<profile>
    <id>core-tests</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <properties>
        <test.groups>core</test.groups>
        <test.excludedGroups>integration,performance,slow,flaky</test.excludedGroups>
    </properties>
</profile>

<!-- Integration tests profile: Database tests with TestContainers -->
<profile>
    <id>integration-tests</id>
    <properties>
        <test.groups>integration</test.groups>
        <test.excludedGroups>performance,slow,flaky</test.excludedGroups>
    </properties>
</profile>
```

**Development Workflow:**
```bash
# Quick feedback during development (CORE tests only, < 5 seconds)
mvn test

# Before committing (run INTEGRATION tests with TestContainers)
mvn test -Pintegration-tests

# Run specific integration test
mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests

# Clean build with full integration tests
mvn clean test -Pintegration-tests

# Get coverage report for integration tests
mvn clean test -Pintegration-tests jacoco:report
```

**Common Pitfalls:**
1. ❌ Creating INTEGRATION test, running `mvn test`, seeing "Tests run: 0", assuming test is broken
   - ✅ Solution: Use `-Pintegration-tests` for any test tagged `@Tag(TestCategories.INTEGRATION)`

2. ❌ Tagging database test as CORE to make it run by default
   - ✅ Solution: Tag it INTEGRATION and use proper profile

3. ❌ Assuming BUILD SUCCESS means tests ran
   - ✅ Solution: Check "Tests run:" count in output
- **Code Practice**:
  ```java
  // ALWAYS add diagnostic logging to verify test execution
  @Test
  void testSomething() {
      System.err.println("=== TEST METHOD STARTED ===");
      System.err.flush();

      // Your test logic here
      assertTrue(actualCondition, "Test assertion");

      System.err.println("=== TEST METHOD COMPLETED ===");
      System.err.flush();
  }

  // Use Maven debug mode to see diagnostic output
  // mvn test -Dtest=YourTest -X
  ```

### **TestContainers Debugging Strategy**
- **Issue**: TestContainers setup can fail silently, preventing test method execution
- **Solution**: Isolate TestContainers issues by temporarily disabling container setup
- **Code Practice**:
  ```java
  // Step 1: Test without TestContainers to verify JUnit execution
  //@Testcontainers  // Comment out temporarily
  public class YourTest {
      //@Container  // Comment out temporarily
      //static PostgreSQLContainer<?> postgres = ...

      @Test
      void testBasicExecution() {
          System.err.println("=== BASIC TEST EXECUTION ===");
          assertTrue(true, "This should always pass");
      }
  }

  // Step 2: Once basic execution is verified, re-enable TestContainers
  // Step 3: Add container-specific diagnostics
  @BeforeEach
  void setUp() {
      System.err.println("=== CONTAINER STATUS: " + postgres.isRunning() + " ===");
      System.err.println("=== JDBC URL: " + postgres.getJdbcUrl() + " ===");
  }
  ```

### **Maven Test Log Analysis**
- **Critical Skill**: Always use Maven debug mode (`-X`) when investigating test issues
- **Key Indicators**: Look for actual test method output, not just Maven summary
- **Warning Signs**:
  - Tests "pass" but no test-specific log output appears
  - Container startup logs appear but test method diagnostics are missing
  - Exit code 0 but expected test behavior doesn't occur



Also , here’s a **no-nonsense migration checklist** for moving from **Vert.x 4.x → 5.x**. This is the stuff you actually need to watch out for in a Maven-based Java project. You should be using this now in the PeeGeeQ project and any other projects using vert.x 5.x

---

# 1. Dependencies & Build Setup

* **Use the Vert.x 5.x BOM (`vertx-dependencies`)**

  ```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-dependencies</artifactId>
        <version>5.0.4</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  ```
* Update all Vert.x dependencies to **5.x** (don’t mix 4.x/5.x).
* **Do not add Netty manually** → Vert.x manages it.
*  Some artifacts were renamed/reorganized:

  * `vertx-rx-java2` → dropped. Use `vertx-rx-java3`.
  * Mutiny wrappers are available under `io.vertx:vertx-mutiny-*`.

---

# 2. API Changes

### Futures Replace Callbacks

* **Vert.x 4.x**:

  ```java
  server.listen(8080, ar -> {
    if (ar.succeeded()) { ... }
  });
  ```
* **Vert.x 5.x**:

  ```java
  server.listen(8080)
        .onSuccess(s -> ...)
        .onFailure(Throwable::printStackTrace);
  ```

### Composition is Cleaner

* `.compose()`, `.map()`, `.transform()`, `.eventually()` replace `future.setHandler()` spaghetti.

---

#  3. Event Bus & Cluster

*  Event Bus API is still there, but clustering now expects you to configure explicitly (e.g., Infinispan, Hazelcast).
* ️ Some clustering SPI changes → check if you had custom cluster managers.

---

#  4. Verticles

*  Still deploy the same way, but deployment returns `Future<String>` instead of requiring a callback:

  ```java
  vertx.deployVerticle(new MyVerticle())
       .onSuccess(id -> ...)
       .onFailure(Throwable::printStackTrace);
  ```

---

#  5. Reactive APIs

*  Vert.x 5 core sticks with `Future<T>`.
*  If you want richer operators, use **Mutiny wrappers** (`vertx-mutiny-*`).
*  RxJava 2 support is gone; RxJava 3 still supported but not recommended going forward.

---

#  6. Web & HTTP

*  WebSocket API is the same, but startup methods now return `Future<HttpServer>`.
*  Routing API (`Router`) is unchanged, but more utilities are `Future`-based.
*  OpenAPI & GraphQL modules aligned with Vert.x 5.
---

#  7. Database Clients

*  Reactive DB clients (`vertx-pg-client`, `vertx-mysql-client`, etc.) unchanged, but now return `Future<RowSet<Row>>` instead of callback handlers.
*  Mutiny wrappers give you `Uni`/`Multi`.

---

#  8. Metrics, Tracing, Monitoring

*  Dropwizard and Micrometer metrics are still available, but check compatibility versions.
* ⚠️ If you used **old Dropwizard module names**, update to the new Vert.x 5 artifacts.

---

# 9. Logging

*  SLF4J remains the default logging facade.
* ️ If you had hard Netty logging bindings in 4.x, remove them — Vert.x 5 aligns Netty’s logger with SLF4J automatically.

---

#  10. Breaking Changes to Watch

* No **RxJava 2** anymore.
* All async APIs now **return `Future<T>`** — callbacks still exist in some places for compatibility, but don’t use them.
* Event bus codec registration signatures changed slightly (`MessageCodec` improvements).
* Some SPI packages (cluster manager, metrics) were refactored → check custom extensions.

---

#  Migration Strategy

1. **Update dependencies** → import the 5.0.4 BOM.
2. **Search your codebase for `Handler<AsyncResult<...>>`** → refactor to `Future<T>`.
3. **Replace RxJava 2** if you used it → migrate to RxJava 3 or Mutiny.
4. **Check custom integrations** (cluster manager, metrics, Netty handlers) → adjust to new SPI signatures.
5. Run your test suite — most code compiles fine, but async composition is where you’ll hit surprises.

---

 Bottom line: the **biggest migration step** is moving from **callbacks to `Future` composition**. Everything else (web, event bus, DB) is mostly the same, just cleaner.

# Vert.x 5.x Composable Future Patterns Guide

```
    ____            ______            ____
   / __ \___  ___  / ____/__  ___    / __ \
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \  / / / /
 / ____/  __/  __/ /_/ /  __/  __/ / /_/ /
/_/    \___/\___/\____/\___/\___/  \___\_\

PostgreSQL Event-Driven Queue System
```

**Author**: Mark A Ray-Smith Cityline Ltd.
**Date**: September 2025
**Version**: Vert.x 5.0.4 Migration Complete

---

## Overview

I have now fully upgraded all PeeGeeQ modules to Vert.x 5.x and implemented the composable `Future<T>` patterns. Vert.x 5.x's native Future API offers elegant composable patterns (`.compose()`, `.onSuccess()`, `.onFailure()`, `.map()`, `.transform()`, `.eventually()`) that prioritize functional composition and developer experience. This migration transforms the entire codebase from callback-style programming to modern, composable asynchronous patterns using pure Vert.x 5.x Future APIs. **Note: `.recover()` is banned — see the Forbidden Reactive Patterns section.**

This guide demonstrates the modern Vert.x 5.x composable Future patterns that have been implemented throughout the PeeGeeQ project. These patterns provide significantly better readability, error handling, and maintainability compared to the traditional callback-style programming we've moved away from.

The migration represents a fundamental shift in how we handle asynchronous operations across all 9 PeeGeeQ modules, bringing the project in line with modern reactive programming best practices while maintaining full backward compatibility.

## Key Pattern: Composable Future Chains

### ✅ Modern Vert.x 5.x Style (RECOMMENDED)

```java
server.listen(8080)
  .compose(s -> doWarmupQuery())     // returns Future<Void>
  .compose(v -> registerWithRegistry()) // returns Future<Void>
  .onSuccess(v -> System.out.println("Server is ready"))
  .onFailure(Throwable::printStackTrace);
```

### ❌ Old Callback Style (AVOID)

```java
server.listen(8080, ar -> {
    if (ar.succeeded()) {
        doWarmupQuery(warmupResult -> {
            if (warmupResult.succeeded()) {
                registerWithRegistry(registryResult -> {
                    if (registryResult.succeeded()) {
                        System.out.println("Server is ready");
                    } else {
                        registryResult.cause().printStackTrace();
                    }
                });
            } else {
                warmupResult.cause().printStackTrace();
            }
        });
    } else {
        ar.cause().printStackTrace();
    }
});
```

## Implemented Patterns in PeeGeeQ

### 1. Server Startup with Sequential Operations

**File**: `peegeeq-service-manager/src/main/java/dev/mars/peegeeq/servicemanager/PeeGeeQServiceManager.java`

```java
// Modern composable startup
vertx.createHttpServer()
    .requestHandler(router)
    .listen(port)
    .compose(httpServer -> {
        server = httpServer;
        logger.info("PeeGeeQ Service Manager started successfully on port {}", port);
        
        // Register this service manager with Consul (optional)
        return registerSelfWithConsul()
            // CORRECT: .transform() converts the AsyncResult regardless of success/failure.
            // Use this when the operation is genuinely optional (warn on failure, continue).
            // NEVER use .recover(e -> Future.succeededFuture()) — .recover() is banned.
            .transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Failed to register with Consul (optional, continuing): {}",
                            ar.cause().getMessage());
                }
                return Future.succeededFuture();
            });
    })
    .compose(v -> {
        logger.info("Service Manager registered with Consul");
        return Future.succeededFuture();
    })
    .onSuccess(v -> startPromise.complete())
    .onFailure(cause -> {
        logger.error("Failed to start PeeGeeQ Service Manager", cause);
        startPromise.fail(cause);
    });
```

### 2. Database Operations with Error Recovery

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/ModernVertxCompositionExample.java`

```java
return client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
    .sendJsonObject(setupRequest)
    .compose(response -> {
        if (response.statusCode() == 200) {
            JsonObject result = response.bodyAsJsonObject();
            logger.info("✅ Database setup created: {}", result.getString("message"));
            return Future.<Void>succeededFuture();
        } else {
            return Future.<Void>failedFuture("Database setup failed with status: " + response.statusCode());
        }
    })
    // If database setup is optional, use .transform() to warn and continue.
    // If it is required, let the failure propagate — do not use .recover().
    .transform(ar -> {
        if (ar.failed()) {
            logger.warn("⚠️ Database setup failed, using fallback configuration: {}", ar.cause().getMessage());
            return performFallbackDatabaseSetup(client);
        }
        return Future.succeededFuture();
    });
```

### 3. Service Interactions with Health Checks

```java
return client.get(REST_PORT, "localhost", "/health")
    .send()
    .compose(healthResponse -> {
        logger.info("✅ REST API health check: {}", healthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/health").send();
    })
    .compose(serviceHealthResponse -> {
        logger.info("✅ Service Manager health check: {}", serviceHealthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances").send();
    })
    .compose(instancesResponse -> {
        if (instancesResponse.statusCode() == 200) {
            logger.info("✅ Retrieved service instances: {}", instancesResponse.bodyAsJsonArray().size());
        }
        return Future.<Void>succeededFuture();
    })
    // CORRECT: .transform() for optional terminal step — warn and continue.
    // NEVER use .recover(e -> Future.succeededFuture()) — .recover() is banned.
    .transform(ar -> {
        if (ar.failed()) {
            logger.warn("⚠️ Some service interactions failed: {}", ar.cause().getMessage());
        }
        return Future.<Void>succeededFuture();
    });
```

### 4. Test Patterns - Modern vs Old Style

**✅ Modern Style** (Implemented in test files):
```java
queue.send(message)
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> fail("Failed to send message: " + throwable.getMessage()));
```

**❌ Old Style** (Refactored away):
```java
queue.send(message)
    .onComplete(ar -> {
        if (ar.succeeded()) {
            latch.countDown();
        } else {
            fail("Failed to send message: " + ar.cause().getMessage());
        }
    });
```

### 5. Resource Cleanup with Composition

**✅ Modern Style**:
```java
// Use .eventually() so vertx.close() runs even when queue.close() fails
queue.close()
    .eventually(() -> vertx.close())
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> latch.countDown());
```

**❌ Old Style** (skips `vertx.close()` when `queue.close()` fails):
```java
queue.close()
    .compose(v -> vertx.close())
    .onComplete(v -> latch.countDown());
```

## Key Benefits of Composable Patterns

### 1. **Better Readability**
- Linear flow instead of nested callbacks
- Clear separation of success and error paths
- Self-documenting sequential operations

### 2. **Improved Error Handling**
- Centralized error handling with `.onFailure()`
- Cleanup always runs with `.eventually()` regardless of prior failure
- Error propagation through the chain (`.recover()` is banned — see Forbidden Reactive Patterns)

### 3. **Enhanced Maintainability**
- Easier to add new steps in the sequence
- Simpler to modify individual operations
- Reduced callback hell and indentation

### 4. **Better Testing**
- Each step can be tested independently
- Clearer test failure points
- Easier to mock individual operations

## Migration Checklist

### ✅ Completed Refactoring

- [x] **Server startup patterns** - Service Manager, REST Server
- [x] **Main method deployments** - All main classes
- [x] **Database setup operations** - REST API examples
- [x] **Test patterns** - Native queue tests, outbox tests
- [x] **Resource cleanup** - Test tearDown methods
- [x] **Service interactions** - Health checks, registration

### 🔍 Areas to Monitor

- **CompletableFuture bridges** - Some legacy APIs still use CompletableFuture
- **Handler<AsyncResult<T>>** patterns - Watch for any remaining callback patterns
- **Nested .onSuccess() calls** - Should be converted to .compose() chains

## Best Practices

### 1. **Use .compose() for Sequential Operations**
```java
// ✅ Good
operation1()
    .compose(result1 -> operation2(result1))
    .compose(result2 -> operation3(result2))
    .onSuccess(finalResult -> handleSuccess(finalResult))
    .onFailure(throwable -> handleError(throwable));
```

### 2. **`.recover()` Is Banned — Use `.transform()` or `.eventually()`**

`.recover()` has zero legitimate uses in this codebase. All 117 instances audited were wrong.
See the [`.recover()` — Full Analysis and Project-Wide Audit](#recover----full-analysis-rationale-and-project-wide-audit) section at the end of this document for the full audit.

| Intent | Correct pattern |
|---|---|
| Log error, do not affect chain outcome | `.onFailure(e -> logger.warn(...))` |
| Cleanup that must run regardless of prior failure | `.eventually(() -> resource.close())` |
| Optional step — warn on failure, continue chain | `.transform(ar -> { if (ar.failed()) log; return Future.succeededFuture(); })` |
| Retry / circuit-break on failure | Retry middleware or Resilience4j `CircuitBreaker` via `HealthCheckManager` |

```java
// ❌ BANNED — silences errors in shutdown cleanup
resource.doWork()
    .recover(e -> {
        logger.warn("failed: {}", e.getMessage());
        return Future.succeededFuture();   // ← ERASURE-IN-SHUTDOWN
    });

// ❌ BANNED — swallows error silently
pool.close()
    .recover(e -> Future.succeededFuture());

// ✅ CORRECT — cleanup that must always run
resource.doWork()
    .eventually(() -> resource.close());   // runs on success AND failure

// ✅ CORRECT — log and continue (optional step)
optionalOperation()
    .transform(ar -> {
        if (ar.failed()) {
            logger.warn("Optional step failed (continuing): {}", ar.cause().getMessage());
        }
        return Future.succeededFuture();
    });

// ✅ CORRECT — observe error, propagate unchanged
resource.doWork()
    .onFailure(e -> logger.warn("doWork failed: {}", e.getMessage()));
```

### 3. **Explicit Type Parameters When Needed**
```java
// ✅ Good - Explicit type when compiler can't infer
return Future.<Void>succeededFuture();
return Future.<Void>failedFuture("Error message");
```

### 4. **Use `.eventually()` for Resource Cleanup**

`.eventually()` runs the cleanup step regardless of whether the chain succeeded or failed.
`.compose()` skips steps after a failure — do NOT use it for teardown.

```java
// ✅ CORRECT — vertx.close() runs even if queue.close() failed
queue.close()
    .eventually(() -> vertx.close())
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> latch.countDown());

// ❌ WRONG — vertx.close() is skipped when queue.close() fails
queue.close()
    .compose(v -> vertx.close())
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> latch.countDown());
```

## Conclusion

The PeeGeeQ project now consistently uses modern Vert.x 5.x composable Future patterns throughout the codebase. This provides better readability, maintainability, and error handling compared to callback-style programming. The patterns demonstrated here serve as a reference for future development and can be applied to any Vert.x 5.x application.

Here are the key bullets from the PGQ coding principles:

## **Core Principles Summary**

1. **Investigate First**: Understand the problem before implementing solutions
2. **Follow Patterns**: Learn from existing code in the same project
3. **Verify Assumptions**: Don't assume tests are working - check the logs
4. **Fix Root Causes**: Address configuration issues, don't mask them
5. **Document Honestly**: Write comments that reflect actual behavior
6. **Validate Incrementally**: Test each change before moving to the next
7. **Classify Tests Clearly**: Know whether you're writing unit or integration tests
8. **Fail Honestly**: Let tests fail when there are real problems to fix
9. **Read Logs Carefully**: Error messages usually tell you exactly what's wrong
10. **Use Modern Vert.x 5.x Patterns**: Always use composable Futures (`.compose()`, `.onSuccess()`, `.onFailure()`) instead of callback-style programming

## **Critical Additional Principles**

- **Work incrementally and test after each small incremental change**
- **When testing make sure you scan the test logs properly for test errors, do not rely on the exit code**
- **Use Maven debug mode (`-X`) when investigating test issues**
- **Always verify that test methods are actually executing** - Maven can report success even when test methods never run
- **Remember dependent peegeeq modules need to be installed to the local Maven repository first**
- **Do not guess. Use the coding principles. Test after every change. Read the test log output in detail after every test run.**
- **Do not continue with the next step until the tests are passing**
- **NEVER skip failing tests - it is entirely non-professional and unacceptable** - All test failures must be fixed, never skipped with `@Disabled`, `-DskipTests`, or Maven exclusions

These principles emphasize investigation before implementation, following established patterns, incremental validation, and careful log analysis - exactly what we need for the TestContainers standardization work.



remember there are dozens of examples already of how to set up test containers.

remember there are dozens of examples already of how to use vert.x 5.x. 

Do not guess. 

Use the coding principles. 

Test after every change. 

Read the test log output in detail after every test run.

Do not continue with the next step until the tests are passing.

rememeber the dependent peegeeq modules need to be installed to the local Maven repository first. 

we are coding on a windows 11 machine

---

## `.recover()` — Full Analysis, Rationale, and Project-Wide Audit

### The anti-pattern: `.recover()` as silent error erasure

In a `Future` chain, `.recover()` is the reactive equivalent of a `catch` block. When a
`.recover()` handler returns `Future.succeededFuture()`, it converts a failed Future into
a succeeded one. Every caller upstream now sees success. The error is gone from the chain
— it has been erased.

The dangerous pattern looks like this:

```java
doSomething()
    .recover(error -> {
        logger.error("Something failed: {}", error.getMessage());
        return Future.succeededFuture();  // failure becomes success
    })
    .compose(result -> doNextThing())  // runs as if nothing went wrong
```

This is functionally identical to:

```java
try {
    doSomething();
} catch (Exception e) {
    logger.error("Something failed: {}", e.getMessage());
    // swallowed — execution continues as if nothing happened
}
doNextThing();
```

The log line creates the illusion that the error is handled. It is not handled. It is
discarded. The caller has no way to know the operation failed. The system continues in a
corrupt or inconsistent state, and any downstream failure is now disconnected from its
actual cause.

This pattern is especially destructive in reactive code because:

- **Errors are values, not stack-unwinding exceptions.** In synchronous code, a swallowed
  exception at least stops the current method from doing further damage (unless you catch
  and continue). In a `Future` chain, `.recover()` to success means the chain keeps
  composing — the next `.compose()` runs, receives a result that looks valid, and acts
  on it. There is no implicit "stop executing" behavior.

- **It defeats the entire point of `Future` error propagation.** A `Future<T>` carries
  either a result or an error. `.compose()` already short-circuits on failure — it skips
  downstream stages and propagates the error to whoever is observing the final Future.
  This is correct behavior, and it is free. `.recover()` to success actively works against
  this by forcing the chain to continue through stages that should never have run.

- **It is invisible at the call site.** The caller composes on the returned Future and
  has no indication that failures have been silently erased inside. Unlike a `throws`
  declaration or a checked exception, there is no signal in the type system that this
  Future can never fail because someone swallowed the errors internally.

- **Log lines are not error signals.** A `logger.error()` call inside `.recover()` writes
  to a file or stdout. It does not complete a Promise as failed, does not throw, does not
  set a flag, does not notify the caller. In production, nobody is tailing the log in real
  time. The error is recorded for forensic use but has zero operational effect.

### What `.recover()` is actually for (per Vert.x documentation)

The Vert.x API Javadoc defines `recover()` as:

> **Handles a failure of this Future by returning the result of another Future.**
> If the mapper fails, then the returned future will be failed with this failure.
>
> — `Future.recover(Function<Throwable, Future<T>> mapper)`

The key phrase is "**returning the result of another Future**." The mapper function
receives the exception and is expected to return a *new Future that produces a real
result of the same type `T`*. This is a recovery operation: the original operation
failed, so you try an alternative path that can still produce a meaningful value
for the caller.

`recover()` is the failure-side counterpart to `compose()`. Where `compose()` chains
on success (`successMapper` returns a new `Future<U>`), `recover()` chains on failure
(`mapper` returns a new `Future<T>`). The two-argument form of `compose()` makes this
explicit:

```java
future.compose(successMapper, failureMapper)
// recover() is sugar for:
future.compose(Future::succeededFuture, failureMapper)
```

In both cases, the mapper is expected to produce a Future that represents a real
alternative outcome — either a successful result or a propagated/re-thrown failure.

The Vert.x composition model works as follows:
- `.compose()` short-circuits on failure: when a Future fails, downstream `.compose()`
  stages are skipped, and the failure propagates automatically to the final Future.
  This is free and correct.
- `.recover()` intercepts that propagation. If the mapper returns
  `Future.succeededFuture()`, the failure is erased and downstream stages run as if
  nothing went wrong.
- `.eventually()` runs a side-effect on completion (success or failure) without
  altering the outcome — it explicitly documents that "the outcome of the future
  returned by the mapper will not influence the nature of the returned future."

### There are zero legitimate uses of `.recover()` in this project

The Vert.x API defines two narrow use cases for `recover()`:

1. **Fallback to a real alternative** — try a secondary data source, use a cached value.
2. **Selective recovery** — inspect the exception type, recover from expected failures,
   re-throw everything else.

Neither applies to any `.recover()` call in this codebase. Every instance is either
using the wrong API entirely, or implementing logic that belongs at a different
architectural layer.

#### Why the "health check" pattern is wrong

The previous version of this analysis classified 12 health check instances as
legitimate — converting a connection error into `HealthStatus.unhealthy("...")` or
`Future.succeededFuture(false)` via `recover()`.

This is wrong. The health check should let the Future fail. The health endpoint
caller should handle the failed Future and render it as an unhealthy response.
Pushing error→domain-status conversion into a `.recover()` inside the health check
implementation conflates error handling with domain mapping. Use `.transform()` if
the return type needs converting, or let the caller decide what a failure means.

#### Why the "idempotency / conflict detection" pattern is wrong

The previous analysis classified 3 instances as legitimate selective recovery:
idempotency key conflicts, database creation conflicts, and `CREATE IF NOT EXISTS`
logic.

This is wrong. Idempotency should be handled in SQL (`INSERT ... ON CONFLICT DO NOTHING`,
`CREATE DATABASE IF NOT EXISTS`) — not by throwing an exception and then catching it
with `recover()` to convert it back to success. The exception-and-recover path is a
round-trip through failure for something that is not a failure. It is a control flow
abuse.

#### Why the "retry / failover" pattern is wrong

The previous analysis classified retry logic (`PeeGeeQRestClient`) and
`ConnectionRouter` failover as legitimate.

Retry and failover are infrastructure concerns. They should be implemented in retry
middleware or a circuit breaker, not inline via `recover()` at each call site.
`recover()` makes the retry logic invisible to the caller and couples it to the
specific call chain.

#### Why the "DLQ routing" pattern is wrong

The previous analysis classified `OutboxConsumer` DLQ/retry routing as legitimate.

Message processing failure → DLQ routing is a framework responsibility. The message
processing pipeline should handle failures through its own error channel, not by
using `recover()` to convert a processing failure into a succeeded Future that
happens to have routed the message to DLQ. The caller of the processing chain
should see the failure.

#### What about cleanup during shutdown?

The previous version of this section listed "cleanup during shutdown" as a third
correct use. It is not. The correct API for cleanup-regardless-of-outcome is
`.eventually()`:

> **Invokes the given function upon completion.** The outcome of the
> future returned by the mapper will **not influence the nature of the
> returned future.**
>
> — `Future.eventually(Function<Void, Future<T>> mapper)`

`.eventually()` runs a side-effect (close a pool, cancel a timer, leave a group)
without altering whether the overall Future succeeds or fails. This is exactly
what shutdown cleanup needs. Using `.recover()` for shutdown cleanup forces the
developer to explicitly return `Future.succeededFuture()` to prevent errors from
propagating — and that is error erasure, even if the context is "just cleanup."

#### What about log-and-re-throw?

A common pattern in this codebase is:

```java
.recover(error -> {
    logger.error("Operation failed: {}", error.getMessage(), error);
    return Future.failedFuture(new RuntimeException("Wrapped: " + error.getMessage(), error));
})
```

This is not recovery either. The mapper always returns a failed Future. Nothing is
recovered. The correct approach is:

- **If you just want to log:** use `.onFailure(error -> logger.error(...))`. This
  is a terminal handler — it observes the failure without altering the chain.
- **If you need to wrap the exception type:** use `.transform()` or `.recover()`
  with the understanding that you are selectively converting one failure type to
  another. But if every error path returns `failedFuture()`, you are paying the
  semantic cost of `recover()` (which implies recovery might happen) for zero
  benefit over `.onFailure()`.

#### Summary: when to use each API

| Intent | Correct API | Wrong API |
|---|---|---|
| Log an error without affecting the chain | `.onFailure(e -> log(e))` | `.recover(e -> { log(e); return failedFuture(e); })` |
| Convert error to domain status (e.g. unhealthy) | `.transform()` or caller handles failure | `.recover(e -> succeededFuture(unhealthy(e)))` |
| Handle expected error (e.g. duplicate key) | SQL-level (`ON CONFLICT`, `IF NOT EXISTS`) | `.recover(e -> { if (duplicate) return succeededFuture(); })` |
| Retry or failover | Retry middleware / circuit breaker | `.recover(e -> { if (retryable) return retry(); })` |
| Run cleanup regardless of outcome | `.eventually(() -> resource.close())` | `.recover(e -> { cleanup(); return succeededFuture(); })` |
| Erase errors so the chain continues | **Do not do this.** Let `.compose()` short-circuit propagate the error. | `.recover(e -> { log(e); return succeededFuture(); })` |
| Return fabricated data on failure | **Do not do this.** Propagate the error. | `.recover(e -> succeededFuture(0L))` |

#### Audit classification summary

Applying these rules, **every `.recover()` in this project is the wrong API**:

| Classification | Count | Was it the correct API? |
|---|---|---|
| **ERASURE** | 17 | No. Should not use `.recover()`. Errors should propagate naturally. Remove entirely. |
| **ERASURE-IN-SHUTDOWN** | 27 | No. Should use `.eventually()` for cleanup. `.recover()` is the wrong tool. |
| **RE-WRAPS-FAILURE** | 25 | No. Should use `.onFailure()` for logging, let error propagate. Or `.transform()` if wrapping exception types. |
| **SELECTIVE-RECOVERY** | 7 | No. Idempotency and conflict detection should be handled in SQL (`ON CONFLICT`, `IF NOT EXISTS`). Retry should be infrastructure, not inline `recover()`. |
| **PROPER-FALLBACK** | 15 | No. Health checks should let the Future fail and let the caller render the failure as unhealthy. Failover belongs in retry middleware. DLQ routing belongs in the message processing framework. Use `.transform()` where the return type needs converting. |
| **TYPED-ERASURE** | 26 | No. Returns fabricated data (`0L`, empty `JsonArray()`, `null`). Error erasure with the right type signature. |
| | **Total: 117** | **0 correct. 117 wrong. 100%.** |

The previous versions of this analysis classified first 31 (27%), then 18 (15%)
instances as correct. Both were wrong. Each round of re-examination found that
instances labelled "correct" were either:

- Using `recover()` for something that has a better Vert.x API (`.eventually()`,
  `.onFailure()`, `.transform()`)
- Using `recover()` for something that belongs at a different architectural layer
  (SQL-level idempotency, retry middleware, caller-side error rendering)
- Using `recover()` to fabricate data that the caller cannot distinguish from
  real results

There are zero legitimate uses of `.recover()` in this codebase.

---

### Project-wide `.recover()` audit — systematic module-by-module review

A full audit of every `.recover()` call across the entire PeeGeeQ codebase. Every
module is listed. Within each module, every class containing `.recover()` is listed.
Within each class, every instance is listed with its line number, classification,
and description.

#### Classification key

| Classification | Meaning |
|---|---|
| **ERASURE** | Silent error swallowing in operational code. Callers see success. This is a bug. |
| **ERASURE-IN-SHUTDOWN** | Error swallowing during shutdown/cleanup. Should use `.eventually()`. |
| **RE-WRAPS-FAILURE** | Logs error, then re-throws via `Future.failedFuture()`. Should use `.onFailure()` or `.transform()`. |
| **SELECTIVE-RECOVERY** | Inspects exception type, recovers from expected errors. Should be handled in SQL or retry infrastructure. |
| **PROPER-FALLBACK** | Returns a domain-meaningful alternative (health status, failover). Should use `.transform()` or let caller handle failure. |
| **TYPED-ERASURE** | Returns fabricated data (`0`, `0L`, empty `JsonArray`, `null`, empty list) of the correct return type. Caller cannot distinguish "no data exists" from "query failed." Error erasure in a type-correct wrapper. This is a bug. |

#### Summary totals (production code only)

| Classification | Count |
|---|---|
| **ERASURE** | 17 |
| **ERASURE-IN-SHUTDOWN** | 27 |
| **RE-WRAPS-FAILURE** | 25 |
| **SELECTIVE-RECOVERY** | 7 |
| **PROPER-FALLBACK** | 15 |
| **TYPED-ERASURE** | 26 |
| **Total** | **117 — all wrong** |

---

#### Module 1: `peegeeq-api`

**Production code:** No `.recover()` calls.

**Test code:** No `.recover()` calls.

This module defines interfaces and data types only. No reactive implementation code.

---

#### Module 2: `peegeeq-bitemporal`

**Production code: 1 class, 6 instances.**

##### PgBiTemporalEventStore.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 1 | 1286 | **ERASURE-IN-SHUTDOWN** | `close()`: notification handler stop → logs warning, captures first error in `AtomicReference<Throwable>`, returns `Future.succeededFuture()`. Error is captured and re-raised at end of close chain. | Partially — captured and re-raised |
| 2 | 1298 | **ERASURE-IN-SHUTDOWN** | `close()`: reactive pool close → same `firstError` capture pattern. | Partially — captured and re-raised |
| 3 | 1310 | **ERASURE-IN-SHUTDOWN** | `close()`: pipelined client close → same `firstError` capture pattern. | Partially — captured and re-raised |
| 4 | 1816 | **RE-WRAPS-FAILURE** | Event bus operation fails → logs error, re-throws via `Future.failedFuture(error)`. | Yes |
| 5 | 2122 | **ERASURE-IN-SHUTDOWN** | `clearInstancePools()`: reactive pool close → captures first error. | Partially — captured and re-raised |
| 6 | 2133 | **ERASURE-IN-SHUTDOWN** | `clearInstancePools()`: pipelined client close → captures first error. | Partially — captured and re-raised |

**Test code: 27 instances across 19 test classes.** Most are teardown `manager.closeReactive().recover(err -> Future.succeededFuture())` in `@AfterEach` methods — the same ERASURE-IN-SHUTDOWN pattern replicated in test cleanup.

---

#### Module 3: `peegeeq-coverage-report`

**Production code:** No Java source files. POM-only aggregation module.

**Test code:** None.

---

#### Module 4: `peegeeq-db`

**Production code: 14 classes, 59 instances.** This is the worst-affected module.

##### PeeGeeQManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 7 | 278 | **RE-WRAPS-FAILURE** | `start()` fails → logs error, stops background tasks and health checks (with nested ERASURE), publishes "manager.failed" event, then re-throws via `Future.failedFuture(new RuntimeException(...))`. | Yes (eventually) |
| 8 | 285 | **ERASURE-IN-SHUTDOWN** | Inside start() failure handler: `healthCheckManager.stop().recover(e -> Future.succeededFuture())` — ignores errors during cleanup-after-startup-failure. | No — erased |
| 9 | 325 | **RE-WRAPS-FAILURE** | `stop()` fails → logs error, marks as stopped anyway, returns `Future.failedFuture(throwable)`. | Yes |
| 10 | 407 | **ERASURE-IN-SHUTDOWN** | `closeReactive()`: `awaitStart.recover(startError -> Future.succeededFuture())` — absorbs start failure so cleanup chain proceeds. | No — erased |
| 11 | 410 | **ERASURE-IN-SHUTDOWN** | `stop()` fails during close → warns, returns `Future.succeededFuture()`. | No — erased |
| 12 | 423 | **ERASURE-IN-SHUTDOWN** | Each close hook failure → warns, returns `Future.succeededFuture()` to continue chain. | No — erased |
| 13 | 437 | **ERASURE-IN-SHUTDOWN** | Worker executor close failure → warns, returns `Future.succeededFuture()`. | No — erased |
| 14 | 449 | **ERASURE-IN-SHUTDOWN** | Client factory close failure → warns, returns `Future.succeededFuture()`. | No — erased |
| 15 | 474 | **SELECTIVE-RECOVERY** | Vert.x close: if `RejectedExecutionException`, treats as expected and succeeds; other errors → warn + succeed. `RejectedExecutionException` branch should use `.eventually()` since this is shutdown. | Partially |
| 16 | 490 | **SELECTIVE-RECOVERY** | Final catch-all: `RejectedExecutionException` → succeed; other errors → re-throw via `Future.failedFuture(e)`. | Yes (for non-RejectedExecution) |
| 17 | 738 | **RE-WRAPS-FAILURE** | `validateDatabaseConnectivity()` fails → logs, re-throws as `Future.failedFuture(new RuntimeException("Database startup validation failed", throwable))`. | Yes |
| 18 | 935 | **ERASURE-IN-SHUTDOWN** | `deadConsumerDetectionJob.stop().recover(e -> Future.succeededFuture())` inside `stopBackgroundTasks()`. | No — erased |

##### PeeGeeQMetrics.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 19 | 672 | **ERASURE** | `executeReactiveCountQuery()` fails → logs warning, returns `Future.succeededFuture(0.0)`. Metric query failure silently returns zero. | No — erased |
| 20 | 705 | **ERASURE** | `persistMetrics()` fails → logs error (or debug for connection errors), returns `Future.succeededFuture()`. Metric persistence failure silently swallowed. | No — erased |
| 21 | 745 | **ERASURE** | `isHealthy()` fails → logs warning, returns `Future.succeededFuture(false)`. Health check failure returns false instead of propagating. | No — erased (but returns meaningful "unhealthy" signal) |

##### HealthCheckManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 22 | 235 | **ERASURE-IN-SHUTDOWN** | `stop()`: awaits in-flight check cycle, `.recover(e -> Future.succeededFuture())`. | No — erased (shutdown) |
| 23 | 252 | **RE-WRAPS-FAILURE** | `validateConnectionPool()` fails → logs error, re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |
| 24 | 352 | **ERASURE-IN-SHUTDOWN** | `inFlightCheckCycle.recover(e -> Future.succeededFuture())` — tracks in-flight cycle, erases errors for stop() await. | No — erased |
| 25 | 640 | **PROPER-FALLBACK** | `DatabaseHealthCheck.checkReactive()` outer recover → returns `HealthStatus.unhealthy(...)`. Should use `.transform()` or let caller handle failure. | No — wrong layer |
| 26 | 655 | **PROPER-FALLBACK** | `DatabaseHealthCheck.checkDatabase()` inner recover → returns unhealthy status with error message. Should use `.transform()`. | No — wrong layer |
| 27 | 666 | **PROPER-FALLBACK** | `OutboxQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 28 | 689 | **PROPER-FALLBACK** | `OutboxQueueHealthCheck.checkOutboxQueue()` inner recover — detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |
| 29 | 705 | **PROPER-FALLBACK** | `NativeQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 30 | 724 | **PROPER-FALLBACK** | `NativeQueueHealthCheck.checkNativeQueue()` inner recover — detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |
| 31 | 740 | **PROPER-FALLBACK** | `DeadLetterQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 32 | 764 | **PROPER-FALLBACK** | `DeadLetterQueueHealthCheck.checkDeadLetterQueue()` inner recover — detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |

##### DeadLetterQueueManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 33 | 143 | **RE-WRAPS-FAILURE** | `moveToDeadLetterQueue()` fails → logs error, re-throws via `Future.failedFuture(throwable)`. | Yes |
| 34 | 182 | **RE-WRAPS-FAILURE** | `fetchStatistics()` → logs error, re-throws. | Yes |
| 35 | 210 | **RE-WRAPS-FAILURE** | `fetchDeadLetterMessagesByTopic()` → logs error, re-throws. | Yes |
| 36 | 237 | **RE-WRAPS-FAILURE** | `fetchAllDeadLetterMessages()` → logs error, re-throws. | Yes |
| 37 | 262 | **RE-WRAPS-FAILURE** | `fetchDeadLetterMessage()` → logs error, re-throws. | Yes |
| 38 | 322 | **RE-WRAPS-FAILURE** | `reprocessDeadLetterMessageRecord()` → logs error, re-throws. | Yes |
| 39 | 344 | **RE-WRAPS-FAILURE** | `removeDeadLetterMessage()` → logs error, re-throws. | Yes |
| 40 | 366 | **RE-WRAPS-FAILURE** | `purgeOldDeadLetterMessages()` → logs error, re-throws. | Yes |

##### PgConnectionManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 41 | 368 | **PROPER-FALLBACK** | `checkHealth()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()` or let caller handle failure. | No — wrong layer |
| 42 | 477 | **ERASURE-IN-SHUTDOWN** | `close()` all pools — if some fail, logs error and returns `Future.succeededFuture()`. Close never propagates failure. | No — erased |

##### PgConnectionProvider.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 43 | 152 | **PROPER-FALLBACK** | `isHealthy()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()`. | No — wrong layer |

##### StuckMessageRecoveryManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 44 | 104 | **ERASURE** | `recoverStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. Recovery failure silently swallowed; callers think zero messages were recovered. | No — erased |
| 45 | 132 | **ERASURE** | `countStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |
| 46 | 164 | **ERASURE** | `resetStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |
| 47 | 202 | **ERASURE** | `logRecoveredMessages()` fails → logs warning, returns `Future.succeededFuture()`. Benign: this is just logging, not business logic. | No — erased (low risk) |
| 48 | 221 | **ERASURE** | `getRecoveryStats()` fails → logs warning, returns `Future.succeededFuture(new RecoveryStats(0, 0, true))`. | No — erased |
| 49 | 241 | **ERASURE** | `countTotalProcessingMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |

##### SubscriptionManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 50 | 189 | **ERASURE** | Auto-backfill after subscribe with FROM_BEGINNING fails → logs warning, returns `Future.succeededFuture()`. Subscription was created but backfill silently failed. | No — erased |
| 51 | 411 | **ERASURE** | Cancel cleanup after subscription cancellation fails → logs warning, returns `Future.succeededFuture()`. Cancel succeeded but cleanup was lost. | No — erased |
| 52 | 587 | **ERASURE** | Resurrection re-backfill fails → logs warning, returns `Future.succeededFuture()`. Heartbeat succeeded but re-backfill silently failed. | No — erased |

##### SqlTemplateProcessor.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 53 | 65 | **RE-WRAPS-FAILURE** | SQL file execution fails → logs error, re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |

##### PeeGeeQDatabaseSetupService.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 54 | 195 | **RE-WRAPS-FAILURE** | Database creation step fails → logs error, re-throws as `Future.failedFuture(new RuntimeException("Database creation failed", ex))`. | Yes |
| 55 | 252 | **SELECTIVE-RECOVERY** | `createCompleteSetup()` outer recover → checks if conflict error → wraps as `DatabaseCreationConflictException`; otherwise cleans up and re-throws. Should use SQL-level `IF NOT EXISTS` / `ON CONFLICT`. | Partially |
| 56 | 266 | **ERASURE-IN-SHUTDOWN** | Cleanup after setup failure → logs error, returns `Future.succeededFuture()`. Cleanup failure during error recovery is swallowed. | No — erased |
| 57 | 566 | **ERASURE-IN-SHUTDOWN** | `destroySetup()` → `manager.closeReactive().recover(error -> Future.succeededFuture())` — manager close failure during teardown is swallowed. | No — erased |
| 58 | 774 | **RE-WRAPS-FAILURE** | `addEventStore()` fails → re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |
| 59 | 1111 | **ERASURE-IN-SHUTDOWN** | `close()` → each setup destroy failure → warn, returns `Future.succeededFuture()`. | No — erased |

##### DatabaseTemplateManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 60 | 90 | **SELECTIVE-RECOVERY** | Database creation: if conflict error (`already exists`), succeeds (idempotent); otherwise re-throws. Should use SQL-level `IF NOT EXISTS`. | Yes (for non-conflict errors) |

##### DeadConsumerGroupCleanup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 61 | 217 | **TYPED-ERASURE** | `cleanupAllDeadGroups()` — individual group cleanup fails → logs error, adds zero-result to list, continues batch. Caller sees fabricated zero-result per failed group. | No — fabricated |

##### DeadConsumerDetectionJob.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 62 | 199 | **ERASURE-IN-SHUTDOWN** | `stop()` → awaits in-flight detection `.recover(e -> Future.succeededFuture())`. | No — erased (shutdown) |
| 63 | 363 | **ERASURE-IN-SHUTDOWN** | `inFlightDetection` tracking: `.recover(e -> Future.succeededFuture())` for fire-and-forget reference. | No — erased |

##### MultiConfigurationManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 64 | 172 | **RE-WRAPS-FAILURE** | `start()` fails → sets state to STOPPED, logs error, re-throws via `Future.failedFuture(e)`. | Yes |
| 65 | 333 | **ERASURE-IN-SHUTDOWN** | `close()` → some configs fail to close → warns, returns `Future.succeededFuture()`. | No — erased |

**Test code: 30+ instances across 15 test classes.** Predominantly teardown cleanup and test-specific error handling.

---

#### Module 5: `peegeeq-examples`

**Production code:** No `.recover()` calls.

**Test code:** No `.recover()` calls.

---

#### Module 6: `peegeeq-examples-spring`

**Production code:** No `.recover()` calls. One comment in `ReactiveBiTemporalAdapter.java` (line 141) mentions `.recover()` in documentation but does not call it.

**Test code:** No `.recover()` calls.

---

#### Module 7: `peegeeq-integration-tests`

**Production code:** No Java source files.

**Test code:** No `.recover()` calls.

---

#### Module 8: `peegeeq-management-ui`

**Production code:** No Java source files. Frontend module.

**Test code:** None.

---

#### Module 9: `peegeeq-migrations`

**Production code:** No `.recover()` calls. SQL migration files only.

**Test code:** No `.recover()` calls.

---

#### Module 10: `peegeeq-native`

**Production code: 4 classes, 9 instances.**

##### PgNativeQueueProducer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 66 | 312 | **SELECTIVE-RECOVERY** | Checks if the error is a duplicate idempotency key violation (`idx_queue_messages_idempotency_key`). If so, returns success (message already exists). All other errors re-thrown. Should use `INSERT ... ON CONFLICT DO NOTHING`. | Yes (non-idempotency errors propagate) |

##### PgNativeQueueConsumer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 67 | 280 | **ERASURE-IN-SHUTDOWN** | During `connection.close()` after UNLISTEN during shutdown: `.recover(ignore -> Future.succeededFuture())`. Ignores connection close errors. | No — erased (narrow scope, single resource) |

##### PgNativeConsumerGroup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 68 | 246 | **ERASURE-IN-SHUTDOWN** | During startup abort: `partitionedEngine.stop().recover(stopErr -> Future.succeededFuture())` — if stop fails during startup abort, error is swallowed so the "closed during startup" failure can propagate. | No — erased (cleanup during abort) |
| 69 | 256 | **RE-WRAPS-FAILURE** | Logs error and resets state from STARTING to NEW, then re-throws via `Future.failedFuture(err)`. | Yes |
| 70 | 276 | **PROPER-FALLBACK** | `isOffsetWatermarkTopic()` fails → falls back to reference counting mode with `Future.succeededFuture(false)`. Should use `.transform()` or let caller handle. | No — wrong layer |
| 71 | 371 | **RE-WRAPS-FAILURE** | `subscriptionService.subscribe()` fails → resets state to NEW and re-throws via `Future.failedFuture(err)`. | Yes |
| 72 | 405 | **ERASURE** | `stopGracefully()` → cancel subscription fails → logs warning and returns `Future.succeededFuture()`, then continues to `stopInternal()`. Subscription cancellation error is silently erased. | No — erased |

##### PartitionedConsumerEngine.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 73 | 163 | **RE-WRAPS-FAILURE** | `resetAfterFailedStart(err)` — sets running=false, tears down, and re-throws the original error via `Future.failedFuture(err)`. | Yes |
| 74 | 200 | **ERASURE-IN-SHUTDOWN** | `assignmentService.leaveGroup()` fails during teardown → logs warning, returns `Future.succeededFuture()`. | No — erased (shutdown cleanup) |

**Test code: 5 instances across 3 test classes.** Cleanup and test assertions about `.recover()` behavior.

---

#### Module 11: `peegeeq-openapi`

**Production code:** No `.recover()` calls. OpenAPI specification module.

**Test code:** No `.recover()` calls.

---

#### Module 12: `peegeeq-outbox`

**Production code: 3 classes, 9 instances.**

##### OutboxFactory.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 75 | 325 | **PROPER-FALLBACK** | `isHealthyAsync()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()`. | No — wrong layer |
| 76 | 462 | **TYPED-ERASURE** | `getStatsAsync()` fails → returns fallback basic stats. Caller sees fabricated numbers. | No — fabricated |
| 77 | 555 | **ERASURE-IN-SHUTDOWN** | `closeTrackedResourcesAsync()`: consumer close fails → logs warning, returns `Future.succeededFuture()`. | No — erased |
| 78 | 559 | **ERASURE-IN-SHUTDOWN** | `closeTrackedResourcesAsync()`: consumer group close fails → same pattern. | No — erased |

##### OutboxConsumerGroup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 79 | 389 | **RE-WRAPS-FAILURE** | `startWithSubscription()`: subscription fails → resets state to NEW, re-throws. | Yes |
| 80 | 448 | **ERASURE** | `stopGracefully()`: cancel subscription fails → logs warning, returns `Future.succeededFuture()`, continues to `stopInternal()`. | No — erased |

##### OutboxConsumer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 81 | 436 | **PROPER-FALLBACK** | Message processing fails → calls `markMessageFailed()` to update status in database. DLQ routing should be a framework responsibility, not inline `recover()`. | No — wrong layer |
| 82 | 510 | **SELECTIVE-RECOVERY** | Post-completion recover: `RejectedMessageException` → resets to PENDING; `MessageFilteredException` → resets to PENDING; retry logic for other failures → either retry or DLQ. Retry/DLQ routing should be framework infrastructure. | Varies — wrong layer |
| 83 | 871 | **ERASURE-IN-SHUTDOWN** | `closeAsync()`: waits for in-flight processing, `.recover(e -> Future.succeededFuture())` to proceed to pool close regardless. | No — erased (shutdown) |

**Test code: 8 instances across 3 test classes.** Example tests and retry resilience tests.

---

#### Module 13: `peegeeq-performance-test-harness`

**Production code: 1 class, 2 instances.**

##### PerformanceTestHarness.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 84 | 86 | **ERASURE** | Logs error on test suite failure, then adds failure to results and returns `Future.succeededFuture()`. The overall harness chain continues silently. | No — erased |
| 85 | 103 | **ERASURE** | Top-level harness execution error: logs, adds failure to aggregated results, returns `Future.succeededFuture(aggregatedResults)`. Error is swallowed. | No — erased |

**Test code:** No `.recover()` calls.

---

#### Module 14: `peegeeq-rest`

**Production code: 4 classes, 22 instances.**

##### SystemMonitoringHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 86 | 409 | **TYPED-ERASURE** | Metrics collection fails → returns cached/minimal runtime metrics as fallback. Caller sees stale data with no indication it is stale. | No — fabricated |
| 87 | 507 | **TYPED-ERASURE** | `collectMetricsFromServices()` fails → returns minimal metrics with error message embedded in JSON. Error is in the data, not in the Future. | No — fabricated |
| 88 | 567 | **TYPED-ERASURE** | `collectSetupMetrics()` fails → returns accumulator unchanged (skips this setup). Aggregate metrics are silently incomplete. | No — fabricated |
| 89 | 623 | **TYPED-ERASURE** | `collectTopicSubscriptionMetrics()` fails → returns accumulator unchanged. Aggregate silently incomplete. | No — fabricated |

##### QueueHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 90 | 239 | **TYPED-ERASURE** | Batch message send: individual message fails → if `failOnError=false`, returns "FAILED:" marker string. Caller sees a string that looks like a result. | No — fabricated marker |

##### ManagementApiHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 91 | 158 | **RE-WRAPS-FAILURE** | `getRealQueues()` fails → re-throws as `RuntimeException`. | Yes |
| 92 | 209 | **TYPED-ERASURE** | `getQueuesForSetup()` fails → returns empty `JsonArray()`. Caller sees "no queues" instead of "error loading queues." | No — fabricated |
| 93 | 427 | **TYPED-ERASURE** | `getConsumerGroupsForSetup()` topic subscription list fails → returns empty `JsonArray()`. | No — fabricated |
| 94 | 446 | **TYPED-ERASURE** | Same setup-level recover for consumer groups. | No — fabricated |
| 95 | 465 | **RE-WRAPS-FAILURE** | `getRealConsumerGroups()` fails → re-throws. | Yes |
| 96 | 589 | **TYPED-ERASURE** | `getRealEventCount()` fails → returns `0L`. Caller displays "0 events" — fabricated. | No — fabricated |
| 97 | 627 | **TYPED-ERASURE** | `getRealAggregateCount()` fails → returns `0L`. Caller displays "0 aggregates" — fabricated. | No — fabricated |
| 98 | 643 | **TYPED-ERASURE** | `getRealCorrectionCount()` fails → returns `0L`. Caller displays "0 corrections" — fabricated. | No — fabricated |
| 99 | 750 | **RE-WRAPS-FAILURE** | `getRealEventStores()` fails → re-throws. | Yes |
| 100 | 795 | **TYPED-ERASURE** | `getEventStoresForSetup()` fails → returns empty `JsonArray()`. Caller sees "no event stores" — fabricated. | No — fabricated |
| 101 | 813 | **TYPED-ERASURE** | `getRealMessages()` fails → returns empty `JsonArray()`. Caller sees "no messages" — fabricated. | No — fabricated |
| 102 | 856 | **TYPED-ERASURE** | `getRecentActivity()` fails → returns empty `JsonArray()`. Caller sees "no activity" — fabricated. | No — fabricated |
| 103 | 900 | **TYPED-ERASURE** | `getRecentActivityForSetup()` → store query fails → returns empty list. | No — fabricated |
| 104 | 951 | **TYPED-ERASURE** | `getRecentActivityForSetup()` → setup not found → returns empty list. | No — fabricated |
| 105 | 1618 | **TYPED-ERASURE** | `getRealConsumerCount()` fails → returns `0`. Caller displays "0 consumers" — fabricated. | No — fabricated |
| 106 | 1733 | **TYPED-ERASURE** | Subscription listing for queue details fails → returns empty list. Caller sees "no subscriptions" — fabricated. | No — fabricated |

##### ConsumerGroupHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 107 | 912 | **TYPED-ERASURE** | Subscription lookup for consumer group options fails → returns `null` (caller uses defaults). Caller cannot distinguish "no subscription" from "lookup failed." | No — fabricated |

**Test code: 5 instances across 2 test classes.** SSE streaming test and example tests.

---

#### Module 15: `peegeeq-rest-client`

**Production code: 1 class, 2 instances.**

##### PeeGeeQRestClient.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 108 | 792 | **RE-WRAPS-FAILURE** | `.recover(this::handleNetworkError)` — converts network errors into `PeeGeeQNetworkException` and re-throws via `Future.failedFuture()`. | Yes |
| 109 | 798 | **SELECTIVE-RECOVERY** | Retry logic: if the error is retryable and within retry limit, schedules a retry; otherwise re-throws the original error. Retry should be middleware, not inline `recover()`. | Yes (non-retryable errors propagate) |

**Test code:** No `.recover()` calls.

---

#### Module 16: `peegeeq-runtime`

**Production code:** No `.recover()` calls.

**Test code:** No `.recover()` calls.

---

#### Module 17: `peegeeq-service-manager`

**Production code: 4 classes, 8 instances.**

##### ConnectionRouter.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 110 | 105 | **PROPER-FALLBACK** | Retry with failover: request fails → if retries remain, selects another instance and retries; if max retries exceeded, propagates failure. Failover belongs in retry middleware. | Yes (at retry exhaustion) |

##### PeeGeeQServiceManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 111 | 93 | **ERASURE** | Consul registration fails → logs warning, returns `Future.succeededFuture()`. Service starts even if Consul registration fails. | No — erased (arguably acceptable: Consul is optional) |

##### HealthMonitor.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 112 | 165 | **PROPER-FALLBACK** | Health check fails → increments failure counter, marks as UNHEALTHY/UNKNOWN, returns `HealthCheckResult` with error info. Should use `.transform()`. | No — wrong layer |

##### FederatedManagementHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 113 | 388 | **TYPED-ERASURE** | `fetchInstanceOverview()` fails → returns error response JSON as succeeded Future. HTTP 200 with error payload instead of proper error status. | No — fabricated |
| 114 | 397 | **TYPED-ERASURE** | `fetchInstanceQueues()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 115 | 406 | **TYPED-ERASURE** | `fetchInstanceConsumerGroups()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 116 | 415 | **TYPED-ERASURE** | `fetchInstanceEventStores()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 117 | 424 | **TYPED-ERASURE** | `fetchInstanceMetrics()` fails → returns error response JSON as succeeded Future. | No — fabricated |

**Test code:** No `.recover()` calls.

---

#### Module 18: `peegeeq-test-support`

**Production code:** No `.recover()` calls.

**Test code:** No `.recover()` calls.

---

#### Per-module summary

| Module | Production instances | ERASURE | ERASURE-IN-SHUTDOWN | RE-WRAPS | SELECTIVE | PROPER-FALLBACK | TYPED-ERASURE |
|---|---|---|---|---|---|---|---|
| `peegeeq-api` | 0 | — | — | — | — | — | — |
| `peegeeq-bitemporal` | 6 | 0 | 5 | 1 | 0 | 0 | 0 |
| `peegeeq-coverage-report` | 0 | — | — | — | — | — | — |
| `peegeeq-db` | 59 | 12 | 16 | 16 | 4 | 10 | 1 |
| `peegeeq-examples` | 0 | — | — | — | — | — | — |
| `peegeeq-examples-spring` | 0 | — | — | — | — | — | — |
| `peegeeq-integration-tests` | 0 | — | — | — | — | — | — |
| `peegeeq-management-ui` | 0 | — | — | — | — | — | — |
| `peegeeq-migrations` | 0 | — | — | — | — | — | — |
| `peegeeq-native` | 9 | 1 | 3 | 3 | 1 | 1 | 0 |
| `peegeeq-openapi` | 0 | — | — | — | — | — | — |
| `peegeeq-outbox` | 9 | 1 | 3 | 1 | 1 | 2 | 1 |
| `peegeeq-performance-test-harness` | 2 | 2 | 0 | 0 | 0 | 0 | 0 |
| `peegeeq-rest` | 22 | 0 | 0 | 3 | 0 | 0 | 19 |
| `peegeeq-rest-client` | 2 | 0 | 0 | 1 | 1 | 0 | 0 |
| `peegeeq-runtime` | 0 | — | — | — | — | — | — |
| `peegeeq-service-manager` | 8 | 1 | 0 | 0 | 0 | 2 | 5 |
| `peegeeq-test-support` | 0 | — | — | — | — | — | — |
| **Totals** | **117** | **17** | **27** | **25** | **7** | **15** | **26** |

**100% of `.recover()` uses in this codebase are wrong.** Every instance is either
using the wrong Vert.x API or implementing logic that belongs at a different
architectural layer. There are 0 legitimate uses out of 117 production instances.

---

#### Critical ERASURE findings (non-shutdown operational code)

These are the most dangerous — errors silently swallowed in code paths where callers
depend on the Future's outcome:

| # | Module | Class | Line | Impact |
|---|---|---|---|---|
| 19 | peegeeq-db | PeeGeeQMetrics | 672 | Metric count query failures return zero silently |
| 20 | peegeeq-db | PeeGeeQMetrics | 705 | Metric persistence failures swallowed |
| 44 | peegeeq-db | StuckMessageRecoveryManager | 104 | **Stuck message recovery failures return zero** — caller thinks nothing was stuck |
| 45 | peegeeq-db | StuckMessageRecoveryManager | 132 | Count query failure hidden |
| 46 | peegeeq-db | StuckMessageRecoveryManager | 164 | Reset failure hidden — stuck messages stay stuck forever |
| 48 | peegeeq-db | StuckMessageRecoveryManager | 221 | Recovery stats fabricated |
| 49 | peegeeq-db | StuckMessageRecoveryManager | 241 | Count fabricated |
| 50 | peegeeq-db | SubscriptionManager | 189 | **FROM_BEGINNING backfill silently fails** — subscription appears complete but data is missing |
| 51 | peegeeq-db | SubscriptionManager | 411 | **Cancel cleanup silently fails** — orphan rows and zombie tracking data remain |
| 52 | peegeeq-db | SubscriptionManager | 587 | **Resurrection re-backfill silently fails** — resurrected consumer misses messages |
| 72 | peegeeq-native | PgNativeConsumerGroup | 405 | Subscription cancellation failure during graceful stop lost |
| 80 | peegeeq-outbox | OutboxConsumerGroup | 448 | Subscription cancellation failure during graceful stop lost |
| 84 | peegeeq-performance-test-harness | PerformanceTestHarness | 86 | Test suite failures hidden in aggregated results |
| 85 | peegeeq-performance-test-harness | PerformanceTestHarness | 103 | Top-level execution failure swallowed |
| 111 | peegeeq-service-manager | PeeGeeQServiceManager | 93 | Consul registration failure hidden (lower risk — Consul is optional) |

---

#### TYPED-ERASURE findings (fabricated data disguised as real results)

These are instances returning fabricated data where the caller receives a succeeded Future
with a type-correct result and has no way to know the data is fake.

##### ManagementApiHandler — 13 instances (peegeeq-rest)

The management UI dashboard calls these endpoints. Every failure returns empty
JSON or zero counts. The dashboard shows "0 events", "0 queues", "no messages",
"no activity" — indistinguishable from a system with no data. An operator looking
at this dashboard during an outage sees a clean, empty system instead of errors.

| # | Line | Returns | What the caller sees |
|---|---|---|---|
| 92 | 209 | `new JsonArray()` | "No queues" |
| 93 | 427 | `new JsonArray()` | "No topic subscriptions" |
| 94 | 446 | `new JsonArray()` | "No consumer groups" |
| 96 | 589 | `0L` | "0 events" |
| 97 | 627 | `0L` | "0 aggregates" |
| 98 | 643 | `0L` | "0 corrections" |
| 100 | 795 | `new JsonArray()` | "No event stores" |
| 101 | 813 | `new JsonArray()` | "No messages" |
| 102 | 856 | `new JsonArray()` | "No recent activity" |
| 103 | 900 | `empty list` | "No activity for this setup" |
| 104 | 951 | `empty list` | "Setup not found" (actually: query failed) |
| 105 | 1618 | `0` | "0 consumers" |
| 106 | 1733 | `empty list` | "No subscriptions" |

##### SystemMonitoringHandler — 4 instances (peegeeq-rest)

| # | Line | Returns | What the caller sees |
|---|---|---|---|
| 86 | 409 | cached/minimal metrics | Stale metrics with no staleness indicator |
| 87 | 507 | minimal metrics + error msg in JSON | Error buried inside JSON data, not in HTTP status |
| 88 | 567 | accumulator unchanged | Aggregate metrics missing a setup — silently |
| 89 | 623 | accumulator unchanged | Aggregate metrics missing subscriptions — silently |

##### FederatedManagementHandler — 5 instances (peegeeq-service-manager)

HTTP 200 with error JSON payload instead of proper HTTP error status code.

| # | Line | Returns |
|---|---|---|
| 113 | 388 | Error response JSON |
| 114 | 397 | Error response JSON |
| 115 | 406 | Error response JSON |
| 116 | 415 | Error response JSON |
| 117 | 424 | Error response JSON |

---

### Appendix: Vert.x 5.x failure-handling cheat sheet

#### The rule

**Default to propagation, not recovery.**

A Vert.x `Future` already propagates failure unless you intercept it. `compose(...)` only runs on success; if the upstream future fails, the failure is propagated. `recover(...)` handles failure by switching to another `Future`. `onFailure(...)`, `onSuccess(...)`, and `onComplete(...)` are terminal operations.

---

#### 1. Normal async pipeline

```java
return step1()
  .compose(this::step2)
  .compose(this::step3);
```

Use this for the normal path.

* `step2` runs only if `step1` succeeded.
* `step3` runs only if `step2` succeeded.
* Any failure automatically propagates downstream.

---

#### 2. Log the error without changing the outcome

```java
return step1()
  .compose(this::step2)
  .onFailure(err -> log.error("Pipeline failed", err));
```

Use `onFailure(...)` for logging, metrics, tracing, alerts, and similar side effects.

Do **not** use it as business control flow. It is terminal.

**Meaning:** log it, then let the failure stay failed.

---

#### 3. Real recovery with a true fallback

```java
return primaryLookup(id)
  .recover(err -> secondaryLookup(id));
```

This is the **only** legitimate use of `recover()` — an alternate path that produces a real result. Secondary lookup must actually be able to return a valid result, not fake data.

---

#### 4. Selective recovery (inspect the error first)

```java
return doOperation()
  .recover(err -> {
    if (err instanceof NotFoundException) {
      return Future.succeededFuture(defaultValue);
    }
    return Future.failedFuture(err);
  });
```

Only recover from the specific known failure. Re-throw everything else.
**Prefer SQL-level solutions** (`ON CONFLICT`, `IF NOT EXISTS`) over exception-and-recover patterns.

---

#### 5. Cleanup that must run regardless of outcome

```java
return doWork()
  .eventually(() -> resource.close());
```

`eventually` = finally. The mapper runs on success **and** on failure. The outcome of `resource.close()` does not affect the outer Future's outcome.

---

#### 6. Transform the AsyncResult (success or failure) without altering propagation direction

```java
return doWork()
  .transform(ar -> {
    if (ar.failed()) {
      log.warn("doWork failed (optional step, continuing): {}", ar.cause().getMessage());
    }
    return Future.succeededFuture();
  });
```

Use `.transform()` when the step is genuinely optional and you want to continue the chain regardless. This is the correct replacement for most `recover(e -> succeededFuture())` patterns.

---

#### 7. Explicit failure

```java
return validate(request)
  .compose(valid -> {
    if (!valid) {
      return Future.failedFuture(new IllegalArgumentException("Invalid request"));
    }
    return process(request);
  });
```

For expected business failures, fail explicitly rather than returning fake defaults.

---

#### 8. HTTP handlers: fail the routing context

```java
router.get("/users/:id").handler(ctx -> {
  loadUser(ctx.pathParam("id"))
    .onSuccess(user -> ctx.json(user))
    .onFailure(ctx::fail);
});

router.route().failureHandler(ctx -> {
  Throwable err = ctx.failure();
  int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
  log.error("Request failed", err);
  ctx.response().setStatusCode(status).end("Request failed");
});
```

In Vert.x Web: async operation fails → call `ctx.fail(err)` → centralize HTTP error mapping in failure handlers. Do **not** scatter ad hoc `setStatusCode(...).end(...)` error handling all over every route.

---

#### 9. Composite futures

##### Fail fast when any dependency fails

```java
return Future.all(f1, f2, f3);
```

`all(...)` succeeds when all succeed and fails as soon as one fails.

##### Wait for all to finish before overall failure/success

```java
return Future.join(f1, f2, f3);
```

`join(...)` waits for all futures to complete and does not fail immediately when one fails.

##### Succeed when any one succeeds

```java
return Future.any(f1, f2, f3);
```

`any(...)` succeeds as soon as one succeeds and fails only when all fail.

Use:
* `all` for strict dependency sets
* `join` when you need all outcomes collected before deciding
* `any` for race / first-success patterns

---

#### 10. Blocking code

```java
return vertx.executeBlocking(() -> {
  return legacyBlockingCall();
});
```

Use `executeBlocking` for blocking calls. Let thrown exceptions fail the future. Handle the failure in the normal async chain.

---

#### 11. The blunt rules

1. **Use `compose(...)` for the happy path.**
2. **Let failures propagate by default.**
3. **Use `onFailure(...)` for side effects only.**
4. **Use `recover(...)` only for a real alternate path that produces genuine data.**
5. **Use `eventually(...)` for cleanup.**
6. **In Vert.x Web, convert async failure to `ctx.fail(...)`.**
7. **Centralize HTTP failure mapping in failure handlers.**
8. **Use `executeBlocking(...)` for blocking code, not the event loop.**
9. **Do not fake success when the system has actually failed.**

---

#### 12. Default production pattern

```java
return doSomething(request)
  .compose(this::enrich)
  .compose(this::persist)
  .onFailure(err -> log.error("Operation failed for request {}", request.id(), err));
```

Then only add one of these when truly needed:

* `recover(...)` for a real fallback to a genuine alternative result
* `eventually(...)` for cleanup
* `transform(...)` for full success/failure remapping
* `ctx.fail(...)` at the HTTP boundary

---

## **Multi-Tenant Schema Isolation: Implementation Guidance**

### **Principle: "Tenant Separation is Absolute"**
**PeeGeeQ is moving to a "Single Isolated Schema per tenant" model. Shared infrastructure tables are a design smell.**

- ✅ **MANDATORY**: All tables and triggers for a given tenant must reside within that tenant's configured schema.
- ❌ **FORBIDDEN**: Hardcoding global schema names like `peegeeq` or `bitemporal` in SQL or Java code.
- ❌ **FORBIDDEN**: Assuming that global infrastructure schemas are available or reachable via `search_path`.

### **Principle: "Parametrize, Don't Hardcode"**
**All SQL templates and queries must be fully parameterizable.**

- ✅ **MANDATORY**: All SQL templates must use the `{schema}` placeholder for both table qualification and schema creation.
- ✅ **MANDATORY**: Java service methods must construct fully qualified table names using the tenant's configuration.
- ❌ **FORBIDDEN**: Unqualified table names in utility queries (e.g., `SELECT * FROM outbox`). Use `{schema}.outbox` or ensure `search_path` is explicitly set per session.

### **Principle: "Silent Neighbors (Avoid Notification Noise)"**
**Notifications must be schema-qualified to prevent cross-tenant "thundering herd" issues.**

- ✅ **MANDATORY**: `LISTEN` and `NOTIFY` channel names must be prefixed with the tenant schema (e.g., `{schema}_{topic}_changes`).
- ✅ **MANDATORY**: `ReactiveNotificationHandler` must be schema-aware and construct channel names dynamically.
- ❌ **FORBIDDEN**: Global notification channels (e.g., `bitemporal_events`) that broadcast to all tenants in a shared database.

### **Principle: "Standardized Configuration"**
**Only one configuration key should rule the schema resolution.**

- ✅ **MANDATORY**: Use `peegeeq.database.schema` as the single source of truth.
- ❌ **FORBIDDEN**: Implementing module-specific defaults (like the `peegeeq` vs `public` discrepancy found in REST handler).
- ✅ **MANDATORY**: The `PeeGeeQRuntime` must validate that the schema is consistently applied across all enabled features (Native, Outbox, Bi-Temporal).

### **Verification of Isolation**
**Every PR touching schema logic must include a multi-tenant isolation test.**
- Tests must use `TestContainers` to spin up a real PostgreSQL instance.
- Tests must create two distinct schemas (e.g., `tenant_a`, `tenant_b`).
- Validation must prove that `tenant_a` cannot `LISTEN` to `tenant_b`'s events or `SELECT` from its tables.
