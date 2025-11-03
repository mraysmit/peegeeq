# Integrated Outbox + Bi-Temporal Pattern Guide

**Example**: `springboot-integrated`
**Use Case**: Middle Office Transaction Processing with Exception Handling
**Focus**: Multi-store coordination, CloudEvents format, ACID guarantees, exception management

---

## What This Guide Covers

This guide demonstrates how to combine PeeGeeQ's outbox pattern and bi-temporal event store in a single transaction for **middle office transaction processing**. This enables both immediate real-time processing (via outbox consumers) and complete audit trail (via bi-temporal JSONB queries) with comprehensive exception handling.

**Middle Office Responsibilities**:
- Trade confirmation and matching across multiple systems
- Position reconciliation between trading and settlement systems
- Exception detection and resolution workflows
- STP (Straight Through Processing) break management
- Cross-system data consistency validation
- Regulatory compliance and audit trail maintenance

**Key Patterns**:
- Single transaction coordination across three operations (database + outbox + event store)
- CloudEvents v1.0 format for standardized events with rich metadata
- Exception handling with automated retry and escalation workflows
- JSONB storage with advanced querying capabilities for operational analysis
- Complete ACID guarantees across all storage layers
- Middle office domain patterns (trade matching, position reconciliation, exception management)

**Core Integration Challenge**:
How to ensure that database updates, real-time event publishing, and historical event storage all succeed together or fail together, using modern CloudEvents format for maximum interoperability.

---

## Core Concepts

### The Integration Challenge

**Problem**: How to ensure consistency across three operations in financial trade processing?

1. **Database Save** - Store trade in `trades` table
2. **Outbox Send** - Send CloudEvent to `outbox` table for immediate settlement processing
3. **Event Store Append** - Append CloudEvent to bi-temporal event store for regulatory audit trail

**Requirements**:
- All three succeed together OR all three fail together
- No partial updates (critical for financial data integrity)
- No lost events (regulatory compliance requirement)
- Complete consistency across all storage layers
- CloudEvents v1.0 format for interoperability

**Solution**: Single database transaction using `ConnectionProvider.withTransaction()` with CloudEvents.

### Single Transaction Pattern

**Key Principle**: Pass the SAME `SqlConnection` to all three operations using CloudEvents format.

```java
connectionProvider.withTransaction("client-id", connection -> {
    // 1. Save trade to database (connection)
    // 2. Send CloudEvent to outbox (connection)
    // 3. Append CloudEvent to bi-temporal store (connection)
    // All use SAME connection = SAME transaction
    // All use SAME CloudEvent = consistent data
});
```

**How It Works**:
1. `withTransaction()` begins database transaction
2. Creates `SqlConnection` for this transaction
3. Create CloudEvent with trade data and metadata
4. Pass same connection to all three operations
5. Pass same CloudEvent to outbox and event store
6. If any fails → rollback ALL (including database save)
7. If all succeed → commit ALL atomically

### Dual-Purpose CloudEvents

**Same CloudEvent, Two Purposes**:

**Purpose 1: Real-Time Processing** (Outbox)
- Immediate downstream processing via consumers
- Settlement instructions sent to custodian systems
- Position updates sent to risk management systems
- Trade confirmations sent to front office systems
- NAV calculations triggered for fund management

**Purpose 2: Historical Audit Trail** (Bi-Temporal)
- Complete regulatory audit trail with valid time and transaction time
- Point-in-time trade reconstruction for compliance inquiries
- MiFID II, EMIR, SOX regulatory reporting
- Historical position analysis and P&L attribution
- Late trade confirmation tracking and NAV impact analysis

**CloudEvents Benefits**:
- Standardized event format (CloudEvents v1.0 specification)
- Rich metadata with correlation IDs and causation chains
- JSONB storage enabling powerful PostgreSQL queries
- Interoperability with external systems and cloud platforms
- Extension attributes for financial domain context

---

## Implementation

### Configuration

**Bean Setup with CloudEvents Support**:
```java
@Configuration
public class IntegratedFinancialConfig {

    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        return new PgDatabaseService(manager);
    }

    @Bean
    public ObjectMapper cloudEventObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public QueueFactory queueFactory(DatabaseService databaseService) {
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        return provider.createFactory("outbox", databaseService);
    }

    @Bean
    public OutboxProducer<CloudEvent> tradeEventProducer(QueueFactory factory) {
        return (OutboxProducer<CloudEvent>) factory.createProducer("trade-events");
    }

    @Bean
    public EventStore<CloudEvent> tradeEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("trade_events", CloudEvent.class, databaseService);
    }

    @Bean
    public FinancialCloudEventBuilder cloudEventBuilder(ObjectMapper objectMapper) {
        return new FinancialCloudEventBuilder(objectMapper);
    }
}
```

### CloudEvent Builder

**Financial Domain CloudEvent Builder**:
```java
@Component
public class FinancialCloudEventBuilder {

    private final ObjectMapper objectMapper;

    public FinancialCloudEventBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> CloudEvent buildTradeEvent(
            String eventType,
            T payload,
            String tradeId,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {

        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(eventType)
            .withSource(URI.create("https://trading.financialcorp.com"))
            .withSubject(tradeId)
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(objectMapper.writeValueAsBytes(payload))
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId)
            .withExtension("validtime", validTime.toString())
            .withExtension("bookingsystem", "TradingSystem")
            .withExtension("businessdomain", "EQUITIES")
            .build();
    }
}
```

### Service Implementation

