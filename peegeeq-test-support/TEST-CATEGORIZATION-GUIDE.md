# Test Categorization Guide - peegeeq-test-support

## Overview

The peegeeq-test-support module uses JUnit 5 `@Tag` annotations with Maven profiles to enable selective test execution. This system allows developers to run different subsets of tests based on their needs, dramatically improving development feedback cycles.

## Test Categories

### 🚀 CORE Tests (Default)
**Target: <30 seconds total, <1 second per test**
- **Purpose**: Fast unit tests for daily development
- **Dependencies**: Mocked dependencies only, no external infrastructure
- **Examples**: Constants validation, utility class tests, metrics creation
- **Parallel Execution**: Methods (4 threads)

**Current CORE tests:**
- `PostgreSQLTestConstantsTest` - TestContainer factory constants validation
- `PerformanceMetricsCollectorTest` - Metrics collection with mocked dependencies
- `ConsumerModeTestScenarioTest` - Consumer scenario configuration tests
- `PerformanceComparisonTest` - Performance comparison logic tests
- `PerformanceSnapshotTest` - Performance snapshot creation tests

### 🔧 INTEGRATION Tests
**Target: 1-3 minutes total**
- **Purpose**: Tests with TestContainers and real infrastructure
- **Dependencies**: Real PostgreSQL via TestContainers, database connectivity
- **Examples**: TestContainer factory tests, base test class validation, database connectivity
- **Parallel Execution**: Classes (2 threads)

**Current INTEGRATION tests:**
- `PeeGeeQTestBaseTest` - Base test class functionality with real PostgreSQL
- `ParameterizedPerformanceTestBaseTest` - Parameterized performance test base validation
- `PeeGeeQTestContainerFactoryTest` - TestContainer factory with real containers

### ⚡ PERFORMANCE Tests
**Target: 2-5 minutes total**
- **Purpose**: Load and throughput tests with hardware profiling
- **Dependencies**: Real infrastructure, resource monitoring, hardware profiling
- **Examples**: Hardware profiling, resource monitoring, performance metrics collection
- **Parallel Execution**: None (1 thread for accurate measurements)

**Current PERFORMANCE tests:**
- `HardwareProfilingIntegrationTest` - Complete hardware profiling pipeline
- `ConsumerModePerformanceTestBaseTest` - Consumer mode performance testing patterns

### 🐌 SLOW Tests
**Target: 5+ minutes**
- **Purpose**: Long-running comprehensive tests and demos
- **Dependencies**: Complex scenarios, stress testing, comprehensive validation
- **Examples**: Comprehensive performance demos, stress tests
- **Parallel Execution**: None (1 thread)

**Current SLOW tests:**
- `ParameterizedPerformanceDemoTest` - Comprehensive performance demonstration across profiles

## Maven Profiles

### Daily Development
```bash
# Default profile - runs CORE tests only
mvn test                           # ~10 seconds
mvn test -Pcore-tests             # Explicit core tests
```

### Pre-commit Validation
```bash
# Integration tests with real infrastructure
mvn test -Pintegration-tests       # ~2-3 minutes
```

### Performance Benchmarking
```bash
# Performance tests with hardware profiling
mvn test -Pperformance-tests       # ~5 minutes
```

### Comprehensive Testing
```bash
# All tests except flaky ones
mvn test -Pall-tests              # ~10 minutes

# Long-running comprehensive tests
mvn test -Pslow-tests             # ~15+ minutes
```

### Helper Script
```bash
# Use the convenient helper script
./run-tests.sh core               # Core tests
./run-tests.sh integration        # Integration tests
./run-tests.sh performance        # Performance tests
./run-tests.sh slow               # Slow tests
./run-tests.sh all                # All tests
```

## Test Categorization Guidelines

### When to use CORE
- ✅ Constants validation and utility class tests
- ✅ Metrics creation and configuration tests
- ✅ Scenario building and validation logic
- ✅ Performance comparison calculations
- ✅ Tests that complete in under 1 second
- ❌ Tests requiring TestContainers or external infrastructure

### When to use INTEGRATION
- ✅ TestContainer factory validation
- ✅ Base test class functionality with real PostgreSQL
- ✅ Database connectivity and setup validation
- ✅ Container configuration and lifecycle tests
- ❌ Performance benchmarking or hardware profiling

### When to use PERFORMANCE
- ✅ Hardware profiling and resource monitoring
- ✅ Performance metrics collection with real workloads
- ✅ Consumer mode performance testing patterns
- ✅ Throughput and latency measurements
- ❌ Simple unit tests or basic validation

### When to use SLOW
- ✅ Comprehensive performance demonstrations
- ✅ Multi-profile comparison tests
- ✅ Stress testing and long-running scenarios
- ✅ Tests that take 5+ minutes to complete
- ❌ Regular development feedback tests

## Performance Results

### Before Categorization
- **All tests**: ~1 minute 15 seconds
- **Daily development feedback**: 75+ seconds (too slow)

### After Categorization
- **Core tests**: ~10 seconds (7.5x faster)
- **Integration tests**: ~2-3 minutes (focused infrastructure testing)
- **Performance tests**: ~5 minutes (comprehensive benchmarking)
- **All tests**: ~10 minutes (complete validation)

## Adding New Tests

### Step 1: Determine Category
1. **CORE**: Fast unit test with mocked dependencies?
2. **INTEGRATION**: Requires TestContainers or real infrastructure?
3. **PERFORMANCE**: Measures performance or includes hardware profiling?
4. **SLOW**: Long-running comprehensive test?

### Step 2: Add Tag Annotation
```java
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;

@Tag(TestCategories.CORE)  // or INTEGRATION, PERFORMANCE, SLOW
class YourNewTest {
    // test methods
}
```

### Step 3: Verify Execution
```bash
# Test your specific category
mvn test -Pcore-tests              # For CORE tests
mvn test -Pintegration-tests       # For INTEGRATION tests
mvn test -Pperformance-tests       # For PERFORMANCE tests
mvn test -Pslow-tests              # For SLOW tests
```

## Test Support Module Specific Notes

### Hardware Profiling Tests
- Always use `@Tag(TestCategories.PERFORMANCE)`
- Include resource monitoring and hardware profile capture
- Expect longer execution times for accurate measurements

### TestContainer Factory Tests
- Use `@Tag(TestCategories.INTEGRATION)`
- Test with real PostgreSQL containers
- Validate different performance profiles

### Utility and Constants Tests
- Use `@Tag(TestCategories.CORE)`
- Mock all external dependencies
- Focus on fast feedback for daily development

### Performance Demo Tests
- Use `@Tag(TestCategories.SLOW)` for comprehensive demos
- Use `@Tag(TestCategories.PERFORMANCE)` for focused benchmarks
- Include detailed logging and metrics collection

This categorization system transforms peegeeq-test-support from a 75+ second feedback cycle to a 10-second development experience while maintaining comprehensive test coverage for all scenarios.
