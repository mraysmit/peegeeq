# CloudEvents Integration in PeeGeeQ

## Overview

PeeGeeQ provides comprehensive built-in support for CloudEvents v1.0 specification through automatic Jackson module registration. This integration enables standardized event formats across all PeeGeeQ components, with particular strength in bi-temporal event stores where CloudEvents combine with PostgreSQL's JSONB capabilities for powerful querying.

**Key Benefits**:
- **Standardized Event Format**: CloudEvents v1.0 specification compliance
- **JSONB Storage**: CloudEvents stored as JSONB in PostgreSQL for efficient querying
- **Bi-Temporal Support**: CloudEvents with valid time and transaction time dimensions
- **Financial Use Cases**: Trade lifecycle, settlement processing, regulatory compliance
- **Advanced Querying**: PostgreSQL JSONB operators for complex event queries

## What's Included

### Automatic CloudEvents Support

All PeeGeeQ ObjectMapper instances now automatically include CloudEvents Jackson module support when the CloudEvents library is available on the classpath:

- **PeeGeeQManager**: Core ObjectMapper with CloudEvents support
- **OutboxFactory**: Outbox pattern with CloudEvents serialization
- **BiTemporalEventStoreFactory**: Bi-temporal event store with CloudEvents support
- **PgNativeQueueFactory**: Native queue with CloudEvents support
- **PeeGeeQRestServer**: REST API with CloudEvents support

### Dependencies

The CloudEvents dependencies are included in the examples module:

```xml
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-core</artifactId>
    <version>4.0.1</version>
</dependency>
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-json-jackson</artifactId>
    <version>4.0.1</version>
</dependency>
```

## Usage Examples

### Basic CloudEvent Creation and Serialization

```java
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;

// Create a CloudEvent
CloudEvent event = CloudEventBuilder.v1()
    .withId("order-123")
    .withType("com.example.order.created.v1")
    .withSource(URI.create("https://example.com/orders"))
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData("{\"orderId\": \"123\", \"amount\": 100.50}".getBytes())
    .withExtension("correlationid", "workflow-456")
    .build();

// PeeGeeQ's ObjectMapper automatically supports CloudEvents
PeeGeeQManager manager = new PeeGeeQManager(config);
ObjectMapper mapper = manager.getObjectMapper();

// Serialize CloudEvent
String json = mapper.writeValueAsString(event);

// Deserialize CloudEvent
CloudEvent deserializedEvent = mapper.readValue(json, CloudEvent.class);
```

### Using CloudEvents with Outbox Pattern

```java
// Create outbox factory - automatically includes CloudEvents support
OutboxFactory outboxFactory = new OutboxFactory(databaseService);
MessageProducer<CloudEvent> producer = outboxFactory.createProducer("events", CloudEvent.class);

// Send CloudEvent through outbox
CloudEvent event = CloudEventBuilder.v1()
    .withId("trade-789")
    .withType("com.trading.trade.executed.v1")
    .withSource(URI.create("https://trading.example.com"))
    .withData(tradeData)
    .build();

producer.send(event).get();
```

### Using CloudEvents with Bi-Temporal Event Store

```java
// Create bi-temporal event store with CloudEvents support
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
EventStore<CloudEvent> eventStore = factory.createEventStore(CloudEvent.class);

// Store CloudEvent with temporal information
CloudEvent event = CloudEventBuilder.v1()
    .withId("settlement-456")
    .withType("com.settlement.instruction.created.v1")
    .withSource(URI.create("https://settlement.example.com"))
    .withData(settlementData)
    .withExtension("validtime", validTime.toString())
    .build();

eventStore.append("SettlementCreated", event, validTime);
```

### Financial Trade Lifecycle Example

**Real-World Scenario**: Back office trade processing with CloudEvents

```java
// Trade execution event
TradeLifecycleData tradeData = new TradeLifecycleData(
    "TRD-001", "AAPL", "BUY",
    new BigDecimal("100"), new BigDecimal("150.50"), "USD",
    "Goldman Sachs", "john.trader", "equity-trading",
    "NEW", "2025-10-17", "Murex", "DTCC"
);

CloudEvent tradeEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withType("backoffice.trade.new.v1")
    .withSource(URI.create("https://backoffice.example.com/trade-capture"))
    .withSubject("TRD-001")  // Trade ID for correlation
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData(mapper.writeValueAsBytes(tradeData))
    .withExtension("correlationid", "TRD-001")
    .withExtension("causationid", "execution-001")
    .withExtension("bookingsystem", "Murex")
    .withExtension("clearinghouse", "DTCC")
    .build();

// Store with valid time = trade execution time
eventStore.append("TradeExecuted", tradeEvent, tradeExecutionTime);
```

**Trade Affirmation Event**:
```java
// Update trade status to AFFIRMED
TradeLifecycleData affirmedData = new TradeLifecycleData(
    "TRD-001", "AAPL", "BUY",
    new BigDecimal("100"), new BigDecimal("150.50"), "USD",
    "Goldman Sachs", "john.trader", "equity-trading",
    "AFFIRMED", "2025-10-17", "Murex", "DTCC"  // Status changed
);

CloudEvent affirmationEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withType("backoffice.trade.affirmed.v1")
    .withSource(URI.create("https://backoffice.example.com/affirmation"))
    .withSubject("TRD-001")
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData(mapper.writeValueAsBytes(affirmedData))
    .withExtension("correlationid", "TRD-001")  // Same correlation ID
    .withExtension("causationid", "affirmation-001")
    .withExtension("bookingsystem", "Murex")
    .withExtension("clearinghouse", "DTCC")
    .build();

// Store with valid time = affirmation time
eventStore.append("TradeAffirmed", affirmationEvent, affirmationTime);
```

### Financial Fabric Example

The `springbootfinancialfabric` example demonstrates comprehensive CloudEvents usage:

```java
@Bean
public ObjectMapper cloudEventObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
    return mapper;
}

@Component
public class FinancialCloudEventBuilder {
    
    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {
        
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(eventType)
            .withSource(URI.create("https://financial.example.com"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(objectMapper.writeValueAsBytes(payload))
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId)
            .withExtension("validtime", validTime.toString())
            .build();
    }
}
```

## Implementation Details

### Automatic Module Registration

PeeGeeQ automatically detects and registers the CloudEvents Jackson module when available:

```java
private static ObjectMapper createDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    
    // Add CloudEvents Jackson module support if available on classpath
    try {
        Class<?> jsonFormatClass = Class.forName("io.cloudevents.jackson.JsonFormat");
        Object cloudEventModule = jsonFormatClass.getMethod("getCloudEventJacksonModule").invoke(null);
        if (cloudEventModule instanceof com.fasterxml.jackson.databind.Module) {
            mapper.registerModule((com.fasterxml.jackson.databind.Module) cloudEventModule);
            logger.debug("CloudEvents Jackson module registered successfully");
        }
    } catch (Exception e) {
        logger.debug("CloudEvents Jackson module not available on classpath, skipping registration: {}", e.getMessage());
    }
    
    return mapper;
}
```

### Graceful Degradation

If CloudEvents dependencies are not available on the classpath, PeeGeeQ continues to work normally without CloudEvents support. The integration is designed to be optional and non-breaking.

---

## Domain-Specific Query Patterns

### Aggregate ID Prefixing Strategy

**The Challenge**: PeeGeeQ uses a shared table with JSONB serialization for all event types. Querying without proper filtering causes:
- Cross-type deserialization attempts
- Performance degradation
- Warning log spam
- Scalability problems

**The Solution**: Use aggregate ID prefixing to separate event streams by domain:

```java
// ✅ CORRECT - Prefix aggregate IDs by domain
String accountAggregateId = "account:" + accountId;
String tradeAggregateId = "trade:" + tradeId;
String fundAggregateId = "fund:" + fundId;
String settlementAggregateId = "settlement:" + instructionId;

// Query by aggregate ID (efficient)
eventStore.query(EventQuery.forAggregate(tradeAggregateId))
```

**Benefits**:
- Separate event streams by domain
- Efficient querying (no cross-type deserialization)
- Clear domain boundaries
- Scalable as data grows

### Financial Domain Patterns

#### Fund Management Queries
```java
// Get all events for a specific fund
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getFundEvents(String fundId) {
    return eventStore.query(EventQuery.forAggregate("fund:" + fundId));
}

// Get late trade confirmations for NAV correction
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getLateTradeConfirmations(
        String fundId, LocalDate tradingDay) {

    Instant dayStart = tradingDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant navCutoff = tradingDay.atTime(18, 0).toInstant(ZoneOffset.UTC);
    Instant dayEnd = tradingDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.query(EventQuery.forAggregate("fund:" + fundId))
        .thenApply(events -> events.stream()
            // Valid time = during trading day
            .filter(e -> !e.getValidTime().isBefore(dayStart))
            .filter(e -> e.getValidTime().isBefore(dayEnd))
            // Transaction time = after NAV cutoff
            .filter(e -> e.getTransactionTime().isAfter(navCutoff))
            .collect(Collectors.toList())
        );
}
```

#### Trade Lifecycle Queries
```java
// Get complete trade lifecycle using correlation ID
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getTradeLifecycle(String tradeId) {
    return eventStore.query(EventQuery.forAggregate("trade:" + tradeId))
        .thenApply(events -> events.stream()
            .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
            .collect(Collectors.toList())
        );
}

// Get trades by booking system
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getTradesByBookingSystem(
        String bookingSystem) {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> {
                CloudEvent ce = e.getPayload();
                return bookingSystem.equals(ce.getExtension("bookingsystem"));
            })
            .collect(Collectors.toList())
        );
}
```

#### Settlement Processing Queries
```java
// Get settlement instructions by custodian
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getSettlementsByCustodian(
        String custodian) {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> {
                CloudEvent ce = e.getPayload();
                return ce.getType().startsWith("settlement.instruction.") &&
                       custodian.equals(ce.getExtension("custodian"));
            })
            .collect(Collectors.toList())
        );
}

// Get failed settlements for retry processing
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getFailedSettlements() {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> {
                CloudEvent ce = e.getPayload();
                return "settlement.instruction.failed.v1".equals(ce.getType());
            })
            .collect(Collectors.toList())
        );
}
```

### Anti-Pattern: EventQuery.all()

```java
// ❌ WRONG - queries ALL events, attempts to deserialize everything
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getAllEvents() {
    return eventStore.query(EventQuery.all());  // Performance killer!
}

// ✅ CORRECT - query by aggregate or event type
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getAccountEvents(String accountId) {
    return eventStore.query(EventQuery.forAggregate("account:" + accountId));
}

// ✅ CORRECT - query by event type
public CompletableFuture<List<BiTemporalEvent<CloudEvent>>> getTradeEvents() {
    return eventStore.query(EventQuery.forEventType("TradeExecuted"));
}
```

---

## Advanced JSONB Querying with CloudEvents

### PostgreSQL JSONB Operators

CloudEvents stored in PeeGeeQ's bi-temporal event store are serialized as JSONB, enabling powerful PostgreSQL queries:

#### Query by CloudEvent Type
```sql
-- Find all trade execution events
SELECT event_id, payload->>'type' as event_type, payload->>'subject' as trade_id
FROM bitemporal_event_log
WHERE payload->>'type' = 'backoffice.trade.new.v1'
ORDER BY transaction_time;
```

#### Query by Extension Attributes
```sql
-- Find all events for a specific trade using correlationid
SELECT event_id, payload->>'type' as event_type,
       payload->>'correlationid' as correlation_id
FROM bitemporal_event_log
WHERE payload->>'correlationid' = 'TRD-001'
ORDER BY valid_time;
```

#### Query by Data Payload Fields
```sql
-- Find all trades with notional amount > 100,000
SELECT event_id, payload->>'subject' as trade_id,
       (payload->'data'->>'notionalAmount')::numeric as notional
FROM bitemporal_event_log
WHERE (payload->'data'->>'notionalAmount')::numeric > 100000
ORDER BY (payload->'data'->>'notionalAmount')::numeric DESC;
```

#### Combine JSONB with Bi-Temporal Queries
```sql
-- Find all DTCC trades that occurred before cutoff time
SELECT event_id, payload->>'subject' as trade_id,
       payload->>'clearinghouse' as clearing_house,
       valid_time
FROM bitemporal_event_log
WHERE payload->>'clearinghouse' = 'DTCC'
AND valid_time < '2025-10-15T18:00:00Z'
ORDER BY valid_time;
```

### Trade Lifecycle Reconstruction

**Complete Trade History**:
```sql
-- Reconstruct complete trade lifecycle using correlationid
SELECT event_id,
       payload->>'type' as event_type,
       payload->>'subject' as trade_id,
       payload->'data'->>'status' as status,
       payload->'data'->>'notionalAmount' as notional,
       valid_time, transaction_time
FROM bitemporal_event_log
WHERE payload->>'correlationid' = 'TRD-001'
ORDER BY valid_time ASC;
```

