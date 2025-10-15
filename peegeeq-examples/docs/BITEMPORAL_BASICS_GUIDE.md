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

### Example 1: Bank Deposit with Correction

**Timeline**:
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

### Example 2: Late Trade Booking

**Timeline**:
- **2025-10-05 09:30** - Trade executed on exchange (valid time)
- **2025-10-05 16:45** - Trade booked in back office system (transaction time)
- **2025-10-06 08:15** - Price correction discovered (correction)

**Bi-Temporal Tracking**:
```
Event 1: Trade Booked - Buy 100 AAPL @ $150.00
  Valid Time: 2025-10-05 09:30 (when trade executed)
  Transaction Time: 2025-10-05 16:45 (when we booked it)

Event 2: Price Correction - Buy 100 AAPL @ $149.75
  Valid Time: 2025-10-05 09:30 (SAME - when trade actually executed)
  Transaction Time: 2025-10-06 08:15 (when we corrected it)
```

**Key Questions Answered**:
- What trades happened on Oct 5th? (Query by valid time)
- What did our books show at market close Oct 5th? (Query by transaction time)
- When did we discover the pricing error? (Transaction time of correction)

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

### Example: Recording a Bank Deposit

**Scenario**: Customer made a $1000 deposit at 2:00 PM, but we're recording it at 2:05 PM.

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

**Example Usage**:
```java
// Customer deposited $1000 at 2:00 PM (valid time)
// We're recording it now at 2:05 PM (transaction time)
TransactionRequest request = new TransactionRequest(
    "ACC-123",
    new BigDecimal("1000.00"),
    TransactionType.CREDIT,
    "ATM Deposit",
    "REF-456",
    Instant.parse("2025-10-07T14:00:00Z")  // When it actually happened
);

BiTemporalEvent<TransactionEvent> result = recordTransaction(request).join();
```

**What Happens**:
1. Event created with transaction details ($1000 deposit)
2. Valid time set to 2:00 PM (when deposit actually occurred)
3. Event appended to event store
4. Transaction time set automatically to 2:05 PM (when we recorded it)
5. Returns `BiTemporalEvent` with both times

**Result in Database**:
```
Event: TransactionRecorded
  Valid Time: 2025-10-07 14:00:00 (when deposit happened)
  Transaction Time: 2025-10-07 14:05:00 (when we recorded it)
  Payload: {"transactionId": "...", "amount": 1000.00, "type": "CREDIT"}
```

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

### Example: Point-in-Time Balance Calculation

**Scenario**: Account has multiple transactions, we want balance at different points in time.

**Transaction History**:
```
2025-10-01 09:00 - Deposit $1000 (valid time)
2025-10-02 10:30 - Withdrawal $200 (valid time)
2025-10-03 14:15 - Deposit $500 (valid time)
2025-10-04 11:00 - Correction: Oct 1 deposit was actually $1200 (correction made Oct 4)
```

```java
public CompletableFuture<BigDecimal> getAccountBalance(String accountId, Instant asOf) {
    return eventStore.query(EventQuery.all())
        .thenApply(events -> {
            BigDecimal balance = events.stream()
                // Filter by account
                .filter(event -> accountId.equals(event.getPayload().getAccountId()))

                // Only events up to asOf time (by VALID time)
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

**Example Queries**:
```java
// What was the balance at end of Oct 2?
// Answer: $800 ($1000 deposit - $200 withdrawal)
BigDecimal oct2Balance = getAccountBalance("ACC-123",
    Instant.parse("2025-10-02T23:59:59Z")).join();

// What was the balance at end of Oct 3?
// Answer: $1300 ($1000 + $500 - $200)
BigDecimal oct3Balance = getAccountBalance("ACC-123",
    Instant.parse("2025-10-03T23:59:59Z")).join();

// What is the current balance (after correction)?
// Answer: $1500 ($1200 corrected deposit + $500 - $200)
BigDecimal currentBalance = getAccountBalance("ACC-123", Instant.now()).join();
```

**Key Point**: Balance calculation uses **valid time** (when transactions actually occurred), not transaction time (when we recorded them). This gives us the true business state at any point in time.

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

### Example: Correcting a Deposit Amount

**Timeline**:
- **2025-10-07 14:00** - Customer deposits money (valid time)
- **2025-10-07 14:05** - Teller records $1000 deposit (transaction time)
- **2025-10-09 10:00** - Audit discovers actual amount was $1500 (correction time)

**Step 1: Original Transaction** (recorded 2025-10-07 14:05):
```java
TransactionEvent original = new TransactionEvent(
    "TXN-123",
    "ACC-456",
    new BigDecimal("1000.00"),  // WRONG amount
    TransactionType.CREDIT,
    "ATM Deposit",
    "REF-789"
);

