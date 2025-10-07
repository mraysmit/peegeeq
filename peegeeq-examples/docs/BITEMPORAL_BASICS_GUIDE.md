# Bi-Temporal Event Store Basics Guide

**Example**: `springboot-bitemporal`  
**Use Case**: Financial transaction processing with complete audit trail  
**Focus**: Valid time vs transaction time, point-in-time queries, corrections

---

## What This Guide Covers

This guide introduces bi-temporal event storage concepts using PeeGeeQ's event store. It demonstrates how to track both when events happened in the real world (valid time) and when they were recorded in the system (transaction time).

**Key Patterns**:
- Bi-temporal event appending (valid time + transaction time)
- Historical queries and point-in-time reconstruction
- Event corrections with complete audit trail
- Account balance calculations from event history
- Regulatory compliance (SOX, GDPR, MiFID II)

**What This Guide Does NOT Cover**:
- Reactive integration (see REACTIVE_BITEMPORAL_GUIDE.md)
- Multi-store transactions (see TRANSACTIONAL_BITEMPORAL_GUIDE.md)
- Outbox pattern (see CONSUMER_GROUP_GUIDE.md)
- Message retry/DLQ (see DLQ_RETRY_GUIDE.md)

---

## Core Concepts

### Bi-Temporal Dimensions

**Two Time Dimensions**:

1. **Valid Time** - When the event actually happened in the real world
   - Business time
   - When the transaction occurred
   - Set by application
   - Can be in the past (backdated transactions)

2. **Transaction Time** - When the event was recorded in the database
   - System time
   - When we learned about the event
   - Set automatically by database (`NOW()`)
   - Always current time

**Why Two Dimensions?**

**Problem**: Real world vs system knowledge don't always align.

**Example**:
- **2025-10-07 14:00** - Customer makes $1000 deposit (valid time)
- **2025-10-07 14:05** - We record it in system (transaction time)
- **2025-10-09 10:00** - We discover amount was actually $1500 (correction)

**Bi-Temporal Tracking**:
```
Event 1: Deposit $1000
  Valid Time: 2025-10-07 14:00 (when it happened)
  Transaction Time: 2025-10-07 14:05 (when we recorded it)

Event 2: Correction to $1500
  Valid Time: 2025-10-07 14:00 (SAME - when it actually happened)
  Transaction Time: 2025-10-09 10:00 (when we corrected it)
```

**Benefits**:
- Complete audit trail (who changed what when)
- Point-in-time reconstruction (what did we know on 2025-10-08?)
- Regulatory compliance (prove what we knew and when)
- Error correction without losing history

### Event Store Schema

**Database Table**:
```sql
CREATE TABLE IF NOT EXISTS transaction_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    valid_time TIMESTAMPTZ NOT NULL,        -- When event happened
    transaction_time TIMESTAMPTZ DEFAULT NOW()  -- When recorded
);

CREATE INDEX idx_transaction_events_event_type ON transaction_events(event_type);
CREATE INDEX idx_transaction_events_valid_time ON transaction_events(valid_time);
CREATE INDEX idx_transaction_events_transaction_time ON transaction_events(transaction_time);
```

**Key Fields**:
- `event_type`: Type of event (e.g., "TransactionRecorded", "TransactionCorrected")
- `payload`: Event data as JSONB
- `valid_time`: When event occurred in real world
- `transaction_time`: When event was recorded (automatic)

---

## Basic Operations

### Appending Events

**Pattern**: Record event with valid time.

```java
@Service
public class TransactionService {
    
    private final EventStore<TransactionEvent> eventStore;
    
    public CompletableFuture<BiTemporalEvent<TransactionEvent>> recordTransaction(
            TransactionRequest request) {
        
        // Create event
        TransactionEvent event = new TransactionEvent(
            UUID.randomUUID().toString(),  // transactionId
            request.getAccountId(),
            request.getAmount(),
            request.getType(),             // CREDIT or DEBIT
            request.getDescription(),
            request.getReference()
        );
        
        // Valid time (when transaction occurred)
        Instant validTime = request.getValidTime() != null 
            ? request.getValidTime() 
            : Instant.now();
        
        // Append to event store
        return eventStore.append("TransactionRecorded", event, validTime);
    }
}
```

