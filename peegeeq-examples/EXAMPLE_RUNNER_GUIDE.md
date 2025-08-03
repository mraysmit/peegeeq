# PeeGeeQ Example Runner Guide

The **PeeGeeQExampleRunner** is a comprehensive Java application that executes all PeeGeeQ examples sequentially with detailed reporting and error handling.

## Quick Start

### Run All Examples (Recommended)
```bash
# Run all 16 examples in logical order
mvn compile exec:java -pl peegeeq-examples

# Alternative using explicit main class
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQExampleRunner" -pl peegeeq-examples
```

### List Available Examples
```bash
# Show all examples with descriptions
mvn compile exec:java@list-examples -pl peegeeq-examples

# Alternative
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQExampleRunner" -Dexec.args="--list" -pl peegeeq-examples
```

### Run Specific Examples
```bash
# Run only selected examples
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQExampleRunner" -Dexec.args="self-contained rest-api advanced-config" -pl peegeeq-examples
```

## Available Examples (16 Total)

### Core Examples (6)
- **self-contained** (RECOMMENDED) - Self-contained demo with Docker PostgreSQL (RECOMMENDED FIRST)
- **traditional** - Traditional example with external PostgreSQL
- **bitemporal** - Bi-temporal event store capabilities
- **consumer-groups** - Consumer groups and message routing
- **multi-config** - Multiple queue configurations
- **transactional** - Advanced transactional patterns

### REST API Examples (2)
- **rest-api** - Comprehensive REST API usage
- **rest-streaming** - Real-time streaming capabilities

### Service Discovery Examples (1)
- **service-discovery** - Service discovery and federation

### Implementation Comparison (1)
- **native-vs-outbox** - Implementation comparison and benchmarking

### Advanced Examples (6)
- **advanced-config** - Production-ready configuration patterns
- **message-priority** - Message priority handling
- **error-handling** - Sophisticated error handling patterns
- **security** - Security best practices and SSL/TLS
- **performance** - Performance optimization techniques
- **integration-patterns** - Distributed system integration patterns

## Features

### ✅ Sequential Execution
- Runs examples in logical order (Core → REST API → Service Discovery → Comparison → Advanced)
- Starts with the recommended self-contained demo
- 3-second pause between examples for cleanup

### ✅ Comprehensive Error Handling
- Continues execution even if individual examples fail
- Captures and reports error details
- Tracks success/failure rates

### ✅ Detailed Reporting
- **Execution Summary**: Total count, success rate, overall time
- **Performance Analysis**: Fastest/slowest examples, average duration
- **Detailed Results**: Individual timing and status for each example
- **Failed Examples**: Error details for troubleshooting

### ✅ Flexible Execution
- Run all examples or select specific ones
- List examples with descriptions
- Categorized organization for easy navigation

## Sample Output

```
================================================================================
Running Example 1/16: self-contained (PeeGeeQSelfContainedDemo)
Description: Self-contained demo with Docker PostgreSQL
================================================================================
Invoking PeeGeeQSelfContainedDemo.main()...
[Example execution output...]
✅ Example 'self-contained' completed successfully in 45.234s

Pausing 3 seconds before next example...

================================================================================
EXECUTION SUMMARY
================================================================================
Total Examples: 16
✅ Successful: 15
❌ Failed: 1
⏱ Total Time: 12m 34s
📊 Success Rate: 93.8%

PERFORMANCE ANALYSIS:
----------------------------------------
🚀 Fastest: message-priority (8.123s)
🐌 Slowest: rest-streaming (1m 23s)
📈 Average: 47.1 seconds
```

## Individual Example Execution

You can also run individual examples using Maven executions:

```bash
# Core examples
mvn compile exec:java@run-self-contained-demo -pl peegeeq-examples
mvn compile exec:java@run-traditional-example -pl peegeeq-examples
mvn compile exec:java@run-bitemporal-example -pl peegeeq-examples
mvn compile exec:java@run-consumer-group-example -pl peegeeq-examples
mvn compile exec:java@run-multi-config-example -pl peegeeq-examples
mvn compile exec:java@run-transactional-example -pl peegeeq-examples

# REST API examples
mvn compile exec:java@run-rest-api-example -pl peegeeq-examples
mvn compile exec:java@run-rest-streaming-example -pl peegeeq-examples

# Service discovery
mvn compile exec:java@run-service-discovery-example -pl peegeeq-examples

# Implementation comparison
mvn compile exec:java@run-native-vs-outbox-example -pl peegeeq-examples

# Advanced examples
mvn compile exec:java@run-advanced-config-example -pl peegeeq-examples
mvn compile exec:java@run-message-priority-example -pl peegeeq-examples
mvn compile exec:java@run-error-handling-example -pl peegeeq-examples
mvn compile exec:java@run-security-example -pl peegeeq-examples
mvn compile exec:java@run-performance-example -pl peegeeq-examples
mvn compile exec:java@run-integration-patterns-example -pl peegeeq-examples
```

## Requirements

- **Docker**: Required for most examples (they use TestContainers)
- **Java 21**: Required for compilation and execution
- **Maven**: For dependency management and execution
- **Internet Connection**: For downloading Docker images

## Troubleshooting

### Docker Issues
- Ensure Docker is running: `docker info`
- Check available disk space for container images
- Verify internet connectivity for image downloads

### Memory Issues
- Some examples are memory-intensive
- Consider increasing JVM heap size: `-Xmx2g`

### Port Conflicts
- Examples use various ports (8080, 9090, etc.)
- Ensure ports are available or examples may fail

## Next Steps

After running the examples:

1. **Explore the code** - Review implementation details in `src/main/java/dev/mars/peegeeq/examples/`
2. **Run tests** - Execute `mvn test -pl peegeeq-examples` for comprehensive testing
3. **Customize configuration** - Adapt settings for your environment
4. **Integrate with your application** - Use PeeGeeQ patterns in your own code
5. **Monitor in production** - Set up metrics and alerting based on example patterns

## Related Documentation

- [Main README](README.md) - Complete module documentation
- [Production Readiness Guide](../docs/PRODUCTION_READINESS.md)
- [Configuration Reference](../peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/README.md)
- [Testing Guide](../TESTING.md)
