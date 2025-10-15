# Bi-Temporal Event Store: Basics Guide

**Purpose**: Gentle introduction to bi-temporal event storage concepts and implementation

**Audience**: Developers new to bi-temporal data modeling

**Example**: `springboot-bitemporal` - Financial transaction processing with complete audit trail

---

## Introduction

### What is Bi-Temporal Data?

Imagine you're building a financial system. A customer makes a \$1,000 deposit on Monday at 2pm. You record it in your system at 2:05pm. On Wednesday, you discover the deposit was actually \$1,500.

**Traditional databases** would either:
- Overwrite the \$1,000 with \$1,500 (losing history)
- Keep both records but can't answer: "What did we think the balance was on Tuesday?"

**Bi-temporal databases** track TWO time dimensions:
1. **When it happened** (Valid Time) - Monday 2pm
2. **When we knew about it** (Transaction Time) - Monday 2:05pm, corrected Wednesday

This lets you answer questions like:
- "What was the actual balance on Tuesday?" (use Valid Time)
- "What did we THINK the balance was on Tuesday?" (use Transaction Time)
- "When did we discover the error?" (compare Transaction Times)

### Why Bi-Temporal?

**Real-world scenarios in financial services transaction processing**:

**Middle Office Operations**:
1. **Trade Confirmation**
   - Trade executed at 9:30am, confirmed by counterparty at 4:45pm
   - Confirmation contains price correction (executed at $150.00, confirmed at 149.75)
   - Need to track: when trade executed vs when confirmed vs when correction discovered

2. **Position Reconciliation**
   - End-of-day position calculated at 5:00pm
   - Overnight trade discovered next morning (executed yesterday at 3:45pm)
   - Need to recalculate yesterday's position without losing original calculation

3. **Corporate Actions**
   - Dividend announced today, effective date is 3 days ago
   - Stock split processed with backdated effective date
   - Need to adjust historical positions while tracking when adjustment was made

**Back Office Operations**:
1. **Settlement Processing**
   - Trade settles T+2 (2 days after execution)
   - Settlement fails, rescheduled for T+3
   - Need to track: trade date, expected settlement date, actual settlement date, when we learned about failure

2. **Cash Reconciliation**
   - Bank statement shows transaction from 3 days ago
   - Transaction not in our system (late notification)
   - Need to update historical cash position while tracking when we discovered it

3. **Custody Reconciliation**
   - Custodian reports position discrepancy
   - Discrepancy traced to trade from last week
   - Need to correct historical position while maintaining audit trail

**Regulatory Compliance**:
- **MiFID II**: Prove what you reported and when
- **EMIR**: Complete audit trail for derivative trades
- **SOX**: Demonstrate internal controls and change tracking
- **GDPR**: Track data corrections and deletions with timestamps

---

## What This Guide Covers

This guide introduces bi-temporal concepts and shows you how to implement them using PeeGeeQ's event store.

**Learning Path**:
1. **Core Concepts** - Understanding valid time vs transaction time
2. **Basic Implementation** - Appending events with bi-temporal tracking
3. **Querying Patterns** - Point-in-time queries and historical reconstruction
4. **Event Corrections** - Handling errors while maintaining audit trail
5. **Practical Examples** - Financial transactions, account balances, regulatory reporting

**What This Guide Does NOT Cover**:
- Advanced patterns (see BITEMPORAL_ADVANCED_GUIDE.md for reactive integration, multi-store transactions, domain patterns, and financial use cases)
- Outbox pattern (see INTEGRATED_PATTERN_GUIDE.md)
- Consumer groups (see CONSUMER_GROUP_GUIDE.md)
- Message retry/DLQ (see DLQ_RETRY_GUIDE.md)

---

## Core Concepts

### Understanding the Two Time Dimensions

Every event in a bi-temporal system has TWO timestamps:

#### 1. Valid Time (Business Time)

**Definition**: When the event actually happened in the real world.

**Characteristics**:
- Represents business reality
- Set by your application based on business rules
- Can be in the past (backdated transactions)
- Can be in the future (scheduled events)
- Answers: "When did this actually happen?"

**Examples**:
- Trade execution time: 2025-10-05 09:30:00
- Customer deposit time: 2025-10-07 14:00:00
- Policy effective date: 2025-01-01 00:00:00

#### 2. Transaction Time (System Time)

**Definition**: When the event was recorded in the database.

**Characteristics**:
- Represents system knowledge
- Set automatically by the database (`NOW()`)
- Always current time (never in the past or future)
- Immutable once recorded
- Answers: "When did we learn about this?"

