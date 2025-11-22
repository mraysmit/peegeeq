# PeeGeeQ Test Categorization Guide

## Overview

This guide explains how to categorize and execute tests in the peegeeq-db module using JUnit 5 `@Tag` annotations and Maven profiles for optimal development productivity.

## Test Categories

### 🚀 **CORE** - Fast Unit Tests
- **Purpose**: Critical functionality validation with minimal dependencies
- **Target Time**: < 30 seconds total
- **Includes**: Configuration loading, validation logic, basic CRUD operations, error handling
- **Usage**: `@Tag(TestCategories.CORE)`
- **Run**: `mvn test` (default) or `mvn test -Pcore-tests`

### 🔧 **INTEGRATION** - Infrastructure Tests  
- **Purpose**: End-to-end functionality with real PostgreSQL/TestContainers
- **Target Time**: 1-3 minutes total
- **Includes**: Database schema, connection pooling, transaction management, query operations
- **Usage**: `@Tag(TestCategories.INTEGRATION)`
- **Run**: `mvn test -Pintegration-tests`

### ⚡ **PERFORMANCE** - Load & Throughput Tests
- **Purpose**: Performance validation and benchmarking
- **Target Time**: 2-5 minutes
- **Includes**: High-frequency operations, connection pool stress, concurrent access
- **Usage**: `@Tag(TestCategories.PERFORMANCE)`
- **Run**: `mvn test -Pperformance-tests`

### 🐌 **SLOW** - Comprehensive Tests
- **Purpose**: Long-running comprehensive validation
- **Target Time**: 5+ minutes
- **Includes**: Full system integration, multi-container orchestration, extended reliability
- **Usage**: `@Tag(TestCategories.SLOW)`
- **Run**: `mvn test -Pall-tests` (includes slow)

### 🔥 **SMOKE** - Ultra-Fast Verification
- **Purpose**: Basic "system works" verification
- **Target Time**: < 10 seconds total
- **Includes**: System startup, basic connections, minimal operations
- **Usage**: `@Tag(TestCategories.SMOKE)`
- **Run**: `mvn test -Psmoke-tests`

### ⚠️ **FLAKY** - Unstable Tests
- **Purpose**: Tests that may fail due to timing/external factors
- **Target Time**: Variable
- **Includes**: Timing-sensitive tests, external dependencies
- **Usage**: `@Tag(TestCategories.FLAKY)`
- **Run**: `mvn test -Pfull-tests` (includes flaky)

## Maven Execution Commands

### Development Workflow (Recommended)
```bash
# Fast feedback during development (< 30 seconds)
mvn test

# Verify integration after changes (1-3 minutes)  
mvn test -Pintegration-tests

# Full validation before commit (5-10 minutes)
mvn test -Pall-tests
```

### Specialized Testing
```bash
# Ultra-fast smoke test (< 10 seconds)
mvn test -Psmoke-tests

# Performance benchmarking (2-5 minutes)
mvn test -Pperformance-tests

# Complete validation including flaky tests
mvn test -Pfull-tests
```

### CI/CD Pipeline Recommendations
```bash
# Pull Request validation
mvn test -Pcore-tests && mvn test -Pintegration-tests

# Nightly builds
mvn test -Pall-tests

# Release validation
mvn test -Pfull-tests
```

## How to Categorize Your Tests

### 1. Add the @Tag annotation to your test class:
```java
import org.junit.jupiter.api.Tag;
import dev.mars.peegeeq.test.categories.TestCategories;

@Tag(TestCategories.CORE)  // or INTEGRATION, PERFORMANCE, etc.
class MyTest {
    // test methods
}
```

### 2. Choose the right category:

**CORE** if your test:
- ✅ Runs in < 1 second
- ✅ Has no external dependencies (no TestContainers)
- ✅ Tests critical business logic
- ✅ Can run in parallel with other tests

**INTEGRATION** if your test:
- ✅ Uses TestContainers or real database
- ✅ Tests end-to-end functionality
- ✅ Runs in 1-30 seconds
- ✅ Tests component interactions

**PERFORMANCE** if your test:
- ✅ Measures throughput, latency, or load
- ✅ May take 30+ seconds
- ✅ Validates performance requirements
- ✅ Should run sequentially

**SLOW** if your test:
- ✅ Takes 1+ minutes
- ✅ Tests complex scenarios
- ✅ Comprehensive integration testing
- ✅ Not needed for fast feedback

**SMOKE** if your test:
- ✅ Ultra-fast (< 1 second)
- ✅ Tests basic "system works"
- ✅ Minimal assertions
- ✅ High-level validation

**FLAKY** if your test:
- ⚠️ Sometimes fails due to timing
- ⚠️ Depends on external services
- ⚠️ Sensitive to system load
- ⚠️ Needs investigation/fixing

## Benefits

### For Developers
- **Fast Feedback**: Core tests run in < 30 seconds
- **Targeted Testing**: Run only relevant test categories
- **Parallel Execution**: Faster test execution
- **Clear Organization**: Easy to understand test purpose

### For CI/CD
- **Efficient Pipelines**: Different test categories for different stages
- **Resource Optimization**: Appropriate parallelism per category
- **Failure Isolation**: Skip flaky tests in critical pipelines
- **Scalable Testing**: Add categories as needed

## Migration Strategy

1. **Start with existing tests**: Add `@Tag(TestCategories.INTEGRATION)` to TestContainer-based tests
2. **Identify fast tests**: Add `@Tag(TestCategories.CORE)` to unit tests
3. **Mark performance tests**: Add `@Tag(TestCategories.PERFORMANCE)` to load tests
4. **Test the setup**: Run `mvn test -Pcore-tests` to verify fast execution
5. **Gradually categorize**: Add tags to remaining tests over time

## Troubleshooting

### Tests not running?
- Check that `@Tag` import is correct: `import org.junit.jupiter.api.Tag;`
- Verify category constant: `TestCategories.CORE` (not `"core"`)
- Ensure Maven profile is active: `mvn test -Pcore-tests`

### Tests running in wrong category?
- Check for multiple `@Tag` annotations (use only one per class)
- Verify Maven profile excludedGroups configuration
- Use `mvn test -Dgroups=core -DexcludedGroups=integration` for debugging

### Performance issues?
- Reduce threadCount for integration tests
- Use `parallel=none` for performance tests
- Check TestContainer resource limits
