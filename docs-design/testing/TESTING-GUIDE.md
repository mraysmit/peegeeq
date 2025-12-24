# PeeGeeQ Testing Guide

**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Date**: 2025-09-11
**Version**: 1.0
**Status**: Active

## Overview

PeeGeeQ includes a comprehensive test categorization system that transforms the development experience from 12+ minute feedback cycles to sub-minute core test execution. The master test script provides centralized control over all 12 Maven modules and test categories.

---

## 🚀 Quick Reference

### Most Common Commands (Copy & Paste)

#### Using the Master Test Script (Recommended)
```bash
# Daily development (24 seconds)
./scripts/run-tests.sh core

# Quick validation (20 seconds)
./scripts/run-tests.sh smoke

# Single module development
./scripts/run-tests.sh core peegeeq-outbox

# Multiple modules
./scripts/run-tests.sh core peegeeq-db peegeeq-api

# Integration testing (10-15 minutes)
./scripts/run-tests.sh integration

# Help and usage
./scripts/run-tests.sh help
```

#### Using Maven Directly (Advanced)
```bash
# Daily development (24 seconds)
mvn test -Pcore-tests

# Quick validation (20 seconds)
mvn test -Psmoke-tests

# Single module development
mvn test -Pcore-tests -pl :peegeeq-outbox

# Multiple modules
mvn test -Pcore-tests -pl :peegeeq-db,:peegeeq-api

# Integration testing (10-15 minutes)
mvn test -Pintegration-tests

# Complete test suite
mvn test -Pall-tests
```

> **⚠️ Critical**: Maven requires explicit profile activation (`-P<profile>`). Without a profile, **0 tests will run**.

### Test Categories at a Glance

| Category | Duration | Purpose | Script Command | Maven Command |
|----------|----------|---------|----------------|---------------|
| **core** | 24s | Daily development | `./scripts/run-tests.sh core` | `mvn test -Pcore-tests` |
| **smoke** | 20s | Quick validation | `./scripts/run-tests.sh smoke` | `mvn test -Psmoke-tests` |
| **integration** | 10-15m | Real infrastructure | `./scripts/run-tests.sh integration` | `mvn test -Pintegration-tests` |
| **performance** | 20-30m | Benchmarks | `./scripts/run-tests.sh performance` | `mvn test -Pperformance-tests` |
| **slow** | 15+m | Comprehensive | `./scripts/run-tests.sh slow` | `mvn test -Pslow-tests` |
| **all** | 45+m | Complete suite | `./scripts/run-tests.sh all` | `mvn test -Pall-tests` |

### Pro Tips

#### Speed Optimization
```bash
# Use smoke tests for quick validation
./scripts/run-tests.sh smoke

# Focus on modules you're changing
./scripts/run-tests.sh core peegeeq-outbox

# Combine related modules for efficiency
./scripts/run-tests.sh core peegeeq-outbox peegeeq-bitemporal
```

#### Development Efficiency
```bash
# Create shell aliases for frequent commands
alias pqcore='./scripts/run-tests.sh core'
alias pqsmoke='./scripts/run-tests.sh smoke'
alias pqoutbox='./scripts/run-tests.sh core peegeeq-outbox'

# Use in development
pqcore
pqoutbox
pqsmoke
```

---

## Quick Start

### Using the Master Test Script (Recommended)

```bash
# Daily development testing (recommended)
./scripts/run-tests.sh core

# Quick validation before commits
./scripts/run-tests.sh smoke

# Single module development
./scripts/run-tests.sh core peegeeq-outbox

# Multiple specific modules
./scripts/run-tests.sh core peegeeq-db peegeeq-api

# Integration testing
./scripts/run-tests.sh integration

# Complete test suite
./scripts/run-tests.sh all
```

### Using Maven Directly (Advanced)

```bash
# Daily development testing - requires profile activation
mvn test -Pcore-tests

# Quick validation before commits
mvn test -Psmoke-tests

# Single module development
mvn test -Pcore-tests -pl :peegeeq-outbox

# Multiple specific modules
mvn test -Pcore-tests -pl :peegeeq-db,:peegeeq-api

# Integration testing
mvn test -Pintegration-tests

# Complete test suite
mvn test -Pall-tests

# Module-specific integration testing
mvn test -Pintegration-tests -pl :peegeeq-examples-spring
```

