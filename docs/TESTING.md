# PeeGeeQ Testing Guide

This document provides comprehensive information about testing PeeGeeQ, including unit tests, integration tests, and performance tests.

## Test Structure

The PeeGeeQ project includes comprehensive test coverage across all modules:

### Test Categories

1. **Unit Tests** - Fast, isolated tests for individual components
2. **Integration Tests** - Tests that verify component interactions with real databases
3. **Performance Tests** - Load and stress tests for production readiness
4. **End-to-End Tests** - Complete workflow tests across all modules

### Test Modules

```
peegeeq-db/src/test/java/
├── dev/mars/peegeeq/db/
│   ├── config/                    # Configuration management tests
│   │   └── PeeGeeQConfigurationTest.java
│   ├── migration/                 # Database migration tests
│   │   └── SchemaMigrationManagerTest.java
│   ├── metrics/                   # Metrics and monitoring tests
│   │   └── PeeGeeQMetricsTest.java
│   ├── health/                    # Health check tests
│   │   └── HealthCheckManagerTest.java
│   ├── resilience/                # Circuit breaker and backpressure tests
│   │   ├── CircuitBreakerManagerTest.java
│   │   └── BackpressureManagerTest.java
│   ├── deadletter/                # Dead letter queue tests
│   │   └── DeadLetterQueueManagerTest.java
│   ├── performance/               # Performance and load tests
│   │   └── PeeGeeQPerformanceTest.java
│   ├── PeeGeeQManagerIntegrationTest.java
│   └── PeeGeeQTestSuite.java

peegeeq-outbox/src/test/java/
└── dev/mars/peegeeq/outbox/
    └── OutboxIntegrationTest.java

peegeeq-native/src/test/java/
└── dev/mars/peegeeq/pgqueue/
    └── NativeQueueIntegrationTest.java

peegeeq-bitemporal/src/test/java/
└── dev/mars/peegeeq/bitemporal/
    └── PgBiTemporalEventStoreTest.java

peegeeq-examples/src/test/java/
└── dev/mars/peegeeq/examples/
    ├── BiTemporalEventStoreExampleTest.java
    ├── PeeGeeQExampleTest.java
    ├── PeeGeeQSelfContainedDemoTest.java
    ├── AdvancedProducerConsumerGroupTest.java
    ├── ConsumerGroupResilienceTest.java
    ├── HighFrequencyProducerConsumerTest.java
    ├── ShutdownTest.java
    └── TestContainersShutdownTest.java
```

## Running Tests

### Prerequisites

- Java 21 or higher
- Docker (for TestContainers)
- Maven 3.8+

### Basic Test Execution

```bash
# Run all tests
mvn test

# Run tests for specific module
mvn test -pl peegeeq-db
mvn test -pl peegeeq-outbox
mvn test -pl peegeeq-native
mvn test -pl peegeeq-bitemporal
mvn test -pl peegeeq-examples

# Run specific test class
mvn test -Dtest=PeeGeeQConfigurationTest
mvn test -Dtest=HealthCheckManagerTest
mvn test -Dtest=BiTemporalEventStoreExampleTest
mvn test -Dtest=PgBiTemporalEventStoreTest

# Run test suite
mvn test -Dtest=PeeGeeQTestSuite
```

### Integration Tests

Integration tests use TestContainers to spin up real PostgreSQL instances:

```bash
# Run integration tests (includes TestContainers)
mvn verify

# Run specific integration test
mvn test -Dtest=PeeGeeQManagerIntegrationTest
mvn test -Dtest=OutboxIntegrationTest
mvn test -Dtest=NativeQueueIntegrationTest
mvn test -Dtest=PgBiTemporalEventStoreTest
mvn test -Dtest=BiTemporalEventStoreExampleTest
```

### Performance Tests

Performance tests are disabled by default and can be enabled with a system property:

```bash
# Run performance tests
mvn test -Dpeegeeq.performance.tests=true

# Run specific performance test
mvn test -Dtest=PeeGeeQPerformanceTest -Dpeegeeq.performance.tests=true
```

### Test Profiles

Different test profiles for various scenarios:

```bash
# Fast tests only (unit tests, no TestContainers)
mvn test -Dgroups=unit

# Integration tests only
mvn test -Dgroups=integration

# All tests including performance
mvn test -Dpeegeeq.performance.tests=true
```

## Test Configuration

### Environment Variables

Tests can be configured using environment variables:

