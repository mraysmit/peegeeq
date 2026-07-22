# PeeGeeQ Management UI - Testing Guide

## Overview

This guide covers the complete workflow for UI testing in the PeeGeeQ Management UI, from initial design through implementation and validation.

## Testing Philosophy

PeeGeeQ adopts a "Production-Grade Testing" philosophy. Since PeeGeeQ is a database-centric message queue, mocking the database is often insufficient for verifying correctness. Therefore, our testing strategy relies heavily on **TestContainers** to run tests against real, ephemeral PostgreSQL instances.

**E2E Tests (Playwright)**
- ✅ Comprehensive E2E test suite with real browser automation
- ✅ Page Object Model pattern for maintainable tests
- ✅ TestContainers integration for real PostgreSQL database
- ✅ Tests actual user workflows without mocks
- ✅ Visual regression testing capabilities
- ✅ Cross-browser testing support (Chromium, Firefox, WebKit)

**Mock-Based Unit Tests** (All removed as per requirements)
- ❌ API client tests with mocked fetch
- ❌ Hook tests with mocked WebSocket/SSE
- ❌ Store tests with mocked Zustand
- ❌ Component tests with mocked Ant Design
- ❌ RTK Query tests with mocked endpoints

### The one sanctioned exception: fault injection

Decision 2026-06-15. A healthy backend will not produce a 500, a malformed payload, or a
failed lookup on demand, so error-path UI code is unreachable from a real backend. Those
paths are exercised by **injecting the failure** with `page.route(...)` — deliberate fault
injection, not data mocking. The distinction is strict: a spec may fake a *failure* the
backend cannot be made to produce; it may never fake *data* the backend can serve.

Every such spec declares the exception in a header comment beginning
`NO-MOCK POLICY EXCEPTION (fault injection)`, naming the failure it injects and why a real
backend cannot produce it. To list them:

```powershell
Select-String -Path src/tests/e2e/specs/*.spec.ts -Pattern "NO-MOCK POLICY EXCEPTION"
```

A spec is not listed here by name — the grep is the source of truth, a list in a document is
not. Some of these specs intercept every backend call and run standalone (no TestContainers);
others inject one failure into an otherwise real session.

The most recent addition is `database-setups-details-failure.spec.ts` (its own project,
standalone, added 2026-07-21): a per-setup `GET /api/v1/setups/:setupId` returning 500 must
render the row as UNAVAILABLE with "—" counts, never as a fabricated healthy
`ACTIVE / 0 queues / 0 event stores`. Review any new fault-injection spec against the
data-versus-failure distinction above before adding one.

## CRITICAL CONCEPT: Container port vs. backend connection

### The Problem

When running Playwright e2e tests with a separate backend process:

1. Backend is a Java process that connects to PostgreSQL on startup
2. Backend reads connection details from `testcontainers-db.json` ONCE when it starts
3. Backend stays connected to that specific port (e.g., 33265)

The container gets a NEW random host port on every run, so a backend left running
from a previous session is pooled against a port that no longer exists:

- Test run 1: Container on port 33265, backend connected to 33265 ✅
- Test run 2: NEW container on port 33287, backend STILL connected to 33265 ❌
- Backend and tests are using DIFFERENT databases; writes return 503

### The Solution: restart the backend when the port moves

This module does **not** use `.withReuse()` — [global-setup-testcontainers.ts](../src/tests/global-setup-testcontainers.ts)
starts a fresh container each run and reconciles the backend instead:

1. `.testcontainers-backend-db-port` records the DB port the backend was started with.
2. On each run, if a backend is already healthy on 8088, the recorded port is compared
   with the current container's port.
3. Mismatch — or no record at all, meaning the backend was started outside this setup —
   kills the backend on 8088 and restarts it against the new port. A stale CORS config
   triggers the same restart.

So an already-running backend is only left alone when it is already pointed at the
current container. Neither file is committed; both are per-session state.

*(`peegeeq-utilities-ui` takes the other approach and does call `.withReuse()`. Both are
valid; do not assume one module's behaviour from the other.)*

Database state is additionally cleaned via the API before tests run:

```typescript
const setupsResponse = await fetch(`${API_BASE_URL}/api/v1/setups`)
const setupsData = await setupsResponse.json()
for (const setupId of setupsData.setupIds) {
  await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, { method: 'DELETE' })
}
```

## Required Test Execution Workflow

### YOU MUST ALWAYS RUN THE FULL TEST SUITE

**NEVER run individual test files in isolation** - tests have dependencies on each other.

### Step 1: Start Backend (REQUIRED - Terminal 1)

```bash
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.sh  # or .ps1 on Windows
```

This script:
1. Starts a PostgreSQL container
2. Container gets a port (e.g., 33265)
3. Writes connection details to `testcontainers-db.json`
4. Starts backend connected to that container
5. Backend stays connected to port 33265

`testcontainers-db.json` is generated per session and is **not** committed — if you are
looking at one from an old run, delete it and let the script regenerate it. Pointing the
backend at a port from a previous session is the failure described above.

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
      schema: 'peegeeq_test'
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

## Runtime Validation (Zod)

The application uses a "Three-Layer Defense" strategy for runtime validation:

### Layer 1: Runtime Schema Validation with Zod

**File**: `src/types/queue.validation.ts`

- Defines Zod schemas that mirror TypeScript types
- Validates API responses at runtime
- Provides default values for missing fields
- Logs validation errors for debugging

### Layer 2: API Response Transformation

**File**: `src/store/api/queuesApi.ts`

- Uses `transformResponse` in RTK Query to validate all API responses
- Ensures data is validated before it reaches components
- Provides consistent data structure across the application

### Layer 3: Defensive Programming in Components

**File**: `src/pages/QueuesEnhanced.tsx`

- Uses nullish coalescing operator (`??`) to handle undefined values
- Prevents `NaN` from propagating through calculations
- Ensures UI always has valid numbers to display

## Debugging Tests

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
