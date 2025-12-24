# Legacy JDBC to Vert.x 5.x Migration Plan

```
    ____            ______            ____
   / __ \___  ___  / ____/__  ___    / __ \
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \  / / / /
 / ____/  __/  __/ /_/ /  __/  __/ / /_/ /
/_/    \___/\___/\____/\___/\___/  \___\_\

PostgreSQL Event-Driven Queue System
```

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: September 2025  
**Version**: Comprehensive Legacy JDBC Migration Plan  

---

## Executive Summary

This document outlines a comprehensive, phased migration plan to eliminate legacy JDBC code from PeeGeeQ core components (peegeeq-db, peegeeq-outbox, peegeeq-api) and fully adopt Vert.x 5.x reactive patterns. The migration follows the 10 core coding principles, leverages existing performance optimizations, and ensures comprehensive testing at each stage.

## Current State Analysis

### Legacy JDBC Components Identified

**peegeeq-db Module:**
- `PgConnectionManager` - Contains deprecated DataSource methods
- `PgConnectionProvider` - Uses CompletableFuture and blocking Connection APIs
- `PeeGeeQManager` - Creates temporary DataSource for legacy components
- Core infrastructure components (metrics, health checks, migration) still use JDBC

**peegeeq-outbox Module:**
- `OutboxConsumer` - Has deprecated getDataSource() method
- `OutboxProducer` - Uses CompletableFuture patterns
- Legacy DataSource fallback patterns for backward compatibility

**peegeeq-api Module:**
- `ConnectionProvider` interface - Defines JDBC-based contracts
- HikariCP dependencies still present in pom.xml

### Performance Impact
- Current hybrid approach creates dual connection management overhead
- JDBC blocking operations limit scalability compared to reactive patterns
- Connection pool fragmentation between JDBC and reactive pools

## Migration Phases

### Phase 1: Foundation and Planning (Week 1)
**Objective**: Establish migration infrastructure and comprehensive test coverage

#### Phase 1.1: Test Infrastructure Enhancement
- **Task 1.1.1**: Comprehensive test pattern audit (54+ test classes affected)
  - Audit peegeeq-db tests (27 classes using JDBC patterns)
  - Audit peegeeq-outbox tests (27 classes with CompletableFuture dependencies)
  - Identify cross-module test dependencies and integration patterns
  - Document current test coverage and performance baselines
- **Task 1.1.2**: Create reactive test framework and utilities
  - Implement ReactiveTestUtils with Future-based helpers
  - Create VertxTestContext patterns for async testing
  - Establish reactive test naming conventions
  - Build automated test migration validation tools
- **Task 1.1.3**: Establish performance benchmarks for current JDBC operations
  - Measure current test execution times and resource usage
  - Document JDBC test performance characteristics
  - Create performance comparison framework for reactive tests
- **Task 1.1.4**: Create migration validation test suite
  - Implement automated test coverage validation
  - Create compatibility test framework for dual-pattern validation
  - Establish test migration completion criteria

#### Phase 1.2: API Design and Contracts
- **Task 1.2.1**: Design new reactive ConnectionProvider interface
- **Task 1.2.2**: Create migration compatibility layer
- **Task 1.2.3**: Define Vert.x 5.x composable Future patterns for all operations
- **Task 1.2.4**: Document new reactive API contracts

#### Phase 1.3: Performance Baseline
- **Task 1.3.1**: Measure current JDBC operation performance
- **Task 1.3.2**: Establish reactive operation performance targets
- **Task 1.3.3**: Create performance monitoring for migration progress

**Deliverables:**
- Enhanced test infrastructure
- New reactive API specifications
- Performance baseline documentation
- Migration validation framework

**Testing Strategy:**
- All existing tests must pass during transition
- Create reactive test equivalents for all 54+ affected test classes
- Parallel test development: maintain both JDBC and reactive test suites
- Automated validation that every JDBC test has a reactive equivalent
- Performance benchmarks documented and validated for reactive tests

### Phase 2: Core Infrastructure Migration (Week 2-3)
**Objective**: Migrate core peegeeq-db infrastructure to pure reactive patterns

#### Phase 2.1: Connection Management Modernization
- **Task 2.1.1**: Remove deprecated DataSource methods from PgConnectionManager
  - Migrate PgConnectionManagerTest (JDBC → Reactive patterns)
  - Update PgListenerConnectionTest for reactive connection handling
  - Create PgConnectionManagerReactiveTest with VertxTestContext
- **Task 2.1.2**: Implement pure reactive connection acquisition patterns
  - Migrate PgClientTest from blocking Connection to reactive Pool
  - Update connection pooling tests (PoolingUnderLoad.java)
  - Create reactive connection validation test utilities
- **Task 2.1.3**: Migrate PgConnectionProvider to Future-based APIs
  - Replace CompletableFuture.getConnectionAsync() with Future<SqlConnection>
  - Update all ConnectionProvider interface tests
  - Create ReactiveConnectionProviderTest with new interface contracts
- **Task 2.1.4**: Update connection pooling to use only reactive pools
  - Migrate connection pool performance tests
  - Update resource management and cleanup tests
  - Validate reactive pool behavior under load

