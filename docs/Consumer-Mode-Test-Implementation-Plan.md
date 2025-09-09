# Consumer Mode Test Implementation Plan

## Overview

This document outlines the comprehensive test implementation plan for the new Consumer Mode features in PeeGeeQ Native Queue. The consumer mode implementation introduces three operational modes: `LISTEN_NOTIFY_ONLY`, `POLLING_ONLY`, and `HYBRID`, each requiring thorough testing to ensure production readiness.

## Current Test Coverage Status

- **Current Coverage**: ~15% (4 basic integration tests)
- **Required Additional Tests**: ~85 new test methods across 10 categories
- **Implementation Priority**: High → Medium → Low
- **Estimated Timeline**: 2-3 days for comprehensive coverage

## Test Categories and Implementation Plan

### 1. Consumer Mode Configuration Tests

#### A. ConsumerConfig Validation Tests
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerConfigValidationTest.java`
**Priority**: HIGH
**Status**: Missing

```java
@Test void testNegativePollingInterval()
@Test void testNullPollingInterval() 
@Test void testZeroBatchSize()
@Test void testNegativeBatchSize()
@Test void testZeroConsumerThreads()
@Test void testNegativeConsumerThreads()
@Test void testMaximumPollingInterval()
@Test void testMaximumBatchSize()
@Test void testMaximumConsumerThreads()
```

**Key Validation Rules**:
- Polling interval must be positive for POLLING_ONLY mode
- Batch size must be positive
- Consumer threads must be >= 1
- Maximum reasonable limits for all parameters

#### B. ConsumerMode Enum Tests
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeTest.java`
**Priority**: MEDIUM
**Status**: Missing

```java
@Test void testEnumValues()
@Test void testEnumSerialization()
@Test void testEnumDeserialization()
@Test void testEnumToString()
@Test void testEnumValueOf()
```

### 2. Consumer Mode Behavior Tests

#### A. LISTEN_NOTIFY_ONLY Edge Cases
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ListenNotifyOnlyEdgeCaseTest.java`
**Priority**: HIGH
**Status**: Missing

**Critical Test Cases**:
- Existing messages processing after LISTEN setup
- Connection loss and recovery scenarios
- Channel name special characters handling
- Large payload processing
- Concurrent producer scenarios
- Shutdown during message processing

#### B. POLLING_ONLY Edge Cases
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PollingOnlyEdgeCaseTest.java`
**Priority**: HIGH
**Status**: Missing

**Critical Test Cases**:
- Very fast/slow polling intervals
- Database lock scenarios
- High concurrency situations
- Large batch processing
- Empty queue handling
- Stuck message recovery

#### C. HYBRID Mode Edge Cases
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/HybridModeEdgeCaseTest.java`
**Priority**: HIGH
**Status**: Missing

**Critical Test Cases**:
- Partial mechanism failure scenarios
- Message ordering consistency
- Resource cleanup validation
- Performance under load

### 3. Configuration Integration Tests

#### A. Property File Integration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModePropertyIntegrationTest.java`
**Priority**: MEDIUM
**Status**: Missing

**Test Scenarios**:
- All property file configurations with consumer modes
- Property override behavior
- Invalid property value handling
- Configuration precedence rules

#### B. PeeGeeQConfiguration Integration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PeeGeeQConfigurationConsumerModeTest.java`
**Priority**: MEDIUM
**Status**: Missing

### 4. Factory Pattern Tests

#### A. QueueFactory Consumer Creation
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/QueueFactoryConsumerModeTest.java`
**Priority**: HIGH
**Status**: Missing

**Critical Validations**:
- Type safety for ConsumerConfig parameter
- Null and invalid config handling
- Backward compatibility preservation
- Concurrent consumer creation

#### B. Type Safety Tests
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeTypeSafetyTest.java`
**Priority**: HIGH
**Status**: Missing

### 5. Concurrency and Threading Tests

