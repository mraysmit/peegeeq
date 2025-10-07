# Financial Services Event Fabric Integration Guide

**Example**: `springboot-financial-fabric`
**Use Case**: Multi-domain financial event processing with CloudEvents and bi-temporal audit
**Focus**: Event naming patterns, correlation tracking, regulatory compliance

---

## What This Guide Covers

This guide demonstrates a complete financial services event fabric using PeeGeeQ's bi-temporal event store with CloudEvents integration. It shows how to implement cross-domain event processing following the `{entity}.{action}.{state}` naming pattern with complete audit trail for regulatory compliance.

**Key Patterns**:
- Event naming standard: `{entity}.{action}.{state}`
- CloudEvents v1.0 integration
- Multi-domain transaction coordination
- Correlation and causation tracking
- Bi-temporal regulatory queries
- Wildcard event routing

**What This Guide Does NOT Cover**:
- Basic bi-temporal concepts (see BITEMPORAL_BASICS_GUIDE.md)
- Outbox pattern basics (see CONSUMER_GROUP_GUIDE.md)
- Retry and DLQ (see DLQ_RETRY_GUIDE.md)

---

## Core Concepts

### Event Naming Pattern

**Format**: `{entity}.{action}.{state}`

**Why This Pattern?**

Traditional event naming often lacks structure:
- ❌ `TradeCreated` - No context about domain or outcome
- ❌ `trade_confirmed` - Inconsistent casing
- ❌ `SETTLEMENT_FAIL` - No entity or action clarity

The `{entity}.{action}.{state}` pattern provides:
- ✅ **Entity** - Business domain object (trade, instruction, cash, position)
- ✅ **Action** - Business operation (capture, settlement, movement, reconciliation)
- ✅ **State** - Outcome (completed, matched, failed, rejected)

**Examples Across Domains**:

**Trading Domain**:
```
trading.equities.capture.completed
trading.equities.confirmation.matched
trading.fx.capture.completed
trading.bonds.capture.failed
```

**Custody Domain**:
```
instruction.settlement.submitted
instruction.settlement.matched
instruction.settlement.confirmed
instruction.settlement.failed
```

**Treasury Domain**:
```
cash.movement.completed
cash.liquidity.checked
cash.funding.required
```

**Position Management**:
```
position.update.completed
position.reconciliation.completed
position.reconciliation.failed
```

**Regulatory Domain**:
```
regulatory.transaction.reported
regulatory.compliance.checked
regulatory.breach.detected
```

### CloudEvents Integration

**Why CloudEvents?**

CloudEvents is a CNCF specification for describing event data in a common way. Benefits:
- Industry standard format
- Interoperability across systems
- Built-in metadata (source, time, type)
- Extensibility for custom fields

**CloudEvents Structure**:
```json
{
  "specversion": "1.0",
  "type": "com.fincorp.trading.equities.capture.completed.v1",
  "source": "trading-system",
  "id": "evt-12345-abc",
  "time": "2025-10-07T14:30:00Z",
  "datacontenttype": "application/json",
  "data": {
    "tradeId": "TRD-12345",
    "instrument": "AAPL",
    "quantity": 1000,
    "price": 175.50,
    "counterparty": "BROKER-XYZ"
  },

  "correlationid": "workflow-abc-123",
  "causationid": "evt-parent-456",
  "validtime": "2025-10-07T14:30:00Z"
}
```

**Custom Extensions**:
- `correlationid` - Links all events in a business workflow
- `causationid` - Identifies the parent event that caused this event
- `validtime` - Business time (when event occurred in real world)

### Correlation vs Causation

**Correlation ID** - Workflow identifier:
- Same for ALL events in a business transaction
- Used to query complete workflow history
- Example: All events from trade capture → settlement → reporting

**Causation ID** - Parent event identifier:
- Points to the event that caused this event
- Creates event lineage graph
- Example: Settlement instruction caused by trade capture

