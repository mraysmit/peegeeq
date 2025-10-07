# Example 9: Financial Services Event Fabric - Implementation Plan

**Example Name**: `springboot-financial-fabric`
**Status**: 📋 **PLANNED**
**Phase**: Advanced Integration (Phase 5)
**Estimated Effort**: 18-24 hours
**Created**: 2025-10-07

---

## Executive Summary

This implementation plan details the creation of a comprehensive financial services event fabric example demonstrating cross-domain event processing using the `{entity}.{action}.{state}` naming pattern, CloudEvents integration, bi-temporal event storage, and multi-domain transaction coordination.

**Core Scenario**: End-to-end equity trade lifecycle flowing through Trading → Custody → Treasury → Position Management → Regulatory Reporting domains.

---

## Business Context

### Use Case: Equity Trade Lifecycle

**Scenario**: A trader executes an equity trade for 1,000 shares of AAPL at $175.50 through BROKER-XYZ.

**Event Flow**:
1. **Trading Domain** - Trade captured and confirmed
2. **Custody Domain** - Settlement instruction generated and submitted
3. **Treasury Domain** - Cash movement calculated and recorded
4. **Position Management** - Position updated and reconciled
5. **Regulatory Domain** - Transaction reported (MiFID II/EMIR compliance)

**Key Requirements**:
- All events follow `{entity}.{action}.{state}` naming pattern
- CloudEvents format for interoperability
- Bi-temporal storage for audit trail
- Correlation tracking across domains
- Causation tracking for event lineage
- Single transaction coordination (ACID guarantees)
- Regulatory compliance (MiFID II, EMIR, SOX)

---

## Architecture Overview

### Event Naming Pattern

**Format**: `{entity}.{action}.{state}`

**Examples**:
```
trading.equities.capture.completed
trading.equities.confirmation.matched
instruction.settlement.submitted
instruction.settlement.confirmed
cash.movement.completed
position.update.completed
position.reconciliation.completed
regulatory.transaction.reported
```

**Pattern Components**:
- **Entity**: Business domain object (trade, instruction, cash, position, regulatory)
- **Action**: Business operation (capture, confirmation, settlement, movement, update, reconciliation)
- **State**: Outcome (completed, matched, submitted, confirmed, failed, rejected)

### CloudEvents Structure

**Standard CloudEvents v1.0 Format**:
```json
{
  "specversion": "1.0",
  "type": "com.fincorp.trading.equities.capture.completed.v1",
  "source": "trading-system",
  "id": "evt-12345-abc",
  "time": "2025-10-07T14:30:00Z",
  "datacontenttype": "application/json",
  "data": { ... },

  "correlationid": "workflow-abc-123",
  "causationid": "evt-parent-456",
  "validtime": "2025-10-07T14:30:00Z",
  "traceparent": "00-trace-id-span-id-01"
}
```

**Custom Extensions**:
- `correlationid` - Workflow identifier (links all events in business transaction)
- `causationid` - Parent event identifier (event that caused this event)
- `validtime` - Business time (when event occurred in real world)
- `traceparent` - W3C Trace Context for distributed tracing

### Multi-Domain Event Stores

**Five Domain-Specific Event Stores**:

1. **Trading Event Store** (`trading_events`)
   - Trade capture, confirmation, amendments, cancellations
   - Event types: `trading.{asset-class}.{action}.{state}`

2. **Settlement Event Store** (`settlement_events`)
   - Settlement instructions, matching, confirmation, failures
   - Event types: `instruction.settlement.{state}`

3. **Cash Event Store** (`cash_events`)
   - Cash movements, liquidity checks, funding
   - Event types: `cash.{action}.{state}`

4. **Position Event Store** (`position_events`)
   - Position updates, reconciliation, breaks
   - Event types: `position.{action}.{state}`

5. **Regulatory Event Store** (`regulatory_events`)
   - Transaction reporting, compliance events
   - Event types: `regulatory.{action}.{state}`

### Event Routing Patterns

**Wildcard Pattern Matching**:
```java
// High-priority trades (any asset class)
@EventPattern("trading.*.capture.*.high")

// All settlement events
@EventPattern("instruction.settlement.*")

// Cross-domain failures
@EventPattern("*.*.*.failed")

// Regulatory events (all domains)
@EventPattern("regulatory.*.*")
```

---

## Technical Implementation

### Phase 1: Project Setup (3-4 hours)

#### 1.1 Create Module Structure

**Directory Structure**:
```
peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootfinancialfabric/
├── SpringBootFinancialFabricApplication.java
├── config/
│   ├── FinancialFabricConfig.java
│   ├── CloudEventsConfig.java
│   ├── EventRoutingConfig.java
│   └── FinancialFabricProperties.java
├── events/
│   ├── TradeEvent.java
│   ├── SettlementInstructionEvent.java
│   ├── CashMovementEvent.java
│   ├── PositionUpdateEvent.java
│   └── RegulatoryReportEvent.java
├── cloudevents/
│   ├── CloudEventBuilder.java
│   ├── CloudEventExtensions.java
│   └── CloudEventValidator.java
├── services/
│   ├── TradeWorkflowService.java
│   ├── TradeCaptureService.java
│   ├── SettlementService.java
│   ├── CashManagementService.java
│   ├── PositionService.java
│   └── RegulatoryReportingService.java
├── handlers/
│   ├── TradeEventHandler.java
│   ├── SettlementEventHandler.java
│   ├── CashEventHandler.java
│   ├── PositionEventHandler.java
│   └── ExceptionEventHandler.java
├── query/
│   ├── TradeHistoryQueryService.java
│   ├── PositionReconService.java
│   └── RegulatoryQueryService.java
├── controllers/
│   ├── TradeController.java
│   ├── QueryController.java
│   └── MonitoringController.java
└── model/
    ├── TradeRequest.java
    ├── WorkflowResult.java
    ├── TradeAuditReport.java
    ├── PositionSnapshot.java
    └── WorkflowAuditTrail.java
```

#### 1.2 Maven Dependencies

