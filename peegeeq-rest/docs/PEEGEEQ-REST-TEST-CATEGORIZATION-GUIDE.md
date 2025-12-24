# PeeGeeQ REST Test Categorization Guide

## Overview

This module uses JUnit 5 `@Tag` annotations to categorize tests for selective execution through Maven profiles. This enables fast development feedback cycles while maintaining comprehensive test coverage for REST API functionality.

## Test Categories

### 🚀 CORE Tests (Default)
**Purpose**: Fast unit tests for immediate development feedback  
**Execution Time**: < 30 seconds total, < 1 second per test  
**Dependencies**: No external services, mocked dependencies only  
**Parallel Execution**: Methods (4 threads)  

**Examples**:
- `BasicUnitTest` - Configuration and API validation
- `QueueHandlerUnitTest` - Message request serialization tests
- Configuration validation logic
- JSON serialization/deserialization tests

**Usage**:
```java
@Test
@Tag(TestCategories.CORE)
void testConfigurationValidation() { ... }
```

### 🔧 INTEGRATION Tests
**Purpose**: Tests with real infrastructure dependencies  
**Execution Time**: 1-3 minutes total  
**Dependencies**: TestContainers with PostgreSQL, REST servers  
**Parallel Execution**: Classes (2 threads)  

**Examples**:
- `PeeGeeQRestServerTest` - Full REST API integration
- `SqlTemplateProcessorTest` - Database template processing
- REST endpoint validation with real servers
- Database setup and management tests

**Usage**:
```java
@Test
@Tag(TestCategories.INTEGRATION)
@Testcontainers
void testRestApiEndpoint() { ... }
```

### ⚡ PERFORMANCE Tests
**Purpose**: Load and throughput benchmarks  
**Execution Time**: 2-5 minutes  
**Dependencies**: TestContainers, PostgreSQL, may require specific hardware  
**Parallel Execution**: None (sequential to avoid resource contention)  

**Examples**:
- `PeeGeeQRestPerformanceTest` - REST API throughput testing
- Concurrent request handling benchmarks
- Database connection pooling performance

**Usage**:
```java
@Test
@Tag(TestCategories.PERFORMANCE)
void testApiThroughput() { ... }
```

### 💨 SMOKE Tests
**Purpose**: Ultra-fast basic verification  
**Execution Time**: < 10 seconds total  
**Dependencies**: None  
**Parallel Execution**: Methods (8 threads)  

**Examples**:
- Basic server startup validation
- Configuration loading tests
- Simple endpoint availability checks

**Usage**:
```java
@Test
@Tag(TestCategories.SMOKE)
void testServerStartup() { ... }
```

### 🐌 SLOW Tests
**Purpose**: Long-running comprehensive tests  
**Execution Time**: 5+ minutes  
**Dependencies**: Various  
**Parallel Execution**: None  

### 🔥 FLAKY Tests
**Purpose**: Unstable tests requiring investigation  
**Execution**: Excluded from all profiles by default  

## Maven Profiles

### Default Profile (core-tests)
```bash
mvn test                    # Runs CORE tests only (~8 seconds)
```

### Specific Categories
```bash
mvn test -Pintegration-tests    # Integration tests (~13 seconds)
mvn test -Pperformance-tests    # Performance tests (~10 seconds)
mvn test -Psmoke-tests          # Smoke tests (~5 seconds)
mvn test -Pslow-tests           # Slow tests (5+ minutes)
```

### Combined Execution
```bash
mvn test -Pall-tests           # All tests except flaky (~30 seconds)
```

## Development Workflow

### 🔄 Daily Development
```bash
mvn test                       # Fast feedback (8 seconds)
```

### 🚀 Pre-Commit
```bash
mvn test -Pintegration-tests   # Comprehensive validation (13 seconds)
```

### 🏗️ CI/CD Pipeline
```bash
# Stage 1: Fast feedback
mvn test -Pcore-tests

# Stage 2: Integration validation  
mvn test -Pintegration-tests

# Stage 3: Performance validation
mvn test -Pperformance-tests

# Stage 4: Comprehensive (optional)
mvn test -Pall-tests
```

## Test Categorization Guidelines

### When to use CORE
- Pure unit tests with no external dependencies
- Configuration validation logic
- JSON serialization/deserialization tests
- Request/response mapping validation
- Business logic tests
- < 1 second execution time per test

### When to use INTEGRATION
- Tests requiring TestContainers
- REST API endpoint testing
- Database integration tests
- Server startup/shutdown tests
- End-to-end workflow validation
- Real PostgreSQL interactions

### When to use PERFORMANCE
- REST API throughput measurements
- Concurrent request handling tests
- Database connection pool performance
- Load testing scenarios
- Resource usage validation

### When to use SMOKE
- Basic server availability checks
- Configuration loading validation
- Simple endpoint health checks
- Pre-commit hook candidates
- Ultra-fast sanity checks

## Adding New Tests