**What Happens**:
1. Event created with transaction details
2. Valid time set (when transaction occurred)
3. Event appended to event store
4. Transaction time set automatically by database
5. Returns `BiTemporalEvent` with both times

### Querying Events

**Get All Events**:
```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAllTransactions() {
    return eventStore.query(EventQuery.all());
}
```

**Filter by Account**:
```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAccountHistory(
        String accountId) {
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> accountId.equals(event.getPayload().getAccountId()))
            .collect(Collectors.toList())
        );
}
```

**Point-in-Time Query**:
```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTransactionsAsOf(
        Instant pointInTime) {
    
    return eventStore.query(EventQuery.asOfValidTime(pointInTime));
}
```

### BiTemporalEvent Structure

**What You Get Back**:
```java
BiTemporalEvent<TransactionEvent> event = ...;

// Event metadata
String eventType = event.getEventType();           // "TransactionRecorded"
Instant validTime = event.getValidTime();          // When it happened
Instant transactionTime = event.getTransactionTime();  // When recorded

// Event payload
TransactionEvent payload = event.getPayload();
String accountId = payload.getAccountId();
BigDecimal amount = payload.getAmount();
TransactionType type = payload.getType();
```

---

## Point-in-Time Queries

### Account Balance Calculation

**Pattern**: Calculate balance as of specific date.

```java
public CompletableFuture<BigDecimal> getAccountBalance(String accountId, Instant asOf) {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> {
            BigDecimal balance = events.stream()
                // Filter by account
                .filter(event -> accountId.equals(event.getPayload().getAccountId()))
                
                // Only events up to asOf time
                .filter(event -> !event.getValidTime().isAfter(asOf))
                
                // Calculate balance
                .map(event -> {
                    TransactionEvent txn = event.getPayload();
                    return txn.getType() == TransactionType.CREDIT
                        ? txn.getAmount()
                        : txn.getAmount().negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            return balance;
        });
}
```

**Example**:
```java
// What was the balance on 2025-10-01?
BigDecimal balance = getAccountBalance("ACC-123", 
    Instant.parse("2025-10-01T23:59:59Z")).join();

// What is the current balance?
BigDecimal currentBalance = getAccountBalance("ACC-123", Instant.now()).join();
```

### Transaction History

**Pattern**: Get complete transaction history for account.

```java
public CompletableFuture<AccountHistoryResponse> getAccountHistory(String accountId) {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> {
            List<BiTemporalEvent<TransactionEvent>> accountTransactions = events.stream()
                .filter(event -> accountId.equals(event.getPayload().getAccountId()))
                .sorted((a, b) -> a.getValidTime().compareTo(b.getValidTime()))
                .collect(Collectors.toList());
            
            return new AccountHistoryResponse(accountId, accountTransactions);
        });
}
```

**Response Structure**:
```java
public class AccountHistoryResponse {
    private final String accountId;
    private final List<BiTemporalEvent<TransactionEvent>> transactions;
    
    // Each transaction includes:
    // - Event type (TransactionRecorded, TransactionCorrected)
    // - Valid time (when transaction occurred)
    // - Transaction time (when recorded)
    // - Payload (transaction details)
}
```

---

## Event Corrections

### Correction Pattern

**Scenario**: Discover error in recorded transaction amount.

**Original Transaction** (2025-10-07 14:00):
```java
TransactionEvent original = new TransactionEvent(
    "TXN-123",
    "ACC-456",
    new BigDecimal("1000.00"),  // WRONG amount
    TransactionType.CREDIT,
    "Deposit",
    "REF-789"
);

eventStore.append("TransactionRecorded", original, 
    Instant.parse("2025-10-07T14:00:00Z"));
```

**Correction** (2025-10-09 10:00):
```java
public CompletableFuture<BiTemporalEvent<TransactionEvent>> correctTransaction(
        String transactionId, TransactionCorrectionRequest request) {
    
    // Find original transaction
    return eventStore.query(EventQuery.all())
        .thenCompose(events -> {
            BiTemporalEvent<TransactionEvent> original = events.stream()
                .filter(event -> transactionId.equals(event.getPayload().getTransactionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            
            // Create correction event
            TransactionEvent correctionEvent = new TransactionEvent(
                transactionId,                              // SAME transaction ID
                original.getPayload().getAccountId(),
                request.getCorrectedAmount(),               // CORRECTED amount
                original.getPayload().getType(),
                original.getPayload().getDescription() + " [CORRECTED: " + request.getReason() + "]",
                original.getPayload().getReference()
            );
            
            // Append with ORIGINAL valid time
            return eventStore.append(
                "TransactionCorrected", 
                correctionEvent, 
                original.getValidTime()  // SAME valid time as original!
            );
        });
}
```

