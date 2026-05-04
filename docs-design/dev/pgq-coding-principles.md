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
See [`docs-design/code reviews/vertx-recover-usage.md`](../code%20reviews/vertx-recover-usage.md) for the full audit.

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