**Examples**:
- Trade booked in system: 2025-10-05 16:45:00 (7 hours after execution)
- Deposit recorded: 2025-10-07 14:05:00 (5 minutes after it happened)
- Policy entered: 2024-12-15 10:30:00 (before effective date)

#### Why Two Dimensions?

**The Problem**: Real-world events and system knowledge don't always align.

**Common Scenarios**:
- **Late arrivals**: Event happens, but we learn about it later
- **Corrections**: We recorded something wrong and need to fix it
- **Backdating**: Event effective date is in the past
- **Batch processing**: Events processed hours/days after they occurred

**The Solution**: Track BOTH times to maintain complete history and enable powerful queries.

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

## Implementation with PeeGeeQ

Now that you understand the concepts, let's see how to implement bi-temporal event storage using PeeGeeQ's event store.

### Setup

**Dependencies** (Maven):
```xml
<dependency>
    <groupId>dev.mars.peegeeq</groupId>
    <artifactId>peegeeq-bitemporal</artifactId>
    <version>${peegeeq.version}</version>
</dependency>
```

**Configuration**:
```java
@Configuration
public class BiTemporalConfig {

    @Bean
    public EventStore<TransactionEvent> transactionEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("transaction_events", TransactionEvent.class, databaseService);
    }
}
```

**What This Does**:
- Creates a bi-temporal event store for `TransactionEvent` objects
- Uses table name `transaction_events`
- Automatically manages valid time and transaction time
- Provides query capabilities for temporal data

---

## Basic Operations

### 1. Appending Events

**Pattern**: Record an event with its valid time (when it actually happened).

**Key Points**:
- You provide the **valid time** (business time)
- PeeGeeQ automatically sets the **transaction time** (system time)
- Returns a `BiTemporalEvent` with both timestamps

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

### 2. Querying Events

**Pattern**: Retrieve events using different query strategies.

#### Query All Events

**Use Case**: Get complete event history.

```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAllTransactions() {
    return eventStore.query(EventQuery.all());
}
```

**Returns**: All events with both valid time and transaction time.

#### Filter by Business Criteria

**Use Case**: Get events for a specific account.

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

**Note**: This gets ALL events for the account, including corrections.

#### Point-in-Time Query (Valid Time)

**Use Case**: "What transactions happened before October 5th?"

```java
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTransactionsAsOf(
        Instant pointInTime) {

    return eventStore.query(EventQuery.asOfValidTime(pointInTime));
}
```

**Returns**: Events where `valid_time <= pointInTime`.

**Example**:
```java
// Get all transactions that happened before Oct 5, 2025
Instant oct5 = Instant.parse("2025-10-05T00:00:00Z");
List<BiTemporalEvent<TransactionEvent>> events = getTransactionsAsOf(oct5).join();
```

### 3. Understanding BiTemporalEvent

Every query returns `BiTemporalEvent<T>` objects that contain:

#### Event Metadata (Temporal Information)

```java
BiTemporalEvent<TransactionEvent> event = ...;

// Event type
String eventType = event.getEventType();
// Example: "TransactionRecorded"

// Valid time (when it actually happened)
Instant validTime = event.getValidTime();
// Example: 2025-10-07T14:00:00Z (2:00 PM deposit time)

// Transaction time (when we recorded it)
Instant transactionTime = event.getTransactionTime();
// Example: 2025-10-07T14:05:00Z (2:05 PM when we entered it)

// Additional metadata
Map<String, String> metadata = event.getMetadata();
// Example: {"userId": "john.doe", "source": "mobile-app"}
```

#### Event Payload (Business Data)

```java
// Get the business event
TransactionEvent payload = event.getPayload();

// Access business fields
String accountId = payload.getAccountId();      // "ACC-123"
BigDecimal amount = payload.getAmount();        // 1000.00
TransactionType type = payload.getType();       // CREDIT
String description = payload.getDescription();  // "ATM Deposit"
```

#### Complete Example

```java
// Query events
List<BiTemporalEvent<TransactionEvent>> events = getAllTransactions().join();

// Process each event
for (BiTemporalEvent<TransactionEvent> event : events) {
    System.out.println("Event Type: " + event.getEventType());
    System.out.println("Happened At: " + event.getValidTime());
    System.out.println("Recorded At: " + event.getTransactionTime());
    System.out.println("Account: " + event.getPayload().getAccountId());
    System.out.println("Amount: " + event.getPayload().getAmount());
    System.out.println("---");
}
```

