# Bi-Temporal Event Store: Common Domain Use Cases

This document proposes additional domain-specific query patterns and use cases that leverage the unique capabilities of bi-temporal event stores beyond basic transaction recording.

## Overview

Bi-temporal event stores track two time dimensions:
- **Valid Time** (Business Time) - When the event was valid in the real world
- **Transaction Time** (System Time) - When the event was recorded in the system

This enables powerful query patterns for compliance, auditing, reconciliation, and temporal analysis.

## Use Case Categories

### 1. Temporal Queries & Time Travel

#### 1.1 Point-in-Time Reconstruction
**Use Case:** "What did we know about this account on December 31st?"

```java
/**
 * Reconstruct account state as it was known at a specific transaction time.
 * This answers: "What did our system show on this date?"
 */
public CompletableFuture<AccountSnapshot> getAccountAsKnownAt(
        String accountId, Instant transactionTime) {
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(accountId)
            .asOfTransactionTime(transactionTime)
            .build()
    ).thenApply(events -> reconstructAccountState(events));
}

/**
 * Reconstruct account state as it was valid at a specific business time.
 * This answers: "What was the actual state on this business date?"
 */
public CompletableFuture<AccountSnapshot> getAccountAsValidAt(
        String accountId, Instant validTime) {
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(accountId)
            .asOfValidTime(validTime)
            .build()
    ).thenApply(events -> reconstructAccountState(events));
}
```

#### 1.2 Bi-Temporal Point Query
**Use Case:** "What did we know on Dec 31st about what happened on Nov 15th?"

```java
/**
 * Query combining both time dimensions.
 * Example: Regulatory reporting - "Show me what we reported on year-end 
 * about transactions that occurred in Q3"
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> 
        getEventsAsKnownAtForValidPeriod(
            String accountId,
            Instant validTimeStart,
            Instant validTimeEnd,
            Instant asOfTransactionTime) {
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(accountId)
            .validTimeStart(validTimeStart)
            .validTimeEnd(validTimeEnd)
            .asOfTransactionTime(asOfTransactionTime)
            .build()
    );
}
```

### 2. Audit & Compliance Queries

#### 2.1 Change Detection
**Use Case:** "What changed between two reporting periods?"

```java
/**
 * Detect all changes made to an account between two transaction times.
 * Useful for audit trails and change tracking.
 */
public CompletableFuture<ChangeReport> getChangesBetween(
        String accountId,
        Instant fromTransactionTime,
        Instant toTransactionTime) {
    
    CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> beforeState = 
        eventStore.query(
            EventQuery.builder()
                .aggregateId(accountId)
                .asOfTransactionTime(fromTransactionTime)
                .build()
        );
    
    CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> afterState = 
        eventStore.query(
            EventQuery.builder()
                .aggregateId(accountId)
                .asOfTransactionTime(toTransactionTime)
                .build()
        );
    
    return beforeState.thenCombine(afterState, (before, after) -> 
        calculateChanges(before, after)
    );
}
```

#### 2.2 Correction Audit Trail
**Use Case:** "Show me all corrections made to transactions in a period"

```java
/**
 * Find all corrections made within a transaction time window.
 * Critical for compliance and audit reporting.
 */
public CompletableFuture<List<CorrectionAudit>> getCorrectionsInPeriod(
        String accountId,
        Instant periodStart,
        Instant periodEnd) {
    
    return queryCorrectedTransactions(accountId)
        .thenApply(corrections -> corrections.stream()
            .filter(event -> !event.getTransactionTime().isBefore(periodStart))
            .filter(event -> !event.getTransactionTime().isAfter(periodEnd))
            .map(this::buildCorrectionAudit)
            .collect(Collectors.toList())
        );
}

/**
 * Find all corrections that affected a specific valid time period.
 * Example: "Show corrections made to Q3 transactions, regardless of when corrected"
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> 
        getCorrectionsAffectingValidPeriod(
            String accountId,
            Instant validStart,
            Instant validEnd) {
    
    return queryCorrectedTransactions(accountId)
        .thenApply(corrections -> corrections.stream()
            .filter(event -> !event.getValidTime().isBefore(validStart))
            .filter(event -> !event.getValidTime().isAfter(validEnd))
            .collect(Collectors.toList())
        );
}
```

### 3. Reconciliation Patterns

#### 3.1 Late-Arriving Events
**Use Case:** "Process events that arrive after the reporting period closed"

