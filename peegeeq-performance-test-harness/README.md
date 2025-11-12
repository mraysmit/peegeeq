# PeeGeeQ Performance Test Harness

A comprehensive performance testing suite for the PeeGeeQ message queue system, providing automated performance validation across all modules with detailed reporting and analysis.

## Overview

The Performance Test Harness is a dedicated Maven module that consolidates all performance testing capabilities into a unified, easy-to-use framework. It provides:

- **Comprehensive Testing**: Covers all PeeGeeQ modules (bi-temporal, outbox, native queue, database, **consumer group fan-out**)
- **Automated Execution**: Command-line and programmatic execution with configurable parameters
- **Detailed Reporting**: Rich HTML and Markdown reports with performance metrics and analysis
- **CI/CD Integration**: Maven profiles and system property configuration for automated testing
- **Flexible Configuration**: Support for different test scenarios (load, stress, regression)
- **Consumer Group Fan-Out**: 29 acceptance tests covering functional, recovery, cleanup, contention, and performance scenarios

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.6+
- Docker (for TestContainers)
- 8GB+ RAM recommended for full test suite

### Running Performance Tests

#### 1. Run All Performance Tests
```bash
# From project root
mvn test -pl peegeeq-performance-test-harness

# Or with explicit performance profile
mvn test -pl peegeeq-performance-test-harness -Pperformance
```

#### 2. Run Consumer Group Fan-Out Benchmarks
```bash
# Run all fan-out benchmarks
cd scripts
chmod +x run-fanout-benchmarks.sh
./run-fanout-benchmarks.sh --mode both --profile standard

# Run specific fan-out tests
./run-fanout-benchmarks.sh --tests F1,P1,P2 --mode bitmap

# Run individual fan-out test
mvn test -pl peegeeq-performance-test-harness -Dtest=F1_AtLeastOnceDeliveryTest
```

#### 3. Run Specific Test Suite
```bash
# Bi-temporal tests only
mvn exec:java -pl peegeeq-performance-test-harness -Dexec.args="--suite=bitemporal"

# Outbox pattern tests only
mvn exec:java -pl peegeeq-performance-test-harness -Dexec.args="--suite=outbox"

# Native queue tests only
mvn exec:java -pl peegeeq-performance-test-harness -Dexec.args="--suite=native"

# Database core tests only
mvn exec:java -pl peegeeq-performance-test-harness -Dexec.args="--suite=database"
```

#### 4. Custom Test Configuration
```bash
# 30-minute stress test with 50 concurrent threads
mvn exec:java -pl peegeeq-performance-test-harness \
  -Dexec.args="--duration=1800 --threads=50 --output=target/stress-reports"

# Load test with custom configuration
mvn test -pl peegeeq-performance-test-harness -Pload-test
```

## Test Suites

### 1. Bi-temporal Event Store Performance
- **Individual vs Batch Append**: Compares single event vs batch event append performance
- **Query Performance**: Tests temporal queries, range queries, and complex filtering
- **Concurrent Operations**: Validates performance under concurrent read/write operations
- **Memory Usage**: Monitors memory consumption under sustained load

**Key Metrics:**
- Query throughput (events/sec)
- Append throughput (events/sec)
- Average latency (ms)
- Memory utilization

### 2. Outbox Pattern Performance
- **Message Send Throughput**: Tests raw message publishing performance
- **End-to-End Latency**: Measures complete message processing time
- **Concurrent Producers**: Validates performance with multiple producers
- **Transactional Outbox**: Tests performance with transactional message publishing

**Key Metrics:**
- Send throughput (msg/sec)
- Total throughput (msg/sec)
- Average latency (ms)
- Transactional throughput (msg/sec)

### 3. Native Queue Performance
- **LISTEN/NOTIFY Performance**: Tests PostgreSQL notification performance
- **Consumer Mode Comparison**: Compares HYBRID, LISTEN_NOTIFY_ONLY, and POLLING_ONLY modes
- **Producer-Consumer Throughput**: End-to-end message processing performance
- **Message Processing Latency**: Detailed latency analysis