**Complete Pattern with CloudEvents**:
```java
@Service
public class TradeService {

    private final DatabaseService databaseService;
    private final TradeRepository tradeRepository;
    private final OutboxProducer<CloudEvent> tradeEventProducer;
    private final EventStore<CloudEvent> tradeEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<String> captureEquityTrade(CaptureTradeRequest request) {
        String tradeId = UUID.randomUUID().toString();
        String correlationId = "TRD-" + UUID.randomUUID().toString().substring(0, 8);
        Instant tradeTime = request.getTradeTime();

        // Create domain objects
        Trade trade = new Trade(
            tradeId,
            request.getSymbol(),
            request.getQuantity(),
            request.getPrice(),
            request.getCounterparty(),
            TradeStatus.CAPTURED,
            tradeTime
        );

        TradeEvent tradeEvent = new TradeEvent(
            tradeId,
            request.getSymbol(),
            request.getQuantity(),
            request.getPrice(),
            request.getCounterparty(),
            "BUY",
            TradeStatus.CAPTURED,
            tradeTime
        );

        // Get connection provider
        ConnectionProvider cp = databaseService.getConnectionProvider();

        // Execute all operations in SINGLE transaction
        return cp.withTransaction("peegeeq-main", connection -> {

            // Step 1: Save trade to database
            return tradeRepository.save(trade, connection)

                .compose(v -> {
                    try {
                        // Create CloudEvent
                        CloudEvent cloudEvent = cloudEventBuilder.buildTradeEvent(
                            "trading.equities.captured.v1",
                            tradeEvent,
                            tradeId,
                            correlationId,
                            null, // No causation for initial capture
                            tradeTime
                        );

                        // Step 2: Send CloudEvent to outbox (for immediate processing)
                        return Future.fromCompletionStage(
                            tradeEventProducer.sendInTransaction(cloudEvent, connection)
                        ).compose(v2 -> {
                            // Step 3: Append CloudEvent to bi-temporal store (for audit trail)
                            return Future.fromCompletionStage(
                                tradeEventStore.appendInTransaction(
                                    "TradeCaptured",   // Event type
                                    cloudEvent,        // CloudEvent payload
                                    tradeTime,         // Valid time (when trade occurred)
                                    connection         // SAME connection
                                )
                            );
                        });

                    } catch (JsonProcessingException e) {
                        return Future.failedFuture(e);
                    }
                })

                // Return trade ID
                .map(v -> tradeId);

        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Key Points**:
1. **Single Transaction** - `withTransaction()` creates one transaction
2. **Same Connection** - Pass `connection` to all three operations
3. **Same CloudEvent** - Use identical CloudEvent for outbox and event store
4. **Composition** - Use `.compose()` to chain operations sequentially
5. **ACID Guarantees** - All succeed or all fail together

### Repository Implementation

**Database Save**:
```java
@Repository
public class TradeRepository {

    public Future<Void> save(Trade trade, SqlConnection connection) {
        String sql = "INSERT INTO trades (id, symbol, quantity, price, counterparty, status, trade_time) " +
                    "VALUES ($1, $2, $3, $4, $5, $6, $7)";

        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                trade.getId(),
                trade.getSymbol(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getCounterparty(),
                trade.getStatus().name(),
                trade.getTradeTime()
            ))
            .map(result -> null);
    }
}
```

**Important**: Accept `SqlConnection` parameter, don't create your own connection.

### Outbox Producer

**Send CloudEvent in Transaction**:
```java
// ✅ CORRECT - use sendInTransaction() with CloudEvent
tradeEventProducer.sendInTransaction(cloudEvent, connection)

// ❌ WRONG - creates separate transaction
tradeEventProducer.send(cloudEvent)
```

**API**:
```java
public interface OutboxProducer<CloudEvent> {
    // Separate transaction (don't use for integrated pattern)
    CompletableFuture<Void> send(CloudEvent payload);

    // Same transaction (use this!)
    CompletableFuture<Void> sendInTransaction(CloudEvent payload, SqlConnection connection);
}
```

### Event Store

**Append CloudEvent in Transaction**:
```java
// ✅ CORRECT - use appendInTransaction() with CloudEvent
tradeEventStore.appendInTransaction(
    "TradeCaptured",
    cloudEvent,        // Same CloudEvent as sent to outbox
    tradeTime,         // Valid time (when trade occurred)
    connection         // SAME connection
)

// ❌ WRONG - creates separate transaction
tradeEventStore.append("TradeCaptured", cloudEvent, tradeTime)
```

**API**:
```java
public interface EventStore<CloudEvent> {
    // Separate transaction (don't use for integrated pattern)
    CompletableFuture<BiTemporalEvent<CloudEvent>> append(
        String eventType, CloudEvent payload, Instant validTime);

    // Same transaction (use this!)
    CompletableFuture<BiTemporalEvent<CloudEvent>> appendInTransaction(
        String eventType, CloudEvent payload, Instant validTime, SqlConnection connection);
}
```

---

## Transaction Flow

### Success Path

**Sequence**:
```
1. BEGIN TRANSACTION
2. INSERT INTO trades (...)           ✅ Trade saved
3. INSERT INTO outbox (CloudEvent)    ✅ CloudEvent queued for consumers
4. INSERT INTO trade_events (CloudEvent) ✅ CloudEvent stored for audit
5. COMMIT TRANSACTION                 ✅ All committed atomically
```

**Result**:
- Trade saved in database ✅
- CloudEvent in outbox (consumers will process immediately) ✅
- CloudEvent in bi-temporal store (regulatory audit trail) ✅
- All three operations committed together ✅
- Same CloudEvent data in both outbox and event store ✅

### Failure Path

**Scenario**: Bi-temporal event store append fails.

**Sequence**:
```
1. BEGIN TRANSACTION
2. INSERT INTO trades (...)           ✅ Success
3. INSERT INTO outbox (CloudEvent)    ✅ Success
4. INSERT INTO trade_events (CloudEvent) ❌ FAILS (constraint violation)
5. ROLLBACK TRANSACTION               ✅ All rolled back
```

**Result**:
- Trade NOT saved (rolled back) ✅
- CloudEvent NOT in outbox (rolled back) ✅
- CloudEvent NOT in event store (failed) ✅
- Complete consistency maintained ✅
- No downstream processing triggered ✅

**No Partial Updates**: Either all three succeed or none succeed.

### Exception Handling

**Transaction Rollback with Audit**:
```java
return cp.withTransaction("peegeeq-main", connection -> {
    // All operations...
}).toCompletionStage().toCompletableFuture()
.exceptionally(throwable -> {
    // Log the failure for operational monitoring
    log.error("Trade capture failed - all operations rolled back", throwable);

    // Record the failure attempt for audit purposes
    recordTradeFailure(tradeId, correlationId, throwable);

    // Return failure result
    throw new TradeProcessingException("Trade capture failed: " + throwable.getMessage());
});
```

---

## Advanced Querying Patterns

### Database Queries

**Get Trade by ID**:
```java
public CompletableFuture<Trade> getTrade(String tradeId) {
    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            String sql = "SELECT * FROM trades WHERE id = $1";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(tradeId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("Trade not found");
                    }
                    Row row = rows.iterator().next();
                    return new Trade(
                        row.getString("id"),
                        row.getString("symbol"),
                        row.getBigDecimal("quantity"),
                        row.getBigDecimal("price"),
                        row.getString("counterparty"),
                        TradeStatus.valueOf(row.getString("status")),
                        row.getOffsetDateTime("trade_time").toInstant()
                    );
                });
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### CloudEvents JSONB Queries

