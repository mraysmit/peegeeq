# Test Categorization Guide - peegeeq-examples

This document provides comprehensive guidance for using the test categorization system in the peegeeq-examples module.

## Overview

The peegeeq-examples module uses JUnit 5 `@Tag` annotations with Maven profiles to enable selective test execution. This system allows developers to run different subsets of tests based on their needs, from ultra-fast smoke tests to comprehensive performance testing.

## Test Categories

### 🚀 SMOKE Tests
**Target**: < 30 seconds total, < 10 seconds per test  
**Purpose**: Ultra-fast basic verification  
**Dependencies**: Minimal infrastructure, placeholder tests  

**Examples**:
- `TransactionalOutboxAnalysisTest` - Placeholder test for outbox analysis
- Basic configuration validation
- Quick utility function checks

**Usage**:
```java
@Tag(TestCategories.SMOKE)
class TransactionalOutboxAnalysisTest {
    @Test
    void testPlaceholderAnalysis() {
        // Quick placeholder validation
    }
}
```

### ⚡ CORE Tests
**Target**: < 30 seconds total, < 1 second per test  
**Purpose**: Fast unit tests for daily development  
**Dependencies**: Mocked only, no external infrastructure  

**Examples**:
- `CloudEventsObjectMapperTest` - ObjectMapper validation with CloudEvents
- Configuration validation tests
- Utility class tests
- JSON serialization tests

**Usage**:
```java
@Tag(TestCategories.CORE)
class CloudEventsObjectMapperTest {
    @Test
    void testCloudEventsJacksonIntegration() {
        // Fast unit test with ObjectMapper
    }
}
```

### 🔧 INTEGRATION Tests
**Target**: 5-10 minutes total  
**Purpose**: Tests with TestContainers and real infrastructure  
**Dependencies**: Real databases, TestContainers, infrastructure setup  

**Examples**:
- `SimpleNativeQueueTest` - Native queue with PostgreSQL
- `BiTemporalEventStoreExampleTest` - Bitemporal event store integration
- `PeeGeeQExampleTest` - Infrastructure lifecycle tests
- Database integration tests
- Queue operation tests

**Usage**:
```java
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class SimpleNativeQueueTest {
    @Test
    void testNativeQueueWithPostgreSQL() {
        // Test with real PostgreSQL database
    }
}
```

### 🚀 PERFORMANCE Tests
**Target**: 10-15 minutes total  
**Purpose**: Load and throughput tests  
**Dependencies**: Real infrastructure, performance measurement  

**Examples**:
- `HighFrequencyProducerConsumerTest` - High-throughput message processing
- Load testing scenarios
- Throughput benchmarks
- Concurrent access tests

**Usage**:
```java
@Tag(TestCategories.PERFORMANCE)
@Testcontainers
class HighFrequencyProducerConsumerTest {
    @Test
    void testHighThroughputMessageProcessing() {
        // Performance benchmarks with real infrastructure
    }
}
```

### 🐌 SLOW Tests
**Target**: 15+ minutes  
**Purpose**: Long-running comprehensive tests  
**Dependencies**: Extended infrastructure, high resource usage  

**Examples**:
- Comprehensive integration scenarios
- Stress tests with large datasets
- Long-running performance tests
- Complex business scenarios

### 🚨 FLAKY Tests
**Target**: Excluded from regular runs  
**Purpose**: Tests with connection issues or environment dependencies  
**Dependencies**: Manual configuration, environment-specific setup  

**Examples**:
- Tests with connection errors
- Environment-dependent tests
- Timing-sensitive tests

## Maven Profiles

### Daily Development
```bash
# Core tests only (default) - ~30 seconds
mvn test
mvn test -Pcore-tests
```

### Ultra-Fast Validation
```bash
# Smoke tests only - ~30 seconds
mvn test -Psmoke-tests
```

### Integration Testing
```bash
# Integration tests - ~5-10 minutes
mvn test -Pintegration-tests
```

### Performance Benchmarks
```bash
# Performance tests - ~10-15 minutes
mvn test -Pperformance-tests
```

### Comprehensive Testing
```bash
# All tests except flaky - ~20+ minutes
mvn test -Pall-tests
```

## Categorization Guidelines

### Examples Module Specific Rules

**SMOKE Category**:
- Placeholder tests
- Basic validation tests
- Ultra-fast verification
- Tests with minimal setup