// Record with valid time = when deposit actually happened
eventStore.append("TransactionRecorded", original,
    Instant.parse("2025-10-07T14:00:00Z"));
```

**Result in Database**:
```
Event 1: TransactionRecorded
  Valid Time: 2025-10-07 14:00:00 (when deposit happened)
  Transaction Time: 2025-10-07 14:05:00 (when teller recorded it)
  Amount: $1000.00 (WRONG)
```

**Step 2: Correction** (made 2025-10-09 10:00):
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

**Usage**:
```java
TransactionCorrectionRequest correction = new TransactionCorrectionRequest(
    new BigDecimal("1500.00"),  // Correct amount
    "Audit found receipt showing $1500, not $1000"
);

correctTransaction("TXN-123", correction);
```

**Result in Database**:
```
Event 1: TransactionRecorded
  Valid Time: 2025-10-07 14:00:00 (when deposit happened)
  Transaction Time: 2025-10-07 14:05:00 (when teller recorded it)
  Amount: $1000.00 (WRONG)

Event 2: TransactionCorrected
  Valid Time: 2025-10-07 14:00:00 (SAME - when deposit actually happened)
  Transaction Time: 2025-10-09 10:00:00 (when auditor corrected it)
  Amount: $1500.00 (CORRECT)
```

**Key Points**:
- **Same transaction ID** - Links correction to original
- **Same valid time** - When transaction actually occurred (doesn't change)
- **Different transaction time** - When correction was made (new)
- **Different event type** - "TransactionCorrected" vs "TransactionRecorded"
- **Complete audit trail** - Both versions preserved forever

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

**Complete Audit Trail**:
```
Version 1: TransactionRecorded
  Valid Time: 2025-10-07 14:00:00 (when it happened)
  Transaction Time: 2025-10-07 14:05:00 (when we recorded it)
  Amount: $1000.00 (WRONG)
  Recorded by: Teller-001

Version 2: TransactionCorrected
  Valid Time: 2025-10-07 14:00:00 (SAME - when it actually happened)
  Transaction Time: 2025-10-09 10:00:00 (when we corrected it)
  Amount: $1500.00 (CORRECT)
  Corrected by: Auditor-005
  Reason: "Audit found receipt showing $1500, not $1000"
```

**Audit Questions Answered**:
- **What did we think happened?** $1000 deposit on Oct 7 at 2:00 PM
- **When did we record our initial understanding?** Oct 7 at 2:05 PM
- **What actually happened?** $1500 deposit on Oct 7 at 2:00 PM (same time, different amount)
- **When did we discover the error?** Oct 9 at 10:00 AM
- **Who made the correction?** Auditor-005
- **Why was it corrected?** Receipt showed $1500, not $1000
- **What was the account balance on Oct 8?** Depends on perspective:
  - **Business reality**: $1500 (using corrected amount)
  - **What we knew then**: $1000 (using transaction time filter)

### Real-World Example: Regulatory Inquiry

**Scenario**: Regulator asks "What did your system show for account ACC-456 on October 8th?"

```java
// What we KNEW on Oct 8 (transaction time perspective)
List<BiTemporalEvent<TransactionEvent>> knownOnOct8 = eventStore.query(
    EventQuery.builder()
        .aggregateId("ACC-456")
        .asOfTransactionTime(Instant.parse("2025-10-08T23:59:59Z"))
        .build()
).join();
// Result: Shows $1000 deposit (correction hadn't been made yet)