**Result**:
```
Stage 1: backoffice.trade.new.v1 - Status: NEW - Valid Time: 2025-10-15T09:30:00Z
Stage 2: backoffice.trade.affirmed.v1 - Status: AFFIRMED - Valid Time: 2025-10-15T10:15:00Z
Stage 3: backoffice.trade.settled.v1 - Status: SETTLED - Valid Time: 2025-10-17T16:00:00Z
```

### Multi-System Queries

**Cross-System Analysis**:
```sql
-- Find all trades processed through Murex OR cleared through DTCC
SELECT event_id,
       payload->>'subject' as trade_id,
       payload->>'bookingsystem' as booking_system,
       payload->>'clearinghouse' as clearing_house,
       payload->'data'->>'status' as status
FROM bitemporal_event_log
WHERE (payload->>'bookingsystem' = 'Murex'
OR payload->>'clearinghouse' = 'DTCC')
AND payload->>'type' = 'backoffice.trade.new.v1'
ORDER BY transaction_time;
```

---

## Benefits

1. **Standardized Event Format**: CloudEvents v1.0 specification for consistent event structure
2. **Powerful Querying**: PostgreSQL JSONB operators for complex event analysis
3. **Bi-Temporal Compliance**: Valid time and transaction time for regulatory requirements
4. **Financial Domain Support**: Trade lifecycle, settlement, corporate actions
5. **Interoperability**: CloudEvents widely supported across cloud platforms
6. **Rich Metadata**: Built-in support for extensions, correlation IDs, causation chains
7. **Type Safety**: Full Jackson serialization/deserialization support
8. **Performance**: JSONB indexing for efficient queries on large event volumes

---

## Real-World Use Cases

### 1. Late Trade Confirmations

**Scenario**: Fund management late trade processing with NAV impact

**Timeline**:
- **10:30 AM** - Trade executed (Valid Time)
- **6:00 PM** - NAV cut-off time
- **8:00 PM** - Trade confirmed (Transaction Time) ⚠️ **LATE**
- **10:00 PM** - NAV calculated (without late trade)
- **Next day 9:00 AM** - NAV corrected to include late trade

```java
// Late trade confirmation CloudEvent
TradeLifecycleData lateTradeData = new TradeLifecycleData(
    "TRD-LATE-001", "AAPL", "BUY",
    new BigDecimal("1000"), new BigDecimal("150.50"), "USD",
    "Goldman Sachs", "john.trader", "equity-trading",
    "CONFIRMED", "2025-10-17", "Murex", "DTCC"
);

CloudEvent lateTradeEvent = CloudEventBuilder.v1()
    .withType("funds.trade.late.confirmed.v1")
    .withSubject("TRD-LATE-001")
    .withSource(URI.create("https://funds.example.com/trade-confirmation"))
    .withExtension("correlationid", "TRD-LATE-001")
    .withExtension("fundId", "FUND-ABC-001")
    .withExtension("navCutoffMissed", "true")
    .withExtension("navCutoffTime", "18:00:00Z")
    .withExtension("impactType", "NAV_CORRECTION_REQUIRED")
    .withData(mapper.writeValueAsBytes(lateTradeData))
    .build();

// Valid time = 10:30 AM (when trade executed)
// Transaction time = 8:00 PM (when confirmed - after cutoff)
Instant tradeExecutionTime = LocalDateTime.of(2025, 10, 15, 10, 30)
    .toInstant(ZoneOffset.UTC);
eventStore.append("LateTradeConfirmed", lateTradeEvent, tradeExecutionTime);
```

**Query Late Trade Confirmations**:
```sql
-- Find all late trade confirmations for NAV correction
SELECT payload->>'subject' as trade_id,
       payload->>'fundId' as fund_id,
       payload->'data'->>'notionalAmount' as notional,
       payload->>'navCutoffMissed' as missed_cutoff,
       valid_time as execution_time,
       transaction_time as confirmation_time,
       (transaction_time::time > '18:00:00'::time) as after_cutoff
FROM bitemporal_event_log
WHERE payload->>'type' = 'funds.trade.late.confirmed.v1'
AND payload->>'navCutoffMissed' = 'true'
AND valid_time::date = '2025-10-15'  -- Trading day
ORDER BY transaction_time;
```

**NAV Correction Query**:
```sql
-- Get trades for NAV recalculation (as-of original NAV time vs corrected)
WITH nav_cutoff AS (
    SELECT '2025-10-15T18:00:00Z'::timestamptz as cutoff_time
)
SELECT
    'ORIGINAL_NAV' as calculation_type,
    payload->>'fundId' as fund_id,
    COUNT(*) as trade_count,
    SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional
FROM bitemporal_event_log, nav_cutoff
WHERE payload->>'type' LIKE 'funds.trade.%'
AND payload->>'fundId' = 'FUND-ABC-001'
AND valid_time::date = '2025-10-15'
AND transaction_time <= nav_cutoff.cutoff_time  -- Known at NAV time

UNION ALL

SELECT
    'CORRECTED_NAV' as calculation_type,
    payload->>'fundId' as fund_id,
    COUNT(*) as trade_count,
    SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional
FROM bitemporal_event_log
WHERE payload->>'type' LIKE 'funds.trade.%'
AND payload->>'fundId' = 'FUND-ABC-001'
AND valid_time::date = '2025-10-15'  -- All trades that actually happened
ORDER BY calculation_type;
```

### 2. Corporate Actions Processing

**Scenario**: Dividend payment with backdated ex-date and position adjustments

**Timeline**:
- **2025-10-01** - Ex-dividend date (Valid Time)
- **2025-10-15** - Payment date
- **2025-10-20** - We learn about dividend (Transaction Time)
- **2025-10-21** - Historical positions adjusted

```java
// Corporate action data model
public class CorporateActionData {
    public String securityId;
    public String actionType;  // DIVIDEND, SPLIT, MERGER, SPINOFF
    public BigDecimal dividendPerShare;
    public LocalDate exDate;
    public LocalDate paymentDate;
    public String currency;
    public String announcementDate;
    public String recordDate;
}

// Dividend announcement CloudEvent
CorporateActionData dividendData = new CorporateActionData(
    "AAPL", "DIVIDEND", new BigDecimal("0.25"),
    LocalDate.of(2025, 10, 1),   // Ex-date
    LocalDate.of(2025, 10, 15),  // Payment date
    "USD", "2025-09-28", "2025-10-02"
);

CloudEvent corporateActionEvent = CloudEventBuilder.v1()
    .withType("custody.corporate.action.dividend.v1")
    .withSubject("AAPL-DIV-Q4-2025")
    .withSource(URI.create("https://custody.example.com/corporate-actions"))
    .withExtension("correlationid", "CA-DIV-AAPL-001")
    .withExtension("securityId", "AAPL")
    .withExtension("actionType", "DIVIDEND")
    .withExtension("exDate", "2025-10-01")
    .withExtension("paymentDate", "2025-10-15")
    .withExtension("impactType", "POSITION_ADJUSTMENT")
    .withExtension("backdated", "true")
    .withData(mapper.writeValueAsBytes(dividendData))
    .build();

// Valid time = ex-dividend date (when it actually happened)
// Transaction time = when we learned about it (today)
Instant exDate = LocalDate.of(2025, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
eventStore.append("CorporateActionRecorded", corporateActionEvent, exDate);
```