**Query by CloudEvent Type**:
```java
// Find all trade capture events using JSONB operators
String sql = "SELECT event_id, payload->>'type' as event_type, payload->>'subject' as trade_id " +
            "FROM trade_events " +
            "WHERE payload->>'type' = $1 " +
            "ORDER BY transaction_time";

// Execute: payload->>'type' = 'trading.equities.captured.v1'
```

**Query by Correlation ID**:
```java
// Find all events for a specific trade using correlation ID
String sql = "SELECT event_id, payload->>'type' as event_type, " +
            "payload->>'correlationid' as correlation_id " +
            "FROM trade_events " +
            "WHERE payload->>'correlationid' = $1 " +
            "ORDER BY valid_time";

// Execute: payload->>'correlationid' = 'TRD-12345'
```

**Query by Extension Attributes**:
```java
// Find all trades from specific booking system
String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
            "payload->>'bookingsystem' as booking_system " +
            "FROM trade_events " +
            "WHERE payload->>'bookingsystem' = $1 " +
            "AND payload->>'businessdomain' = $2 " +
            "ORDER BY transaction_time";

// Execute: bookingsystem = 'TradingSystem', businessdomain = 'EQUITIES'
```

### Bi-Temporal CloudEvent Queries

**Get Trade History**:
```java
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getTradeHistory(String tradeId) {
    return tradeEventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> {
                CloudEvent ce = event.getPayload();
                return tradeId.equals(ce.getSubject());
            })
            .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
            .collect(Collectors.toList())
        );
}
```

**Point-in-Time Query**:
```java
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getTradesAsOfTime(Instant validTime) {
    return tradeEventStore.query(EventQuery.asOfValidTime(validTime));
}
```

**Query by Symbol and Status**:
```java
// Complex JSONB query on CloudEvent data payload
String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
            "payload->'data'->>'symbol' as symbol, " +
            "payload->'data'->>'status' as status " +
            "FROM trade_events " +
            "WHERE payload->'data'->>'symbol' = $1 " +
            "AND payload->'data'->>'status' = $2 " +
            "ORDER BY valid_time";

// Execute: symbol = 'AAPL', status = 'CAPTURED'
```

---

## Event Definition

### Trade Event Data Model

**Financial Trade Event**:
```java
public class TradeEvent {

    private final String tradeId;
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final String counterparty;
    private final String side;
    private final TradeStatus status;
    private final Instant tradeTime;

    @JsonCreator
    public TradeEvent(
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("side") String side,
            @JsonProperty("status") TradeStatus status,
            @JsonProperty("tradeTime") Instant tradeTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.counterparty = counterparty;
        this.side = side;
        this.status = status;
        this.tradeTime = tradeTime;
    }

    // Getters...

    public enum TradeStatus {
        CAPTURED, AFFIRMED, SETTLED, CANCELLED, FAILED
    }
}
```

### CloudEvent Usage

**CloudEvent Wraps Trade Data**:
- **CloudEvent Metadata**: type, source, subject, time, extensions
- **CloudEvent Data**: TradeEvent serialized as JSON
- **Bi-Temporal Dimensions**: valid time (trade time) + transaction time (system time)
- **Financial Extensions**: correlationid, bookingsystem, businessdomain

**Used By**:
- Outbox producer (sends CloudEvent to consumers)
- Event store (stores CloudEvent for audit trail)
- Consumers (process CloudEvent from outbox)
- Query services (retrieve CloudEvent from event store)
- Regulatory reporting (extract data from CloudEvent JSONB)

---

## Database Schema

### Trades Table

```sql
CREATE TABLE IF NOT EXISTS trades (
    id VARCHAR(255) PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    quantity DECIMAL(19, 4) NOT NULL,
    price DECIMAL(19, 4) NOT NULL,
    counterparty VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    trade_time TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trades_symbol ON trades(symbol);
CREATE INDEX idx_trades_counterparty ON trades(counterparty);
CREATE INDEX idx_trades_trade_time ON trades(trade_time);
CREATE INDEX idx_trades_status ON trades(status);
```

### Outbox Table (CloudEvents)

```sql
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,  -- CloudEvent stored as JSONB
    headers JSONB,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_outbox_queue_name ON outbox(queue_name);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);

-- CloudEvent-specific indexes for outbox
CREATE INDEX idx_outbox_cloudevent_type ON outbox
USING GIN ((payload->>'type'));

CREATE INDEX idx_outbox_cloudevent_subject ON outbox
USING GIN ((payload->>'subject'));
```

