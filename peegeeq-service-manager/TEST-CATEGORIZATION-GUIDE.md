# Test Categorization Guide - peegeeq-service-manager

This document provides comprehensive guidance for using the test categorization system in the peegeeq-service-manager module.

## Overview

The peegeeq-service-manager module uses JUnit 5 `@Tag` annotations with Maven profiles to enable selective test execution. This system allows developers to run different subsets of tests based on their needs, dramatically improving development feedback cycles.

## Test Categories

### 🚀 CORE Tests
**Target**: < 30 seconds total, < 1 second per test  
**Purpose**: Fast unit tests for daily development  
**Dependencies**: Mocked only, no external infrastructure  

**Examples**:
- `PeeGeeQInstanceTest` - Model class validation and JSON serialization
- `LoadBalancerTest` - Load balancing algorithm logic with mock instances

**Usage**:
```java
@Tag(TestCategories.CORE)
class PeeGeeQInstanceTest {
    @Test
    void testInstanceCreation() {
        // Fast model validation without external dependencies
    }
}
```

### 🔧 INTEGRATION Tests
**Target**: 1-3 minutes total  
**Purpose**: Tests with TestContainers and real infrastructure  
**Dependencies**: Real Consul via TestContainers, HTTP servers, service discovery  

**Examples**:
- `PeeGeeQServiceManagerIntegrationTest` - Full service manager with real Consul
- `RealServiceDiscoveryIntegrationTest` - Complete service discovery pipeline
- `ManualHealthCheckTest` - HTTP health endpoint validation

**Usage**:
```java
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class PeeGeeQServiceManagerIntegrationTest {
    @Container
    static ConsulContainer consul = new ConsulContainer("hashicorp/consul:1.15.3");
    
    @Test
    void testServiceRegistration() {
        // Real Consul integration test
    }
}
```

### ⚡ PERFORMANCE Tests
**Target**: 2-5 minutes total  
**Purpose**: Load and throughput tests  
**Dependencies**: Real infrastructure for realistic performance measurement  

**Examples**:
- Load balancing performance under high request volume
- Service discovery throughput with multiple instances
- Health check response time validation

### 🚨 FLAKY Tests
**Target**: Excluded from regular runs  
**Purpose**: Tests requiring manual infrastructure setup  
**Dependencies**: Manual Consul setup, environment-specific configuration  

**Examples**:
- `PeeGeeQServiceManagerTest` - Requires manual Consul on localhost:8500
- `ConsulServiceDiscoveryTest` - Requires manual Consul setup

**Usage**:
```java
@Tag(TestCategories.FLAKY)
@Disabled("Use PeeGeeQServiceManagerIntegrationTest instead - requires Consul running")
class PeeGeeQServiceManagerTest {
    @Test
    void testServiceManager() {
        // Test that requires manual Consul setup
    }
}
```

## Maven Profiles

### Daily Development
```bash
# Core tests only (default) - ~5 seconds
mvn test
mvn test -Pcore-tests
```

### Pre-commit Validation
```bash
# Integration tests - ~1-2 minutes
mvn test -Pintegration-tests
```

### Performance Benchmarks
```bash
# Performance tests - ~3-5 minutes
mvn test -Pperformance-tests
```

### Comprehensive Testing
```bash
# All tests except flaky - ~5-10 minutes
mvn test -Pall-tests
```

### Smoke Testing
```bash
# Ultra-fast basic verification - ~10 seconds
mvn test -Psmoke-tests
```

### Slow/Comprehensive Testing
```bash
# Long-running comprehensive tests - ~15+ minutes
mvn test -Pslow-tests
```

## Categorization Guidelines

### Service Manager Module Specific Rules

**CORE Category**:
- Model classes (`PeeGeeQInstance`, `ServiceHealth`)
- Load balancer logic and algorithms
- Utility functions and constants
- JSON serialization/deserialization
- Configuration validation

**INTEGRATION Category**:
- Consul integration tests
- Service discovery and registration
- HTTP endpoint tests
- Health check validation
- TestContainers-based tests
- Complete service manager workflows

**PERFORMANCE Category**:
- Load balancing performance under load
- Service discovery throughput
- Health check response times
- Resource usage monitoring
- Scalability validation

**FLAKY Category**:
- Tests requiring manual Consul setup
- Environment-dependent tests
- Tests with timing dependencies
- Tests requiring specific network configuration

## Development Workflow

### 1. Daily Development (TDD Cycle)
```bash
# Run core tests frequently during development
mvn test                    # ~5 seconds
```

### 2. Pre-commit Validation
```bash
# Validate integration before committing
mvn test -Pintegration-tests    # ~1-2 minutes
```

### 3. CI/CD Pipeline
```bash
# Comprehensive validation in CI
mvn test -Pall-tests           # ~5-10 minutes
```

### 4. Performance Monitoring
```bash
# Regular performance benchmarks
mvn test -Pperformance-tests   # ~3-5 minutes
```

## Helper Script

Use the provided helper script for convenient test execution:

```bash
# Make executable (first time only)
chmod +x run-tests.sh

# Run different test categories
./run-tests.sh core          # Core tests (~5 seconds)
./run-tests.sh integration   # Integration tests (~1-2 minutes)
./run-tests.sh performance   # Performance tests (~3-5 minutes)
./run-tests.sh all           # All tests (~5-10 minutes)
```

## Measured Performance

Based on actual measurements:

| Profile | Execution Time | Use Case |
|---------|---------------|----------|
| core-tests | ~5 seconds | Daily development, TDD |
| integration-tests | ~1-2 minutes | Pre-commit validation |
| performance-tests | ~3-5 minutes | Performance monitoring |
| all-tests | ~5-10 minutes | CI/CD comprehensive testing |

## Best Practices

1. **Keep CORE tests fast** - Under 1 second per test
2. **Use TestContainers for INTEGRATION** - Real infrastructure, no manual setup
3. **Mock external dependencies in CORE** - Fast, reliable, repeatable
4. **Move flaky tests to FLAKY category** - Don't let them break the build
5. **Measure and document performance** - Update this guide with actual timings
6. **Use appropriate parallel execution** - Methods for CORE, classes for INTEGRATION
7. **Set realistic timeouts** - 10s for CORE, 3m for INTEGRATION, 5m for PERFORMANCE

## Troubleshooting

### Tests Running Too Long
- Check if tests are properly categorized
- Verify Maven profile is active: `mvn help:active-profiles`
- Look for tests without `@Tag` annotations

### Integration Tests Failing
- Ensure TestContainers is working: `docker version`
- Check Consul container startup in logs
- Verify port conflicts with other services

### Performance Tests Inconsistent
- Run on dedicated hardware when possible
- Warm up JVM before measurements
- Account for system load and resource availability

---

*This guide is maintained as part of the peegeeq-service-manager module. Update performance measurements and examples as the codebase evolves.*