**Position Adjustment Event**:
```java
// Position adjustment for dividend
PositionAdjustmentData adjustmentData = new PositionAdjustmentData(
    "FUND-ABC-001", "AAPL", new BigDecimal("1000"),  // 1000 shares
    new BigDecimal("250.00"),  // 1000 * $0.25 dividend
    "DIVIDEND_PAYMENT", "CA-DIV-AAPL-001"
);

CloudEvent adjustmentEvent = CloudEventBuilder.v1()
    .withType("custody.position.adjustment.dividend.v1")
    .withSubject("FUND-ABC-001-AAPL")
    .withExtension("correlationid", "CA-DIV-AAPL-001")  // Links to corporate action
    .withExtension("causationid", "CA-DIV-AAPL-001")
    .withExtension("fundId", "FUND-ABC-001")
    .withExtension("securityId", "AAPL")
    .withExtension("adjustmentType", "DIVIDEND_PAYMENT")
    .withData(mapper.writeValueAsBytes(adjustmentData))
    .build();

// Valid time = ex-dividend date (when adjustment should have been made)
// Transaction time = when we processed the adjustment
eventStore.append("PositionAdjusted", adjustmentEvent, exDate);
```

**Corporate Action Queries**:
```sql
-- Find all corporate actions for a security with backdated impact
SELECT payload->>'subject' as action_id,
       payload->>'securityId' as security,
       payload->>'actionType' as action_type,
       payload->>'exDate' as ex_date,
       payload->>'backdated' as is_backdated,
       valid_time as effective_date,
       transaction_time as processing_date,
       (transaction_time::date - valid_time::date) as days_late
FROM bitemporal_event_log
WHERE payload->>'type' = 'custody.corporate.action.dividend.v1'
AND payload->>'securityId' = 'AAPL'
AND payload->>'backdated' = 'true'
ORDER BY valid_time DESC;
```

**Position Impact Analysis**:
```sql
-- Analyze position adjustments from corporate actions
SELECT payload->>'fundId' as fund_id,
       payload->>'securityId' as security,
       payload->>'adjustmentType' as adjustment_type,
       (payload->'data'->>'adjustmentAmount')::numeric as amount,
       payload->>'correlationid' as corporate_action_id,
       valid_time as effective_date,
       transaction_time as processed_date
FROM bitemporal_event_log
WHERE payload->>'type' = 'custody.position.adjustment.dividend.v1'
AND payload->>'correlationid' = 'CA-DIV-AAPL-001'
ORDER BY valid_time;
```

### 3. Settlement Processing

**Scenario**: Multi-stage settlement lifecycle with failure recovery

**Settlement Lifecycle**: SUBMITTED → MATCHED → CONFIRMED → SETTLED (or FAILED)

```java
// Settlement instruction data model
public class SettlementInstructionData {
    public String instructionId;
    public String tradeId;
    public String counterparty;
    public BigDecimal amount;
    public String currency;
    public LocalDate settlementDate;
    public String status;  // SUBMITTED, MATCHED, CONFIRMED, SETTLED, FAILED
    public String failureReason;
    public String custodian;
    public String clearingHouse;
}

// 1. Settlement instruction submission
SettlementInstructionData instructionData = new SettlementInstructionData(
    "SSI-12345", "TRD-001", "CUSTODIAN-XYZ",
    new BigDecimal("1000000.00"), "USD",
    LocalDate.of(2025, 10, 17), "SUBMITTED", null, "DTCC", "LCH"
);

CloudEvent instructionEvent = CloudEventBuilder.v1()
    .withType("settlement.instruction.submitted.v1")
    .withSubject("SSI-12345")
    .withSource(URI.create("https://settlement.example.com/instructions"))
    .withExtension("correlationid", "SSI-12345")
    .withExtension("tradeId", "TRD-001")
    .withExtension("custodian", "DTCC")
    .withExtension("clearingHouse", "LCH")
    .withExtension("settlementDate", "2025-10-17")
    .withData(mapper.writeValueAsBytes(instructionData))
    .build();

// Valid time = when instruction should be effective
// Transaction time = when submitted
Instant instructionTime = LocalDateTime.of(2025, 10, 15, 9, 0)
    .toInstant(ZoneOffset.UTC);
eventStore.append("SettlementInstructionSubmitted", instructionEvent, instructionTime);

// 2. Settlement matching
SettlementInstructionData matchedData = new SettlementInstructionData(
    "SSI-12345", "TRD-001", "CUSTODIAN-XYZ",
    new BigDecimal("1000000.00"), "USD",
    LocalDate.of(2025, 10, 17), "MATCHED", null, "DTCC", "LCH"
);

CloudEvent matchedEvent = CloudEventBuilder.v1()
    .withType("settlement.instruction.matched.v1")
    .withSubject("SSI-12345")
    .withExtension("correlationid", "SSI-12345")
    .withExtension("causationid", "SSI-12345")  // Links to original instruction
    .withExtension("matchingTime", Instant.now().toString())
    .withData(mapper.writeValueAsBytes(matchedData))
    .build();

// 3. Settlement failure and retry
SettlementInstructionData failedData = new SettlementInstructionData(
    "SSI-12345", "TRD-001", "CUSTODIAN-XYZ",
    new BigDecimal("1000000.00"), "USD",
    LocalDate.of(2025, 10, 17), "FAILED", "INSUFFICIENT_FUNDS", "DTCC", "LCH"
);

CloudEvent failureEvent = CloudEventBuilder.v1()
    .withType("settlement.instruction.failed.v1")
    .withSubject("SSI-12345")
    .withExtension("correlationid", "SSI-12345")
    .withExtension("causationid", "SSI-12345")
    .withExtension("failureReason", "INSUFFICIENT_FUNDS")
    .withExtension("retryScheduled", "true")
    .withExtension("nextRetryTime", "2025-10-18T09:00:00Z")
    .withData(mapper.writeValueAsBytes(failedData))
    .build();
```