### Bi-Temporal Event Store Table (CloudEvents)

```sql
CREATE TABLE IF NOT EXISTS trade_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,  -- CloudEvent stored as JSONB
    valid_time TIMESTAMPTZ NOT NULL,
    transaction_time TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trade_events_event_type ON trade_events(event_type);
CREATE INDEX idx_trade_events_valid_time ON trade_events(valid_time);
CREATE INDEX idx_trade_events_transaction_time ON trade_events(transaction_time);

-- CloudEvent-specific JSONB indexes for powerful querying
CREATE INDEX idx_trade_events_cloudevent_type ON trade_events
USING GIN ((payload->>'type'));

CREATE INDEX idx_trade_events_cloudevent_subject ON trade_events
USING GIN ((payload->>'subject'));

CREATE INDEX idx_trade_events_correlation_id ON trade_events
USING GIN ((payload->>'correlationid'));

CREATE INDEX idx_trade_events_booking_system ON trade_events
USING GIN ((payload->>'bookingsystem'));

-- Composite index for common financial queries
CREATE INDEX idx_trade_events_symbol_status ON trade_events
USING GIN ((payload->'data'->>'symbol'), (payload->'data'->>'status'));
```

---

## Real-World Middle Office Use Cases

### 1. Trade Confirmation and Matching

**Scenario**: Middle office receives trade confirmations from multiple counterparties and must match them against internal trade records with exception handling.

**Business Requirements**:
- Trade confirmations processed with ACID guarantees across multiple stores
- Immediate exception detection and processing:
  - Price discrepancy detection and escalation
  - Quantity mismatch identification and resolution
  - Counterparty confirmation status tracking
  - STP break management and workflow routing
- Complete audit trail:
  - Exception detection and resolution history
  - Trade matching lifecycle tracking
  - Regulatory compliance for trade reporting
  - Position reconciliation audit trail

**Implementation with Exception Handling**:
```java
@Service
public class TradeConfirmationService {

    private final EventStore<CloudEvent> tradeEventStore;
    private final EventStore<CloudEvent> exceptionEventStore;
    private final OutboxProducer<CloudEvent> tradeEventProducer;

    public CompletableFuture<TradeConfirmationResult> processTradeConfirmation(
            TradeConfirmationRequest request) {

        String confirmationId = UUID.randomUUID().toString();
        String correlationId = request.getOriginalTradeId();
        Instant confirmationTime = Instant.now();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            // Step 1: Validate confirmation against original trade
            return validateTradeConfirmation(request, connection)

                .compose(validationResult -> {
                    if (validationResult.hasExceptions()) {
                        // Handle exceptions in same transaction
                        return processTradeExceptions(request, validationResult,
                            correlationId, confirmationTime, connection);
                    } else {
                        // Process successful confirmation
                        return processSuccessfulConfirmation(request, correlationId,
                            confirmationTime, connection);
                    }
                })

                .map(result -> new TradeConfirmationResult(
                    confirmationId, correlationId, result.getStatus(), result.getExceptions()
                ));

        }).toCompletionStage().toCompletableFuture();
    }

    private CompletableFuture<ProcessingResult> processTradeExceptions(
            TradeConfirmationRequest request, ValidationResult validationResult,
            String correlationId, Instant confirmationTime, SqlConnection connection) {

        List<CompletableFuture<Void>> exceptionProcessing = new ArrayList<>();

        for (TradeException exception : validationResult.getExceptions()) {

            // Create exception event data
            TransactionExceptionEvent exceptionEvent = new TransactionExceptionEvent(
                UUID.randomUUID().toString(),
                request.getOriginalTradeId(),
                correlationId,
                exception.getType().name(),  // PRICE_MISMATCH, QUANTITY_DISCREPANCY, etc.
                exception.getSeverity().name(),
                exception.getDescription(),
                "TRADE_CONFIRMATION",
                exception.getOriginalValue(),
                exception.getExpectedValue(),
                "COUNTERPARTY_SYSTEM",
                "middle.office.team",
                "DETECTED",
                null,
                confirmationTime,
                null,
                exception.getAdditionalData()
            );

            try {
                // Create CloudEvent for exception
                CloudEvent exceptionCloudEvent = cloudEventBuilder.buildTradeEvent(
                    "middleoffice.exception." + exception.getType().name().toLowerCase() + ".v1",
                    exceptionEvent,
                    request.getOriginalTradeId(),
                    correlationId,
                    request.getOriginalEventId(),
                    confirmationTime
                );

                // Process exception: outbox + event store in same transaction
                CompletableFuture<Void> exceptionFuture = Future.fromCompletionStage(
                    tradeEventProducer.sendInTransaction(exceptionCloudEvent, connection)
                ).compose(v -> Future.fromCompletionStage(
                    exceptionEventStore.appendInTransaction(
                        "TradeExceptionDetected",
                        exceptionCloudEvent,
                        confirmationTime,
                        connection
                    )
                )).toCompletionStage().toCompletableFuture();

                exceptionProcessing.add(exceptionFuture);

            } catch (JsonProcessingException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // Wait for all exception processing to complete
        return CompletableFuture.allOf(exceptionProcessing.toArray(new CompletableFuture[0]))
            .thenApply(v -> new ProcessingResult("EXCEPTIONS_DETECTED", validationResult.getExceptions()));
    }
}
```

### 2. Position Reconciliation with Exception Management

**Scenario**: Middle office performs end-of-day position reconciliation between trading system and settlement system, detecting and resolving discrepancies.

**Business Challenge**:
- Position differences detected between systems at 6:00 PM reconciliation
- Multiple potential causes: late trades, settlement failures, system timing issues
- Need automated exception detection and workflow routing
- Regulatory requirement for same-day resolution of material discrepancies

