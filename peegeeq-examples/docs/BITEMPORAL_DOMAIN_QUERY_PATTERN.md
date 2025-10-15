# Domain-Specific Query Pattern

This guide demonstrates how to wrap PeeGeeQ's `EventQuery` API with domain-specific query methods that use business language and maintain async/non-blocking behavior.

## Overview

The domain-specific query pattern provides:

1. **Domain Language** - Methods named after business concepts instead of technical query operations
2. **Type Safety** - Strongly typed return values specific to your domain
3. **Async/Non-Blocking** - Returns `CompletableFuture` to avoid blocking the Vert.x event loop
4. **Encapsulation** - Hides query complexity behind simple method calls
5. **Testability** - Easy to mock for unit testing

## The Pattern

### ❌ Anti-Pattern: Blocking the Event Loop

```java
// DON'T DO THIS - blocks the event loop!
public List<BiTemporalEvent<TransactionEvent>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId)).join();  // ❌ BLOCKS!
}
```

### ✅ Correct Pattern: Async/Non-Blocking

```java
// DO THIS - maintains async chain
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId));  // ✅ Non-blocking
}
```

## Example: TransactionService

### Basic Query Methods

```java
@Service
public class TransactionService {

    private final EventStore<TransactionEvent> eventStore;

    /**
     * Domain-specific query: Get all transactions for a specific account.
     * Wraps EventQuery.forAggregate() with domain language.
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
            queryTransactionsByAccount(String accountId) {
        return eventStore.query(EventQuery.forAggregate(accountId));
    }

    /**
     * Domain-specific query: Get transactions by account and type.
     * Demonstrates the new EventQuery.forAggregateAndType() convenience method.
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
            queryTransactionsByAccountAndType(String accountId, String eventType) {
        return eventStore.query(EventQuery.forAggregateAndType(accountId, eventType));
    }
}
```

### Convenience Methods

Build higher-level methods on top of the basic queries:

```java
/**
 * Get only recorded (non-corrected) transactions for an account.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
        queryRecordedTransactions(String accountId) {
    return queryTransactionsByAccountAndType(accountId, "TransactionRecorded");
}

/**
 * Get only corrected transactions for an account.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
        queryCorrectedTransactions(String accountId) {
    return queryTransactionsByAccountAndType(accountId, "TransactionCorrected");
}
```

### Composing Async Operations

Use `.thenApply()` and `.thenCompose()` to chain operations:

```java
/**
 * Calculate account balance at a specific point in time.
 */
public CompletableFuture<BigDecimal> getAccountBalance(String accountId, Instant asOf) {
    return queryTransactionsByAccount(accountId)
        .thenApply(transactions -> {
            return transactions.stream()
                .filter(event -> !event.getValidTime().isAfter(asOf))
                .map(event -> {
                    TransactionEvent txn = event.getPayload();
                    return txn.getType() == TransactionType.CREDIT
                        ? txn.getAmount()
                        : txn.getAmount().negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
}
```

## EventQuery Convenience Methods

PeeGeeQ provides several static factory methods for common query patterns:

```java
// Query all events
EventQuery.all()

// Query by event type
EventQuery.forEventType("OrderCreated")

// Query by aggregate ID
EventQuery.forAggregate("order-123")

// Query by aggregate ID AND event type (NEW!)
EventQuery.forAggregateAndType("order-123", "OrderCreated")

// Query as of a specific valid time
EventQuery.asOfValidTime(Instant.now())

// Query as of a specific transaction time
EventQuery.asOfTransactionTime(Instant.now())
```

## Usage in Tests

When testing, call `.get()` on the `CompletableFuture` to wait for results:

```java
@Test
void testDomainSpecificQueryMethods() throws Exception {
    // Record some transactions
    transactionService.recordTransaction(request1).get();
    transactionService.recordTransaction(request2).get();

    // Query using domain-specific methods
    List<BiTemporalEvent<TransactionEvent>> allTransactions =
        transactionService.queryTransactionsByAccount("ACC-001").get();

    assertEquals(2, allTransactions.size());

    List<BiTemporalEvent<TransactionEvent>> recordedOnly =
        transactionService.queryRecordedTransactions("ACC-001").get();

    assertEquals(2, recordedOnly.size());
}
```

## Benefits

### 1. Domain Language

Instead of:
```java
eventStore.query(EventQuery.forAggregateAndType("ACC-001", "TransactionRecorded"))
```

You write:
```java
transactionService.queryRecordedTransactions("ACC-001")
```

### 2. Encapsulation

The service layer hides:
- Event type strings ("TransactionRecorded", "TransactionCorrected")
- Query builder complexity
- Async handling details

### 3. Type Safety

```java
// Strongly typed - returns TransactionEvent, not generic Object
CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> transactions =
    transactionService.queryRecordedTransactions("ACC-001");
```

### 4. Testability

Easy to mock in unit tests:
```java
@Mock
private TransactionService transactionService;

when(transactionService.queryRecordedTransactions("ACC-001"))
    .thenReturn(CompletableFuture.completedFuture(mockTransactions));
```

## Important: Aggregate ID

To use `EventQuery.forAggregate()` or `EventQuery.forAggregateAndType()`, you **must** set the `aggregateId` when appending events:

```java
// ✅ CORRECT - includes aggregateId
eventStore.append("TransactionRecorded", event, validTime,
                 Map.of(), null, accountId);  // accountId is the aggregate

// ❌ WRONG - aggregateId is null, queries won't work
eventStore.append("TransactionRecorded", event, validTime);
```

## See Also

- `TransactionService.java` - Full implementation example
- `SpringBootBitemporalApplicationTest.java` - Test examples
- `EventQuery.java` - API reference for query building

## Summary

The domain-specific query pattern:
1. Returns `CompletableFuture` (never blocks with `.join()`)
2. Uses business language for method names
3. Encapsulates query complexity
4. Maintains type safety
5. Enables easy testing and mocking

---

# Funds & Custody: Bi-Temporal Use Cases

This section describes bi-temporal event store patterns specifically for **middle office and back office transaction processing** in funds management and custody operations.

## Domain Context

### Middle Office / Back Office Operations

**Middle Office** responsibilities:
- Trade confirmation and matching
- Position reconciliation
- Corporate actions processing
- NAV (Net Asset Value) calculation
- Performance attribution
- Risk management

**Back Office** responsibilities:
- Settlement processing
- Cash management
- Custody reconciliation
- Fee calculation and billing
- Regulatory reporting (AIFMD, MiFID II, EMIR)
- Tax lot accounting

### Why Bi-Temporal for Funds & Custody?

1. **Trade Date vs Settlement Date** - Natural bi-temporal dimension
2. **Late Trade Confirmations** - Trades confirmed after cut-off time
3. **Corporate Actions** - Backdated adjustments (dividends, splits, mergers)
4. **NAV Corrections** - Recalculate historical NAV with corrected prices
5. **Regulatory Reporting** - Prove what was reported and when
6. **Reconciliation Breaks** - Track when discrepancies were discovered vs when they occurred
7. **Audit Trail** - Complete history required by regulators



## Use Case Categories

### 1. Trade Lifecycle Management

#### 1.1 Trade Date vs Settlement Date Tracking

**Business Problem:** A trade executed on T+0 may settle on T+2, but corrections can arrive at any time.

```java
/**
 * Record a trade with trade date as valid time, system time as transaction time.
 * This allows querying "what trades settled on date X" vs "what trades were known on date Y"
 */
public CompletableFuture<BiTemporalEvent<TradeEvent>> recordTrade(TradeRequest trade) {
    TradeEvent event = new TradeEvent(
        trade.getTradeId(),
        trade.getFundId(),
        trade.getSecurityId(),
        trade.getQuantity(),
        trade.getPrice(),
        trade.getTradeDate(),      // Business event date
        trade.getSettlementDate()
    );

    // Valid time = trade date (when trade actually occurred)
    // Transaction time = now (when we learned about it)
    return eventStore.append(
        "TradeExecuted",
        event,
        trade.getTradeDate(),      // Valid time
        Map.of("settlementDate", trade.getSettlementDate().toString()),
        null,
        trade.getFundId()          // Aggregate by fund
    );
}
```

#### 1.2 Late Trade Confirmations

**Business Problem:** Broker confirmations arrive after EOD cut-off, affecting previous day's NAV.

```java
/**
 * Identify trades confirmed after cut-off time.
 * These require NAV recalculation and may need regulatory reporting.
 */
public CompletableFuture<List<BiTemporalEvent<TradeEvent>>>
        getLateTradeConfirmations(String fundId, LocalDate tradingDay) {

    Instant tradeDayEnd = tradingDay.atTime(18, 0).toInstant(ZoneOffset.UTC);

    return queryTradesByFund(fundId)
        .thenApply(trades -> trades.stream()
            .filter(trade -> {
                // Trade date is on tradingDay
                LocalDate tradeDate = LocalDate.ofInstant(
                    trade.getValidTime(), ZoneOffset.UTC);

                // But confirmation arrived after cut-off
                return tradeDate.equals(tradingDay) &&
                       trade.getTransactionTime().isAfter(tradeDayEnd);
            })
            .collect(Collectors.toList())
        );
}
```

#### 1.3 Trade Cancellations and Amendments

**Business Problem:** Trades can be cancelled or amended, requiring historical correction.

```java
/**
 * Cancel a trade by recording a cancellation event with original trade date.
 * Preserves audit trail while correcting position.
 */
public CompletableFuture<BiTemporalEvent<TradeEvent>> cancelTrade(
        String tradeId, String reason) {

    return findOriginalTrade(tradeId)
        .thenCompose(original -> {
            TradeEvent cancellation = new TradeEvent(
                tradeId,
                original.getPayload().getFundId(),
                original.getPayload().getSecurityId(),
                original.getPayload().getQuantity().negate(), // Reverse quantity
                original.getPayload().getPrice(),
                original.getPayload().getTradeDate(),
                original.getPayload().getSettlementDate()
            );

            // Valid time = original trade date (backdated correction)
            // Transaction time = now (when cancellation was processed)
            return eventStore.append(
                "TradeCancelled",
                cancellation,
                original.getValidTime(),  // Same valid time as original
                Map.of("reason", reason, "originalTradeId", tradeId),
                null,
                original.getAggregateId()
            );
        });
}
```

### 2. NAV Calculation & Corrections

