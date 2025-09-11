# PeeGeeQ Bitemporal Test Configuration

This document explains the test configuration for the PeeGeeQ Bitemporal module, including logging and fail-fast behavior.

## 🚀 Quick Start

### Run Fast Unit Tests (Default)
```bash
# Runs only unit tests, excludes integration tests, fails fast on first error
mvn test -pl peegeeq-bitemporal
```

### Run All Tests Including Integration Tests
```bash
# Runs all tests including performance validation, fails fast on first error
mvn test -pl peegeeq-bitemporal -Pintegration-tests
```

### Run Specific Test
```bash
# Run only the batch operations test
mvn test -pl peegeeq-bitemporal -Dtest=VertxPerformanceOptimizationValidationTest#shouldValidateBatchOperations
```

## 📋 Test Profiles

### Default Profile (unit-tests)
- **Active by default**
- **Excludes**: Integration tests, performance tests
- **Includes**: Unit tests only
- **Timeout**: 30s per test method, 60s total
- **Behavior**: Fails fast on first error

### Integration Tests Profile
- **Activation**: `-Pintegration-tests`
- **Includes**: All tests including integration and performance validation
- **Timeout**: 120s per test method, 300s total
- **Behavior**: Fails fast on first error

## 📝 Logging Configuration

### Permanent Log Files
All test logs are automatically saved to permanent files:

```
peegeeq-bitemporal/logs/
├── bitemporal-tests-current.log          # Current test run
├── bitemporal-tests-2025-09-11_18-30-15.log  # Previous runs (timestamped)
├── bitemporal-tests-2025-09-11_18-25-42.log
└── ...
```

### Log Retention
- **History**: 30 previous test runs
- **Size Limit**: 100MB total
- **Format**: Timestamped with thread and logger information

### Log Levels
- **PeeGeeQ Bitemporal**: DEBUG (detailed operation logging)
- **TestContainers**: WARN (reduced noise)
- **Vert.x**: WARN (reduced noise)
- **Root**: INFO

## ⚡ Fail Fast Configuration

### Behavior
- **Stops after first test class failure** (Maven Surefire limitation)
- **No more waiting** for 10-minute test runs when there's an early failure
- **Sequential execution** to ensure proper fail-fast behavior
- **Works best with multiple test classes**

### Configuration
```xml
<skipAfterFailureCount>1</skipAfterFailureCount>
<parallel>none</parallel>
```

### Practical Usage
```bash
# Run all tests - stops after first failing test class
mvn test -pl peegeeq-bitemporal

# Run specific test that might fail - immediate feedback
mvn test -pl peegeeq-bitemporal -Dtest=VertxPerformanceOptimizationValidationTest#shouldValidateBatchOperations
```

## 🔧 Advanced Usage

### Override Timeouts
```bash
# Set custom timeout for long-running tests
mvn test -pl peegeeq-bitemporal -Djunit.jupiter.execution.timeout.testable.method.default=180s
```

### Debug Specific Logger
```bash
# Enable debug logging for specific component
mvn test -pl peegeeq-bitemporal -Dlogback.logger.dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore=DEBUG
```

### Run Tests with Custom Log File
```bash
# Specify custom log file location
mvn test -pl peegeeq-bitemporal -Dlogback.configurationFile=custom-logback.xml
```

## 📊 Performance Test Examples

### Run Performance Validation
```bash
# Run the comprehensive performance validation test
mvn test -pl peegeeq-bitemporal -Pintegration-tests -Dtest=VertxPerformanceOptimizationValidationTest
```

### Run Benchmark Tests
```bash
# Run performance benchmarks
mvn test -pl peegeeq-bitemporal -Pintegration-tests -Dtest=BiTemporalPerformanceBenchmarkTest
```

## 🐛 Troubleshooting

### Test Logs Not Appearing
1. Check that `logs/` directory exists in `peegeeq-bitemporal/`
2. Verify logback configuration: `src/test/resources/logback-test.xml`
3. Check file permissions on logs directory

### Tests Not Failing Fast
1. Verify Maven Surefire plugin configuration in `pom.xml`
2. Check for `<skipAfterFailureCount>1</skipAfterFailureCount>`
3. Ensure you're using Maven Surefire 3.1.2 or later

### Integration Tests Not Running
1. Use the integration-tests profile: `-Pintegration-tests`
2. Check test class naming (should end with `Test.java`)
3. Verify test is not in excludes list

## 📁 File Locations

- **Test Configuration**: `peegeeq-bitemporal/pom.xml`
- **Logging Configuration**: `peegeeq-bitemporal/src/test/resources/logback-test.xml`
- **Log Files**: `peegeeq-bitemporal/logs/`
- **Test Classes**: `peegeeq-bitemporal/src/test/java/`

## 🎯 Best Practices

1. **Run unit tests first** (default profile) for quick feedback
2. **Use integration tests profile** only when needed
3. **Check log files** after test failures for detailed debugging
4. **Clean logs periodically** if disk space is a concern
5. **Use specific test execution** for focused debugging

## 📈 Performance Expectations

### Unit Tests
- **Duration**: < 30 seconds total
- **Individual Test**: < 5 seconds
- **Parallel Execution**: 4 threads per core

### Integration Tests
- **Duration**: < 5 minutes total
- **Individual Test**: < 2 minutes
- **Database Setup**: TestContainers PostgreSQL

## 🔍 Monitoring Test Health

### Log Analysis
```bash
# Check recent test failures
tail -f logs/bitemporal-tests-current.log | grep ERROR

# Search for specific errors
grep -n "Exception\|Error" logs/bitemporal-tests-*.log
```

### Performance Monitoring
```bash
# Check test execution times
grep "completed in" logs/bitemporal-tests-current.log
```

This configuration ensures fast feedback, comprehensive logging, and efficient test execution for the PeeGeeQ Bitemporal module.