**Settlement Status Queries**:
```sql
-- Get current settlement status for all instructions
SELECT DISTINCT ON (payload->>'correlationid')
       payload->>'correlationid' as instruction_id,
       payload->>'type' as latest_event_type,
       payload->'data'->>'status' as current_status,
       payload->'data'->>'failureReason' as failure_reason,
       payload->>'custodian' as custodian,
       valid_time as effective_time,
       transaction_time as processed_time
FROM bitemporal_event_log
WHERE payload->>'type' LIKE 'settlement.instruction.%'
ORDER BY payload->>'correlationid', valid_time DESC, transaction_time DESC;
```

---

## Regulatory Compliance and Audit Patterns

### Financial Services Compliance

**Regulatory Requirements Supported**:
- **MiFID II**: Prove what you reported and when
- **EMIR**: Complete audit trail for derivative trades
- **SOX**: Demonstrate internal controls and change tracking
- **AIFMD**: Fund management audit trails and NAV reporting
- **GDPR**: Track data corrections and deletions with timestamps

### Compliance Query Patterns

#### MiFID II Trade Reporting
```sql
-- Prove what was reported to regulators at specific time
SELECT payload->>'subject' as trade_id,
       payload->'data'->>'symbol' as instrument,
       payload->'data'->>'notionalAmount' as notional,
       payload->'data'->>'counterparty' as counterparty,
       valid_time as trade_time,
       transaction_time as reporting_time,
       payload->>'correlationid' as correlation_id
FROM bitemporal_event_log
WHERE payload->>'type' = 'backoffice.trade.new.v1'
AND transaction_time <= '2025-10-15T17:00:00Z'  -- Regulatory deadline
AND valid_time::date = '2025-10-15'  -- Trading day
ORDER BY transaction_time;
```

#### EMIR Derivative Trade Audit Trail
```sql
-- Complete audit trail for derivative trade lifecycle
SELECT payload->>'correlationid' as trade_id,
       payload->>'type' as event_type,
       payload->'data'->>'status' as status,
       payload->>'clearinghouse' as clearing_house,
       payload->'data'->>'notionalAmount' as notional,
       valid_time as effective_time,
       transaction_time as recorded_time,
       (transaction_time - valid_time) as reporting_delay
FROM bitemporal_event_log
WHERE payload->>'correlationid' = 'TRD-DERIV-001'
AND payload->>'type' LIKE '%trade%'
ORDER BY valid_time, transaction_time;
```

### 4. Exception Handling in Middle Office Operations

**Scenario**: Transaction processing exceptions with automated retry and escalation

**Middle Office Responsibilities**:
- Trade confirmation and matching
- Position reconciliation
- Corporate actions processing
- Exception management and resolution
- STP (Straight Through Processing) monitoring

#### Exception Types and Handling

```java
// Exception data model
public class TransactionExceptionData {
    public String transactionId;
    public String exceptionType;  // MATCHING_FAILED, PRICE_DISCREPANCY, COUNTERPARTY_UNKNOWN, etc.
    public String severity;       // LOW, MEDIUM, HIGH, CRITICAL
    public String description;
    public String originalValue;
    public String expectedValue;
    public String systemSource;
    public String assignedTo;
    public String status;         // OPEN, IN_PROGRESS, RESOLVED, ESCALATED
    public String resolutionAction;
    public Instant detectedTime;
    public Instant resolvedTime;
}

// 1. Trade matching exception
TransactionExceptionData matchingException = new TransactionExceptionData(
    "TRD-001", "MATCHING_FAILED", "HIGH",
    "Trade confirmation price mismatch with counterparty",
    "150.50", "150.25", "TRADE_CAPTURE_SYSTEM",
    "middle.office.team", "OPEN", null,
    Instant.now(), null
);

CloudEvent exceptionEvent = CloudEventBuilder.v1()
    .withType("middleoffice.exception.matching.failed.v1")
    .withSubject("TRD-001")
    .withSource(URI.create("https://middleoffice.example.com/exception-management"))
    .withExtension("correlationid", "TRD-001")
    .withExtension("exceptionId", "EXC-" + UUID.randomUUID().toString().substring(0, 8))
    .withExtension("severity", "HIGH")
    .withExtension("exceptionType", "MATCHING_FAILED")
    .withExtension("systemSource", "TRADE_CAPTURE_SYSTEM")
    .withExtension("assignedTo", "middle.office.team")
    .withExtension("autoRetryEnabled", "true")
    .withExtension("escalationRequired", "false")
    .withData(mapper.writeValueAsBytes(matchingException))
    .build();

// Valid time = when exception was detected
// Transaction time = when exception was recorded
eventStore.append("TransactionExceptionDetected", exceptionEvent, matchingException.detectedTime);
```

#### Automated Retry Processing

```java
// 2. Automated retry attempt
TransactionExceptionData retryAttempt = new TransactionExceptionData(
    "TRD-001", "MATCHING_FAILED", "HIGH",
    "Automated retry attempt #1 - checking updated counterparty confirmation",
    "150.50", "150.25", "EXCEPTION_PROCESSOR",
    "system.auto.retry", "IN_PROGRESS", "AUTOMATED_RETRY",
    matchingException.detectedTime, null
);

CloudEvent retryEvent = CloudEventBuilder.v1()
    .withType("middleoffice.exception.retry.attempted.v1")
    .withSubject("TRD-001")
    .withExtension("correlationid", "TRD-001")
    .withExtension("causationid", exceptionEvent.getId())  // Links to original exception
    .withExtension("exceptionId", exceptionEvent.getExtension("exceptionId").toString())
    .withExtension("retryAttempt", "1")
    .withExtension("retryType", "AUTOMATED")
    .withExtension("maxRetries", "3")
    .withData(mapper.writeValueAsBytes(retryAttempt))
    .build();

// 3. Exception resolution
TransactionExceptionData resolved = new TransactionExceptionData(
    "TRD-001", "MATCHING_FAILED", "HIGH",
    "Resolved: Counterparty confirmed correct price is 150.25",
    "150.50", "150.25", "MIDDLE_OFFICE_USER",
    "john.analyst", "RESOLVED", "MANUAL_CORRECTION",
    matchingException.detectedTime, Instant.now()
);

CloudEvent resolutionEvent = CloudEventBuilder.v1()
    .withType("middleoffice.exception.resolved.v1")
    .withSubject("TRD-001")
    .withExtension("correlationid", "TRD-001")
    .withExtension("causationid", exceptionEvent.getId())
    .withExtension("exceptionId", exceptionEvent.getExtension("exceptionId").toString())
    .withExtension("resolutionMethod", "MANUAL_CORRECTION")
    .withExtension("resolvedBy", "john.analyst")
    .withExtension("resolutionTime", resolved.resolvedTime.toString())
    .withData(mapper.writeValueAsBytes(resolved))
    .build();
```