```bash
# Database configuration for tests
export PEEGEEQ_DATABASE_HOST=localhost
export PEEGEEQ_DATABASE_PORT=5432
export PEEGEEQ_DATABASE_NAME=peegeeq_test

# Test-specific settings
export PEEGEEQ_TEST_TIMEOUT=30
export PEEGEEQ_TEST_PARALLEL=true
```

### System Properties

```bash
# Enable debug logging for tests
mvn test -Dpeegeeq.logging.level.root=DEBUG

# Configure test database
mvn test -Dpeegeeq.database.host=testdb.example.com

# Enable specific test features
mvn test -Dpeegeeq.circuit-breaker.enabled=false
```

## Bi-Temporal Event Store Testing

### BiTemporalEventStoreExampleTest

The `BiTemporalEventStoreExampleTest` provides comprehensive testing for the bi-temporal event store example:

```bash
# Run bi-temporal event store tests
mvn test -Dtest=BiTemporalEventStoreExampleTest -pl peegeeq-examples
```

#### Test Coverage

The test suite includes 5 comprehensive test methods:

1. **`testMainMethodExecutesWithoutErrors()`**
   - Verifies the main method runs without exceptions
   - Checks expected output messages are present

2. **`testBiTemporalEventStoreOperations()`**
   - Tests core bi-temporal functionality:
     - Event creation and appending
     - Event querying (all events, by type, by aggregate)
     - Event corrections and versioning
     - Point-in-time queries
     - Statistics retrieval

3. **`testTemporalRangeQueries()`**
   - Tests temporal range query functionality
   - Verifies events can be queried within specific time ranges
   - Uses unique identifiers to avoid test interference

4. **`testOrderEventEqualsAndHashCode()`**
   - Tests the `OrderEvent` class methods
   - Verifies proper object equality and string representation

5. **`testAsyncOperations()`**
   - Tests asynchronous operations using `CompletableFuture`
   - Verifies async event appending and querying

#### Key Features

- **TestContainers Integration**: Uses PostgreSQL TestContainers for realistic testing
- **Proper Resource Management**: Ensures setup and teardown of resources
- **Test Isolation**: Uses unique identifiers to prevent test interference
- **Comprehensive Coverage**: Tests all major bi-temporal event store functionality

#### Running Bi-Temporal Tests

```bash
# Run all bi-temporal tests
mvn test -pl peegeeq-bitemporal,peegeeq-examples -Dtest="*BiTemporal*"

# Run with debug logging
mvn test -Dtest=BiTemporalEventStoreExampleTest -Dpeegeeq.logging.level.peegeeq=DEBUG

# Run with specific PostgreSQL version
mvn test -Dtest=BiTemporalEventStoreExampleTest -Dtestcontainers.postgres.image=postgres:15
```

## Test Coverage

### Coverage Reports

Generate test coverage reports:

```bash
# Generate coverage report
mvn jacoco:prepare-agent test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Coverage Targets

- **Unit Tests**: > 90% line coverage
- **Integration Tests**: > 80% line coverage
- **Overall**: > 85% line coverage

## Test Data Management

### TestContainers Configuration

Tests use PostgreSQL TestContainers with optimized settings:

```java
@Container
private static final PostgreSQLContainer<?> postgres = 
    new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("test_db")
        .withUsername("test_user")
        .withPassword("test_pass")
        .withSharedMemorySize(256 * 1024 * 1024L); // 256MB for performance
```

### Test Data Cleanup

Tests automatically clean up data between runs:

- Database schemas are recreated for each test class
- System properties are restored after each test
- Connection pools are properly closed

## Debugging Tests

### Common Issues

1. **TestContainers fails to start**
   ```bash
   # Check Docker is running
   docker ps
   
   # Check available memory
   docker system df
   ```

2. **Tests timeout**
   ```bash
   # Increase timeout
   mvn test -Dpeegeeq.test.timeout=60
   ```

3. **Port conflicts**
   ```bash
   # Use random ports
   mvn test -Dpeegeeq.test.random-ports=true
   ```

### Debug Logging

Enable debug logging for specific components:

```bash
# Debug all PeeGeeQ components
mvn test -Dpeegeeq.logging.level.peegeeq=DEBUG