> **⚠️ Important**: Direct Maven execution requires explicit profile activation (`-P<profile>`). Without a profile, **0 tests will run** due to the test categorization system. The master test script automatically handles profile selection.

## Test Categories

### CORE Tests
**Purpose**: Fast unit tests for daily development  
**Duration**: ~30 seconds total, <1 second per test  
**When to use**: Regular development, before commits, CI/CD fast feedback

```bash
# All modules (12 modules, ~24 seconds)
./scripts/run-tests.sh core

# Single module (~2-5 seconds)
./scripts/run-tests.sh core peegeeq-api
./scripts/run-tests.sh core peegeeq-db
./scripts/run-tests.sh core peegeeq-outbox

# Multiple modules (~6-10 seconds)
./scripts/run-tests.sh core peegeeq-db peegeeq-api
./scripts/run-tests.sh core peegeeq-outbox peegeeq-rest peegeeq-native
```

### SMOKE Tests
**Purpose**: Ultra-fast basic verification  
**Duration**: ~15-20 seconds total  
**When to use**: Quick sanity checks, pre-commit validation

```bash
# All modules (12 modules, ~20 seconds)
./scripts/run-tests.sh smoke

# Single module (~1-2 seconds)
./scripts/run-tests.sh smoke peegeeq-api
./scripts/run-tests.sh smoke peegeeq-native

# Multiple modules (~3-5 seconds)
./scripts/run-tests.sh smoke peegeeq-db peegeeq-api
```

### INTEGRATION Tests
**Purpose**: Tests with real PostgreSQL via TestContainers  
**Duration**: ~10-15 minutes total  
**When to use**: Before major releases, integration validation

```bash
# All modules (12 modules, ~10-15 minutes)
./scripts/run-tests.sh integration

# Single module (~2-5 minutes)
./scripts/run-tests.sh integration peegeeq-outbox
./scripts/run-tests.sh integration peegeeq-bitemporal
./scripts/run-tests.sh integration peegeeq-rest

# Multiple modules (~5-10 minutes)
./scripts/run-tests.sh integration peegeeq-outbox peegeeq-bitemporal
```

### PERFORMANCE Tests
**Purpose**: Load and throughput benchmarks  
**Duration**: ~20-30 minutes total  
**When to use**: Performance validation, benchmarking

```bash
# All modules (12 modules, ~20-30 minutes)
./scripts/run-tests.sh performance

# Single module (~3-8 minutes)
./scripts/run-tests.sh performance peegeeq-outbox
./scripts/run-tests.sh performance peegeeq-test-support

# Multiple modules (~10-15 minutes)
./scripts/run-tests.sh performance peegeeq-outbox peegeeq-test-support
```

### SLOW Tests
**Purpose**: Long-running comprehensive tests  
**Duration**: ~15+ minutes total  
**When to use**: Comprehensive validation, nightly builds

```bash
# All modules (12 modules, ~15+ minutes)
./scripts/run-tests.sh slow

# Single module (~2-5 minutes)
./scripts/run-tests.sh slow peegeeq-test-support
```

### ALL Tests
**Purpose**: Complete test suite execution  
**Duration**: ~45+ minutes total  
**When to use**: Full validation, release preparation

```bash
# All modules (12 modules, ~45+ minutes)
./scripts/run-tests.sh all
```

## Module-Specific Testing

### Available Modules
- `peegeeq-api` - Core API definitions and message filtering
- `peegeeq-db` - Database configuration and utilities  
- `peegeeq-native` - Native PostgreSQL queue implementation
- `peegeeq-outbox` - Transactional outbox pattern implementation
- `peegeeq-bitemporal` - Bi-temporal event store
- `peegeeq-rest` - REST API server
- `peegeeq-test-support` - Testing utilities and helpers
- `peegeeq-service-manager` - Service discovery and management
- `peegeeq-performance-test-harness` - Performance testing framework
- `peegeeq-migrations` - Database schema migrations and validation
- `peegeeq-examples` - Usage examples and demonstrations
- `peegeeq-examples-spring` - Spring Boot integration examples