**Add to `pom.xml`**:
```xml
<dependencies>
    <!-- PeeGeeQ Core -->
    <dependency>
        <groupId>dev.mars.peegeeq</groupId>
        <artifactId>peegeeq-api</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars.peegeeq</groupId>
        <artifactId>peegeeq-outbox</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.mars.peegeeq</groupId>
        <artifactId>peegeeq-bitemporal</artifactId>
    </dependency>

    <!-- CloudEvents SDK -->
    <dependency>
        <groupId>io.cloudevents</groupId>
        <artifactId>cloudevents-core</artifactId>
        <version>2.5.0</version>
    </dependency>
    <dependency>
        <groupId>io.cloudevents</groupId>
        <artifactId>cloudevents-json-jackson</artifactId>
        <version>2.5.0</version>
    </dependency>

    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### 1.3 Configuration Files

**application-springboot-financial-fabric.yml**:
```yaml
spring:
  application:
    name: springboot-financial-fabric

peegeeq:
  client-id: financial-fabric-main
  database:
    host: localhost
    port: 5432
    database: peegeeq_financial_fabric
    username: peegeeq
    password: peegeeq

financial-fabric:
  event-stores:
    trading:
      table-name: trading_events
      enabled: true
    settlement:
      table-name: settlement_events
      enabled: true
    cash:
      table-name: cash_events
      enabled: true
    position:
      table-name: position_events
      enabled: true
    regulatory:
      table-name: regulatory_events
      enabled: true

  cloudevents:
    source: trading-system
    spec-version: "1.0"
    data-content-type: application/json

  routing:
    high-priority-pattern: "*.*.*.*.high"
    failure-pattern: "*.*.*.failed"
    regulatory-pattern: "regulatory.*.*"

logging:
  level:
    dev.mars.peegeeq: DEBUG
