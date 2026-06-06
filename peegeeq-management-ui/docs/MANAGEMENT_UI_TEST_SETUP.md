# Management UI Test Setup

**Purpose:** Prove the Management UI works end-to-end through all PeeGeeQ layers.

**Date:** 2025-12-28

## End-to-End Call Stack

```
UI (React/TypeScript)
  ↓ HTTP GET /api/v1/overview
PeeGeeQRestServer (Vert.x)
  ↓ ManagementApiHandler.getOverview()
DatabaseSetupService (peegeeq-runtime facade)
  ↓ RuntimeDatabaseSetupService
PeeGeeQDatabaseSetupService (peegeeq-db)
  ↓ SQL queries
PostgreSQL (tenant schema)
  ↓ Results
Back up through all layers to UI
```

## Prerequisites

- PostgreSQL running on localhost:5432
- Database: `peegeeq`
- User: `peegeeq` / `peegeeq`

## Setup Steps

### 1. Start the REST Server

```bash
cd peegeeq-rest
mvn exec:java
```

Server starts on **http://localhost:8080**

This bootstraps the full PeeGeeQ runtime via `PeeGeeQRuntime.bootstrap()`.

### 2. Create a Test Tenant Schema

**Option A: Using PowerShell Script (Windows)**

```powershell
cd peegeeq-management-ui/scripts
./setup-test-data.ps1
```

**Option B: Using curl (Cross-platform)**

```bash
curl -X POST http://localhost:8080/api/v1/database-setup/create \
  -H "Content-Type: application/json" \
  -d '{
    "setupId": "demo-tenant",
    "databaseConfig": {
      "host": "localhost",
      "port": 5432,
      "databaseName": "peegeeq",
      "username": "peegeeq",
      "password": "peegeeq",
      "schema": "demo_tenant"
    },
    "queues": [
      {
        "queueName": "orders",
        "batchSize": 10,
        "pollingInterval": "PT5S"
      },
      {
        "queueName": "notifications",
        "batchSize": 20,
        "pollingInterval": "PT10S"
      }
    ],
    "eventStores": [
      {
        "eventStoreName": "order-events",
        "tableName": "order_events",
        "biTemporalEnabled": true
      }
    ]
  }'
```

This creates:
- Schema: `demo_tenant`
- Queues: `orders`, `notifications`
- Event Store: `order-events` (bi-temporal)

### 3. Start the Management UI

```bash
cd peegeeq-management-ui
npm run dev
```

UI opens on **http://localhost:3000**

### 4. Verify End-to-End Functionality

**In the UI:**

1. **Overview Page** - Should show:
   - Total queues: 2
   - Total event stores: 1
   - System statistics

2. **Queues Page** - Should show:
   - `orders` queue
   - `notifications` queue
   - Real statistics from database

3. **Event Stores Page** - Should show:
   - `order-events` event store
   - Bi-temporal enabled

4. **Send a Test Message:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/queues/demo-tenant/orders/messages \
     -H "Content-Type: application/json" \
     -d '{
       "payload": {"orderId": "ORDER-001", "amount": 99.99},
       "priority": 5
     }'
   ```

5. **Verify in UI:**
   - Queue statistics update
   - Message count increases
   - Recent activity shows the message

## What This Proves

This test validates the complete call stack:

1. **UI Layer** - React components render and make API calls
2. **REST Layer** - `peegeeq-rest` handles HTTP requests
3. **Runtime Layer** - `peegeeq-runtime` routes to correct services
4. **Database Layer** - `peegeeq-db` executes SQL queries
5. **PostgreSQL** - Tenant schema stores data
6. **Response Flow** - Data flows back through all layers to UI

## Automated E2E Test

The full system test already validates this:

```bash
cd peegeeq-management-ui
npm run test:e2e -- full-system-test
```

This test:
- Creates database setup via REST API
- Sends messages to queues
- Verifies UI displays the data correctly
- Validates complete end-to-end integration

## Cleanup

```bash
# Drop the test schema
psql -U peegeeq -d peegeeq -c "DROP SCHEMA IF EXISTS demo_tenant CASCADE;"
```

Or use the cleanup script:

```powershell
cd peegeeq-management-ui/scripts
./cleanup-test-data.ps1
```

---

## TRUE End-to-End UI Test

A new test has been created: `true-end-to-end-test.spec.ts`

This test uses **ONLY the Management UI** (no direct API calls) to:
1. ✅ Create database setup via UI form
2. ✅ Verify setup appears in UI
3. ⚠️ Create queue via UI (NOT AVAILABLE - see gaps below)
4. ⚠️ Send message via UI (requires queue to exist)
5. ✅ Verify data in Overview page

### Run the Test

```bash
cd peegeeq-management-ui
npm run test:e2e -- true-end-to-end-test
```

### Critical Bugs Discovered

The test revealed that the UI **has the forms** but they're **calling the wrong APIs**:

#### Bug #1: Queues Page - Wrong API Endpoint

**Location:** `peegeeq-management-ui/src/pages/Queues.tsx` line 131

**Current (WRONG):**
```typescript
await axios.post('/api/v1/management/queues', values)
```

**Should be:**
```typescript
await axios.post(`/api/v1/database-setup/${setupId}/queues`, {
  name: values.name,
  // ... queue config
})
```

**Impact:** Queue creation fails because endpoint doesn't exist

---

#### Bug #2: Queues Page - Hardcoded Setup Options

**Location:** `peegeeq-management-ui/src/pages/Queues.tsx` lines 360-364

**Current (WRONG):**
```typescript
<Select placeholder="Select setup">
  <Select.Option value="production">Production</Select.Option>
  <Select.Option value="staging">Staging</Select.Option>
  <Select.Option value="development">Development</Select.Option>
</Select>
```

**Should be:**
```typescript
// Fetch setups from API
const [setups, setSetups] = useState([])
useEffect(() => {
  axios.get('/api/v1/database-setup').then(r => setSetups(r.data))
}, [])

// Render dynamic options
<Select placeholder="Select setup">
  {setups.map(s => <Select.Option value={s.setupId}>{s.setupId}</Select.Option>)}
</Select>
```

**Impact:** Can't select actual database setups, only hardcoded values

---

#### Bug #3: Event Stores Page - No API Call

**Location:** `peegeeq-management-ui/src/pages/EventStores.tsx` lines 610-626

**Current (WRONG):**
```typescript
const newEventStore: EventStore = { ... }
setEventStores(prev => [...prev, newEventStore])  // Just updates local state!
```

**Should be:**
```typescript
await axios.post(`/api/v1/database-setup/${setupId}/event-stores`, {
  name: values.name,
  // ... event store config
})
await fetchEventStores()  // Refresh from server
```

**Impact:** Event store creation doesn't persist to database

---

### What Works ✅

- Database setup creation via UI
- Setup verification in UI
- Overview page displays statistics
- Data flows through all layers: UI → REST → Runtime → DB → UI

### What's Broken ❌

- Queue creation (wrong API endpoint)
- Event store creation (no API call at all)
- Setup selection (hardcoded options instead of fetching from API)

