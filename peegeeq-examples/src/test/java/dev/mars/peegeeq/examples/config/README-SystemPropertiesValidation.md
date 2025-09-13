# System Properties Validation Tests

This directory contains comprehensive tests that validate the behavior of key PeeGeeQ system properties. These tests ensure that configuration properties actually control the expected runtime behavior.

## Test Coverage

### Properties Tested

1. **`peegeeq.queue.max-retries`** - Controls the actual number of retry attempts before dead letter
2. **`peegeeq.consumer.threads`** - Controls the actual number of consumer threads created
3. **`peegeeq.queue.polling-interval`** - Controls the actual polling frequency timing
4. **`peegeeq.queue.batch-size`** - Controls the actual batch processing behavior
5. **Property Override Behavior** - System properties override configuration file values

### Test Classes

- **`SystemPropertiesValidationTest.java`** - Tests max retries, consumer threads, and polling interval
- **`SystemPropertiesValidationTestPart2.java`** - Tests batch size and property override behavior
- **`SystemPropertiesValidationSuite.java`** - Test suite runner for all property validation tests

## Running the Tests

### Run All Property Validation Tests
```bash
# Run the complete test suite
mvn test -Dtest=SystemPropertiesValidationSuite

# Or using Gradle
./gradlew test --tests SystemPropertiesValidationSuite
```

### Run Individual Test Classes
```bash
# Test max retries, consumer threads, and polling interval
mvn test -Dtest=SystemPropertiesValidationTest

# Test batch size and property overrides
mvn test -Dtest=SystemPropertiesValidationTestPart2
```

### Run Specific Property Tests
```bash
# Test only max retries behavior
mvn test -Dtest=SystemPropertiesValidationTest#testMaxRetriesPropertyControlsRetryBehavior

# Test only polling interval behavior
mvn test -Dtest=SystemPropertiesValidationTest#testPollingIntervalPropertyControlsPollingFrequency

# Test only batch size behavior
mvn test -Dtest=SystemPropertiesValidationTestPart2#testBatchSizePropertyControlsBatchProcessing

# Test only property override behavior
mvn test -Dtest=SystemPropertiesValidationTestPart2#testSystemPropertiesOverrideConfigurationFiles
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
