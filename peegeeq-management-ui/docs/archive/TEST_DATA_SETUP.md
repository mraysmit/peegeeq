# Test Data Setup Guide

This guide explains how to set up test data for E2E tests in the PeeGeeQ Management UI.

## Overview

The E2E tests use fixtures and helper functions to create consistent test data. This ensures tests are reproducible and isolated.

## Test Fixtures

Test fixtures are defined in `src/tests/fixtures/`:

- **`testData.ts`** - Defines test data structures for queues, consumer groups, and messages
- **`apiHelpers.ts`** - Functions to create/delete test data via API
- **`formHelpers.ts`** - Functions to interact with Ant Design forms

## Using Test Fixtures

### Import Fixtures and Helpers

```typescript
import { queueFixtures, generateTestName } from '../fixtures/testData'
import { createQueue, deleteQueue } from '../fixtures/apiHelpers'
import { fillAntInput, selectAntOption } from '../fixtures/formHelpers'
```

### Example: Create Test Queue

```typescript
test('should create and verify queue', async ({ page, request }) => {
  // Generate unique test name
  const queueName = generateTestName('test-queue')
  
  // Create queue via API
  const queue = {
    name: queueName,
    setup: 'Production',
    durability: 'Durable',
    description: 'Test queue'
  }
  
  await createQueue(request, queue)
  
  // Verify in UI
  await page.goto('/queues')
  await expect(page.locator(`text=${queueName}`)).toBeVisible()
  
  // Cleanup
  await deleteQueue(request, queueName)
})
```

### Example: Fill Forms with Helpers

```typescript
test('should fill queue creation form', async ({ page }) => {
  await page.goto('/queues')
  await page.click('button:has-text("Create Queue")')
  
  // Use form helpers for Ant Design components
  await fillAntInput(page, 'Queue Name', 'my-test-queue')
  await selectAntOption(page, 'Setup', 'Production')
  await selectAntOption(page, 'Durability', 'Durable')
  
  await clickModalButton(page, 'OK')
})
```

## Test Data Lifecycle

### Before Tests

```typescript
test.beforeEach(async ({ request }) => {
  // Create test data needed for the test
  for (const fixture of queueFixtures) {
    await createQueue(request, fixture)
  }
})
```

### After Tests

```typescript
test.afterEach(async ({ request }) => {
  // Clean up test data
  await cleanupTestQueues(request)
})
```

## Pre-populating Test Data

For tests that need existing data, you can pre-populate using the API:

### Manual Setup Script

Create `scripts/setup-test-data.ts`:

```typescript
import axios from 'axios'

const API_BASE = 'http://localhost:8080/api/v1'

async function setupTestData() {
  // Create test queues
  await axios.post(`${API_BASE}/management/queues`, {
    name: 'test-orders',
    setup: 'production',
    durability: 'durable'
  })
  
  await axios.post(`${API_BASE}/management/queues`, {
    name: 'test-payments',
    setup: 'production',
    durability: 'durable'
  })
  
  console.log('Test data created')
}

setupTestData().catch(console.error)
```

Run with:
```powershell
npx ts-node scripts/setup-test-data.ts
```

### Global Setup in Playwright

Add to `playwright.config.ts`:

```typescript
export default defineConfig({
  globalSetup: require.resolve('./src/tests/global-setup.ts'),
  globalTeardown: require.resolve('./src/tests/global-teardown.ts'),
})
```

Create `src/tests/global-setup.ts`:

```typescript
import { chromium, FullConfig } from '@playwright/test'
import { queueFixtures } from './fixtures/testData'
import { createQueue } from './fixtures/apiHelpers'

async function globalSetup(config: FullConfig) {
  const browser = await chromium.launch()
  const context = await browser.newContext()
  const request = context.request
  
  // Create test data
  for (const queue of queueFixtures) {
    await createQueue(request, queue)
  }
  
  await browser.close()
}

export default globalSetup
```

## Best Practices

### 1. Use Unique Names

Always generate unique test names to avoid conflicts:

```typescript
const queueName = generateTestName('test-queue')
```

### 2. Clean Up After Tests

Always delete test data to keep the system clean:

```typescript
test.afterEach(async ({ request }) => {
  await deleteQueue(request, testQueueName)
})
```

### 3. Verify Data Creation

Check that API calls succeeded:

```typescript
const result = await createQueue(request, queue)
expect(result).toBeTruthy()
await waitForQueue(request, queue.name)
```

### 4. Isolate Tests

Don't depend on data from other tests:

```typescript
// BAD: Assumes queue exists from previous test
test('should delete queue', async ({ page }) => {
  await page.click('button:has-text("Delete")')
})

// GOOD: Creates its own test data
test('should delete queue', async ({ page, request }) => {
  const queue = await createQueue(request, { name: 'test-delete-queue' })
  await page.goto('/queues')
  // ... perform delete
})
```

### 5. Use Fixtures for Common Data

Define reusable fixtures instead of hardcoding:

```typescript
// BAD
await fillAntInput(page, 'Queue Name', 'orders')

// GOOD
const queue = getRandomQueue()
await fillAntInput(page, 'Queue Name', queue.name)
```

## Troubleshooting

### Tests Fail Due to Missing Data

**Symptom**: Tests expect queues/data but tables are empty

**Solution**: 
1. Ensure backend is running
2. Pre-populate test data using global setup
3. Use `beforeEach` to create test data

### Tests Interfere with Each Other

**Symptom**: Tests pass individually but fail when run together

**Solution**:
1. Use unique names with `generateTestName()`
2. Clean up data in `afterEach`
3. Don't share mutable data between tests

### API Calls Fail

**Symptom**: `createQueue()` or other API helpers fail

**Solution**:
1. Verify backend is running on port 8080
2. Check API endpoints are correct
3. Review backend logs for errors
4. Use `checkHealth()` to verify connectivity

## Example: Complete Test with Fixtures

```typescript
import { test, expect } from '@playwright/test'
import { generateTestName } from '../fixtures/testData'
import { createQueue, deleteQueue } from '../fixtures/apiHelpers'
import { fillAntInput, selectAntOption, clickModalButton } from '../fixtures/formHelpers'

test.describe('Queue Management', () => {
  let testQueueName: string
  
  test.beforeEach(async ({ request }) => {
    // Setup: Create test queue
    testQueueName = generateTestName('test-queue')
    await createQueue(request, {
      name: testQueueName,
      setup: 'Production',
      durability: 'Durable'
    })
  })
  
  test.afterEach(async ({ request }) => {
    // Cleanup: Delete test queue
    await deleteQueue(request, testQueueName)
  })
  
  test('should display created queue', async ({ page }) => {
    await page.goto('/queues')
    await expect(page.locator(`text=${testQueueName}`)).toBeVisible()
  })
  
  test('should allow queue deletion', async ({ page }) => {
    await page.goto('/queues')
    
    // Find queue row and delete
    const row = page.locator(`tr:has-text("${testQueueName}")`)
    await row.locator('button:has-text("Delete")').click()
    
    // Confirm deletion
    await clickModalButton(page, 'OK')
    
    // Verify removed
    await expect(page.locator(`text=${testQueueName}`)).not.toBeVisible()
  })
})
```