### Single Module Examples

#### Using the Master Test Script
```bash
# API module
./scripts/run-tests.sh core peegeeq-api
./scripts/run-tests.sh smoke peegeeq-api
./scripts/run-tests.sh integration peegeeq-api

# Database module
./scripts/run-tests.sh core peegeeq-db
./scripts/run-tests.sh integration peegeeq-db

# Outbox module
./scripts/run-tests.sh core peegeeq-outbox
./scripts/run-tests.sh integration peegeeq-outbox
./scripts/run-tests.sh performance peegeeq-outbox

# Native queue module
./scripts/run-tests.sh core peegeeq-native
./scripts/run-tests.sh integration peegeeq-native

# REST API module
./scripts/run-tests.sh core peegeeq-rest
./scripts/run-tests.sh integration peegeeq-rest

# Test support module
./scripts/run-tests.sh core peegeeq-test-support
./scripts/run-tests.sh performance peegeeq-test-support
```

#### Using Maven Directly
```bash
# API module
mvn test -Pcore-tests -pl :peegeeq-api
mvn test -Psmoke-tests -pl :peegeeq-api
mvn test -Pintegration-tests -pl :peegeeq-api

# Database module
mvn test -Pcore-tests -pl :peegeeq-db
mvn test -Pintegration-tests -pl :peegeeq-db

# Outbox module
mvn test -Pcore-tests -pl :peegeeq-outbox
mvn test -Pintegration-tests -pl :peegeeq-outbox
mvn test -Pperformance-tests -pl :peegeeq-outbox

# Native queue module
mvn test -Pcore-tests -pl :peegeeq-native
mvn test -Pintegration-tests -pl :peegeeq-native

# REST API module
mvn test -Pcore-tests -pl :peegeeq-rest
mvn test -Pintegration-tests -pl :peegeeq-rest

# Test support module
mvn test -Pcore-tests -pl :peegeeq-test-support
mvn test -Pperformance-tests -pl :peegeeq-test-support
```

### Multiple Module Examples

#### Using the Master Test Script
```bash
# Core modules together
./scripts/run-tests.sh core peegeeq-api peegeeq-db peegeeq-native

# Outbox and related modules
./scripts/run-tests.sh integration peegeeq-outbox peegeeq-bitemporal

# REST and service modules
./scripts/run-tests.sh core peegeeq-rest peegeeq-service-manager

# Example modules
./scripts/run-tests.sh smoke peegeeq-examples peegeeq-examples-spring

# Performance-focused modules
./scripts/run-tests.sh performance peegeeq-outbox peegeeq-test-support peegeeq-performance-test-harness
```

#### Using Maven Directly
```bash
# Core modules together
mvn test -Pcore-tests -pl :peegeeq-api,:peegeeq-db,:peegeeq-native

# Outbox and related modules
mvn test -Pintegration-tests -pl :peegeeq-outbox,:peegeeq-bitemporal

# REST and service modules
mvn test -Pcore-tests -pl :peegeeq-rest,:peegeeq-service-manager

# Example modules
mvn test -Psmoke-tests -pl :peegeeq-examples,:peegeeq-examples-spring

# Performance-focused modules
mvn test -Pperformance-tests -pl :peegeeq-outbox,:peegeeq-test-support,:peegeeq-performance-test-harness
```

## Maven Profile System

### Understanding Test Categorization

The PeeGeeQ project uses **Maven profiles** to control test execution through JUnit 5 `@Tag` annotations. This system ensures that:

- **Fast tests** run during daily development
- **Slow tests** only run when explicitly requested
- **Integration tests** don't accidentally run during unit testing
- **Performance tests** are isolated from regular development workflows

### Available Maven Profiles

| Profile | Tests Included | Typical Duration | Use Case |
|---------|---------------|------------------|----------|
| `core-tests` | `@Tag("core")` | ~30 seconds | Daily development |
| `smoke-tests` | `@Tag("smoke")` | ~20 seconds | Quick validation |
| `integration-tests` | `@Tag("integration")` | 5-10 minutes | Pre-commit testing |
| `performance-tests` | `@Tag("performance")` | 10-15 minutes | Performance validation |
| `slow-tests` | `@Tag("slow")` | 15+ minutes | Comprehensive testing |
| `all-tests` | All except `flaky` | Variable | Complete test suite |

