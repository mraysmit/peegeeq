# Test Categorization Guide - peegeeq-api

This document provides comprehensive guidance for using the test categorization system in the peegeeq-api module.

## Overview

The peegeeq-api module uses JUnit 5 `@Tag` annotations with Maven profiles to enable selective test execution. This system allows developers to run different subsets of tests based on their needs, from ultra-fast smoke tests to comprehensive integration testing.

## Test Categories

### 🚀 SMOKE Tests
**Target**: < 10 seconds total, < 5 seconds per test  
**Purpose**: Ultra-fast basic verification  
**Dependencies**: Mocked only, no external infrastructure  

**Examples**:
- `MessageTest` - Basic placeholder and interface validation
- Quick API constant validation
- Basic utility function checks

**Usage**:
```java
@Tag(TestCategories.SMOKE)
class MessageTest {
    @Test
    void testMessageImplementation() {
        // Quick placeholder or basic validation
    }
}
```

### ⚡ CORE Tests
**Target**: < 30 seconds total, < 1 second per test  
**Purpose**: Fast unit tests for daily development  
**Dependencies**: Mocked only, no external infrastructure  

**Examples**:
- `MessageFilterDebugTest` - Filter logic with mock Message objects
- Interface validation tests
- Utility class tests
- API constant tests

**Usage**:
```java
@Tag(TestCategories.CORE)
class MessageFilterDebugTest {
    @Test
    void testByHeaderInWithNullValues() {
        // Fast unit test with mock Message objects
    }
}
```

### 🔧 INTEGRATION Tests
**Target**: 1-3 minutes total  
**Purpose**: Tests with TestContainers and real infrastructure  
**Dependencies**: Real databases, external services, infrastructure setup  

**Examples**:
- API implementation validation with PostgreSQL
- Message processing integration tests
- Database schema validation
- Real service integration

**Usage**:
```java
@Tag(TestCategories.INTEGRATION)
class MessageApiIntegrationTest {
    @Test
    void testMessageProcessingWithDatabase() {
        // Test with real PostgreSQL database
    }
}
```

### 🚀 PERFORMANCE Tests
**Target**: 2-5 minutes total  
**Purpose**: Load and throughput tests  
**Dependencies**: Real infrastructure, performance measurement  

**Examples**:
- Message filter performance tests
- API throughput benchmarks
- Large dataset processing
- Concurrent access tests

### 🐌 SLOW Tests
**Target**: 5+ minutes  
**Purpose**: Long-running comprehensive tests  
**Dependencies**: Extended infrastructure, high resource usage  

**Examples**:
- Comprehensive API validation
- Stress tests with large datasets
- Long-running integration scenarios
- Memory leak detection

### 🚨 FLAKY Tests
**Target**: Excluded from regular runs  
**Purpose**: Tests requiring specific environment setup  
**Dependencies**: Manual configuration, environment-specific setup  

**Examples**:
- Environment-dependent tests
- Timing-sensitive tests
- Network-dependent tests

## Maven Profiles

### Daily Development
```bash
# Core tests only (default) - ~2 seconds
mvn test
mvn test -Pcore-tests
```

### Ultra-Fast Validation
```bash
# Smoke tests only - ~1 second
mvn test -Psmoke-tests
```

### Integration Testing
```bash
# Integration tests - ~1-3 minutes
mvn test -Pintegration-tests
```

### Performance Benchmarks
```bash
# Performance tests - ~2-5 minutes
mvn test -Pperformance-tests
```

### Comprehensive Testing
```bash
# All tests except flaky - ~5 minutes
mvn test -Pall-tests
```

## Categorization Guidelines

### API Module Specific Rules

**SMOKE Category**:
- Placeholder tests
- Basic interface validation
- Quick constant checks
- Ultra-fast verification

**CORE Category**:
- Message filter logic tests
- Interface implementation tests
- Utility class validation
- API constant tests
- Mock-based unit tests

**INTEGRATION Category**:
- Database integration tests
- Real PostgreSQL validation
- API implementation with infrastructure
- Service integration tests

**PERFORMANCE Category**:
- Message processing throughput
- Filter performance benchmarks
- API response time tests
- Concurrent access validation

**SLOW Category**:
- Comprehensive API validation
- Stress tests with large datasets
- Memory and resource tests
- Long-running scenarios

**FLAKY Category**:
- Environment-dependent tests
- Network-dependent tests
- Timing-sensitive tests

## Development Workflow

### 1. Daily Development (Quick Feedback)
```bash
# Run core tests for quick feedback
mvn test                    # ~2 seconds
```

### 2. Ultra-Fast Validation
```bash
# Run smoke tests for immediate feedback
mvn test -Psmoke-tests     # ~1 second
```

### 3. Integration Validation
```bash
# Test with real infrastructure
mvn test -Pintegration-tests    # ~1-3 minutes
```

### 4. Performance Validation
```bash
# Performance benchmarks
mvn test -Pperformance-tests    # ~2-5 minutes
```

### 5. CI/CD Pipeline
```bash
# Full test suite validation
mvn test -Pall-tests           # ~5 minutes
```

## Helper Script

Use the provided helper script for convenient test execution:

```bash
# Make executable (first time only)
chmod +x run-tests.sh

# Run different test categories
./run-tests.sh smoke         # Smoke tests (~1 second)
./run-tests.sh core          # Core tests (~2 seconds)
./run-tests.sh integration   # Integration tests (~1-3 minutes)
./run-tests.sh performance   # Performance tests (~2-5 minutes)
./run-tests.sh all           # All tests (~5 minutes)
```

## Current Test Files

### SMOKE Tests (1 file)
- `MessageTest.java` - Basic placeholder test for Message interface

### CORE Tests (1 file)
- `MessageFilterDebugTest.java` - Message filter logic with null handling

### Future Test Categories
As the API module grows, additional tests should be categorized as:
- **INTEGRATION**: Database integration, real service tests
- **PERFORMANCE**: Throughput and latency benchmarks
- **SLOW**: Comprehensive validation and stress tests

## Measured Performance

Based on actual measurements:

| Profile | Execution Time | Use Case |
|---------|---------------|----------|
| smoke-tests | ~1 second | Ultra-fast validation |
| core-tests | ~2 seconds | Daily development |
| integration-tests | ~1-3 minutes | Infrastructure validation |
| performance-tests | ~2-5 minutes | Performance benchmarks |
| all-tests | ~5 minutes | Comprehensive testing |

## Best Practices

1. **Keep SMOKE tests minimal** - Basic validation and placeholders only
2. **Use CORE for daily development** - Fast feedback with mocked dependencies
3. **Reserve INTEGRATION for real infrastructure** - Database and service tests
4. **Measure PERFORMANCE regularly** - API throughput and filter performance
5. **Use appropriate timeouts** - 10s for CORE, 3m for INTEGRATION, 5m for PERFORMANCE
6. **Mock external dependencies in CORE** - Keep tests fast and reliable
7. **Document performance expectations** - Update this guide with actual timings

## Troubleshooting

### Tests Not Running
- Check Maven profile is active: `mvn help:active-profiles`
- Verify test files have proper `@Tag` annotations
- Ensure JUnit 5 dependencies are correct

### Slow Performance
- Check if tests are properly categorized
- Verify parallel execution settings
- Monitor system resources during test execution

### Integration Test Failures
- Verify TestContainers setup
- Check database connectivity
- Ensure proper test isolation

---

*This guide is maintained as part of the peegeeq-api module. Update performance measurements and examples as the codebase evolves.*