**Implementation**:
```java
@Service
public class PositionReconciliationService {

    private final EventStore<CloudEvent> positionEventStore;
    private final EventStore<CloudEvent> exceptionEventStore;
    private final OutboxProducer<CloudEvent> reconciliationEventProducer;

    public CompletableFuture<ReconciliationResult> performPositionReconciliation(
            ReconciliationRequest request) {

        String reconciliationId = UUID.randomUUID().toString();
        String correlationId = "RECON-" + UUID.randomUUID().toString().substring(0, 8);
        Instant reconciliationTime = Instant.now();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            // Step 1: Compare positions between systems
            return comparePositions(request, connection)

                .compose(comparisonResult -> {
                    if (comparisonResult.hasDiscrepancies()) {
                        // Process discrepancies with exception handling
                        return processPositionDiscrepancies(request, comparisonResult,
                            correlationId, reconciliationTime, connection);
                    } else {
                        // Record successful reconciliation
                        return recordSuccessfulReconciliation(request, correlationId,
                            reconciliationTime, connection);
                    }
                })

                .map(result -> new ReconciliationResult(
                    reconciliationId, correlationId, result.getStatus(), result.getDiscrepancies()
                ));

        }).toCompletionStage().toCompletableFuture();
    }

    private CompletableFuture<ProcessingResult> processPositionDiscrepancies(
            ReconciliationRequest request, ComparisonResult comparisonResult,
            String correlationId, Instant reconciliationTime, SqlConnection connection) {

        List<CompletableFuture<Void>> discrepancyProcessing = new ArrayList<>();

        for (PositionDiscrepancy discrepancy : comparisonResult.getDiscrepancies()) {

            // Determine exception type and severity
            String exceptionType = determineExceptionType(discrepancy);
            String severity = determineSeverity(discrepancy);

            // Create exception event data
            PositionReconciliationException exceptionEvent = new PositionReconciliationException(
                UUID.randomUUID().toString(),
                request.getAccountId(),
                correlationId,
                exceptionType,
                severity,
                discrepancy.getDescription(),
                "POSITION_RECONCILIATION",
                discrepancy.getTradingSystemPosition().toString(),
                discrepancy.getSettlementSystemPosition().toString(),
                "RECONCILIATION_ENGINE",
                "middle.office.team",
                "DETECTED",
                null,
                reconciliationTime,
                null,
                discrepancy.getAdditionalData()
            );

            try {
                // Create CloudEvent for discrepancy
                CloudEvent discrepancyCloudEvent = cloudEventBuilder.buildTradeEvent(
                    "middleoffice.reconciliation.discrepancy." + exceptionType.toLowerCase() + ".v1",
                    exceptionEvent,
                    request.getAccountId(),
                    correlationId,
                    null,
                    reconciliationTime
                );

                // Process discrepancy: outbox + event store in same transaction
                CompletableFuture<Void> discrepancyFuture = Future.fromCompletionStage(
                    reconciliationEventProducer.sendInTransaction(discrepancyCloudEvent, connection)
                ).compose(v -> Future.fromCompletionStage(
                    exceptionEventStore.appendInTransaction(
                        "PositionDiscrepancyDetected",
                        discrepancyCloudEvent,
                        reconciliationTime,
                        connection
                    )
                )).toCompletionStage().toCompletableFuture();

                discrepancyProcessing.add(discrepancyFuture);

            } catch (JsonProcessingException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // Wait for all discrepancy processing to complete
        return CompletableFuture.allOf(discrepancyProcessing.toArray(new CompletableFuture[0]))
            .thenApply(v -> new ProcessingResult("DISCREPANCIES_DETECTED", comparisonResult.getDiscrepancies()));
    }
}
```

**Downstream Processing** (Exception Management Consumers):
```java
// Consumer 1: Exception Workflow Router
@Service
public class ExceptionWorkflowConsumer {

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        exceptionEventConsumer.subscribe(this::routeException);
    }

    private CompletableFuture<Void> routeException(Message<CloudEvent> message) {
        CloudEvent cloudEvent = message.getPayload();

        try {
            // Extract exception data from CloudEvent
            if (cloudEvent.getType().startsWith("middleoffice.exception.")) {
                TransactionExceptionEvent exception = objectMapper.readValue(
                    cloudEvent.getData().toBytes(), TransactionExceptionEvent.class);

                // Route based on exception type and severity
                return routeExceptionToWorkflow(exception, cloudEvent);

            } else if (cloudEvent.getType().startsWith("middleoffice.reconciliation.discrepancy.")) {
                PositionReconciliationException discrepancy = objectMapper.readValue(
                    cloudEvent.getData().toBytes(), PositionReconciliationException.class);

                // Route discrepancy to appropriate resolution team
                return routeDiscrepancyToTeam(discrepancy, cloudEvent);
            }

            return CompletableFuture.completedFuture(null);

        } catch (IOException e) {
            throw new RuntimeException("Failed to process exception event", e);
        }
    }

    private CompletableFuture<Void> routeExceptionToWorkflow(
            TransactionExceptionEvent exception, CloudEvent cloudEvent) {

        // Determine routing based on exception type and severity
        if ("CRITICAL".equals(exception.getSeverity())) {
            // Immediate escalation for critical exceptions
            return escalationService.escalateImmediately(exception, cloudEvent.getExtension("correlationid"));

        } else if (exception.getExceptionType().startsWith("PRICE_")) {
            // Route price-related exceptions to pricing team
            return workflowService.assignToTeam("pricing.team", exception, cloudEvent);

        } else if (exception.getExceptionType().startsWith("POSITION_")) {
            // Route position-related exceptions to operations team
            return workflowService.assignToTeam("operations.team", exception, cloudEvent);

        } else {
            // Default routing to middle office team
            return workflowService.assignToTeam("middle.office.team", exception, cloudEvent);
        }
    }
}

// Consumer 2: Automated Exception Resolution
@Service
public class AutomatedResolutionConsumer {

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        exceptionEventConsumer.subscribe(this::attemptAutomatedResolution);
    }

    private CompletableFuture<Void> attemptAutomatedResolution(Message<CloudEvent> message) {
        CloudEvent cloudEvent = message.getPayload();

        try {
            // Only attempt automated resolution for specific exception types
            if (cloudEvent.getType().contains("price_mismatch") ||
                cloudEvent.getType().contains("quantity_discrepancy")) {

                TransactionExceptionEvent exception = objectMapper.readValue(
                    cloudEvent.getData().toBytes(), TransactionExceptionEvent.class);

                // Attempt automated resolution (e.g., re-fetch counterparty confirmation)
                return automatedResolutionService.attemptResolution(
                    exception, cloudEvent.getExtension("correlationid"))
                    .thenApply(v -> null);
            }

            return CompletableFuture.completedFuture(null);

        } catch (IOException e) {
            throw new RuntimeException("Failed to process exception for automated resolution", e);
        }
    }
}
```

