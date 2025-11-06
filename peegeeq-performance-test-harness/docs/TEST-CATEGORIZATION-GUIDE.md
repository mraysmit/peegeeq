# Test Categorization Guide - peegeeq-performance-test-harness

This document provides comprehensive guidance for using the test categorization system in the peegeeq-performance-test-harness module.

## Overview

The peegeeq-performance-test-harness module uses JUnit 5 `@Tag` annotations with Maven profiles to enable selective test execution. This system allows developers to run different subsets of performance tests based on their needs, from quick smoke tests to comprehensive stress testing.

## Test Categories

### 🚀 SMOKE Tests
**Target**: < 10 seconds total, < 5 seconds per test  
**Purpose**: Ultra-fast basic verification  
**Dependencies**: Mocked only, no external infrastructure  

**Examples**:
- Configuration loading and validation
- Test suite enumeration
- Basic harness setup verification

**Usage**:
```java
@Tag(TestCategories.SMOKE)
class PerformanceConfigTest {
    @Test
    void testConfigurationLoading() {
        // Fast configuration validation without execution
    }
}
```

### ⚡ PERFORMANCE Tests
**Target**: 2-5 minutes total  
**Purpose**: Standard performance tests and benchmarks  
**Dependencies**: Real infrastructure, performance harness execution  

**Examples**:
- `PerformanceTestHarnessIntegrationTest` - Complete performance test harness execution
- Standard benchmark validation
- Performance metrics collection

**Usage**:
```java
@Tag(TestCategories.PERFORMANCE)
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
class PerformanceTestHarnessIntegrationTest {
    @Test
    void testCompletePerformanceTestExecution() {
        // Full performance test harness with all modules
    }
}
```

### 🔧 INTEGRATION Tests
**Target**: 1-3 minutes total  
**Purpose**: Tests with TestContainers and real infrastructure  
**Dependencies**: Real databases, external services, infrastructure setup  

**Examples**:
- Database integration validation
- Module integration testing
- Infrastructure setup and teardown

### 🐌 SLOW Tests
**Target**: 5+ minutes  
**Purpose**: Long-running comprehensive tests and stress testing  
**Dependencies**: Extended infrastructure, high resource usage  

**Examples**:
- Extended stress tests
- Comprehensive load tests
- Full benchmark suites with multiple scenarios

**Usage**:
```java
@Tag(TestCategories.SLOW)
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
class StressTestSuite {
    @Test
    void testLongRunningStressTest() {
        // Extended stress testing with high load
    }
}
```

### 🚨 FLAKY Tests
**Target**: Excluded from regular runs  
**Purpose**: Tests requiring specific environment setup  
**Dependencies**: Manual configuration, environment-specific setup  

**Examples**:
- Environment-dependent tests
- Timing-sensitive tests
- Tests requiring specific hardware configuration

## Maven Profiles

### Daily Development
```bash
# Smoke tests only (default) - ~5 seconds
mvn test
mvn test -Psmoke-tests
```

### Performance Benchmarks
```bash
# Standard performance tests - ~3-5 minutes
mvn test -Pperformance-tests
```

### Extended Testing
```bash
# Long-running stress tests - ~15+ minutes
mvn test -Pslow-tests
```

### Comprehensive Testing
```bash
# All tests except flaky - ~20+ minutes
mvn test -Pall-tests
```

### Legacy Compatibility
```bash
# Legacy performance profile - ~5 minutes
mvn test -Pperformance
```

## Categorization Guidelines

### Performance Test Harness Module Specific Rules

**SMOKE Category**:
- Configuration loading and validation
- Test suite enumeration
- Basic harness setup verification
- Utility function validation

**PERFORMANCE Category**:
- Standard performance test harness execution
- Benchmark validation (5-10 minutes)
- Performance metrics collection
- Module performance integration

**SLOW Category**:
- Extended stress tests (15+ minutes)
- Comprehensive load tests
- Full benchmark suites
- Resource-intensive scenarios

**INTEGRATION Category**:
- Database integration validation
- Module integration testing
- Infrastructure setup and teardown
- External service integration

**FLAKY Category**:
- Environment-dependent tests
- Timing-sensitive tests
- Hardware-specific tests
- Manual configuration required

## Development Workflow

### 1. Daily Development (Quick Validation)
```bash
# Run smoke tests for quick feedback
mvn test                    # ~5 seconds
```

### 2. Performance Validation
```bash
# Standard performance benchmarks
mvn test -Pperformance-tests    # ~3-5 minutes
```

### 3. Comprehensive Testing
```bash
# Extended stress testing
mvn test -Pslow-tests          # ~15+ minutes
```

### 4. CI/CD Pipeline
```bash
# Full test suite validation
mvn test -Pall-tests           # ~20+ minutes
```

## Helper Script

Use the provided helper script for convenient test execution:

```bash
# Make executable (first time only)
chmod +x run-tests.sh

# Run different test categories
./run-tests.sh smoke         # Smoke tests (~5 seconds)
./run-tests.sh performance   # Performance tests (~3-5 minutes)
./run-tests.sh slow          # Slow tests (~15+ minutes)
./run-tests.sh all           # All tests (~20+ minutes)
```

## Performance Test Configuration

The performance test harness supports various configuration options through system properties:

### Standard Performance Tests
```bash
mvn test -Pperformance-tests
# Uses: 300s duration, 10 threads
```

### Load Testing
```bash
mvn test -Pload-test
# Uses: 1800s duration, 50 threads
```

### Stress Testing
```bash
mvn test -Pstress-test
# Uses: 3600s duration, 100 threads
```

## Measured Performance

Based on actual measurements:

| Profile | Execution Time | Use Case |
|---------|---------------|----------|
| smoke-tests | ~5 seconds | Daily development, quick validation |
| performance-tests | ~3-5 minutes | Standard benchmarks |
| slow-tests | ~15+ minutes | Comprehensive stress testing |
| all-tests | ~20+ minutes | CI/CD comprehensive validation |

## Best Practices

1. **Keep SMOKE tests minimal** - Configuration and setup validation only
2. **Use system properties for performance control** - Enable with `peegeeq.performance.tests=true`
3. **Separate standard from stress tests** - PERFORMANCE vs SLOW categories
4. **Monitor resource usage** - Performance tests can be resource-intensive
5. **Use appropriate timeouts** - 5m for PERFORMANCE, 15m for SLOW
6. **Document performance expectations** - Update this guide with actual timings
7. **Consider CI/CD resource limits** - SLOW tests may need dedicated runners

## Troubleshooting

### Tests Not Running
- Check if `peegeeq.performance.tests=true` system property is set
- Verify Maven profile is active: `mvn help:active-profiles`
- Ensure test files have proper `@Tag` annotations

### Performance Tests Failing
- Check available system resources (CPU, memory)
- Verify database connectivity and performance
- Monitor for resource contention with other processes
- Consider adjusting test duration and thread counts

### Inconsistent Results
- Run tests on dedicated hardware when possible
- Warm up JVM before measurements
- Account for system load and background processes
- Use multiple test runs for statistical significance

---

*This guide is maintained as part of the peegeeq-performance-test-harness module. Update performance measurements and examples as the codebase evolves.*
