# Configuration Validation Tests

This directory contains comprehensive tests that validate PeeGeeQ configuration behavior. These tests ensure that configuration properties work correctly across different deployment scenarios and environments.

## Business Value

These tests validate critical enterprise deployment scenarios:
- **Multi-Environment Deployment**: System properties override configuration files for dev/staging/production
- **Kubernetes Integration**: Environment variable injection works correctly
- **DevOps Automation**: Configuration naming conventions enable reliable automation
- **Production Reliability**: Configuration validation prevents runtime failures

## Test Coverage

### Properties Tested

1. **`peegeeq.queue.max-retries`** - Controls retry attempts for reliability tuning
2. **`peegeeq.consumer.threads`** - Controls consumer thread allocation for performance
3. **`peegeeq.queue.polling-interval`** - Controls polling frequency for environment optimization
4. **`peegeeq.queue.batch-size`** - Controls batch processing for throughput tuning
5. **Database Connection Properties** - Host, port, database name, credentials
6. **Profile-Based Configuration** - Environment-specific configuration selection

### Test Classes

- **`ConfigurationValidationTest.java`** - Comprehensive configuration validation (consolidated from 6 previous test classes)

## Running the Tests

### Run All Configuration Validation Tests
```bash
# Run the comprehensive configuration validation test
mvn test -Dtest=ConfigurationValidationTest

# Or using Gradle
./gradlew test --tests ConfigurationValidationTest
```

### Run Specific Configuration Tests
```bash
# Test system property override behavior (critical for production deployment)
mvn test -Dtest=ConfigurationValidationTest#testSystemPropertiesOverrideConfigurationFiles

# Test database connection configuration (critical for multi-environment deployment)
mvn test -Dtest=ConfigurationValidationTest#testDatabaseConnectionPropertyOverrides

# Test profile-based configuration (critical for environment management)
mvn test -Dtest=ConfigurationValidationTest#testProfileBasedConfiguration

# Test property naming conventions (critical for automation)
mvn test -Dtest=ConfigurationValidationTest#testConfigurationPropertyNamingConventions

# Test configuration factory integration (critical for end-to-end validation)
mvn test -Dtest=ConfigurationValidationTest#testConfigurationFactoryIntegration
```

## Test Details

### Max Retries Test
- **Purpose**: Validates that `peegeeq.queue.max-retries` controls actual retry attempts
- **Method**: Sets different retry values (2, 4) and verifies exact number of attempts
- **Validation**: Counts actual processing attempts and confirms they match configuration

### Consumer Threads Test
- **Purpose**: Validates that `peegeeq.consumer.threads` affects concurrent processing
- **Method**: Creates multiple consumers and tracks which threads process messages
- **Validation**: Verifies concurrent processing occurs and tracks thread usage
- **Note**: This test validates current threading model and can be extended when the property is fully implemented

### Polling Interval Test
- **Purpose**: Validates that `peegeeq.queue.polling-interval` controls polling frequency
- **Method**: Sets different intervals (1s, 3s) and measures message receive timing
- **Validation**: Verifies configuration is applied and timing behavior is reasonable

### Batch Size Test
- **Purpose**: Validates that `peegeeq.queue.batch-size` affects batch processing
- **Method**: Sets different batch sizes (5, 15) and tracks message processing patterns
- **Validation**: Verifies configuration is applied and messages are processed correctly

### Property Override Test
- **Purpose**: Validates that system properties override configuration file values
- **Method**: Compares base config vs. system property overridden config
- **Validation**: Confirms system properties take precedence over configuration files

## Test Infrastructure

### Database Setup
- Uses TestContainers with PostgreSQL 15
- Automatic database migration and setup
- Isolated test databases for each test class

### Property Management
- Saves and restores original system properties
- Prevents test interference
- Clean test environment for each test

### Timing and Synchronization
- Uses CountDownLatch for reliable test synchronization
- Generous timeouts to handle CI/CD environment variations
- Detailed logging for debugging timing issues

## Expected Behavior

### Successful Test Run
```
=== Testing Max Retries Property Controls Retry Behavior ===
Testing with max retries = 2
✅ Verified 3 total attempts (1 initial + 2 retries) for max-retries=2
Testing with max retries = 4
✅ Verified 5 total attempts (1 initial + 4 retries) for max-retries=4
✅ Max retries property test completed successfully

=== Testing Consumer Threads Property Behavior ===
Testing with consumer threads = 2
✅ Verified concurrent processing with 2 consumers using X threads
Testing with consumer threads = 4
✅ Verified concurrent processing with 4 consumers using Y threads
✅ Consumer threads property test completed successfully

=== Testing Polling Interval Property Controls Polling Frequency ===
Testing with polling interval = PT1S
✅ Polling test completed in Xms with interval 1000ms
Testing with polling interval = PT3S
✅ Polling test completed in Yms with interval 3000ms
✅ Polling interval property test completed successfully

=== Testing Batch Size Property Controls Batch Processing ===
Testing with batch size = 5
✅ Verified batch processing: sent 10 messages with batch-size=5, processed 10 messages
Testing with batch size = 15
✅ Verified batch processing: sent 30 messages with batch-size=15, processed 30 messages
✅ Batch size property test completed successfully

=== Testing System Properties Override Configuration Files ===
✅ Verified system properties override configuration file values:
  MaxRetries: 5 -> 10
  BatchSize: 20 -> 30
  PollingInterval: PT2S -> PT4S
✅ Property override test completed successfully
```

## Troubleshooting

### Common Issues

1. **Test Timeouts**
   - Increase timeout values if running in slow CI/CD environments
   - Check database connectivity and performance

2. **Property Conflicts**
   - Ensure no conflicting system properties are set before running tests
   - Tests save and restore properties automatically

3. **Database Issues**
   - Verify Docker is available for TestContainers
   - Check PostgreSQL container startup logs

### Debugging

Enable debug logging to see detailed test execution:
```bash
mvn test -Dtest=SystemPropertiesValidationSuite -Dlogging.level.dev.mars.peegeeq=DEBUG
```

## Contributing

When adding new system properties:

1. Add property validation to the appropriate test class
2. Follow the existing test pattern:
   - Save/restore original properties
   - Test with multiple values
   - Verify actual behavior matches configuration
   - Include detailed logging
3. Update this README with the new property details
4. Add the test to the test suite if creating a new test class

## Notes

- These tests validate that properties control actual runtime behavior, not just configuration loading
- Some properties may have implementation-dependent behavior that affects test validation
- Tests are designed to be robust across different environments and timing conditions
- Property names in tests match the actual property names used in the codebase