#### Phase 2.2: Core Component Migration
- **Task 2.2.1**: Migrate SchemaMigrationManager to reactive patterns
  - Convert SchemaMigrationManagerTest from DataSource to Pool-based testing
  - Create ReactiveSchemaMigrationManagerTest with Future-based migration validation
  - Update migration performance and reliability tests
- **Task 2.2.2**: Convert PeeGeeQMetrics to use reactive database operations
  - Migrate PeeGeeQMetricsTest from JDBC to reactive database queries
  - Update metrics collection tests to use reactive patterns
  - Validate metrics accuracy with reactive operations
- **Task 2.2.3**: Update HealthCheckManager to use Vert.x 5.x patterns
  - Convert HealthCheckManagerTest to reactive health check validation
  - Update health check performance and timeout tests
  - Implement reactive health check monitoring
- **Task 2.2.4**: Migrate CircuitBreakerManager database operations
  - Update CircuitBreakerManagerTest for reactive database operations
  - Test circuit breaker behavior with reactive patterns
  - Validate resilience patterns with Future-based operations

#### Phase 2.3: PeeGeeQManager Modernization
- **Task 2.3.1**: Remove temporary DataSource creation
- **Task 2.3.2**: Implement pure reactive initialization patterns
- **Task 2.3.3**: Update component dependencies to use reactive APIs
- **Task 2.3.4**: Ensure proper resource management with reactive cleanup

**Deliverables:**
- Fully reactive peegeeq-db core infrastructure
- Eliminated temporary DataSource usage
- Updated component initialization patterns
- Comprehensive reactive test coverage

**Testing Strategy:**
- Migrate 27 peegeeq-db test classes to reactive patterns
- Create parallel reactive test suite alongside existing JDBC tests
- Integration tests for all migrated components using VertxTestContext
- Performance validation against baseline with reactive test framework
- Resource leak detection tests for reactive pools and connections
- Backward compatibility verification during transition period
- Automated validation that all JDBC tests have reactive equivalents

### Phase 3: Outbox Pattern Migration (Week 4)
**Objective**: Complete migration of peegeeq-outbox to pure reactive patterns

#### Phase 3.1: OutboxConsumer Modernization
- **Task 3.1.1**: Remove deprecated getDataSource() method
  - Migrate OutboxConsumerCrashRecoveryTest from DataSource verification to reactive patterns
  - Update StuckMessageRecoveryIntegrationTest to use reactive database operations
  - Convert direct database verification tests to use reactive pools
- **Task 3.1.2**: Implement pure reactive message processing
  - Update OutboxBasicTest and OutboxQueueTest for reactive message processing
  - Migrate consumer group tests (OutboxConsumerGroupTest) to reactive patterns
  - Convert message reliability tests to use Future-based validation
- **Task 3.1.3**: Convert CompletableFuture patterns to Vert.x 5.x Futures
  - Migrate OutboxCompletableFutureExceptionTest → OutboxReactiveFutureExceptionTest
  - Convert OutboxExceptionHandlingDemonstrationTest to reactive patterns
  - Update all CompletableFuture.get() calls to reactive Future handling
- **Task 3.1.4**: Optimize reactive pool usage for consumer operations
  - Update OutboxPerformanceTest for reactive throughput measurement
  - Migrate PerformanceBenchmarkTest to reactive performance validation
  - Test consumer operations under load with reactive pools

#### Phase 3.2: OutboxProducer Enhancement
- **Task 3.2.1**: Eliminate CompletableFuture usage
  - Migrate ReactiveOutboxProducerTest from CompletableFuture to Future patterns
  - Update OutboxRetryLogicTest and OutboxRetryResilienceTest for reactive patterns
  - Convert all producer.send().get() calls to reactive Future handling
- **Task 3.2.2**: Implement composable Future chains for message production
  - Update OutboxConfigurationIntegrationTest for reactive message production
  - Migrate OutboxParallelProcessingTest to use reactive concurrency patterns
  - Convert message production tests to use .compose() chains
- **Task 3.2.3**: Optimize batch operations using reactive patterns
  - Update batch processing tests for reactive throughput optimization
  - Migrate OutboxRetryConcurrencyTest to reactive concurrency patterns
  - Test batch operations with pipelined clients
- **Task 3.2.4**: Enhance error handling with .recover() patterns
  - Migrate OutboxErrorHandlingTest and OutboxDirectExceptionHandlingTest
  - Update OutboxEdgeCasesTest for reactive error handling patterns
  - Convert retry and resilience tests to use .recover() and .onFailure() patterns

#### Phase 3.3: Outbox Infrastructure
- **Task 3.3.1**: Update OutboxFactory to use only reactive connections
- **Task 3.3.2**: Migrate retry and resilience components
- **Task 3.3.3**: Implement reactive transaction management
- **Task 3.3.4**: Optimize performance using pipelined clients

**Deliverables:**
- Fully reactive outbox implementation
- Eliminated CompletableFuture dependencies
- Enhanced performance through reactive optimizations
- Comprehensive outbox test suite