#### 2.1 Historical NAV Recalculation

**Business Problem:** Price corrections require recalculating historical NAV.

```java
/**
 * Calculate NAV as it was known on a specific date (for regulatory reporting).
 * vs Calculate NAV as it should have been on a specific date (with corrections).
 */
public CompletableFuture<NAVSnapshot> getNAVAsReported(
        String fundId, LocalDate navDate) {

    Instant navDateTime = navDate.atTime(18, 0).toInstant(ZoneOffset.UTC);

    // Get positions as they were known at NAV calculation time
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(fundId)
            .asOfTransactionTime(navDateTime)  // What we knew then
            .asOfValidTime(navDateTime)        // For that business date
            .build()
    ).thenApply(events -> calculateNAV(events, navDate));
}

/**
 * Calculate corrected NAV with all subsequent price corrections applied.
 */
public CompletableFuture<NAVSnapshot> getNAVCorrected(
        String fundId, LocalDate navDate) {

    Instant navDateTime = navDate.atTime(18, 0).toInstant(ZoneOffset.UTC);

    // Get positions with all corrections up to now
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(fundId)
            .asOfValidTime(navDateTime)  // For that business date
            // No transaction time filter = use latest knowledge
            .build()
    ).thenApply(events -> calculateNAV(events, navDate));
}
```

#### 2.2 NAV Correction Impact Analysis

**Business Problem:** When NAV is corrected, identify affected investors and calculate compensation.

```java
/**
 * Compare NAV as reported vs corrected NAV to calculate investor impact.
 */
public CompletableFuture<NAVCorrectionImpact> analyzeNAVCorrection(
        String fundId, LocalDate navDate) {

    CompletableFuture<NAVSnapshot> reported = getNAVAsReported(fundId, navDate);
    CompletableFuture<NAVSnapshot> corrected = getNAVCorrected(fundId, navDate);

    return reported.thenCombine(corrected, (rep, cor) -> {
        BigDecimal difference = cor.getNavPerShare().subtract(rep.getNavPerShare());
        BigDecimal percentageError = difference.divide(rep.getNavPerShare(), 6, RoundingMode.HALF_UP);

        return NAVCorrectionImpact.builder()
            .fundId(fundId)
            .navDate(navDate)
            .reportedNAV(rep.getNavPerShare())
            .correctedNAV(cor.getNavPerShare())
            .difference(difference)
            .percentageError(percentageError)
            .requiresInvestorCompensation(percentageError.abs().compareTo(new BigDecimal("0.005")) > 0)
            .build();
    });
}
```

### 3. Corporate Actions Processing

#### 3.1 Dividend Processing with Ex-Date

**Business Problem:** Dividends are announced with ex-date in the past, requiring position adjustment.

```java
/**
 * Process dividend with ex-date as valid time.
 * This backdates the position adjustment to the ex-date.
 */
public CompletableFuture<BiTemporalEvent<CorporateActionEvent>> processDividend(
        DividendAnnouncement announcement) {

    CorporateActionEvent event = new CorporateActionEvent(
        announcement.getAnnouncementId(),
        announcement.getSecurityId(),
        "DIVIDEND",
        announcement.getDividendPerShare(),
        announcement.getExDate(),
        announcement.getPaymentDate()
    );

    // Valid time = ex-date (when position was affected)
    // Transaction time = now (when we learned about it)
    return eventStore.append(
        "DividendProcessed",
        event,
        announcement.getExDate(),  // Backdated to ex-date
        Map.of(
            "paymentDate", announcement.getPaymentDate().toString(),
            "currency", announcement.getCurrency()
        ),
        null,
        announcement.getSecurityId()
    );
}
```

#### 3.2 Stock Split Adjustments

**Business Problem:** Stock splits require adjusting historical positions and prices.

```java
/**
 * Process stock split with effective date as valid time.
 * Requires recalculating positions and cost basis.
 */
public CompletableFuture<BiTemporalEvent<CorporateActionEvent>> processStockSplit(
        StockSplitAnnouncement split) {

    CorporateActionEvent event = new CorporateActionEvent(
        split.getAnnouncementId(),
        split.getSecurityId(),
        "STOCK_SPLIT",
        split.getSplitRatio(),
        split.getEffectiveDate(),
        null
    );

    return eventStore.append(
        "StockSplitProcessed",
        event,
        split.getEffectiveDate(),  // Backdated to effective date
        Map.of("splitRatio", split.getSplitRatio().toString()),
        null,
        split.getSecurityId()
    );
}
```

### 4. Custody Reconciliation

#### 4.1 Custodian Break Detection

**Business Problem:** Identify when custody breaks were discovered vs when they actually occurred.

```java
/**
 * Record a reconciliation break with the date it occurred (valid time)
 * and when it was discovered (transaction time).
 */
public CompletableFuture<BiTemporalEvent<ReconciliationBreakEvent>> recordCustodyBreak(
        ReconciliationBreak break) {

    ReconciliationBreakEvent event = new ReconciliationBreakEvent(
        break.getBreakId(),
        break.getFundId(),
        break.getSecurityId(),
        break.getInternalPosition(),
        break.getCustodianPosition(),
        break.getDifference(),
        break.getAsOfDate()  // Date the break occurred
    );

    // Valid time = as-of date (when break actually occurred)
    // Transaction time = now (when break was discovered)
    return eventStore.append(
        "ReconciliationBreakDetected",
        event,
        break.getAsOfDate(),  // When break occurred
        Map.of(
            "custodian", break.getCustodianName(),
            "discoveredDate", Instant.now().toString()
        ),
        null,
        break.getFundId()
    );
}
```

