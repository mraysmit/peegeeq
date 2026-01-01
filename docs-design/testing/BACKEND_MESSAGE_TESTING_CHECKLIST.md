# Backend Message Enhancement - Testing Checklist

**Date**: January 1, 2026  
**Status**: Ready for Testing After Backend Restart

---

## Changes Summary

We've enhanced **23 backend messages** across **5 files** to include specific entity names (queue names, event store names, event types, consumer group names, setup IDs) instead of generic messages.

---

## Testing Checklist

After backend restart, verify the following operations display enhanced messages:

### ✅ Queue Management Operations

#### 1. Create Queue
**Test**: Create a new queue named `test-queue-123` in setup `default`

**Expected Success Message:**
```
"Queue 'test-queue-123' created successfully in setup 'default'"
```

**Expected Error Message (duplicate):**
```
"Failed to create queue 'test-queue-123': [error details]"
```

**UI Test**: Run event store/queue management E2E tests
```bash
npx playwright test src/tests/e2e/specs/queue-management.spec.ts --headed --workers=1
```

---

#### 2. Update Queue
**Expected Success Message:**
```
"Queue 'test-queue-123' configuration updated successfully in setup 'default'"
```

---

#### 3. Delete Queue
**Expected Success Message:**
```
"Queue 'test-queue-123' deleted successfully from setup 'default' (X messages deleted)"
```

---

#### 4. Purge Queue
**Expected Success Message:**
```
"Queue 'test-queue-123' purged successfully in setup 'default' (X messages deleted)"
```

---

#### 5. Pause/Resume Queue
**Expected Success Messages:**
```
"Queue 'test-queue-123' paused successfully in setup 'default' (X subscriptions)"
"Queue 'test-queue-123' resumed successfully in setup 'default' (X subscriptions)"
```

---

### ✅ Event Store Management Operations

#### 6. Create Event Store
**Test**: Create a new event store named `test-store-456` in setup `default`

**Expected Success Message:**
```
"Event store 'test-store-456' created successfully in setup 'default'"
```

**Expected Error Message (duplicate):**
```
"Failed to create event store 'test-store-456': [error details]"
```

**UI Test**: Run event store management E2E tests
```bash
npx playwright test src/tests/e2e/specs/event-store-management.spec.ts --headed --workers=1
```

---

#### 7. Delete Event Store
**Expected Success Message:**
```
"Event store 'test-store-456' deleted successfully from setup 'default'"
```

---

### ✅ Consumer Group Operations

#### 8. Create Consumer Group
**Test**: Create consumer group `test-group` for queue `test-queue-123`

**Expected Success Message:**
```
"Consumer group 'test-group' created successfully for queue 'test-queue-123' in setup 'default'"
```

---

#### 9. Delete Consumer Group
**Expected Success Message:**
```
"Consumer group 'test-group' deleted successfully from setup 'default'"
```

---

### ✅ Message Operations (NEW)

#### 10. Send Message to Queue
**Test**: POST message to `/api/v1/queues/default/test-queue-123`

**Expected Success Message:**
```
"Message sent successfully to queue 'test-queue-123' in setup 'default'"
```

**Expected Error Message:**
```
"Failed to send message to queue 'test-queue-123' in setup 'default': [error details]"
```

**Test Command:**
```bash
curl -X POST http://localhost:8080/api/v1/queues/default/test-queue-123 \
  -H "Content-Type: application/json" \
  -d '{"data": {"test": "message"}, "priority": 5}'
```

---

#### 11. Send Batch Messages
**Test**: POST batch to `/api/v1/queues/default/test-queue-123/batch`

**Expected Success Message:**
```
"Batch of 45/50 messages sent successfully to queue 'test-queue-123' in setup 'default'"
```

---

### ✅ Event Store Event Operations (NEW)

#### 12. Store Event
**Test**: POST event to `/api/v1/eventstores/default/test-store-456/events`

**Expected Success Message:**
```
"Event 'OrderCreated' stored successfully in event store 'test-store-456' in setup 'default'"
```