```

---

### Phase 2: Event Models & CloudEvents (3-4 hours)

#### 2.1 Event Model Classes

**TradeEvent.java**:
```java
public class TradeEvent {
    private final String tradeId;
    private final String instrument;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final String counterparty;
    private final String assetClass;  // EQUITIES, FX, BONDS
    private final String tradeType;   // BUY, SELL

#### 3.3 Domain-Specific Services

**TradeCaptureService.java**:
```java
@Service
public class TradeCaptureService {

    private final EventStore<CloudEvent> tradingEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<CloudEvent> captureTrade(
            TradeEvent tradeEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

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
                Map.of("correlationId", correlationId, "priority", "normal"),
                correlationId,
                tradeEvent.getTradeId(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }

    public CompletableFuture<CloudEvent> confirmTrade(
            String tradeId,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        // Create confirmation event
        Map<String, Object> confirmationData = Map.of(
            "tradeId", tradeId,
            "status", "MATCHED",
            "confirmationTime", validTime.toString()
        );

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.trading.equities.confirmation.matched.v1",
            confirmationData,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) tradingEventStore)
            .appendInTransaction(
                "trading.equities.confirmation.matched",
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId),
                correlationId,
                tradeId,
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }
}
```

**SettlementService.java**:
```java
@Service
public class SettlementService {

    private final EventStore<CloudEvent> settlementEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<CloudEvent> submitSettlementInstruction(
            SettlementInstructionEvent instructionEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.instruction.settlement.submitted.v1",
            instructionEvent,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) settlementEventStore)
            .appendInTransaction(
                "instruction.settlement.submitted",
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId, "custodian", instructionEvent.getCustodian()),
                correlationId,
                instructionEvent.getInstructionId(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }

    public CompletableFuture<CloudEvent> confirmSettlement(
            String instructionId,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        Map<String, Object> confirmationData = Map.of(
            "instructionId", instructionId,
            "status", "CONFIRMED",
            "confirmationTime", validTime.toString()
        );

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.instruction.settlement.confirmed.v1",
            confirmationData,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) settlementEventStore)
            .appendInTransaction(
                "instruction.settlement.confirmed",
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId),
                correlationId,
                instructionId,
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }
}
```

**CashManagementService.java**:
```java
@Service
public class CashManagementService {

    private final EventStore<CloudEvent> cashEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<CloudEvent> recordCashMovement(
            CashMovementEvent cashEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.cash.movement.completed.v1",
            cashEvent,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) cashEventStore)
            .appendInTransaction(
                "cash.movement.completed",
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId, "currency", cashEvent.getCurrency()),
                correlationId,
                cashEvent.getMovementId(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }

    public CompletableFuture<BigDecimal> checkLiquidity(
            String account,
            String currency,
            Instant asOf) {

        // Query cash events to calculate available liquidity
        return cashEventStore.query(EventQuery.all())
            .thenApply(events -> {
                return events.stream()
                    .filter(e -> {
                        try {
                            CashMovementEvent cash = CloudEventExtensions.getPayload(
                                e.getPayload(), CashMovementEvent.class, new ObjectMapper());
                            return account.equals(cash.getAccount()) &&
                                   currency.equals(cash.getCurrency());
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .map(e -> {
                        try {
                            CashMovementEvent cash = CloudEventExtensions.getPayload(
                                e.getPayload(), CashMovementEvent.class, new ObjectMapper());
                            return "CREDIT".equals(cash.getMovementType()) ?
                                cash.getAmount() : cash.getAmount().negate();
                        } catch (IOException ex) {
                            return BigDecimal.ZERO;
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            });
    }
}
```

**PositionService.java**:
```java
@Service
public class PositionService {

    private final EventStore<CloudEvent> positionEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<CloudEvent> updatePosition(
            PositionUpdateEvent positionEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.position.update.completed.v1",
            positionEvent,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) positionEventStore)
            .appendInTransaction(
                "position.update.completed",
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId, "instrument", positionEvent.getInstrument()),
                correlationId,
                positionEvent.getUpdateId(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }

    public CompletableFuture<PositionSnapshot> getPositionAsOf(
            String instrument,
            String account,
            Instant validTime) {

        // Query position events up to validTime
        return positionEventStore.query(EventQuery.all())
            .thenApply(events -> {
                BigDecimal position = events.stream()
                    .filter(e -> {
                        Instant eventValidTime = CloudEventExtensions.getValidTime(e.getPayload());
                        return eventValidTime != null && !eventValidTime.isAfter(validTime);
                    })
                    .filter(e -> {
                        try {
                            PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                                e.getPayload(), PositionUpdateEvent.class, new ObjectMapper());
                            return instrument.equals(pos.getInstrument()) &&
                                   account.equals(pos.getAccount());
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .map(e -> {
                        try {
                            PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                                e.getPayload(), PositionUpdateEvent.class, new ObjectMapper());
                            return pos.getQuantityChange();
                        } catch (IOException ex) {
                            return BigDecimal.ZERO;
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                return new PositionSnapshot(instrument, account, position, validTime);
            });
    }

    public CompletableFuture<CloudEvent> reconcilePosition(
            String instrument,
            String account,
            BigDecimal expectedPosition,
            BigDecimal actualPosition,
            String correlationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        Map<String, Object> reconData = Map.of(
            "instrument", instrument,
            "account", account,
            "expectedPosition", expectedPosition.toString(),
            "actualPosition", actualPosition.toString(),
            "difference", actualPosition.subtract(expectedPosition).toString(),
            "status", expectedPosition.equals(actualPosition) ? "MATCHED" : "BREAK"
        );

        String eventType = expectedPosition.equals(actualPosition) ?
            "com.fincorp.position.reconciliation.completed.v1" :
            "com.fincorp.position.reconciliation.failed.v1";

        String eventName = expectedPosition.equals(actualPosition) ?
            "position.reconciliation.completed" :
            "position.reconciliation.failed";

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            eventType,
            reconData,
            correlationId,
            null,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) positionEventStore)
            .appendInTransaction(
                eventName,
                cloudEvent,
                validTime,
                Map.of("correlationId", correlationId, "instrument", instrument),
                correlationId,
                UUID.randomUUID().toString(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }
}
```

**RegulatoryReportingService.java**:
```java
@Service
public class RegulatoryReportingService {

    private final EventStore<CloudEvent> regulatoryEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CompletableFuture<CloudEvent> reportTransaction(
            RegulatoryReportEvent reportEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) throws JsonProcessingException {

        CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
            "com.fincorp.regulatory.transaction.reported.v1",
            reportEvent,
            correlationId,
            causationId,
            validTime
        );

        return ((PgBiTemporalEventStore<CloudEvent>) regulatoryEventStore)
            .appendInTransaction(
                "regulatory.transaction.reported",
                cloudEvent,
                validTime,
                Map.of(
                    "correlationId", correlationId,
                    "reportType", reportEvent.getReportType(),
                    "regime", reportEvent.getRegulatoryRegime()
                ),
                correlationId,
                reportEvent.getReportId(),
                connection
            )
            .thenApply(biTemporalEvent -> cloudEvent);
    }

    public CompletableFuture<List<CloudEvent>> getRegulatoryReports(
            String reportType,
            Instant fromTime,
            Instant toTime) {

        return regulatoryEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> {
                    Instant validTime = CloudEventExtensions.getValidTime(e.getPayload());
                    return validTime != null &&
                           !validTime.isBefore(fromTime) &&
                           !validTime.isAfter(toTime);
                })
                .filter(e -> {
                    try {
                        RegulatoryReportEvent report = CloudEventExtensions.getPayload(
                            e.getPayload(), RegulatoryReportEvent.class, new ObjectMapper());
                        return reportType == null || reportType.equals(report.getReportType());
                    } catch (IOException ex) {
                        return false;
                    }
                })
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.toList())
            );
    }
}
```

---

### Phase 4: Event Routing & Handlers (2-3 hours)

#### 4.1 Event Handler Pattern

**TradeEventHandler.java**:
```java
@Component
public class TradeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeEventHandler.class);

    private final AtomicLong tradesProcessed = new AtomicLong(0);
    private final AtomicLong highPriorityTrades = new AtomicLong(0);

    // Handle all trading events
    @EventPattern("trading.*.*.*")
    public void handleTradingEvent(CloudEvent event) {
        log.info("Trading event received: type={}, id={}, correlationId={}",
            event.getType(),
            event.getId(),
            CloudEventExtensions.getCorrelationId(event));

        tradesProcessed.incrementAndGet();
    }

    // Handle high-priority trades
    @EventPattern("trading.*.*.*.high")
    public void handleHighPriorityTrade(CloudEvent event) {
        log.warn("HIGH PRIORITY TRADE: type={}, id={}",
            event.getType(), event.getId());

        highPriorityTrades.incrementAndGet();

        // Alert trading desk, risk team
        // Escalate to senior management if needed
    }

    // Handle trade failures
    @EventPattern("trading.*.*.failed")
    public void handleTradeFailure(CloudEvent event) {
        log.error("TRADE FAILED: type={}, id={}, correlationId={}",
            event.getType(),
            event.getId(),
            CloudEventExtensions.getCorrelationId(event));

        // Create exception ticket
        // Notify operations team
    }

    public long getTradesProcessed() {
        return tradesProcessed.get();
    }

    public long getHighPriorityTrades() {
        return highPriorityTrades.get();
    }
}
```

**SettlementEventHandler.java**:
```java
@Component
public class SettlementEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventHandler.class);

    private final AtomicLong settlementsProcessed = new AtomicLong(0);
    private final AtomicLong settlementFailures = new AtomicLong(0);

    // Handle all settlement events
    @EventPattern("instruction.settlement.*")
    public void handleSettlementEvent(CloudEvent event) {
        log.info("Settlement event received: type={}, id={}",
            event.getType(), event.getId());

        settlementsProcessed.incrementAndGet();
    }

    // Handle settlement failures
    @EventPattern("instruction.settlement.failed")
    public void handleSettlementFailure(CloudEvent event) {
        log.error("SETTLEMENT FAILED: type={}, id={}, correlationId={}",
            event.getType(),
            event.getId(),
            CloudEventExtensions.getCorrelationId(event));

        settlementFailures.incrementAndGet();

        // Create exception ticket
        // Notify custody operations
        // Investigate with custodian
    }

    public long getSettlementsProcessed() {
        return settlementsProcessed.get();
    }

    public long getSettlementFailures() {
        return settlementFailures.get();
    }
}
```

**ExceptionEventHandler.java** (Cross-domain):
```java
@Component
public class ExceptionEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ExceptionEventHandler.class);

    private final Map<String, AtomicLong> failuresByDomain = new ConcurrentHashMap<>();

    // Handle all failures across all domains
    @EventPattern("*.*.*.failed")
    public void handleFailure(CloudEvent event) {
        String eventType = event.getType();
        String domain = extractDomain(eventType);

        log.error("FAILURE DETECTED: domain={}, type={}, id={}, correlationId={}",
            domain,
            eventType,
            event.getId(),
            CloudEventExtensions.getCorrelationId(event));

        failuresByDomain.computeIfAbsent(domain, k -> new AtomicLong(0))
            .incrementAndGet();

        // Record in exception management system
        // Create ticket in workflow system
        // Alert operations team
        // Trigger automated remediation if applicable
    }

    private String extractDomain(String eventType) {
        // Extract domain from event type
        // e.g., "com.fincorp.trading.equities.capture.failed.v1" -> "trading"
        String[] parts = eventType.split("\\.");
        return parts.length > 2 ? parts[2] : "unknown";
    }

    public Map<String, Long> getFailuresByDomain() {
        return failuresByDomain.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get()
            ));
    }
}
```

---

### Phase 5: Bi-Temporal Queries (2-3 hours)

#### 5.1 Query Services

**TradeHistoryQueryService.java**:
```java
@Service
public class TradeHistoryQueryService {

    private final EventStore<CloudEvent> tradingEventStore;
    private final ObjectMapper objectMapper;

    /**
     * Get trade as it was known at a specific system time (regulatory audit)
     */
    public CompletableFuture<TradeAuditReport> getTradeAsKnownAt(
            String tradeId,
            Instant systemTime) {

        return tradingEventStore.query(EventQuery.asOfSystemTime(systemTime))
            .thenApply(events -> {
                List<CloudEvent> tradeEvents = events.stream()
                    .filter(e -> {
                        try {
                            TradeEvent trade = CloudEventExtensions.getPayload(
                                e.getPayload(), TradeEvent.class, objectMapper);
                            return tradeId.equals(trade.getTradeId());
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .map(BiTemporalEvent::getPayload)
                    .collect(Collectors.toList());

                return new TradeAuditReport(
                    tradeId,
                    systemTime,
                    tradeEvents,
                    "System knowledge as of " + systemTime
                );
            });
    }

    /**
     * Get all trades valid within a time range (business time query)
     */
    public CompletableFuture<List<CloudEvent>> getTradesValidBetween(
            Instant fromTime,
            Instant toTime) {

        return tradingEventStore.query(EventQuery.validTimeBetween(fromTime, toTime))
            .thenApply(events -> events.stream()
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.toList())
            );
    }

    /**
     * Get complete audit trail including all corrections
     */
    public CompletableFuture<List<CloudEvent>> getCompleteTradeHistory(String tradeId) {

        return tradingEventStore.query(EventQuery.includeCorrections())
            .thenApply(events -> events.stream()
                .filter(e -> {
                    try {
                        TradeEvent trade = CloudEventExtensions.getPayload(
                            e.getPayload(), TradeEvent.class, objectMapper);
                        return tradeId.equals(trade.getTradeId());
                    } catch (IOException ex) {
                        return false;
                    }
                })
                .sorted(Comparator.comparing(e -> e.getTransactionTime()))
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.toList())
            );
    }
}
```

**PositionReconService.java**:
```java
@Service
public class PositionReconService {

    private final EventStore<CloudEvent> positionEventStore;
    private final ObjectMapper objectMapper;

    /**
     * Reconcile position as of a specific business date
     */
    public CompletableFuture<PositionReconciliationReport> reconcilePositionAsOf(
            String instrument,
            String account,
            Instant businessDate,
            BigDecimal externalPosition) {

        return positionEventStore.query(EventQuery.validTimeBetween(Instant.MIN, businessDate))
            .thenApply(events -> {
                BigDecimal calculatedPosition = events.stream()
                    .filter(e -> {
                        try {
                            PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                                e.getPayload(), PositionUpdateEvent.class, objectMapper);
                            return instrument.equals(pos.getInstrument()) &&
                                   account.equals(pos.getAccount());
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .map(e -> {
                        try {
                            PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                                e.getPayload(), PositionUpdateEvent.class, objectMapper);
                            return pos.getQuantityChange();
                        } catch (IOException ex) {
                            return BigDecimal.ZERO;
                        }
                    })
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

    /**
     * Get position movement history for a date range
     */
    public CompletableFuture<List<PositionMovement>> getPositionMovements(
            String instrument,
            String account,
            Instant fromDate,
            Instant toDate) {

        return positionEventStore.query(EventQuery.validTimeBetween(fromDate, toDate))
            .thenApply(events -> events.stream()
                .filter(e -> {
                    try {
                        PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                            e.getPayload(), PositionUpdateEvent.class, objectMapper);
                        return instrument.equals(pos.getInstrument()) &&
                               account.equals(pos.getAccount());
                    } catch (IOException ex) {
                        return false;
                    }
                })
                .map(e -> {
                    try {
                        PositionUpdateEvent pos = CloudEventExtensions.getPayload(
                            e.getPayload(), PositionUpdateEvent.class, objectMapper);
                        return new PositionMovement(
                            pos.getUpdateId(),
                            pos.getTradeId(),
                            pos.getInstrument(),
                            pos.getQuantityChange(),
                            pos.getNewPosition(),
                            pos.getUpdateTime()
                        );
                    } catch (IOException ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
            );
    }
}
```

**RegulatoryQueryService.java**:
```java
@Service
public class RegulatoryQueryService {

    private final EventStore<CloudEvent> tradingEventStore;
    private final EventStore<CloudEvent> settlementEventStore;
    private final EventStore<CloudEvent> cashEventStore;
    private final EventStore<CloudEvent> positionEventStore;
    private final EventStore<CloudEvent> regulatoryEventStore;
    private final ObjectMapper objectMapper;

    /**
     * Get complete workflow audit trail by correlation ID
     */
    public CompletableFuture<WorkflowAuditTrail> getWorkflowAuditTrail(
            String correlationId) {

        // Query all event stores in parallel
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

            // Sort by time
            allEvents.sort(Comparator.comparing(e ->
                CloudEventExtensions.getValidTime(e)));

            return new WorkflowAuditTrail(
                correlationId,
                allEvents,
                buildCausationGraph(allEvents)
            );
        });
    }

    private CompletableFuture<List<CloudEvent>> queryByCorrelation(
            EventStore<CloudEvent> eventStore,
            String correlationId) {

        return eventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(
                    CloudEventExtensions.getCorrelationId(e.getPayload())))
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.toList())
            );
    }

    private Map<String, String> buildCausationGraph(List<CloudEvent> events) {
        // Build parent-child relationship map
        return events.stream()
            .filter(e -> CloudEventExtensions.getCausationId(e) != null)
            .collect(Collectors.toMap(
                CloudEvent::getId,
                e -> CloudEventExtensions.getCausationId(e)
            ));
    }

    /**
     * Generate MiFID II transaction report
     */
    public CompletableFuture<MiFIDIIReport> generateMiFIDIIReport(
            Instant reportingDate) {

        Instant startOfDay = reportingDate.truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);

        return tradingEventStore.query(EventQuery.validTimeBetween(startOfDay, endOfDay))
            .thenApply(events -> {
                List<MiFIDIITransaction> transactions = events.stream()
                    .filter(e -> e.getPayload().getType().contains("capture.completed"))
                    .map(e -> {
                        try {
                            TradeEvent trade = CloudEventExtensions.getPayload(
                                e.getPayload(), TradeEvent.class, objectMapper);
                            return new MiFIDIITransaction(
                                trade.getTradeId(),
                                trade.getInstrument(),
                                trade.getQuantity(),
                                trade.getPrice(),
                                trade.getTradeTime(),
                                "EQUITY",
                                "XNYS"  // Trading venue
                            );
                        } catch (IOException ex) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

                return new MiFIDIIReport(
                    reportingDate,
                    transactions,
                    "MiFID II Transaction Report"
                );
            });
    }
}
```
---

### Phase 6: REST Controllers (2-3 hours)

#### 6.1 Trade Controller

**TradeController.java**:
```java
@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeWorkflowService tradeWorkflowService;
    private final TradeHistoryQueryService tradeHistoryQueryService;

    @PostMapping
    public CompletableFuture<ResponseEntity<WorkflowResult>> executeTrade(
            @RequestBody TradeRequest request) {

        return tradeWorkflowService.executeTradeWorkflow(request)
            .thenApply(result -> ResponseEntity.ok(result))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }

    @GetMapping("/{tradeId}/history")
    public CompletableFuture<ResponseEntity<List<CloudEvent>>> getTradeHistory(
            @PathVariable String tradeId) {

        return tradeHistoryQueryService.getCompleteTradeHistory(tradeId)
            .thenApply(events -> ResponseEntity.ok(events))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }

    @GetMapping("/{tradeId}/audit")
    public CompletableFuture<ResponseEntity<TradeAuditReport>> getTradeAudit(
            @PathVariable String tradeId,
            @RequestParam String systemTime) {

        Instant instant = Instant.parse(systemTime);

        return tradeHistoryQueryService.getTradeAsKnownAt(tradeId, instant)
            .thenApply(report -> ResponseEntity.ok(report))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }
}
```

#### 6.2 Query Controller

**QueryController.java**:
```java
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final RegulatoryQueryService regulatoryQueryService;
    private final PositionReconService positionReconService;

    @GetMapping("/workflow/{correlationId}")
    public CompletableFuture<ResponseEntity<WorkflowAuditTrail>> getWorkflowAudit(
            @PathVariable String correlationId) {

        return regulatoryQueryService.getWorkflowAuditTrail(correlationId)
            .thenApply(trail -> ResponseEntity.ok(trail))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }

    @GetMapping("/position/{instrument}/{account}")
    public CompletableFuture<ResponseEntity<PositionSnapshot>> getPosition(
            @PathVariable String instrument,
            @PathVariable String account,
            @RequestParam String asOf) {

        Instant instant = Instant.parse(asOf);

        return positionReconService.getPositionAsOf(instrument, account, instant)
            .thenApply(snapshot -> ResponseEntity.ok(snapshot))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }

    @PostMapping("/position/reconcile")
    public CompletableFuture<ResponseEntity<PositionReconciliationReport>> reconcilePosition(
            @RequestBody PositionReconciliationRequest request) {

        return positionReconService.reconcilePositionAsOf(
            request.getInstrument(),
            request.getAccount(),
            request.getBusinessDate(),
            request.getExternalPosition()
        )
        .thenApply(report -> ResponseEntity.ok(report))
        .exceptionally(ex -> ResponseEntity.status(500).build());
    }

    @GetMapping("/regulatory/mifid2")
    public CompletableFuture<ResponseEntity<MiFIDIIReport>> getMiFIDIIReport(
            @RequestParam String reportingDate) {

        Instant instant = Instant.parse(reportingDate);

        return regulatoryQueryService.generateMiFIDIIReport(instant)
            .thenApply(report -> ResponseEntity.ok(report))
            .exceptionally(ex -> ResponseEntity.status(500).build());
    }
}
```

#### 6.3 Monitoring Controller

**MonitoringController.java**:
```java
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final TradeEventHandler tradeEventHandler;
    private final SettlementEventHandler settlementEventHandler;
    private final ExceptionEventHandler exceptionEventHandler;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        metrics.put("tradesProcessed", tradeEventHandler.getTradesProcessed());
        metrics.put("highPriorityTrades", tradeEventHandler.getHighPriorityTrades());
        metrics.put("settlementsProcessed", settlementEventHandler.getSettlementsProcessed());
        metrics.put("settlementFailures", settlementEventHandler.getSettlementFailures());
        metrics.put("failuresByDomain", exceptionEventHandler.getFailuresByDomain());

        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> getHealth() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(health);
    }
}
```

---

### Phase 7: Testing (3-4 hours)

#### 7.1 Test Structure

**SpringBootFinancialFabricApplicationTest.java**:
```java
@SpringBootTest
@Testcontainers
public class SpringBootFinancialFabricApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("peegeeq_financial_fabric_test")
        .withUsername("peegeeq")
        .withPassword("peegeeq");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", postgres::getFirstMappedPort);
        registry.add("peegeeq.database.database", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
    }

    @Autowired
    private TradeWorkflowService tradeWorkflowService;

    @Autowired
    private RegulatoryQueryService regulatoryQueryService;

    @Autowired
    private PositionReconService positionReconService;

    @Test
    public void testCompleteTradeWorkflow() throws Exception {
        // Create trade request
        TradeRequest request = new TradeRequest(
            "TRD-001",
            "AAPL",
            new BigDecimal("1000"),
            new BigDecimal("175.50"),
            "BROKER-XYZ",
            "BUY",
            Instant.now()
        );

        // Execute workflow
        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Verify result
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertNotNull(result.getCorrelationId());
        assertEquals(5, result.getEventTypes().size());

        // Verify all event types
        assertTrue(result.getEventTypes().contains("trading.equities.capture.completed"));
        assertTrue(result.getEventTypes().contains("instruction.settlement.submitted"));
        assertTrue(result.getEventTypes().contains("cash.movement.completed"));
        assertTrue(result.getEventTypes().contains("position.update.completed"));
        assertTrue(result.getEventTypes().contains("regulatory.transaction.reported"));
    }

    @Test
    public void testEventNamingPattern() throws Exception {
        TradeRequest request = new TradeRequest(
            "TRD-002",
            "MSFT",
            new BigDecimal("500"),
            new BigDecimal("380.25"),
            "BROKER-ABC",
            "SELL",
            Instant.now()
        );

        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Verify event naming pattern: {entity}.{action}.{state}
        for (String eventType : result.getEventTypes()) {
            String[] parts = eventType.split("\\.");
            assertTrue(parts.length >= 3,
                "Event type should follow {entity}.{action}.{state} pattern: " + eventType);
        }
    }

    @Test
    public void testCloudEventsFormat() throws Exception {
        TradeRequest request = new TradeRequest(
            "TRD-003",
            "GOOGL",
            new BigDecimal("200"),
            new BigDecimal("140.75"),
            "BROKER-DEF",
            "BUY",
            Instant.now()
        );

        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Query workflow audit trail
        WorkflowAuditTrail trail = regulatoryQueryService
            .getWorkflowAuditTrail(result.getCorrelationId())
            .get(10, TimeUnit.SECONDS);

        // Verify CloudEvents format
        assertFalse(trail.getEvents().isEmpty());

        for (CloudEvent event : trail.getEvents()) {
            // Verify CloudEvents v1.0 spec
            assertEquals("1.0", event.getSpecVersion().toString());
            assertNotNull(event.getId());
            assertNotNull(event.getType());
            assertNotNull(event.getSource());
            assertNotNull(event.getTime());

            // Verify custom extensions
            assertNotNull(CloudEventExtensions.getCorrelationId(event));
            assertNotNull(CloudEventExtensions.getValidTime(event));
        }
    }

    @Test
    public void testCorrelationTracking() throws Exception {
        TradeRequest request = new TradeRequest(
            "TRD-004",
            "AMZN",
            new BigDecimal("100"),
            new BigDecimal("145.30"),
            "BROKER-GHI",
            "BUY",
            Instant.now()
        );

        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Query by correlation ID
        WorkflowAuditTrail trail = regulatoryQueryService
            .getWorkflowAuditTrail(result.getCorrelationId())
            .get(10, TimeUnit.SECONDS);

        // Verify all events have same correlation ID
        for (CloudEvent event : trail.getEvents()) {
            assertEquals(result.getCorrelationId(),
                CloudEventExtensions.getCorrelationId(event));
        }

        // Verify events from all 5 domains
        Set<String> domains = trail.getEvents().stream()
            .map(e -> e.getType().split("\\.")[2])
            .collect(Collectors.toSet());

        assertTrue(domains.contains("trading"));
        assertTrue(domains.contains("instruction"));
        assertTrue(domains.contains("cash"));
        assertTrue(domains.contains("position"));
        assertTrue(domains.contains("regulatory"));
    }

    @Test
    public void testCausationTracking() throws Exception {
        TradeRequest request = new TradeRequest(
            "TRD-005",
            "TSLA",
            new BigDecimal("50"),
            new BigDecimal("245.80"),
            "BROKER-JKL",
            "SELL",
            Instant.now()
        );

        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Query workflow audit trail
        WorkflowAuditTrail trail = regulatoryQueryService
            .getWorkflowAuditTrail(result.getCorrelationId())
            .get(10, TimeUnit.SECONDS);

        // Verify causation graph
        Map<String, String> causationGraph = trail.getCausationGraph();
        assertFalse(causationGraph.isEmpty());

        // First event should have no causation
        CloudEvent firstEvent = trail.getEvents().get(0);
        assertNull(CloudEventExtensions.getCausationId(firstEvent));

        // Subsequent events should have causation
        for (int i = 1; i < trail.getEvents().size(); i++) {
            CloudEvent event = trail.getEvents().get(i);
            String causationId = CloudEventExtensions.getCausationId(event);
            assertNotNull(causationId, "Event " + i + " should have causation ID");
        }
    }

    @Test
    public void testBiTemporalQueries() throws Exception {
        Instant tradeTime = Instant.now();

        TradeRequest request = new TradeRequest(
            "TRD-006",
            "NVDA",
            new BigDecimal("300"),
            new BigDecimal("495.20"),
            "BROKER-MNO",
            "BUY",
            tradeTime
        );

        WorkflowResult result = tradeWorkflowService.executeTradeWorkflow(request)
            .get(10, TimeUnit.SECONDS);

        // Wait a moment
        Thread.sleep(1000);

        Instant queryTime = Instant.now();

        // Query as of system time
        TradeAuditReport auditReport = tradeHistoryQueryService
            .getTradeAsKnownAt("TRD-006", queryTime)
            .get(10, TimeUnit.SECONDS);

        assertNotNull(auditReport);
        assertFalse(auditReport.getEvents().isEmpty());

        // Query position as of trade time
        PositionSnapshot snapshot = positionReconService
            .getPositionAsOf("NVDA", "ACCOUNT-001", tradeTime.plusSeconds(60))
            .get(10, TimeUnit.SECONDS);

        assertNotNull(snapshot);
        assertEquals("NVDA", snapshot.getInstrument());
    }

    @Test
    public void testRegulatoryAudit() throws Exception {
        Instant reportingDate = Instant.now();

        // Execute multiple trades
        for (int i = 0; i < 3; i++) {
            TradeRequest request = new TradeRequest(
                "TRD-REG-" + i,
                "AAPL",
                new BigDecimal("100"),
                new BigDecimal("175.50"),
                "BROKER-XYZ",
                "BUY",
                reportingDate
            );

            tradeWorkflowService.executeTradeWorkflow(request)
                .get(10, TimeUnit.SECONDS);
        }

        // Generate MiFID II report
        MiFIDIIReport report = regulatoryQueryService
            .generateMiFIDIIReport(reportingDate)
            .get(10, TimeUnit.SECONDS);

        assertNotNull(report);
        assertTrue(report.getTransactions().size() >= 3);
    }
}
```

---

### Phase 8: Documentation (2-3 hours)

#### 8.1 README.md Structure

**README.md** sections:

1. **Overview**
   - Financial event fabric architecture
   - Multi-domain event processing
   - CloudEvents integration
   - Bi-temporal audit trail

2. **Event Naming Pattern**
   - `{entity}.{action}.{state}` explained
   - Examples from each domain
   - Pattern benefits

3. **CloudEvents Integration**
   - CloudEvents v1.0 structure
   - Custom extensions (correlationid, causationid, validtime)
   - Event serialization

4. **Multi-Domain Workflow**
   - Trade lifecycle example
   - Event flow diagram
   - Transaction coordination

5. **Bi-Temporal Queries**
   - System time queries (audit)
   - Valid time queries (business)
   - Correction handling

6. **Event Routing**
   - Wildcard pattern examples
   - Handler registration
   - Cross-domain patterns

7. **Correlation and Causation**
   - Workflow tracking
   - Event lineage
   - Causation graph

8. **API Endpoints**
   - Trade execution
   - Query endpoints
   - Monitoring endpoints

9. **Testing**
   - Running tests
   - Test coverage
   - Example scenarios

10. **Use Cases**
    - Trade lifecycle management
    - Position reconciliation
    - Regulatory reporting
    - Exception handling

---

## Implementation Checklist

### Phase 1: Project Setup ✅
- [ ] Create module directory structure
- [ ] Add Maven dependencies (PeeGeeQ, CloudEvents, Spring Boot)
- [ ] Create application.yml configuration
- [ ] Create FinancialFabricProperties class

### Phase 2: Event Models & CloudEvents ✅
- [ ] Create TradeEvent.java
- [ ] Create SettlementInstructionEvent.java
- [ ] Create CashMovementEvent.java
- [ ] Create PositionUpdateEvent.java
- [ ] Create RegulatoryReportEvent.java
- [ ] Create FinancialCloudEventBuilder.java
- [ ] Create CloudEventExtensions.java
- [ ] Create CloudEventValidator.java

### Phase 3: Multi-Domain Services ✅
- [ ] Create FinancialFabricConfig.java (5 event store beans)
- [ ] Create TradeWorkflowService.java (orchestration)
- [ ] Create TradeCaptureService.java
- [ ] Create SettlementService.java
- [ ] Create CashManagementService.java
- [ ] Create PositionService.java
- [ ] Create RegulatoryReportingService.java

### Phase 4: Event Routing & Handlers ✅
- [ ] Create TradeEventHandler.java
- [ ] Create SettlementEventHandler.java
- [ ] Create CashEventHandler.java
- [ ] Create PositionEventHandler.java
- [ ] Create ExceptionEventHandler.java (cross-domain)

### Phase 5: Bi-Temporal Queries ✅
- [ ] Create TradeHistoryQueryService.java
- [ ] Create PositionReconService.java
- [ ] Create RegulatoryQueryService.java

### Phase 6: REST Controllers ✅
- [ ] Create TradeController.java
- [ ] Create QueryController.java
- [ ] Create MonitoringController.java

### Phase 7: Testing ✅
- [ ] Create SpringBootFinancialFabricApplicationTest.java
- [ ] Test: testCompleteTradeWorkflow()
- [ ] Test: testEventNamingPattern()
- [ ] Test: testCloudEventsFormat()
- [ ] Test: testCorrelationTracking()
- [ ] Test: testCausationTracking()
- [ ] Test: testBiTemporalQueries()
- [ ] Test: testRegulatoryAudit()

### Phase 8: Documentation ✅
- [ ] Create comprehensive README.md
- [ ] Add architecture diagrams
- [ ] Add API documentation
- [ ] Add usage examples (curl commands)
- [ ] Add troubleshooting section

---

## Success Criteria

1. ✅ **Event Naming Pattern** - All events follow `{entity}.{action}.{state}`
2. ✅ **CloudEvents Format** - All events use CloudEvents v1.0 spec
3. ✅ **Multi-Domain Coordination** - Single transaction across 5 event stores
4. ✅ **Correlation Tracking** - All events linked by correlation ID
5. ✅ **Causation Tracking** - Event lineage captured
6. ✅ **Bi-Temporal Queries** - System time and valid time queries work
7. ✅ **Event Routing** - Wildcard patterns work correctly
8. ✅ **All Tests Passing** - 8/8 tests pass
9. ✅ **Documentation Complete** - README with examples
10. ✅ **Regulatory Compliance** - MiFID II report generation works

---

## Estimated Timeline

| Phase | Task | Hours |
|-------|------|-------|
| 1 | Project Setup | 3-4 |
| 2 | Event Models & CloudEvents | 3-4 |
| 3 | Multi-Domain Services | 4-5 |
| 4 | Event Routing & Handlers | 2-3 |
| 5 | Bi-Temporal Queries | 2-3 |
| 6 | REST Controllers | 2-3 |
| 7 | Testing | 3-4 |
| 8 | Documentation | 2-3 |
| **Total** | | **21-29 hours** |

---

## Dependencies

**Maven Dependencies**:
- `peegeeq-api` - Core PeeGeeQ API
- `peegeeq-outbox` - Outbox pattern
- `peegeeq-bitemporal` - Bi-temporal event store
- `cloudevents-core:2.5.0` - CloudEvents SDK
- `cloudevents-json-jackson:2.5.0` - CloudEvents JSON serialization
- `spring-boot-starter-web` - Spring Boot web
- `testcontainers-postgresql` - Testing

**External Dependencies**:
- PostgreSQL 15+ database
- Java 17+
- Maven 3.8+

---

## Related Examples

This example builds on patterns from:
- **springboot-integrated** - Outbox + bi-temporal integration
- **springboot-bitemporal-tx** - Multi-store transactions
- **springboot-priority** - Event routing patterns
- **springboot2-bitemporal** - Reactive bi-temporal

---

## Next Steps After Implementation

1. **Create Integration Guide** - FINANCIAL_FABRIC_GUIDE.md
2. **Update ADDITIONAL_EXAMPLES_PROPOSAL.md** - Mark as complete
3. **Add to Main README** - Link to new example
4. **Create Mermaid Diagrams** - Event flow visualization
5. **Add to CI/CD** - Include in test suite

---

## Notes

- This is the most comprehensive example in the suite
- Demonstrates complete financial services event architecture
- Shows real-world patterns for trading, custody, treasury, position, and regulatory domains
- Provides foundation for building production financial systems
- Can be extended with additional domains (funds, securities lending, etc.)

---

**End of Implementation Plan**
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("assetClass") String assetClass,
            @JsonProperty("tradeType") String tradeType,
            @JsonProperty("tradeTime") Instant tradeTime) {
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.quantity = quantity;
        this.price = price;
        this.counterparty = counterparty;
        this.assetClass = assetClass;
        this.tradeType = tradeType;
        this.tradeTime = tradeTime;
    }

    // Getters...

    public BigDecimal getNotional() {
        return quantity.multiply(price);
    }
}
```

**SettlementInstructionEvent.java**:
```java
public class SettlementInstructionEvent {
    private final String instructionId;
    private final String tradeId;
    private final String instrument;
    private final BigDecimal quantity;
    private final String custodian;
    private final LocalDate settlementDate;
    private final String settlementType;  // DVP, FOP, RVP
    private final Instant instructionTime;