### Why Profiles Are Required

**Without a profile**, Maven's default Surefire configuration **excludes all test categories**:

```xml
<configuration>
    <!-- Default: exclude all tests unless a profile is active -->
    <excludedGroups>core,smoke,integration,performance,slow</excludedGroups>
</configuration>
```

This design prevents:
- ❌ Accidental execution of slow integration tests during `mvn test`
- ❌ Long feedback cycles during development
- ❌ Inconsistent test execution across environments
- ❌ CI/CD pipelines running unintended test categories

### Profile Activation Examples

```bash
# ✅ Correct - activates core-tests profile
mvn test -Pcore-tests

# ❌ Wrong - runs 0 tests (no profile activated)
mvn test

# ✅ Correct - runs integration tests for specific module
mvn test -Pintegration-tests -pl :peegeeq-examples-spring

# ❌ Wrong - runs 0 tests (no profile activated)
mvn test -pl :peegeeq-examples-spring
```

## Script Features

### Intelligent Duration Estimation
The script provides accurate time estimates based on test category and module count:

```bash
# Shows: "Expected duration: ~20 seconds"
./scripts/run-tests.sh core

# Shows: "Expected duration: ~55 seconds" 
./scripts/run-tests.sh smoke

# Shows: "Expected duration: ~10 minutes"
./scripts/run-tests.sh integration
```

### Performance Feedback
After execution, the script provides performance ratings:

- ⚡ **Excellent performance!** (under expected time)
- 👍 **Good performance!** (within expected range)  
- ⚠️ **Slower than expected** (over expected time)

### Colored Output
- 🎯 **Blue headers** for execution phases
- ℹ️ **Cyan info** for configuration details
- ✅ **Green success** for completed tests
- ❌ **Red errors** for failures
- ⚠️ **Yellow warnings** for issues

### Help and Usage

```bash
# Show comprehensive help
./scripts/run-tests.sh help
./scripts/run-tests.sh --help
./scripts/run-tests.sh -h

# Invalid usage shows help automatically
./scripts/run-tests.sh
./scripts/run-tests.sh invalid-category
```

## Development Workflows

### Daily Development Workflow

#### Using the Master Test Script
```bash
# Start development session
./scripts/run-tests.sh core

# Work on specific module
./scripts/run-tests.sh core peegeeq-outbox

# Quick validation before commit
./scripts/run-tests.sh smoke

# Final check before push
./scripts/run-tests.sh core
```

#### Using Maven Directly
```bash
# Start development session
mvn test -Pcore-tests

# Work on specific module
mvn test -Pcore-tests -pl :peegeeq-outbox

# Quick validation before commit
mvn test -Psmoke-tests

# Final check before push
mvn test -Pcore-tests
```

### Pre-Commit Workflow

#### Using the Master Test Script
```bash
# Quick validation (20 seconds)
./scripts/run-tests.sh smoke

# Core functionality check (24 seconds)
./scripts/run-tests.sh core

# Module-specific validation
./scripts/run-tests.sh core peegeeq-outbox peegeeq-rest
```

#### Using Maven Directly
```bash
# Quick validation (20 seconds)
mvn test -Psmoke-tests

# Core functionality check (24 seconds)
mvn test -Pcore-tests

# Module-specific validation
mvn test -Pcore-tests -pl :peegeeq-outbox,:peegeeq-rest
```

### Integration Testing Workflow

#### Using the Master Test Script
```bash
# Single module integration
./scripts/run-tests.sh integration peegeeq-outbox

# Related modules integration
./scripts/run-tests.sh integration peegeeq-outbox peegeeq-bitemporal

# Full integration suite
./scripts/run-tests.sh integration
```

#### Using Maven Directly
```bash
# Single module integration
mvn test -Pintegration-tests -pl :peegeeq-outbox

# Related modules integration
mvn test -Pintegration-tests -pl :peegeeq-outbox,:peegeeq-bitemporal

# Full integration suite
mvn test -Pintegration-tests
```

### Performance Testing Workflow