**Key Metrics:**
- Message throughput (msg/sec)
- Average/min/max latency (ms)
- Consumer mode performance comparison
- Real-time notification performance

### 4. Database Core Performance
- **Query Performance**: Raw database query throughput and latency
- **Connection Pool Utilization**: Pool efficiency and resource usage
- **Backpressure Management**: System behavior under load
- **Health Check Performance**: Monitoring system overhead
- **Metrics Collection**: Performance monitoring impact

**Key Metrics:**
- Query throughput (queries/sec)
- Connection pool utilization (%)
- Backpressure success rate (%)
- Health check throughput (checks/sec)

### 5. Consumer Group Fan-Out Performance (NEW)
- **At-Least-Once Delivery**: Validates message delivery guarantees with crash recovery
- **Throughput Curve**: Measures steady-state throughput across batch/payload/group configurations
- **Fanout Scaling**: Tests scaling characteristics from 1 to 128 consumer groups
- **Cleanup & Retention**: Validates watermark correctness and partition drop safety
- **Contention & Concurrency**: Tests bitmap conflicts and CAS efficiency
- **Recovery & Backfill**: Validates crash recovery and bounded backfill

**Test Categories (29 tests total):**
- Functional Semantics (F1-F6): 6 tests
- Recovery & Backfill (R1-R4): 4 tests
- Cleanup & Retention (C1-C4): 4 tests
- Contention & Concurrency (K1-K2): 2 tests
- Signaling & Wakeups (S1-S2): 2 tests
- Performance & Scalability (P1-P5): 5 tests
- Data Integrity Audits (A1-A2): 2 tests
- Operational Edge Cases (O1-O3): 3 tests

**Key Metrics:**
- Throughput (msg/sec)
- End-to-end latency (p50/p95/p99)
- DB CPU utilization (%)
- WAL generation (MB)
- Write amplification (Bitmap vs Offset modes)
- Watermark advancement
- Cleanup rate
- CAS/bitmap conflict rates

**Acceptance Thresholds:**
- ✅ Throughput ≥ 30,000 msg/sec (2KB payload, 4 groups)
- ✅ p95 latency < 300ms
- ✅ DB CPU < 70%
- ✅ Missing IDs = 0 (no data loss)
- ✅ Duplicates < 0.5% during crash tests
- ✅ Bitmap conflicts < 10% at N=16
- ✅ CAS conflicts < 5%

## Consumer Group Fan-Out Test Harness

### Overview

The `ConsumerGroupFanoutTestHarness` provides a comprehensive framework for testing consumer group fan-out functionality with:

- **Configurable Producer**: Rate-limited message publishing with payload size control
- **Configurable Consumer**: Multi-worker consumer groups with batch size control
- **Failure Injection**: Crash simulation (before/after ack, network, database, random)
- **Feature Toggles**: LISTEN/NOTIFY, polling intervals
- **Subscription Management**: Pause, resume, kill subscriptions
- **Metrics Collection**: Comprehensive metrics tracking (correctness, latency, throughput, contention, cleanup, resources)

### Example Usage

```java
ConsumerGroupFanoutTestHarness harness = new ConsumerGroupFanoutTestHarness(vertx, pool);

// Configure producer
harness.publishMessages("orders.events", 100000, 1000, PayloadSize.MEDIUM);

// Configure consumers
harness.startConsumer("orders.events", "email-service", 4, 100);
harness.startConsumer("orders.events", "analytics-service", 8, 500);

// Inject failures
harness.injectFailure(FailureType.BEFORE_ACK, 0.10); // 10% failure rate

// Wait for completion
harness.awaitCompletion(Duration.ofMinutes(10));

// Get metrics
TestMetrics metrics = harness.getMetrics();
System.out.println(metrics.generateReport());
```

