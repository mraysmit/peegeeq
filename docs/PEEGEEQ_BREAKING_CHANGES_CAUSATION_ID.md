# PeeGeeQ Breaking Changes - CausationId Implementation

**Version:** 1.1.0  
**Date:** January 2, 2026
**Author:** Mark Andrew Ray-Smith Cityline Ltd
**Impact Level:** 🟡 MEDIUM - Code changes required for event store append operations

---

## Overview

The `causationId` field has been added to the event sourcing system to support event causality tracking. This enables tracking which event caused another event, forming complete event dependency chains for advanced patterns like sagas, process managers, and event-driven workflows.

This feature was only partially implemented in previous version.

---

## Breaking Changes

### 1. EventStore.append() Method Signature Changed

**Affected API:** `dev.mars.peegeeq.api.EventStore`

#### Before (v1.0.x):
```java
CompletableFuture<BiTemporalEvent<T>> append(
    String eventType, 
    T payload, 
    Instant validTime,
    Map<String, String> headers, 
    String correlationId,
    String aggregateId
);
```

#### After (v1.1.0):
```java
CompletableFuture<BiTemporalEvent<T>> append(
    String eventType, 
    T payload, 
    Instant validTime,
    Map<String, String> headers, 
    String correlationId,
    String causationId,  // ← NEW PARAMETER
    String aggregateId
);
```

**Migration Required:** ✅ Yes

**Fix:** Add `null` as the 6th parameter for existing code that doesn't use causationId:

```java
// OLD CODE (will not compile):
eventStore.append("OrderCreated", event, validTime, headers, "corr-123", "order-456");

// NEW CODE (add null for causationId):
eventStore.append("OrderCreated", event, validTime, headers, "corr-123", null, "order-456");

// OR use causationId for event chains:
eventStore.append("PaymentProcessed", event, validTime, headers, "corr-123", parentEventId, "order-456");
```

---

### 2. BiTemporalEvent Interface Updated

**Affected API:** `dev.mars.peegeeq.api.BiTemporalEvent`

#### New Method Added:
```java
public interface BiTemporalEvent<T> {
    // ...existing methods...
    String getCausationId();  // ← NEW METHOD
}
```

**Migration Required:** ✅ Yes (only if you implement BiTemporalEvent directly)

**Fix:** Implement the new method in custom BiTemporalEvent implementations:

```java
public class MyCustomEvent implements BiTemporalEvent<MyData> {
    // ...existing fields...
    private final String causationId;  // Add field
    
    // ...existing methods...
    
    @Override
    public String getCausationId() {   // Add method
        return causationId;
    }
}
```

---

### 3. SimpleBiTemporalEvent Constructor Signature Changed

**Affected API:** `dev.mars.peegeeq.api.SimpleBiTemporalEvent`

#### Before (v1.0.x):
```java
new SimpleBiTemporalEvent<>(
    eventId, eventType, payload, validTime, transactionTime,
    version, previousVersionId, headers, 
    correlationId, aggregateId, isCorrection, correctionReason
);
```

#### After (v1.1.0):
```java
new SimpleBiTemporalEvent<>(
    eventId, eventType, payload, validTime, transactionTime,
    version, previousVersionId, headers, 
    correlationId, causationId, aggregateId,  // ← causationId added
    isCorrection, correctionReason
);
```

**Migration Required:** ✅ Yes (only if you create SimpleBiTemporalEvent directly)

**Fix:** Add `null` for causationId parameter, or provide the actual causationId:

```java
// Add null as the 10th parameter:
new SimpleBiTemporalEvent<>(
    eventId, eventType, payload, validTime, transactionTime,
    version, previousVersionId, headers, 
    correlationId, null, aggregateId,  // ← Add null here
    isCorrection, correctionReason
);
```

---

### 4. Database Schema Change

**Affected:** All event store tables

#### New Column Added:
```sql
ALTER TABLE event_store_template 
ADD COLUMN causation_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_event_store_causation_id 
ON event_store_template(causation_id);
```

**Migration Required:** ✅ Yes

**Database Migration Options:**

**Option A - Automatic (Recommended for Development):**
```bash
# Drop and recreate all event stores (WARNING: destroys data)
# The system will automatically apply the new schema
mvn clean install
# Restart backend - new schema will be applied
```