**Testing Strategy:**
- Migrate 27 peegeeq-outbox test classes from CompletableFuture to reactive Future patterns
- Convert all OutboxCompletableFutureExceptionTest patterns to reactive equivalents
- Outbox pattern integration tests using VertxTestContext and reactive validation
- Message delivery reliability tests with Future-based verification
- Performance comparison between JDBC and reactive versions
- Concurrent operation stress tests using reactive concurrency patterns
- Automated validation of CompletableFuture → Future migration completeness

### Phase 4: API Layer Modernization (Week 5)
**Objective**: Update peegeeq-api to define pure reactive contracts

#### Phase 4.1: Interface Modernization
- **Task 4.1.1**: Create new ReactiveConnectionProvider interface
- **Task 4.1.2**: Define Future-based database operation contracts
- **Task 4.1.3**: Update DatabaseService interface for reactive patterns
- **Task 4.1.4**: Remove JDBC-specific method signatures

#### Phase 4.2: Dependency Management
- **Task 4.2.1**: Remove HikariCP dependency from pom.xml
- **Task 4.2.2**: Update to pure Vert.x 5.x dependencies
- **Task 4.2.3**: Clean up unused JDBC imports and references
- **Task 4.2.4**: Update documentation for new reactive APIs

#### Phase 4.3: Compatibility Layer
- **Task 4.3.1**: Implement migration bridge for existing consumers
- **Task 4.3.2**: Provide clear migration path documentation
- **Task 4.3.3**: Create deprecation warnings for old patterns
- **Task 4.3.4**: Establish timeline for compatibility layer removal

**Deliverables:**
- Pure reactive API definitions
- Cleaned dependency management
- Migration compatibility layer
- Updated API documentation

**Testing Strategy:**
- Create new ReactiveConnectionProvider interface tests
- API contract validation tests for all new reactive interfaces
- Backward compatibility verification during transition period
- Integration tests with all modules using new reactive APIs
- Cross-module test migration (examples, REST API, bitemporal tests)
- Documentation accuracy validation with updated reactive patterns
- Automated verification that all interface changes have corresponding test updates

### Phase 5: Integration and Optimization (Week 6)
**Objective**: Complete integration testing and performance optimization

#### Phase 5.1: End-to-End Integration
- **Task 5.1.1**: Comprehensive integration testing across all modules
  - Execute complete reactive test suite (54+ migrated test classes)
  - Validate end-to-end reactive patterns across peegeeq-db, peegeeq-outbox, peegeeq-api
  - Run cross-module integration tests with reactive interfaces
- **Task 5.1.2**: Validate all example applications work with reactive patterns
  - Migrate JdbcIntegrationHybridExample to pure reactive patterns
  - Update TransactionParticipationAdvancedExampleTest for reactive transactions
  - Convert all example tests from CompletableFuture.get() to reactive Future handling
- **Task 5.1.3**: Test complex scenarios (transactions, error handling, recovery)
  - Validate reactive transaction management across all components
  - Test error handling and recovery with .recover() and .onFailure() patterns
  - Verify circuit breaker and resilience patterns work with reactive operations
- **Task 5.1.4**: Verify resource management and cleanup
  - Test reactive pool cleanup and resource management
  - Validate proper Future completion and error propagation
  - Ensure no resource leaks in reactive patterns

#### Phase 5.2: Performance Optimization
- **Task 5.2.1**: Apply Vert.x 5.x performance optimizations from guide
- **Task 5.2.2**: Implement pipelined client patterns for maximum throughput
- **Task 5.2.3**: Optimize connection pool configurations
- **Task 5.2.4**: Validate performance improvements against baseline

#### Phase 5.3: Documentation and Training
- **Task 5.3.1**: Update all documentation for reactive patterns
- **Task 5.3.2**: Create migration guide for external consumers
- **Task 5.3.3**: Document new performance characteristics
- **Task 5.3.4**: Provide troubleshooting guide for reactive patterns

**Deliverables:**
- Fully integrated reactive system
- Performance-optimized configuration
- Complete documentation update
- Migration guide for consumers

**Testing Strategy:**
- Execute complete migrated test suite (54+ reactive test classes)
- Full system integration tests using only reactive patterns
- Performance validation and benchmarking comparing JDBC vs reactive performance
- Load testing with reactive patterns and pipelined clients
- Automated test migration completion validation
- Documentation accuracy verification with reactive examples
- Final cleanup of legacy JDBC test classes

## Risk Mitigation Strategies

### Technical Risks
1. **Performance Regression**: Continuous benchmarking at each phase
2. **Resource Leaks**: Comprehensive resource management testing
3. **Compatibility Issues**: Gradual migration with compatibility layers
4. **Complex Transaction Scenarios**: Dedicated transaction testing phase

### Operational Risks
1. **Breaking Changes**: Phased rollout with backward compatibility
2. **Learning Curve**: Comprehensive documentation and examples
3. **Testing Coverage**: Enhanced test suite before migration
4. **Rollback Capability**: Maintain compatibility layers during transition

## Success Criteria

### Performance Targets
- **Throughput**: Maintain or improve current message processing rates
- **Latency**: Achieve <50ms P95 latency for database operations
- **Resource Usage**: Reduce connection pool overhead by 30%
- **Scalability**: Support 2x concurrent operations with reactive patterns

