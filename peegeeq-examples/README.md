# PeeGeeQ Examples

This module contains self-contained example applications demonstrating PeeGeeQ's production readiness features.

## Quick Start - Run All Examples

**For the most comprehensive experience, use the example runner to run all examples:**

```bash
# Run ALL examples sequentially (recommended) - Requires Docker
mvn compile exec:java -pl peegeeq-examples

# Alternative: Run directly with classpath
java -cp "target/classes:..." dev.mars.peegeeq.examples.PeeGeeQExampleRunner

# List all available examples
mvn compile exec:java@list-examples -pl peegeeq-examples

# Run specific examples only
java -cp "target/classes:..." dev.mars.peegeeq.examples.PeeGeeQExampleRunner self-contained rest-api
```

## Quick Start - Self-Contained Demo

**For a single comprehensive demo, use the self-contained demo:**

```bash
# Run the self-contained demo (requires Docker) - Single example
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

## PeeGeeQ Example Runner

The **PeeGeeQExampleRunner** is a comprehensive Java application that can run all examples sequentially with detailed reporting:

### Features
- **Sequential execution** of all examples in logical order
- **Error handling and recovery** - continues even if individual examples fail
- **Execution timing and statistics** - tracks performance of each example
- **Selective execution** - run only specific examples
- **Comprehensive reporting** - detailed summary with success rates and performance analysis
- **Categorized examples** - organized by functionality (Core, REST API, Advanced, etc.)

### Usage Examples

```bash
# Run all examples (default behavior)
mvn compile exec:java -pl peegeeq-examples

# List all available examples with descriptions
mvn compile exec:java@list-examples -pl peegeeq-examples

# Run specific examples only
java -cp "target/classes:..." dev.mars.peegeeq.examples.PeeGeeQExampleRunner self-contained rest-api advanced-config

# Run examples by category (using individual Maven executions)
mvn compile exec:java@run-self-contained-demo -pl peegeeq-examples
mvn compile exec:java@run-rest-api-example -pl peegeeq-examples
mvn compile exec:java@run-service-discovery-example -pl peegeeq-examples
```

### Example Categories

The runner organizes examples into logical categories:

1. **Core Examples** - Fundamental PeeGeeQ features and patterns
2. **REST API Examples** - HTTP endpoints and web integration
3. **Service Discovery Examples** - Service registration and federation
4. **Implementation Comparison** - Performance and pattern analysis
5. **Advanced Examples** - Production optimization and security

### Execution Report

The runner provides detailed execution reports including:
- Success/failure count and percentage
- Individual and total execution times
- Performance analysis (fastest/slowest examples)
- Detailed results for each example
- Error details for failed examples

## Complete Example Catalog

This module contains comprehensive examples demonstrating all PeeGeeQ features:

### 🆕 System Properties Configuration Examples

**NEW**: Comprehensive examples demonstrating runtime configuration through system properties.

- **`SystemPropertiesConfigurationExample.java`**: Complete demonstration of all four system properties that control runtime behavior:
  - `peegeeq.queue.max-retries` - Retry attempts before dead letter queue
  - `peegeeq.queue.polling-interval` - Message polling frequency
  - `peegeeq.consumer.threads` - Concurrent processing threads
  - `peegeeq.queue.batch-size` - Batch processing size

- **`RetryAndFailureHandlingExample.java`**: Focused demonstration of retry behavior and failure handling with configurable max retries.

- **`PerformanceComparisonExample.java`**: Performance impact analysis of different system property configurations.

- **`SYSTEM_PROPERTIES_GUIDE.md`**: Comprehensive guide covering all system properties, configuration patterns, and best practices.

**Run these examples**:
```bash
# System properties configuration demo
java -cp "target/classes:..." dev.mars.peegeeq.examples.SystemPropertiesConfigurationExample

# Retry and failure handling demo
java -cp "target/classes:..." dev.mars.peegeeq.examples.RetryAndFailureHandlingExample