**Key Points**:
- **Same transaction ID** - Links correction to original
- **Same valid time** - When transaction actually occurred
- **Different transaction time** - When correction was made
- **Different event type** - "TransactionCorrected" vs "TransactionRecorded"

### Audit Trail

**Query All Versions**:
```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTransactionVersions(
        String transactionId) {
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> transactionId.equals(event.getPayload().getTransactionId()))
            .sorted((a, b) -> a.getTransactionTime().compareTo(b.getTransactionTime()))
            .collect(Collectors.toList())
        );
}
```

**Result**:
```
Version 1:
  Event Type: TransactionRecorded
  Valid Time: 2025-10-07 14:00:00 (when it happened)
  Transaction Time: 2025-10-07 14:05:00 (when we recorded it)
  Amount: $1000.00 (WRONG)

Version 2:
  Event Type: TransactionCorrected
  Valid Time: 2025-10-07 14:00:00 (SAME - when it actually happened)
  Transaction Time: 2025-10-09 10:00:00 (when we corrected it)
  Amount: $1500.00 (CORRECT)
```

**Audit Questions Answered**:
- What did we think happened? ($1000 deposit)
- When did we think it happened? (2025-10-07 14:00)
- When did we record it? (2025-10-07 14:05)
- What actually happened? ($1500 deposit)
- When did we discover the error? (2025-10-09 10:00)
- Who made the correction? (from transaction time + audit logs)

---

## Regulatory Compliance

### SOX Compliance

**Requirement**: Complete audit trail of all financial transactions.

**How Bi-Temporal Helps**:
- Every transaction recorded with timestamp
- Corrections don't delete original records
- Complete history of who changed what when
- Immutable append-only log

**Query for Audit**:
```java
// Get all transactions for period
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTransactionsForPeriod(
        Instant startDate, Instant endDate) {
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> !event.getValidTime().isBefore(startDate))
            .filter(event -> !event.getValidTime().isAfter(endDate))
            .collect(Collectors.toList())
        );
}

// Get all corrections made in period
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getCorrections(
        Instant startDate, Instant endDate) {
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> "TransactionCorrected".equals(event.getEventType()))
            .filter(event -> !event.getTransactionTime().isBefore(startDate))
            .filter(event -> !event.getTransactionTime().isAfter(endDate))
            .collect(Collectors.toList())
        );
}
```

### GDPR Compliance

**Requirement**: Provide complete history of personal data changes.

**How Bi-Temporal Helps**:
- Track when data was recorded (transaction time)
- Track when data was valid (valid time)
- Show all corrections and updates
- Prove compliance with data accuracy requirements

### MiFID II Compliance

**Requirement**: Transaction reporting with complete audit trail.

**How Bi-Temporal Helps**:
- Record exact time of transaction (valid time)
- Record when transaction was reported (transaction time)
- Track all amendments and corrections
- Reconstruct state at any point in time

---

## Financial Transaction Use Case

### Scenario

**Back Office Trade Booking**:
- Traders execute trades throughout the day
- Trades booked in system (may be delayed)
- Errors discovered during reconciliation
- Need to correct without losing history
- Regulatory reporting requires complete audit trail

### Implementation

**Record Trade**:
```java
public CompletableFuture<BiTemporalEvent<TransactionEvent>> recordTrade(TradeRequest request) {
    TransactionEvent event = new TransactionEvent(
        UUID.randomUUID().toString(),
        request.getAccountId(),
        request.getAmount(),
        TransactionType.DEBIT,  // Trade purchase
        "Trade: " + request.getInstrument() + " " + request.getQuantity() + " @ " + request.getPrice(),
        request.getTradeId()
    );
    
    // Valid time = when trade was executed
    Instant tradeTime = request.getTradeTime();
    
    return eventStore.append("TransactionRecorded", event, tradeTime);
}
```