### Quality Metrics
- **Test Coverage**: Maintain >90% test coverage throughout migration
- **Zero Regressions**: All existing functionality preserved
- **Documentation**: 100% API documentation coverage
- **Performance**: Meet or exceed current performance benchmarks

### Compliance Requirements
- **Coding Principles**: Follow all 10 core coding principles
- **Vert.x 5.x Patterns**: Use composable Futures exclusively
- **Performance Guide**: Apply all optimizations from VertxPerformanceOptimization.md
- **Testing Standards**: Comprehensive test coverage at each phase

## Implementation Guidelines

### Development Practices
1. **Incremental Changes**: Small, testable changes following coding principles
2. **Test-First Approach**: Write reactive tests before implementation
3. **Performance Monitoring**: Continuous performance validation
4. **Code Review**: Mandatory review for all reactive pattern implementations

### Testing Requirements
1. **Unit Tests**: Test each component in isolation
2. **Integration Tests**: Validate component interactions
3. **Performance Tests**: Benchmark against current implementation
4. **Stress Tests**: Validate under high load conditions

### Documentation Standards
1. **API Documentation**: Complete Javadoc for all public APIs
2. **Migration Guides**: Step-by-step migration instructions
3. **Performance Characteristics**: Document performance implications
4. **Troubleshooting**: Common issues and solutions

## Timeline Summary

| Phase | Duration | Key Deliverables | Success Criteria |
|-------|----------|------------------|------------------|
| Phase 1 | Week 1 | Test infrastructure, API design | All tests pass, baseline established |
| Phase 2 | Week 2-3 | Core infrastructure migration | peegeeq-db fully reactive |
| Phase 3 | Week 4 | Outbox pattern migration | peegeeq-outbox fully reactive |
| Phase 4 | Week 5 | API layer modernization | peegeeq-api pure reactive |
| Phase 5 | Week 6 | Integration and optimization | Full system validation |

**Total Duration**: 6 weeks  
**Resource Requirements**: 1 senior developer, dedicated testing environment  
**Dependencies**: Vert.x 5.x expertise, PostgreSQL test infrastructure

## Next Steps

1. **Immediate Actions**:
   - Review and approve migration plan
   - Set up dedicated migration branch
   - Establish performance monitoring infrastructure
   - Create migration tracking dashboard

2. **Phase 1 Preparation**:
   - Audit current test coverage
   - Identify all JDBC usage patterns
   - Set up reactive testing framework
   - Create performance baseline measurements

3. **Resource Allocation**:
   - Assign dedicated developer for migration
   - Allocate testing environment resources
   - Schedule regular progress reviews
   - Plan stakeholder communication

This migration plan ensures a systematic, well-tested transition to pure Vert.x 5.x reactive patterns while maintaining system reliability and performance throughout the process.

## Technical Implementation Details

### Phase 1 Technical Specifications

#### Task 1.1.1: JDBC Pattern Audit
**Current JDBC Usage Identified:**
```java
// peegeeq-db/PgConnectionManager.java
@Deprecated
public DataSource getOrCreateDataSource(String serviceId, ...) {
    // Legacy JDBC DataSource creation
}

// peegeeq-db/PgConnectionProvider.java
@Override
public CompletableFuture<Connection> getConnectionAsync(String clientId) {
    return CompletableFuture.supplyAsync(() -> {
        // Blocking JDBC connection acquisition
    });
}

// peegeeq-outbox/OutboxConsumer.java
private DataSource getDataSource() {
    // Deprecated JDBC DataSource access
    return null; // Currently returns null
}
```

**Migration Target:**
```java
// New reactive patterns
public Future<SqlConnection> getConnectionReactive(String clientId) {
    return pool.getConnection()
        .onSuccess(conn -> logger.debug("Acquired reactive connection"))
        .onFailure(err -> logger.error("Connection failed", err));
}
```

#### Task 1.2.1: Reactive ConnectionProvider Design
**New Interface Specification:**
```java
public interface ReactiveConnectionProvider {
    Future<Pool> getReactivePool(String clientId);
    Future<SqlConnection> getConnection(String clientId);
    Future<Void> withConnection(String clientId, Function<SqlConnection, Future<Void>> operation);
    Future<Void> withTransaction(String clientId, Function<SqlConnection, Future<Void>> operation);
    boolean hasClient(String clientId);
    Future<Boolean> isHealthy();
}
```

### Phase 2 Technical Specifications

#### Task 2.1.1: PgConnectionManager Modernization
**Current Legacy Code:**
```java
// Remove this deprecated method
@Deprecated
public DataSource getOrCreateDataSource(String serviceId, ...) {
    throw new UnsupportedOperationException(
        "JDBC DataSource usage has been removed...");
}
```

**Replacement Implementation:**
```java
public Future<Pool> getOrCreateReactivePool(String serviceId,
                                          PgConnectionConfig connectionConfig,
                                          PgPoolConfig poolConfig) {
    return Future.succeededFuture(
        reactivePools.computeIfAbsent(serviceId,
            k -> createReactivePool(connectionConfig, poolConfig))
    );
}
```

