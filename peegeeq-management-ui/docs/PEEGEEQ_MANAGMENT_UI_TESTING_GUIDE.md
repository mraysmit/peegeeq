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

## CRITICAL CONCEPT: Container Reuse

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

## Required Test Execution Workflow

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
