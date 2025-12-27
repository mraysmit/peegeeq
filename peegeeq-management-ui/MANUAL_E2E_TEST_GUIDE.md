# Manual E2E Testing Guide

This guide walks you through manually testing the PeeGeeQ Management UI to create test data and verify E2E functionality.

## Prerequisites

1. Backend REST server running on http://localhost:8080
2. Frontend UI running on http://localhost:3000
3. PostgreSQL database accessible

## Step 1: Create a Database Setup

The UI requires a "setup" to exist before you can create queues. A setup represents a database configuration.

### Option A: Use the Backend API Directly

Open PowerShell and run:

```powershell
$setupBody = @{
    setupId = "test-setup"
    databaseConfig = @{
        host = "localhost"
        port = 5432
        databaseName = "peegeeq_test_ui"
        username = "peegeeq"
        password = "peegeeq"
        schema = "public"
        templateDatabase = "template0"
        encoding = "UTF8"
    }
    queues = @()
    eventStores = @()
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/database-setup/create' `
    -Method Post `
    -ContentType 'application/json' `
    -Body $setupBody
```

**Note:** This will create a NEW PostgreSQL database called `peegeeq_test_ui`. You need PostgreSQL admin permissions.

### Option B: Use Existing Database (Simpler)

If you don't have permissions to create databases, you can try to use the existing database by modifying the setup creation to use an existing database name. However, this may fail if the database already has PeeGeeQ tables.

## Step 2: Verify Setup Creation

Check that the setup was created:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/setups' -Method Get | ConvertTo-Json
```

You should see:
```json
{
  "count": 1,
  "setupIds": ["test-setup"]
}
```

## Step 3: Create Queues via UI

1. Open http://localhost:3000 in your browser
2. Click "Queues" in the left menu
3. Click the "Create Queue" button (top right)
4. Fill in the form:
   - **Queue Name:** `test-queue-1`
   - **Setup:** Select `test-setup` (or whatever setup you created)
   - **Durability:** Durable
   - **Auto Delete:** No
5. Click "OK" to create the queue

Repeat to create 2-3 queues for testing.

## Step 4: Send Messages to Queues

1. Click on a queue name to open the queue details page
2. Click the "Actions" tab
3. Click "Publish Message"
4. Enter a JSON payload:
   ```json
   {
     "orderId": "12345",
     "amount": 99.99,
     "customer": "John Doe"
   }
   ```
5. Set priority (optional): 5
6. Click "OK" to publish

Repeat to send 5-10 messages to each queue.

## Step 5: Browse Messages

1. On the queue details page, click the "Messages" tab
2. Click "Get Messages"
3. Set:
   - **Number of Messages:** 10
   - **Acknowledgement Mode:** Manual (messages remain in queue)
4. Click "OK"
5. Verify messages are displayed in the table

## Step 6: Create Event Stores (Optional)

1. Click "Event Stores" in the left menu
2. Click "Create Event Store"
3. Fill in the form:
   - **Event Store Name:** `test-events`
   - **Setup:** Select `test-setup`
4. Click "OK"

## Step 7: Run E2E Tests

Now that you have test data, run the E2E tests:

```bash
npm run test:e2e
```

Expected improvements:
- More queue-related tests should pass
- Message browser tests should pass
- Data validation tests should pass

## Troubleshooting

### "Setup not found" error when creating queue

The setup doesn't exist. Go back to Step 1 and create a setup first.

### "Database creation failed" error

You don't have permissions to create new databases. Options:
1. Ask your DBA for permissions
2. Use a different PostgreSQL instance where you have admin rights
3. Modify the backend to support using existing databases without creating new ones

### Queue creation succeeds but queue doesn't appear

1. Check browser console for errors (F12)
2. Refresh the page
3. Check backend logs for errors
4. Verify the setup exists: `GET http://localhost:8080/api/v1/setups`

### Messages don't appear after publishing

1. Check the "Messages" tab (not "Overview")
2. Click "Get Messages" to fetch messages
3. Check backend logs for errors
4. Verify the queue exists: `GET http://localhost:8080/api/v1/management/queues`

## Expected E2E Test Results

After creating test data:

- **Before:** 66/177 tests passing (37%)
- **After:** 100+/177 tests passing (56%+)

Tests that should now pass:
- Queue creation and listing
- Message publishing and browsing
- Queue details display
- Real-time updates
- Data validation
- Consumer group display (if you create consumer groups)

## Cleanup

To remove test data:

```powershell
# Delete all queues
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/database-setup/test-setup' -Method Delete

# Or use the cleanup script
./cleanup-test-data.ps1
```