#### A. Multi-Consumer Tests
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MultiConsumerModeTest.java`
**Priority**: MEDIUM
**Status**: Missing

**Test Scenarios**:
- Multiple consumers with same/different modes
- Consumer mode isolation
- Thread safety across modes

#### B. Resource Management Tests
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeResourceManagementTest.java`
**Priority**: HIGH
**Status**: Missing

**Resource Validation**:
- Connection pool usage by mode
- Vert.x instance sharing
- Scheduler resource management
- Memory usage patterns
- Cleanup on shutdown

### 6. Error Handling and Recovery Tests

#### A. Failure Scenarios
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeFailureTest.java`
**Priority**: HIGH
**Status**: Missing

**Critical Failure Cases**:
- Channel name collisions
- Database connection failures
- Partial mode failures
- Recovery after failure
- Exception handling in handlers

#### B. Graceful Degradation
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeGracefulDegradationTest.java`
**Priority**: MEDIUM
**Status**: Missing

### 7. Performance and Load Tests

#### A. Performance Comparison
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModePerformanceTest.java`
**Priority**: MEDIUM
**Status**: Missing

**Performance Metrics**:
- Throughput by mode
- Latency measurements
- Resource usage comparison
- Mode performance ranking

#### B. Load Testing
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeLoadTest.java`
**Priority**: LOW
**Status**: Missing

### 8. Integration with Existing Features

#### A. Consumer Group Integration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeConsumerGroupTest.java`
**Priority**: MEDIUM
**Status**: Missing - **BLOCKED** (Requires Step 7 implementation)

#### B. Metrics Integration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeMetricsTest.java`
**Priority**: MEDIUM
**Status**: Missing

#### C. Circuit Breaker Integration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeCircuitBreakerTest.java`
**Priority**: LOW
**Status**: Missing

### 9. Backward Compatibility Tests

#### A. API Compatibility
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeBackwardCompatibilityTest.java`
**Priority**: HIGH
**Status**: Missing

#### B. Configuration Migration
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeConfigurationMigrationTest.java`
**Priority**: MEDIUM
**Status**: Missing

### 10. Documentation and Examples Tests