**Example**:
```
Event 1: Trade Capture
  - correlationId: workflow-123
  - causationId: null (first event)

Event 2: Settlement Instruction
  - correlationId: workflow-123
  - causationId: evt-trade-capture-id

Event 3: Cash Movement
  - correlationId: workflow-123
  - causationId: evt-trade-capture-id

Event 4: Position Update
  - correlationId: workflow-123
  - causationId: evt-trade-capture-id

Event 5: Regulatory Report
  - correlationId: workflow-123
  - causationId: evt-trade-capture-id
```

---

## Implementation

### Multi-Domain Event Stores

**Five Domain-Specific Event Stores**:

```java
@Configuration
public class FinancialFabricConfig {

    @Bean
    public EventStore<CloudEvent> tradingEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("trading_events", CloudEvent.class, databaseService);
    }

    @Bean
    public EventStore<CloudEvent> settlementEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("settlement_events", CloudEvent.class, databaseService);
    }

    @Bean
    public EventStore<CloudEvent> cashEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("cash_events", CloudEvent.class, databaseService);
    }

    @Bean
    public EventStore<CloudEvent> positionEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("position_events", CloudEvent.class, databaseService);
    }

    @Bean
    public EventStore<CloudEvent> regulatoryEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("regulatory_events", CloudEvent.class, databaseService);
    }
}
```

**Why Separate Event Stores?**
- Domain separation (clear boundaries)
- Independent querying (each domain has own history)
- Scalability (different retention policies)
- Compliance (separate audit trails)

### CloudEvent Builder

**Creating CloudEvents**:

```java
@Component
public class FinancialCloudEventBuilder {

    private final FinancialFabricProperties properties;
    private final ObjectMapper objectMapper;

    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {

        String eventId = UUID.randomUUID().toString();
        URI source = URI.create(properties.getCloudevents().getSource());

        // Serialize payload to JSON
        byte[] data = objectMapper.writeValueAsBytes(payload);

        // Build CloudEvent
        return CloudEventBuilder.v1()
            .withId(eventId)
            .withType(eventType)
            .withSource(source)
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(data)
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId != null ? causationId : "")
            .withExtension("validtime", validTime.toString())
            .build();
    }
}
```

### Trade Workflow Orchestration

**Complete Trade Lifecycle**:

```java
@Service
public class TradeWorkflowService {

    private final EventStore<CloudEvent> tradingEventStore;
    private final EventStore<CloudEvent> settlementEventStore;
    private final EventStore<CloudEvent> cashEventStore;
    private final EventStore<CloudEvent> positionEventStore;
    private final EventStore<CloudEvent> regulatoryEventStore;
    private final DatabaseService databaseService;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<WorkflowResult> executeTradeWorkflow(TradeRequest request) {
        String correlationId = UUID.randomUUID().toString();
        Instant tradeTime = request.getTradeTime();

        return databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {

                // Step 1: Capture trade
                return captureTrade(request, correlationId, null, tradeTime, connection)

                    // Step 2: Generate settlement instruction
                    .thenCompose(tradeEvent ->
                        generateSettlementInstruction(request, correlationId,
                            tradeEvent.getId(), tradeTime, connection))

                    // Step 3: Record cash movement
                    .thenCompose(settlementEvent ->
                        recordCashMovement(request, correlationId,
                            settlementEvent.getId(), tradeTime, connection))

                    // Step 4: Update position
                    .thenCompose(cashEvent ->
                        updatePosition(request, correlationId,
                            cashEvent.getId(), tradeTime, connection))

                    // Step 5: Regulatory reporting
                    .thenCompose(positionEvent ->
                        reportRegulatory(request, correlationId,
                            positionEvent.getId(), tradeTime, connection))

                    .thenApply(regulatoryEvent -> new WorkflowResult(
                        request.getTradeId(),
                        "SUCCESS",
                        correlationId,
                        tradeTime,
                        List.of(
                            "trading.equities.capture.completed",

---

## Use Cases

### Use Case 1: Trade Lifecycle Management

**Scenario**: Equity trade execution with complete audit trail

**Flow**:
1. Trader executes trade: 1,000 shares AAPL @ $175.50
2. System captures trade → `trading.equities.capture.completed`
3. System generates settlement instruction → `instruction.settlement.submitted`
4. System records cash movement → `cash.movement.completed`
5. System updates position → `position.update.completed`
6. System reports to regulator → `regulatory.transaction.reported`

**All in single transaction with same correlation ID**

**Benefits**:
- Complete ACID guarantees
- Full audit trail across all domains
- Regulatory compliance (MiFID II, EMIR)
- Point-in-time reconstruction

### Use Case 2: Position Reconciliation

**Scenario**: Daily position reconciliation with external custodian

**Query**:
```java
public CompletableFuture<PositionReconciliationReport> reconcilePositionAsOf(
        String instrument,
        String account,
        Instant businessDate,
        BigDecimal externalPosition) {

    // Calculate internal position from events
    return positionEventStore.query(EventQuery.validTimeBetween(Instant.MIN, businessDate))
        .thenApply(events -> {
            BigDecimal calculatedPosition = events.stream()
                .filter(e -> matchesInstrumentAndAccount(e, instrument, account))
                .map(e -> extractQuantityChange(e))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal difference = externalPosition.subtract(calculatedPosition);
            boolean matched = difference.compareTo(BigDecimal.ZERO) == 0;

            return new PositionReconciliationReport(
                instrument,
                account,
                businessDate,
                calculatedPosition,
                externalPosition,
                difference,
                matched ? "MATCHED" : "BREAK"
            );
        });
}
```

**Benefits**:
- Reconstruct position from event history
- Compare with external source
- Identify breaks immediately
- Complete audit trail for investigation

### Use Case 3: Regulatory Reporting (MiFID II)

**Scenario**: Generate daily MiFID II transaction report

**Query**:
```java
public CompletableFuture<MiFIDIIReport> generateMiFIDIIReport(
        Instant reportingDate) {

    Instant startOfDay = reportingDate.truncatedTo(ChronoUnit.DAYS);
    Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);

    return tradingEventStore.query(EventQuery.validTimeBetween(startOfDay, endOfDay))
        .thenApply(events -> {
            List<MiFIDIITransaction> transactions = events.stream()
                .filter(e -> e.getPayload().getType().contains("capture.completed"))
                .map(e -> convertToMiFIDIITransaction(e))
                .collect(Collectors.toList());

            return new MiFIDIIReport(
                reportingDate,
                transactions,
                "MiFID II Transaction Report"
            );
        });
}
```

**Benefits**:
- Query by business time (valid time)
- Complete transaction history
- Regulatory compliance
- Audit trail for investigations

### Use Case 4: Exception Management

**Scenario**: Cross-domain failure handling

**Handler**:
```java
@Component
public class ExceptionEventHandler {

    @EventPattern("*.*.*.failed")
    public void handleFailure(CloudEvent event) {
        String domain = extractDomain(event.getType());
        String correlationId = CloudEventExtensions.getCorrelationId(event);

        log.error("FAILURE: domain={}, correlationId={}", domain, correlationId);

        // Create exception ticket
        ExceptionTicket ticket = new ExceptionTicket(
            event.getId(),
            domain,
            event.getType(),
            correlationId,
            "OPEN",
            Instant.now()
        );

        exceptionRepository.save(ticket);

        // Alert operations team
        alertService.sendAlert(
            "FAILURE_DETECTED",
            "Domain: " + domain + ", Event: " + event.getType()
        );

        // Trigger automated remediation if applicable
        if (isRetryable(event)) {
            remediationService.scheduleRetry(event);
        }
    }
}
```

**Benefits**:
- Centralized exception handling
- Cross-domain visibility
- Automated alerting
- Remediation workflows

---

## Best Practices

### 1. Use Consistent Event Naming

**Why**: Predictable, searchable, maintainable

```java
// ✅ CORRECT - {entity}.{action}.{state}
"trading.equities.capture.completed"
"instruction.settlement.submitted"
"cash.movement.completed"
"position.update.completed"
"regulatory.transaction.reported"

