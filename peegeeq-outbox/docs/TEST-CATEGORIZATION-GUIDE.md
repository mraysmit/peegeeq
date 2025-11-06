# PeeGeeQ Outbox Test Categorization Guide

## Overview

This module uses JUnit 5 `@Tag` annotations to categorize tests for selective execution through Maven profiles. This enables fast development feedback cycles while maintaining comprehensive test coverage.

## Test Categories

### 🚀 CORE Tests (Default)
**Purpose**: Fast unit tests for immediate development feedback  
**Execution Time**: < 30 seconds total, < 1 second per test  
**Dependencies**: No external services, mocked dependencies only  
**Parallel Execution**: Methods (4 threads)  

**Examples**:
- `AsyncRetryMechanismTest` - Retry logic unit tests
- `FilterErrorHandlingTest` - Circuit breaker logic tests  
- `CircuitBreakerRecoveryTest` - State transition tests

**Usage**:
```java
@Test
@Tag(TestCategories.CORE)
void testRetryLogic() { ... }
```

### 🔧 INTEGRATION Tests
**Purpose**: Tests with real infrastructure dependencies  
**Execution Time**: 1-3 minutes total  
**Dependencies**: TestContainers with PostgreSQL  
**Parallel Execution**: Classes (2 threads)  

**Examples**:
- `OutboxBasicTest` - End-to-end outbox functionality
- `OutboxConfigurationIntegrationTest` - Configuration validation
- `AutomaticTransactionManagementExampleTest` - Transaction management

**Usage**:
```java
@Test
@Tag(TestCategories.INTEGRATION)
@Testcontainers
void testWithDatabase() { ... }
```

### ⚡ PERFORMANCE Tests
**Purpose**: Load and throughput benchmarks  
**Execution Time**: 2-5 minutes  
**Dependencies**: TestContainers, may require specific hardware  
**Parallel Execution**: None (sequential to avoid resource contention)  

**Examples**:
- `PerformanceBenchmarkTest` - Throughput comparisons
- `OutboxPerformanceTest` - Load testing

**Usage**:
```java
@Test
@Tag(TestCategories.PERFORMANCE)
void testThroughput() { ... }
```

### 💨 SMOKE Tests
**Purpose**: Ultra-fast basic verification  
**Execution Time**: < 10 seconds total  
**Dependencies**: None  
**Parallel Execution**: Methods (8 threads)  

**Usage**:
```java
@Test
@Tag(TestCategories.SMOKE)
void testBasicConfiguration() { ... }
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
mvn test                    # Runs CORE tests only (~30 seconds)
```

### Specific Categories
```bash
mvn test -Pintegration-tests    # Integration tests (~3 minutes)
mvn test -Pperformance-tests    # Performance tests (~5 minutes)
mvn test -Psmoke-tests          # Smoke tests (~10 seconds)
mvn test -Pslow-tests           # Slow tests (5+ minutes)
```

### Combined Execution
```bash
mvn test -Pall-tests           # All tests except flaky (~8 minutes)
```

## Development Workflow

### 🔄 Daily Development
```bash
mvn test                       # Fast feedback (30 seconds)
```

### 🚀 Pre-Commit
```bash
mvn test -Pintegration-tests   # Comprehensive validation (3 minutes)
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
- Mocked database/network interactions
- Configuration validation logic
- Business logic tests
- < 1 second execution time per test

### When to use INTEGRATION
- Tests requiring TestContainers
- Database schema validation
- End-to-end workflow tests
- Real PostgreSQL interactions
- Consumer/Producer integration tests

### When to use PERFORMANCE
- Throughput measurements
- Latency benchmarks
- Resource usage validation
- Load testing scenarios
- Concurrent processing tests

### When to use SMOKE
- Basic sanity checks
- Configuration loading tests
- Simple object creation
- Constant validation
- Pre-commit hook candidates

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

| Category | Execution Time | Test Count | Examples |
|----------|---------------|------------|----------|
| CORE | ~30 seconds | 4+ | AsyncRetryMechanismTest |
| INTEGRATION | ~3 minutes | 3+ | OutboxBasicTest |
| PERFORMANCE | ~5 minutes | 1+ | PerformanceBenchmarkTest |
| ALL | ~8 minutes | 8+ | Combined execution |

## Benefits

✅ **Fast Development Feedback**: Core tests provide sub-30-second feedback  
✅ **Selective Testing**: Run only relevant tests for specific changes  
✅ **CI/CD Optimization**: Parallel pipeline stages with appropriate timeouts  
✅ **Resource Efficiency**: Avoid unnecessary TestContainer startup for unit tests  
✅ **Clear Organization**: Explicit categorization makes test purpose obvious  