#### Using the Master Test Script
```bash
# Quick performance check
./scripts/run-tests.sh performance peegeeq-outbox

# Comprehensive performance suite
./scripts/run-tests.sh performance

# Performance comparison
./scripts/run-tests.sh performance peegeeq-outbox peegeeq-test-support
```

#### Using Maven Directly
```bash
# Quick performance check
mvn test -Pperformance-tests -pl :peegeeq-outbox

# Comprehensive performance suite
mvn test -Pperformance-tests

# Performance comparison
mvn test -Pperformance-tests -pl :peegeeq-outbox,:peegeeq-test-support
```

## CI/CD Integration

### GitHub Actions Examples

#### Using the Master Test Script
```yaml
# Fast feedback (core tests)
- name: Run Core Tests
  run: ./scripts/run-tests.sh core

# Pre-merge validation (smoke + core)
- name: Run Smoke Tests
  run: ./scripts/run-tests.sh smoke
- name: Run Core Tests
  run: ./scripts/run-tests.sh core

# Nightly builds (full suite)
- name: Run All Tests
  run: ./scripts/run-tests.sh all
```

#### Using Maven Directly
```yaml
# Fast feedback (core tests)
- name: Run Core Tests
  run: mvn test -Pcore-tests

# Pre-merge validation (smoke + core)
- name: Run Smoke Tests
  run: mvn test -Psmoke-tests
- name: Run Core Tests
  run: mvn test -Pcore-tests

# Nightly builds (full suite)
- name: Run All Tests
  run: mvn test -Pall-tests
```

### Jenkins Pipeline Examples

#### Using the Master Test Script
```groovy
// Fast feedback stage
stage('Core Tests') {
    steps {
        sh './scripts/run-tests.sh core'
    }
}

// Integration stage
stage('Integration Tests') {
    steps {
        sh './scripts/run-tests.sh integration'
    }
}

// Performance stage
stage('Performance Tests') {
    steps {
        sh './scripts/run-tests.sh performance'
    }
}
```

#### Using Maven Directly
```groovy
// Fast feedback stage
stage('Core Tests') {
    steps {
        sh 'mvn test -Pcore-tests'
    }
}

// Integration stage
stage('Integration Tests') {
    steps {
        sh 'mvn test -Pintegration-tests'
    }
}

// Performance stage
stage('Performance Tests') {
    steps {
        sh 'mvn test -Pperformance-tests'
    }
}
```

## Troubleshooting

### Common Issues

**Script not executable:**
```bash
chmod +x scripts/run-tests.sh
```

**Module not found:**
```bash
# Check available modules
./scripts/run-tests.sh help

# Verify module name spelling
./scripts/run-tests.sh core peegeeq-outbox  # correct
./scripts/run-tests.sh core peegeeq-outbox-wrong  # incorrect
```

**Tests failing:**
```bash
# Run with Maven verbose output
mvn test -Pcore-tests -pl :peegeeq-outbox -X

# Check individual module
cd peegeeq-outbox
mvn test -Pcore-tests
```

### Performance Issues

**Slower than expected performance:**
1. Check system resources (CPU, memory)
2. Verify no other heavy processes running
3. Consider running fewer modules simultaneously
4. Check Docker resources for integration tests

**TestContainers issues:**
1. Ensure Docker is running
2. Check Docker resources allocation
3. Verify network connectivity
4. Clean up old containers: `docker system prune`

## Advanced Usage

### Custom Maven Profiles
The script uses these Maven profiles internally:
- `core-tests` - Fast unit tests
- `smoke-tests` - Ultra-fast validation  
- `integration-tests` - TestContainers integration
- `performance-tests` - Load and throughput
- `slow-tests` - Long-running comprehensive
- `all-tests` - Complete test suite

### Direct Maven Usage
If you prefer direct Maven commands:
```bash
# Equivalent to: ./scripts/run-tests.sh core peegeeq-outbox
mvn test -Pcore-tests -pl :peegeeq-outbox

# Equivalent to: ./scripts/run-tests.sh integration
mvn test -Pintegration-tests -pl :peegeeq-db,:peegeeq-native,:peegeeq-bitemporal,:peegeeq-outbox,:peegeeq-rest,:peegeeq-test-support,:peegeeq-service-manager,:peegeeq-performance-test-harness,:peegeeq-api,:peegeeq-examples,:peegeeq-examples-spring
```