**CORE Category**:
- ObjectMapper tests (CloudEvents, JSON)
- Configuration validation
- Utility class tests
- Mock-based unit tests
- No external dependencies

**INTEGRATION Category**:
- TestContainers tests
- Database integration tests
- Queue operation tests
- Infrastructure lifecycle tests
- Real PostgreSQL validation

**PERFORMANCE Category**:
- High-frequency message processing
- Throughput benchmarks
- Load testing scenarios
- Concurrent access validation
- Performance measurement tests

**SLOW Category**:
- Comprehensive integration tests
- Stress tests with large datasets
- Long-running scenarios
- Complex business workflows

**FLAKY Category**:
- Tests with connection errors
- Environment-dependent tests
- Network-dependent tests
- Timing-sensitive tests

## Development Workflow

### 1. Daily Development (Quick Feedback)
```bash
# Run core tests for quick feedback
mvn test                    # ~30 seconds
```

### 2. Ultra-Fast Validation
```bash
# Run smoke tests for immediate feedback
mvn test -Psmoke-tests     # ~30 seconds
```

### 3. Integration Validation
```bash
# Test with real infrastructure
mvn test -Pintegration-tests    # ~5-10 minutes
```

### 4. Performance Validation
```bash
# Performance benchmarks
mvn test -Pperformance-tests    # ~10-15 minutes
```

### 5. CI/CD Pipeline
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
./run-tests.sh smoke         # Smoke tests (~30 seconds)
./run-tests.sh core          # Core tests (~30 seconds)
./run-tests.sh integration   # Integration tests (~5-10 minutes)
./run-tests.sh performance   # Performance tests (~10-15 minutes)
./run-tests.sh all           # All tests (~20+ minutes)
```

## Current Test Files Status

### Categorized Tests
- **CORE (1 file)**: `CloudEventsObjectMapperTest` - ObjectMapper validation
- **SMOKE (1 file)**: `TransactionalOutboxAnalysisTest` - Placeholder test
- **INTEGRATION (3 files)**: 
  - `SimpleNativeQueueTest` - Native queue integration
  - `BiTemporalEventStoreExampleTest` - Bitemporal event store
  - `PeeGeeQExampleTest` - Infrastructure lifecycle
- **PERFORMANCE (1 file)**: `HighFrequencyProducerConsumerTest` - High-throughput testing

### Remaining Files to Categorize
The module contains approximately 30+ test files that need categorization. These include:
- Native queue tests (various scenarios)
- Outbox pattern tests
- Bitemporal tests
- Fund custody tests
- Pattern demonstration tests

## Measured Performance

Based on actual measurements:

| Profile | Execution Time | Use Case |
|---------|---------------|----------|
| smoke-tests | ~5.3 seconds | Ultra-fast validation |
| core-tests | ~8.2 seconds | Daily development |
| integration-tests | ~TBD minutes | Infrastructure validation |
| performance-tests | ~TBD minutes | Performance benchmarks |
| all-tests | ~TBD minutes | Comprehensive testing |

## Best Practices

1. **Keep SMOKE tests minimal** - Placeholder and basic validation only
2. **Use CORE for daily development** - Fast feedback with mocked dependencies
3. **Reserve INTEGRATION for real infrastructure** - TestContainers and database tests
4. **Measure PERFORMANCE regularly** - High-throughput and load testing
5. **Use appropriate timeouts** - 30s for CORE, 10m for INTEGRATION, 15m for PERFORMANCE
6. **Mock external dependencies in CORE** - Keep tests fast and reliable
7. **Document performance expectations** - Update this guide with actual timings

## Connection Error Resolution

If you encounter connection errors:
1. Check if tests are properly categorized with `@Tag` annotations
2. Verify TestContainers setup for integration tests
3. Ensure proper test isolation and cleanup
4. Check system properties configuration
5. Consider moving problematic tests to FLAKY category temporarily

## Troubleshooting

### Tests Not Running
- Check Maven profile is active: `mvn help:active-profiles`
- Verify test files have proper `@Tag` annotations
- Ensure JUnit 5 dependencies are correct

### Connection Errors
- Verify TestContainers setup
- Check database connectivity
- Ensure proper test isolation
- Consider categorizing as FLAKY if environment-dependent

### Slow Performance
- Check if tests are properly categorized
- Verify parallel execution settings
- Monitor system resources during test execution

---

*This guide is maintained as part of the peegeeq-examples module. Update performance measurements and examples as categorization progresses.*
