# E2E Testing Guide - PeeGeeQ Management UI

## CRITICAL CONCEPT: Why Container Reuse is Required

### The Problem

When running Playwright e2e tests with a separate backend process:

1. Backend is a Java process that connects to PostgreSQL on startup
2. Backend reads connection details from `testcontainers-db.json` ONCE when it starts
3. Backend stays connected to that specific port (e.g., 33265)

**What happens if you create fresh containers for each test run:**
- Test run 1: Container on port 33265, backend connected to 33265 ✅
- Test run 2: NEW container on port 33287, backend STILL connected to 33265 ❌
- Backend and tests are using DIFFERENT databases
- Tests fail with confusing errors

### The Solution: Container Reuse + API Cleanup

Use `.withReuse()` on TestContainers + clean database state via API:

```typescript
// In global-setup-testcontainers.ts
const postgresContainer = await new PostgreSqlContainer('postgres:15.13-alpine3.20')
  .withReuse()  // CRITICAL: Reuse container so backend stays connected
  .start()

// Clean up database state via API before tests run
const setupsResponse = await fetch(`${API_BASE_URL}/api/v1/setups`)
const setupsData = await setupsResponse.json()
for (const setupId of setupsData.setupIds) {
  await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, { method: 'DELETE' })
}
```

**Why this works:**
- Container stays on same port across test runs (e.g., 33265)
- Backend stays connected to that port
- Database state is cleaned via API (no dirty state)
- Tests are fast (no container restart overhead)
- Tests are reliable (no connection issues)

## CRITICAL: Required Test Execution Workflow

### YOU MUST ALWAYS RUN THE FULL TEST SUITE

**NEVER run individual test files in isolation** - tests have dependencies on each other.

### Step 1: Start Backend (REQUIRED - Terminal 1)

```bash
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.sh  # or .ps1 on Windows
```

This script:
1. Starts PostgreSQL container with `.withReuse()`
2. Container gets a port (e.g., 33265)
3. Writes connection details to `testcontainers-db.json`
4. Starts backend connected to that container
5. Backend stays connected to port 33265

**LEAVE THIS TERMINAL RUNNING** - backend must stay running for all tests.

### Step 2: Run ALL Tests (Terminal 2)

```bash
cd peegeeq-management-ui

# Run ALL tests (REQUIRED - tests depend on each other)
npm run test:e2e

# Run ALL tests in headed mode (see browser)
npx playwright test --headed --workers=1

# Run ALL tests with slow motion (500ms delay between actions)
npx playwright test --headed --workers=1 --slowMo=500
```

**CRITICAL RULES:**
1. **ALWAYS run the full test suite** - `npx playwright test` (no file specified)
2. **NEVER run individual test files** - e.g., `npx playwright test queues-management.spec.ts` will FAIL
3. **NEVER run individual tests** - e.g., `npx playwright test queues-management.spec.ts:102` will FAIL
4. **Use --workers=1** when running headed mode to see tests execute sequentially

**Why?** Tests have dependencies:
- `database-setup.spec.ts` creates the 'default' setup (MUST run first)
- `queues-management.spec.ts` uses the 'default' setup (depends on database-setup)
- Running queue tests alone = no 'default' setup = tests fail

### Step 3: Playwright Global Setup Runs

Playwright global setup (`global-setup-testcontainers.ts`) runs automatically:
1. Reuses SAME PostgreSQL container (port 33265)
2. Checks if backend is healthy (FAILS if backend not running)
3. Cleans database via API (DELETE all setups)
4. Tests run against clean database

### Step 4: Tests Execute in Order

**Test execution order:**
1. `database-setup.spec.ts` - Creates 'default' setup through UI
2. `queues-management.spec.ts` - Uses 'default' setup to test queue operations
3. `system-integration.spec.ts` - Tests overall system integration

**What happens:**
- UI interactions in browser
- Backend API calls to `http://localhost:8080`
- Backend connected to SAME container (port 33265)
- Database operations work correctly

### Step 5: Teardown

- Cleans up state files
- Container keeps running (reused for next test run)
- To stop: `docker ps | grep postgres && docker stop <container-id>`

## Debugging Workflow

### To See What's Happening in Tests