**Option B - Manual Migration (Recommended for Production):**
```sql
-- For each event store table:
ALTER TABLE your_event_store_table 
ADD COLUMN causation_id VARCHAR(255);

CREATE INDEX idx_your_event_store_causation_id 
ON your_event_store_table(causation_id);
```

---

## Non-Breaking Changes

### REST API - No Breaking Changes ✅

The REST API already had `causationId` in the request/response DTOs, so **no client code changes are needed**:

```json
POST /api/v1/setups/{setupId}/event-stores/{storeName}/events
{
  "eventType": "OrderCreated",
  "eventData": { "orderId": "12345" },
  "correlationId": "session-123",
  "causationId": "user-action-456",  // Already supported!
  "aggregateId": "order-12345"
}
```

### Backward Compatibility

- ✅ `causationId` is **nullable** - existing code passing `null` works fine
- ✅ Simple `append()` methods (without metadata) still work unchanged
- ✅ Existing events in database continue to work (causation_id will be NULL)
- ✅ No changes required for message queue operations

---

## Migration Guide

### Step 1: Update Code

**For Java Applications:**

```bash
# Find all append() calls with 6 parameters:
grep -r "\.append(" --include="*.java" | grep -v "causationId"

# Update each occurrence by adding null as 6th parameter
```

**Example Migration:**
```java
// Before:
eventStore.append(type, payload, time, headers, corrId, aggId)

// After:
eventStore.append(type, payload, time, headers, corrId, null, aggId)
```

### Step 2: Rebuild Application

```bash
mvn clean install
```

### Step 3: Apply Database Migration

**Development/Testing:**
```bash
# Option 1: Drop and recreate (loses data)
# Restart backend - schema auto-applied

# Option 2: Manual migration
psql -U postgres -d peegeeq < migration_add_causation_id.sql
```

**Production:**
```bash
# Always use manual migration with backup
pg_dump peegeeq > backup_before_causation_id.sql
psql -U postgres -d peegeeq < migration_add_causation_id.sql
```

### Step 4: Test

```bash
# Run full test suite
mvn test

# Run integration tests
cd peegeeq-integration-tests
mvn test -Dtest=EventStoreAdvancedAttributesSmokeTest
```

---

## What You Get

### New Capabilities Enabled:

1. **Event Causality Chains** - Track "what caused what"
   ```
   UserAction (id: A) 
     → OrderCreated (id: B, causationId: A)
       → InventoryReserved (id: C, causationId: B)
         → PaymentProcessed (id: D, causationId: C)
   ```

2. **Enhanced Debugging** - Trace event origins
3. **Saga Support** - Build complex workflows
4. **Process Managers** - Implement long-running processes
5. **Audit Trails** - Complete event causality history

### Event Sourcing Patterns Now Supported:

- ✅ Command-Event causality tracking
- ✅ Saga orchestration
- ✅ Process manager workflows
- ✅ Event-driven architectures with causation
- ✅ Complete audit trails with "why" context

---

## Affected Versions

| Version | Status | causationId Support |
|---------|--------|---------------------|
| 1.0.x | Old | ❌ Not supported |
| 1.1.0+ | Current | ✅ Fully supported |

---

## Quick Reference

### Parameter Order (NEW):
```java
eventStore.append(
    eventType,      // 1. String
    payload,        // 2. T
    validTime,      // 3. Instant
    headers,        // 4. Map<String, String>
    correlationId,  // 5. String (groups related events)
    causationId,    // 6. String (NEW - parent event ID)
    aggregateId     // 7. String (entity ID)
);
```

### Event Relationship Pattern:
```java
// Parent event
BiTemporalEvent<Order> orderEvent = eventStore.append(
    "OrderCreated", order, now, headers, "corr-123", null, "order-456"
).join();

// Child event (caused by parent)
BiTemporalEvent<Payment> paymentEvent = eventStore.append(
    "PaymentProcessed", payment, now, headers, 
    "corr-123",              // Same correlationId (same workflow)
    orderEvent.getEventId(), // causationId = parent event ID
    "order-456"              // Same aggregateId (same entity)
).join();
```

---



**Migration Complexity:** 🟢 LOW - Simple parameter addition, no logic changes required.