#### 4.2 Break Resolution Tracking

**Business Problem:** Track when breaks were resolved and what the root cause was.

```java
/**
 * Resolve a reconciliation break, maintaining audit trail.
 */
public CompletableFuture<BiTemporalEvent<ReconciliationBreakEvent>> resolveCustodyBreak(
        String breakId, BreakResolution resolution) {

    return findOriginalBreak(breakId)
        .thenCompose(original -> {
            ReconciliationBreakEvent resolvedEvent = new ReconciliationBreakEvent(
                breakId,
                original.getPayload().getFundId(),
                original.getPayload().getSecurityId(),
                resolution.getCorrectedInternalPosition(),
                resolution.getCorrectedCustodianPosition(),
                BigDecimal.ZERO,  // Break resolved
                original.getPayload().getAsOfDate()
            );

            return eventStore.append(
                "ReconciliationBreakResolved",
                resolvedEvent,
                original.getValidTime(),  // Same valid time as original break
                Map.of(
                    "rootCause", resolution.getRootCause(),
                    "resolution", resolution.getResolutionDescription(),
                    "resolvedBy", resolution.getResolvedBy()
                ),
                null,
                original.getAggregateId()
            );
        });
}
```

### 5. Regulatory Reporting

#### 5.1 AIFMD Reporting Snapshot

**Business Problem:** Generate AIFMD report showing exact state as reported on filing date.

```java
/**
 * Generate AIFMD report as it was filed on the reporting date.
 * Critical for regulatory audits - must prove what was reported.
 */
public CompletableFuture<AIFMDReport> getAIFMDReportAsFiledOn(
        String fundId, LocalDate reportingDate) {

    Instant filingDateTime = reportingDate.atTime(23, 59).toInstant(ZoneOffset.UTC);

    return eventStore.query(
        EventQuery.builder()
            .aggregateId(fundId)
            .asOfTransactionTime(filingDateTime)  // What we knew when we filed
            .build()
    ).thenApply(events -> buildAIFMDReport(events, reportingDate));
}
```

#### 5.2 MiFID II Transaction Reporting

**Business Problem:** Report all trades executed on a specific date, including late confirmations.

```java
/**
 * Generate MiFID II transaction report for a specific trading day.
 * Must include all trades with trade date on that day, regardless of when confirmed.
 */
public CompletableFuture<MiFIDReport> getMiFIDTransactionReport(
        String fundId, LocalDate tradingDay) {

    Instant dayStart = tradingDay.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant dayEnd = tradingDay.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

    return eventStore.query(
        EventQuery.builder()
            .aggregateId(fundId)
            .eventType("TradeExecuted")
            .validTimeStart(dayStart)
            .validTimeEnd(dayEnd)
            .build()
    ).thenApply(trades -> buildMiFIDReport(trades, tradingDay));
}
```


---

# Funds & Custody: Quick Start Guide

A practical guide for implementing bi-temporal event stores in middle office and back office systems.

## Real-World Scenarios

### Scenario 1: Late Trade Confirmation

**Timeline:**
- 10:30 - Trade executed (Valid Time)
- 18:00 - NAV cut-off
- 20:00 - Trade confirmed (Transaction Time) ⚠️ LATE
- 22:00 - NAV calculated (without trade)
- Next day 09:00 - NAV corrected

**Query Patterns:**

```java
// Find late trades for a trading day
getLateTradeConfirmations("FUND-001", LocalDate.of(2024, 11, 15))

// NAV as originally reported (for regulatory filing)
getNAVAsReported("FUND-001", LocalDate.of(2024, 11, 15))

// NAV with corrections (for investor statements)
getNAVCorrected("FUND-001", LocalDate.of(2024, 11, 15))
```

### Scenario 2: Dividend with Ex-Date

**Timeline:**
- Nov 10 - Dividend ex-date (Valid Time)
- Nov 15 - Dividend announced (Transaction Time)
- Nov 30 - Payment date

**Implementation:**

```java
// Record dividend backdated to ex-date
processDividend(DividendAnnouncement.builder()
    .securityId("AAPL")
    .exDate(LocalDate.of(2024, 11, 10))      // Valid time
    .announcementDate(LocalDate.of(2024, 11, 15))  // Transaction time
    .dividendPerShare(new BigDecimal("0.25"))
    .paymentDate(LocalDate.of(2024, 11, 30))
    .build()
)

// Recalculate positions as of ex-date with dividend applied
getPositionAsOf("FUND-001", "AAPL", LocalDate.of(2024, 11, 10))
```

### Scenario 3: NAV Correction Impact

**Timeline:**
- Nov 15 18:00 - NAV calculated with incorrect price
- Nov 15 22:00 - NAV published to investors
- Nov 16 09:00 - Price correction received
- Nov 16 10:00 - NAV recalculated and corrected

**Query Patterns:**