    @JsonCreator
    public SettlementInstructionEvent(
            @JsonProperty("instructionId") String instructionId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("custodian") String custodian,
            @JsonProperty("settlementDate") LocalDate settlementDate,
            @JsonProperty("settlementType") String settlementType,
            @JsonProperty("instructionTime") Instant instructionTime) {
        this.instructionId = instructionId;
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.quantity = quantity;
        this.custodian = custodian;
        this.settlementDate = settlementDate;
        this.settlementType = settlementType;
        this.instructionTime = instructionTime;
    }

    // Getters...
}
```

**CashMovementEvent.java**:
```java
public class CashMovementEvent {
    private final String movementId;
    private final String tradeId;
    private final BigDecimal amount;
    private final String currency;
    private final String movementType;  // DEBIT, CREDIT
    private final String account;
    private final Instant movementTime;

    @JsonCreator
    public CashMovementEvent(
            @JsonProperty("movementId") String movementId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("movementType") String movementType,
            @JsonProperty("account") String account,
            @JsonProperty("movementTime") Instant movementTime) {
        this.movementId = movementId;
        this.tradeId = tradeId;
        this.amount = amount;
        this.currency = currency;
        this.movementType = movementType;
        this.account = account;
        this.movementTime = movementTime;
    }

    // Getters...
}
```

**PositionUpdateEvent.java**:
```java
public class PositionUpdateEvent {
    private final String updateId;
    private final String tradeId;
    private final String instrument;
    private final BigDecimal quantityChange;
    private final BigDecimal newPosition;
    private final String account;
    private final Instant updateTime;