```java
/**
 * Identify late-arriving events - events recorded after their valid time period.
 * Common in batch processing, delayed feeds, or manual corrections.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> 
        getLateArrivingEvents(String accountId, Duration threshold) {
    
    return queryTransactionsByAccount(accountId)
        .thenApply(events -> events.stream()
            .filter(event -> {
                Duration delay = Duration.between(
                    event.getValidTime(), 
                    event.getTransactionTime()
                );
                return delay.compareTo(threshold) > 0;
            })
            .collect(Collectors.toList())
        );
}
```

#### 3.2 Reconciliation Snapshots
**Use Case:** "Compare our records with external system at specific points in time"

```java
/**
 * Generate reconciliation snapshot for a specific business date
 * as it was known at a specific system date.
 */
public CompletableFuture<ReconciliationSnapshot> getReconciliationSnapshot(
        String accountId,
        Instant businessDate,
        Instant systemDate) {
    
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(accountId)
            .asOfValidTime(businessDate)
            .asOfTransactionTime(systemDate)
            .build()
    ).thenApply(events -> buildReconciliationSnapshot(events, businessDate, systemDate));
}
```

### 4. Temporal Analytics

#### 4.1 Balance History Over Time
**Use Case:** "Show daily balance for the last 30 days"

```java
/**
 * Calculate balance for each day in a period.
 * Useful for charts, reports, and trend analysis.
 */
public CompletableFuture<Map<LocalDate, BigDecimal>> getDailyBalanceHistory(
        String accountId,
        LocalDate startDate,
        LocalDate endDate) {
    
    return queryTransactionsByAccount(accountId)
        .thenApply(events -> {
            Map<LocalDate, BigDecimal> dailyBalances = new TreeMap<>();
            
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                Instant endOfDay = date.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
                BigDecimal balance = calculateBalanceAsOf(events, endOfDay);
                dailyBalances.put(date, balance);
            }
            
            return dailyBalances;
        });
}
```

#### 4.2 Event Velocity Analysis
**Use Case:** "Detect unusual activity patterns"

```java
/**
 * Analyze transaction velocity - rate of events over time.
 * Useful for fraud detection, capacity planning, or anomaly detection.
 */
public CompletableFuture<VelocityReport> analyzeTransactionVelocity(
        String accountId,
        Instant periodStart,
        Instant periodEnd,
        Duration bucketSize) {
    
    return queryTransactionsByAccount(accountId)
        .thenApply(events -> {
            Map<Instant, Long> buckets = events.stream()
                .filter(e -> !e.getValidTime().isBefore(periodStart))
                .filter(e -> !e.getValidTime().isAfter(periodEnd))
                .collect(Collectors.groupingBy(
                    e -> bucketTime(e.getValidTime(), bucketSize),
                    Collectors.counting()
                ));
            
            return new VelocityReport(buckets, detectAnomalies(buckets));
        });
}
```

### 5. Versioning & History Patterns

#### 5.1 Event Lineage Tracking
**Use Case:** "Show the complete history of changes to a specific transaction"

```java
/**
 * Get complete lineage of a transaction including all corrections.
 * Returns events ordered by transaction time to show evolution.
 */
public CompletableFuture<TransactionLineage> getTransactionLineage(
        String transactionId) {
    
    return getTransactionVersions(transactionId)
        .thenApply(versions -> {
            versions.sort(Comparator.comparing(BiTemporalEvent::getTransactionTime));
            
            return TransactionLineage.builder()
                .transactionId(transactionId)
                .originalEvent(versions.get(0))
                .corrections(versions.subList(1, versions.size()))
                .correctionCount(versions.size() - 1)
                .build();
        });
}
```

#### 5.2 Current vs Historical State
**Use Case:** "Compare current state with state at previous reporting period"

```java
/**
 * Compare current account state with state at a previous point in time.
 * Useful for period-over-period analysis.
 */
public CompletableFuture<StateComparison> compareStateWithPrevious(
        String accountId,
        Instant previousPeriodEnd) {
    
    CompletableFuture<AccountSnapshot> currentState = 
        getAccountAsValidAt(accountId, Instant.now());
    
    CompletableFuture<AccountSnapshot> previousState = 
        getAccountAsValidAt(accountId, previousPeriodEnd);
    
    return currentState.thenCombine(previousState, 
        (current, previous) -> new StateComparison(current, previous)
    );
}
```

### 6. Regulatory & Reporting Patterns

#### 6.1 Regulatory Snapshot
**Use Case:** "Generate exact state as reported to regulators on specific date"