```java
// Compare NAV as reported vs corrected
analyzeNAVCorrection("FUND-001", LocalDate.of(2024, 11, 15))
// Returns: {
//   reportedNAV: 100.50,
//   correctedNAV: 100.48,
//   difference: -0.02,
//   percentageError: -0.0199%,
//   requiresInvestorCompensation: false  // < 0.5% threshold
// }

// Identify affected investors
getInvestorsAffectedByNAVCorrection("FUND-001", LocalDate.of(2024, 11, 15))
```

### Scenario 4: Custody Reconciliation Break

**Timeline:**
- Nov 10 - Break occurred (Valid Time) - unknown
- Nov 15 - Reconciliation run discovers break (Transaction Time)
- Nov 16 - Root cause identified (missing trade)
- Nov 17 - Break resolved

**Implementation:**

```java
// Record break when discovered
recordCustodyBreak(ReconciliationBreak.builder()
    .fundId("FUND-001")
    .securityId("MSFT")
    .asOfDate(LocalDate.of(2024, 11, 10))  // When break occurred
    .internalPosition(1000)
    .custodianPosition(1100)
    .difference(-100)
    .custodianName("State Street")
    .build()
)

// Resolve break with root cause
resolveCustodyBreak("BREAK-123", BreakResolution.builder()
    .rootCause("Missing trade confirmation")
    .resolutionDescription("Trade confirmed and processed")
    .resolvedBy("john.smith@fund.com")
    .build()
)

// Query break history
getCustodyBreaksForPeriod("FUND-001", startDate, endDate)
```

## Implementation Roadmap

### Phase 1: Foundation (Weeks 1-2) ✅ COMPLETE

**Goal:** Basic trade and position management

1. Implement `TradeService`
   - `recordTrade(TradeRequest)` - Record trade with trade date
   - `cancelTrade(tradeId, reason)` - Cancel trade
   - `getTradesByFund(fundId)` - Query trades by fund
   - `getLateTradeConfirmations(fundId, tradingDay)` - Find late trades

2. Implement `PositionService`
   - `getPositionAsOf(fundId, securityId, date)` - Position at date
   - `getPositionsByFund(fundId, date)` - All positions for fund
   - `getPositionHistory(fundId, securityId, startDate, endDate)` - History

**Deliverables:**
- ✅ Trade recording with bi-temporal tracking
- ✅ Position queries by date
- ✅ Late trade detection
- ✅ 14 passing tests (6 TradeServiceTest + 8 PositionServiceTest)

### Phase 2: NAV & Corporate Actions (Weeks 3-4)

**Goal:** NAV calculation and corrections

3. Implement `NAVService`
   - `calculateNAV(fundId, navDate)` - Calculate NAV
   - `getNAVAsReported(fundId, navDate)` - NAV as reported
   - `getNAVCorrected(fundId, navDate)` - NAV with corrections
   - `analyzeNAVCorrection(fundId, navDate)` - Impact analysis

4. Implement `CorporateActionService`
   - `processDividend(announcement)` - Process dividend
   - `processStockSplit(announcement)` - Process stock split
   - `getCorporateActionsForSecurity(securityId, startDate, endDate)` - Query

**Deliverables:**
- NAV calculation with corrections
- Corporate action processing
- NAV correction impact analysis
- Investor compensation calculation

### Phase 3: Reconciliation (Weeks 5-6)

**Goal:** Custody reconciliation and break management

5. Implement `ReconciliationService`
   - `recordCustodyBreak(break)` - Record break
   - `resolveCustodyBreak(breakId, resolution)` - Resolve break
   - `getCustodyBreaksForPeriod(fundId, startDate, endDate)` - Query breaks
   - `getBreakResolutionHistory(breakId)` - Break history

**Deliverables:**
- Break detection and tracking
- Break resolution workflow
- Break aging reports
- Root cause analysis

### Phase 4: Regulatory & Reporting (Weeks 7-8)

**Goal:** Regulatory reporting and audit trail

6. Implement `RegulatoryReportingService`
   - `getAIFMDReportAsFiledOn(fundId, reportingDate)` - AIFMD snapshot
   - `getMiFIDTransactionReport(fundId, tradingDay)` - MiFID II
   - `getAuditTrail(fundId, startDate, endDate)` - Complete audit trail

7. Implement `FeeService`
   - `calculateFees(fundId, periodStart, periodEnd)` - Calculate fees
   - `reconcileFees(fundId, periodStart, periodEnd)` - Fee reconciliation
   - `getFeeCorrectionImpact(fundId, periodStart, periodEnd)` - Impact

**Deliverables:**
- Regulatory report generation
- Audit trail queries
- Fee calculation with corrections
- Complete documentation


## Best Practices

### 1. Always Set Valid Time Appropriately

```java
// ✅ CORRECT - Use business date
eventStore.append("TradeExecuted", event,
    trade.getTradeDate(),  // Valid time = trade date
    Map.of(), null, fundId)

// ❌ WRONG - Using system time
eventStore.append("TradeExecuted", event,
    Instant.now(),  // Should be trade date!
    Map.of(), null, fundId)
```

### 2. Use Aggregate ID for Efficient Queries