// ❌ WRONG - Inconsistent patterns
"TradeCreated"
"settlement_instruction_sent"
"CASH_MOVED"
"PositionUpdated"
"RegReport"
```

### 2. Always Use Correlation IDs

**Why**: Links events across domains for complete audit trail

```java
// ✅ CORRECT - Same correlation ID for entire workflow
String correlationId = UUID.randomUUID().toString();

CloudEvent tradeEvent = buildCloudEvent(..., correlationId, null, ...);
CloudEvent settlementEvent = buildCloudEvent(..., correlationId, tradeEvent.getId(), ...);
CloudEvent cashEvent = buildCloudEvent(..., correlationId, tradeEvent.getId(), ...);

// ❌ WRONG - Different correlation IDs
CloudEvent tradeEvent = buildCloudEvent(..., UUID.randomUUID().toString(), ...);
CloudEvent settlementEvent = buildCloudEvent(..., UUID.randomUUID().toString(), ...);
```

### 3. Use Causation for Event Lineage

**Why**: Understand event dependencies and workflow

```java
// ✅ CORRECT - Causation chain
CloudEvent tradeEvent = buildCloudEvent(..., correlationId, null, ...);  // First event
CloudEvent settlementEvent = buildCloudEvent(..., correlationId, tradeEvent.getId(), ...);  // Caused by trade
CloudEvent cashEvent = buildCloudEvent(..., correlationId, tradeEvent.getId(), ...);  // Caused by trade

// ❌ WRONG - No causation
CloudEvent tradeEvent = buildCloudEvent(..., correlationId, null, ...);
CloudEvent settlementEvent = buildCloudEvent(..., correlationId, null, ...);  // Lost causation
```

### 4. Use Same Valid Time for Workflow

**Why**: Represents single business event

```java
// ✅ CORRECT - Same valid time
Instant tradeTime = request.getTradeTime();

CloudEvent tradeEvent = buildCloudEvent(..., tradeTime);
CloudEvent settlementEvent = buildCloudEvent(..., tradeTime);
CloudEvent cashEvent = buildCloudEvent(..., tradeTime);

// ❌ WRONG - Different valid times
CloudEvent tradeEvent = buildCloudEvent(..., Instant.now());
Thread.sleep(100);
CloudEvent settlementEvent = buildCloudEvent(..., Instant.now());  // Different time!
```

### 5. Use Single Transaction for Consistency

**Why**: ACID guarantees across all event stores

```java
// ✅ CORRECT - Single transaction
return connectionProvider.withTransaction("peegeeq-main", connection -> {
    return appendToTradingStore(event1, connection)
        .thenCompose(r -> appendToSettlementStore(event2, connection))
        .thenCompose(r -> appendToCashStore(event3, connection));
});

// ❌ WRONG - Separate transactions
appendToTradingStore(event1);  // Transaction 1
appendToSettlementStore(event2);  // Transaction 2 - could fail!
appendToCashStore(event3);  // Transaction 3 - partial update!
```

### 6. Use Wildcard Patterns for Cross-Cutting Concerns

**Why**: Handle common patterns across domains

```java
// ✅ CORRECT - Wildcard for all failures
@EventPattern("*.*.*.failed")
public void handleAllFailures(CloudEvent event) {
    // Centralized exception handling
}

// ✅ CORRECT - Wildcard for high priority
@EventPattern("*.*.*.*.high")
public void handleHighPriority(CloudEvent event) {
    // Centralized priority handling
}

// ❌ WRONG - Duplicate handlers for each domain
@EventPattern("trading.*.*.failed")
public void handleTradingFailure(CloudEvent event) { ... }

@EventPattern("settlement.*.*.failed")
public void handleSettlementFailure(CloudEvent event) { ... }

// ... repeated for every domain
```

### 7. Query by Correlation for Complete Audit

**Why**: Reconstruct entire workflow across all domains

```java
// ✅ CORRECT - Query all stores by correlation
CompletableFuture<WorkflowAuditTrail> getWorkflowAudit(String correlationId) {
    return CompletableFuture.allOf(
        queryByCorrelation(tradingEventStore, correlationId),
        queryByCorrelation(settlementEventStore, correlationId),
        queryByCorrelation(cashEventStore, correlationId),
        queryByCorrelation(positionEventStore, correlationId),
        queryByCorrelation(regulatoryEventStore, correlationId)
    ).thenApply(v -> combineResults(...));
}