## Test Categories Deep Dive

This section provides comprehensive information about the test categorization system using JUnit 5 `@Tag` annotations and Maven profiles.

### Test Category Definitions

#### 🚀 **CORE** - Fast Unit Tests
- **Purpose**: Critical functionality validation with minimal dependencies
- **Target Time**: < 30 seconds total
- **Includes**: Configuration loading, validation logic, basic CRUD operations, error handling
- **Usage**: `@Tag(TestCategories.CORE)`
- **Run**: `mvn test` (default) or `mvn test -Pcore-tests`

#### 🔧 **INTEGRATION** - Infrastructure Tests
- **Purpose**: End-to-end functionality with real PostgreSQL/TestContainers
- **Target Time**: 1-3 minutes total
- **Includes**: Database schema, connection pooling, transaction management, query operations
- **Usage**: `@Tag(TestCategories.INTEGRATION)`
- **Run**: `mvn test -Pintegration-tests`

#### ⚡ **PERFORMANCE** - Load & Throughput Tests
- **Purpose**: Performance validation and benchmarking
- **Target Time**: 2-5 minutes
- **Includes**: High-frequency operations, connection pool stress, concurrent access
- **Usage**: `@Tag(TestCategories.PERFORMANCE)`
- **Run**: `mvn test -Pperformance-tests`

#### 🐌 **SLOW** - Comprehensive Tests
- **Purpose**: Long-running comprehensive validation
- **Target Time**: 5+ minutes
- **Includes**: Full system integration, multi-container orchestration, extended reliability
- **Usage**: `@Tag(TestCategories.SLOW)`
- **Run**: `mvn test -Pall-tests` (includes slow)

#### 🔥 **SMOKE** - Ultra-Fast Verification
- **Purpose**: Basic "system works" verification
- **Target Time**: < 10 seconds total
- **Includes**: System startup, basic connections, minimal operations
- **Usage**: `@Tag(TestCategories.SMOKE)`
- **Run**: `mvn test -Psmoke-tests`

#### ⚠️ **FLAKY** - Unstable Tests
- **Purpose**: Tests that may fail due to timing/external factors
- **Target Time**: Variable
- **Includes**: Timing-sensitive tests, external dependencies
- **Usage**: `@Tag(TestCategories.FLAKY)`
- **Run**: `mvn test -Pfull-tests` (includes flaky)

### How to Categorize Your Tests

#### 1. Add the @Tag annotation to your test class:
```java
import org.junit.jupiter.api.Tag;
import dev.mars.peegeeq.test.categories.TestCategories;

@Tag(TestCategories.CORE)  // or INTEGRATION, PERFORMANCE, etc.
class MyTest {
    // test methods
}
```

#### 2. Choose the right category:

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

### Benefits of Test Categorization

#### For Developers
- **Fast Feedback**: Core tests run in < 30 seconds
- **Targeted Testing**: Run only relevant test categories
- **Parallel Execution**: Faster test execution
- **Clear Organization**: Easy to understand test purpose

#### For CI/CD
- **Efficient Pipelines**: Different test categories for different stages
- **Resource Optimization**: Appropriate parallelism per category
- **Failure Isolation**: Skip flaky tests in critical pipelines
- **Scalable Testing**: Add categories as needed

### Migration Strategy

1. **Start with existing tests**: Add `@Tag(TestCategories.INTEGRATION)` to TestContainer-based tests
2. **Identify fast tests**: Add `@Tag(TestCategories.CORE)` to unit tests
3. **Mark performance tests**: Add `@Tag(TestCategories.PERFORMANCE)` to load tests
4. **Test the setup**: Run `mvn test -Pcore-tests` to verify fast execution
5. **Gradually categorize**: Add tags to remaining tests over time

### Troubleshooting Test Categories

#### Tests not running?
- Check that `@Tag` import is correct: `import org.junit.jupiter.api.Tag;`
- Verify category constant: `TestCategories.CORE` (not `"core"`)
- Ensure Maven profile is active: `mvn test -Pcore-tests`