#### Task 2.2.1: SchemaMigrationManager Migration
**Current JDBC Implementation:**
```java
public class SchemaMigrationManager {
    private final DataSource dataSource;

    public void migrate() {
        try (Connection conn = dataSource.getConnection()) {
            // Blocking JDBC migration
        }
    }
}
```

**Target Reactive Implementation:**
```java
public class ReactiveSchemaMigrationManager {
    private final Pool pool;

    public Future<Void> migrate() {
        return pool.withConnection(connection -> {
            return executeMigrationScripts(connection)
                .compose(v -> validateMigration(connection))
                .onSuccess(v -> logger.info("Migration completed"))
                .onFailure(err -> logger.error("Migration failed", err));
        });
    }
}
```

### Phase 3 Technical Specifications

#### Task 3.1.2: OutboxConsumer Reactive Processing
**Current CompletableFuture Pattern:**
```java
return Future.fromCompletionStage(
    CompletableFuture.runAsync(() -> {
        try {
            processMessageWithCompletion(message, messageId);
        } catch (Exception e) {
            logger.error("Failed to process message", e);
        }
    }, messageProcessingExecutor)
);
```

**Target Vert.x 5.x Pattern:**
```java
return pool.withConnection(connection -> {
    return processMessage(connection, message)
        .compose(result -> updateMessageStatus(connection, messageId, "COMPLETED"))
        .recover(error -> {
            logger.warn("Message processing failed, using recovery", error);
            return updateMessageStatus(connection, messageId, "FAILED")
                .compose(v -> Future.failedFuture(error));
        });
}).onSuccess(v -> logger.debug("Message processed successfully"))
  .onFailure(err -> logger.error("Message processing failed", err));
```

#### Task 3.2.1: OutboxProducer CompletableFuture Elimination
**Current Pattern:**
```java
public CompletableFuture<Void> send(T payload, Map<String, String> headers,
                                   String correlationId, String messageGroup) {
    // CompletableFuture-based implementation
}
```

**Target Reactive Pattern:**
```java
public Future<Void> send(T payload, Map<String, String> headers,
                        String correlationId, String messageGroup) {
    return pool.withTransaction(connection -> {
        return insertOutboxMessage(connection, payload, headers, correlationId, messageGroup)
            .compose(messageId -> notifyConsumers(messageId))
            .onSuccess(v -> logger.debug("Message sent successfully"))
            .onFailure(err -> logger.error("Failed to send message", err));
    });
}
```

### Phase 4 Technical Specifications

#### Task 4.1.1: ReactiveConnectionProvider Implementation
**Complete Interface:**
```java
public interface ReactiveConnectionProvider {
    /**
     * Gets a reactive pool for the specified client.
     * Uses Vert.x 5.x composable Future patterns.
     */
    Future<Pool> getReactivePool(String clientId);

    /**
     * Executes an operation with a connection from the pool.
     * Automatically handles connection lifecycle.
     */
    <T> Future<T> withConnection(String clientId,
                                Function<SqlConnection, Future<T>> operation);

    /**
     * Executes an operation within a transaction.
     * Provides automatic transaction management.
     */
    <T> Future<T> withTransaction(String clientId,
                                 Function<SqlConnection, Future<T>> operation);

    /**
     * Health check using reactive patterns.
     */
    Future<Boolean> isHealthy();

    /**
     * Graceful shutdown with resource cleanup.
     */
    Future<Void> close();
}
```

#### Task 4.2.1: Dependency Management Updates
**Remove from pom.xml:**
```xml
<!-- Remove these JDBC dependencies -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
```

**Keep only reactive dependencies:**
```xml
<!-- Keep these Vert.x 5.x dependencies -->
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-pg-client</artifactId>
</dependency>
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-sql-client</artifactId>
</dependency>
```

### Phase 5 Technical Specifications

#### Task 5.2.1: Performance Optimization Implementation
**Apply Vert.x 5.x Performance Guide Patterns:**
```java
// Use pipelined client for maximum throughput
SqlClient pipelinedClient = PgBuilder.client()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx)
    .build();

// Batch operations for high throughput
public Future<List<Long>> insertBatch(List<OutboxMessage> messages) {
    List<Tuple> batchParams = messages.stream()
        .map(this::createInsertTuple)
        .collect(Collectors.toList());

    return pipelinedClient.preparedQuery(INSERT_BATCH_SQL)
        .executeBatch(batchParams)
        .map(this::extractGeneratedIds);
}
```

#### Task 5.2.2: Connection Pool Optimization
**Optimized Pool Configuration:**
```java
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(100)  // Research-based optimization
    .setShared(true)  // Share across verticles
    .setMaxWaitQueueSize(1000)  // 10x pool size
    .setConnectionTimeout(30000)
    .setIdleTimeout(600000);

PgConnectOptions connectOptions = new PgConnectOptions()
    .setHost(config.getHost())
    .setPort(config.getPort())
    .setDatabase(config.getDatabase())
    .setUser(config.getUsername())
    .setPassword(config.getPassword())
    .setPipeliningLimit(1024);  // Enable pipelining
```

## Testing Strategy Details

