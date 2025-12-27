# E2E Test Data Setup Guide

## Prerequisites
- Backend running on http://localhost:8080
- Frontend running on http://localhost:3000

## Manual Test Data Creation

### Step 1: Create Test Queues

Open http://localhost:3000 in your browser and create the following queues:

#### Queue 1: "test-queue-1"
- Setup ID: `test-setup`
- Queue Name: `test-queue-1`
- Batch Size: `10`
- Polling Interval: `PT5S`

#### Queue 2: "test-queue-2"
- Setup ID: `test-setup`
- Queue Name: `test-queue-2`
- Batch Size: `20`
- Polling Interval: `PT10S`

#### Queue 3: "demo-queue"
- Setup ID: `demo-setup`
- Queue Name: `demo-queue`
- Batch Size: `5`
- Polling Interval: `PT3S`

### Step 2: Send Test Messages

For each queue, send a few test messages:

**Queue: test-queue-1**
- Message 1: `{"type": "test", "data": "message 1"}`
- Message 2: `{"type": "test", "data": "message 2"}`
- Message 3: `{"type": "test", "data": "message 3"}`

**Queue: test-queue-2**
- Message 1: `{"type": "demo", "data": "sample 1"}`
- Message 2: `{"type": "demo", "data": "sample 2"}`

### Step 3: Create Consumer Groups (if UI supports it)

If the UI has consumer group creation:
- Consumer Group 1: `test-group-1` on `test-queue-1`
- Consumer Group 2: `test-group-2` on `test-queue-2`

### Step 4: Verify Data

Check that the overview page shows:
- Total Queues: 3
- Total Messages: 5+
- Active Consumers: 0 or more

## Alternative: Use API to Create Test Data

If you prefer to use the API directly, run these curl commands:

```bash
# Create Queue 1
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-1 \
  -H "Content-Type: application/json" \
  -d '{"batchSize": 10, "pollingInterval": "PT5S"}'

# Create Queue 2
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-2 \
  -H "Content-Type: application/json" \
  -d '{"batchSize": 20, "pollingInterval": "PT10S"}'

# Create Queue 3
curl -X POST http://localhost:8080/api/v1/queues/demo-setup/demo-queue \
  -H "Content-Type: application/json" \
  -d '{"batchSize": 5, "pollingInterval": "PT3S"}'

# Send messages to Queue 1
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-1/messages \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"type\": \"test\", \"data\": \"message 1\"}", "headers": {"source": "test"}}'

curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-1/messages \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"type\": \"test\", \"data\": \"message 2\"}", "headers": {"source": "test"}}'

curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-1/messages \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"type\": \"test\", \"data\": \"message 3\"}", "headers": {"source": "test"}}'

# Send messages to Queue 2
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-2/messages \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"type\": \"demo\", \"data\": \"sample 1\"}", "headers": {"source": "demo"}}'

curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-2/messages \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"type\": \"demo\", \"data\": \"sample 2\"}", "headers": {"source": "demo"}}'

# Verify queues exist
curl http://localhost:8080/api/v1/management/queues

# Verify overview
curl http://localhost:8080/api/v1/management/overview
```

## After Creating Test Data

Once test data is created, re-run the E2E tests:

```bash
npm run test:e2e
```

Expected improvement:
- More tests should pass (especially data validation tests)
- Queue-related tests should work
- Message browser tests should work
- Consumer group tests may still fail if groups aren't created

## Cleanup

To clean up test data after testing:

```bash
# Purge all queues
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-1/purge
curl -X POST http://localhost:8080/api/v1/queues/test-setup/test-queue-2/purge
curl -X POST http://localhost:8080/api/v1/queues/demo-setup/demo-queue/purge

# Or restart the backend to reset the database
```