```java
// ✅ CORRECT - Query by fund
eventStore.query(EventQuery.forAggregate(fundId))

// ❌ INEFFICIENT - Query all then filter
eventStore.query(EventQuery.all())
    .thenApply(events -> events.stream()
        .filter(e -> fundId.equals(e.getPayload().getFundId()))
        .collect(Collectors.toList()))
```

### 3. Document Correction Reasons

```java
// ✅ CORRECT - Include metadata
eventStore.append("NAVCorrected", event, navDate,
    Map.of(
        "reason", "Price correction from vendor",
        "originalNAV", "100.50",
        "correctedNAV", "100.48",
        "approvedBy", "jane.doe@fund.com"
    ),
    null, fundId)
```

### 4. Maintain Async Chains

```java
// ✅ CORRECT - Async chain
public CompletableFuture<Position> getPositionAsOf(String fundId, String securityId, LocalDate date) {
    return eventStore.query(EventQuery.forAggregate(fundId))
        .thenApply(events -> calculatePosition(events, securityId, date));
}

// ❌ WRONG - Blocking
public Position getPositionAsOf(String fundId, String securityId, LocalDate date) {
    List<BiTemporalEvent<TradeEvent>> events =
        eventStore.query(EventQuery.forAggregate(fundId)).join();  // BLOCKS!
    return calculatePosition(events, securityId, date);
}
```

## Key Benefits

### For Middle Office

✅ **Accurate NAV** - Handle late trades and corrections properly
✅ **Corporate Actions** - Backdate to ex-date automatically
✅ **Performance Attribution** - Recalculate with corrected data
✅ **Risk Management** - Point-in-time position views

### For Back Office

✅ **Reconciliation** - Track when breaks occurred vs discovered
✅ **Settlement** - Handle late confirmations
✅ **Fee Calculation** - Recalculate with corrected NAV
✅ **Cash Management** - Accurate cash positions

### For Compliance

✅ **Audit Trail** - Complete history of all changes
✅ **Regulatory Reporting** - Prove what was reported when
✅ **Investor Protection** - Accurate compensation calculations
✅ **Transparency** - Full visibility into corrections

## Domain Model Reference

### Aggregates

```java
// Fund - Investment fund
aggregateId = fundId
Events: TradeExecuted, NAVCalculated, NAVCorrected, FeeCalculated

// Security - Financial instrument
aggregateId = securityId
Events: DividendProcessed, StockSplitProcessed, PriceUpdated

// CustodyAccount - Custodian account
aggregateId = custodyAccountId
Events: ReconciliationBreakDetected, ReconciliationBreakResolved

// Investor - Fund investor
aggregateId = investorId
Events: SubscriptionProcessed, RedemptionProcessed, FeeCharged
```

### Event Types

| Event Type | Valid Time | Transaction Time | Use Case |
|------------|------------|------------------|----------|
| `TradeExecuted` | Trade date | Confirmation time | Trade lifecycle |
| `TradeCancelled` | Original trade date | Cancellation time | Trade corrections |
| `NAVCalculated` | NAV date | Calculation time | NAV processing |
| `NAVCorrected` | NAV date | Correction time | NAV corrections |
| `DividendProcessed` | Ex-date | Announcement time | Corporate actions |
| `StockSplitProcessed` | Effective date | Processing time | Corporate actions |
| `ReconciliationBreakDetected` | Break occurrence date | Discovery time | Reconciliation |
| `ReconciliationBreakResolved` | Break occurrence date | Resolution time | Reconciliation |
| `FeeCalculated` | Period end date | Calculation time | Fee processing |
| `PerformanceCalculated` | Period end date | Calculation time | Performance |

---

# ⚠️ CRITICAL: Cross-Type Deserialization and Query Performance

## The Problem

PeeGeeQ's bi-temporal event store uses a **single shared table** (`bitemporal_event_log`) for all event types. When you create multiple `EventStore<T>` instances with different generic types, they all query the same underlying table.

### Symptom: Deserialization Warnings

```
WARN d.m.p.b.PgBiTemporalEventStore - Failed to map row to event:
Cannot construct instance of `TradeEvent`, problem: tradeType cannot be null
```

These warnings indicate that the event store is:
1. Fetching rows from the shared table
2. Attempting to deserialize each row as the target type (e.g., `TradeEvent`)
3. Failing when the row contains a different event type (e.g., `NAVEvent`, `TradeCancelledEvent`)
4. Logging a WARNING and skipping the row

### Root Cause: JSONB Serialization with Shared Table

The event store uses JSONB to serialize event payloads. When different event types share the same table:

```sql
-- All events stored in same table
CREATE TABLE bitemporal_event_log (
    event_id UUID PRIMARY KEY,
    event_type TEXT,           -- "TradeExecuted", "NAVCalculated", etc.
    aggregate_id TEXT,         -- "FUND-001", "NAV:FUND-001", etc.
    payload JSONB,             -- Serialized event (TradeEvent, NAVEvent, etc.)
    ...
);
```

When you query with `EventQuery.all()`:
- The query returns **ALL rows** from the table
- The event store tries to deserialize each row as the target type
- Rows with incompatible JSON structure fail deserialization
- This generates warnings and wastes CPU cycles

### Performance Impact