### Benchmarking Scripts

#### ThroughputBenchmark (P1)

Measures steady-state throughput across different configurations:

```bash
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.fanout.benchmark.ThroughputBenchmark"
```

**Matrix Testing:**
- Batch sizes: {100, 500, 2000}
- Payload sizes: {0.5KB, 2KB, 16KB}
- Consumer groups: {4, 8, 16}

**Pass Criteria:**
- ✅ Throughput ≥ 30,000 msg/sec (2KB, 4 groups, batch=500)
- ✅ p95 latency < 300ms
- ✅ DB CPU < 70%

#### FanoutScalingBenchmark (P2)

Measures scaling characteristics with increasing consumer groups:

```bash
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.fanout.benchmark.FanoutScalingBenchmark"
```

**Test Matrix:**
- Consumer groups: {1, 4, 8, 16, 32, 64, 128}
- Fixed production rate: 10,000 msg/sec
- Compares Bitmap vs Offset modes

**Pass Criteria:**
- ✅ DB CPU < 70% at N=16 for both modes
- ✅ Offset mode: O(1) CPU scaling
- ✅ Bitmap mode: O(N) CPU scaling for N ≤ 64

### Database Schema

The fan-out schema is created by migration `V010__Create_Consumer_Group_Fanout_Tables.sql`:

**Tables:**
- `outbox_topics` - Topic registry with configuration
- `outbox_topic_subscriptions` - Consumer group subscriptions
- `outbox_consumer_groups` - Message tracking (Reference Counting mode)
- `outbox_subscription_offsets` - Cursor tracking (Offset/Watermark mode)
- `outbox_topic_watermarks` - Per-topic watermarks
- `processed_ledger` - Audit log for testing
- `partition_drop_audit` - Cleanup safety verification
- `consumer_group_index` - Bitmap index mapping

**Running Migrations:**
```bash
cd peegeeq-migrations
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/peegeeq \
  -Dflyway.user=postgres \
  -Dflyway.password=postgres
```

### Test Tracking

Test execution is tracked in `docs/design/CONSUMER_GROUP_FANOUT_TEST_TRACKING.csv`:

- **Total Tests**: 29
- **Critical**: 18 tests
- **High**: 9 tests
- **Medium**: 3 tests
- **Low**: 1 test

**Phase Breakdown:**
- Phase 2 (Week 3-4): 2 tests (F5, F6)
- Phase 4 (Week 7-8): 4 tests (F1-F4)
- Phase 5 (Week 9-10): 4 tests (C1-C4)
- Phase 6 (Week 11-12): 19 tests (remaining)

## Configuration

### Command Line Options

```bash
java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner [options]

Options:
  --suite=<name>      Run specific test suite (bitemporal, outbox, native, db, all)
  --config=<file>     Use custom configuration file
  --duration=<sec>    Test duration in seconds (default: 300)
  --threads=<num>     Number of concurrent threads (default: 10)
  --output=<dir>      Output directory for reports (default: target/performance-reports)
  --help, -h          Show help message
```

### System Properties

```bash
# Enable performance tests (required for test execution)
-Dpeegeeq.performance.tests=true

# Test configuration
-Dpeegeeq.performance.suite=all
-Dpeegeeq.performance.duration=300
-Dpeegeeq.performance.threads=10
-Dpeegeeq.performance.output=target/performance-reports

# Detailed logging
-Dpeegeeq.performance.detailed.logging=true

# Report generation
-Dpeegeeq.performance.generate.graphs=true
```

### Maven Profiles

#### Performance Profile
```bash
mvn test -pl peegeeq-performance-test-harness -Pperformance
```
- Standard performance testing (5 minutes, 10 threads)
- Suitable for CI/CD pipelines
- Generates comprehensive reports

#### Load Test Profile
```bash
mvn test -pl peegeeq-performance-test-harness -Pload-test
```
- Extended load testing (30 minutes, 50 threads)
- Validates sustained performance
- Stress tests connection pools and resources