// What ACTUALLY happened on Oct 8 (valid time perspective)
List<BiTemporalEvent<TransactionEvent>> actualOnOct8 = eventStore.query(
    EventQuery.builder()
        .aggregateId("ACC-456")
        .asOfValidTime(Instant.parse("2025-10-08T23:59:59Z"))
        .build()
).join();
// Result: Shows $1500 deposit (using latest corrected information)
```

**Answer to Regulator:** "On October 8th, our system showed a $1000 deposit. We later discovered on October 9th that the actual amount was $1500, and we have a complete audit trail of this correction."

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

### Scenario: Investment Bank Trade Processing

**Real-World Timeline**:
- **09:30 AM** - Trader executes: Buy 1000 AAPL @ $150.00 (valid time)
- **04:45 PM** - Back office books trade in system (transaction time)
- **Next day 08:15 AM** - Reconciliation finds price was actually $149.75 (correction)

**Business Requirements**:
- Track when trades actually executed (for regulatory reporting)
- Track when we recorded them (for operational audit)
- Correct errors without losing history
- Prove compliance with trade reporting rules
- Calculate P&L at any point in time

### Implementation

### Implementation Example

**Step 1: Record Trade** (Back office at 4:45 PM):
```java
public CompletableFuture<BiTemporalEvent<TransactionEvent>> recordTrade(TradeRequest request) {
    TransactionEvent event = new TransactionEvent(
        UUID.randomUUID().toString(),
        request.getAccountId(),
        request.getAmount(),                    // $150,000 (1000 * $150.00)
        TransactionType.DEBIT,                  // Trade purchase
        "Trade: " + request.getInstrument() + " " + request.getQuantity() + " @ " + request.getPrice(),
        request.getTradeId()
    );

    // Valid time = when trade was executed (9:30 AM)
    Instant tradeTime = request.getTradeTime();

    return eventStore.append("TransactionRecorded", event, tradeTime);
}
```

**Usage**:
```java
TradeRequest trade = new TradeRequest(
    "PORTFOLIO-001",
    new BigDecimal("150000.00"),           // 1000 shares * $150.00
    "Buy 1000 AAPL @ $150.00",
    "TRADE-12345",
    Instant.parse("2025-10-05T09:30:00Z") // When trade executed
);

// Recorded at 4:45 PM, but valid time is 9:30 AM
recordTrade(trade);
```

**Result in Database**:
```
Event: TransactionRecorded
  Valid Time: 2025-10-05 09:30:00 (when trade executed)
  Transaction Time: 2025-10-05 16:45:00 (when back office recorded it)
  Amount: $150,000.00 (WRONG - should be $149,750.00)
```

**Step 2: Correct Trade** (Next day at 8:15 AM during reconciliation):
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
                transactionId,                              // SAME transaction ID
                original.getPayload().getAccountId(),
                correctedAmount,                            // CORRECTED amount
                original.getPayload().getType(),
                original.getPayload().getDescription() + " [CORRECTED: " + reason + "]",
                original.getPayload().getReference()
            );

            // Use original trade time (valid time) - when trade actually executed
            return eventStore.append("TransactionCorrected", correction, original.getValidTime());
        });
}
```

**Usage**:
```java
// Reconciliation discovers actual price was $149.75, not $150.00
correctTrade(
    "TRADE-12345",
    new BigDecimal("149750.00"),  // 1000 * $149.75
    "Reconciliation: Exchange confirms price was $149.75"
);
```

**Result in Database**:
```
Event 1: TransactionRecorded
  Valid Time: 2025-10-05 09:30:00 (when trade executed)
  Transaction Time: 2025-10-05 16:45:00 (when back office recorded it)
  Amount: $150,000.00 (WRONG)

Event 2: TransactionCorrected
  Valid Time: 2025-10-05 09:30:00 (SAME - when trade actually executed)
  Transaction Time: 2025-10-06 08:15:00 (when reconciliation corrected it)
  Amount: $149,750.00 (CORRECT)
```

**Step 3: Regulatory Reporting**:

### Example: Daily Trade Report for Oct 5th

```java
// Report all trades that EXECUTED on Oct 5th (by valid time)
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

// Report all corrections MADE on Oct 6th (by transaction time)
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

**Usage Examples**:
```java
// Regulatory report: "All trades executed on Oct 5th"
List<BiTemporalEvent<TransactionEvent>> oct5Trades =
    getDailyTrades(LocalDate.of(2025, 10, 5)).join();
// Returns: AAPL trade with corrected amount $149,750 (latest version)

// Operational report: "All corrections made on Oct 6th"
List<BiTemporalEvent<TransactionEvent>> oct6Corrections =
    getTodayCorrections().join();
// Returns: AAPL trade correction made at 8:15 AM