### Unit Testing Patterns
```java
@Test
void testReactiveMessageProcessing() {
    OutboxMessage message = createTestMessage();

    consumer.processMessage(message)
        .onSuccess(result -> {
            // Verify message processed
            testContext.verify(() -> {
                assertEquals("COMPLETED", result.getStatus());
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}
```

### Integration Testing Patterns
```java
@Test
void testEndToEndReactiveFlow() {
    producer.send(testPayload, headers, correlationId, messageGroup)
        .compose(v -> waitForConsumerProcessing())
        .compose(v -> verifyMessageDelivery())
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
}
```

### Performance Testing Framework
```java
@Test
void testReactivePerformance() {
    int messageCount = 1000;
    long startTime = System.currentTimeMillis();

    List<Future> futures = IntStream.range(0, messageCount)
        .mapToObj(i -> producer.send(createMessage(i)))
        .collect(Collectors.toList());

    Future.all(futures)
        .onSuccess(v -> {
            long duration = System.currentTimeMillis() - startTime;
            double throughput = messageCount * 1000.0 / duration;
            logger.info("Reactive throughput: {} msg/sec", throughput);
            assertTrue(throughput > 500, "Should achieve >500 msg/sec");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}
```

## Migration Validation Checklist

### Phase Completion Criteria

**Phase 1 Complete When:**
- [ ] All JDBC usage patterns documented (54+ test classes analyzed)
- [ ] Reactive test framework established with ReactiveTestUtils
- [ ] Performance baseline measured for both JDBC and reactive patterns
- [ ] New API contracts defined with reactive interfaces
- [ ] Test migration strategy validated and automated tools created
- [ ] Parallel test development framework established

**Phase 2 Complete When:**
- [ ] All DataSource usage removed from peegeeq-db
- [ ] 27 peegeeq-db test classes migrated to reactive patterns
- [ ] Core components use only reactive patterns
- [ ] Integration tests pass with reactive infrastructure
- [ ] Performance meets or exceeds baseline with reactive tests
- [ ] Automated validation confirms all JDBC tests have reactive equivalents

**Phase 3 Complete When:**
- [ ] OutboxConsumer fully reactive
- [ ] OutboxProducer eliminates CompletableFuture
- [ ] 27 peegeeq-outbox test classes migrated from CompletableFuture to reactive Future
- [ ] All outbox tests pass with reactive patterns
- [ ] Message delivery reliability maintained with reactive validation
- [ ] Performance parity achieved between JDBC and reactive outbox operations

**Phase 4 Complete When:**
- [ ] API layer defines only reactive contracts
- [ ] JDBC dependencies removed
- [ ] Compatibility layer functional
- [ ] Documentation updated

**Phase 5 Complete When:**
- [ ] End-to-end integration validated with complete reactive test suite
- [ ] All 54+ test classes successfully migrated and passing
- [ ] Performance optimizations applied and validated
- [ ] Cross-module tests (examples, REST API, bitemporal) fully reactive
- [ ] All documentation updated with reactive patterns
- [ ] Migration guide complete with test migration examples
- [ ] Legacy JDBC test classes removed after validation
- [ ] Automated test migration validation passes 100%

This comprehensive technical specification ensures each phase has clear, measurable deliverables and follows the established coding principles and performance guidelines.

## Test Migration Strategy

### Critical Test Dependencies Identified

The migration will impact **extensive test infrastructure** across all modules. Analysis reveals:

#### **peegeeq-db Test Dependencies (27 test classes)**
- **Connection Tests**: `PgConnectionManagerTest`, `PgListenerConnectionTest`, `PgClientTest`
- **Infrastructure Tests**: `SchemaMigrationManagerTest`, `PeeGeeQMetricsTest`, `HealthCheckManagerTest`
- **Integration Tests**: `PeeGeeQManagerIntegrationTest`, `BaseIntegrationTest`
- **Performance Tests**: `PeeGeeQPerformanceTest`, `VertxPerformanceOptimizerTest`
- **Configuration Tests**: 15+ test classes using JDBC patterns

#### **peegeeq-outbox Test Dependencies (27 test classes)**
- **CompletableFuture Tests**: `OutboxCompletableFutureExceptionTest`, `OutboxExceptionHandlingDemonstrationTest`
- **Integration Tests**: `ReactiveOutboxProducerTest`, `OutboxConsumerCrashRecoveryTest`
- **Performance Tests**: `OutboxPerformanceTest`, `PerformanceBenchmarkTest`
- **Resilience Tests**: `OutboxRetryResilienceTest`, `CircuitBreakerRecoveryTest`

#### **Cross-Module Test Dependencies**
- **Example Tests**: `JdbcIntegrationHybridExample`, `TransactionParticipationAdvancedExampleTest`
- **REST API Tests**: Database setup and template management tests
- **Bitemporal Tests**: Performance and integration tests using JDBC verification

### Phase-Specific Test Migration Plan

#### **Phase 1: Test Infrastructure Preparation**