### 3. STP Break Management

**Scenario**: Automated straight-through processing fails and requires middle office intervention with complete exception tracking.

**Business Requirements**:
- STP failure detected in real-time during settlement processing
- Exception must be categorized and routed to appropriate team
- Automated retry attempts with exponential backoff
- Complete audit trail for operational risk management
- SLA tracking for resolution times

**Implementation**:
```java
@Service
public class STPBreakManagementService {

    private final EventStore<CloudEvent> stpEventStore;
    private final EventStore<CloudEvent> exceptionEventStore;
    private final OutboxProducer<CloudEvent> stpEventProducer;

    public CompletableFuture<STPBreakResult> handleSTPBreak(STPBreakRequest request) {

        String breakId = UUID.randomUUID().toString();
        String correlationId = request.getOriginalTransactionId();
        Instant breakTime = Instant.now();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            // Step 1: Record STP break detection
            return recordSTPBreakDetection(request, breakId, correlationId, breakTime, connection)

                .compose(detectionResult -> {
                    // Step 2: Determine if automated retry is possible
                    if (request.isRetryable() && request.getRetryCount() < 3) {
                        return scheduleAutomatedRetry(request, breakId, correlationId, breakTime, connection);
                    } else {
                        // Route to manual intervention
                        return routeToManualIntervention(request, breakId, correlationId, breakTime, connection);
                    }
                })

                .map(result -> new STPBreakResult(
                    breakId, correlationId, result.getStatus(), result.getAssignedTeam()
                ));

        }).toCompletionStage().toCompletableFuture();
    }

    private CompletableFuture<ProcessingResult> recordSTPBreakDetection(
            STPBreakRequest request, String breakId, String correlationId,
            Instant breakTime, SqlConnection connection) {

        // Create STP break event data
        STPBreakEvent breakEvent = new STPBreakEvent(
            breakId,
            request.getOriginalTransactionId(),
            correlationId,
            request.getBreakType().name(),  // SETTLEMENT_FAILED, CONFIRMATION_TIMEOUT, etc.
            request.getSeverity().name(),
            request.getDescription(),
            "STP_PROCESSING",
            request.getExpectedValue(),
            request.getActualValue(),
            "SETTLEMENT_SYSTEM",
            "stp.operations.team",
            "DETECTED",
            null,
            breakTime,
            null,
            request.getAdditionalData()
        );

        try {
            // Create CloudEvent for STP break
            CloudEvent breakCloudEvent = cloudEventBuilder.buildTradeEvent(
                "middleoffice.stp.break." + request.getBreakType().name().toLowerCase() + ".v1",
                breakEvent,
                request.getOriginalTransactionId(),
                correlationId,
                request.getOriginalEventId(),
                breakTime
            );

            // Record STP break: outbox + event store in same transaction
            return Future.fromCompletionStage(
                stpEventProducer.sendInTransaction(breakCloudEvent, connection)
            ).compose(v -> Future.fromCompletionStage(
                exceptionEventStore.appendInTransaction(
                    "STPBreakDetected",
                    breakCloudEvent,
                    breakTime,
                    connection
                )
            )).thenApply(v -> new ProcessingResult("STP_BREAK_RECORDED", null))
            .toCompletionStage().toCompletableFuture();

        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<ProcessingResult> scheduleAutomatedRetry(
            STPBreakRequest request, String breakId, String correlationId,
            Instant breakTime, SqlConnection connection) {

        // Calculate retry delay (exponential backoff)
        long retryDelayMinutes = (long) Math.pow(2, request.getRetryCount()) * 5; // 5, 10, 20 minutes
        Instant retryTime = breakTime.plus(retryDelayMinutes, ChronoUnit.MINUTES);

        // Create retry event data
        STPRetryEvent retryEvent = new STPRetryEvent(
            UUID.randomUUID().toString(),
            breakId,
            request.getOriginalTransactionId(),
            correlationId,
            request.getRetryCount() + 1,
            retryTime,
            "AUTOMATED_RETRY",
            "Scheduled automated retry attempt #" + (request.getRetryCount() + 1)
        );

        try {
            // Create CloudEvent for retry scheduling
            CloudEvent retryCloudEvent = cloudEventBuilder.buildTradeEvent(
                "middleoffice.stp.retry.scheduled.v1",
                retryEvent,
                request.getOriginalTransactionId(),
                correlationId,
                breakId,
                retryTime  // Valid time = when retry should occur
            );

            // Schedule retry: outbox + event store in same transaction
            return Future.fromCompletionStage(
                stpEventProducer.sendInTransaction(retryCloudEvent, connection)
            ).compose(v -> Future.fromCompletionStage(
                stpEventStore.appendInTransaction(
                    "STPRetryScheduled",
                    retryCloudEvent,
                    retryTime,  // Valid time = when retry should happen
                    connection
                )
            )).thenApply(v -> new ProcessingResult("AUTOMATED_RETRY_SCHEDULED", "stp.automation.service"))
            .toCompletionStage().toCompletableFuture();

        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
```