#### A. Example Code Validation
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeExampleTest.java`
**Priority**: LOW
**Status**: Missing

## Implementation Strategy

### Phase 1: Critical Foundation (HIGH Priority)
1. **ConsumerConfig Validation Tests** - Ensure configuration robustness
2. **Edge Case Tests** - Cover all three consumer modes thoroughly
3. **Factory Pattern Tests** - Validate type safety and creation logic
4. **Resource Management Tests** - Prevent resource leaks
5. **Error Handling Tests** - Ensure graceful failure handling
6. **Backward Compatibility Tests** - Preserve existing functionality

### Phase 2: Integration and Performance (MEDIUM Priority)
7. **Configuration Integration Tests** - Validate property file integration
8. **Multi-Consumer Tests** - Test concurrency scenarios
9. **Performance Tests** - Measure and compare mode performance
10. **Metrics Integration Tests** - Ensure proper monitoring

### Phase 3: Advanced Features (LOW Priority)
11. **Load Testing** - Stress test under high load
12. **Circuit Breaker Integration** - Advanced failure handling
13. **Documentation Tests** - Validate examples and documentation

## Success Criteria

- **100% test coverage** for all new consumer mode features
- **All edge cases** properly handled with appropriate error messages
- **Performance benchmarks** established for each consumer mode
- **Backward compatibility** fully preserved and tested
- **Resource management** validated with no leaks
- **Production readiness** confirmed through comprehensive testing

## Test Implementation Guidelines

### Testing Standards
- **Use TestContainers**: Standardized PostgreSQL container `postgres:15.13-alpine3.20`
- **Follow Existing Patterns**: Maintain consistency with `ConsumerModeIntegrationTest.java`
- **Comprehensive Assertions**: Test both positive and negative scenarios
- **Resource Cleanup**: Ensure proper cleanup in `@AfterEach` methods
- **Logging Validation**: Verify appropriate log levels and messages
- **Performance Baselines**: Establish measurable performance criteria

### Test Data Management
- **Isolated Test Topics**: Use unique topic names per test method
- **Clean State**: Reset database state between tests
- **Realistic Payloads**: Test with various message sizes and types
- **Concurrent Scenarios**: Use `CountDownLatch` and `AtomicReference` patterns

### Error Testing Patterns
- **Expected Exceptions**: Use `assertThrows()` for validation failures
- **Timeout Handling**: Set appropriate timeouts for async operations
- **Failure Recovery**: Test system behavior after failures
- **Resource Exhaustion**: Test behavior under resource constraints

### Performance Testing Standards
- **Baseline Metrics**: Establish throughput and latency baselines
- **Load Patterns**: Test sustained load, burst traffic, and gradual ramp-up
- **Resource Monitoring**: Monitor memory, CPU, and database connections
- **Comparative Analysis**: Compare performance across consumer modes

## Implementation Checklist

### Phase 1: Critical Foundation Tests
- [ ] `ConsumerConfigValidationTest.java` - Configuration validation
- [ ] `ListenNotifyOnlyEdgeCaseTest.java` - LISTEN_NOTIFY_ONLY edge cases
- [ ] `PollingOnlyEdgeCaseTest.java` - POLLING_ONLY edge cases
- [ ] `HybridModeEdgeCaseTest.java` - HYBRID mode edge cases
- [ ] `QueueFactoryConsumerModeTest.java` - Factory pattern validation
- [ ] `ConsumerModeTypeSafetyTest.java` - Type safety validation
- [ ] `ConsumerModeResourceManagementTest.java` - Resource management
- [ ] `ConsumerModeFailureTest.java` - Error handling scenarios
- [ ] `ConsumerModeBackwardCompatibilityTest.java` - API compatibility

### Phase 2: Integration and Performance Tests
- [ ] `ConsumerModePropertyIntegrationTest.java` - Property integration
- [ ] `PeeGeeQConfigurationConsumerModeTest.java` - Configuration integration
- [ ] `MultiConsumerModeTest.java` - Multi-consumer scenarios
- [ ] `ConsumerModePerformanceTest.java` - Performance comparison
- [ ] `ConsumerModeMetricsTest.java` - Metrics integration
- [ ] `ConsumerModeGracefulDegradationTest.java` - Graceful degradation

### Phase 3: Advanced Feature Tests
- [ ] `ConsumerModeLoadTest.java` - Load testing
- [ ] `ConsumerModeCircuitBreakerTest.java` - Circuit breaker integration
- [ ] `ConsumerModeConfigurationMigrationTest.java` - Migration scenarios
- [ ] `ConsumerModeExampleTest.java` - Documentation validation
- [ ] `ConsumerModeTest.java` - Enum testing
- [ ] `ConsumerModeConsumerGroupTest.java` - Consumer group integration (BLOCKED)

## Expected Outcomes

### Test Coverage Metrics
- **Line Coverage**: Target 95%+ for new consumer mode code
- **Branch Coverage**: Target 90%+ for all conditional logic
- **Integration Coverage**: 100% of consumer mode combinations tested

### Performance Benchmarks
- **LISTEN_NOTIFY_ONLY**: Lowest latency, highest real-time performance
- **POLLING_ONLY**: Predictable throughput, higher database load
- **HYBRID**: Balanced performance with maximum reliability

### Quality Gates
- **Zero Resource Leaks**: All tests must pass resource cleanup validation
- **Exception Safety**: All error scenarios properly handled
- **Backward Compatibility**: 100% existing functionality preserved
- **Documentation Accuracy**: All examples and documentation validated

## Next Steps

1. **Start with Phase 1** - Implement critical foundation tests
2. **Use TestContainers** - Ensure consistent PostgreSQL testing environment
3. **Follow existing patterns** - Maintain consistency with current test structure
4. **Validate incrementally** - Run tests after each implementation
5. **Document findings** - Record performance characteristics and limitations
6. **Create test execution plan** - Define order and dependencies for test implementation
