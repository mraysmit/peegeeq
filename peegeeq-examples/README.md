# PeeGeeQ Examples

This module contains self-contained example applications demonstrating PeeGeeQ's production readiness features.

## Quick Start - Self-Contained Demo

**For the easiest experience, use the self-contained demo:**

```bash
# Run the self-contained demo (requires Docker) - Recommended approach
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQSelfContainedDemo" -pl peegeeq-examples

# Alternative: Run directly with classpath
java -cp "target/classes:..." dev.mars.peegeeq.examples.PeeGeeQSelfContainedDemo
```

This demo:
- ✅ **No setup required** - starts its own PostgreSQL container
- ✅ **Works out of the box** - no external dependencies
- ✅ **Self-cleaning** - automatically removes containers when done
- ✅ **Production-like** - uses real PostgreSQL database

## Traditional Example

**For testing with your own PostgreSQL setup:**

```bash
# Run with default development profile
java -cp <classpath> dev.mars.peegeeq.examples.PeeGeeQExample

# Run with specific profile
java -Dpeegeeq.profile=production -cp <classpath> dev.mars.peegeeq.examples.PeeGeeQExample

# Run with your own custom configuration
java -Dpeegeeq.database.host=myhost \
     -Dpeegeeq.database.username=myuser \
     -Dpeegeeq.database.password=mypass \
     -cp <classpath> dev.mars.peegeeq.examples.PeeGeeQExample
```

### Prerequisites for Traditional Example

1. **PostgreSQL Server** running and accessible
2. **Database and User** configured according to your profile

#### Quick Database Setup

```sql
-- For development profile
CREATE DATABASE peegeeq_dev;
CREATE USER peegeeq_dev WITH PASSWORD 'peegeeq_dev';
GRANT ALL PRIVILEGES ON DATABASE peegeeq_dev TO peegeeq_dev;

-- For production profile
CREATE DATABASE peegeeq_prod;
CREATE USER peegeeq_prod WITH PASSWORD 'your_secure_password';
GRANT ALL PRIVILEGES ON DATABASE peegeeq_prod TO peegeeq_prod;
```

## What the Examples Demonstrate

Both examples showcase the following PeeGeeQ production readiness features:

### 🔧 Configuration Management
- **Environment-specific profiles** (development, production, test)
- **System property overrides** for containerized deployments
- **Environment variable support** for cloud-native environments
- **Configuration validation** with helpful error messages

### 🏥 Health Checks
- **Database connectivity** monitoring
- **Connection pool** health status
- **Overall system health** aggregation
- **Component-level** health reporting

### 📊 Metrics & Monitoring
- **Message processing** metrics (sent, received, processed, failed)
- **Success rate** calculations
- **Queue depth** monitoring
- **Performance** tracking

### ⚡ Circuit Breaker
- **Failure detection** and automatic circuit opening
- **Configurable thresholds** for failure rates
- **Automatic recovery** when service becomes healthy
- **Metrics** for circuit breaker state and performance

### 🚦 Backpressure Management
- **Concurrent operation** limiting
- **Queue capacity** management
- **Timeout handling** for overloaded systems
- **Utilization metrics** and success rates

### 💀 Dead Letter Queue
- **Failed message** capture and storage
- **Retry count** tracking
- **Failure reason** logging
- **Message recovery** capabilities

### 🔄 Database Migrations
- **Automatic schema** setup and updates
- **Version control** for database changes
- **Rollback capabilities** for failed migrations

## Configuration Profiles

The examples support multiple configuration profiles:

- **`development`** - Default profile for local development
- **`production`** - Production-ready configuration with enhanced security
- **`test`** - Optimized for testing environments
- **`demo`** - Used by the self-contained demo

## Running Tests

```bash
# Run all tests for the examples module
mvn test -pl peegeeq-examples

# Run specific test class
mvn test -pl peegeeq-examples -Dtest=PeeGeeQExampleTest
```

## Docker Requirements (Self-Contained Demo Only)

The self-contained demo requires Docker to be installed and running:

```bash
# Check if Docker is running
docker info

# If Docker is not running, start it:
# - On Windows: Start Docker Desktop
# - On macOS: Start Docker Desktop
# - On Linux: sudo systemctl start docker
```

## Troubleshooting

### Common Issues

1. **Database Connection Failed**
   - Verify PostgreSQL is running
   - Check host, port, and credentials
   - Ensure database exists and user has permissions

2. **Docker Issues (Self-Contained Demo)**
   - Ensure Docker is installed and running
   - Check internet connectivity for image download
   - Verify Docker has sufficient resources

3. **Configuration Issues**
   - Check system properties and environment variables
   - Verify profile-specific configuration files
   - Review logs for detailed error messages

### Getting Help

The examples include comprehensive error handling and troubleshooting guidance:
- **Detailed error messages** with specific remediation steps
- **Configuration validation** with helpful hints
- **Alternative approaches** when primary method fails

## Related Documentation

- [Production Readiness Guide](../docs/PRODUCTION_READINESS.md)
- [Configuration Reference](../peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/README.md)
- [Testing Guide](../TESTING.md)
- [PostgreSQL Connectivity Solution](../docs/PostgreSQL%20Connectivity%20Solution%20for%20PeeGeeQ.md)

## Next Steps

After running the examples:

1. **Explore the code** - Review the implementation details
2. **Customize configuration** - Adapt settings for your environment
3. **Run tests** - Execute comprehensive integration tests
4. **Integrate with your application** - Use PeeGeeQManager in your own code
5. **Monitor in production** - Set up metrics and alerting

## Architecture

The examples demonstrate a clean separation of concerns:

```
peegeeq-examples/
├── src/main/java/dev/mars/peegeeq/examples/
│   ├── PeeGeeQExample.java              # Traditional example
│   └── PeeGeeQSelfContainedDemo.java    # Self-contained demo
├── src/test/java/dev/mars/peegeeq/examples/
│   └── PeeGeeQExampleTest.java          # Example tests
├── src/main/resources/
│   └── peegeeq-demo.properties          # Demo configuration
└── pom.xml                              # Maven configuration
```

This structure provides:
- **Clear separation** between different example types
- **Comprehensive testing** of example functionality
- **Flexible configuration** for different environments
- **Self-contained dependencies** for easy execution