**Task 1.1.1: Test Pattern Audit and Classification**
```java
// Current JDBC Test Patterns to Migrate:

// Pattern 1: Direct DataSource Usage
@Test
void testWithDataSource() {
    DataSource dataSource = connectionManager.getOrCreateDataSource("test", config, poolConfig);
    try (Connection conn = dataSource.getConnection()) {
        // JDBC operations
    }
}

// Pattern 2: CompletableFuture Testing
@Test
void testCompletableFuturePattern() {
    producer.send(message).get(5, TimeUnit.SECONDS);  // Blocking .get()
    consumer.subscribe(message -> {
        return CompletableFuture.failedFuture(new RuntimeException("test"));
    });
}

// Pattern 3: Blocking Connection Operations
@Test
void testConnectionOperations() throws SQLException {
    try (Connection connection = pgClient.getConnection()) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            // Blocking JDBC operations
        }
    }
}
```

**Task 1.1.2: Reactive Test Framework Creation**
```java
// New Reactive Test Patterns:

// Pattern 1: Reactive Pool Testing
@Test
void testWithReactivePool(VertxTestContext testContext) {
    connectionManager.getOrCreateReactivePool("test", config, poolConfig)
        .compose(pool -> pool.withConnection(connection -> {
            return connection.preparedQuery("SELECT 1").execute();
        }))
        .onSuccess(result -> {
            testContext.verify(() -> {
                assertEquals(1, result.iterator().next().getInteger(0));
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}

// Pattern 2: Vert.x 5.x Future Testing
@Test
void testReactiveFuturePattern(VertxTestContext testContext) {
    producer.send(message)
        .compose(v -> waitForProcessing())
        .onSuccess(result -> {
            testContext.verify(() -> {
                assertNotNull(result);
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}

// Pattern 3: Reactive Consumer Testing
@Test
void testReactiveConsumer(VertxTestContext testContext) {
    consumer.subscribe(message -> {
        return Future.succeededFuture()  // Reactive Future instead of CompletableFuture
            .onSuccess(v -> logger.info("Message processed"))
            .onFailure(err -> logger.error("Processing failed", err));
    });
}
```

**Task 1.1.3: Test Utilities and Helpers**
```java
// Reactive Test Utilities
public class ReactiveTestUtils {

    public static <T> void awaitFuture(Future<T> future, VertxTestContext testContext) {
        future.onSuccess(result -> testContext.completeNow())
              .onFailure(testContext::failNow);
    }

    public static Future<Void> verifyDatabaseState(Pool pool, String query, Object expectedValue) {
        return pool.withConnection(connection -> {
            return connection.preparedQuery(query).execute()
                .map(rowSet -> {
                    assertEquals(expectedValue, rowSet.iterator().next().getValue(0));
                    return null;
                });
        });
    }

    public static Future<Integer> countMessages(Pool pool, String topic) {
        return pool.withConnection(connection -> {
            return connection.preparedQuery("SELECT COUNT(*) FROM outbox WHERE topic = $1")
                .execute(Tuple.of(topic))
                .map(rowSet -> rowSet.iterator().next().getInteger(0));
        });
    }
}
```

#### **Phase 2: Core Infrastructure Test Migration**

**Task 2.1.1: Connection Management Test Updates**
```java
// Before: PgConnectionManagerTest
@Test
void testGetOrCreateDataSource() {
    var dataSource = connectionManager.getOrCreateDataSource("test-service", connectionConfig, poolConfig);
    assertNotNull(dataSource);
}

// After: PgConnectionManagerTest (Reactive)
@Test
void testGetOrCreateReactivePool(VertxTestContext testContext) {
    connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig)
        .onSuccess(pool -> {
            testContext.verify(() -> {
                assertNotNull(pool);
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}
```

**Task 2.2.1: Infrastructure Component Test Migration**
```java
// Before: SchemaMigrationManagerTest
@Test
void testMigration() {
    SchemaMigrationManager migrationManager = new SchemaMigrationManager(dataSource);
    migrationManager.migrate();
    // Verify migration completed
}

// After: ReactiveSchemaMigrationManagerTest
@Test
void testReactiveMigration(VertxTestContext testContext) {
    ReactiveSchemaMigrationManager migrationManager = new ReactiveSchemaMigrationManager(pool);
    migrationManager.migrate()
        .compose(v -> verifyMigrationCompleted(pool))
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
}
```

#### **Phase 3: Outbox Test Migration**

**Task 3.1.1: CompletableFuture Test Elimination**
```java
// Before: OutboxCompletableFutureExceptionTest
@Test
void testFailedCompletableFuture() throws Exception {
    producer.send(testMessage).get(5, TimeUnit.SECONDS);

    consumer.subscribe(message -> {
        return CompletableFuture.failedFuture(
            new RuntimeException("INTENTIONAL FAILURE")
        );
    });

    boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
    assertTrue(completed);
}

// After: OutboxReactiveFutureExceptionTest
@Test
void testFailedReactiveFuture(VertxTestContext testContext) {
    producer.send(testMessage)
        .compose(v -> setupConsumerWithFailure())
        .compose(v -> waitForRetryCompletion())
        .onSuccess(retryCount -> {
            testContext.verify(() -> {
                assertEquals(3, retryCount);
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}

private Future<Void> setupConsumerWithFailure() {
    consumer.subscribe(message -> {
        return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE"))
            .onFailure(err -> logger.info("Expected failure: {}", err.getMessage()));
    });
    return Future.succeededFuture();
}
```