```java
/**
 * Generate regulatory report showing state as it was known and reported
 * on a specific regulatory reporting date.
 */
public CompletableFuture<RegulatoryReport> getRegulatorySnapshot(
        String accountId,
        Instant reportingDate) {
    
    // Get state as it was known at reporting time
    return eventStore.query(
        EventQuery.builder()
            .aggregateId(accountId)
            .asOfTransactionTime(reportingDate)
            .build()
    ).thenApply(events -> buildRegulatoryReport(events, reportingDate));
}
```

#### 6.2 Backdated Transaction Detection
**Use Case:** "Find transactions recorded with valid time before transaction time"

```java
/**
 * Identify backdated transactions - events with valid time significantly
 * before transaction time. May require special handling or approval.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> 
        getBackdatedTransactions(String accountId, Duration threshold) {
    
    return queryRecordedTransactions(accountId)
        .thenApply(events -> events.stream()
            .filter(event -> {
                Duration backdating = Duration.between(
                    event.getValidTime(),
                    event.getTransactionTime()
                );
                return backdating.compareTo(threshold) > 0;
            })
            .collect(Collectors.toList())
        );
}
```

## Implementation Recommendations

### 1. Add to EventQuery Builder

Consider adding these convenience methods to `EventQuery`:

```java
// Time range queries
public static EventQuery forValidTimeRange(Instant start, Instant end)
public static EventQuery forTransactionTimeRange(Instant start, Instant end)

// Combined temporal queries
public static EventQuery forBiTemporalPoint(
    Instant validTime, 
    Instant transactionTime)
```

### 2. Create Domain-Specific Services

Organize these patterns into focused services:

- `TemporalQueryService` - Time travel and point-in-time queries
- `AuditService` - Change detection and correction tracking
- `ReconciliationService` - Late arrivals and reconciliation
- `AnalyticsService` - Temporal analytics and reporting

### 3. Add Response DTOs

Create domain-specific response objects:

```java
record AccountSnapshot(String accountId, BigDecimal balance, 
                      Instant asOfValid, Instant asOfTransaction) {}
record ChangeReport(List<Change> additions, List<Change> modifications) {}
record CorrectionAudit(String transactionId, BigDecimal oldAmount, 
                      BigDecimal newAmount, String reason, Instant correctedAt) {}
record VelocityReport(Map<Instant, Long> buckets, List<Anomaly> anomalies) {}
```

## Benefits of These Patterns

1. **Regulatory Compliance** - Prove exactly what was known and when
2. **Audit Trail** - Complete history of all changes with reasons
3. **Reconciliation** - Handle late-arriving data and corrections
4. **Analytics** - Temporal analysis and trend detection
5. **Debugging** - Time-travel to reproduce historical states
6. **Reporting** - Generate accurate historical reports

## Priority Implementation Recommendations

Based on common domain needs, consider implementing these use cases first:

### High Priority (Immediate Value)
1. **Point-in-Time Reconstruction** - Essential for debugging and auditing
2. **Correction Audit Trail** - Required for compliance
3. **Late-Arriving Events Detection** - Common in real-world systems
4. **Balance History** - Frequently requested by business users

### Medium Priority (Enhanced Capabilities)
5. **Change Detection** - Useful for reconciliation
6. **Backdated Transaction Detection** - Risk management
7. **Event Lineage Tracking** - Debugging and support
8. **Regulatory Snapshots** - Compliance reporting

### Lower Priority (Advanced Analytics)
9. **Event Velocity Analysis** - Fraud detection, capacity planning
10. **Bi-Temporal Point Queries** - Complex regulatory scenarios
11. **Reconciliation Snapshots** - Advanced reconciliation needs

## Example Implementation Priority

Start with these three methods in `TransactionService`:

```java
// 1. Point-in-time reconstruction (HIGH)
public CompletableFuture<AccountSnapshot> getAccountAsKnownAt(
    String accountId, Instant transactionTime)

// 2. Correction audit trail (HIGH)
public CompletableFuture<List<CorrectionAudit>> getCorrectionsInPeriod(
    String accountId, Instant periodStart, Instant periodEnd)

// 3. Late-arriving events (HIGH)
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
    getLateArrivingEvents(String accountId, Duration threshold)
```

## See Also

- `BITEMPORAL_DOMAIN_QUERY_PATTERN.md` - Basic query patterns
- `BITEMPORAL_BASICS_GUIDE.md` - Bi-temporal concepts
- `TransactionService.java` - Current implementation examples