#### Exception Escalation

```java
// 4. Exception escalation for critical issues
TransactionExceptionData criticalException = new TransactionExceptionData(
    "TRD-002", "COUNTERPARTY_UNKNOWN", "CRITICAL",
    "Unknown counterparty 'MYSTERIOUS_BANK' - potential compliance issue",
    "MYSTERIOUS_BANK", "KNOWN_COUNTERPARTY", "COMPLIANCE_SYSTEM",
    "compliance.team", "ESCALATED", null,
    Instant.now(), null
);

CloudEvent escalationEvent = CloudEventBuilder.v1()
    .withType("middleoffice.exception.escalated.v1")
    .withSubject("TRD-002")
    .withExtension("correlationid", "TRD-002")
    .withExtension("exceptionId", "EXC-CRITICAL-001")
    .withExtension("severity", "CRITICAL")
    .withExtension("exceptionType", "COUNTERPARTY_UNKNOWN")
    .withExtension("escalatedTo", "compliance.team")
    .withExtension("escalationReason", "POTENTIAL_COMPLIANCE_VIOLATION")
    .withExtension("slaBreached", "true")
    .withExtension("regulatoryImpact", "true")
    .withData(mapper.writeValueAsBytes(criticalException))
    .build();
```

#### Exception Management Queries

**Exception Dashboard - Current Open Issues**:
```sql
-- Get all open exceptions by severity
SELECT payload->>'exceptionId' as exception_id,
       payload->>'subject' as transaction_id,
       payload->>'exceptionType' as exception_type,
       payload->>'severity' as severity,
       payload->'data'->>'description' as description,
       payload->>'assignedTo' as assigned_to,
       payload->'data'->>'status' as status,
       valid_time as detected_time,
       (EXTRACT(EPOCH FROM (NOW() - valid_time))/3600)::int as hours_open
FROM bitemporal_event_log
WHERE payload->>'type' LIKE 'middleoffice.exception.%'
AND payload->'data'->>'status' IN ('OPEN', 'IN_PROGRESS', 'ESCALATED')
ORDER BY
    CASE payload->>'severity'
        WHEN 'CRITICAL' THEN 1
        WHEN 'HIGH' THEN 2
        WHEN 'MEDIUM' THEN 3
        ELSE 4
    END,
    valid_time;
```

**Exception Resolution Metrics**:
```sql
-- Exception resolution time analysis
WITH exception_lifecycle AS (
    SELECT payload->>'exceptionId' as exception_id,
           payload->>'exceptionType' as exception_type,
           payload->>'severity' as severity,
           MIN(CASE WHEN payload->>'type' LIKE '%.detected.%' THEN valid_time END) as detected_time,
           MAX(CASE WHEN payload->>'type' LIKE '%.resolved.%' THEN valid_time END) as resolved_time,
           MAX(CASE WHEN payload->>'type' LIKE '%.resolved.%' THEN payload->>'resolutionMethod' END) as resolution_method
    FROM bitemporal_event_log
    WHERE payload->>'type' LIKE 'middleoffice.exception.%'
    AND payload->>'exceptionId' IS NOT NULL
    GROUP BY payload->>'exceptionId', payload->>'exceptionType', payload->>'severity'
    HAVING MAX(CASE WHEN payload->>'type' LIKE '%.resolved.%' THEN 1 ELSE 0 END) = 1
)
SELECT exception_type,
       severity,
       COUNT(*) as total_exceptions,
       AVG(EXTRACT(EPOCH FROM (resolved_time - detected_time))/3600) as avg_resolution_hours,
       MIN(EXTRACT(EPOCH FROM (resolved_time - detected_time))/3600) as min_resolution_hours,
       MAX(EXTRACT(EPOCH FROM (resolved_time - detected_time))/3600) as max_resolution_hours,
       COUNT(CASE WHEN resolution_method = 'AUTOMATED_RETRY' THEN 1 END) as auto_resolved,
       COUNT(CASE WHEN resolution_method = 'MANUAL_CORRECTION' THEN 1 END) as manual_resolved
FROM exception_lifecycle
WHERE detected_time >= '2025-10-01'
GROUP BY exception_type, severity
ORDER BY exception_type,
    CASE severity
        WHEN 'CRITICAL' THEN 1
        WHEN 'HIGH' THEN 2
        WHEN 'MEDIUM' THEN 3
        ELSE 4
    END;
```

**SLA Breach Analysis**:
```sql
-- Identify exceptions breaching SLA thresholds
WITH sla_thresholds AS (
    SELECT 'CRITICAL' as severity, 1 as sla_hours
    UNION ALL SELECT 'HIGH', 4
    UNION ALL SELECT 'MEDIUM', 24
    UNION ALL SELECT 'LOW', 72
),
open_exceptions AS (
    SELECT DISTINCT ON (payload->>'exceptionId')
           payload->>'exceptionId' as exception_id,
           payload->>'exceptionType' as exception_type,
           payload->>'severity' as severity,
           payload->'data'->>'assignedTo' as assigned_to,
           valid_time as detected_time,
           EXTRACT(EPOCH FROM (NOW() - valid_time))/3600 as hours_open
    FROM bitemporal_event_log
    WHERE payload->>'type' LIKE 'middleoffice.exception.%'
    AND payload->'data'->>'status' IN ('OPEN', 'IN_PROGRESS')
    ORDER BY payload->>'exceptionId', transaction_time DESC
)
SELECT oe.exception_id,
       oe.exception_type,
       oe.severity,
       oe.assigned_to,
       oe.detected_time,
       oe.hours_open,
       st.sla_hours,
       (oe.hours_open - st.sla_hours) as hours_over_sla,
       CASE
           WHEN oe.hours_open > st.sla_hours THEN 'SLA_BREACHED'
           WHEN oe.hours_open > (st.sla_hours * 0.8) THEN 'SLA_WARNING'
           ELSE 'WITHIN_SLA'
       END as sla_status
FROM open_exceptions oe
JOIN sla_thresholds st ON oe.severity = st.severity
WHERE oe.hours_open > (st.sla_hours * 0.8)  -- Show warnings and breaches
ORDER BY (oe.hours_open - st.sla_hours) DESC;
```

#### SOX Internal Controls Audit
```sql
-- Track all corrections and who made them
SELECT payload->>'subject' as entity_id,
       payload->>'type' as event_type,
       payload->'data'->>'correctionReason' as reason,
       payload->>'userId' as user_id,
       payload->>'systemId' as system_id,
       valid_time as original_effective_time,
       transaction_time as correction_time,
       (transaction_time::date - valid_time::date) as days_to_correction
FROM bitemporal_event_log
WHERE payload->>'type' LIKE '%.corrected.%'
AND transaction_time >= '2025-10-01'
ORDER BY transaction_time DESC;
```

#### AIFMD Fund NAV Reporting
```sql
-- NAV calculation audit trail with late trade impact
WITH nav_calculations AS (
    SELECT payload->>'fundId' as fund_id,
           payload->>'type' as calculation_type,
           payload->'data'->>'navPerShare' as nav_value,
           payload->>'calculationMethod' as method,
           valid_time as nav_date,
           transaction_time as calculation_time,
           payload->>'lateTradesIncluded' as includes_late_trades
    FROM bitemporal_event_log
    WHERE payload->>'type' LIKE 'funds.nav.%'
    AND payload->>'fundId' = 'FUND-ABC-001'
    AND valid_time::date = '2025-10-15'
)
SELECT fund_id,
       calculation_type,
       nav_value,
       includes_late_trades,
       calculation_time,
       CASE
           WHEN includes_late_trades = 'true' THEN 'CORRECTED'
           ELSE 'ORIGINAL'
       END as reporting_status
FROM nav_calculations
ORDER BY calculation_time;
```

### Point-in-Time Regulatory Queries

#### "What Did We Know When?" Queries
```sql
-- Reconstruct portfolio position as known at specific regulatory deadline
SELECT payload->>'fundId' as fund_id,
       payload->'data'->>'symbol' as security,
       SUM((payload->'data'->>'quantity')::numeric) as total_position,
       SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional,
       COUNT(*) as trade_count
FROM bitemporal_event_log
WHERE payload->>'type' LIKE 'funds.trade.%'
AND payload->>'fundId' = 'FUND-ABC-001'
AND valid_time::date <= '2025-10-15'  -- Positions as of date
AND transaction_time <= '2025-10-15T17:00:00Z'  -- Known by deadline
GROUP BY payload->>'fundId', payload->'data'->>'symbol'
ORDER BY total_notional DESC;
```

#### Regulatory Change Impact Analysis
```sql
-- Analyze impact of late-arriving information on regulatory reports
WITH original_report AS (
    SELECT payload->>'fundId' as fund_id,
           COUNT(*) as original_trade_count,
           SUM((payload->'data'->>'notionalAmount')::numeric) as original_notional
    FROM bitemporal_event_log
    WHERE payload->>'type' LIKE 'funds.trade.%'
    AND valid_time::date = '2025-10-15'
    AND transaction_time <= '2025-10-15T17:00:00Z'  -- Original deadline
    GROUP BY payload->>'fundId'
),
corrected_report AS (
    SELECT payload->>'fundId' as fund_id,
           COUNT(*) as corrected_trade_count,
           SUM((payload->'data'->>'notionalAmount')::numeric) as corrected_notional
    FROM bitemporal_event_log
    WHERE payload->>'type' LIKE 'funds.trade.%'
    AND valid_time::date = '2025-10-15'  -- All trades that actually happened
    GROUP BY payload->>'fundId'
)
SELECT o.fund_id,
       o.original_trade_count,
       c.corrected_trade_count,
       (c.corrected_trade_count - o.original_trade_count) as missed_trades,
       o.original_notional,
       c.corrected_notional,
       (c.corrected_notional - o.original_notional) as notional_difference,
       ROUND(((c.corrected_notional - o.original_notional) / o.original_notional * 100)::numeric, 2) as percentage_impact
FROM original_report o
JOIN corrected_report c ON o.fund_id = c.fund_id
WHERE ABS(c.corrected_notional - o.original_notional) > 1000  -- Material differences
ORDER BY ABS(percentage_impact) DESC;
```

**Settlement Failure Analysis**:
```sql
-- Analyze settlement failures by custodian and reason
SELECT payload->>'custodian' as custodian,
       payload->'data'->>'failureReason' as failure_reason,
       COUNT(*) as failure_count,
       AVG(EXTRACT(EPOCH FROM (transaction_time - valid_time))/3600) as avg_detection_hours
FROM bitemporal_event_log
WHERE payload->>'type' = 'settlement.instruction.failed.v1'
AND valid_time >= '2025-10-01'
GROUP BY payload->>'custodian', payload->'data'->>'failureReason'
ORDER BY failure_count DESC;
```

**Settlement Lifecycle Reconstruction**:
```sql
-- Complete settlement lifecycle for specific instruction
SELECT payload->>'correlationid' as instruction_id,
       payload->>'type' as event_type,
       payload->'data'->>'status' as status,
       payload->'data'->>'failureReason' as failure_reason,
       valid_time as effective_time,
       transaction_time as processed_time,
       ROW_NUMBER() OVER (PARTITION BY payload->>'correlationid' ORDER BY valid_time, transaction_time) as sequence
FROM bitemporal_event_log
WHERE payload->>'correlationid' = 'SSI-12345'
AND payload->>'type' LIKE 'settlement.instruction.%'
ORDER BY valid_time, transaction_time;
```

---

## Testing

### Comprehensive Test Coverage

**CloudEventsJsonbQueryTest** - Advanced JSONB query patterns:
- CloudEvent metadata queries (type, source, subject)
- Extension attribute queries (correlationid, causationid, booking system)
- Data payload field queries (notional amount, counterparty, status)
- Bi-temporal point-in-time queries with JSONB filtering
- Trade lifecycle reconstruction using correlation IDs
- Multi-system queries across booking systems and clearing houses

**Test Scenarios**:
1. **Trade Lifecycle Events** - NEW → AFFIRMED → SETTLED progression
2. **Cross-System Analysis** - Murex vs Calypso booking systems
3. **Clearing House Queries** - DTCC vs LCH settlement
4. **Notional Amount Filtering** - Large trade identification
5. **Settlement Date Ranges** - Time-based trade filtering
6. **Point-in-Time Reconstruction** - Historical state queries

**Key Test Patterns**:
```java
// Query by CloudEvent type
String sql = "SELECT event_id, payload->>'type' as event_type " +
            "FROM bitemporal_event_log " +
            "WHERE payload->>'type' = $1";

// Query by extension attributes
String sql = "SELECT event_id, payload->>'correlationid' " +
            "FROM bitemporal_event_log " +
            "WHERE payload->>'correlationid' = $1";

// Query by nested data payload
String sql = "SELECT event_id, (payload->'data'->>'notionalAmount')::numeric " +
            "FROM bitemporal_event_log " +
            "WHERE (payload->'data'->>'notionalAmount')::numeric > $1";
```

