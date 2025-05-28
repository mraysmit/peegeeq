# PostgreSQL as a Message Queue (PeeGeeQ)

This project demonstrates how to use PostgreSQL as a message queue, implementing two different approaches:

1. **Outbox Pattern**: A reliable way to implement eventual consistency in distributed systems
2. **Native PostgreSQL Queue**: Utilizing PostgreSQL's LISTEN/NOTIFY, advisory locks, and other features

## Features

- Thread-safe implementations
- SOLID design principles
- Configurable retry mechanisms
- Real-time notifications with LISTEN/NOTIFY
- Optimistic locking for concurrency control
- Connection pooling with HikariCP
- Comprehensive error handling

## Architecture

The project is organized into the following packages:

```
dev.mars.peegeeq
├── api                     # Public interfaces
│   ├── MessageQueue.java   # Core queue interface
│   ├── Message.java        # Message interface
│   └── Consumer.java       # Message consumer interface
├── core                    # Core implementations
│   ├── PostgresConfig.java # Database configuration
│   └── AbstractQueue.java  # Common queue functionality
├── outbox                  # Outbox pattern implementation
│   ├── OutboxQueue.java    # Outbox pattern queue
│   └── OutboxMessage.java  # Outbox message implementation
├── pg                      # Native PostgreSQL queue implementation
│   ├── PgQueue.java        # Native PostgreSQL queue
│   ├── PgMessage.java      # Native queue message implementation
│   └── NotificationListener.java # LISTEN/NOTIFY handler
└── util                    # Utilities
    ├── ConnectionPool.java # Thread-safe connection pool
    └── ThreadSafetyUtils.java # Thread safety utilities
```

## Getting Started

### Prerequisites

- Java 24 or higher
- PostgreSQL 12 or higher
- Maven 3.6 or higher

### Database Setup

Run the schema creation script:

```sql
-- See src/main/resources/db/schema.sql for the complete schema
```

### Usage

#### Outbox Pattern

```java
// Create configuration
PostgresConfig config = new PostgresConfig.Builder()
    .withHost("localhost")
    .withPort(5432)
    .withDatabase("mydb")
    .withUsername("user")
    .withPassword("password")
    .build();

// Create connection pool
DataSource dataSource = ConnectionPool.getInstance(config).getDataSource();

// Create object mapper
ObjectMapper objectMapper = new ObjectMapper();

// Create queue
OutboxQueue<MyPayload> queue = new OutboxQueue<>(
    dataSource,
    objectMapper,
    MyPayload.class,
    5, // Thread pool size
    Duration.ofSeconds(1), // Polling interval
    10, // Batch size
    3  // Max retries
);

// Send a message
queue.send("my-topic", new MyPayload("Hello, world!"));

// Receive a message
Optional<Message<MyPayload>> message = queue.receive("my-topic", Duration.ofMinutes(5));
message.ifPresent(m -> {
    // Process message
    System.out.println("Received: " + m.getPayload());

    // Complete message
    queue.complete("my-topic", m.getId());
});

// Subscribe a consumer
queue.subscribe("my-topic", message -> {
    System.out.println("Consumed: " + message.getPayload());
});

// Shutdown when done
queue.shutdown();
```

#### Native PostgreSQL Queue

```java
// Create configuration
PostgresConfig config = new PostgresConfig.Builder()
    .withHost("localhost")
    .withPort(5432)
    .withDatabase("mydb")
    .withUsername("user")
    .withPassword("password")
    .build();

// Create connection pool
DataSource dataSource = ConnectionPool.getInstance(config).getDataSource();

// Create object mapper
ObjectMapper objectMapper = new ObjectMapper();

// Create queue
PgQueue<MyPayload> queue = new PgQueue<>(
    dataSource,
    objectMapper,
    MyPayload.class,
    5, // Thread pool size
    3  // Max retries
);

// Send a message
queue.send("my-topic", new MyPayload("Hello, world!"));

// Receive a message
Optional<Message<MyPayload>> message = queue.receive("my-topic", Duration.ofMinutes(5));
message.ifPresent(m -> {
    // Process message
    System.out.println("Received: " + m.getPayload());

    // Complete message
    queue.complete("my-topic", m.getId());
});

// Subscribe a consumer
queue.subscribe("my-topic", message -> {
    System.out.println("Consumed: " + message.getPayload());
});

// Shutdown when done
queue.shutdown();
```

## SOLID Design Principles

### Single Responsibility Principle
Each class has a single responsibility:
- `MessageQueue`: Interface for queue operations
- `OutboxQueue.OutboxPoller`: Inner class only responsible for polling the outbox table
- `NotificationListener`: Only responsible for listening to PostgreSQL notifications

### Open/Closed Principle
The architecture is open for extension but closed for modification:
- Abstract base classes with template methods
- Strategy pattern for different queue implementations
- Extension points for custom message handling

### Liskov Substitution Principle
Subtypes are substitutable for their base types:
- All queue implementations adhere to the `MessageQueue` interface
- Message implementations are interchangeable

### Interface Segregation Principle
Interfaces are client-specific rather than general-purpose:
- Separate interfaces for producers and consumers
- Specialized interfaces for different message types if needed

### Dependency Inversion Principle
High-level modules do not depend on low-level modules:
- Dependency injection for database connections
- Use of interfaces for all components

## Thread Safety

### Database-Level Concurrency Control
- Row-level locking for queue operations
- Advisory locks for critical sections
- Serializable isolation level for critical transactions

### Application-Level Thread Safety
- Immutable message objects
- Thread-safe connection pool
- Atomic operations using Java's concurrency utilities
- Lock-free algorithms where possible

## Performance Considerations

- Batch operations for high-throughput scenarios
- Index optimization for queue tables
- Connection pooling configuration
- Tuning PostgreSQL for queue workloads

## License

This project is licensed under the MIT License - see the LICENSE file for details.
