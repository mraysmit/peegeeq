# PeeGeeQ Management UI - End-to-End Test Plan

**Date:** 2025-12-27  
**Backend:** Running on http://localhost:8080  
**Frontend:** Running on http://localhost:3000  
**Status:** Ready for testing

---

## Test Environment

### Backend Status
- ✅ REST API Server: Running on port 8080
- ✅ Health Check: http://localhost:8080/health
- ✅ API Base URL: http://localhost:8080/api/v1
- ✅ Integration Tests: 17/17 passing

### Frontend Status
- ✅ Vite Dev Server: Running on port 3000
- ✅ UI URL: http://localhost:3000
- ✅ Build: Successful

---

## Critical Features to Test

### 1. Queue Purge (Critical Gap 1)
**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/purge`  
**UI Location:** Queue Details → Actions → Purge Queue

**Test Steps:**
1. Create a new setup
2. Create a queue
3. Send 10 messages to the queue
4. Navigate to queue details
5. Click "Purge Queue" button
6. Verify purge confirmation dialog
7. Confirm purge
8. Verify message count drops to 0
9. Verify success notification

**Expected Results:**
- ✅ Purge button is visible and enabled
- ✅ Confirmation dialog appears
- ✅ Messages are actually purged (count = 0)
- ✅ Success notification shows "10 messages purged"

---

### 2. Message Browsing (Critical Gap 2)
**Endpoint:** `GET /api/v1/management/messages`  
**UI Location:** Queue Details → Messages Tab

**Test Steps:**
1. Create a queue
2. Send 5 messages to the queue
3. Navigate to queue details
4. Click "Messages" tab
5. Verify messages are displayed
6. Verify message details (ID, payload, headers, timestamp)
7. Click "Refresh" button
8. Verify same messages are still there (non-destructive)

**Expected Results:**
- ✅ Messages tab shows 5 messages
- ✅ Each message displays correctly
- ✅ Refresh doesn't remove messages
- ✅ Message details are accurate

---

### 3. Message Polling (Critical Gap 3)
**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages`  
**UI Location:** Queue Details → Poll Messages

**Test Steps:**
1. Create a queue
2. Send 3 messages to the queue
3. Navigate to queue details
4. Click "Poll Messages" button
5. Set limit to 2
6. Click "Poll"
7. Verify 2 messages are retrieved
8. Verify message count decreases by 2

**Expected Results:**
- ✅ Poll dialog appears
- ✅ Limit parameter works
- ✅ 2 messages are polled
- ✅ Queue count decreases from 3 to 1

---

### 4. Recent Activity (Critical Gap 4)
**Endpoint:** `GET /api/v1/management/recent-activity`  
**UI Location:** Dashboard → Recent Activity Panel

**Test Steps:**
1. Navigate to dashboard
2. Create a queue
3. Send a message
4. Create an event store
5. Append an event
6. Refresh dashboard
7. Verify recent activity shows these actions

**Expected Results:**
- ✅ Recent activity panel is visible
- ✅ Activities are displayed in chronological order
- ✅ Activity types are correct (queue created, message sent, etc.)
- ✅ Timestamps are accurate

---

## Additional Features to Test

### 5. System Overview
**Test Steps:**
1. Navigate to dashboard
2. Verify system stats (queues, messages, uptime)
3. Verify queue summary (total, active, idle)
4. Verify charts and graphs render

**Expected Results:**
- ✅ All stats display correctly
- ✅ Charts render without errors
- ✅ Data updates on refresh

---

### 6. Queue Management
**Test Steps:**
1. Create a new queue with custom config
2. Verify queue appears in list
3. Edit queue configuration
4. Delete queue
5. Verify queue is removed

**Expected Results:**
- ✅ Queue creation works
- ✅ Queue list updates
- ✅ Configuration changes persist
- ✅ Deletion works

---

### 7. Event Store Management
**Test Steps:**
1. Create a new event store
2. Append events
3. Query events
4. View event details
5. Test corrections

**Expected Results:**
- ✅ Event store creation works
- ✅ Events are appended
- ✅ Queries return correct results
- ✅ Corrections work

---

### 8. Consumer Groups
**Test Steps:**
1. Create a consumer group
2. Add members
3. View group details
4. Monitor consumption

**Expected Results:**
- ✅ Group creation works
- ✅ Members are added
- ✅ Details display correctly
- ✅ Consumption tracking works

---

### 9. Dead Letter Queue
**Test Steps:**
1. View DLQ messages
2. Retry failed messages
3. Delete DLQ messages

**Expected Results:**
- ✅ DLQ messages display
- ✅ Retry works
- ✅ Deletion works

---

### 10. Webhook Subscriptions
**Test Steps:**
1. Create webhook subscription
2. View subscription details
3. Test webhook delivery
4. Delete subscription

**Expected Results:**
- ✅ Subscription creation works
- ✅ Details display correctly
- ✅ Webhooks are delivered
- ✅ Deletion works

---

## Test Execution Instructions

### Manual Testing
1. Open browser to http://localhost:3000
2. Follow test steps for each feature
3. Document results in test results file
4. Take screenshots of critical features

### Automated Testing
```bash
cd peegeeq-management-ui
npm run test:e2e
```

---

## Success Criteria

### Must Pass (Critical)
- ✅ All 4 critical gaps work correctly
- ✅ No console errors
- ✅ No network errors
- ✅ All CRUD operations work

### Should Pass (Important)
- ✅ UI is responsive
- ✅ Loading states work
- ✅ Error handling works
- ✅ Navigation works

### Nice to Have
- ✅ Animations are smooth
- ✅ Tooltips are helpful
- ✅ Accessibility is good

---

**Test Plan Ready**  
**Next Step:** Execute manual testing or run automated E2E tests