// ❌ WRONG - Query only one store
CompletableFuture<List<CloudEvent>> getWorkflowAudit(String correlationId) {
    return queryByCorrelation(tradingEventStore, correlationId);  // Incomplete!
}
```

### 8. Use CloudEvents Extensions for Metadata

**Why**: Standard way to add custom metadata

```java
// ✅ CORRECT - CloudEvents extensions
CloudEvent event = CloudEventBuilder.v1()
    .withId(eventId)
    .withType(eventType)
    .withSource(source)
    .withExtension("correlationid", correlationId)
    .withExtension("causationid", causationId)
    .withExtension("validtime", validTime.toString())
    .build();

// ❌ WRONG - Custom fields in payload
Map<String, Object> payload = new HashMap<>();
payload.put("data", actualData);
payload.put("correlationId", correlationId);  // Should be extension!
payload.put("causationId", causationId);  // Should be extension!
```

---

## Summary

**Key Takeaways**:

1. **Event Naming Pattern** - `{entity}.{action}.{state}` provides structure and consistency
2. **CloudEvents Integration** - Industry standard for event interoperability
3. **Correlation Tracking** - Links all events in a business workflow
4. **Causation Tracking** - Captures event lineage and dependencies
5. **Multi-Domain Coordination** - Single transaction across multiple event stores
6. **Bi-Temporal Queries** - System time and valid time for regulatory compliance
7. **Wildcard Routing** - Flexible event subscription patterns
8. **ACID Guarantees** - All events succeed or all fail together

**Regulatory Benefits**:
- Complete audit trail (MiFID II, EMIR, SOX)
- Point-in-time reconstruction
- Event corrections without losing history
- Causation graph for investigations

**Operational Benefits**:
- Cross-domain visibility
- Centralized exception handling
- Automated alerting
- Workflow tracking

**Related Guides**:
- **BITEMPORAL_BASICS_GUIDE.md** - Bi-temporal fundamentals
- **TRANSACTIONAL_BITEMPORAL_GUIDE.md** - Multi-store transactions
- **INTEGRATED_PATTERN_GUIDE.md** - Outbox + bi-temporal integration
- **REACTIVE_BITEMPORAL_GUIDE.md** - Reactive patterns
                        )
                    ));

            }).toCompletionStage().toCompletableFuture();
    }

    private CompletableFuture<CloudEvent> captureTrade(
            TradeRequest request,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        try {
            TradeEvent tradeEvent = new TradeEvent(
                request.getTradeId(),
                request.getInstrument(),
                request.getQuantity(),
                request.getPrice(),
                request.getCounterparty(),
                "EQUITIES",
                request.getTradeType(),
                validTime
            );

            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.trading.equities.capture.completed.v1",
                tradeEvent,
                correlationId,
                causationId,
                validTime
            );

            return ((PgBiTemporalEventStore<CloudEvent>) tradingEventStore)
                .appendInTransaction(
                    "trading.equities.capture.completed",
                    cloudEvent,
                    validTime,
                    Map.of("correlationId", correlationId),
                    correlationId,
                    request.getTradeId(),
                    connection
                )
                .thenApply(biTemporalEvent -> cloudEvent);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
```

**Key Points**:
1. **Single Transaction** - All 5 event stores use same connection
2. **Correlation ID** - Same for all events in workflow
3. **Causation Chain** - Each event caused by previous event
4. **Valid Time** - Same business time for all events
5. **ACID Guarantees** - All succeed or all fail

---

## Event Routing

### Wildcard Pattern Matching

**Handler Registration**:

```java
@Component
public class TradeEventHandler {

    // Handle all trading events
    @EventPattern("trading.*.*.*")
    public void handleTradingEvent(CloudEvent event) {
        log.info("Trading event: {}", event.getType());
    }

    // Handle high-priority trades only
    @EventPattern("trading.*.*.*.high")
    public void handleHighPriorityTrade(CloudEvent event) {
        log.warn("HIGH PRIORITY TRADE: {}", event.getId());
        // Alert trading desk, risk team
    }

    // Handle trade failures
    @EventPattern("trading.*.*.failed")
    public void handleTradeFailure(CloudEvent event) {
        log.error("TRADE FAILED: {}", event.getId());
        // Create exception ticket
    }
}
```