# Performance comparison demo
java -cp "target/classes:..." dev.mars.peegeeq.examples.PerformanceComparisonExample
```

### Core Examples

1. **PeeGeeQExample.java** - Production readiness features demonstration
   - Health checks and monitoring
   - Metrics collection and reporting
   - Circuit breaker patterns
   - Backpressure management
   - Dead letter queue handling

2. **PeeGeeQSelfContainedDemo.java** - Self-contained demo using TestContainers
   - Complete setup with PostgreSQL container
   - Database schema initialization
   - Queue operations demonstration
   - Clean shutdown procedures

3. **BiTemporalEventStoreExample.java** - Bi-temporal event store capabilities
   - Event sourcing patterns
   - Temporal queries and corrections
   - Historical data reconstruction
   - Real-time event processing

4. **ConsumerGroupExample.java** - Consumer groups and message routing
   - Message filtering by headers
   - Load balancing across consumers
   - Consumer group coordination
   - Regional message routing

5. **MultiConfigurationExample.java** - Multiple queue configurations
   - Different queue types for different use cases
   - Configuration management patterns
   - Performance optimization strategies

6. **TransactionalBiTemporalExample.java** - Advanced transactional patterns
   - Integration between queues and event stores
   - ACID transaction guarantees
   - Complex business workflows
   - Error handling and recovery

7. **SimpleConsumerGroupTest.java** - Basic consumer group verification
   - Simple consumer group setup
   - Message distribution testing
   - Basic load balancing verification

### REST API Examples

8. **RestApiExample.java** - Comprehensive REST API usage demonstration
   - Database setup management via HTTP endpoints
   - Queue operations through REST API
   - Event store operations via HTTP
   - Health checks and metrics endpoints
   - Consumer group management via REST
   - CORS and web application integration

9. **RestApiStreamingExample.java** - Real-time streaming capabilities
   - WebSocket streaming for message consumption
   - Server-Sent Events (SSE) for real-time updates
   - Message filtering in streaming scenarios
   - Consumer group coordination with streaming
   - Connection management and error handling

### Service Discovery Examples

10. **ServiceDiscoveryExample.java** - Service discovery and federation
    - Service Manager startup and configuration
    - Instance registration and management
    - Federated management across multiple instances
    - Health monitoring and status checking
    - Load balancing and failover scenarios
    - Multi-environment support and filtering
    - Self-contained demo without external dependencies

### Implementation Comparison Examples

11. **NativeVsOutboxComparisonExample.java** - Comprehensive implementation comparison
    - Side-by-side comparison of Native LISTEN/NOTIFY vs Outbox Pattern
    - Performance benchmarking and latency analysis
    - Reliability and delivery guarantee demonstrations
    - Scalability pattern comparisons
    - Failure scenario testing
    - Technical guidance for choosing the right approach
    - Use case recommendations and trade-off analysis

### Advanced Configuration Management Examples

12. **AdvancedConfigurationExample.java** - Production-ready configuration patterns
    - Environment-specific configurations (development, staging, production)
    - External configuration management (properties files, environment variables)
    - Database connection pooling and tuning examples
    - Monitoring integration (Prometheus/Grafana ready examples)
    - Configuration validation and best practices
    - Runtime configuration updates and safety considerations
    - Configuration hierarchy and precedence demonstration

### Enhanced Examples (New)

13. **MessagePriorityExample.java** - Message priority handling demonstration
    - Priority-based message ordering and processing
    - Different priority levels for different message types
    - Priority queue configuration and optimization
    - Consumer behavior with priority messages
    - Performance characteristics of priority queues
    - Real-world use cases for message prioritization

14. **EnhancedErrorHandlingExample.java** - Sophisticated error handling patterns
    - Retry strategies with exponential backoff
    - Circuit breaker integration for consumer error handling
    - Dead letter queue management and recovery
    - Error classification and routing
    - Poison message detection and handling
    - Consumer group error isolation
    - Monitoring and alerting for error conditions

15. **SecurityConfigurationExample.java** - Security best practices and SSL/TLS configuration
    - SSL/TLS configuration for database connections
    - Certificate management and validation
    - Connection security best practices
    - Environment-specific security configurations
    - Security monitoring and logging
    - Credential management patterns
    - Compliance and audit requirements

16. **PerformanceTuningExample.java** - Performance optimization techniques
    - Database connection pool optimization
    - Queue performance tuning strategies
    - Batch processing optimization
    - Memory usage optimization
    - Throughput and latency optimization
    - Performance monitoring and metrics
    - Load testing and capacity planning

17. **IntegrationPatternsExample.java** - Complex distributed system integration patterns
    - Microservices communication patterns
    - Event-driven architecture implementation
    - Request-Reply and Publish-Subscribe patterns
    - Message routing and transformation
    - Content-based routing and aggregation
    - Service orchestration vs choreography
    - Distributed system resilience patterns

### Running the New Examples

#### REST API Examples
```bash
# REST API comprehensive demo
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.RestApiExample" -pl peegeeq-examples