**Output**:
```
Event Type: TransactionRecorded
Happened At: 2025-10-07T14:00:00Z
Recorded At: 2025-10-07T14:05:00Z
Account: ACC-123
Amount: 1000.00
---
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

## Advanced: CloudEvents with JSONB Queries

### Overview

PeeGeeQ's bi-temporal event store stores events as JSONB in PostgreSQL, enabling powerful queries using PostgreSQL's native JSONB operators. When combined with CloudEvents v1.0 specification, you get standardized event metadata plus flexible querying capabilities.

**Key Benefits**:
- **Standardized event format** - CloudEvents v1.0 specification
- **Flexible querying** - PostgreSQL JSONB operators (`->`, `->>`, `@>`, type casting)
- **Bi-temporal + JSONB** - Combine time-based queries with content filtering
- **No schema changes** - Query nested fields without altering database schema

### CloudEvents Structure in JSONB

**CloudEvents v1.0 stored as JSONB**:
```json
{
  "id": "e053ba9a-c952-46d7-aa26-23c38b25bc90",
  "type": "backoffice.trade.new.v1",
  "source": "https://backoffice.example.com/trading",
  "subject": "TRD-001",
  "time": "2025-10-15T14:14:13Z",
  "correlationid": "TRD-001",
  "bookingsystem": "Murex",
  "clearinghouse": "DTCC",
  "data": {
    "tradeId": "TRD-001",
    "symbol": "AAPL",
    "side": "BUY",
    "quantity": 100,
    "price": 150.50,
    "notionalAmount": 15050.00,
    "currency": "USD",
    "counterparty": "Goldman Sachs",
    "status": "NEW",
    "settlementDate": "2025-10-17",
    "bookingSystem": "Murex",
    "clearingHouse": "DTCC"
  }
}
```

**Key Points**:
- **CloudEvents metadata** - `id`, `type`, `source`, `subject`, `time` (standard fields)
- **CloudEvents extensions** - `correlationid`, `bookingsystem`, `clearinghouse` (custom fields at top level)
- **Event data** - Business payload in `data` field

### JSONB Query Patterns

#### 1. Query by CloudEvent Type

**Use Case**: Find all trade NEW events.

```sql
SELECT * FROM events
WHERE payload->>'type' = 'backoffice.trade.new.v1';
```

**Java Example**:
```java
@Test
void queryByCloudEventType() throws Exception {
    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.query(
        "SELECT payload->>'id' as event_id, " +
        "       payload->>'type' as event_type, " +
        "       payload->'data'->>'tradeId' as trade_id " +
        "FROM events " +
        "WHERE payload->>'type' = 'backoffice.trade.new.v1'"
    ).onComplete(ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
    for (Row row : rows) {
        logger.info("Found NEW trade event: {} for trade: {}",
            row.getString("event_id"),
            row.getString("trade_id"));
    }
}
```

#### 2. Query by CloudEvent Extensions

**Use Case**: Find all events for a specific correlation ID (trade lifecycle).

**Important**: CloudEvents extensions are stored as **top-level fields**, not in an `extensions` object.

```sql
-- ✅ CORRECT - Extensions are top-level fields
SELECT * FROM events
WHERE payload->>'correlationid' = 'TRD-001';

-- ❌ WRONG - No 'extensions' object
SELECT * FROM events
WHERE payload->'extensions'->>'correlationid' = 'TRD-001';
```

**Java Example**:
```java
@Test
void queryByCorrelationId() throws Exception {
    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.query(
        "SELECT payload->>'id' as event_id, " +
        "       payload->>'type' as event_type, " +
        "       payload->>'correlationid' as correlation_id " +
        "FROM events " +
        "WHERE payload->>'correlationid' = 'TRD-001' " +
        "ORDER BY valid_time"
    ).onComplete(ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
    for (Row row : rows) {
        logger.info("Found lifecycle event: {} type: {} for trade TRD-001",
            row.getString("event_id"),
            row.getString("event_type"));
    }
}
```

#### 3. Query by Data Payload Fields

**Use Case**: Find large trades (notional > $100,000).

```sql
SELECT * FROM events
WHERE (payload->'data'->>'notionalAmount')::numeric > 100000;
```

**JSONB Operators**:
- `->` - Get JSON object (returns JSONB)
- `->>` - Get text value (returns TEXT)
- `::numeric` - Type cast to numeric for comparisons

**Java Example**:
```java
@Test
void queryByNotionalAmount() throws Exception {
    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.query(
        "SELECT payload->'data'->>'tradeId' as trade_id, " +
        "       (payload->'data'->>'notionalAmount')::numeric as notional, " +
        "       payload->>'type' as event_type " +
        "FROM events " +
        "WHERE (payload->'data'->>'notionalAmount')::numeric > 100000"
    ).onComplete(ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
    for (Row row : rows) {
        logger.info("Found large trade: {} notional: {} event: {}",
            row.getString("trade_id"),
            row.getBigDecimal("notional"),
            row.getString("event_type"));
    }
}
```

#### 4. Combine Bi-Temporal + JSONB Queries

**Use Case**: Find all DTCC trades before market cutoff time.

```sql
SELECT * FROM events
WHERE payload->>'clearinghouse' = 'DTCC'
  AND valid_time < '2025-10-15T16:00:00Z';
```

**Java Example**:
```java
@Test
void combineTimeRangeWithJsonbFilter() throws Exception {
    Instant cutoffTime = baseTime.plus(2, ChronoUnit.HOURS);

    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.preparedQuery(
        "SELECT payload->'data'->>'tradeId' as trade_id, " +
        "       payload->>'clearinghouse' as clearing_house, " +
        "       valid_time " +
        "FROM events " +
        "WHERE payload->>'clearinghouse' = $1 " +
        "  AND valid_time < $2"
    ).execute(Tuple.of("DTCC", cutoffTime), ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
    for (Row row : rows) {
        logger.info("Found DTCC trade before cutoff: {} at valid_time: {}",
            row.getString("trade_id"),
            row.getOffsetDateTime("valid_time"));
    }
}
```

### Trade Lifecycle Reconstruction

**Use Case**: Reconstruct complete trade lifecycle using correlation ID.

**Pattern**: Query all events with same `correlationid`, ordered by `valid_time`.

```java
@Test
void reconstructTradeLifecycle() throws Exception {
    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.query(
        "SELECT payload->>'type' as event_type, " +
        "       payload->'data'->>'status' as status, " +
        "       (payload->'data'->>'notionalAmount')::numeric as notional, " +
        "       valid_time " +
        "FROM events " +
        "WHERE payload->>'correlationid' = 'TRD-001' " +
        "ORDER BY valid_time"
    ).onComplete(ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);

    logger.info("=== Trade Lifecycle for TRD-001 ===");
    int stage = 1;
    for (Row row : rows) {
        logger.info("Stage {}: {} - Status: {} - Notional: {} - Valid Time: {}",
            stage++,
            row.getString("event_type"),
            row.getString("status"),
            row.getBigDecimal("notional"),
            row.getOffsetDateTime("valid_time"));
    }
}
```

**Output**:
```
=== Trade Lifecycle for TRD-001 ===
Stage 1: backoffice.trade.new.v1 - Status: NEW - Notional: 15050.0 - Valid Time: 2025-10-15T14:14:13Z
Stage 2: backoffice.trade.affirmed.v1 - Status: AFFIRMED - Notional: 15050.0 - Valid Time: 2025-10-15T14:44:13Z
Stage 3: backoffice.trade.settled.v1 - Status: SETTLED - Notional: 15050.0 - Valid Time: 2025-10-17T14:14:13Z
```

### Aggregation Queries

**Use Case**: Calculate total notional by counterparty.

```sql
SELECT
    payload->'data'->>'counterparty' as counterparty,
    COUNT(*) as trade_count,
    SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional
FROM events
WHERE payload->>'type' = 'backoffice.trade.new.v1'
GROUP BY payload->'data'->>'counterparty'
ORDER BY total_notional DESC;
```

**Java Example**:
```java
@Test
void aggregateByCounterparty() throws Exception {
    CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();

    pool.query(
        "SELECT payload->'data'->>'counterparty' as counterparty, " +
        "       COUNT(*)::integer as trade_count, " +
        "       SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional " +
        "FROM events " +
        "WHERE payload->>'type' = 'backoffice.trade.new.v1' " +
        "GROUP BY payload->'data'->>'counterparty' " +
        "ORDER BY total_notional DESC"
    ).onComplete(ar -> {
        if (ar.succeeded()) {
            future.complete(ar.result());
        } else {
            future.completeExceptionally(ar.cause());
        }
    });

    RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
    for (Row row : rows) {
        logger.info("Counterparty: {} - Trades: {} - Total Notional: {}",
            row.getString("counterparty"),
            row.getInteger("trade_count"),
            row.getBigDecimal("total_notional"));
    }
}
```

### Complete Example

**See**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/CloudEventsJsonbQueryTest.java`

This test demonstrates:
- ✅ Query by CloudEvent type, source, subject
- ✅ Query by CloudEvent extensions (correlationid, bookingsystem, clearinghouse)
- ✅ Query by data payload fields (notionalAmount, counterparty, status)
- ✅ Combine bi-temporal time ranges with JSONB filtering
- ✅ Trade lifecycle reconstruction using correlation IDs
- ✅ Aggregation queries on JSONB data
- ✅ Point-in-time queries with JSONB filtering

**Run the example**:
```bash
mvn test -Dtest=CloudEventsJsonbQueryTest -pl peegeeq-examples
```

### Best Practices

#### 1. Use Correct JSONB Operators

```sql
-- ✅ CORRECT - Get JSON object, then text value
payload->'data'->>'tradeId'

-- ❌ WRONG - Can't use ->> twice
payload->>'data'->>'tradeId'
```

#### 2. Type Cast for Comparisons

```sql
-- ✅ CORRECT - Cast to numeric for comparison
WHERE (payload->'data'->>'notionalAmount')::numeric > 100000

-- ❌ WRONG - Text comparison (lexicographic)
WHERE payload->'data'->>'notionalAmount' > '100000'
```

#### 3. CloudEvents Extensions are Top-Level

```sql
-- ✅ CORRECT - Extensions at top level
WHERE payload->>'correlationid' = 'TRD-001'

-- ❌ WRONG - No 'extensions' object
WHERE payload->'extensions'->>'correlationid' = 'TRD-001'
```

#### 4. Use Prepared Statements for Parameters

```java
// ✅ CORRECT - Parameterized query
pool.preparedQuery(
    "SELECT * FROM events WHERE payload->>'type' = $1"
).execute(Tuple.of("backoffice.trade.new.v1"), ...)

// ❌ WRONG - SQL injection risk
pool.query(
    "SELECT * FROM events WHERE payload->>'type' = '" + eventType + "'"
)
```

### JSONB Operator Reference

| Operator | Description | Example | Returns |
|----------|-------------|---------|---------|
| `->` | Get JSON object field | `payload->'data'` | JSONB |
| `->>` | Get text value | `payload->>'type'` | TEXT |
| `@>` | Contains | `payload @> '{"type":"trade.new"}'` | BOOLEAN |
| `?` | Key exists | `payload ? 'correlationid'` | BOOLEAN |
| `::type` | Type cast | `(payload->>'amount')::numeric` | Specified type |

---

## Summary

### Key Concepts Recap

**Bi-Temporal = Two Time Dimensions**:
1. **Valid Time** - When the event actually happened (business time)
2. **Transaction Time** - When we recorded it (system time)

**Why Bi-Temporal?**:
- Complete audit trail (who changed what when)
- Point-in-time reconstruction (what did we know on date X?)
- Error correction without losing history
- Regulatory compliance (SOX, GDPR, MiFID II, EMIR)

### Basic Operations

**1. Append Events**:
```java
eventStore.append("TransactionRecorded", event, validTime);
```
- You provide: event type, payload, valid time
- PeeGeeQ provides: transaction time (automatic)

**2. Query Events**:
```java
// All events
eventStore.query(EventQuery.all())

// As of valid time
eventStore.query(EventQuery.asOfValidTime(instant))

// As of transaction time
eventStore.query(EventQuery.asOfTransactionTime(instant))
```

**3. Handle Corrections**:
```java
// Append correction with SAME valid time, NEW transaction time
eventStore.append("TransactionCorrected", correctedEvent, originalValidTime);
```

### Common Patterns

**Point-in-Time Balance**:
- Filter by valid time ≤ target date
- Sum credits, subtract debits

**Audit Trail**:
- Query by transaction time to see what we knew when
- Compare valid time vs transaction time to find late arrivals

**Corrections**:
- Never modify existing events
- Append correction with original valid time
- Query shows complete history

### When to Use Bi-Temporal

**Use bi-temporal when you need**:
- ✅ Complete audit trail
- ✅ Point-in-time reconstruction
- ✅ Error correction with history
- ✅ Regulatory compliance
- ✅ Late-arriving events
- ✅ Backdated transactions

**Don't use bi-temporal when**:
- ❌ Simple CRUD operations
- ❌ No audit requirements
- ❌ Events never corrected
- ❌ No historical queries needed

### Next Steps

**Ready for more?** See **BITEMPORAL_ADVANCED_GUIDE.md** for:
- Reactive integration with Spring WebFlux
- Multi-store transactions and saga pattern
- Domain-specific query patterns
- Financial services use cases (funds, custody, trade lifecycle)

---

## Related Guides

- **BITEMPORAL_ADVANCED_GUIDE.md** - Advanced patterns including:
  - Part 1: Reactive integration with Spring WebFlux
  - Part 2: Multi-store transactions and saga pattern
  - Part 3: Domain-specific query patterns
  - Part 4: Funds & custody use cases (NAV, corporate actions, trade lifecycle)
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal patterns
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns
- **DLQ_RETRY_GUIDE.md** - Error handling and retry patterns