1. **Identify the appropriate category** based on dependencies and execution time
2. **Add the @Tag annotation** to the test class:
   ```java
   @Tag(TestCategories.CORE)
   public class MyNewTest { ... }
   ```
3. **Import the TestCategories class**:
   ```java
   import dev.mars.peegeeq.test.categories.TestCategories;
   import org.junit.jupiter.api.Tag;
   ```
4. **Verify categorization** by running the specific profile:
   ```bash
   mvn test -Pcore-tests -Dtest=MyNewTest
   ```

## Troubleshooting

### Test not running in expected profile
- Verify `@Tag` annotation is present on the test class
- Check imports are correct
- Ensure test class name follows naming conventions

### Performance issues
- Check if tests are properly categorized
- Verify parallel execution settings
- Consider moving slow tests to appropriate categories

### Profile conflicts
- Only one profile should be active at a time
- Use `-P` flag to explicitly specify profile
- Check for conflicting system properties

## Measured Performance

Based on actual execution times:

| Category | Before | After | Improvement | Test Count |
|----------|--------|-------|-------------|------------|
| CORE | 55.3s | **8.2s** | **6.7x faster** | 2+ |
| INTEGRATION | 55.3s | **13.3s** | **4.2x faster** | 2+ |
| PERFORMANCE | 55.3s | **10.3s** | **5.4x faster** | 1+ |
| ALL | 55.3s | **~30s** | **1.8x faster** | 5+ |

## Benefits

✅ **Massive Performance Improvement**: Core tests now run in 8.2 seconds (6.7x faster)
✅ **Fast Integration Testing**: Integration tests complete in 13.3 seconds (4.2x faster)
✅ **Selective Testing**: Run only relevant tests for specific changes
✅ **CI/CD Optimization**: Parallel pipeline stages with appropriate timeouts
✅ **Resource Efficiency**: Avoid unnecessary TestContainer startup for unit tests
✅ **Clear Organization**: Explicit categorization makes test purpose obvious
✅ **Professional Development Experience**: Sub-10-second feedback for daily development

---

## E2E Test Suites

### Overview
The PeeGeeQ REST API has comprehensive E2E test coverage with **26 tests** across three test suites. All tests use TestContainers with PostgreSQL and follow the integration-tests Maven profile.

### Test Suites

#### 1. QueueManagementE2ETest (11 tests)
**File**: `src/test/java/dev/mars/peegeeq/rest/handlers/QueueManagementE2ETest.java`

Core queue management operations:
- ✅ **Test 1**: Get Queue Details - Retrieve queue information
- ✅ **Test 2**: Publish Messages - Send messages to queue
- ✅ **Test 3**: Verify Message Count - Check queue depth
- ✅ **Test 4**: Pause Queue - Pause all subscriptions
- ✅ **Test 5**: Resume Queue - Resume all subscriptions
- ✅ **Test 6**: Purge Queue - Clear all messages
- ✅ **Test 7**: Verify Queue is Empty - Confirm purge worked
- ✅ **Test 8**: Delete Queue - Permanently remove queue
- ✅ **Test 9**: Verify Queue No Longer Exists - Confirm deletion
- ✅ **Test 10**: Error Handling - Invalid Setup ID (negative test)
- ✅ **Test 11**: Error Handling - Invalid Queue Name (negative test)

**Key Features**:
- Uses `@Order` annotation to ensure tests run in sequence
- Each test builds on previous test state
- Comprehensive logging with test markers (===)
- Validates HTTP status codes and response formats
- Tests both success and error scenarios

#### 2. QueueManagementAdvancedE2ETest (8 tests)
**File**: `src/test/java/dev/mars/peegeeq/rest/handlers/QueueManagementAdvancedE2ETest.java`

Advanced scenarios and edge cases:
- ✅ **Concurrent Publishing** - 50 messages from 10 concurrent publishers
- ✅ **Empty Queue Operations** - Pause/resume/purge on empty queues
- ✅ **Large Message Payload** - 600KB message handling
- ✅ **Special Characters** - Unicode and special character handling
- ✅ **Rapid Queue Operations** - 10 pause/resume cycles
- ✅ **Error Recovery** - Invalid message format handling
- ✅ **Concurrent Queue Purge** - 5 concurrent purge operations
- ✅ **Queue Details During Operations** - Monitoring queue state

**Key Features**:
- Tests concurrent operations and race conditions
- Validates edge cases and boundary conditions
- Tests error recovery and resilience
- Verifies system behavior under stress

#### 3. MessageOperationsE2ETest (7 tests)
**File**: `src/test/java/dev/mars/peegeeq/rest/handlers/MessageOperationsE2ETest.java`

Message-focused operations:
- ✅ **Simple Message Publishing** - Basic message send
- ✅ **Priority Messages** - High-priority message handling
- ✅ **Delayed Messages** - Message scheduling (5-second delay)
- ✅ **Custom Headers** - Message metadata (X-Custom-Header)
- ✅ **Message Groups** - Grouped message handling
- ✅ **Batch Publishing** - 10 messages in sequence
- ✅ **Queue Message Count** - Verification of message counts

