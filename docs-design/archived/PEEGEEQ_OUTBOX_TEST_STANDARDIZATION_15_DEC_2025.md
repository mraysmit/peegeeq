# Test Standardization Report

**Date:** 15 December 2025
**Modules:** peegeeq-outbox, peegeeq-native
**Status:** Complete

## Overview

This report documents the standardization of three outbox test classes to align with the established integration test patterns used across the `peegeeq-native` and `peegeeq-bitemporal` modules.

## Problem Statement

The original unit tests (`OutboxConsumerUnitTest`, `OutboxProducerUnitTest`, `OutboxFactoryUnitTest`) were generating ERROR logs during execution:

```
Connection configuration 'default' not found
```

This occurred because the tests were using mocked components but the polling mechanism required real database connections. Since PeeGeeQ depends entirely on PostgreSQL, all tests should be integration tests using TestContainers.

## Changes Made

### Files Renamed and Standardized

| Original File | New File | Test Count |
|---------------|----------|------------|
| `OutboxConsumerUnitTest.java` | `OutboxConsumerIntegrationTest.java` | 18 |
| `OutboxProducerUnitTest.java` | `OutboxProducerIntegrationTest.java` | 21 |
| `OutboxFactoryUnitTest.java` | `OutboxFactoryIntegrationTest.java` | 38 |

### Pattern Standardization Details

#### 1. Container Configuration

**Before:**
```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_consumer_test")
        .withUsername("test")
        .withPassword("test");
```

**After:**
```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
        .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
        .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
        .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
        .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
        .withReuse(false);
```

#### 2. Schema Initialization

**Before:**
```java
TestSchemaInitializer.initializeSchema(postgres);
```

**After:**
```java
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
```

#### 3. Manager Shutdown

**Before:**
```java
manager.close();
Thread.sleep(100);
```

**After:**
```java
manager.stop();
```

#### 4. Test Category Tag

**Before:**
```java
@Tag(TestCategories.CORE)
```

**After:**
```java
@Tag(TestCategories.INTEGRATION)
```

#### 5. Removed Unnecessary Annotation

Removed `@TestInstance(TestInstance.Lifecycle.PER_METHOD)` to match other integration tests.

## Centralized Test Infrastructure Used

| Component | Class | Purpose |
|-----------|-------|---------|
| Constants | `PostgreSQLTestConstants` | Docker image, credentials, shared memory size |
| Schema Init | `PeeGeeQTestSchemaInitializer` | Centralized schema initialization with component selection |
| Categories | `TestCategories.INTEGRATION` | Consistent test categorization |

## Test Execution Results

```
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 25.415 s
```

All 77 tests pass with proper database connectivity via TestContainers. No ERROR logs related to missing connection configurations.

## Running the Tests

```bash
mvn test -pl peegeeq-outbox -Pintegration-tests \
  "-Dtest=OutboxConsumerIntegrationTest,OutboxProducerIntegrationTest,OutboxFactoryIntegrationTest"
```

## Benefits of Standardization

1. **Consistency** - All modules now follow the same test patterns
2. **Maintainability** - Centralized constants and utilities reduce duplication
3. **Reliability** - Real database connections ensure tests validate actual behavior
4. **No False Errors** - Eliminated misleading ERROR logs from missing connections

## Files Deleted

- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerUnitTest.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxProducerUnitTest.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxFactoryUnitTest.java`

## Files Created

- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerIntegrationTest.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxProducerIntegrationTest.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxFactoryIntegrationTest.java`

---

## Part 2: peegeeq-native PostgreSQL Image Constant Standardization

### Problem Statement

The `peegeeq-native` module had 33 test files with hardcoded PostgreSQL Docker image strings:

```java
new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
```

This was inconsistent with `peegeeq-outbox` and `peegeeq-bitemporal` which use the centralized constant `PostgreSQLTestConstants.POSTGRES_IMAGE`.

### Files Updated (33 total)

#### Core Test Files (21 files)

| File | Change |
|------|--------|
| `NativeQueueIntegrationTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerGroupTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerGroupV110Test.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeBackwardCompatibilityTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeFailureTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeGracefulDegradationTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeIntegrationTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeMetricsTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModePerformanceTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModePropertyIntegrationTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeResourceManagementTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ConsumerModeTypeSafetyTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `HybridModeEdgeCaseTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `JsonbConversionValidationTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ListenNotifyOnlyEdgeCaseTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `ListenReconnectFaultInjectionIT.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `MemoryAndResourceLeakTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `MultiConsumerModeTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PeeGeeQConfigurationConsumerModeTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PgNativeQueueShutdownTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PgNativeQueueTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |

#### Additional Test Files (7 files)

| File | Change |
|------|--------|
| `PgNativeQueueTestContainers.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PgNativeQueueTestContainersWithPojo.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PgNotificationStreamTestContainers.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PollingOnlyEdgeCaseTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PostgreSQLErrorHandlingTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `QueueFactoryConsumerModeTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `RetryableErrorIT.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |

#### Example Test Files (5 files)

| File | Change |
|------|--------|
| `ConsumerGroupExampleTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `MessagePriorityExampleTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `NativeVsOutboxComparisonExampleTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PeeGeeQExampleTest.java` | Removed local `POSTGRES_IMAGE` constant, replaced with `PostgreSQLTestConstants.POSTGRES_IMAGE` |
| `PerformanceComparisonExampleTest.java` | Replaced hardcoded string with `PostgreSQLTestConstants.POSTGRES_IMAGE` |

### Pattern Applied

**Before:**
```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("testdb")
        .withUsername("testuser")
        .withPassword("testpass");
```

**After:**
```java
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
        .withDatabaseName("testdb")
        .withUsername("testuser")
        .withPassword("testpass");
```

### Special Case: PeeGeeQExampleTest.java

This file had a local constant that was removed:

**Before:**
```java
private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
```

**After:**
```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
```

### Verification

1. **Search Verification:** `findstr` command returns no matches for hardcoded string in peegeeq-native
2. **Compilation:** `mvn test-compile` succeeds with no errors
3. **Test Execution:** `NativeQueueIntegrationTest` passes successfully

### Benefits

1. **Single Point of Change:** PostgreSQL version can be updated in one place (`PostgreSQLTestConstants.POSTGRES_IMAGE`)
2. **Consistency:** All modules now use the same centralized constant
3. **Reduced Docker Image Pollution:** Ensures all tests use the same Docker image version
4. **Maintainability:** Easier to upgrade PostgreSQL version across all tests