```bash
# ALWAYS run the FULL test suite, just add --headed and --workers=1
npx playwright test --headed --workers=1

# To slow down and see each action (500ms delay)
npx playwright test --headed --workers=1 --slowMo=500

# To slow down even more (2000ms delay)
npx playwright test --headed --workers=1 --slowMo=2000
```

**NEVER do this:**
```bash
# ❌ WRONG - will fail because 'default' setup doesn't exist
npx playwright test queues-management.spec.ts --headed

# ❌ WRONG - will fail because 'default' setup doesn't exist
npx playwright test queues-management.spec.ts:102 --headed
```

**ALWAYS do this:**
```bash
# ✅ CORRECT - runs database-setup.spec.ts first, then queues-management.spec.ts
npx playwright test --headed --workers=1
```

## Test Writing Patterns

### Database Setup Tests - The Foundation

Database setup tests are SPECIAL:
- MUST run FIRST (other tests depend on them)
- MUST create setups through UI (not API)
- MUST run in serial mode (tests depend on each other)
- MUST assume clean database (global setup deletes all setups)

**Example: database-setup.spec.ts**

```typescript
import { test, expect } from '@playwright/test'
import * as fs from 'fs'

const SETUP_ID = 'default'

// CRITICAL: Serial mode ensures tests run in order
test.describe.configure({ mode: 'serial' })

test.describe('Database Setup', () => {
  test('should create database setup through UI', async ({ page, databaseSetupsPage }) => {
    // Read TestContainers connection details
    const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))

    await databaseSetupsPage.goto()

    // Should show "No Database Setups Found" (clean database)
    await expect(page.locator('.ant-alert')
      .filter({ hasText: 'No Database Setups Found' })).toBeVisible()

    // Create setup through UI (NOT via API)
    await databaseSetupsPage.createSetup({
      setupId: SETUP_ID,
      host: dbConfig.host,
      port: dbConfig.port,
      databaseName: `e2e_test_db_${Date.now()}`,
      username: dbConfig.username,
      password: dbConfig.password,
      schema: 'public'
    })

    // Verify setup was created
    const exists = await databaseSetupsPage.setupExists(SETUP_ID)
    expect(exists).toBeTruthy()

    // Verify exact count (should be 1 in clean database)
    const setupCount = await databaseSetupsPage.getSetupCount()
    expect(setupCount).toBe(1)
  })

  test('should display created setup in table', async ({ databaseSetupsPage }) => {
    await databaseSetupsPage.goto()

    // Setup exists from previous test (serial mode ensures order)
    const exists = await databaseSetupsPage.setupExists(SETUP_ID)
    expect(exists).toBeTruthy()
  })
})
```

**Key Points:**
- ✅ `test.describe.configure({ mode: 'serial' })` for sequential execution
- ✅ Reads TestContainers connection from `testcontainers-db.json`


### Queue Tests - Depend on Database Setup

Queue tests MUST:
- Run AFTER database setup tests
- Create queues through UI (not API)
- Use the 'default' setup created by database setup tests

**Example: queue-operations.spec.ts**

```typescript
test.describe('Queue Operations', () => {
  test('should create queue through UI', async ({ queuesPage }) => {
    await queuesPage.goto()

    // Create queue using 'default' setup from database-setup.spec.ts
    await queuesPage.createQueue({
      queueName: 'test-queue',
      setupId: 'default',
      description: 'Test queue'
    })

    // Verify queue appears in table
    const exists = await queuesPage.queueExists('test-queue')
    expect(exists).toBeTruthy()
  })
})
```

## Common Mistakes - LEARN FROM THESE

### ❌ WRONG: Creating fresh containers

```typescript
// DON'T DO THIS
const postgresContainer = await new PostgreSqlContainer('postgres:15.13-alpine3.20')
  .start()  // No .withReuse()
```

**Result**: Backend connected to port 33265, new container on port 33287, tests fail.

### ✅ CORRECT: Reuse container + API cleanup

```typescript
// DO THIS
const postgresContainer = await new PostgreSqlContainer('postgres:15.13-alpine3.20')
  .withReuse()
  .start()

// Clean via API
for (const setupId of setupsData.setupIds) {
  await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, { method: 'DELETE' })
}
```

### ❌ WRONG: Assuming sequential execution

