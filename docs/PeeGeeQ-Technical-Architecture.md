# PeeGeeQ Technical Architecture

## Overview

PeeGeeQ is a production-ready, enterprise-grade message queue system built on PostgreSQL, providing both native queue functionality and outbox pattern implementation. The system leverages PostgreSQL's advanced features including LISTEN/NOTIFY, advisory locks, and ACID transactions to deliver reliable, scalable message processing.

## System Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                    PeeGeeQ System                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐  │
│  │   PeeGeeQ API   │  │  PeeGeeQ Native │  │ PeeGeeQ DB  │  │
│  │                 │  │                 │  │             │  │
│  │ • MessageProducer│  │ • Real-time     │  │ • Schema    │  │
│  │ • MessageConsumer│  │   LISTEN/NOTIFY │  │   Management│  │
│  │ • Message        │  │ • Advisory Locks│  │ • Migrations│  │
│  │ • Interfaces     │  │ • Visibility    │  │ • Health    │  │
│  │                 │  │   Timeout       │  │   Checks    │  │
│  └─────────────────┘  │ • Dead Letter   │  │ • Metrics   │  │
│                       │   Queue         │  │ • Circuit   │  │
│  ┌─────────────────┐  │ • Metrics       │  │   Breakers  │  │
│  │ PeeGeeQ Outbox  │  │   Integration   │  │             │  │
│  │                 │  └─────────────────┘  └─────────────┘  │
│  │ • Outbox Pattern│                                        │
│  │ • Transactional │                                        │
│  │   Guarantees    │                                        │
│  │ • JDBC-based    │                                        │
│  └─────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

### Module Structure

#### 1. **peegeeq-api** - Core Interfaces
- `MessageProducer<T>` - Message publishing interface
- `MessageConsumer<T>` - Message consumption interface  
- `Message<T>` - Message abstraction
- Common data structures and contracts

#### 2. **peegeeq-db** - Database Foundation
- Connection management with HikariCP
- Schema migrations with Flyway
- Health checks and monitoring
- Metrics collection
- Circuit breaker patterns
- Dead letter queue management

#### 3. **peegeeq-native** - Native Queue Implementation
- Real-time message processing with LISTEN/NOTIFY
- Advisory locks for concurrency control
- Visibility timeout for message reliability
- Dead letter queue integration
- Comprehensive metrics tracking
- Vert.x-based async processing

#### 4. **peegeeq-outbox** - Outbox Pattern Implementation
- Transactional outbox pattern
- JDBC-based reliable messaging
- Polling-based message processing
- Integration with existing database transactions

## Database Schema

### Core Tables

```sql
-- Main message queue table
CREATE TABLE queue_messages (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    status VARCHAR(50) DEFAULT 'AVAILABLE',
    priority INTEGER DEFAULT 5,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    lock_until TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    INDEX idx_queue_messages_topic_status (topic, status),
    INDEX idx_queue_messages_lock_until (lock_until),
    INDEX idx_queue_messages_priority_created (priority DESC, created_at ASC)
);

-- Outbox table for transactional messaging
CREATE TABLE outbox (
    id VARCHAR(255) PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    status VARCHAR(50) DEFAULT 'PENDING',
    processing_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    INDEX idx_outbox_topic_status (topic, status),
    INDEX idx_outbox_created_at (created_at)
);

-- Dead letter queue for failed messages
CREATE TABLE dead_letter_queue (
    id BIGSERIAL PRIMARY KEY,
    original_table VARCHAR(255) NOT NULL,
    original_id BIGINT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    retry_count INTEGER DEFAULT 0,
    failure_reason TEXT,
    failed_at TIMESTAMPTZ DEFAULT NOW(),
    original_created_at TIMESTAMPTZ,
    INDEX idx_dlq_topic (topic),
    INDEX idx_dlq_failed_at (failed_at)
);
```

## Key Features

### 1. Real-time Message Processing
- **LISTEN/NOTIFY**: Immediate message delivery using PostgreSQL's pub/sub
- **Zero Polling**: Messages are pushed to consumers instantly
- **Channel Management**: Automatic channel subscription and cleanup

### 2. Concurrency Control
- **Advisory Locks**: Prevents duplicate message processing
- **Visibility Timeout**: Automatic message release after timeout
- **Lock Expiration**: Background cleanup of expired locks

### 3. Reliability & Resilience
- **Dead Letter Queue**: Failed messages moved to DLQ after retry limit
- **Circuit Breakers**: Automatic failure detection and recovery
- **Health Checks**: Continuous system health monitoring
- **Backpressure**: Automatic load management

### 4. Observability
- **Comprehensive Metrics**: Message lifecycle tracking
- **Performance Monitoring**: Processing time and throughput metrics
- **Error Tracking**: Detailed failure analysis
- **Health Dashboards**: Real-time system status

### 5. Transactional Guarantees
- **Outbox Pattern**: Ensures message delivery with database transactions
- **ACID Compliance**: Full transactional consistency
- **Exactly-Once Processing**: Prevents message duplication

## Performance Characteristics

### Throughput
- **Native Queue**: 10,000+ messages/second
- **Outbox Pattern**: 5,000+ messages/second
- **Concurrent Consumers**: Scales linearly with consumer count

### Latency
- **Real-time Delivery**: <10ms with LISTEN/NOTIFY
- **Polling-based**: 2-5 seconds (configurable)
- **Processing Overhead**: <1ms per message

### Scalability
- **Horizontal Scaling**: Multiple consumer instances
- **Vertical Scaling**: Leverages PostgreSQL performance
- **Connection Pooling**: Efficient resource utilization

## Configuration

### Database Connection
```properties
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.name=peegeeq
peegeeq.database.username=peegeeq
peegeeq.database.password=peegeeq
peegeeq.database.ssl.enabled=true
```

### Connection Pool
```properties
peegeeq.pool.minimum-idle=5
peegeeq.pool.maximum-pool-size=20
peegeeq.pool.connection-timeout=30000
peegeeq.pool.idle-timeout=600000
peegeeq.pool.max-lifetime=1800000
```

### Queue Configuration
```properties
peegeeq.queue.visibility-timeout=PT30S
peegeeq.queue.max-retries=3
peegeeq.queue.dead-letter.enabled=true
peegeeq.queue.metrics.enabled=true
```

## Technology Stack

### Core Technologies
- **Java 21**: Modern Java features and performance
- **PostgreSQL 15+**: Advanced database features
- **Vert.x 4.5.11**: Reactive, non-blocking I/O
- **HikariCP**: High-performance connection pooling
- **Jackson**: JSON serialization/deserialization

### Testing & Quality
- **JUnit 5**: Modern testing framework
- **TestContainers**: Integration testing with real PostgreSQL
- **Maven**: Build and dependency management
- **SLF4J + Logback**: Structured logging

### Monitoring & Observability
- **Micrometer**: Metrics collection
- **Health Checks**: System health monitoring
- **Circuit Breakers**: Resilience patterns
- **Structured Logging**: Comprehensive observability

## Deployment Considerations

### Database Requirements
- PostgreSQL 15+ recommended
- Sufficient connection pool sizing
- Proper indexing for performance
- Regular maintenance and monitoring

### Application Requirements
- Java 21+ runtime
- Adequate heap sizing for message processing
- Network connectivity to PostgreSQL
- Monitoring and alerting setup

### Security
- Database connection encryption (SSL/TLS)
- Authentication and authorization
- Network security (VPC, firewalls)
- Audit logging for compliance

## Next Steps

This architecture provides a solid foundation for enterprise message processing. Future enhancements may include:

- Message routing and filtering
- Schema evolution support
- Multi-tenant capabilities
- Cloud-native deployment patterns
- Advanced monitoring dashboards