**❌ BAD: Full Table Scan**
```java
// Queries ALL events across ALL types and aggregates
eventStore.query(EventQuery.all())
    .thenApply(events -> events.stream()
        .filter(e -> tradeId.equals(e.getPayload().tradeId()))
        .findFirst()
        .orElse(null));
```

**Impact:**
- Fetches 10,000+ rows from database
- Attempts to deserialize all rows as `TradeEvent`
- Fails on 8,000 rows (NAVEvent, TradeCancelledEvent, etc.)
- Logs 8,000 warnings
- Filters in memory to find 1 trade
- **O(n) complexity** where n = total events in system

## Solutions

### Solution 1: Use Aggregate ID Prefixing (Partial Fix)

Separate event streams by prefixing aggregate IDs:

```java
// ✅ GOOD: Separate streams by event type
// NAV events
eventStore.append("NAVCalculated", event, validTime,
    Map.of(), null, "NAV:" + fundId);

// Trade events
eventStore.append("TradeExecuted", event, validTime,
    Map.of(), null, "TRADE:" + fundId);

// Cancellation events
eventStore.append("TradeCancelled", event, validTime,
    Map.of(), null, "CANCELLATION:" + fundId);
```

**Query by aggregate:**
```java
// ✅ GOOD: Query only NAV events for this fund
eventStore.query(EventQuery.forAggregate("NAV:" + fundId))

// ✅ GOOD: Query only Trade events for this fund
eventStore.query(EventQuery.forAggregate("TRADE:" + fundId))
```

**Benefits:**
- Eliminates cross-type deserialization when querying by aggregate
- Reduces query result set size
- **O(m) complexity** where m = events for this aggregate

**Limitations:**
- Still doesn't help with `EventQuery.all()` queries
- Requires knowing the aggregate ID prefix

### Solution 2: Use Event Type Filtering (Recommended)

Filter by event type at the database level:

```java
// ✅ BEST: Query by event type
eventStore.query(EventQuery.forEventType("TradeExecuted"))
    .thenApply(events -> events.stream()
        .filter(e -> tradeId.equals(e.getPayload().tradeId()))
        .findFirst()
        .orElse(null));
```

**Benefits:**
- Database filters by `event_type` column before fetching rows
- Only deserializes events of the correct type
- No deserialization warnings
- **O(k) complexity** where k = events of this type

**SQL Generated:**
```sql
SELECT * FROM bitemporal_event_log
WHERE event_type = 'TradeExecuted'
```

### Solution 3: Pass Context Parameters (Best for Domain Queries)

Change method signatures to include context that enables efficient queries:

```java
// ❌ BAD: No context - must scan all events
private CompletableFuture<BiTemporalEvent<TradeEvent>> findTradeById(String tradeId) {
    return eventStore.query(EventQuery.all())  // Full table scan!
        .thenApply(trades -> trades.stream()
            .filter(trade -> tradeId.equals(trade.getPayload().tradeId()))
            .findFirst()
            .orElse(null));
}

// ✅ GOOD: Include fundId - can query by aggregate
private CompletableFuture<BiTemporalEvent<TradeEvent>> findTradeById(
        String fundId, String tradeId) {
    return eventStore.query(EventQuery.forAggregate("TRADE:" + fundId))
        .thenApply(trades -> trades.stream()
            .filter(trade -> tradeId.equals(trade.getPayload().tradeId()))
            .findFirst()
            .orElse(null));
}
```

**Benefits:**
- Queries only relevant aggregate
- No cross-type deserialization
- **O(m) complexity** where m = trades for this fund

### Solution 4: Use Metadata Filtering (Future Enhancement)

Store searchable fields in the `headers` JSONB column:

```java
// Store tradeId in metadata
eventStore.append("TradeExecuted", event, validTime,
    Map.of("tradeId", trade.tradeId()),  // Searchable metadata
    null, "TRADE:" + fundId);

// Query by metadata (if supported by implementation)
eventStore.query(EventQuery.builder()
    .headerFilters(Map.of("tradeId", tradeId))
    .build());
```

**Note:** This requires the event store implementation to support metadata filtering at the database level (e.g., JSONB containment queries).

## Anti-Patterns to Avoid

### ❌ Anti-Pattern 1: EventQuery.all() for Specific Lookups

```java
// ❌ TERRIBLE: Full table scan for single trade
public CompletableFuture<BiTemporalEvent<TradeEvent>> findTradeById(String tradeId) {
    return eventStore.query(EventQuery.all())  // Fetches ALL events!
        .thenApply(trades -> trades.stream()
            .filter(trade -> tradeId.equals(trade.getPayload().tradeId()))
            .findFirst()
            .orElse(null));
}
```

**Problems:**
- Fetches entire event table
- Attempts to deserialize all events as TradeEvent
- Generates thousands of warnings
- Wastes CPU and memory
- **Does not scale**

### ❌ Anti-Pattern 2: Multiple Event Stores with Same Aggregate ID

```java
// ❌ BAD: Different event types using same aggregate ID
EventStore<TradeEvent> tradeStore = factory.createEventStore(TradeEvent.class);
EventStore<NAVEvent> navStore = factory.createEventStore(NAVEvent.class);

// Both use fundId as aggregate - causes cross-type deserialization!
tradeStore.append("TradeExecuted", trade, validTime, Map.of(), null, fundId);
navStore.append("NAVCalculated", nav, validTime, Map.of(), null, fundId);

// Query by aggregate returns BOTH types, causing deserialization failures
tradeStore.query(EventQuery.forAggregate(fundId));  // ⚠️ Tries to deserialize NAVEvent as TradeEvent!
```