**Cross-Domain Exception Handling**:

```java
@Component
public class ExceptionEventHandler {

    // Handle ALL failures across ALL domains
    @EventPattern("*.*.*.failed")
    public void handleFailure(CloudEvent event) {
        String domain = extractDomain(event.getType());

        log.error("FAILURE: domain={}, type={}", domain, event.getType());

        // Record in exception management system
        // Create ticket in workflow system
        // Alert operations team
    }
}
```

**Pattern Examples**:
- `trading.*.*.*` - All trading events
- `*.settlement.*` - All settlement events (any domain)
- `*.*.*.failed` - All failures (any domain, any action)
- `regulatory.*.*` - All regulatory events
- `*.*.*.*.high` - All high-priority events

---

## Bi-Temporal Queries

### Regulatory Audit Queries

**Query 1: What did we know at a specific time?**

```java
public CompletableFuture<TradeAuditReport> getTradeAsKnownAt(
        String tradeId,
        Instant systemTime) {

    return tradingEventStore.query(EventQuery.asOfSystemTime(systemTime))
        .thenApply(events -> events.stream()
            .filter(e -> tradeId.equals(extractTradeId(e)))
            .map(BiTemporalEvent::getPayload)
            .collect(Collectors.toList())
        )
        .thenApply(tradeEvents -> new TradeAuditReport(
            tradeId,
            systemTime,
            tradeEvents,
            "System knowledge as of " + systemTime
        ));
}
```

**Use Case**: Regulatory investigation - "Show me what we knew about trade TRD-12345 at 5pm on October 5th"

**Query 2: What events were valid during a time range?**

```java
public CompletableFuture<List<CloudEvent>> getTradesValidBetween(
        Instant fromTime,
        Instant toTime) {

    return tradingEventStore.query(EventQuery.validTimeBetween(fromTime, toTime))
        .thenApply(events -> events.stream()
            .map(BiTemporalEvent::getPayload)
            .collect(Collectors.toList())
        );
}
```

**Use Case**: Daily reporting - "Show me all trades that occurred between 9am and 5pm on October 7th"

**Query 3: Complete workflow audit trail**

```java
public CompletableFuture<WorkflowAuditTrail> getWorkflowAuditTrail(
        String correlationId) {

    // Query all 5 event stores in parallel
    CompletableFuture<List<CloudEvent>> tradingFuture =
        queryByCorrelation(tradingEventStore, correlationId);
    CompletableFuture<List<CloudEvent>> settlementFuture =
        queryByCorrelation(settlementEventStore, correlationId);
    CompletableFuture<List<CloudEvent>> cashFuture =
        queryByCorrelation(cashEventStore, correlationId);
    CompletableFuture<List<CloudEvent>> positionFuture =
        queryByCorrelation(positionEventStore, correlationId);
    CompletableFuture<List<CloudEvent>> regulatoryFuture =
        queryByCorrelation(regulatoryEventStore, correlationId);

    return CompletableFuture.allOf(
        tradingFuture, settlementFuture, cashFuture,
        positionFuture, regulatoryFuture
    ).thenApply(v -> {
        List<CloudEvent> allEvents = new ArrayList<>();
        allEvents.addAll(tradingFuture.join());
        allEvents.addAll(settlementFuture.join());
        allEvents.addAll(cashFuture.join());
        allEvents.addAll(positionFuture.join());
        allEvents.addAll(regulatoryFuture.join());

        // Sort by valid time
        allEvents.sort(Comparator.comparing(e ->
            CloudEventExtensions.getValidTime(e)));

        return new WorkflowAuditTrail(
            correlationId,
            allEvents,
            buildCausationGraph(allEvents)
        );
    });
}
```

**Use Case**: Complete audit - "Show me every event related to workflow-abc-123 across all domains"