# Debug specific component
mvn test -Dpeegeeq.logging.level.peegeeq.metrics=TRACE
mvn test -Dpeegeeq.logging.level.peegeeq.health=DEBUG
```

## Continuous Integration

### GitHub Actions

Example CI configuration:

```yaml
name: Tests
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: [21]
    
    steps:
    - uses: actions/checkout@v3
    - name: Set up JDK ${{ matrix.java }}
      uses: actions/setup-java@v3
      with:
        java-version: ${{ matrix.java }}
        distribution: 'temurin'
    
    - name: Cache Maven dependencies
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
    
    - name: Run tests
      run: mvn verify
    
    - name: Run performance tests
      run: mvn test -Dpeegeeq.performance.tests=true
      if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    
    - name: Generate coverage report
      run: mvn jacoco:report
    
    - name: Upload coverage to Codecov
      uses: codecov/codecov-action@v3
```

### Jenkins Pipeline

Example Jenkins pipeline:

```groovy
pipeline {
    agent any
    
    stages {
        stage('Test') {
            parallel {
                stage('Unit Tests') {
                    steps {
                        sh 'mvn test -Dgroups=unit'
                    }
                }
                stage('Integration Tests') {
                    steps {
                        sh 'mvn test -Dgroups=integration'
                    }
                }
            }
        }
        
        stage('Performance Tests') {
            when {
                branch 'main'
            }
            steps {
                sh 'mvn test -Dpeegeeq.performance.tests=true'
            }
        }
        
        stage('Coverage') {
            steps {
                sh 'mvn jacoco:report'
                publishHTML([
                    allowMissing: false,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: 'target/site/jacoco',
                    reportFiles: 'index.html',
                    reportName: 'Coverage Report'
                ])
            }
        }
    }
    
    post {
        always {
            junit 'target/surefire-reports/*.xml'
        }
    }
}
```

## Test Best Practices

### Writing Tests

1. **Use descriptive test names**
   ```java
   @Test
   void testHealthCheckManagerDetectsUnhealthyDatabase() {
       // Test implementation
   }
   ```

2. **Follow AAA pattern** (Arrange, Act, Assert)
   ```java
   @Test
   void testMetricsRecording() {
       // Arrange
       PeeGeeQMetrics metrics = new PeeGeeQMetrics(dataSource, "test");
       
       // Act
       metrics.recordMessageSent("topic");
       
       // Assert
       assertEquals(1.0, metrics.getSummary().getMessagesSent());
   }
   ```

3. **Use proper cleanup**
   ```java
   @AfterEach
   void tearDown() {
       if (manager != null) {
           manager.close();
       }
   }
   ```

### Performance Testing

1. **Use realistic data volumes**
2. **Test under concurrent load**
3. **Monitor resource usage**
4. **Set appropriate timeouts**
5. **Verify cleanup after tests**

### Integration Testing

1. **Use TestContainers for real databases**
2. **Test complete workflows**
3. **Verify error handling**
4. **Test configuration variations**
5. **Include metrics verification**

### Example Testing

The `peegeeq-examples` module includes comprehensive tests for all example applications:

1. **End-to-End Testing**
   ```java
   @Test
   void testMainMethodExecutesWithoutErrors() {
       assertDoesNotThrow(() -> BiTemporalEventStoreExample.main(new String[]{}));
   }
   ```

2. **Feature-Specific Testing**
   ```java
   @Test
   void testBiTemporalEventStoreOperations() {
       // Test event creation, querying, corrections, etc.
   }
   ```

3. **Test Isolation Patterns**
   ```java
   // Use unique identifiers to prevent test interference
   String testId = String.valueOf(System.currentTimeMillis());
   OrderEvent order = new OrderEvent("TEST-" + testId, "CUST-123", amount, "CREATED");
   ```

4. **Resource Management**
   ```java
   @AfterEach
   void tearDown() {
       if (eventStore != null) eventStore.close();
       if (manager != null) manager.stop();
   }
   ```

## Troubleshooting

### Common Test Failures

1. **Database connection issues**
   - Check TestContainers logs
   - Verify Docker is running
   - Check available ports

2. **Timing issues**
   - Increase timeouts
   - Use proper synchronization
   - Add appropriate waits

3. **Resource cleanup**
   - Ensure proper @AfterEach cleanup
   - Check for connection leaks
   - Monitor memory usage

4. **Bi-temporal event store test issues**
   - Events from previous tests may persist
   - Use unique identifiers and timestamps
   - Filter results by test-specific criteria
   - Ensure proper event store isolation

### Getting Help

- Check test logs in `target/surefire-reports/`
- Enable debug logging for detailed information
- Review TestContainers logs for database issues
- Use IDE debugging for step-through analysis

This comprehensive testing framework ensures PeeGeeQ is production-ready with high reliability and performance.
