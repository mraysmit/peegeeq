# Running Event Store Tests - Step by Step

This guide shows how to run the standalone event store management tests.

## Prerequisites

- Docker running (for TestContainers PostgreSQL)
- Java 17+ and Maven installed
- Node.js 18+ and npm installed
- Dependencies installed: `cd peegeeq-management-ui && npm install`

## The Two-Terminal Setup

**Critical Understanding**: You MUST use two separate terminal windows/sessions.

### Why Two Terminals?

The backend is a **long-running process** that blocks the terminal completely. You cannot run other commands in the same terminal where the backend is running without first stopping the backend.

```
Terminal 1 (Backend)           Terminal 2 (Commands/Tests)
─────────────────────         ────────────────────────────
Backend server RUNS here      ← You work here
DO NOT TYPE HERE!             ← Run tests here
DO NOT CLOSE!                 ← Run npm here
                              ← Run curl here
```

## Step-by-Step Instructions

### Step 1: Open Terminal 1 - Start Backend

```bash
# From repository root
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.ps1  # Windows
# OR
./start-backend-with-testcontainers.sh   # Linux/Mac
```

**What happens:**
1. Reads TestContainers database config from `testcontainers-db.json` (or starts PostgreSQL if not running)
2. Sets database environment variables
3. Starts Maven backend on port 8080
4. **Terminal becomes blocked** - you'll see logs streaming

**⚠️ CRITICAL**: Leave this terminal open and DO NOT type anything here!

### Step 2: Verify Backend (Use a NEW Terminal!)

Open **Terminal 2** (or Terminal 3) to verify:

```bash
curl http://localhost:8080/health
# Should return: {"status":"UP"}
```

**DO NOT** run this curl command in Terminal 1 - it's busy running the backend!

### Step 3: Run Event Store Tests (Terminal 2)

```bash
# From repository root
cd peegeeq-management-ui
npx playwright test src/tests/e2e/specs/event-store-management.spec.ts --headed --workers=1
```

**What happens:**
1. **Global Setup** runs first:
   - Starts TestContainers PostgreSQL (reuses if already running)
   - Creates `peegeeq` superuser
   - Writes connection details to `testcontainers-db.json`
   - Checks backend health
   - Cleans up existing database setups

2. **Vite Dev Server** starts:
   - Frontend available at `http://localhost:3000`
   - WebServer output shows in test console

3. **Tests Execute** (in order - serial mode):
   - `should create database setup for event store tests` - Creates DB setup with TestContainers config
   - `should display event stores page with table` - Navigates to `/event-stores`, verifies table
   - `should refresh event store list` - Tests refresh button
   - `should create event store with valid data` - Creates event store, verifies in table
   - `should validate required fields` - Tests form validation
   - `should close create modal with X button` - Tests modal close
   - `should close create modal with Escape key` - Tests Escape key
   - `should clear form when modal is reopened` - Tests form clearing
   - `should display event store details when clicking view` - Views event store details

4. **Browser Opens** (headed mode):
   - Chrome browser opens
   - Tests run with 1000ms slow-mo (visible actions)
   - You can watch the entire workflow

## What the Tests Validate

### Complete Event Store Workflow:

1. **Database Setup Creation**
   - Reads TestContainers connection details
   - Creates database setup via UI
   - Validates setup appears in table

2. **Event Store Creation**
   - Navigates to event stores page
   - Opens create modal
   - Fills event store name (timestamp-based)
   - Selects database setup from dropdown
   - Submits form
   - **Queries system** - refreshes and verifies event store in table

3. **Event Store Operations**
   - Views event store details via action menu
   - Validates modal displays correct information

4. **UI Behavior**
   - Modal closes with X button
   - Modal closes with Escape key
   - Form clears when modal reopens

## Test Output

```
🐳 Starting TestContainers PostgreSQL for UI tests...
✅ PostgreSQL container started:
   Host: localhost
   Port: 32768
   Database: postgres
   Container ID: ...

✅ Backend is running and healthy
✅ Database cleanup complete

Running 9 tests using 1 worker

[6-event-store-management] › should create database setup for event store tests ✓
[6-event-store-management] › should display event stores page with table ✓
[6-event-store-management] › should refresh event store list ✓
[6-event-store-management] › should create event store with valid data ✓
[6-event-store-management] › should validate required fields ✓
[6-event-store-management] › should close create modal with X button ✓
[6-event-store-management] › should close create modal with Escape key ✓
[6-event-store-management] › should clear form when modal is reopened ✓
[6-event-store-management] › should display event store details when clicking view ✓

9 passed (2.5m)
```

## Common Mistakes

### ❌ Mistake 1: Running Tests in Terminal 1

```bash
# Terminal 1
$ ./start-backend-with-testcontainers.ps1
[Backend running...]
$ npm run test:e2e    # ❌ WRONG! Can't type here - terminal is blocked!
```

**Fix**: Use Terminal 2 for tests!

### ❌ Mistake 2: Closing Terminal 1

```bash
# Terminal 1
$ ./start-backend-with-testcontainers.ps1
[Backend running...]
[User closes terminal]    # ❌ WRONG! Backend stops!

# Terminal 2
$ npm run test:e2e
Error: Cannot connect to backend    # Tests fail - no backend!
```

**Fix**: Keep Terminal 1 open!

### ❌ Mistake 3: Pressing Ctrl+C in Terminal 1

```bash
# Terminal 1
$ ./start-backend-with-testcontainers.ps1
[Backend running...]
^C    # ❌ WRONG! This stops the backend!

# Terminal 2
$ npm run test:e2e
Error: Cannot connect to backend    # Tests fail!
```

**Fix**: Don't press Ctrl+C unless you want to stop the backend!

## Stopping Everything

When you're done testing:

1. **Stop Tests** (Terminal 2): Press Ctrl+C if tests are running
2. **Stop Backend** (Terminal 1): Press Ctrl+C
3. **Stop PostgreSQL** (if desired):
   ```bash
   docker ps | grep postgres
   docker stop <container-id>
   ```

## Understanding Test Dependencies

The event store test is **standalone** - it has NO dependencies on other test files.

**Before (when there were dependencies):**
```
Running event-store tests would trigger:
  1-settings → 1b-ping-utilities → 2-connection-status → 
  3-system-integration → 3b-overview → 3c-setup-prerequisite → 
  4-database-setup → 6-event-store-management
```

**After (standalone configuration):**
```
Running event-store tests only runs:
  6-event-store-management (creates own database setup)
```

This was fixed in `playwright.config.ts` by removing the dependency:

```typescript
// Before
{
  name: '6-event-store-management',
  testMatch: '**/event-store-management.spec.ts',
  dependencies: ['4-database-setup'],  // ← Had dependency
}

// After
{
  name: '6-event-store-management',
  testMatch: '**/event-store-management.spec.ts',
  // No dependencies - standalone!
}
```

## Summary

- ✅ Two terminals required (backend in Terminal 1, everything else in Terminal 2)
- ✅ Backend must stay running in Terminal 1 (don't close or type there)
- ✅ Tests run in Terminal 2 with `npx playwright test ...`
- ✅ Event store tests are standalone (no dependencies)
- ✅ Tests validate complete workflow: create → query → view
- ✅ Browser opens in headed mode (visible, 1 second slow-mo)