**Operational Queries** (Middle Office Analytics):
```java
// Exception analysis for operational reporting
public CompletableFuture<List<ExceptionSummary>> getExceptionSummaryForDate(LocalDate reportDate) {
    Instant dayStart = reportDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = reportDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    // Use direct SQL with JSONB operators for exception analysis
    String sql = """
        SELECT
            payload->>'exceptionType' as exception_type,
            payload->>'severity' as severity,
            payload->>'assignedTo' as assigned_to,
            payload->>'status' as status,
            COUNT(*) as exception_count,
            AVG(EXTRACT(EPOCH FROM (
                COALESCE((payload->>'resolvedTime')::timestamptz, NOW()) -
                (payload->>'detectedTime')::timestamptz
            )) / 3600) as avg_resolution_hours
        FROM exception_events
        WHERE payload->>'type' LIKE 'middleoffice.exception.%'
        AND valid_time >= $1 AND valid_time < $2
        GROUP BY exception_type, severity, assigned_to, status
        ORDER BY exception_count DESC
        """;

    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(dayStart, dayEnd))
                .map(rows -> {
                    List<ExceptionSummary> summaries = new ArrayList<>();
                    for (Row row : rows) {
                        summaries.add(new ExceptionSummary(
                            row.getString("exception_type"),
                            row.getString("severity"),
                            row.getString("assigned_to"),
                            row.getString("status"),
                            row.getInteger("exception_count"),
                            row.getDouble("avg_resolution_hours")
                        ));
                    }
                    return summaries;
                });
        })
        .toCompletionStage()
        .toCompletableFuture();
}

// STP break analysis for process improvement
public CompletableFuture<List<STPBreakAnalysis>> analyzeSTPBreaksForPeriod(
        LocalDate startDate, LocalDate endDate) {

    Instant periodStart = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant periodEnd = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    String sql = """
        SELECT
            payload->>'breakType' as break_type,
            payload->>'systemSource' as system_source,
            COUNT(*) as break_count,
            COUNT(CASE WHEN payload->>'status' = 'RESOLVED' THEN 1 END) as resolved_count,
            AVG(CASE
                WHEN payload->>'resolvedTime' IS NOT NULL THEN
                    EXTRACT(EPOCH FROM (
                        (payload->>'resolvedTime')::timestamptz -
                        (payload->>'detectedTime')::timestamptz
                    )) / 3600
                END) as avg_resolution_hours,
            COUNT(CASE WHEN payload->'data'->>'retryCount' = '0' THEN 1 END) as first_attempt_failures
        FROM stp_events
        WHERE payload->>'type' LIKE 'middleoffice.stp.break.%'
        AND valid_time >= $1 AND valid_time < $2
        GROUP BY break_type, system_source
        ORDER BY break_count DESC
        """;

    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(periodStart, periodEnd))
                .map(rows -> {
                    List<STPBreakAnalysis> analyses = new ArrayList<>();
                    for (Row row : rows) {
                        analyses.add(new STPBreakAnalysis(
                            row.getString("break_type"),
                            row.getString("system_source"),
                            row.getInteger("break_count"),
                            row.getInteger("resolved_count"),
                            row.getDouble("avg_resolution_hours"),
                            row.getInteger("first_attempt_failures")
                        ));
                    }
                    return analyses;
                });
        })
        .toCompletionStage()
        .toCompletableFuture();
}

// Position reconciliation discrepancy tracking
public CompletableFuture<List<ReconciliationDiscrepancy>> getUnresolvedDiscrepancies() {

    String sql = """
        SELECT
            payload->>'subject' as account_id,
            payload->>'correlationid' as correlation_id,
            payload->'data'->>'exceptionType' as discrepancy_type,
            payload->'data'->>'originalValue' as trading_position,
            payload->'data'->>'expectedValue' as settlement_position,
            payload->'data'->>'detectedTime' as detected_time,
            payload->'data'->>'assignedTo' as assigned_to
        FROM exception_events
        WHERE payload->>'type' LIKE 'middleoffice.reconciliation.discrepancy.%'
        AND payload->'data'->>'status' IN ('DETECTED', 'IN_PROGRESS')
        ORDER BY (payload->'data'->>'detectedTime')::timestamptz DESC
        """;

    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            return connection.preparedQuery(sql)
                .execute()
                .map(rows -> {
                    List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
                    for (Row row : rows) {
                        discrepancies.add(new ReconciliationDiscrepancy(
                            row.getString("account_id"),
                            row.getString("correlation_id"),
                            row.getString("discrepancy_type"),
                            new BigDecimal(row.getString("trading_position")),
                            new BigDecimal(row.getString("settlement_position")),
                            row.getOffsetDateTime("detected_time").toInstant(),
                            row.getString("assigned_to")
                        ));
                    }
                    return discrepancies;
                });
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

---

## Best Practices

### 1. Always Use Same Connection

**Why**: Ensures all operations participate in same transaction.

```java
// ✅ CORRECT - Single transaction with CloudEvents
connectionProvider.withTransaction("client-id", connection -> {
    return save(trade, connection)
        .compose(v -> sendInTransaction(cloudEvent, connection))
        .compose(v -> appendInTransaction(cloudEvent, validTime, connection));
});

// ❌ WRONG - separate transactions (no consistency guarantees)
save(trade);
send(cloudEvent);
append(cloudEvent, validTime);
```

### 2. Use InTransaction Methods

**Why**: Participates in existing transaction instead of creating new one.

```java
// ✅ CORRECT - participates in transaction
producer.sendInTransaction(cloudEvent, connection)
eventStore.appendInTransaction("TradeCaptured", cloudEvent, validTime, connection)