**Correct Trade** (discovered error during reconciliation):
```java
public CompletableFuture<BiTemporalEvent<TransactionEvent>> correctTrade(
        String transactionId, BigDecimal correctedAmount, String reason) {
    
    return eventStore.query(EventQuery.all())
        .thenCompose(events -> {
            BiTemporalEvent<TransactionEvent> original = events.stream()
                .filter(e -> transactionId.equals(e.getPayload().getTransactionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            
            TransactionEvent correction = new TransactionEvent(
                transactionId,
                original.getPayload().getAccountId(),
                correctedAmount,
                original.getPayload().getType(),
                original.getPayload().getDescription() + " [CORRECTED: " + reason + "]",
                original.getPayload().getReference()
            );
            
            // Use original trade time (valid time)
            return eventStore.append("TransactionCorrected", correction, original.getValidTime());
        });
}
```

**Regulatory Reporting**:
```java
// Report all trades for day
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getDailyTrades(LocalDate date) {
    Instant startOfDay = date.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> !e.getValidTime().isBefore(startOfDay))
            .filter(e -> e.getValidTime().isBefore(endOfDay))
            .collect(Collectors.toList())
        );
}

// Report all corrections made today
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTodayCorrections() {
    Instant startOfDay = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    
    return eventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> "TransactionCorrected".equals(e.getEventType()))
            .filter(e -> !e.getTransactionTime().isBefore(startOfDay))
            .filter(e -> e.getTransactionTime().isBefore(endOfDay))
            .collect(Collectors.toList())
        );
}
```

---

## Best Practices

### 1. Use Appropriate Valid Time

**Why**: Valid time represents when event actually occurred.

```java
// ✅ CORRECT - use actual transaction time
Instant validTime = request.getTransactionTime();
eventStore.append("TransactionRecorded", event, validTime);

// ❌ WRONG - using current time for past transaction
Instant validTime = Instant.now();  // Wrong if transaction was in past
eventStore.append("TransactionRecorded", event, validTime);
```

### 2. Preserve Valid Time in Corrections

**Why**: Corrections fix data but don't change when event occurred.

```java
// ✅ CORRECT - use original valid time
return eventStore.append("TransactionCorrected", correction, original.getValidTime());

// ❌ WRONG - using current time
return eventStore.append("TransactionCorrected", correction, Instant.now());
```

### 3. Use Descriptive Event Types

**Why**: Makes audit trail clear and queryable.

```java
// ✅ CORRECT - clear event types
eventStore.append("TransactionRecorded", event, validTime);
eventStore.append("TransactionCorrected", correction, validTime);
eventStore.append("TransactionCancelled", cancellation, validTime);

// ❌ WRONG - generic event type
eventStore.append("Event", event, validTime);
```

### 4. Include Correction Reason

**Why**: Audit trail should explain why correction was made.

```java
// ✅ CORRECT - include reason
String description = original.getDescription() + 
    " [CORRECTED: Reconciliation break - wrong amount]";

// ❌ WRONG - no explanation
String description = original.getDescription() + " [CORRECTED]";
```

### 5. Query by Valid Time for Business Logic

**Why**: Valid time represents business reality.

```java
// ✅ CORRECT - filter by valid time
.filter(event -> !event.getValidTime().isAfter(asOf))

// ❌ WRONG - filter by transaction time
.filter(event -> !event.getTransactionTime().isAfter(asOf))
```

### 6. Query by Transaction Time for Audit

**Why**: Transaction time shows when we learned about events.

```java
// ✅ CORRECT - corrections made today
.filter(event -> event.getTransactionTime().isAfter(startOfDay))

// ❌ WRONG - events that happened today
.filter(event -> event.getValidTime().isAfter(startOfDay))
```

---

## Summary

**Key Takeaways**:

1. **Two Time Dimensions** - Valid time (when it happened) vs transaction time (when recorded)
2. **Append-Only** - Events never deleted, only corrected with new events
3. **Point-in-Time Queries** - Reconstruct state at any moment
4. **Corrections** - Fix errors while preserving complete audit trail
5. **Regulatory Compliance** - SOX, GDPR, MiFID II compliance built-in
6. **Immutable History** - Complete audit trail for investigations

**Related Guides**:
- **REACTIVE_BITEMPORAL_GUIDE.md** - Reactive integration with Spring WebFlux
- **TRANSACTIONAL_BITEMPORAL_GUIDE.md** - Multi-store transactions
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns

