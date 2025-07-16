# Test Warnings Documentation

This document explains the various warning messages that may appear during test execution and whether they are expected or need attention.

## Expected Warnings (Safe to Ignore)

### 1. Configuration Test Warnings

These warnings appear in `PeeGeeQConfigurationTest` and are **intentional** - they test error handling for invalid configuration values:

```
WARN d.m.p.db.config.PeeGeeQConfiguration - Invalid duration value for peegeeq.test.invalid.duration: not-a-duration, using default: PT5M
WARN d.m.p.db.config.PeeGeeQConfiguration - Invalid long value for peegeeq.test.invalid.long: not-a-long, using default: 1000
WARN d.m.p.db.config.PeeGeeQConfiguration - Invalid integer value for peegeeq.test.invalid.int: not-an-int, using default: 1000
WARN d.m.p.db.config.PeeGeeQConfiguration - Invalid double value for peegeeq.circuit-breaker.failure-rate-threshold: not-a-double, using default: 50.0
```

**Why they exist**: These test the configuration system's ability to handle invalid values gracefully and fall back to defaults.

### 2. Message Processing Failure Warnings

These warnings appear in native queue tests and are **intentional** - they test retry logic and dead letter queue functionality:

```
WARN d.m.p.pgqueue.PgNativeQueueConsumer - Message processing failed for X: java.lang.RuntimeException: Simulated processing failure, attempt 1
WARN d.m.p.pgqueue.PgNativeQueueConsumer - Message processing failed for X: java.lang.RuntimeException: Always fails
WARN d.m.p.pgqueue.PgNativeQueueConsumer - Message X exceeded retry limit, moving to dead letter queue
```

**Why they exist**: These test the queue's resilience features including retry mechanisms and dead letter queue handling.

### 3. TestContainers Docker Warnings (Resolved)

Previously, you might have seen Docker-related warnings. These have been addressed with:
- `.testcontainers.properties` configuration file
- Optimized TestContainers settings
- Reduced logging levels for TestContainers components

## Warnings That Need Attention

### 1. Unexpected Application Errors

Any warnings from application code that are NOT part of the expected test scenarios above should be investigated:

```
WARN dev.mars.peegeeq.* - [Unexpected warning message]
```

### 2. Database Connection Issues

Warnings about database connectivity issues (outside of TestContainers startup):

```
WARN com.zaxxer.hikari.* - [Connection pool warnings]
WARN org.postgresql.* - [PostgreSQL driver warnings]
```

### 3. Resource Cleanup Warnings

Warnings about resources not being properly cleaned up:

```
WARN - [Resource leak warnings]
WARN - [Thread pool shutdown warnings]
```

## Logging Configuration

### Test-Specific Logging

Each module now has a `logback-test.xml` file that:
- Reduces noise from dependencies (TestContainers, Hikari, Vert.x)
- Sets appropriate log levels for test execution
- Uses simplified console output format
- Optionally supports test-specific file logging

### Production Logging

Each module has a `logback.xml` file for production use that:
- Provides detailed logging for the specific module
- Includes file-based logging with rotation
- Maintains appropriate log levels for production monitoring

## Reducing Test Noise

To further reduce test output noise, you can:

1. **Run tests with specific log levels**:
   ```bash
   mvn test -Dlogback.configurationFile=src/test/resources/logback-test.xml
   ```

2. **Run tests in quiet mode**:
   ```bash
   mvn test -q
   ```

3. **Filter specific test output**:
   ```bash
   mvn test 2>&1 | grep -v "TestContainers\|Hikari\|Vert.x"
   ```

## Test Categories and Expected Behavior

### Unit Tests
- Should have minimal warnings
- Any warnings should be from intentional error condition testing

### Integration Tests
- May have TestContainers startup messages (reduced with current configuration)
- May have database connection establishment messages
- Should not have application-level warnings unless testing error conditions

### End-to-End Tests
- May have more verbose output as they test complete workflows
- Expected to have some retry/failure warnings when testing resilience features

## Monitoring Test Health

Watch for these patterns that indicate test issues:

1. **Increasing warning count over time** - May indicate degrading test quality
2. **New warning types** - Should be investigated and categorized
3. **Warnings in previously clean tests** - May indicate regressions
4. **Resource-related warnings** - Could indicate memory leaks or cleanup issues

## Configuration Files Summary

- **`.testcontainers.properties`** - Reduces TestContainers warnings
- **`*/src/test/resources/logback-test.xml`** - Test-specific logging configuration
- **`*/src/main/resources/logback.xml`** - Production logging configuration
- **`peegeeq-db/src/test/resources/peegeeq-test.properties`** - Contains intentionally invalid values for testing