#### Stress Test Profile
```bash
mvn test -pl peegeeq-performance-test-harness -Pstress-test
```
- Intensive stress testing (60 minutes, 100 threads)
- Identifies performance limits and bottlenecks
- Validates system stability under extreme load

## Reports

### Report Generation

Performance reports are automatically generated after test execution:

- **Location**: `target/performance-reports/`
- **Format**: Markdown with embedded metrics
- **Filename**: `peegeeq-performance-report_YYYY-MM-DD_HH-mm-ss.md`

### Report Contents

1. **Executive Summary**: High-level test results and success rates
2. **Performance Highlights**: Key metrics by module
3. **Detailed Metrics**: Complete performance data
4. **Test Failures**: Error analysis (if any)
5. **Performance Analysis**: Automated analysis and findings
6. **Recommendations**: Optimization suggestions
7. **System Information**: Test environment details

### Sample Report Metrics

```markdown
## Performance Highlights

### 🔄 Bi-temporal Event Store
- **Query Performance:** 50,000 events/sec
- **Append Performance:** 526 events/sec
- **Query Latency:** 2.00 ms
- **Append Latency:** 1.90 ms

### 📤 Outbox Pattern
- **Send Throughput:** 3,802 msg/sec
- **Total Throughput:** 477 msg/sec
- **Average Latency:** 59.15 ms
- **Total Messages:** 1000

### ⚡ Native Queue
- **Throughput:** 5,000 msg/sec
- **Average Latency:** 2.50 ms
- **Max Latency:** 15.00 ms
- **Min Latency:** 0.50 ms

### 🗄️ Database Core
- **Query Throughput:** 7,936 queries/sec
- **Average Latency:** 1.97 ms
- **Pool Utilization:** 65.5%
- **Total Queries:** 2000
```

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: Performance Tests
on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM
  workflow_dispatch:

jobs:
  performance:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - name: Run Performance Tests
        run: |
          mvn test -pl peegeeq-performance-test-harness -Pperformance \
            -Dpeegeeq.performance.tests=true
      - name: Upload Reports
        uses: actions/upload-artifact@v3
        with:
          name: performance-reports
          path: target/performance-reports/
```

### Jenkins Pipeline Example

```groovy
pipeline {
    agent any
    triggers {
        cron('H 2 * * *')  // Daily at 2 AM
    }
    stages {
        stage('Performance Tests') {
            steps {
                sh '''
                    mvn test -pl peegeeq-performance-test-harness -Pperformance \
                      -Dpeegeeq.performance.tests=true
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'target/performance-reports/**', 
                                   fingerprint: true
                    publishHTML([
                        allowMissing: false,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'target/performance-reports',
                        reportFiles: '*.md',
                        reportName: 'Performance Report'
                    ])
                }
            }
        }
    }
}
```

## Troubleshooting

### Common Issues

1. **Tests Skipped**: Ensure `-Dpeegeeq.performance.tests=true` is set
2. **Docker Issues**: Verify Docker is running for TestContainers
3. **Memory Issues**: Increase JVM heap size with `-Xmx8g`
4. **Port Conflicts**: Ensure PostgreSQL test ports are available

### Performance Tuning

1. **JVM Settings**: Use `-XX:+UseG1GC -Xmx8g` for optimal performance
2. **Docker Resources**: Allocate sufficient CPU and memory to Docker
3. **Test Duration**: Adjust test duration based on system capabilities
4. **Concurrent Threads**: Start with lower thread counts and increase gradually

## Contributing

When adding new performance tests:

1. Extend the appropriate `PerformanceTestSuite` implementation
2. Add new metrics to the `Results` classes
3. Update report generation to include new metrics
4. Add integration tests to validate new functionality
5. Update documentation with new test descriptions

## License

Licensed under the Apache License, Version 2.0. See the main project LICENSE file for details.
