# PeeGeeQ Performance Test Harness

A comprehensive performance testing suite for the PeeGeeQ message queue system, providing automated performance validation across all modules with detailed reporting and analysis.

## Overview

The Performance Test Harness is a dedicated Maven module that consolidates all performance testing capabilities into a unified, easy-to-use framework. It provides:

- **Comprehensive Testing**: Covers all PeeGeeQ modules (bi-temporal, outbox, native queue, database)
- **Automated Execution**: Command-line and programmatic execution with configurable parameters
- **Detailed Reporting**: Rich HTML and Markdown reports with performance metrics and analysis
- **CI/CD Integration**: Maven profiles and system property configuration for automated testing
- **Flexible Configuration**: Support for different test scenarios (load, stress, regression)

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

#### 2. Run Specific Test Suite
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

#### 3. Custom Test Configuration
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