#### Tests running in wrong category?
- Check for multiple `@Tag` annotations (use only one per class)
- Verify Maven profile excludedGroups configuration
- Use `mvn test -Dgroups=core -DexcludedGroups=integration` for debugging

#### Performance issues?
- Reduce threadCount for integration tests
- Use `parallel=none` for performance tests
- Check TestContainer resource limits

---

## Performance Benchmarks

### Typical Execution Times

| Category | All Modules | Single Module | Multiple Modules |
|----------|-------------|---------------|------------------|
| **smoke** | 20s | 1-2s | 3-5s |
| **core** | 24s | 2-5s | 6-10s |
| **integration** | 10-15m | 2-5m | 5-10m |
| **performance** | 20-30m | 3-8m | 10-15m |
| **slow** | 15+m | 2-5m | 5-10m |
| **all** | 45+m | 10-15m | 20-30m |

### Module-Specific Performance

| Module | Core | Smoke | Integration | Performance |
|--------|------|-------|-------------|-------------|
| **peegeeq-api** | 2.3s | 1s | 3m | 5m |
| **peegeeq-db** | 1.7s | 0.5s | 2m | 3m |
| **peegeeq-outbox** | 2.4s | 1s | 5m | 8m |
| **peegeeq-native** | 1.5s | 1s | 3m | 4m |
| **peegeeq-rest** | 3s | 1s | 4m | 6m |
| **peegeeq-test-support** | 5.3s | 2s | 8m | 12m |

## Script Architecture

### How It Works

The master test script (`scripts/run-tests.sh`) provides a unified interface to Maven's test execution with intelligent categorization:

1. **Category Validation**: Validates test category against supported options
2. **Module Resolution**: Resolves module names and validates they exist
3. **Maven Profile Mapping**: Maps categories to appropriate Maven profiles
4. **Command Construction**: Builds optimized Maven commands with `-pl` module selection
5. **Execution & Monitoring**: Runs tests with timing and performance feedback
6. **Results Analysis**: Provides performance ratings and recommendations

### Internal Components

**Category-to-Profile Mapping:**
```bash
core        → core-tests
smoke       → smoke-tests
integration → integration-tests
performance → performance-tests
slow        → slow-tests
all         → all-tests
```

**Module List Management:**
The script maintains a centralized list of all categorized modules (12 Maven modules):
```bash
CATEGORIZED_MODULES=(
    "peegeeq-db" "peegeeq-native" "peegeeq-bitemporal"
    "peegeeq-outbox" "peegeeq-rest" "peegeeq-test-support"
    "peegeeq-service-manager" "peegeeq-performance-test-harness"
    "peegeeq-api" "peegeeq-examples" "peegeeq-examples-spring"
    "peegeeq-migrations"
)
```

**Note**: `peegeeq-management-ui` is excluded as it's a TypeScript/JavaScript UI project with separate test tooling (not Maven).

**Maven Command Generation:**
```bash
# Single module
mvn test -Pcore-tests -pl :peegeeq-outbox

# Multiple modules
mvn test -Pcore-tests -pl :peegeeq-db,:peegeeq-api

# All modules (12 Maven modules)
mvn test -Pcore-tests -pl :peegeeq-db,:peegeeq-native,:peegeeq-bitemporal,:peegeeq-migrations,...
```

## Integration with IDEs

### IntelliJ IDEA
1. **Terminal Integration**: Use built-in terminal to run script commands
2. **Run Configurations**: Create custom run configurations for frequent commands
3. **External Tools**: Add script as external tool for quick access

**Setup External Tool:**
- Name: `PeeGeeQ Core Tests`
- Program: `$ProjectFileDir$/scripts/run-tests.sh`
- Arguments: `core`
- Working Directory: `$ProjectFileDir$`

### VS Code
1. **Integrated Terminal**: Run commands directly in VS Code terminal
2. **Tasks Configuration**: Create tasks.json for common test commands
3. **Keyboard Shortcuts**: Bind frequently used commands to shortcuts