**Key Features**:
- Tests message attributes and metadata
- Validates message scheduling and delays
- Tests batch operations
- Verifies message counting accuracy

### Running E2E Tests

#### Run All E2E Tests
```bash
mvn test -Pintegration-tests -pl peegeeq-rest
```

#### Run Specific Test Suite
```bash
# Core queue management tests
mvn test -Dtest=QueueManagementE2ETest -Pintegration-tests -pl peegeeq-rest

# Advanced scenario tests
mvn test -Dtest=QueueManagementAdvancedE2ETest -Pintegration-tests -pl peegeeq-rest

# Message operation tests
mvn test -Dtest=MessageOperationsE2ETest -Pintegration-tests -pl peegeeq-rest
```

#### Run Specific Test
```bash
mvn test -Dtest=QueueManagementE2ETest#test04_PauseQueue -Pintegration-tests -pl peegeeq-rest
```

### Understanding Test Output

#### Successful Test Indicators
- `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`
- Log messages with ✅ emoji
- HTTP 200/201 status codes for successful operations
- HTTP 404 status codes for expected error cases

#### Negative Test Cases
Some tests **intentionally trigger errors** to verify proper error handling:

**Example: Test 10 - Invalid Setup ID**
```
2025-12-24 15:13:48.607 [vert.x-eventloop-thread-0] WARN  i.v.ext.web.handler.LoggerHandler - 127.0.0.1 - - [Wed, 24 Dec 2025 07:13:48 GMT] "GET /api/v1/queues/invalid-setup/some-queue HTTP/1.1" 404 172 "-" "Vert.x-WebClient/5.0.4"
2025-12-24 15:13:48.607 [vert.x-eventloop-thread-2] INFO  d.m.p.r.h.QueueManagementE2ETest - ✅ Correctly handled invalid setup ID
```

This is **expected behavior**:
1. Test requests an invalid setup ID (`"invalid-setup"`)
2. Application correctly throws `SetupNotFoundException`
3. Error is logged at **DEBUG level** (no stack trace for expected errors)
4. API returns HTTP 404 (correct error response)
5. Test verifies the 404 status code and **passes** ✅

**Why This Matters**:
- Validates error handling works correctly
- Ensures API returns appropriate HTTP status codes
- Confirms system doesn't crash on invalid input
- Tests defensive programming practices

**How to Identify Negative Tests**:
- Look for test names containing "Error", "Invalid", or "Handling"
- Check for assertions on 4xx/5xx status codes
- Look for log messages like "✅ Correctly handled invalid..."
- Only HTTP access logs (WARN level) appear, no ERROR logs or stack traces

### Error Logging Best Practice

The codebase follows a pattern of **not logging stack traces for expected errors**:

**Implementation Pattern**:
```java
.exceptionally(throwable -> {
    // Check if this is an expected setup not found error (no stack trace)
    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
    if (isSetupNotFoundError(cause)) {
        logger.debug("🚫 EXPECTED: Setup not found for queue details: {} (setup: {})", queueName, setupId);
    } else {
        logger.error("Error getting queue details for setup: {}, queue: {}", setupId, queueName, throwable);
    }
    sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
    return null;
});
```

**Helper Method**:
```java
private boolean isSetupNotFoundError(Throwable throwable) {
    return throwable != null &&
           throwable.getClass().getSimpleName().equals("SetupNotFoundException");
}
```

**Benefits**:
- ✅ Expected errors logged at DEBUG level (no noise in production logs)
- ✅ No stack traces for expected conditions (cleaner test output)
- ✅ Stack traces only for unexpected errors (easier to identify real problems)
- ✅ Consistent pattern across all handlers

**Handlers Implementing This Pattern**:
- `ManagementApiHandler` - Queue management operations
- `DatabaseSetupHandler` - Setup CRUD operations
- `QueueHandler` - Message operations
- `EventStoreHandler` - Event store operations

### Test Infrastructure

#### TestContainers Setup
All E2E tests use:
- PostgreSQL 15 container
- Automatic container lifecycle management
- Isolated database per test suite
- Automatic cleanup after tests

#### Common Test Patterns
```java
@BeforeAll
static void setup() {
    // Start PostgreSQL container
    // Initialize PeeGeeQ REST server
    // Create HTTP client
}

@AfterAll
static void teardown() {
    // Stop REST server
    // Stop PostgreSQL container
    // Clean up resources
}

@Test
@Order(n)
void testSomething() {
    // Arrange: Set up test data
    // Act: Execute operation
    // Assert: Verify results
    // Log: Record success with ✅
}
```

#### HTTP Client Usage
Tests use Java 11+ HttpClient:
```java
HttpResponse<String> response = client.send(
    HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/v1/queues/..."))
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .header("Content-Type", "application/json")
        .build(),
    HttpResponse.BodyHandlers.ofString()
);
```

### Test Results
**All 26 E2E tests passing** ✅

```
[INFO] Results:
[INFO]
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