**Fix:**
```java
// ✅ GOOD: Use prefixed aggregate IDs
tradeStore.append("TradeExecuted", trade, validTime, Map.of(), null, "TRADE:" + fundId);
navStore.append("NAVCalculated", nav, validTime, Map.of(), null, "NAV:" + fundId);
```

### ❌ Anti-Pattern 3: Ignoring Deserialization Warnings

```
WARN d.m.p.b.PgBiTemporalEventStore - Failed to map row to event...
```

**These warnings are NOT harmless!** They indicate:
- Inefficient queries (full table scans)
- Wasted CPU cycles (failed deserializations)
- Performance problems that will worsen as data grows
- Design issues that need to be addressed

## Best Practices

### ✅ 1. Always Use Aggregate ID Prefixing

```java
// Separate event streams by type
"NAV:" + fundId           // For NAV events
"TRADE:" + fundId         // For trade events
"CANCELLATION:" + fundId  // For cancellation events
"POSITION:" + fundId      // For position snapshots
```

### ✅ 2. Prefer Specific Queries Over EventQuery.all()

```java
// ✅ GOOD: Query by aggregate
eventStore.query(EventQuery.forAggregate("TRADE:" + fundId))

// ✅ GOOD: Query by event type
eventStore.query(EventQuery.forEventType("TradeExecuted"))

// ✅ GOOD: Query by aggregate AND type
eventStore.query(EventQuery.forAggregateAndType("TRADE:" + fundId, "TradeExecuted"))

// ❌ BAD: Query all (only use for admin/debugging)
eventStore.query(EventQuery.all())
```

### ✅ 3. Include Context in Method Signatures

```java
// ✅ GOOD: Include fundId for efficient queries
public CompletableFuture<BiTemporalEvent<TradeEvent>> findTrade(
        String fundId, String tradeId) {
    return eventStore.query(EventQuery.forAggregate("TRADE:" + fundId))
        .thenApply(trades -> trades.stream()
            .filter(t -> tradeId.equals(t.getPayload().tradeId()))
            .findFirst()
            .orElse(null));
}
```

### ✅ 4. Document Query Performance Characteristics

```java
/**
 * Find trade by ID.
 *
 * <p><b>Performance:</b> O(m) where m = number of trades for this fund.
 * Queries only the TRADE aggregate for this fund, not the entire event table.
 *
 * @param fundId fund identifier (required for efficient query)
 * @param tradeId trade identifier
 * @return future containing trade event, or null if not found
 */
public CompletableFuture<BiTemporalEvent<TradeEvent>> findTrade(
        String fundId, String tradeId) {
    // ...
}
```

## Monitoring and Debugging

### Check for Deserialization Warnings

```bash
# Search logs for deserialization failures
grep "Failed to map row to event" application.log

# Count warnings by event type
grep "Failed to map row to event" application.log | \
  grep -o "Cannot construct instance of \`[^`]*\`" | \
  sort | uniq -c
```

### Identify Inefficient Queries

Look for:
- `EventQuery.all()` in production code
- Multiple event types sharing the same aggregate ID
- Methods that don't accept context parameters (fundId, etc.)

### Performance Testing

```java
@Test
void testQueryPerformance() {
    // Create 10,000 events of different types
    createTestData(10000);

    // ❌ BAD: Full table scan
    long start = System.currentTimeMillis();
    eventStore.query(EventQuery.all()).get();
    long fullScanTime = System.currentTimeMillis() - start;

    // ✅ GOOD: Query by aggregate
    start = System.currentTimeMillis();
    eventStore.query(EventQuery.forAggregate("TRADE:FUND-001")).get();
    long aggregateQueryTime = System.currentTimeMillis() - start;

    // Assert aggregate query is much faster
    assertTrue(aggregateQueryTime < fullScanTime / 10,
        "Aggregate query should be 10x faster than full scan");
}
```

## Summary

### The Core Issue

PeeGeeQ uses a **shared table with JSONB serialization** for all event types. Querying without proper filtering causes:
- Cross-type deserialization attempts
- Performance degradation
- Warning log spam
- Scalability problems

### The Solution

1. **Use aggregate ID prefixing** to separate event streams
2. **Query by aggregate or event type** instead of `EventQuery.all()`
3. **Include context parameters** in method signatures
4. **Monitor for deserialization warnings** as a code smell

### Key Takeaway

**Deserialization warnings are not harmless - they indicate inefficient queries that will cause performance problems as your data grows. Fix them by using more specific query patterns.**

---

## See Also

- [Phase 1 Implementation](../src/main/java/dev/mars/peegeeq/examples/fundscustody/README.md) - Working example with tests
- [TransactionService Example](../src/main/java/dev/mars/peegeeq/examples/springbootbitemporal/service/TransactionService.java) - Domain-specific query patterns
- [EventQuery API](../../peegeeq-api/src/main/java/dev/mars/peegeeq/api/EventQuery.java) - Query builder reference