**Example tasks.json:**
```json
{
    "version": "2.0.0",
    "tasks": [
        {
            "label": "PeeGeeQ Core Tests",
            "type": "shell",
            "command": "./scripts/run-tests.sh",
            "args": ["core"],
            "group": "test",
            "presentation": {
                "echo": true,
                "reveal": "always",
                "focus": false,
                "panel": "shared"
            }
        }
    ]
}
```

## Best Practices

### Development Workflow Best Practices

1. **Start with Smoke Tests**: Always begin development sessions with smoke tests
2. **Use Core Tests Frequently**: Run core tests after each significant change
3. **Module-Specific Testing**: Focus on modules you're actively developing
4. **Pre-Commit Validation**: Always run smoke + core before committing
5. **Integration Before Push**: Run integration tests before pushing to main branches

### Performance Optimization Tips

1. **Selective Testing**: Don't run all tests if you're working on specific modules
2. **Parallel Development**: Use module-specific testing for faster feedback
3. **Resource Management**: Close unnecessary applications during integration tests
4. **Docker Optimization**: Ensure Docker has adequate resources for TestContainers

### Team Collaboration

1. **Consistent Commands**: Use the same script commands across the team
2. **CI/CD Alignment**: Match local testing with CI/CD pipeline stages
3. **Documentation**: Keep this guide updated as the project evolves
4. **Knowledge Sharing**: Share performance tips and troubleshooting solutions

## Migration from Legacy Testing

### Before Test Categorization
```bash
# Old way - slow and inefficient
mvn test  # 12+ minutes for everything
```

### After Test Categorization
```bash
# New way - fast and selective
./scripts/run-tests.sh core     # 24 seconds for daily development
./scripts/run-tests.sh smoke    # 20 seconds for quick validation
./scripts/run-tests.sh integration  # 10-15 minutes when needed
```

### Migration Benefits
- **12x faster feedback** for daily development (12+ minutes → 24 seconds)
- **Selective testing** based on development needs
- **Consistent interface** across all modules and categories
- **Better CI/CD integration** with appropriate test stages
- **Improved developer experience** with clear feedback and timing

## Testing Standards Reference

For comprehensive testing standards, including mandatory requirements for TestContainers, database validation patterns, and transaction testing, see:

**[pgq-testing-standards.md](pgq-testing-standards.md)**

Key topics covered in the standards document:
- **TestContainers Standards** - Mandatory usage of `PostgreSQLTestConstants`
- **Database Validation Patterns** - How to verify database state correctly
- **Transaction Testing Requirements** - Comprehensive transaction test scenarios
- **Test Structure Standards** - Naming conventions and organization
- **Increment-Specific Requirements** - Phase-by-phase testing requirements
- **Testing Checklists** - Before, during, and after implementation

---

## Future Enhancements

### Planned Features
1. **Parallel Module Execution**: Run multiple modules in parallel for faster execution
2. **Test Result Caching**: Cache test results to skip unchanged modules
3. **Smart Test Selection**: Automatically select relevant tests based on changed files
4. **Integration with Git Hooks**: Automatic test execution on commits/pushes
5. **Performance Trending**: Track test execution times over time

### Extensibility
The script is designed to be easily extensible:
- **New Categories**: Add new test categories by updating the category mapping
- **New Modules**: Automatically detected when added to the project
- **Custom Profiles**: Support for custom Maven profiles
- **Plugin Integration**: Easy integration with build tools and IDEs

---

## Related Documentation

- **[pgq-testing-standards.md](pgq-testing-standards.md)** - Mandatory testing standards and compliance requirements
- **[Feature Test Coverage Reports](../features/)** - Feature-specific test coverage documentation
- **[PEEGEEQ_COMPLETE_GUIDE.md](../../docs/PEEGEEQ_COMPLETE_GUIDE.md)** - Complete system guide
- **[PeeGeeQ-Development-Testing.md](../../docs/PeeGeeQ-Development-Testing.md)** - Development setup guide

---

*This comprehensive guide covers all aspects of the PeeGeeQ testing system. The master test script transforms development workflow from slow, monolithic testing to fast, selective, and efficient test execution. For questions or issues, refer to the troubleshooting section or check the script's built-in help: `./scripts/run-tests.sh help`*

**Last Updated**: 2025-12-23
**Version**: 1.0