// Audit question: "What did we report on Oct 5th evening?"
List<BiTemporalEvent<TransactionEvent>> reportedOct5 = eventStore.query(
    EventQuery.builder()
        .asOfTransactionTime(Instant.parse("2025-10-05T23:59:59Z"))
        .build()
).join();
// Returns: AAPL trade with original amount $150,000 (correction not made yet)
```

**Key Benefits**:
- **Regulatory compliance**: Can prove exactly what trades executed when
- **Operational audit**: Can track when corrections were made and why
- **Time travel**: Can recreate any report as it existed at any point in time
- **Complete transparency**: Regulators can see both original and corrected data

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

---

# Quick Reference

A quick reference guide for common bi-temporal query patterns in PeeGeeQ.

## Time Dimensions Reference

| Dimension | Also Known As | Meaning | Example |
|-----------|---------------|---------|---------|
| **Valid Time** | Business Time, Effective Time | When the event actually occurred in the real world | Transaction date: Nov 15, 2024 |
| **Transaction Time** | System Time, Knowledge Time | When the event was recorded in the system | Recorded: Nov 20, 2024 |

## Common Query Patterns

### 1. Current State Queries

```java
// Get all current events for an aggregate
eventStore.query(EventQuery.forAggregate(accountId))

// Get all current events of a specific type
eventStore.query(EventQuery.forEventType("TransactionRecorded"))

// Get all current events for aggregate AND type
eventStore.query(EventQuery.forAggregateAndType(accountId, "TransactionRecorded"))
```

### 2. Point-in-Time Queries

```java
// What happened on a specific business date?
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .asOfValidTime(businessDate)
        .build()
)

// What did we know on a specific system date?
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .asOfTransactionTime(systemDate)
        .build()
)

// What did we know on date Y about what happened on date X?
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .asOfValidTime(businessDate)
        .asOfTransactionTime(systemDate)
        .build()
)
```

### 3. Time Range Queries

```java
// All events in a valid time range
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .validTimeStart(startDate)
        .validTimeEnd(endDate)
        .build()
)