---

## Performance Considerations

### JSONB Indexing

For production deployments with high event volumes, consider PostgreSQL JSONB indexes:

```sql
-- Index on CloudEvent type for fast event type queries
CREATE INDEX idx_cloudevents_type ON bitemporal_event_log
USING GIN ((payload->>'type'));

-- Index on correlation ID for fast lifecycle queries
CREATE INDEX idx_cloudevents_correlation ON bitemporal_event_log
USING GIN ((payload->>'correlationid'));

-- Index on subject for fast trade/entity queries
CREATE INDEX idx_cloudevents_subject ON bitemporal_event_log
USING GIN ((payload->>'subject'));

-- Composite index for common query patterns
CREATE INDEX idx_cloudevents_type_correlation ON bitemporal_event_log
USING GIN ((payload->>'type'), (payload->>'correlationid'));
```

### Query Optimization

**Efficient Query Patterns**:
```java
// ✅ GOOD - Specific type and correlation ID
eventStore.query(EventQuery.forAggregateAndType("trade:TRD-001", "TradeExecuted"));

// ✅ GOOD - Use aggregate ID prefixing
String aggregateId = "trade:" + tradeId;  // Separates trade events from other domains

// ❌ AVOID - EventQuery.all() causes cross-type deserialization
eventStore.query(EventQuery.all());  // Inefficient for large datasets
```

**JSONB Query Performance**:
- Use specific field paths: `payload->>'type'` vs `payload->'metadata'->>'type'`
- Leverage PostgreSQL's JSONB operators: `->`, `->>`, `@>`, `?`, `?&`, `?|`
- Consider partial indexes for frequently queried subsets
- Use `EXPLAIN ANALYZE` to optimize complex queries

---

## Migration Guide

### Gradual Adoption Strategy

**Phase 1: Infrastructure Setup**
1. Add CloudEvents dependencies to your project
2. Existing ObjectMapper instances automatically gain CloudEvents support
3. Verify CloudEvents module registration in logs

**Phase 2: New Event Types**
1. Start using CloudEvents for new event types
2. Implement CloudEvent builders for domain events
3. Add extension attributes for correlation and causation

**Phase 3: Legacy Event Migration**
1. Gradually migrate existing events to CloudEvents format
2. Maintain backward compatibility during transition
3. Update query patterns to leverage JSONB capabilities

**Phase 4: Advanced Patterns**
1. Implement bi-temporal CloudEvents for audit requirements
2. Add JSONB indexes for performance optimization
3. Develop domain-specific query patterns

### Example Migration

**Before (Simple Event)**:
```java
TradeEvent trade = new TradeEvent("TRD-001", "AAPL", "BUY", 100);
eventStore.append("TradeExecuted", trade, executionTime);
```

**After (CloudEvent)**:
```java
CloudEvent tradeEvent = CloudEventBuilder.v1()
    .withType("trading.trade.executed.v1")
    .withSubject("TRD-001")
    .withExtension("correlationid", "TRD-001")
    .withExtension("symbol", "AAPL")
    .withData(mapper.writeValueAsBytes(trade))
    .build();

eventStore.append("TradeExecuted", tradeEvent, executionTime);
```

**Benefits of Migration**:
- Standardized event format across systems
- Rich metadata and extension support
- Powerful JSONB querying capabilities
- Improved interoperability with external systems
- Better audit trails and compliance support

No breaking changes are introduced - existing functionality continues to work as before.

---

## Reference Implementation

### Example Projects

**CloudEventsJsonbQueryTest** - Comprehensive JSONB query patterns
- **Location**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/`
- **Demonstrates**: Trade lifecycle events, JSONB queries, bi-temporal patterns
- **Use Cases**: Back office trade processing, settlement, regulatory reporting

**Financial Fabric Example** - Production-ready CloudEvents integration
- **Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootfinancialfabric/`
- **Demonstrates**: Multi-domain event processing, correlation patterns
- **Features**: Trading, settlement, custody, risk management, compliance

**Bi-Temporal Guide** - Complete documentation and patterns
- **Location**: `peegeeq-examples/docs/BITEMPORAL_GUIDE.md`
- **Covers**: Basics through advanced patterns, financial use cases
- **Includes**: CloudEvents + JSONB combination patterns

### Key Implementation Files

**BiTemporalEventStoreFactory** - CloudEvents module registration
- **Location**: `peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/`
- **Features**: Automatic CloudEvents Jackson module detection and registration

**PgBiTemporalEventStore** - JSONB storage implementation
- **Location**: `peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/`
- **Features**: CloudEvents serialization to JSONB, efficient querying

**PeeGeeQRestServer** - REST API CloudEvents support
- **Location**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/`
- **Features**: HTTP endpoints with CloudEvents content negotiation

### Database Schema

**Bi-Temporal Event Log Table**:
```sql
CREATE TABLE bitemporal_event_log (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255),
    valid_time TIMESTAMPTZ NOT NULL,
    transaction_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload JSONB NOT NULL,  -- CloudEvents stored as JSONB
    headers JSONB,
    correlation_id VARCHAR(255),
    version BIGINT DEFAULT 1,
    previous_version_id UUID,
    is_correction BOOLEAN DEFAULT FALSE,
    correction_reason TEXT
);

-- Recommended indexes for CloudEvents
CREATE INDEX idx_cloudevents_type ON bitemporal_event_log
USING GIN ((payload->>'type'));

CREATE INDEX idx_cloudevents_correlation ON bitemporal_event_log
USING GIN ((payload->>'correlationid'));

CREATE INDEX idx_cloudevents_subject ON bitemporal_event_log
USING GIN ((payload->>'subject'));
```

---

## Summary

PeeGeeQ's CloudEvents integration provides a powerful foundation for event-driven architectures in financial services and other domains requiring audit trails, compliance, and complex event processing. The combination of CloudEvents v1.0 specification, PostgreSQL JSONB storage, and bi-temporal event sourcing creates a robust platform for:

- **Regulatory Compliance**: Complete audit trails with valid time and transaction time
- **Financial Processing**: Trade lifecycle, settlement, corporate actions, NAV calculations
- **Cross-System Integration**: Standardized event format for interoperability
- **Advanced Analytics**: JSONB queries for complex event analysis and reporting
- **Production Scale**: High-performance event storage with efficient querying

The integration is designed to be non-breaking, allowing gradual adoption while maintaining full backward compatibility with existing PeeGeeQ applications.