// ❌ WRONG - creates separate transactions
producer.send(cloudEvent)
eventStore.append("TradeCaptured", cloudEvent, validTime)
```

### 3. Compose Operations Sequentially

**Why**: Chains operations in same transaction, maintains order.

```java
// ✅ CORRECT - sequential composition
return save(trade, connection)
    .compose(v -> sendInTransaction(cloudEvent, connection))
    .compose(v -> appendInTransaction(cloudEvent, validTime, connection));

// ❌ WRONG - parallel execution (race conditions, partial failures)
CompletableFuture.allOf(
    save(trade, connection),
    sendInTransaction(cloudEvent, connection),
    appendInTransaction(cloudEvent, validTime, connection)
);
```

### 4. Use Same CloudEvent

**Why**: Ensures identical data in outbox and event store.

```java
// ✅ CORRECT - same CloudEvent instance
CloudEvent cloudEvent = cloudEventBuilder.buildTradeEvent(...);
producer.sendInTransaction(cloudEvent, connection);
eventStore.appendInTransaction("TradeCaptured", cloudEvent, validTime, connection);

// ❌ WRONG - different event instances (potential data inconsistency)
CloudEvent outboxEvent = cloudEventBuilder.buildTradeEvent(...);
CloudEvent storeEvent = cloudEventBuilder.buildTradeEvent(...);
```

### 5. Handle Errors with Audit Trail

**Why**: Ensures rollback on failure and maintains audit trail.

```java
return connectionProvider.withTransaction("client-id", connection -> {
    return save(trade, connection)
        .compose(v -> sendInTransaction(cloudEvent, connection))
        .compose(v -> appendInTransaction(cloudEvent, validTime, connection));
})
.toCompletionStage()
.toCompletableFuture()
.exceptionally(throwable -> {
    // Log the failure for operational monitoring
    log.error("Trade processing failed - all operations rolled back", throwable);

    // Record failure for audit (separate transaction)
    recordTradeFailure(tradeId, correlationId, throwable);

    // Return failure result
    throw new TradeProcessingException("Trade processing failed: " + throwable.getMessage());
});
```

### 6. Use Proper Valid Time

**Why**: Ensures correct bi-temporal semantics for financial events.

```java
// ✅ CORRECT - use business time as valid time
Instant tradeTime = request.getTradeTime();        // When trade actually occurred
Instant confirmationTime = Instant.now();          // When we're processing it

// For initial trade capture
eventStore.appendInTransaction("TradeCaptured", cloudEvent, tradeTime, connection);

// For late confirmation (valid time = original trade time)
eventStore.appendInTransaction("LateTradeConfirmed", cloudEvent, tradeTime, connection);

// ❌ WRONG - using system time as valid time
eventStore.appendInTransaction("TradeCaptured", cloudEvent, Instant.now(), connection);
```

### 7. Leverage CloudEvents Extensions

**Why**: Provides rich metadata for correlation, causation, and domain context.

```java
// ✅ CORRECT - rich CloudEvent metadata
CloudEvent cloudEvent = CloudEventBuilder.v1()
    .withType("trading.equities.captured.v1")
    .withSubject(tradeId)
    .withExtension("correlationid", correlationId)
    .withExtension("causationid", causationId)
    .withExtension("bookingsystem", "TradingSystem")
    .withExtension("businessdomain", "EQUITIES")
    .withExtension("counterparty", trade.getCounterparty())
    .withData(objectMapper.writeValueAsBytes(tradeEvent))
    .build();

// ❌ WRONG - minimal metadata (harder to query and correlate)
CloudEvent cloudEvent = CloudEventBuilder.v1()
    .withType("trade")
    .withData(objectMapper.writeValueAsBytes(tradeEvent))
    .build();
```

---

## Summary

**Key Takeaways**:

1. **Single Transaction Coordination** - Use `withTransaction()` for database + outbox + event store
2. **Same Connection Pattern** - Pass identical `SqlConnection` to all three operations
3. **CloudEvents Format** - Use CloudEvents v1.0 for standardized event structure
4. **Same CloudEvent Instance** - Use identical CloudEvent for outbox and event store
5. **InTransaction Methods** - Use `sendInTransaction()` and `appendInTransaction()`
6. **Sequential Composition** - Chain operations with `.compose()` for proper ordering
7. **ACID Guarantees** - All three operations succeed together or fail together
8. **Bi-Temporal Semantics** - Use business time as valid time, system time as transaction time
9. **Rich Metadata** - Leverage CloudEvents extensions for correlation and domain context
10. **JSONB Querying** - Use PostgreSQL JSONB operators for powerful event analysis

**Business Benefits**:
- **Financial Data Integrity** - No partial updates in trade processing
- **Regulatory Compliance** - Complete audit trail with bi-temporal tracking
- **Real-Time Processing** - Immediate downstream processing via outbox consumers
- **Historical Analysis** - Point-in-time queries and position reconstruction
- **Interoperability** - CloudEvents standard for cross-system integration
- **Operational Visibility** - Rich event metadata for monitoring and troubleshooting

**When to Use This Pattern**:
- Middle office transaction processing requiring exception management
- Multi-system trade confirmation and matching workflows
- Position reconciliation with automated discrepancy detection
- STP break management with automated retry and escalation
- Regulatory environments requiring complete audit trails
- High-consistency requirements where partial updates are unacceptable

**Production Considerations**:
- Monitor transaction duration to avoid long-running transactions
- Use connection pooling for high-throughput exception processing
- Implement proper error handling and alerting for operational teams
- Consider JSONB indexing strategy for exception query performance
- Plan for CloudEvents schema evolution and versioning
- Set up SLA monitoring for exception resolution times
- Implement automated escalation for critical exceptions

