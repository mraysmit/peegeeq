# PeeGeeQ Intentional Failure Tests Documentation

#### &copy; Mark Andrew Ray-Smith Cityline Ltd 2025

This document provides a comprehensive overview of all intentional failure tests in the PeeGeeQ project. These tests are designed to verify error handling, resilience, and recovery mechanisms by deliberately simulating failure scenarios.

## Overview

All intentional failure tests in PeeGeeQ are clearly marked with:
- **Console output** indicating "INTENTIONAL FAILURE" or "INTENTIONAL TEST FAILURE"
- **JavaDoc comments** explaining the failure scenario being tested
- **Clear logging** showing when failures are being simulated
- **Success messages** confirming that error handling worked correctly

## Test Categories

### 1. Health Check Failure Tests

#### HealthCheckManagerTest

**Location:** `peegeeq-db/src/test/java/dev/mars/peegeeq/db/health/HealthCheckManagerTest.java`

| Test Method | Purpose | Failure Scenario |
|-------------|---------|------------------|
| `testFailingHealthCheck()` | Tests health check exception handling | Deliberately throws RuntimeException in health check |
| `testHealthCheckWithDatabaseFailure()` | Tests database connectivity failure detection | Closes database connection to simulate failure |

**Console Output Examples:**
```
=== RUNNING INTENTIONAL HEALTH CHECK FAILURE TEST ===
INTENTIONAL FAILURE: Health check throwing simulated exception
SUCCESS: Health check failure was properly handled and reported
=== INTENTIONAL FAILURE TEST COMPLETED ===
```

### 2. Metrics Failure Tests

#### PeeGeeQMetricsTest

**Location:** `peegeeq-db/src/test/java/dev/mars/peegeeq/db/metrics/PeeGeeQMetricsTest.java`

| Test Method | Purpose | Failure Scenario |
|-------------|---------|------------------|
| `testMessageFailedMetrics()` | Tests failure metrics tracking | Records simulated message processing failures |
| `testMetricsWithDatabaseFailure()` | Tests metrics resilience during DB failure | Closes database connection during metrics operation |

**Console Output Examples:**
```
=== RUNNING INTENTIONAL MESSAGE FAILURE METRICS TEST ===
INTENTIONAL FAILURE: Recording simulated message failures for metrics testing
SUCCESS: Message failure metrics were properly recorded and tracked
=== INTENTIONAL FAILURE TEST COMPLETED ===
```

### 3. Consumer Group Resilience Tests

#### ConsumerGroupResilienceTest

**Location:** `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/ConsumerGroupResilienceTest.java`

| Test Method | Purpose | Failure Scenario |
|-------------|---------|------------------|
| `testConsumerFailureRecovery()` | Tests consumer failure and backup recovery | Creates consumers that fail on specific message patterns |
| `testInvalidMessageFilterHandling()` | Tests filter exception handling | Creates filters that throw exceptions |
| `testConsumerGroupStatisticsDuringFailures()` | Tests monitoring during failures | Simulates processing delays and failures |

**Console Output Examples:**
```
=== RUNNING INTENTIONAL CONSUMER FAILURE RECOVERY TEST ===
INTENTIONAL FAILURE: Adding consumer that will fail on specific messages
INTENTIONAL FAILURE: Sending messages that will trigger consumer failures
SUCCESS: Consumer failure recovery mechanisms worked correctly
=== INTENTIONAL FAILURE TEST COMPLETED ===
```

### 4. Circuit Breaker Failure Tests

#### CircuitBreakerManagerTest

**Location:** `peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/CircuitBreakerManagerTest.java`

| Test Method | Purpose | Failure Scenario |
|-------------|---------|------------------|
| `testFailedExecution()` | Tests single operation failure tracking | Throws RuntimeException in circuit breaker operation |
| `testCircuitBreakerOpening()` | Tests circuit breaker opening mechanism | Executes multiple failures to trigger opening |
| `testCircuitBreakerCallNotPermitted()` | Tests blocked calls when circuit is open | Forces circuit open and attempts operations |

**Console Output Examples:**
```
=== RUNNING INTENTIONAL CIRCUIT BREAKER FAILURE TEST ===
INTENTIONAL FAILURE: Throwing exception to test circuit breaker
SUCCESS: Circuit breaker properly tracked the intentional failure
=== INTENTIONAL FAILURE TEST COMPLETED ===
```

### 5. Dead Letter Queue Failure Tests

#### DeadLetterQueueManagerTest

**Location:** `peegeeq-db/src/test/java/dev/mars/peegeeq/db/deadletter/DeadLetterQueueManagerTest.java`

| Test Method | Purpose | Failure Scenario |
|-------------|---------|------------------|
| `testMoveMessageToDeadLetterQueue()` | Tests failed message handling | Simulates message processing failure and DLQ storage |
| `testReprocessDeadLetterMessage()` | Tests failed message reprocessing | Tests recovery from dead letter queue |

**Console Output Examples:**
```
=== RUNNING INTENTIONAL MESSAGE FAILURE DEAD LETTER QUEUE TEST ===
INTENTIONAL FAILURE: Moving message to dead letter queue due to simulated processing failure
SUCCESS: Failed message was properly moved to dead letter queue
=== INTENTIONAL FAILURE TEST COMPLETED ===
```

## Running Intentional Failure Tests

### Individual Test Execution

To run specific intentional failure tests:

```bash
# Health check failure test
mvn test -Dtest=HealthCheckManagerTest#testFailingHealthCheck -pl peegeeq-db

# Circuit breaker failure test
mvn test -Dtest=CircuitBreakerManagerTest#testFailedExecution -pl peegeeq-db

# Consumer resilience test
mvn test -Dtest=ConsumerGroupResilienceTest#testConsumerFailureRecovery -pl peegeeq-examples
```

### Full Test Suite

To run all tests including intentional failures:

```bash
mvn clean test
```

## Identifying Intentional Failures in Logs

When reviewing test logs, look for these indicators of intentional failures:

1. **Console markers:**
   - `=== RUNNING INTENTIONAL ... TEST ===`
   - `INTENTIONAL FAILURE:`
   - `=== INTENTIONAL FAILURE TEST COMPLETED ===`

2. **Log patterns:**
   - Exception stack traces preceded by "INTENTIONAL FAILURE" messages
   - Success confirmations after failure scenarios
   - Clear explanations of what failure is being simulated

3. **Test method names:**
   - Methods containing "Fail", "Failure", "Error" in their names
   - Methods testing "resilience", "recovery", "exception handling"

## Best Practices for Intentional Failure Tests

1. **Always document the failure scenario** in JavaDoc comments
2. **Use clear console output** to indicate intentional failures
3. **Verify that error handling works correctly** after simulating failures
4. **Test both the failure detection and recovery mechanisms**
5. **Ensure tests are deterministic** and don't rely on timing issues
6. **Clean up resources** properly after failure simulation

## Troubleshooting

If intentional failure tests are failing unexpectedly:

1. Check that the failure simulation is working correctly
2. Verify that error handling mechanisms are properly implemented
3. Ensure that cleanup code runs even after simulated failures
4. Check for resource leaks or connection issues
5. Review test timing and ensure adequate wait periods for async operations

## Contributing

When adding new intentional failure tests:

1. Follow the established patterns for console output and documentation
2. Add entries to this documentation file
3. Ensure tests verify both failure detection and recovery
4. Use descriptive test method names that indicate failure scenarios
5. Include comprehensive JavaDoc comments explaining the test purpose
