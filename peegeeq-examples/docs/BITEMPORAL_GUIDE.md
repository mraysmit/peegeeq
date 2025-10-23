# Bi-Temporal Event Store: Complete Guide

**Purpose**: Complete guide to bi-temporal event storage concepts, implementation, and advanced patterns

**Audience**: Developers implementing bi-temporal data modeling, reactive microservices, and financial services applications

**Examples**:
- `springboot-bitemporal` - Financial transaction processing with complete audit trail
- `springboot2bitemporal` - Reactive settlement processing
- `springboot-bitemporal-tx` - Multi-store trade lifecycle
- `CloudEventsJsonbQueryTest` - Domain-specific query patterns

---

## Table of Contents

### Part 1: Basics
- [Introduction](#introduction)
- [Core Concepts](#core-concepts)
- [Implementation with PeeGeeQ](#implementation-with-peegeeq)
- [Basic Operations](#basic-operations)
- [Point-in-Time Queries](#point-in-time-queries)
- [Event Corrections](#event-corrections)
- [Regulatory Compliance](#regulatory-compliance)
- [Financial Transaction Use Case](#financial-transaction-use-case)
- [Best Practices](#best-practices)
- [Quick Reference](#quick-reference)

### Part 2: Advanced Patterns
- [Reactive Integration Patterns](#part-2-reactive-integration-patterns)
- [Multi-Store Transaction Patterns](#part-2-multi-store-transaction-patterns)
- [Domain-Specific Query Patterns](#part-2-domain-specific-query-patterns)
- [Funds & Custody Use Cases](#part-2-funds--custody-use-cases)

---

# Part 1: Basics

## Introduction

### What is Bi-Temporal Data?

Imagine you're building a financial system. A customer makes a $1,000 deposit on Monday at 2pm. You record it in your system at 2:05pm. On Wednesday, you discover the deposit was actually $1,500.

**Traditional databases** would either:
- Overwrite the $1,000 with $1,500 (losing history)
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

## Quick Reference

A quick reference guide for common bi-temporal query patterns in PeeGeeQ.

### Time Dimensions Reference

| Dimension | Also Known As | Meaning | Example |
|-----------|---------------|---------|---------|
| **Valid Time** | Business Time, Effective Time | When the event actually occurred in the real world | Transaction date: Nov 15, 2024 |
| **Transaction Time** | System Time, Knowledge Time | When the event was recorded in the system | Recorded: Nov 20, 2024 |

### Common Query Patterns

#### 1. Current State Queries

```java
// Get all current events for an aggregate
eventStore.query(EventQuery.forAggregate(accountId))

// Get all current events of a specific type
eventStore.query(EventQuery.forEventType("TransactionRecorded"))

// Get all current events for aggregate AND type
eventStore.query(EventQuery.forAggregateAndType(accountId, "TransactionRecorded"))
```

#### 2. Point-in-Time Queries

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

### Common Use Case Patterns

#### Regulatory Reporting
```java
// Generate report as it was known on reporting date
eventStore.query(
    EventQuery.builder()
        .aggregateId(accountId)
        .asOfTransactionTime(regulatoryReportingDate)
        .build()
)
```

#### Reconciliation
```java
// Find events that arrived late (after period closed)
events.stream()
    .filter(e -> e.getValidTime().isBefore(periodEnd))
    .filter(e -> e.getTransactionTime().isAfter(periodEnd))
```

#### Audit Trail
```java
// Show all corrections made to a transaction
getTransactionVersions(transactionId)
    .thenApply(versions ->
        versions.stream()
            .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
            .collect(Collectors.toList())
    )
```

#### Time Travel Debugging
```java
// Reproduce system state as it was on a specific date
eventStore.query(
    EventQuery.builder()
        .asOfTransactionTime(problemReportedDate)
        .build()
)
```

### Summary

**Key Takeaways**:

1. **Two Time Dimensions** - Valid time (when it happened) vs transaction time (when recorded)
2. **Append-Only** - Events never deleted, only corrected with new events
3. **Point-in-Time Queries** - Reconstruct state at any moment
4. **Corrections** - Fix errors while preserving complete audit trail
5. **Regulatory Compliance** - SOX, GDPR, MiFID II compliance built-in
6. **Immutable History** - Complete audit trail for investigations

---

# Part 2: Advanced Patterns

This part demonstrates advanced bi-temporal patterns organized into 4 sections:

**Part 2.1: Reactive Integration Patterns**
- Reactive adapter (CompletableFuture → Mono/Flux)
- Settlement processing with Spring WebFlux
- Event corrections with audit trail
- Error handling and backpressure control

**Part 2.2: Multi-Store Transaction Patterns**
- Multiple event stores in single transaction
- Transaction coordination using `withTransaction()`
- Saga pattern for compensating actions
- Cross-store queries with correlation IDs

**Part 2.3: Domain-Specific Query Patterns**
- Wrapping EventQuery with business language
- Async/non-blocking query methods
- Aggregate ID prefixing for efficiency
- Query composition patterns

**Part 2.4: Funds & Custody Use Cases**
- Late trade confirmations and NAV corrections
- Corporate actions (dividends, splits)
- Trade lifecycle management
- Reconciliation and regulatory reporting

---

## Part 2: Reactive Integration Patterns

### Overview

PeeGeeQ's bi-temporal event store returns `CompletableFuture` from its public API. For reactive applications using Project Reactor (Spring WebFlux), you need to convert to `Mono`/`Flux`.

### Key Concepts

- **Reactive Adapter** - Convert CompletableFuture to Mono/Flux
- **Non-Blocking** - Maintain reactive streams throughout
- **Backpressure** - Control flow of large event histories
- **Error Handling** - Reactive error recovery patterns

### Reactive Adapter Pattern

#### Converting CompletableFuture to Mono/Flux

**Adapter Implementation**:
```java
@Component
public class ReactiveBiTemporalAdapter {

    public <T> Mono<BiTemporalEvent<T>> toMono(
            CompletableFuture<BiTemporalEvent<T>> future) {
        return Mono.fromFuture(future)
            .doOnError(error -> log.error("Error in bi-temporal operation", error));
    }

    public <T> Flux<BiTemporalEvent<T>> toFlux(
            CompletableFuture<List<BiTemporalEvent<T>>> future) {
        return Mono.fromFuture(future)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error in bi-temporal query", error));
    }
}
```

#### Using the Adapter

**Service Implementation**:
```java
@Service
public class SettlementService {

    private final EventStore<SettlementEvent> eventStore;
    private final ReactiveBiTemporalAdapter adapter;

    public Mono<BiTemporalEvent<SettlementEvent>> recordSettlement(
            String eventType, SettlementEvent event) {

        CompletableFuture<BiTemporalEvent<SettlementEvent>> future =
            eventStore.append(eventType, event, event.getEventTime());

        return adapter.toMono(future);
    }

    public Flux<BiTemporalEvent<SettlementEvent>> getHistory(String instructionId) {
        CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
            eventStore.query(EventQuery.all());

        return adapter.toFlux(future)
            .filter(e -> instructionId.equals(e.getPayload().getInstructionId()));
    }
}
```

### Reactive Query Patterns

#### Get Current State

Get the most recent event for a settlement instruction:

```java
public Mono<SettlementEvent> getCurrentState(String instructionId) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
        .next()
        .map(BiTemporalEvent::getPayload);
}
```

**Use Case**: Display current settlement status in operations dashboard

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Matched    - valid: 2025-10-07 10:30, transaction: 2025-10-07 10:31
3. Confirmed  - valid: 2025-10-08 14:00, transaction: 2025-10-08 14:05

Query: getCurrentState("SSI-12345")
Result: Confirmed (event 3)
```

#### Get State As-Of Point in Time

Reconstruct what we knew about a settlement at a specific moment:

```java
public Mono<SettlementEvent> getStateAsOf(String instructionId, Instant asOfTime) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .filter(e -> !e.getValidTime().isAfter(asOfTime))
        .filter(e -> !e.getTransactionTime().isAfter(asOfTime))
        .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
        .next()
        .map(BiTemporalEvent::getPayload);
}
```

**Use Case**: Regulatory inquiry - "What did you know about this settlement on 2025-10-08 at 15:00?"

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Matched    - valid: 2025-10-07 10:30, transaction: 2025-10-07 10:31
3. Confirmed  - valid: 2025-10-08 14:00, transaction: 2025-10-08 14:05
4. Corrected  - valid: 2025-10-07 09:00, transaction: 2025-10-09 10:00

Query: getStateAsOf("SSI-12345", "2025-10-08T15:00:00Z")
Result: Confirmed (event 3)
  - We knew about submission, matching, and confirmation
  - We did NOT know about correction yet (transaction time is 2025-10-09)

Query: getStateAsOf("SSI-12345", "2025-10-09T11:00:00Z")
Result: Corrected (event 4)
  - Now we know about the correction
  - Valid time shows it applies to original submission time
```

### Event Correction Patterns

#### When to Correct vs Append New Event

**Append New Event** - When business state changes:
- Settlement matched → confirmed
- Settlement confirmed → failed
- New information about current state

**Correct Existing Event** - When original data was wrong:
- Wrong counterparty recorded
- Wrong amount recorded
- Wrong settlement date
- Late-arriving information that changes past state

#### Correction with Audit Trail

**Scenario**: Wrong counterparty recorded on submission

```java
// Original submission (2025-10-07 09:00)
SettlementEvent original = new SettlementEvent(
    "SSI-12345",                                    // instructionId
    "TRD-67890",                                    // tradeId
    "CUSTODIAN-XYZ",                                // counterparty - WRONG!
    new BigDecimal("1000000.00"),                   // amount
    "USD",                                          // currency
    LocalDate.of(2025, 10, 10),                     // settlementDate
    SettlementStatus.SUBMITTED,                     // status
    null,                                           // failureReason
    Instant.parse("2025-10-07T09:00:00Z")          // eventTime (valid time)
);

settlementService.recordSettlement("instruction.settlement.submitted", original)
    .subscribe();

// Discovered error on 2025-10-09 10:00
// Record correction with ORIGINAL valid time but CURRENT transaction time
SettlementEvent corrected = new SettlementEvent(
    "SSI-12345",                                    // instructionId
    "TRD-67890",                                    // tradeId
    "CUSTODIAN-ABC",                                // counterparty - CORRECT!
    new BigDecimal("1000000.00"),                   // amount
    "USD",                                          // currency
    LocalDate.of(2025, 10, 10),                     // settlementDate
    SettlementStatus.CORRECTED,                     // status
    null,                                           // failureReason
    Instant.parse("2025-10-07T09:00:00Z")          // eventTime - Original valid time!
);

settlementService.correctSettlement("SSI-12345", corrected)
    .subscribe();
```

**Result**:
- Original event preserved (transaction time: 2025-10-07 09:01)
- Correction event recorded (transaction time: 2025-10-09 10:00)
- Both events have same valid time (2025-10-07 09:00)
- Complete audit trail of what changed and when

### Reactive Error Handling

#### Error Recovery

```java
public Mono<SettlementEvent> getCurrentStateWithFallback(String instructionId) {
    return getCurrentState(instructionId)
        .onErrorResume(error -> {
            log.error("Failed to get current state for {}", instructionId, error);
            return Mono.empty();
        })
        .doOnSuccess(state -> log.info("Retrieved state for {}: {}",
            instructionId, state.getStatus()));
}
```

#### Backpressure Control

When processing large event histories, use reactive operators to control backpressure:

```java
public Flux<SettlementEvent> processLargeHistory(String instructionId) {
    return getCompleteHistory(instructionId)
        .map(BiTemporalEvent::getPayload)
        .buffer(100)  // Process in batches of 100
        .flatMap(batch -> processBatch(batch), 4);  // Max 4 concurrent batches
}
```

---

## Part 2: Multi-Store Transaction Patterns

### Overview

Complex business workflows often span multiple domains, each with its own event store. PeeGeeQ supports coordinating multiple event stores in a single database transaction using `withTransaction()`.

### Key Concepts

- **Multiple Event Stores** - Separate stores for different domains
- **Transaction Coordination** - Single database transaction across all stores
- **Correlation IDs** - Link related events across stores
- **ACID Guarantees** - All succeed or all fail together
- **Saga Pattern** - Compensating actions for complex workflows

### Multiple Event Stores

#### Why Multiple Stores?

**Problem**: Different business domains need separate event stores.

**Example - Order Processing**:
- **Order Events** - Order lifecycle (created, confirmed, cancelled)
- **Inventory Events** - Stock movements (reserved, allocated, released)
- **Payment Events** - Payment processing (authorized, captured, refunded)
- **Audit Events** - Regulatory compliance (transaction start, complete, failed)

**Benefits**:
- Domain separation (clear boundaries)
- Independent querying (each domain has own history)
- Scalability (different retention policies per domain)
- Compliance (separate audit trail)

#### Configuration

**Multiple Event Store Beans**:
```java
@Configuration
public class BiTemporalTxConfig {

    @Bean
    public EventStore<OrderEvent> orderEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("order_events", OrderEvent.class, databaseService);
    }

    @Bean
    public EventStore<InventoryEvent> inventoryEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("inventory_events", InventoryEvent.class, databaseService);
    }

    @Bean
    public EventStore<PaymentEvent> paymentEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("payment_events", PaymentEvent.class, databaseService);
    }

    @Bean
    public EventStore<AuditEvent> auditEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("audit_events", AuditEvent.class, databaseService);
    }
}
```

### Transaction Coordination

#### The Pattern

**Challenge**: How to ensure consistency across multiple event stores?

**Solution**: Single database transaction using `withTransaction()`.

**Pattern**:
```java
connectionProvider.withTransaction("client-id", connection -> {
    // Append to Order Event Store (connection)
    // Append to Inventory Event Store (connection)
    // Append to Payment Event Store (connection)
    // Append to Audit Event Store (connection)
    // All use SAME connection = SAME transaction
});
```

**Guarantees**:
- All events committed together OR all rolled back
- Consistent transaction time across all stores
- No partial updates
- Complete ACID compliance

#### Correlation IDs

**Purpose**: Link related events across different event stores.

**Pattern**:
```java
String correlationId = UUID.randomUUID().toString();

// Order event
orderEventStore.appendInTransaction("OrderCreated", orderEvent, validTime,
    Map.of("correlationId", correlationId), correlationId, orderId, connection);

// Inventory event
inventoryEventStore.appendInTransaction("InventoryReserved", inventoryEvent, validTime,
    Map.of("correlationId", correlationId), correlationId, orderId, connection);

// Payment event
paymentEventStore.appendInTransaction("PaymentAuthorized", paymentEvent, validTime,
    Map.of("correlationId", correlationId), correlationId, orderId, connection);
```

**Benefits**:
- Query all events for a business transaction
- Trace workflow across stores
- Debug complex scenarios
- Audit trail reconstruction

---

## Part 2: Domain-Specific Query Patterns

### Overview

The domain-specific query pattern wraps PeeGeeQ's `EventQuery` API with methods that use business language and maintain async/non-blocking behavior.

**Benefits**:
1. **Domain Language** - Methods named after business concepts
2. **Type Safety** - Strongly typed return values
3. **Async/Non-Blocking** - Returns `CompletableFuture`
4. **Encapsulation** - Hides query complexity
5. **Testability** - Easy to mock for unit testing

### The Pattern

#### ❌ Anti-Pattern: Blocking the Event Loop

```java
// DON'T DO THIS - blocks the event loop!
public List<BiTemporalEvent<TransactionEvent>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId)).join();  // ❌ BLOCKS!
}
```

#### ✅ Correct Pattern: Async/Non-Blocking

```java
// DO THIS - maintains async chain
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId));  // ✅ Non-blocking
}
```

### Example: TransactionService

#### Basic Query Methods

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
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
            queryTransactionsByAccountAndType(String accountId, String eventType) {
        return eventStore.query(EventQuery.forAggregateAndType(accountId, eventType));
    }
}
```

#### Convenience Methods

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

#### Composing Async Operations

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

### Query Efficiency: Aggregate ID Prefixing

#### The Problem

PeeGeeQ uses a **shared table with JSONB serialization** for all event types. Querying without proper filtering causes:
- Cross-type deserialization attempts
- Performance degradation
- Warning log spam
- Scalability problems

#### The Solution

**Use aggregate ID prefixing** to separate event streams:

```java
// ✅ CORRECT - Prefix aggregate IDs by domain
String accountAggregateId = "account:" + accountId;
String tradeAggregateId = "trade:" + tradeId;
String fundAggregateId = "fund:" + fundId;

// Query by aggregate ID (efficient)
eventStore.query(EventQuery.forAggregate(accountAggregateId))
```

**Benefits**:
- Separate event streams by domain
- Efficient querying (no cross-type deserialization)
- Clear domain boundaries
- Scalable as data grows

#### Anti-Pattern: EventQuery.all()

```java
// ❌ WRONG - queries ALL events, attempts to deserialize everything
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAllTransactions() {
    return eventStore.query(EventQuery.all());
}

// ✅ CORRECT - query by aggregate or event type
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAccountTransactions(String accountId) {
    return eventStore.query(EventQuery.forAggregate("account:" + accountId));
}
```

---

## Part 2: Funds & Custody Use Cases

### Domain Context

#### Middle Office / Back Office Operations

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

#### Why Bi-Temporal for Funds & Custody?

1. **Trade Date vs Settlement Date** - Natural bi-temporal dimension
2. **Late Trade Confirmations** - Trades confirmed after cut-off time
3. **Corporate Actions** - Backdated adjustments (dividends, splits, mergers)
4. **NAV Corrections** - Recalculate historical NAV with corrected prices
5. **Regulatory Reporting** - Prove what was reported and when
6. **Reconciliation Breaks** - Track when discrepancies were discovered vs when they occurred
7. **Audit Trail** - Complete history required by regulators

### Use Case 1: Late Trade Confirmation

#### Scenario

**Timeline:**
- 10:30 - Trade executed (Valid Time)
- 18:00 - NAV cut-off
- 20:00 - Trade confirmed (Transaction Time) ⚠️ LATE
- 22:00 - NAV calculated (without trade)
- Next day 09:00 - NAV corrected

#### Implementation

```java
public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getLateTradeConfirmations(
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

#### Query Patterns

```java
// NAV as originally reported (for regulatory filing)
public CompletableFuture<BigDecimal> getNAVAsReported(String fundId, LocalDate asOfDate) {
    Instant reportTime = asOfDate.atTime(22, 0).toInstant(ZoneOffset.UTC);

    return eventStore.query(
        EventQuery.builder()
            .aggregateId("fund:" + fundId)
            .asOfTransactionTime(reportTime)
            .build()
    ).thenApply(events -> calculateNAV(events));
}

// NAV with late trades included (corrected)
public CompletableFuture<BigDecimal> getNAVCorrected(String fundId, LocalDate asOfDate) {
    Instant endOfDay = asOfDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.query(
        EventQuery.builder()
            .aggregateId("fund:" + fundId)
            .asOfValidTime(endOfDay)
            .build()
    ).thenApply(events -> calculateNAV(events));
}
```

### Use Case 2: Corporate Actions

#### Scenario: Dividend Payment

**Timeline:**
- 2025-10-01 - Ex-dividend date (Valid Time)
- 2025-10-15 - Payment date
- 2025-10-20 - We learn about dividend (Transaction Time)

#### Implementation

```java
public CompletableFuture<BiTemporalEvent<CorporateActionEvent>> recordDividend(
        String securityId, LocalDate exDate, BigDecimal dividendPerShare) {

    CorporateActionEvent dividend = new CorporateActionEvent(
        securityId,
        CorporateActionType.DIVIDEND,
        dividendPerShare,
        exDate
    );

    // Valid time = ex-dividend date (when it actually happened)
    Instant validTime = exDate.atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.append("CorporateActionRecorded", dividend, validTime);
}
```

### Summary

#### Part 2.1: Reactive Integration

**Key Takeaways**:
1. **Reactive Adapter** - Convert CompletableFuture to Mono/Flux
2. **Non-Blocking** - Maintain reactive streams throughout
3. **Error Handling** - Use `.onErrorResume()` for recovery
4. **Backpressure** - Control flow with `.buffer()` and `.flatMap()`
5. **Point-in-Time Queries** - Reconstruct state at any moment

**When to Use**:
- Spring WebFlux applications
- Reactive microservices
- High-throughput event processing
- Non-blocking I/O requirements

#### Part 2.2: Multi-Store Transactions

**Key Takeaways**:
1. **Multiple Event Stores** - Separate stores for different domains
2. **Single Transaction** - Use `withTransaction()` for coordination
3. **Same Connection** - Pass connection to all operations
4. **Correlation IDs** - Link events across stores
5. **Saga Pattern** - Compensating actions for complex workflows
6. **ACID Guarantees** - All succeed or all fail together

**When to Use**:
- Complex workflows spanning multiple domains
- Need ACID guarantees across event stores
- Saga pattern for compensating transactions
- Complete audit trail across all domains

#### Part 2.3: Domain-Specific Query Patterns

**Key Takeaways**:
1. **Domain Language** - Wrap EventQuery with business methods
2. **Async/Non-Blocking** - Maintain CompletableFuture chains
3. **Aggregate ID Prefixing** - Use `"domain:"` prefixes for efficiency
4. **Query Specificity** - Avoid `EventQuery.all()`
5. **Composition** - Chain operations with `.thenApply()` and `.thenCompose()`

**When to Use**:
- Building domain services
- Need business-language APIs
- Multiple event types in same store
- Performance-critical queries

#### Part 2.4: Funds & Custody Use Cases

**Key Takeaways**:
1. **Late Trade Confirmations** - Valid Time ≠ Transaction Time
2. **Corporate Actions** - Backdated adjustments (dividends, splits)
3. **NAV Corrections** - Recalculate with new information
4. **Trade Lifecycle** - Execution → Confirmation → Settlement
5. **Regulatory Reporting** - Prove what was reported and when

**When to Use**:
- Middle office / back office operations
- Fund administration
- Custody reconciliation
- Regulatory compliance (AIFMD, MiFID II, EMIR)

---

## Related Guides

- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal patterns
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns
- **DLQ_RETRY_GUIDE.md** - Error handling and retry patterns

---

## Reference Implementations

- **springboot-bitemporal** - Financial transaction processing example
  - Location: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootbitemporal/`
  - Demonstrates: Basic bi-temporal concepts, transaction processing, corrections

- **springboot2bitemporal** - Reactive settlement processing example
  - Location: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2bitemporal/`
  - Demonstrates: Reactive adapter, settlement lifecycle, corrections

- **springboot-bitemporal-tx** - Multi-store trade lifecycle example
  - Location: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootbitemporaltx/`
  - Demonstrates: Multi-store coordination, saga pattern, correlation IDs

- **TransactionServiceTest** - Domain-specific query pattern examples
  - Location: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/`
  - Demonstrates: Query methods, async composition, aggregate ID prefixing

- **CloudEventsJsonbQueryTest** - JSONB query patterns with CloudEvents
  - Location: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/`
  - Demonstrates: Trade lifecycle, JSONB queries, bi-temporal + JSONB combination

---

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0
