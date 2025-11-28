# PeeGeeQ BiTemporal Module - Test Coverage Improvement Plan

## Executive Summary

**Current Coverage**: 0%  
**Target Coverage**: 70-80%  
**Date**: November 28, 2025

### Problem Analysis

The module currently has **0% code coverage** despite having 19 test files. Root causes:

1. **Test Profile Issue**: Default `core-tests` profile only runs 2 tests (BiTemporalFactoryTest, TransactionParticipationTest)
2. **Placeholder Tests**: The 2 core tests are just checking class existence - they don't exercise any actual code
3. **Integration Tests Require Docker**: All 17 integration/performance tests use Testcontainers and require Docker to be running
4. **Missing Unit Tests**: No true unit tests exist that can run without database infrastructure

### Module Composition

| Class | Lines | Complexity | Priority |
|-------|-------|-----------|----------|
| PgBiTemporalEventStore.java | 1,760 | High | **Critical** |
| ReactiveNotificationHandler.java | 389 | Medium | High |
| VertxPoolAdapter.java | 242 | Medium | High |
| ReactiveUtils.java | 156 | Low | Medium |
| BiTemporalEventStoreFactory.java | 155 | Low | Medium |
| **Total** | **2,702** | - | - |

---

## Prerequisites

### Environment Requirements

Before implementing the coverage improvement plan:

1. **Docker Desktop** must be installed and running
   - All existing integration tests use Testcontainers
   - Requires PostgreSQL container for database operations
   - Verify: `docker ps` should work without errors

2. **JDK 21** properly configured
   - ✅ Already configured via toolchains.xml
   - Located at: `C:\Users\mraysmit\.jdks\corretto-21.0.0_35`

3. **Maven 3.8+** with toolchains support
   - ✅ Already configured

### Current Test Failures

**Integration Test Status**: ❌ All 19 integration tests failing due to Docker unavailability

```
Error: Could not find a valid Docker environment
Tests run: 19, Failures: 0, Errors: 19, Skipped: 0
```

**Action Required**: Start Docker Desktop before running integration tests

---

## Phase 1: Quick Wins (Target: 20-30% Coverage)

### 1.1 Activate Existing Integration Tests

**Prerequisites**: Docker Desktop must be installed and running

**Action**: Run tests with integration profile to establish baseline coverage

```bash
# First, ensure Docker is running
docker ps

# Then run integration tests with coverage
mvn clean test -Pintegration-tests jacoco:report
```

**Expected Impact**:
- **Current Status**: All 19 integration tests fail due to missing Docker environment
- **With Docker Running**: Should provide 15-25% coverage immediately
- Identifies which parts of the codebase are already being exercised
- Reveals coverage gaps for targeted improvement

**Test Files Available** (all require Docker/Testcontainers):
- `PgBiTemporalEventStoreIntegrationTest.java` - Core event store operations
- `PgBiTemporalEventStoreTest.java` - Additional event store tests  
- `PeeGeeQBiTemporalWorkingIntegrationTest.java` - Working integration scenarios
- `ReactiveNotificationHandlerIntegrationTest.java` - Notification handling
- `TransactionParticipationIntegrationTest.java` - Transaction support
- `BiTemporalEventStoreExampleTest.java` (5 tests) - Example scenarios
- `TransactionalBiTemporalExampleTest.java` (5 tests) - Transactional examples
- `JsonbConversionValidationTest.java` - JSON serialization
- `ReactiveNotificationTest.java` - Reactive notification tests
- `BiTemporalQueryEdgeCasesTest.java` - Query edge cases
- `PeeGeeQBiTemporalIntegrationTest.java` - General integration tests

### 1.2 Convert Placeholder Core Tests to Real Unit Tests

**Goal**: Create true unit tests that don't require Docker, focusing on validation logic and factory methods

**BiTemporalFactoryTest.java** - Replace class existence checks with:

```java
@Test
void testCreateEventStoreWithValidConfiguration() {
    // Test factory method parameter validation without needing database
    PeeGeeQManager mockManager = mock(PeeGeeQManager.class);
    ObjectMapper mapper = new ObjectMapper();
    
    // Test that factory accepts valid parameters
    assertDoesNotThrow(() -> 
        BiTemporalEventStoreFactory.create(
            mockManager, mapper, String.class, "test_events"
        )
    );
}

@Test
void testFactoryRejectsNullParameters() {
    ObjectMapper mapper = new ObjectMapper();
    
    assertThrows(NullPointerException.class, () ->
        BiTemporalEventStoreFactory.create(null, mapper, String.class, "test")
    );
    
    assertThrows(NullPointerException.class, () ->
        BiTemporalEventStoreFactory.create(mock(PeeGeeQManager.class), null, String.class, "test")
    );
}

@Test  
void testFactoryRejectsInvalidTableName() {
    PeeGeeQManager mockManager = mock(PeeGeeQManager.class);
    ObjectMapper mapper = new ObjectMapper();
    
    assertThrows(IllegalArgumentException.class, () ->
        BiTemporalEventStoreFactory.create(mockManager, mapper, String.class, "")
    );
    
    assertThrows(IllegalArgumentException.class, () ->
        BiTemporalEventStoreFactory.create(mockManager, mapper, String.class, null)
    );
}
```

**TransactionParticipationTest.java** - Add real parameter validation tests:

```java
@Test
void testValidateEntityId() {
    // Test entity ID validation rules
    String validId = "entity-123";
    String tooLong = "a".repeat(256);
    String empty = "";
    
    // These should pass/fail based on actual validation logic
    assertTrue(isValidEntityId(validId), "Valid entity ID should pass");
    assertFalse(isValidEntityId(tooLong), "Entity ID > 255 chars should fail");
    assertFalse(isValidEntityId(empty), "Empty entity ID should fail");
    assertFalse(isValidEntityId(null), "Null entity ID should fail");
}

@Test
void testValidateEventType() {
    String validType = "OrderCreated";
    String tooLong = "a".repeat(256);
    
    assertTrue(isValidEventType(validType));
    assertFalse(isValidEventType(tooLong));
    assertFalse(isValidEventType(""));
    assertFalse(isValidEventType(null));
}

@Test
void testValidateTemporalTimestamps() {
    Instant now = Instant.now();
    Instant past = now.minusSeconds(86400 * 30); // 30 days ago - valid
    Instant farFuture = now.plusSeconds(86400 * 366); // > 1 year - invalid
    Instant nearFuture = now.plusSeconds(3600); // 1 hour - valid
    
    assertTrue(isValidTemporalTime(past));
    assertTrue(isValidTemporalTime(now));
    assertTrue(isValidTemporalTime(nearFuture));
    assertFalse(isValidTemporalTime(farFuture), "Time > 1 year in future should fail");
}
```

**New Test Class: BiTemporalValidationTest.java** (Unit tests for validation logic):

```java
@Tag(TestCategories.CORE)
class BiTemporalValidationTest {
    
    @Test
    void testEventIdGeneration() {
        // Test UUID generation for events
        String eventId1 = generateEventId();
        String eventId2 = generateEventId();
        
        assertNotNull(eventId1);
        assertNotNull(eventId2);
        assertNotEquals(eventId1, eventId2, "Event IDs should be unique");
        assertTrue(eventId1.matches("[0-9a-f-]{36}"), "Should be valid UUID format");
    }
    
    @Test
    void testJsonSerializationRoundTrip() {
        // Test that events can be serialized/deserialized without database
        ObjectMapper mapper = new ObjectMapper();
        TestEvent original = new TestEvent("test-data", 123);
        
        String json = mapper.writeValueAsString(original);
        TestEvent deserialized = mapper.readValue(json, TestEvent.class);
        
        assertEquals(original, deserialized);
    }
}
```

**Expected Impact**: 5-10% coverage increase without requiring Docker

---

## Phase 2: Core Functionality Coverage (Target: 50-60% Coverage)

### 2.1 PgBiTemporalEventStore - Critical Path Testing

**Priority Areas** (ordered by risk/usage):

#### A. Event Appending Operations (Lines ~300-500)
```java
@Test
void testAppendEvent_BasicScenario() {
    // Test successful event append
    BiTemporalEvent<TestEvent> event = createTestEvent();
    CompletableFuture<Void> result = eventStore.append(event);
    assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
}

@Test
void testAppendEvent_WithTransaction() {
    // Test transaction participation
    SqlConnection conn = getTestConnection();
    eventStore.appendInTransaction(conn, event).get();
    // Verify event stored in transaction context
}

@Test
void testAppendEvent_ValidationFailures() {
    // Test parameter validation
    assertThrows(IllegalArgumentException.class, () -> 
        eventStore.append(null)
    );
}
```