# REST API streaming demo
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.RestApiStreamingExample" -pl peegeeq-examples
```

#### Service Discovery Examples
```bash
# Service Discovery demo (self-contained)
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.ServiceDiscoveryExample" -pl peegeeq-examples
```

#### Implementation Comparison Examples
```bash
# Native vs Outbox Pattern comparison and benchmarking
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.NativeVsOutboxComparisonExample" -pl peegeeq-examples
```

#### Advanced Configuration Management Examples
```bash
# Advanced configuration patterns and best practices
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.AdvancedConfigurationExample" -pl peegeeq-examples
```

#### Enhanced Examples (New)
```bash
# Message Priority handling demonstration
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.MessagePriorityExample" -pl peegeeq-examples

# Enhanced Error Handling patterns
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.EnhancedErrorHandlingExample" -pl peegeeq-examples

# Security Configuration and SSL/TLS best practices
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.SecurityConfigurationExample" -pl peegeeq-examples

# Performance Tuning and optimization techniques
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PerformanceTuningExample" -pl peegeeq-examples

# Integration Patterns for distributed systems
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.IntegrationPatternsExample" -pl peegeeq-examples
```

**Note**: All examples are self-contained and don't require external dependencies.

## Architecture

The examples demonstrate a clean separation of concerns:

```
peegeeq-examples/
├── src/main/java/dev/mars/peegeeq/examples/
│   ├── PeeGeeQExample.java                    # Traditional example
│   ├── PeeGeeQSelfContainedDemo.java          # Self-contained demo
│   ├── BiTemporalEventStoreExample.java       # Bi-temporal event store
│   ├── ConsumerGroupExample.java              # Consumer groups
│   ├── MultiConfigurationExample.java         # Multi-configuration
│   ├── TransactionalBiTemporalExample.java    # Transactional patterns
│   ├── SimpleConsumerGroupTest.java           # Basic consumer groups
│   ├── RestApiExample.java                    # REST API usage
│   ├── RestApiStreamingExample.java           # REST streaming
│   ├── ServiceDiscoveryExample.java           # Service discovery
│   ├── NativeVsOutboxComparisonExample.java   # Implementation comparison
│   └── AdvancedConfigurationExample.java      # Advanced configuration
├── src/test/java/dev/mars/peegeeq/examples/
│   ├── PeeGeeQExampleTest.java                # Core example tests
│   ├── RestApiExampleTest.java                # REST API tests
│   ├── ServiceDiscoveryExampleTest.java       # Service discovery tests
│   └── [Additional test classes...]           # Comprehensive test coverage
├── src/main/resources/
│   └── peegeeq-demo.properties                # Demo configuration
└── pom.xml                                    # Maven configuration
```

This structure provides:
- **Complete feature coverage** across all PeeGeeQ modules
- **Production-ready patterns** for enterprise deployment
- **Comprehensive testing** of all functionality
- **Self-contained examples** requiring minimal setup
- **Advanced integration patterns** for complex scenarios