// All events recorded in a transaction time range
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .transactionTimeStart(startDate)
        .transactionTimeEnd(endDate)
        .build()
)
```

## Advanced Domain Patterns

### Account Balance Calculation

```java
public CompletableFuture<BigDecimal> getAccountBalance(
        String accountId, Instant asOf) {
    return queryTransactionsByAccount(accountId)
        .thenApply(transactions -> {
            return transactions.stream()
                .filter(event -> !event.getValidTime().isAfter(asOf))
                .map(event -> calculateAmount(event))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
}
```

### Late-Arriving Events

```java
public CompletableFuture<List<BiTemporalEvent<T>>> getLateArrivingEvents(
        String accountId, Duration threshold) {
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

### Correction Audit Trail

```java
public CompletableFuture<List<BiTemporalEvent<T>>> getCorrectionsInPeriod(
        String accountId, Instant periodStart, Instant periodEnd) {
    return queryCorrectedTransactions(accountId)
        .thenApply(corrections -> corrections.stream()
            .filter(event -> !event.getTransactionTime().isBefore(periodStart))
            .filter(event -> !event.getTransactionTime().isAfter(periodEnd))
            .collect(Collectors.toList())
        );
}
```

### Change Detection

```java
public CompletableFuture<ChangeReport> getChangesBetween(
        String accountId,
        Instant fromTransactionTime,
        Instant toTransactionTime) {

    CompletableFuture<List<BiTemporalEvent<T>>> beforeState =
        eventStore.query(
            EventQuery.builder()
                .aggregateId(accountId)
                .asOfTransactionTime(fromTransactionTime)
                .build()
        );

    CompletableFuture<List<BiTemporalEvent<T>>> afterState =
        eventStore.query(
            EventQuery.builder()
                .aggregateId(accountId)
                .asOfTransactionTime(toTransactionTime)
                .build()
        );

    return beforeState.thenCombine(afterState,
        (before, after) -> calculateChanges(before, after)
    );
}
```



## EventQuery API Reference

### Builder Methods

| Method | Purpose | Example |
|--------|---------|---------|
| `aggregateId(String)` | Filter by aggregate | `.aggregateId("ACC-001")` |
| `eventType(String)` | Filter by event type | `.eventType("OrderCreated")` |
| `asOfValidTime(Instant)` | Point-in-time valid | `.asOfValidTime(Instant.now())` |
| `asOfTransactionTime(Instant)` | Point-in-time transaction | `.asOfTransactionTime(reportDate)` |
| `validTimeStart(Instant)` | Valid time range start | `.validTimeStart(startDate)` |
| `validTimeEnd(Instant)` | Valid time range end | `.validTimeEnd(endDate)` |
| `transactionTimeStart(Instant)` | Transaction time range start | `.transactionTimeStart(startDate)` |
| `transactionTimeEnd(Instant)` | Transaction time range end | `.transactionTimeEnd(endDate)` |

### Convenience Methods

| Method | Equivalent Builder | Use Case |
|--------|-------------------|----------|
| `EventQuery.all()` | `builder().build()` | Get all events |
| `EventQuery.forEventType(type)` | `builder().eventType(type).build()` | All events of type |
| `EventQuery.forAggregate(id)` | `builder().aggregateId(id).build()` | All events for aggregate |
| `EventQuery.forAggregateAndType(id, type)` | `builder().aggregateId(id).eventType(type).build()` | Aggregate + type |
| `EventQuery.asOfValidTime(instant)` | `builder().asOfValidTime(instant).build()` | Valid time snapshot |
| `EventQuery.asOfTransactionTime(instant)` | `builder().asOfTransactionTime(instant).build()` | Transaction time snapshot |

## Common Use Case Patterns

### Regulatory Reporting
```java
// Generate report as it was known on reporting date
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .asOfTransactionTime(regulatoryReportingDate)
        .build()
)
```

### Reconciliation
```java
// Find events that arrived late (after period closed)
events.stream()
    .filter(e -> e.getValidTime().isBefore(periodEnd))
    .filter(e -> e.getTransactionTime().isAfter(periodEnd))
```

### Audit Trail
```java
// Show all corrections made to a transaction
getTransactionVersions(transactionId)
    .thenApply(versions ->
        versions.stream()
            .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
            .collect(Collectors.toList())
    )
```

### Time Travel Debugging
```java
// Reproduce system state as it was on a specific date
eventStore.query(
    EventQuery.builder()
        .asOfTransactionTime(problemReportedDate)
        .build()
)
```

## Quick Reference Best Practices

### 1. Always Use Async Patterns
```java
// ✅ CORRECT - Non-blocking
public CompletableFuture<List<Event>> query() {
    return eventStore.query(EventQuery.forAggregate(id));
}

// ❌ WRONG - Blocks event loop
public List<Event> query() {
    return eventStore.query(EventQuery.forAggregate(id)).join();
}
```

### 2. Set Aggregate ID When Appending
```java
// ✅ CORRECT - Can query by aggregate
eventStore.append("TransactionRecorded", event, validTime,
                 Map.of(), null, accountId)

// ❌ WRONG - Cannot query by aggregate
eventStore.append("TransactionRecorded", event, validTime)
```

### 3. Use Domain-Specific Wrappers
```java
// ✅ CORRECT - Domain language
transactionService.queryRecordedTransactions(accountId)

// ❌ LESS CLEAR - Technical language
eventStore.query(EventQuery.forAggregateAndType(accountId, "TransactionRecorded"))
```

### 4. Handle Time Zones Consistently
```java
// ✅ CORRECT - Use UTC for storage
Instant validTime = localDateTime.toInstant(ZoneOffset.UTC)

// ❌ WRONG - Ambiguous time zones
Instant validTime = Instant.now() // Uses system default
```

## Common Scenarios Quick Reference

| Scenario | Query Pattern | Time Dimension |
|----------|---------------|----------------|
| "What's the current balance?" | `asOfValidTime(now)` | Valid Time |
| "What did we report last quarter?" | `asOfTransactionTime(quarterEnd)` | Transaction Time |
| "Show me all corrections this month" | `eventType("Corrected") + transactionTimeRange` | Transaction Time |
| "Find late-arriving transactions" | `validTime < transactionTime - threshold` | Both |
| "Reproduce bug from last week" | `asOfTransactionTime(lastWeek)` | Transaction Time |
| "Calculate historical balance" | `asOfValidTime(historicalDate)` | Valid Time |

---

## Related Guides

- **BITEMPORAL_DOMAIN_QUERY_PATTERN.md** - Domain-specific query patterns and funds & custody use cases
- **REACTIVE_BITEMPORAL_GUIDE.md** - Reactive integration with Spring WebFlux
- **TRANSACTIONAL_BITEMPORAL_GUIDE.md** - Multi-store transactions
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns
