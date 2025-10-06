# Spring Boot Bi-Temporal Event Store Example

## Overview

This example demonstrates basic bi-temporal event store patterns using PeeGeeQ in a Spring Boot application. It implements a financial audit system that tracks account transactions with complete historical accuracy.

## Business Scenario

**Financial Audit System** - Track account transactions with bi-temporal dimensions:
- **Valid Time**: When the transaction actually occurred in the real world
- **Transaction Time**: When the transaction was recorded in the system

This enables:
- Complete audit trail for regulatory compliance
- Historical point-in-time balance calculations
- Transaction corrections without losing history
- Real-time transaction monitoring

## Key Features

### 1. Append-Only Event Storage
- Store events with both valid time and transaction time
- Immutable event log - events are never deleted or modified
- Type-safe event handling with Jackson serialization
- Complete audit trail for regulatory compliance

### 2. Historical Point-in-Time Queries
- Query events as they were known at any point in time
- Reconstruct account balances at specific dates
- Support for regulatory reporting and audits
- Temporal range queries for analysis

### 3. Event Corrections Without Losing History
- Correct historical events while preserving original records
- Same valid time, different transaction time
- Complete audit trail of all corrections
- Regulatory compliance for financial corrections

### 4. Real-Time Event Subscriptions
- Subscribe to new events via PostgreSQL LISTEN/NOTIFY
- Real-time notifications for event processing
- Event-driven architecture patterns
- Integration with downstream systems

## Architecture

### Components

```
TransactionController (REST API)
    ↓
TransactionService (Business Logic)
    ↓
EventStore<TransactionEvent> (Bi-Temporal Storage)
    ↓
PostgreSQL (Database)
```

### Event Model

**TransactionEvent**:
- `transactionId`: Unique transaction identifier
- `accountId`: Account identifier
- `amount`: Transaction amount
- `type`: CREDIT or DEBIT
- `description`: Transaction description
- `reference`: External reference

### Bi-Temporal Dimensions

Each event is stored with:
- **Valid Time**: When the transaction occurred
- **Transaction Time**: When it was recorded
- **Event Type**: TransactionRecorded, TransactionCorrected
- **Payload**: TransactionEvent (serialized as JSONB)

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL 15+ (or use Docker)
- PeeGeeQ database schema initialized

### Running the Application

```bash
# Start PostgreSQL (if using Docker)
docker run -d \
  --name peegeeq-postgres \
  -e POSTGRES_DB=peegeeq \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  postgres:15-alpine

# Initialize database schema
cd peegeeq-migrations
mvn flyway:migrate -Plocal

# Start the application
cd ..
mvn spring-boot:run -pl peegeeq-examples \
  -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootbitemporal.SpringBootBitemporalApplication
```

The application will start on port **8083**.

## API Examples

### 1. Record a Transaction

```bash
curl -X POST http://localhost:8083/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "ACC-001",
    "amount": "1000.00",
    "type": "CREDIT",
    "description": "Initial deposit",
    "reference": "REF-001"
  }'
```

Response:
```json
{
  "eventId": "evt-123",
  "eventType": "TransactionRecorded",
  "validTime": "2025-10-06T10:00:00Z",
  "transactionTime": "2025-10-06T10:00:01Z",
  "payload": {
    "transactionId": "txn-456",
    "accountId": "ACC-001",
    "amount": 1000.00,
    "type": "CREDIT",
    "description": "Initial deposit",
    "reference": "REF-001"
  }
}
```

### 2. Query Account History

```bash
curl http://localhost:8083/api/accounts/ACC-001/history
```

Response:
```json
{
  "accountId": "ACC-001",
  "transactions": [
    {
      "eventId": "evt-123",
      "eventType": "TransactionRecorded",
      "validTime": "2025-10-06T10:00:00Z",
      "transactionTime": "2025-10-06T10:00:01Z",
      "payload": { ... }
    }
  ],
  "totalCount": 1
}
```

### 3. Calculate Point-in-Time Balance

```bash
# Current balance
curl http://localhost:8083/api/accounts/ACC-001/balance

# Balance as of specific date
curl "http://localhost:8083/api/accounts/ACC-001/balance?asOf=2025-10-01T12:00:00Z"
```

Response:
```json
1000.00
```

### 4. Correct a Transaction

```bash
curl -X POST http://localhost:8083/api/transactions/txn-456/correct \
  -H "Content-Type: application/json" \
  -d '{
    "correctedAmount": "1050.00",
    "reason": "Amount correction - bank error"
  }'
```

This creates a new event with:
- Same **valid time** as original transaction
- New **transaction time** (now)
- Corrected amount
- Audit trail preserved

### 5. View Transaction Versions

```bash
curl http://localhost:8083/api/transactions/txn-456/versions
```

Response shows all versions (original + corrections):
```json
[
  {
    "eventId": "evt-123",
    "eventType": "TransactionRecorded",
    "validTime": "2025-10-06T10:00:00Z",
    "transactionTime": "2025-10-06T10:00:01Z",
    "payload": { "amount": 1000.00, ... }
  },
  {
    "eventId": "evt-789",
    "eventType": "TransactionCorrected",
    "validTime": "2025-10-06T10:00:00Z",
    "transactionTime": "2025-10-06T11:00:00Z",
    "payload": { "amount": 1050.00, "description": "... [CORRECTED: Amount correction - bank error]", ... }
  }
]
```

## Use Cases

### Use Case 1: Daily Transaction Processing

```bash
# Record multiple transactions
curl -X POST http://localhost:8083/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"ACC-001","amount":"500.00","type":"DEBIT","description":"Payment"}'

curl -X POST http://localhost:8083/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"ACC-001","amount":"200.00","type":"CREDIT","description":"Refund"}'

# Check current balance
curl http://localhost:8083/api/accounts/ACC-001/balance
```

### Use Case 2: Historical Audit

```bash
# What was the balance on October 1st?
curl "http://localhost:8083/api/accounts/ACC-001/balance?asOf=2025-10-01T23:59:59Z"

# View all transactions for audit
curl http://localhost:8083/api/accounts/ACC-001/history
```

### Use Case 3: Error Correction

```bash
# Discover error in transaction amount
# Correct it while preserving audit trail
curl -X POST http://localhost:8083/api/transactions/txn-456/correct \
  -H "Content-Type: application/json" \
  -d '{"correctedAmount":"1050.00","reason":"Discovered data entry error"}'

# View correction history
curl http://localhost:8083/api/transactions/txn-456/versions
```

## Configuration

### Application Properties

```properties
# Server Configuration
server.port=8083

# Bi-Temporal Configuration
bitemporal.profile=development
bitemporal.enable-real-time-subscriptions=true
bitemporal.query-page-size=100
```

### PeeGeeQ Configuration

The application uses PeeGeeQ configuration from:
- `peegeeq-db/src/main/resources/peegeeq-development.properties`

## Testing

Run the integration tests:

```bash
mvn test -pl peegeeq-examples -Dtest=SpringBootBitemporalApplicationTest
```

## Regulatory Compliance

This example demonstrates patterns for:

- **SOX Compliance**: Complete audit trail for financial transactions
- **GDPR Compliance**: Data correction capabilities with history
- **Basel III**: Historical data for risk calculations
- **MiFID II**: Transaction reporting with temporal accuracy

## Next Steps

After understanding this basic example, explore:

1. **springboot-bitemporal-tx**: Multi-event store transactions
2. **springboot-integrated**: Outbox + Bi-temporal integration
3. **springboot2-bitemporal**: Reactive bi-temporal patterns

## Troubleshooting

### Database Connection Issues

Check PeeGeeQ configuration:
```bash
cat peegeeq-db/src/main/resources/peegeeq-development.properties
```

### Port Already in Use

Change the port in `application.properties`:
```properties
server.port=8084
```

### Event Store Initialization Fails

Ensure database schema is initialized:
```bash
cd peegeeq-migrations
mvn flyway:migrate -Plocal
```

## References

- [PeeGeeQ Documentation](../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md)
- [Bi-Temporal Event Store Patterns](../../../peegeeq-bitemporal/README.md)
- [Spring Boot Integration Guide](../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md)