```typescript
// DON'T DO THIS
test.describe('Database Setup', () => {
  test('create setup', async () => { ... })
  test('verify setup', async () => { ... })  // May run BEFORE first test!
})
```

**Result**: Flaky tests (sometimes pass, sometimes fail).

### ✅ CORRECT: Use serial mode

```typescript
// DO THIS
test.describe.configure({ mode: 'serial' })

test.describe('Database Setup', () => {
  test('create setup', async () => { ... })
  test('verify setup', async () => { ... })  // Runs AFTER first test
})
```

### ❌ WRONG: Creating setups via API

```typescript
// DON'T DO THIS - defeats purpose of UI testing
test('create setup', async () => {
  await fetch('http://localhost:8080/api/v1/setups', {
    method: 'POST',
    body: JSON.stringify({ setupId: 'default', ... })
  })
})
```

**Result**: UI bugs not caught, defeats purpose of e2e testing.

### ✅ CORRECT: Create through UI

```typescript
// DO THIS - test actual user workflow
test('create setup through UI', async ({ databaseSetupsPage }) => {
  await databaseSetupsPage.createSetup({
    setupId: 'default',
    host: 'localhost',
    port: 5432,
    ...
  })
})
```

### ❌ WRONG: Not starting backend

```bash
# DON'T DO THIS
npm run test:e2e
# ❌ Error: Cannot connect to PeeGeeQ backend at http://localhost:8080
```

### ✅ CORRECT: Start backend first

```bash
# Terminal 1
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.sh

# Terminal 2
cd peegeeq-management-ui
npm run test:e2e
```

## Debugging Tests - CRITICAL REMINDERS

### ALWAYS Run Full Test Suite

```bash
# ✅ CORRECT - Run ALL tests in headed mode
npx playwright test --headed --workers=1

# ✅ CORRECT - Run ALL tests with slow motion
npx playwright test --headed --workers=1 --slowMo=500

# ❌ WRONG - DO NOT run individual test files
npx playwright test specs/queues-management.spec.ts --headed

# ❌ WRONG - DO NOT run individual tests
npx playwright test specs/queues-management.spec.ts:102 --headed
```

**Why?** Tests depend on each other:
- Database setup tests create the 'default' setup
- Queue tests use the 'default' setup
- Running queue tests alone = no setup = tests fail

## Troubleshooting

### Error: "Cannot connect to PeeGeeQ backend"

**Cause**: Backend not running

**Solution**: Start backend in Terminal 1 before running tests

### Error: "No Database Setups Found" not visible

**Cause**: Database has old setups (cleanup failed)

**Solution**: Check global setup logs for cleanup:
```
🧹 Cleaning up existing database setups...
   ✓ Deleted setup: default
✅ Database cleanup complete
```

If cleanup didn't run, manually delete:
```bash
curl http://localhost:8080/api/v1/setups
curl -X DELETE http://localhost:8080/api/v1/setups/default
```

### Tests are flaky

**Cause**: Tests running in parallel when they should be sequential

**Solution**: Add `test.describe.configure({ mode: 'serial' })` to test file

## Summary - Remember These

1. **Backend MUST be running before tests** - Start it in Terminal 1
2. **ALWAYS run the FULL test suite** - NEVER run individual test files or tests
3. **Use --headed --workers=1 for debugging** - See tests execute sequentially in browser
4. **Use --slowMo=500 to slow down** - See each action with 500ms delay
5. **Container reuse is REQUIRED** - Backend can't reconnect to new containers
6. **Clean via API, not fresh containers** - Fast and reliable
7. **Use serial mode for dependent tests** - Prevents flaky tests
8. **Create through UI, not API** - Test actual user workflows
9. **Read TestContainers config** - Use `testcontainers-db.json` for connection details

## Quick Reference Card

```bash
# Terminal 1: Start backend (REQUIRED - leave running)
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.ps1

# Terminal 2: Run ALL tests
cd peegeeq-management-ui
npm run test:e2e

# Terminal 2: Debug with visible browser
npx playwright test --headed --workers=1

# Terminal 2: Debug with slow motion
npx playwright test --headed --workers=1 --slowMo=500
```

**NEVER run individual test files - tests depend on each other!**