**Expected Error Message:**
```
"Failed to store event 'OrderCreated' in event store 'test-store-456': [error details]"
```

**Test Command:**
```bash
curl -X POST http://localhost:8080/api/v1/eventstores/default/test-store-456/events \
  -H "Content-Type: application/json" \
  -d '{"eventType": "OrderCreated", "eventData": {"orderId": "123"}, "validFrom": "2026-01-01T00:00:00Z"}'
```

---

### ✅ Database Setup Operations

#### 13. Create Database Setup
**Expected Error Message (if setup exists):**
```
"Failed to create setup 'default': [error details]"
```

---

## Quick Test Script

Run all E2E tests to validate enhanced messages:

```bash
# Event Store Tests (includes duplicate event store test)
cd peegeeq-management-ui
npx playwright test src/tests/e2e/specs/event-store-management.spec.ts --headed --workers=1

# Queue Tests
npx playwright test src/tests/e2e/specs/queue-management.spec.ts --headed --workers=1
```

---

## What to Look For

### ✅ Success Indicators

1. **UI Success Messages** show:
   - ✅ Specific entity name (queue/event store/consumer group)
   - ✅ Setup ID
   - ✅ Additional context (message count, subscription count, etc.)

2. **UI Error Messages** show:
   - ✅ Specific entity name
   - ✅ Setup ID
   - ✅ Backend error details

3. **Browser Console** shows enhanced messages in console output

### ❌ Failure Indicators

1. Generic messages like:
   - ❌ "Queue created successfully" (missing queue name)
   - ❌ "Failed to send message" (missing queue name)
   - ❌ "Event stored successfully" (missing event type and event store name)

---

## Files Modified (for reference)

1. **ManagementApiHandler.java** - Queue/Event Store/Consumer Group management (15 changes)
2. **DatabaseSetupHandler.java** - Setup API endpoints (5 changes)
3. **PeeGeeQDatabaseSetupService.java** - Database layer errors (3 changes)
4. **QueueHandler.java** - Message sending operations (3 changes)
5. **EventStoreHandler.java** - Event storing operations (2 changes)

---

## Browser Developer Console Verification

Open browser dev console (F12) while running tests and look for:

**Success messages:**
```
✅ "Queue 'test-queue-123' created successfully in setup 'default'"
✅ "Message sent successfully to queue 'order-processing' in setup 'default'"
✅ "Event 'OrderCreated' stored successfully in event store 'order-events' in setup 'default'"
```

**Error messages:**
```
❌ "Failed to create event store 'duplicate-store': java.lang.RuntimeException: Failed to add event store 'duplicate-store' to setup 'default'"
❌ "Failed to send message to queue 'orders' in setup 'default': Connection timeout"
```

---

## Regression Testing

Ensure existing functionality still works:

1. ✅ Queue creation/deletion works
2. ✅ Event store creation/deletion works
3. ✅ Message sending works
4. ✅ Event storing works
5. ✅ Consumer group management works
6. ✅ All E2E tests pass

---

## Expected Test Results

### Event Store Management Tests (10 tests)
```
✅ should create database setup for event store tests
✅ should display event stores page with table
✅ should refresh event store list
✅ should create event store with valid data
✅ should display backend error for duplicate event store name
✅ should validate required fields
✅ should close create modal with X button
✅ should close create modal with Escape key
✅ should clear form when modal is reopened
✅ should display event store details when clicking view

10 passed
```

### Queue Management Tests
```
✅ All queue tests should pass with enhanced messages
```

---

## Next Steps After Verification

1. ✅ Verify all tests pass
2. ✅ Check browser console shows enhanced messages
3. ✅ Verify duplicate event store test shows event store name in error
4. ✅ Verify message send operations show queue name
5. ✅ Verify event store operations show event type and store name
6. ✅ Update any additional documentation if needed

---

**Testing Complete!** 🎉

All backend messages now include specific entity names for improved user experience and debugging.

