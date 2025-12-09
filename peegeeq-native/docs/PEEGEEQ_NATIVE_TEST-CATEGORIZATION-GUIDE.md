# PeeGeeQ Native Test Categorization Guide

## Overview

This guide explains how to use the test categorization system in peegeeq-native to run different types of tests efficiently. The system uses JUnit 5 `@Tag` annotations and Maven profiles to organize tests by execution time and purpose.

## Test Categories

### 🚀 CORE Tests
- **Purpose**: Fast unit tests for critical functionality
- **Target Time**: < 30 seconds total
- **What to include**:
  - Configuration objects (ConsumerConfig, ConsumerMode)
  - Message objects (PgNativeMessage)
  - Utility classes (EmptyReadStream)
  - Builder patterns and validation
  - Enum behavior and constants
- **What NOT to include**: TestContainers, database connections, external dependencies

### 🔧 INTEGRATION Tests
- **Purpose**: Tests with real infrastructure
- **Target Time**: 1-3 minutes total
- **What to include**:
  - Native queue producer/consumer operations
  - LISTEN/NOTIFY functionality
  - Database schema interactions
  - Connection management
  - TestContainers-based tests
- **What NOT to include**: Performance benchmarks, long-running tests

### ⚡ PERFORMANCE Tests
- **Purpose**: Performance, load, and throughput tests
- **Target Time**: 2-5 minutes
- **What to include**:
  - Consumer mode performance comparisons
  - High-frequency message processing
  - Concurrent consumer testing
  - Throughput benchmarks
- **What NOT to include**: Basic functionality tests

### 🐌 SLOW Tests
- **Purpose**: Long-running comprehensive tests
- **Target Time**: 5+ minutes
- **What to include**:
  - Extended reliability tests
  - Multi-consumer coordination tests
  - Resource leak detection tests
  - Fault injection tests

### 🔥 SMOKE Tests
- **Purpose**: Ultra-fast basic verification
- **Target Time**: < 10 seconds total
- **What to include**:
  - Basic configuration loading
  - Simple object creation
  - Basic validation

### ⚠️ FLAKY Tests
- **Purpose**: Tests that may be unstable
- **What to include**:
  - Tests with timing dependencies
  - Tests sensitive to system load
  - Network-dependent tests

## Maven Execution Commands

### Development Workflow (Recommended)
```bash
# Fast feedback during development (DEFAULT)
mvn test                           # Runs CORE tests only (~30 seconds)
mvn test -Pcore-tests             # Same as above (explicit)

# Verify integration after changes
mvn test -Pintegration-tests      # Integration tests (~1-3 minutes)

# Quick smoke test
mvn test -Psmoke-tests           # Ultra-fast verification (~10 seconds)
```

### Comprehensive Testing
```bash
# All tests except flaky
mvn test -Pall-tests             # CORE + INTEGRATION + PERFORMANCE + SLOW

# Everything including flaky tests
mvn test -Pfull-tests            # All categories

# Performance testing only
mvn test -Pperformance-tests     # Performance benchmarks only
```

### Custom Combinations
```bash
# Core + Integration (common development workflow)
mvn test -Dgroups="core,integration"

# Exclude slow and flaky tests
mvn test -DexcludedGroups="slow,flaky"

# Only specific categories
mvn test -Dgroups="core,smoke"
```

## How to Categorize Your Tests

### Step 1: Import the Categories
```java
import org.junit.jupiter.api.Tag;
import dev.mars.peegeeq.test.categories.TestCategories;
```

### Step 2: Add Tags to Test Classes
```java
@Tag(TestCategories.CORE)
public class ConsumerConfigTest {
    // Fast unit tests
}

@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class NativeQueueIntegrationTest {
    // Integration tests with PostgreSQL
}

@Tag(TestCategories.PERFORMANCE)
@Testcontainers
public class ConsumerModePerformanceTest {
    // Performance benchmarks
}
```

### Step 3: Multiple Tags (if needed)
```java
@Tag(TestCategories.CORE)
@Tag(TestCategories.SMOKE)
public class HelloWorldTest {
    // Basic smoke test that's also core functionality
}
```

## Decision Tree for Categorization

```
Does your test use TestContainers or external dependencies?
├─ YES → Is it a performance/load test?
│  ├─ YES → PERFORMANCE
│  └─ NO → Does it take > 3 minutes?
│     ├─ YES → SLOW
│     └─ NO → INTEGRATION
└─ NO → Is it basic validation/smoke test?
   ├─ YES → CORE + SMOKE
   └─ NO → CORE
```

## Benefits

### 🚀 **Fast Development Feedback**
- Core tests run in ~30 seconds
- No waiting for TestContainers startup
- Immediate validation of business logic changes

### 🎯 **Targeted Testing**
- Run only relevant tests for your changes
- Skip expensive tests during development
- Full testing available when needed

### 📈 **Scalable CI/CD**
- Different pipeline stages can run different test categories
- Parallel execution optimized per category
- Fast feedback with comprehensive coverage

## Migration Strategy

### Phase 1: Start Using (Immediate)
1. Use `mvn test` for daily development (core tests only)
2. Use `mvn test -Pintegration-tests` before commits
3. Use `mvn test -Pall-tests` for releases

### Phase 2: Gradual Categorization
1. Add `@Tag` annotations to new tests
2. Categorize existing tests as you encounter them
3. Focus on obvious categories first:
   - TestContainer tests → INTEGRATION
   - Simple unit tests → CORE
   - Performance tests → PERFORMANCE

### Phase 3: Full Migration
1. Categorize all remaining tests
2. Update CI/CD pipelines to use profiles
3. Monitor execution times and adjust categories

## Troubleshooting

### Tests Not Running
- Check that `@Tag` annotation is imported correctly
- Verify Maven profile is active: `mvn help:active-profiles`
- Ensure test class is public and methods are public/package-private

### Wrong Execution Time
- Move tests between categories based on actual execution time
- Consider splitting large test classes
- Use `@Disabled` for temporarily broken tests instead of FLAKY

### Profile Issues
- Use `mvn test -X` for debug output
- Check that Surefire plugin version is 3.2.5+
- Verify JUnit 5 dependencies are correct

## Examples

See the following test files for categorization examples:
- `ConsumerConfigTest.java` - CORE
- `PgNativeMessageTest.java` - CORE  
- `EmptyReadStreamTest.java` - CORE
- `HelloWorldTest.java` - CORE + SMOKE
- `NativeQueueIntegrationTest.java` - INTEGRATION
- `ConsumerModePerformanceTest.java` - PERFORMANCE
