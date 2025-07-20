# PeeGeeQ Development Commands

This document contains useful Maven commands for development and testing.

## Testing Commands

### REST API Tests
```bash
# Run all REST API tests
mvn test -pl peegeeq-rest

# Run unit tests only
mvn test -pl peegeeq-rest -Dtest=BasicUnitTest

# Run full integration suite
mvn test -Dtest=PeeGeeQRestTestSuite

# Run performance tests
mvn test -Dtest=*PerformanceTest
```

### Module-Specific Testing
```bash
# Test specific modules
mvn test -pl peegeeq-db
mvn test -pl peegeeq-native
mvn test -pl peegeeq-examples
mvn test -pl peegeeq-bitemporal
mvn test -pl peegeeq-outbox

# Run all tests
mvn test

# Run tests with Docker (requires Docker to be available)
mvn test -Dtest.containers.enabled=true
```

### Build Commands
```bash
# Clean and compile
mvn clean compile

# Package without tests
mvn package -DskipTests

# Install to local repository
mvn install

# Generate site documentation
mvn site
```

### Integration Testing
```bash
# Run integration tests with TestContainers
mvn verify -Dtest.profile=integration

# Run with specific database
mvn test -Dtest.database.url=jdbc:postgresql://localhost:5432/peegeeq_test
```

## Development Notes

- All tests require Docker to be available for TestContainers
- Performance tests may take longer to complete
- Integration tests will start PostgreSQL containers automatically
- Use `-DskipTests` to skip tests during development builds
