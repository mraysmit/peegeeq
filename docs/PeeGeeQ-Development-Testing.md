# PeeGeeQ Development & Testing
#### © Mark Andrew Ray-Smith Cityline Ltd 2025

Complete guide to developing with PeeGeeQ, testing strategies, build processes, and development workflows.

## Table of Contents

1. [Development Environment Setup](#development-environment-setup)
2. [Build System & Commands](#build-system--commands)
3. [Testing Strategies](#testing-strategies)
4. [Development Workflow](#development-workflow)
5. [Automation Scripts](#automation-scripts)
6. [Debugging & Troubleshooting](#debugging--troubleshooting)
7. [Code Quality & Standards](#code-quality--standards)
8. [Contributing Guidelines](#contributing-guidelines)

## Development Environment Setup

### Prerequisites

- **Java 21+** (OpenJDK or Oracle JDK)
- **Maven 3.8+** for building
- **PostgreSQL 12+** for development database
- **Docker** for containerized testing
- **Git** for version control
- **IDE**: IntelliJ IDEA, Eclipse, or VS Code with Java extensions

### Local Development Setup

1. **Clone the Repository**:
```bash
git clone <repository-url>
cd peegeeq
```

2. **Set up PostgreSQL**:
```bash
# Using Docker (recommended for development)
docker run --name peegeeq-dev-db \
  -e POSTGRES_DB=peegeeq_dev \
  -e POSTGRES_USER=peegeeq_dev \
  -e POSTGRES_PASSWORD=dev_password \
  -p 5432:5432 \
  -d postgres:15

# Or install PostgreSQL locally and create database
createdb peegeeq_dev
```

3. **Configure Development Properties**:
```properties
# src/main/resources/application-dev.properties
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.name=peegeeq_dev
peegeeq.database.username=peegeeq_dev
peegeeq.database.password=dev_password

# Enable development features
peegeeq.health.enabled=true
peegeeq.metrics.enabled=true
peegeeq.circuitBreaker.enabled=false
logging.level.dev.mars.peegeeq=DEBUG
```

4. **Initialize Development Database**:
```bash
mvn clean compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.DatabaseInitializer"
```

### IDE Configuration

#### IntelliJ IDEA Setup

1. **Import Project**: File → Open → Select `pom.xml`
2. **Configure JDK**: File → Project Structure → Project → SDK → Java 21
3. **Enable Annotation Processing**: Settings → Build → Compiler → Annotation Processors → Enable
4. **Code Style**: Import `ide-config/intellij-code-style.xml`
5. **Run Configurations**: Import from `.run/` directory

#### VS Code Setup

1. **Install Extensions**:
   - Extension Pack for Java
   - Spring Boot Extension Pack
   - PostgreSQL extension

2. **Configure Settings** (`.vscode/settings.json`):
```json
{
    "java.configuration.updateBuildConfiguration": "automatic",
    "java.compile.nullAnalysis.mode": "automatic",
    "java.format.settings.url": "ide-config/eclipse-formatter.xml",
    "maven.executable.path": "mvn"
}
```

## Build System & Commands

### Maven Project Structure

```
peegeeq/
├── pom.xml                    # Parent POM
├── peegeeq-api/              # Core API interfaces
├── peegeeq-db/               # Database management
├── peegeeq-native/           # Native queue implementation
├── peegeeq-outbox/           # Outbox pattern implementation
├── peegeeq-bitemporal/       # Bi-temporal event store
├── peegeeq-rest/             # REST API server
├── peegeeq-service-manager/  # Service discovery
├── peegeeq-examples/         # Example applications (17 comprehensive examples)
└── scripts/                  # Build and utility scripts
```

### Comprehensive Examples Overview

The `peegeeq-examples/` module contains 17 comprehensive examples covering 95-98% of PeeGeeQ functionality:

#### Core Examples
- **PeeGeeQSelfContainedDemo** - Complete self-contained demonstration
- **PeeGeeQExample** - Basic producer/consumer patterns
- **BiTemporalEventStoreExample** - Event sourcing with temporal queries
- **ConsumerGroupExample** - Load balancing and consumer groups
- **RestApiExample** - HTTP interface usage
- **ServiceDiscoveryExample** - Multi-instance deployment

#### Advanced Examples (Enhanced)
- **MessagePriorityExample** - Priority-based message processing with real-world scenarios
- **EnhancedErrorHandlingExample** - Retry strategies, circuit breakers, poison message handling
- **SecurityConfigurationExample** - SSL/TLS, certificate management, compliance features
- **PerformanceTuningExample** - Connection pooling, throughput optimization, memory tuning
- **IntegrationPatternsExample** - Request-reply, pub-sub, message routing, distributed patterns

#### Specialized Examples
- **TransactionalBiTemporalExample** - Combining transactions with event sourcing
- **RestApiStreamingExample** - WebSocket and Server-Sent Events
- **NativeVsOutboxComparisonExample** - Performance comparison and use case guidance
- **AdvancedConfigurationExample** - Production configuration patterns
- **MultiConfigurationExample** - Multi-environment setup
- **SimpleConsumerGroupTest** - Basic consumer group testing

#### Running Examples
```bash
# Run any example
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.ExampleName" -pl peegeeq-examples

# Examples with specific focus areas:
# Message Priority
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.MessagePriorityExample" -pl peegeeq-examples

# Error Handling
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.EnhancedErrorHandlingExample" -pl peegeeq-examples

# Security
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.SecurityConfigurationExample" -pl peegeeq-examples

# Performance
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PerformanceTuningExample" -pl peegeeq-examples

# Integration Patterns
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.IntegrationPatternsExample" -pl peegeeq-examples
```

### Essential Build Commands

#### Basic Build Operations
```bash
# Clean and compile all modules
mvn clean compile

# Run all tests
mvn test

# Package without running tests
mvn package -DskipTests

# Full build with tests
mvn clean install

# Build specific module
mvn clean install -pl peegeeq-api

# Build module and its dependencies
mvn clean install -pl peegeeq-native -am
```

#### Testing Commands
```bash
# Run unit tests only
mvn test

# Run integration tests
mvn verify -P integration-tests

# Run tests for specific module
mvn test -pl peegeeq-db

# Run specific test class
mvn test -Dtest=PeeGeeQManagerTest

# Run specific test method
mvn test -Dtest=PeeGeeQManagerTest#testInitialization

# Run tests with coverage
mvn clean test jacoco:report
```

#### Quality & Analysis
```bash
# Run static analysis
mvn spotbugs:check

# Check code formatting
mvn spotless:check

# Apply code formatting
mvn spotless:apply

# Generate site documentation
mvn site

# Run dependency analysis
mvn dependency:analyze
```

#### Docker & Containerization
```bash
# Build Docker images
mvn clean package docker:build

# Run containerized tests
mvn clean verify -P docker-tests

# Start development environment
docker-compose -f docker/dev-compose.yml up -d

# Stop development environment
docker-compose -f docker/dev-compose.yml down
```

### Maven Profiles

Available profiles for different build scenarios:

```xml
<!-- Development profile -->
<profile>
    <id>dev</id>
    <properties>
        <spring.profiles.active>dev</spring.profiles.active>
        <skipTests>false</skipTests>
    </properties>
</profile>

<!-- Integration tests -->
<profile>
    <id>integration-tests</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>

<!-- Docker tests -->
<profile>
    <id>docker-tests</id>
    <build>
        <plugins>
            <plugin>
                <groupId>io.fabric8</groupId>
                <artifactId>docker-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>start-containers</id>
                        <phase>pre-integration-test</phase>
                        <goals>
                            <goal>start</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>stop-containers</id>
                        <phase>post-integration-test</phase>
                        <goals>
                            <goal>stop</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

## Testing Strategies

### Test Categories

PeeGeeQ uses a comprehensive testing strategy with multiple test categories:

1. **Unit Tests**: Fast, isolated tests for individual components
2. **Integration Tests**: Test component interactions with real database
3. **Contract Tests**: Verify API contracts and interfaces
4. **Performance Tests**: Load and stress testing
5. **End-to-End Tests**: Full system testing with all components

### Unit Testing

#### Example Unit Test Structure

```java
@ExtendWith(MockitoExtension.class)
class MessageProducerTest {
    
    @Mock
    private DatabaseService databaseService;
    
    @Mock
    private PeeGeeQMetrics metrics;
    
    @InjectMocks
    private PgNativeProducer<String> producer;
    
    @Test
    void shouldSendMessageSuccessfully() {
        // Given
        String message = "test message";
        when(databaseService.update(anyString(), any())).thenReturn(1);
        
        // When
        CompletableFuture<Void> result = producer.send(message);
        
        // Then
        assertThat(result).succeedsWithin(Duration.ofSeconds(1));
        verify(databaseService).update(contains("INSERT INTO queue_messages"), any());
        verify(metrics).recordMessageProduced("test-queue");
    }
    
    @Test
    void shouldHandleDatabaseFailure() {
        // Given
        String message = "test message";
        when(databaseService.update(anyString(), any()))
            .thenThrow(new RuntimeException("Database connection failed"));
        
        // When & Then
        assertThatThrownBy(() -> producer.send(message).join())
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(RuntimeException.class)
            .hasMessageContaining("Database connection failed");
    }
}
```

### Integration Testing

#### TestContainers Integration

PeeGeeQ uses TestContainers for integration testing with real PostgreSQL:

```java
@Testcontainers
@SpringBootTest
class PeeGeeQIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", postgres::getFirstMappedPort);
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
    }
    
    @Autowired
    private PeeGeeQManager peeGeeQManager;
    
    @Test
    void shouldProcessMessagesEndToEnd() throws Exception {
        // Given
        QueueFactory factory = QueueFactoryProvider.getInstance()
            .createFactory("native", peeGeeQManager.getDatabaseService());
        
        MessageProducer<String> producer = factory.createProducer("test-queue", String.class);
        MessageConsumer<String> consumer = factory.createConsumer("test-queue", String.class);
        
        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        // When
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            messageReceived.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        producer.send("Hello, Integration Test!").join();
        
        // Then
        assertThat(messageReceived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedMessage.get()).isEqualTo("Hello, Integration Test!");
    }
}
```

### Intentional Failure Testing

PeeGeeQ includes comprehensive failure testing to validate error handling:

```java
@Test
void shouldHandleCircuitBreakerOpening() {
    // Given - Configure circuit breaker to open after 3 failures
    CircuitBreakerConfig config = CircuitBreakerConfig.builder()
        .failureRateThreshold(100.0f)
        .minimumNumberOfCalls(3)
        .build();
    
    CircuitBreaker circuitBreaker = CircuitBreaker.of("test-operation", config);
    
    // When - Execute multiple failing operations
    for (int i = 0; i < 3; i++) {
        try {
            circuitBreaker.executeSupplier(() -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE: Simulated failure #" + i);
            });
        } catch (Exception e) {
            log.info("**INTENTIONAL TEST FAILURE** Simulated failure #{}", i);
        }
    }
    
    // Then - Circuit breaker should be open
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    log.info("**SUCCESS** Circuit breaker properly opened after multiple failures");
}
```

### Performance Testing

#### Load Testing Example

```java
@Test
@Timeout(60)
void shouldHandleHighThroughput() throws Exception {
    // Given
    int messageCount = 10000;
    int producerThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(producerThreads);
    CountDownLatch allMessagesSent = new CountDownLatch(messageCount);
    CountDownLatch allMessagesReceived = new CountDownLatch(messageCount);
    
    // Set up consumer
    consumer.subscribe(message -> {
        allMessagesReceived.countDown();
        return CompletableFuture.completedFuture(null);
    });
    
    // When - Send messages concurrently
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < messageCount; i++) {
        final int messageId = i;
        executor.submit(() -> {
            try {
                producer.send("Message " + messageId).join();
                allMessagesSent.countDown();
            } catch (Exception e) {
                log.error("Failed to send message {}", messageId, e);
            }
        });
    }
    
    // Then - All messages should be processed
    assertThat(allMessagesSent.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(allMessagesReceived.await(30, TimeUnit.SECONDS)).isTrue();
    
    long duration = System.currentTimeMillis() - startTime;
    double throughput = (messageCount * 1000.0) / duration;
    
    log.info("Processed {} messages in {}ms (throughput: {:.2f} msg/sec)", 
             messageCount, duration, throughput);
    
    // Verify minimum throughput requirement
    assertThat(throughput).isGreaterThan(1000.0); // At least 1000 msg/sec
}
```

### Test Data Management

#### Test Data Builders

```java
public class OrderEventTestDataBuilder {
    private String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);
    private String customerId = "CUST-" + UUID.randomUUID().toString().substring(0, 8);
    private BigDecimal amount = new BigDecimal("99.99");
    private OrderStatus status = OrderStatus.PENDING;
    
    public static OrderEventTestDataBuilder anOrderEvent() {
        return new OrderEventTestDataBuilder();
    }
    
    public OrderEventTestDataBuilder withOrderId(String orderId) {
        this.orderId = orderId;
        return this;
    }
    
    public OrderEventTestDataBuilder withCustomerId(String customerId) {
        this.customerId = customerId;
        return this;
    }
    
    public OrderEventTestDataBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }
    
    public OrderEventTestDataBuilder withStatus(OrderStatus status) {
        this.status = status;
        return this;
    }
    
    public OrderEvent build() {
        return new OrderEvent(orderId, customerId, amount, status);
    }
}

// Usage in tests
@Test
void shouldProcessLargeOrder() {
    // Given
    OrderEvent largeOrder = anOrderEvent()
        .withAmount(new BigDecimal("10000.00"))
        .withStatus(OrderStatus.PENDING)
        .build();
    
    // When & Then
    assertThat(orderProcessor.process(largeOrder)).isTrue();
}
```

## Development Workflow

### Git Workflow

PeeGeeQ follows a feature branch workflow with the following conventions:

#### Branch Naming
- `main` - Production-ready code
- `develop` - Integration branch for features
- `feature/ISSUE-123-description` - Feature branches
- `bugfix/ISSUE-456-description` - Bug fix branches
- `hotfix/ISSUE-789-description` - Critical production fixes

#### Commit Message Format
```
type(scope): short description

Longer description if needed

Fixes #123
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

#### Example Workflow
```bash
# Start new feature
git checkout develop
git pull origin develop
git checkout -b feature/ISSUE-123-add-consumer-groups

# Make changes and commit
git add .
git commit -m "feat(consumer-groups): implement basic consumer group coordination

- Add ConsumerGroup and ConsumerGroupMember classes
- Implement round-robin load balancing
- Add consumer group registration and heartbeat

Fixes #123"

# Push and create pull request
git push origin feature/ISSUE-123-add-consumer-groups
# Create PR via GitHub/GitLab interface

# After review and approval, merge to develop
git checkout develop
git pull origin develop
git branch -d feature/ISSUE-123-add-consumer-groups
```

### Code Review Process

#### Pull Request Checklist

**Before Creating PR:**
- [ ] All tests pass locally (`mvn clean test`)
- [ ] Code follows style guidelines (`mvn spotless:check`)
- [ ] Documentation updated if needed
- [ ] Integration tests added for new features
- [ ] Performance impact considered and tested

**PR Description Template:**
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Manual testing performed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Documentation updated
- [ ] Tests pass locally
```

#### Review Guidelines

**For Reviewers:**
1. **Functionality**: Does the code do what it's supposed to do?
2. **Design**: Is the code well-designed and fit the architecture?
3. **Complexity**: Is the code more complex than it needs to be?
4. **Tests**: Are there appropriate tests for the changes?
5. **Naming**: Are variables, methods, and classes named clearly?
6. **Documentation**: Is the code properly documented?

### Release Process

#### Version Management

PeeGeeQ uses semantic versioning (MAJOR.MINOR.PATCH):
- **MAJOR**: Breaking changes
- **MINOR**: New features, backward compatible
- **PATCH**: Bug fixes, backward compatible

#### Release Steps

```bash
# 1. Prepare release branch
git checkout develop
git pull origin develop
git checkout -b release/1.2.0

# 2. Update version numbers
mvn versions:set -DnewVersion=1.2.0
mvn versions:commit

# 3. Update changelog
# Edit CHANGELOG.md with release notes

# 4. Commit version changes
git add .
git commit -m "chore: prepare release 1.2.0"

# 5. Create release PR to main
git push origin release/1.2.0
# Create PR: release/1.2.0 → main

# 6. After PR approval, tag release
git checkout main
git pull origin main
git tag -a v1.2.0 -m "Release version 1.2.0"
git push origin v1.2.0

# 7. Merge back to develop
git checkout develop
git merge main
git push origin develop
```

## Automation Scripts

### Shell Scripts

PeeGeeQ includes cross-platform shell scripts for common development tasks:

#### update-java-headers.sh

Updates JavaDoc headers with author information:

```bash
#!/bin/bash
# Usage: ./update-java-headers.sh [--dry-run] [--verbose] [--help]

AUTHOR_NAME="Mark Andrew Ray-Smith Cityline Ltd"
COPYRIGHT_YEAR=$(date +%Y)
PROJECT_NAME="PeeGeeQ"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Process command line arguments
DRY_RUN=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        --help)
            echo "Usage: $0 [--dry-run] [--verbose] [--help]"
            echo "  --dry-run   Show what would be changed without making changes"
            echo "  --verbose   Show detailed output"
            echo "  --help      Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Find and process Java files
find . -name "*.java" -not -path "./target/*" -not -path "./.git/*" | while read -r file; do
    if [[ $VERBOSE == true ]]; then
        echo -e "${BLUE}Processing: $file${NC}"
    fi

    # Check if file already has proper header
    if grep -q "© $AUTHOR_NAME $COPYRIGHT_YEAR" "$file"; then
        if [[ $VERBOSE == true ]]; then
            echo -e "${GREEN}  Header already up to date${NC}"
        fi
        continue
    fi

    # Determine file type and create appropriate header
    if grep -q "^public class\|^public interface\|^public enum\|^public @interface" "$file"; then
        FILE_TYPE=$(grep -o "^public \(class\|interface\|enum\|@interface\)" "$file" | head -1 | cut -d' ' -f2)
        CLASS_NAME=$(basename "$file" .java)

        HEADER="/**
 * $CLASS_NAME $FILE_TYPE for $PROJECT_NAME
 *
 * @author $AUTHOR_NAME
 * @since $COPYRIGHT_YEAR
 */"

        if [[ $DRY_RUN == true ]]; then
            echo -e "${YELLOW}  Would update: $file${NC}"
        else
            # Create temporary file with new header
            {
                # Add package and imports first
                grep "^package\|^import" "$file"
                echo ""
                echo "$HEADER"
                echo ""
                # Add rest of file, skipping existing package/imports
                grep -v "^package\|^import" "$file"
            } > "$file.tmp" && mv "$file.tmp" "$file"

            echo -e "${GREEN}  Updated: $file${NC}"
        fi
    fi
done

echo -e "${GREEN}Header update complete!${NC}"
```

#### add-license-headers.sh

Adds Apache License 2.0 headers to Java files:

```bash
#!/bin/bash
# Usage: ./add-license-headers.sh [--force] [--dry-run] [--help]

AUTHOR_NAME="Mark Andrew Ray-Smith Cityline Ltd"
COPYRIGHT_YEAR="2025"

LICENSE_HEADER="/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * \"License\"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * © $AUTHOR_NAME $COPYRIGHT_YEAR
 */"

# Process command line arguments
FORCE=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            echo "Usage: $0 [--force] [--dry-run] [--help]"
            echo "  --force     Update files that already have license headers"
            echo "  --dry-run   Show what would be changed without making changes"
            echo "  --help      Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Find and process Java files
find . -name "*.java" -not -path "./target/*" -not -path "./.git/*" | while read -r file; do
    # Check if file already has license header
    if grep -q "Licensed to the Apache Software Foundation" "$file" && [[ $FORCE == false ]]; then
        echo "Skipping $file (already has license header, use --force to update)"
        continue
    fi

    if [[ $DRY_RUN == true ]]; then
        echo "Would add license header to: $file"
    else
        # Create temporary file with license header
        {
            echo "$LICENSE_HEADER"
            echo ""
            # Skip existing license headers if force updating
            if [[ $FORCE == true ]]; then
                sed '/^\/\*$/,/^\*\/$/d' "$file"
            else
                cat "$file"
            fi
        } > "$file.tmp" && mv "$file.tmp" "$file"

        echo "Added license header to: $file"
    fi
done

echo "License header processing complete!"
```

### Build Automation

#### Continuous Integration Pipeline

```yaml
# .github/workflows/ci.yml
name: CI Pipeline

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: peegeeq_test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 21
      uses: actions/setup-java@v3
      with:
        java-version: '21'
        distribution: 'temurin'

    - name: Cache Maven dependencies
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
        restore-keys: ${{ runner.os }}-m2

    - name: Run tests
      run: mvn clean test
      env:
        PEEGEEQ_DATABASE_HOST: localhost
        PEEGEEQ_DATABASE_PORT: 5432
        PEEGEEQ_DATABASE_NAME: peegeeq_test
        PEEGEEQ_DATABASE_USERNAME: postgres
        PEEGEEQ_DATABASE_PASSWORD: postgres

    - name: Run integration tests
      run: mvn verify -P integration-tests

    - name: Generate test report
      uses: dorny/test-reporter@v1
      if: success() || failure()
      with:
        name: Maven Tests
        path: target/surefire-reports/*.xml
        reporter: java-junit

    - name: Upload coverage to Codecov
      uses: codecov/codecov-action@v3
      with:
        file: target/site/jacoco/jacoco.xml
```

## Debugging & Troubleshooting

### Common Development Issues

#### Database Connection Issues

**Problem**: `Connection refused` or `Database connection failed`

**Solutions**:
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Check connection parameters
psql -h localhost -p 5432 -U peegeeq_dev -d peegeeq_dev

# Reset development database
docker rm -f peegeeq-dev-db
docker run --name peegeeq-dev-db \
  -e POSTGRES_DB=peegeeq_dev \
  -e POSTGRES_USER=peegeeq_dev \
  -e POSTGRES_PASSWORD=dev_password \
  -p 5432:5432 \
  -d postgres:15
```

#### Build Failures

**Problem**: Maven build fails with dependency issues

**Solutions**:
```bash
# Clean local repository
rm -rf ~/.m2/repository/dev/mars/peegeeq

# Force update dependencies
mvn clean install -U

# Check for dependency conflicts
mvn dependency:tree
```

#### Test Failures

**Problem**: Tests fail intermittently or in CI

**Solutions**:
```bash
# Run tests with more verbose output
mvn test -X

# Run specific failing test
mvn test -Dtest=FailingTestClass#failingMethod

# Check for resource leaks
mvn test -Dmaven.test.failure.ignore=true
grep -r "Connection leak" target/surefire-reports/
```

### Debugging Tools

#### Application Logging

Configure detailed logging for debugging:

```properties
# application-debug.properties
logging.level.dev.mars.peegeeq=DEBUG
logging.level.org.springframework.jdbc=DEBUG
logging.level.com.zaxxer.hikari=DEBUG

# Log SQL statements
logging.level.org.postgresql=DEBUG

# Log message processing
logging.level.dev.mars.peegeeq.native.PgNativeConsumer=TRACE
logging.level.dev.mars.peegeeq.outbox.OutboxConsumer=TRACE
```

#### JVM Debugging

Enable remote debugging:

```bash
# Start application with debug port
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar target/peegeeq-application.jar

# Connect from IDE to localhost:5005
```

#### Database Debugging

Monitor database activity:

```sql
-- Enable query logging
ALTER SYSTEM SET log_statement = 'all';
ALTER SYSTEM SET log_min_duration_statement = 0;
SELECT pg_reload_conf();

-- Monitor active connections
SELECT pid, usename, application_name, client_addr, state, query
FROM pg_stat_activity
WHERE datname = 'peegeeq_dev';

-- Check for locks
SELECT blocked_locks.pid AS blocked_pid,
       blocked_activity.usename AS blocked_user,
       blocking_locks.pid AS blocking_pid,
       blocking_activity.usename AS blocking_user,
       blocked_activity.query AS blocked_statement,
       blocking_activity.query AS current_statement_in_blocking_process
FROM pg_catalog.pg_locks blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype
JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;
```

## Code Quality & Standards

### Code Style Guidelines

PeeGeeQ follows Google Java Style Guide with some modifications:

#### Formatting Rules
- **Indentation**: 4 spaces (not tabs)
- **Line Length**: 120 characters maximum
- **Braces**: K&R style (opening brace on same line)
- **Imports**: Organize imports, no wildcards except for static imports

#### Naming Conventions
- **Classes**: PascalCase (`MessageProducer`, `DatabaseService`)
- **Methods**: camelCase (`sendMessage`, `getConnection`)
- **Variables**: camelCase (`messageCount`, `databaseUrl`)
- **Constants**: UPPER_SNAKE_CASE (`MAX_RETRY_COUNT`, `DEFAULT_TIMEOUT`)
- **Packages**: lowercase (`dev.mars.peegeeq.api`)

#### Documentation Standards
```java
/**
 * Produces messages to a PostgreSQL-based queue.
 *
 * <p>This implementation uses PostgreSQL's LISTEN/NOTIFY mechanism for
 * real-time message delivery with high throughput and low latency.
 *
 * <p>Example usage:
 * <pre>{@code
 * MessageProducer<String> producer = factory.createProducer("orders", String.class);
 * producer.send("Order #12345 created").join();
 * }</pre>
 *
 * @param <T> the type of messages this producer handles
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 1.0.0
 */
public class PgNativeProducer<T> implements MessageProducer<T> {

    /**
     * Sends a message to the queue asynchronously.
     *
     * @param message the message payload to send
     * @return a future that completes when the message is sent
     * @throws IllegalArgumentException if message is null
     * @throws RuntimeException if database operation fails
     */
    @Override
    public CompletableFuture<Void> send(T message) {
        // Implementation
    }
}
```

### Static Analysis

#### SpotBugs Configuration

```xml
<!-- spotbugs-exclude.xml -->
<FindBugsFilter>
    <!-- Exclude test classes from certain checks -->
    <Match>
        <Class name="~.*Test.*"/>
        <Bug pattern="DM_EXIT"/>
    </Match>

    <!-- Exclude generated code -->
    <Match>
        <Package name="~.*\.generated\..*"/>
    </Match>
</FindBugsFilter>
```

#### Checkstyle Rules

```xml
<!-- checkstyle.xml -->
<module name="Checker">
    <module name="TreeWalker">
        <module name="ConstantName"/>
        <module name="LocalFinalVariableName"/>
        <module name="LocalVariableName"/>
        <module name="MemberName"/>
        <module name="MethodName"/>
        <module name="PackageName"/>
        <module name="ParameterName"/>
        <module name="StaticVariableName"/>
        <module name="TypeName"/>

        <module name="LineLength">
            <property name="max" value="120"/>
        </module>

        <module name="MethodLength">
            <property name="max" value="50"/>
        </module>

        <module name="ParameterNumber">
            <property name="max" value="7"/>
        </module>
    </module>
</module>
```

## Contributing Guidelines

### Getting Started

1. **Fork the Repository**: Create your own fork on GitHub
2. **Clone Your Fork**: `git clone https://github.com/yourusername/peegeeq.git`
3. **Set Up Development Environment**: Follow setup instructions above
4. **Create Feature Branch**: `git checkout -b feature/your-feature-name`

### Making Changes

1. **Write Tests First**: Follow TDD approach when possible
2. **Keep Changes Focused**: One feature or fix per PR
3. **Follow Code Style**: Use provided formatting rules
4. **Update Documentation**: Keep docs in sync with code changes
5. **Add Integration Tests**: For new features that touch multiple components

### Submitting Changes

1. **Run Full Test Suite**: `mvn clean verify`
2. **Check Code Quality**: `mvn spotbugs:check spotless:check`
3. **Update CHANGELOG.md**: Add entry for your changes
4. **Create Pull Request**: Use the provided PR template
5. **Respond to Feedback**: Address review comments promptly

### Code Review Criteria

**Functionality**:
- [ ] Code works as intended
- [ ] Edge cases are handled
- [ ] Error conditions are properly managed

**Design**:
- [ ] Code follows established patterns
- [ ] Abstractions are appropriate
- [ ] Dependencies are minimal

**Testing**:
- [ ] Unit tests cover new code
- [ ] Integration tests verify behavior
- [ ] Tests are maintainable and clear

**Documentation**:
- [ ] Public APIs are documented
- [ ] Complex logic is explained
- [ ] Examples are provided where helpful

---

**Next Reading**: [PeeGeeQ Implementation Notes](PeeGeeQ-Implementation-Notes.md) for implementation history, troubleshooting guides, and reference information.