#### B. Event Querying (Lines ~600-900)
```java
@Test
void testQueryEvents_ByEntityId() {
    // Arrange: Insert test events
    String entityId = "test-entity-1";
    insertTestEvents(entityId, 5);
    
    // Act: Query by entity
    EventQuery query = EventQuery.builder()
        .entityId(entityId)
        .build();
    List<BiTemporalEvent<TestEvent>> results = eventStore.query(query).get();
    
    // Assert
    assertEquals(5, results.size());
}

@Test
void testQueryEvents_WithTemporalRange() {
    // Test temporal queries (valid time, transaction time)
    Instant start = Instant.now().minusSeconds(3600);
    Instant end = Instant.now();
    
    EventQuery query = EventQuery.builder()
        .validTimeFrom(start)
        .validTimeTo(end)
        .build();
        
    List<BiTemporalEvent<TestEvent>> results = eventStore.query(query).get();
    // Verify results within temporal range
}
```

#### C. Notification Handling (Lines ~1100-1300)
```java
@Test
void testSubscribe_ReceivesNotifications() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<BiTemporalEvent<TestEvent>> received = new AtomicReference<>();
    
    eventStore.subscribe("test-channel", event -> {
        received.set(event);
        latch.countDown();
    });
    
    // Trigger notification
    eventStore.append(createTestEvent()).get();
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(received.get());
}

@Test
void testUnsubscribe_StopsNotifications() {
    String subscriptionId = eventStore.subscribe("test-channel", event -> {});
    eventStore.unsubscribe(subscriptionId);
    // Verify no notifications received after unsubscribe
}
```

#### D. Resource Management (Lines ~1500-1700)
```java
@Test
void testClose_ReleasesResources() {
    eventStore.close();
    assertTrue(eventStore.isClosed());
    
    // Verify operations fail after close
    assertThrows(IllegalStateException.class, () ->
        eventStore.append(createTestEvent())
    );
}

@Test
void testVertxPoolManagement() {
    // Test pool initialization and configuration
    Pool pool = getPoolFromEventStore();
    assertNotNull(pool);
    // Verify pool settings
}
```

**Expected Impact**: 30-35% coverage increase

### 2.2 ReactiveNotificationHandler Testing

```java
@Test
void testNotificationHandlerRegistration() {
    ReactiveNotificationHandler<TestEvent> handler = new ReactiveNotificationHandler<>(
        mockVertx, mockPool, objectMapper, TestEvent.class
    );
    
    String channel = "test-events";
    handler.registerHandler(channel, event -> {});
    assertTrue(handler.hasHandlers(channel));
}

@Test
void testNotificationProcessing() {
    // Test JSON notification parsing and handler invocation
    String jsonNotification = "{\"entityId\":\"test-1\",\"payload\":\"data\"}";
    handler.processNotification("test-channel", jsonNotification);
    // Verify handler called with correct event
}

@Test
void testNotificationErrorHandling() {
    // Test malformed JSON handling
    String invalidJson = "{invalid}";
    assertDoesNotThrow(() -> 
        handler.processNotification("test-channel", invalidJson)
    );
}
```

**Expected Impact**: 10-12% coverage increase

### 2.3 Utility Classes (ReactiveUtils, VertxPoolAdapter)

```java
@Test
void testReactiveUtilsConversions() {
    // Test Future to CompletableFuture conversion
    Future<String> vertxFuture = Future.succeededFuture("test");
    CompletableFuture<String> cf = ReactiveUtils.toCompletableFuture(vertxFuture);
    assertEquals("test", cf.get());
}

@Test
void testVertxPoolAdapterConfiguration() {
    PgConnectOptions connectOptions = createTestConnectOptions();
    PoolOptions poolOptions = new PoolOptions().setMaxSize(10);
    
    Pool pool = VertxPoolAdapter.createPool(vertx, connectOptions, poolOptions);
    assertNotNull(pool);
    // Verify pool settings applied correctly
}
```

**Expected Impact**: 5-8% coverage increase

---

## Phase 3: Edge Cases & Error Handling (Target: 70-80% Coverage)

### 3.1 Error Scenarios

```java
@Test
void testConnectionFailureHandling() {
    // Test behavior when database unavailable
    // Should throw appropriate exception with meaningful message
}

@Test
void testConcurrentOperations() {
    // Test thread safety of concurrent appends
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < 100; i++) {
        futures.add(CompletableFuture.runAsync(() -> 
            eventStore.append(createTestEvent()).join()
        , executor));
    }
    
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    // Verify all events stored correctly
}

@Test
void testQueryWithInvalidParameters() {
    // Test query validation
    EventQuery invalidQuery = EventQuery.builder()
        .limit(-1) // Invalid limit
        .build();
        
    assertThrows(IllegalArgumentException.class, () ->
        eventStore.query(invalidQuery)
    );
}
```

### 3.2 Temporal Edge Cases

```java
@Test
void testQueryAtEventTimeBoundary() {
    // Test queries at exact event timestamps
    Instant eventTime = Instant.parse("2025-11-28T10:00:00Z");
    insertEventAtTime(eventTime);
    
    // Query with exact boundary
    EventQuery query = EventQuery.builder()
        .validTimeFrom(eventTime)
        .validTimeTo(eventTime)
        .build();
        
    List<BiTemporalEvent<TestEvent>> results = eventStore.query(query).get();
    assertEquals(1, results.size());
}

@Test
void testQueryWithNullTemporalValues() {
    // Test queries with null temporal values (should default appropriately)
    EventQuery query = EventQuery.builder()
        .entityId("test-1")
        .validTimeFrom(null) // Should default to beginning of time
        .validTimeTo(null)   // Should default to now
        .build();
        
    assertDoesNotThrow(() -> eventStore.query(query).get());
}
```

### 3.3 Performance & Resource Tests

```java
@Test
void testLargePayloadHandling() {
    // Test with large JSON payloads (near limits)
    TestEvent largePayload = createLargePayload(1024 * 100); // 100KB
    CompletableFuture<Void> result = eventStore.append(createEvent(largePayload));
    assertDoesNotThrow(() -> result.get(10, TimeUnit.SECONDS));
}

@Test
void testMemoryLeaksOnSubscriptions() {
    // Subscribe and unsubscribe many times
    for (int i = 0; i < 1000; i++) {
        String id = eventStore.subscribe("test-" + i, event -> {});
        eventStore.unsubscribe(id);
    }
    // Verify internal subscription map is cleared
    assertEquals(0, getActiveSubscriptionCount());
}
```

**Expected Impact**: 15-20% coverage increase

---

## Phase 4: Test Infrastructure Improvements

### 4.1 Shared Test Utilities

Create `BiTemporalTestSupport.java`:

```java
public class BiTemporalTestSupport {
    
    public static PgBiTemporalEventStore<TestEvent> createTestEventStore() {
        // Shared test store creation with test database
    }
    
    public static BiTemporalEvent<TestEvent> createTestEvent(
        String entityId, String eventType, TestEvent payload) {
        return SimpleBiTemporalEvent.<TestEvent>builder()
            .entityId(entityId)
            .eventType(eventType)
            .payload(payload)
            .validTime(Instant.now())
            .build();
    }
    
    public static void insertTestEvents(PgBiTemporalEventStore<?> store, 
                                       int count) {
        // Bulk insert test events
    }
    
    public static void cleanupTestData(String entityIdPrefix) {
        // Clean up test events after test
    }
}
```

### 4.2 Test Categorization Strategy

Update test profiles to include coverage:

```xml
<profile>
    <id>core-tests-with-coverage</id>
    <properties>
        <test.groups>core</test.groups>
        <jacoco.skip>false</jacoco.skip>
    </properties>
</profile>

<profile>
    <id>coverage-report</id>
    <properties>
        <test.groups>core,integration</test.groups>
        <test.excludedGroups>performance,flaky</test.excludedGroups>
        <jacoco.skip>false</jacoco.skip>
    </properties>
</profile>
```

### 4.3 Mock Strategy

Use Mockito for unit tests that don't need real database:

```java
@ExtendWith(MockitoExtension.class)
class PgBiTemporalEventStoreUnitTest {
    
    @Mock
    private PeeGeeQManager mockManager;
    
    @Mock
    private Pool mockPool;
    
    @InjectMocks
    private PgBiTemporalEventStore<TestEvent> eventStore;
    
    @Test
    void testParameterValidation_WithoutDatabase() {
        // Unit tests for validation logic without database
    }
}
```

---

## Implementation Timeline

### Week 1: Foundation
- ✅ Day 1: Add JaCoCo plugin configuration
- Day 2: Run integration tests baseline
- Day 3: Fix placeholder core tests
- Day 4: Create BiTemporalTestSupport utilities
- Day 5: Document baseline coverage metrics

### Week 2: Core Coverage
- Day 1-2: PgBiTemporalEventStore append operations
- Day 3-4: PgBiTemporalEventStore query operations
- Day 5: ReactiveNotificationHandler tests

### Week 3: Comprehensive Coverage
- Day 1-2: Edge cases and error scenarios
- Day 3: Utility classes (ReactiveUtils, VertxPoolAdapter)
- Day 4: Performance and resource management tests
- Day 5: Review and gap analysis

### Week 4: Optimization
- Day 1-2: Identify uncovered branches
- Day 3-4: Add missing test cases
- Day 5: Final coverage report and documentation

---

## Coverage Goals by Component

| Component | Target Coverage | Rationale |
|-----------|----------------|-----------|
| PgBiTemporalEventStore | 75-80% | Core business logic, critical path |
| ReactiveNotificationHandler | 70-75% | Message handling, async complexity |
| VertxPoolAdapter | 70-75% | Resource management critical |
| ReactiveUtils | 80-85% | Utility functions, easier to test |
| BiTemporalEventStoreFactory | 80-85% | Factory pattern, straightforward |
| **Overall Module** | **75-80%** | Balanced coverage for maintainability |

---

## Metrics & Monitoring

### Success Criteria

1. **Coverage Metrics**:
   - Line coverage: 75%+ (1,900+ of 2,702 lines)
   - Branch coverage: 70%+
   - Method coverage: 80%+

2. **Test Quality**:
   - Zero flaky tests in core suite
   - Average test execution time < 5s for core tests
   - All integration tests pass consistently

3. **Documentation**:
   - Every untested code path documented with reason
   - Complex scenarios have example test cases
   - Coverage report accessible to team

### Continuous Monitoring

Add to CI/CD pipeline:

```yaml
# .github/workflows/test-coverage.yml
- name: Run Tests with Coverage
  run: mvn clean test -Pcoverage-report jacoco:report

- name: Check Coverage Threshold
  run: |
    mvn jacoco:check -Djacoco.check.lineRatio=0.75
    
- name: Upload Coverage Report
  uses: codecov/codecov-action@v3
  with:
    files: ./target/site/jacoco/jacoco.xml
```

---

## Exclusions & Rationale

### Code Excluded from Coverage Requirements

1. **Deprecated Methods**: Methods marked for removal in next version
2. **Defensive Programming**: Unreachable error paths (document in comments)
3. **Performance Optimization Code**: JVM-specific optimizations that can't be reliably tested
4. **Logging Statements**: Pure logging calls without business logic

### Example Exclusion Annotation

```java
@ExcludeFromCoverage(reason = "Defensive null check - validated by upstream callers")
private void validateNotNull(Object obj) {
    if (obj == null) throw new IllegalArgumentException("Must not be null");
}
```

---

## Next Steps

1. **Immediate** (Today):
   - ✅ JaCoCo plugin configured
   - ✅ Test coverage improvement plan created
   - ⚠️ **Action Required**: Start Docker Desktop
   - Run `mvn clean test -Pintegration-tests jacoco:report` to get true baseline
   - Review existing integration test coverage
   - Identify quick wins in BiTemporalFactoryTest

2. **This Week**:
   - Start Docker Desktop and verify integration tests pass
   - Document actual baseline coverage from integration tests
   - Implement BiTemporalTestSupport utilities
   - Fix placeholder core tests (BiTemporalFactoryTest, TransactionParticipationTest)
   - Add 5-10 unit tests for parameter validation (no Docker required)

3. **Next Sprint**:
   - Complete Phase 2: Core functionality coverage
   - Target 50% coverage milestone
   - Review with team and adjust priorities

---

## Docker Setup Instructions

If Docker is not installed or not running:

### Windows (Docker Desktop)

1. Download from: https://www.docker.com/products/docker-desktop/
2. Install and start Docker Desktop
3. Verify: Open PowerShell and run `docker ps`
4. Expected output: Empty container list (not an error)

### Troubleshooting

If tests still fail after starting Docker:

```bash
# Check Docker is accessible
docker ps

# Check Docker Desktop is running (Windows)
Get-Process "*docker*"

# Restart Docker Desktop if needed
# From System Tray: Right-click Docker icon → Restart

# Re-run tests
mvn clean test -Pintegration-tests
```

---

## References

- [JaCoCo Documentation](https://www.jacoco.org/jacoco/trunk/doc/)
- [Maven Surefire Test Profiles](https://maven.apache.org/surefire/maven-surefire-plugin/examples/testng.html)
- Project: `docs/TESTING-GUIDE.md` (sibling module testing strategies)
- Project: `docs/PEEGEEQ_COMPLETE_GUIDE.md` (API usage examples)

---

**Document Owner**: Development Team  
**Last Updated**: November 28, 2025  
**Review Schedule**: Weekly during implementation, then monthly