    @JsonCreator
    public PositionUpdateEvent(
            @JsonProperty("updateId") String updateId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("quantityChange") BigDecimal quantityChange,
            @JsonProperty("newPosition") BigDecimal newPosition,
            @JsonProperty("account") String account,
            @JsonProperty("updateTime") Instant updateTime) {
        this.updateId = updateId;
        this.tradeId = tradeId;
        this.instrument = instrument;
        this.quantityChange = quantityChange;
        this.newPosition = newPosition;
        this.account = account;
        this.updateTime = updateTime;
    }

    // Getters...
}
```

**RegulatoryReportEvent.java**:
```java
public class RegulatoryReportEvent {
    private final String reportId;
    private final String tradeId;
    private final String reportType;  // MIFID2, EMIR, SFTR
    private final String regulatoryRegime;
    private final Map<String, Object> reportData;
    private final Instant reportTime;

    @JsonCreator
    public RegulatoryReportEvent(
            @JsonProperty("reportId") String reportId,
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("reportType") String reportType,
            @JsonProperty("regulatoryRegime") String regulatoryRegime,
            @JsonProperty("reportData") Map<String, Object> reportData,
            @JsonProperty("reportTime") Instant reportTime) {
        this.reportId = reportId;
        this.tradeId = tradeId;
        this.reportType = reportType;
        this.regulatoryRegime = regulatoryRegime;
        this.reportData = reportData;
        this.reportTime = reportTime;
    }