**Task 3.2.1: Performance Test Migration**
```java
// Before: OutboxPerformanceTest
@Test
void testThroughput() throws Exception {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
        futures.add(producer.send("message-" + i));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
}

// After: OutboxReactivePerformanceTest
@Test
void testReactiveThroughput(VertxTestContext testContext) {
    List<Future> futures = IntStream.range(0, 1000)
        .mapToObj(i -> producer.send("message-" + i))
        .collect(Collectors.toList());

    Future.all(futures)
        .onSuccess(v -> {
            long duration = System.currentTimeMillis() - startTime;
            double throughput = 1000.0 * 1000 / duration;
            testContext.verify(() -> {
                assertTrue(throughput > 500, "Should achieve >500 msg/sec");
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}
```

#### **Phase 4: API Layer Test Migration**

**Task 4.1.1: Interface Contract Test Updates**
```java
// Before: ConnectionProviderTest
@Test
void testGetDataSource() {
    DataSource dataSource = connectionProvider.getDataSource("test-client");
    assertNotNull(dataSource);
}

@Test
void testGetConnectionAsync() throws Exception {
    CompletableFuture<Connection> future = connectionProvider.getConnectionAsync("test-client");
    Connection connection = future.get(5, TimeUnit.SECONDS);
    assertTrue(connection.isValid(1));
}

// After: ReactiveConnectionProviderTest
@Test
void testGetReactivePool(VertxTestContext testContext) {
    reactiveConnectionProvider.getReactivePool("test-client")
        .onSuccess(pool -> {
            testContext.verify(() -> {
                assertNotNull(pool);
            });
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}

@Test
void testWithConnection(VertxTestContext testContext) {
    reactiveConnectionProvider.withConnection("test-client", connection -> {
        return connection.preparedQuery("SELECT 1").execute()
            .map(rowSet -> rowSet.iterator().next().getInteger(0));
    })
    .onSuccess(result -> {
        testContext.verify(() -> {
            assertEquals(1, result);
        });
        testContext.completeNow();
    })
    .onFailure(testContext::failNow);
}
```

### Test Migration Implementation Strategy

#### **Parallel Test Development**
1. **Create new reactive test classes** alongside existing JDBC tests
2. **Maintain both test suites** during migration phases
3. **Gradually replace JDBC tests** as components are migrated
4. **Remove legacy tests** only after full validation

#### **Test Naming Convention**
```java
// Legacy JDBC Tests (to be replaced)
PgConnectionManagerTest.java
OutboxCompletableFutureExceptionTest.java
SchemaMigrationManagerTest.java

// New Reactive Tests
PgConnectionManagerReactiveTest.java
OutboxReactiveFutureExceptionTest.java
ReactiveSchemaMigrationManagerTest.java

// Transition Tests (validate both patterns)
PgConnectionManagerMigrationTest.java
OutboxMigrationCompatibilityTest.java
```

#### **Test Execution Strategy**
```bash
# Phase 1: Run both test suites
mvn test -Dtest="*Test"                    # All existing tests
mvn test -Dtest="*ReactiveTest"            # New reactive tests

# Phase 2: Validate migration progress
mvn test -Dtest="*MigrationTest"           # Migration compatibility tests
mvn test -Dtest="*CompatibilityTest"       # Backward compatibility tests

# Phase 3: Performance comparison
mvn test -Dtest="*PerformanceTest"         # Compare JDBC vs Reactive performance
mvn test -Dtest="*BenchmarkTest"           # Throughput and latency validation

# Final: Reactive-only test suite
mvn test -Dtest="*ReactiveTest,*Test" -Dexclude="*JDBC*,*CompletableFuture*"
```

### Test Migration Validation

#### **Automated Test Migration Validation**
```java
@TestSuite
@DisplayName("Migration Validation Test Suite")
class MigrationValidationSuite {

    @Test
    @DisplayName("All JDBC tests have reactive equivalents")
    void validateTestMigrationCompleteness() {
        // Scan for JDBC test patterns
        List<String> jdbcTests = findTestsWithPatterns(
            "DataSource", "Connection", "CompletableFuture.get()"
        );

        // Verify reactive equivalents exist
        List<String> reactiveTests = findTestsWithPatterns(
            "Future<", "VertxTestContext", ".onSuccess(", ".onFailure("
        );

        // Validate coverage
        assertTrue(reactiveTests.size() >= jdbcTests.size(),
            "All JDBC tests should have reactive equivalents");
    }

    @Test
    @DisplayName("Performance parity between JDBC and reactive tests")
    void validatePerformanceParity() {
        // Run equivalent JDBC and reactive performance tests
        // Verify reactive performance meets or exceeds JDBC performance
    }
}
```

#### **Test Coverage Requirements**
- **100% test migration**: Every JDBC test must have a reactive equivalent
- **Performance validation**: Reactive tests must meet or exceed JDBC performance
- **Compatibility verification**: Migration tests validate both patterns work
- **Resource management**: Tests verify proper cleanup of reactive resources

This comprehensive test migration strategy ensures that the extensive test infrastructure is properly migrated alongside the production code, maintaining quality and reliability throughout the transition.