    // Getters...
}
```

#### 2.2 CloudEvents Integration

**CloudEventBuilder.java**:
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
            .withDataContentType(properties.getCloudevents().getDataContentType())
            .withData(data)
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId != null ? causationId : "")
            .withExtension("validtime", validTime.toString())
            .build();
    }
}
```

**CloudEventExtensions.java**:
```java
public class CloudEventExtensions {

    public static String getCorrelationId(CloudEvent event) {
        return (String) event.getExtension("correlationid");
    }

    public static String getCausationId(CloudEvent event) {
        String causationId = (String) event.getExtension("causationid");
        return causationId != null && !causationId.isEmpty() ? causationId : null;
    }

    public static Instant getValidTime(CloudEvent event) {
        String validTime = (String) event.getExtension("validtime");
        return validTime != null ? Instant.parse(validTime) : null;
    }

    public static <T> T getPayload(CloudEvent event, Class<T> payloadClass,
                                    ObjectMapper objectMapper) throws IOException {
        byte[] data = event.getData().toBytes();
        return objectMapper.readValue(data, payloadClass);
    }
}
```

---

### Phase 3: Multi-Domain Services (4-5 hours)

#### 3.1 Configuration

**FinancialFabricConfig.java**:
```java
@Configuration
public class FinancialFabricConfig {

    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        return new PgDatabaseService(manager);
    }

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

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
```

#### 3.2 Trade Workflow Service

**TradeWorkflowService.java** (Core orchestration):
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
        Instant tradeTime = request.getTradeTime() != null ?
            request.getTradeTime() : Instant.now();

        log.info("Starting trade workflow: tradeId={}, correlationId={}",
            request.getTradeId(), correlationId);

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

                    // Return workflow result
                    .thenApply(regulatoryEvent -> {
                        log.info("Trade workflow completed: tradeId={}, correlationId={}",
                            request.getTradeId(), correlationId);

                        return new WorkflowResult(
                            request.getTradeId(),
                            "SUCCESS",
                            correlationId,
                            tradeTime,
                            List.of(
                                "trading.equities.capture.completed",
                                "instruction.settlement.submitted",
                                "cash.movement.completed",
                                "position.update.completed",
                                "regulatory.transaction.reported"
                            )
                        );
                    });

            }).toCompletionStage().toCompletableFuture();
    }

    private CompletableFuture<CloudEvent> captureTrade(
            TradeRequest request,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        try {
            // Create trade event
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

            // Build CloudEvent
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.trading.equities.capture.completed.v1",
                tradeEvent,
                correlationId,
                causationId,
                validTime
            );

            // Append to trading event store
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

    // Similar methods for other steps...
}
```


