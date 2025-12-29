# PeeGeeQ Management UI - Testing Approach and Design

Last Updated: 2025-12-28

## Overview

The PeeGeeQ Management UI uses a comprehensive three-tier testing strategy to ensure quality and reliability.

### Testing Pyramid

```
        ┌─────────────┐
        │   E2E       │  ← Full user workflows
        │   (66+)     │
        ├─────────────┤
        │ Integration │  ← API + Backend
        │    (15)     │
        ├─────────────┤
        │   Unit      │  ← Components + Utils
        │   (~50)     │
        └─────────────┘
```

### Test Coverage Goals

- Unit Tests: > 80% code coverage
- Integration Tests: All API endpoints
- E2E Tests: All critical user paths

## Testing Philosophy

### 1. Test Behavior, Not Implementation

- Focus on what users see and do
- Avoid testing internal component state
- Test public APIs and interfaces

### 2. Real Data Over Mocks

- Use real backend for integration/E2E tests
- Mock only when backend is unavailable
- Clear indicators for mock vs. real data

### 3. Fast Feedback

- Unit tests run in < 5 seconds
- Integration tests run in < 30 seconds
- E2E tests run in < 5 minutes (Chrome only)

### 4. Reliable Tests

- No flaky tests
- Proper wait conditions
- Isolated test data

## Unit Testing

### Framework: Vitest + React Testing Library

Configuration: vitest.config.ts

### Running Unit Tests

```bash
# Watch mode (recommended for development)
npm run test

# Run once
npm run test:run

# With coverage
npm run test:coverage

# Specific file
npm run test -- src/components/Button.test.tsx
```

### Writing Unit Tests

#### Component Test Example

```typescript
// src/components/Button.test.tsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Button } from './Button';

describe('Button', () => {
  it('renders with text', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByText('Click me')).toBeInTheDocument();
  });

  it('calls onClick when clicked', () => {
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>Click me</Button>);
    
    fireEvent.click(screen.getByText('Click me'));
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('is disabled when disabled prop is true', () => {
    render(<Button disabled>Click me</Button>);
    expect(screen.getByText('Click me')).toBeDisabled();
  });
});
```

#### Utility Function Test Example

```typescript
// src/utils/formatters.test.ts
import { describe, it, expect } from 'vitest';
import { formatNumber, formatBytes } from './formatters';

describe('formatNumber', () => {
  it('formats large numbers with commas', () => {
    expect(formatNumber(1000)).toBe('1,000');
    expect(formatNumber(1000000)).toBe('1,000,000');
  });

  it('handles zero', () => {
    expect(formatNumber(0)).toBe('0');
  });
});
```

### Best Practices

1. Use Testing Library Queries:
   - Prefer getByRole over getByTestId
   - Use getByLabelText for form inputs
   - Use getByText for content

2. Avoid Implementation Details:
   - Don't test component state directly
   - Don't test CSS classes
   - Focus on user-visible behavior

3. Mock External Dependencies:
   - Mock API calls
   - Mock timers with vi.useFakeTimers()
   - Mock modules with vi.mock()

## Integration Testing

### Framework: Vitest + Axios

Purpose: Test integration between frontend and backend API

Configuration: vitest.integration.config.ts

### Running Integration Tests

```bash
# Run integration tests
npm run test:integration

# With verbose output
npm run test:integration -- --reporter=verbose
```

### What Integration Tests Cover

1. Backend Connectivity:
   - Health check endpoint
   - API availability
   - CORS configuration

2. API Response Formats:
   - Correct JSON structure
   - Required fields present
   - Data types match TypeScript interfaces

3. Error Handling:
   - 404 for non-existent resources
   - 400 for invalid requests
   - 500 for server errors

4. Performance:
   - Response times < 200ms for list endpoints
   - Response times < 100ms for health checks

### Integration Test Example

```typescript
// tests/integration/api.test.ts
import { describe, it, expect, beforeAll } from 'vitest';
import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080';

describe('Queue API Integration', () => {
  beforeAll(async () => {
    // Verify backend is running
    const response = await axios.get(`${API_BASE_URL}/health`);
    expect(response.status).toBe(200);
  });

  it('should fetch queue list', async () => {
    const response = await axios.get(`${API_BASE_URL}/api/v1/management/queues`);

    expect(response.status).toBe(200);
    expect(response.data).toHaveProperty('queues');
    expect(Array.isArray(response.data.queues)).toBe(true);
  });

  it('should handle CORS correctly', async () => {
    const response = await axios.get(`${API_BASE_URL}/api/v1/management/queues`, {
      headers: {
        'Origin': 'http://localhost:3000'
      }
    });

    expect(response.headers['access-control-allow-origin']).toBeDefined();
  });

  it('should return 404 for non-existent queue', async () => {
    try {
      await axios.get(`${API_BASE_URL}/api/v1/management/queues/non-existent-queue`);
      expect.fail('Should have thrown 404 error');
    } catch (error: any) {
      expect(error.response.status).toBe(404);
    }
  });
});
```

### Current Integration Test Status

15/15 Tests Passing

| Test Suite | Tests | Status |
|------------|-------|--------|
| Backend Connectivity | 3 | Pass |
| Queue API | 4 | Pass |
| Consumer Group API | 3 | Pass |
| Event Store API | 2 | Pass |
| Error Handling | 3 | Pass |

## End-to-End Testing

### Framework: Playwright

Purpose: Test complete user workflows in real browser

Configuration: playwright.config.ts

### Running E2E Tests

```bash
# Run all E2E tests (Chrome only)
npm run test:e2e

# Run with UI (interactive mode)
npm run test:e2e:ui

# Debug mode (step through tests)
npm run test:e2e:debug

# Run specific test file
npx playwright test tests/e2e/queues.spec.ts

# Run specific test by name
npx playwright test -g "should create a new queue"
```

### E2E Test Structure

```
tests/e2e/
├── overview.spec.ts          # Dashboard tests
├── queues.spec.ts            # Queue management
├── consumer-groups.spec.ts   # Consumer group tests
├── event-stores.spec.ts      # Event store tests
├── message-browser.spec.ts   # Message browser
└── fixtures/
    └── test-helpers.ts       # Shared test utilities
```

### E2E Test Example

```typescript
// tests/e2e/queues.spec.ts
import { test, expect } from '@playwright/test';

test.describe('Queue Management', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:3000');
    await page.click('text=Queues');
  });

  test('should display queue list', async ({ page }) => {
    // Wait for table to load
    await expect(page.locator('table')).toBeVisible();

    // Check table headers
    await expect(page.locator('th:has-text("Queue Name")')).toBeVisible();
    await expect(page.locator('th:has-text("Setup")')).toBeVisible();
    await expect(page.locator('th:has-text("Messages")')).toBeVisible();
  });

  test('should filter queues by type', async ({ page }) => {
    // Open type filter
    await page.click('button:has-text("Type")');

    // Select "Standard" type
    await page.click('text=Standard');

    // Verify filtered results
    const rows = page.locator('tbody tr');
    await expect(rows).toHaveCount(5); // Assuming 5 standard queues
  });

  test('should create a new queue', async ({ page }) => {
    // Click create button
    await page.click('button:has-text("Create Queue")');

    // Fill form
    await page.fill('input[name="queueName"]', 'test-queue');
    await page.selectOption('select[name="type"]', 'standard');

    // Submit form
    await page.click('button[type="submit"]');

    // Verify success message
    await expect(page.locator('text=Queue created successfully')).toBeVisible();

    // Verify queue appears in list
    await expect(page.locator('td:has-text("test-queue")')).toBeVisible();
  });
});
```

### Current E2E Test Status

66+ Tests Passing (37% pass rate)

Test failures are primarily due to:
1. Empty database state (no test data)
2. Test quality issues (strict mode violations, outdated screenshots)
3. ~~Missing UI features~~ Authentication not implemented (tests expect login/auth features)

The passing tests validate:
- UI loads and renders correctly
- Navigation and routing work
- Backend API is accessible
- Empty states are handled properly
- Core components function correctly

### Browser Configuration

Current: Chrome only (for speed)

To enable all browsers, edit playwright.config.ts:

```typescript
projects: [
  { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  { name: 'firefox', use: { ...devices['Desktop Firefox'] } },    // Uncomment
  { name: 'webkit', use: { ...devices['Desktop Safari'] } },      // Uncomment
],
```

### E2E Best Practices

1. Use Page Object Model:
   ```typescript
   class QueuePage {
     constructor(private page: Page) {}

     async goto() {
       await this.page.goto('/queues');
     }

     async createQueue(name: string, type: string) {
       await this.page.click('button:has-text("Create Queue")');
       await this.page.fill('input[name="queueName"]', name);
       await this.page.selectOption('select[name="type"]', type);
       await this.page.click('button[type="submit"]');
     }
   }
   ```

2. Wait for Network Idle:
   ```typescript
   await page.goto('/queues', { waitUntil: 'networkidle' });
   ```

3. Use Data Test IDs for Stability:
   ```typescript
   // In component
   <button data-testid="create-queue-btn">Create Queue</button>

   // In test
   await page.click('[data-testid="create-queue-btn"]');
   ```

4. Clean Up Test Data:
   ```typescript
   test.afterEach(async ({ page }) => {
     // Delete test queues
     await page.evaluate(() => {
       // Call cleanup API
     });
   });
   ```

## Test Data Setup

### Backend Test Data

The E2E and integration tests rely on the backend having test data available.

#### Option 1: Use Existing Data

If your backend already has queues, consumer groups, and event stores, the tests will use that data.

#### Option 2: Manual UI Testing

Steps:
1. Open http://localhost:3000 in your browser
2. Manually create 2-3 queues through the UI
3. Send a few messages to each queue
4. Re-run E2E tests: npm run test:e2e

Expected Result:
- More tests will pass (especially queue-related tests)
- Data validation tests will work
- Message browser tests will work

Time: 10-15 minutes

#### Option 3: Global Setup (Recommended)

Playwright can automatically seed data before tests:

```typescript
// tests/global-setup.ts
import axios from 'axios';

export default async function globalSetup() {
  const API_BASE_URL = 'http://localhost:8080';

  // Check backend is running
  try {
    await axios.get(`${API_BASE_URL}/health`);
  } catch (error) {
    throw new Error('Backend is not running on port 8080');
  }

  // Seed test data
  // ... (create test queues, consumer groups, etc.)
}
```

Configure in playwright.config.ts:

```typescript
export default defineConfig({
  globalSetup: './tests/global-setup.ts',
  // ...
});
```

### Test Data Cleanup

Important: Clean up test data after tests to avoid pollution.

```typescript
// tests/global-teardown.ts
import axios from 'axios';

export default async function globalTeardown() {
  const API_BASE_URL = 'http://localhost:8080';

  // Delete test queues
  const testQueues = ['test-queue-1', 'test-queue-2', 'test-queue-3'];

  for (const queueName of testQueues) {
    try {
      await axios.delete(`${API_BASE_URL}/api/v1/management/queues/${queueName}`);
      console.log(`Deleted queue: ${queueName}`);
    } catch (error) {
      // Queue might not exist, ignore
    }
  }
}
```

## CI/CD Integration

### GitHub Actions Example

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'

      - name: Setup Java
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Install dependencies
        run: |
          cd peegeeq-management-ui
          npm ci

      - name: Run unit tests
        run: |
          cd peegeeq-management-ui
          npm run test:run

      - name: Start backend
        run: |
          cd peegeeq-rest
          mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080" &
          sleep 10

      - name: Run integration tests
        run: |
          cd peegeeq-management-ui
          npm run test:integration

      - name: Install Playwright browsers
        run: |
          cd peegeeq-management-ui
          npx playwright install --with-deps chromium

      - name: Run E2E tests
        run: |
          cd peegeeq-management-ui
          npm run test:e2e

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: peegeeq-management-ui/playwright-report/
```

### Test Coverage Reporting

```bash
# Generate coverage report
npm run test:coverage

# View coverage report
open coverage/index.html
```

Coverage Thresholds (configured in vitest.config.ts):

```typescript
test: {
  coverage: {
    provider: 'v8',
    reporter: ['text', 'json', 'html'],
    lines: 80,
    functions: 80,
    branches: 75,
    statements: 80,
  },
},
```

## Troubleshooting

### Common Issues

#### 1. Backend Not Running

Error: Cannot connect to PeeGeeQ backend at http://localhost:8080

Solution:
```bash
# Start the backend
cd peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"

# Verify it's running
curl http://localhost:8080/health
```

#### 2. Port Already in Use

Error: Port 8080 is already in use

Solution:
```bash
# Windows PowerShell
Get-NetTCPConnection -LocalPort 8080 | Select-Object OwningProcess | Get-Process | Stop-Process

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

#### 3. Playwright Browsers Not Installed

Error: Executable doesn't exist at ...

Solution:
```bash
npx playwright install
```

#### 4. Tests Timeout

Error: Test timeout of 30000ms exceeded

Solution:
- Increase timeout in test:
  ```typescript
  test('slow test', async ({ page }) => {
    test.setTimeout(60000); // 60 seconds
    // ...
  });
  ```
- Or globally in playwright.config.ts:
  ```typescript
  timeout: 60000,
  ```

#### 5. Flaky Tests

Symptoms: Tests pass sometimes, fail other times

Solutions:
1. Add proper waits:
   ```typescript
   // Bad
   await page.click('button');
   expect(page.locator('div')).toBeVisible();

   // Good
   await page.click('button');
   await expect(page.locator('div')).toBeVisible();
   ```

2. Wait for network idle:
   ```typescript
   await page.goto('/queues', { waitUntil: 'networkidle' });
   ```

3. Use retry logic:
   ```typescript
   await expect(async () => {
     const text = await page.textContent('.status');
     expect(text).toBe('Active');
   }).toPass({ timeout: 5000 });
   ```

#### 6. CORS Errors in Tests

Error: Access to fetch at 'http://localhost:8080' from origin 'http://localhost:3000' has been blocked by CORS

Solution: Ensure backend CORS is configured correctly:

```java
// In PeeGeeQRestServer.java
router.route().handler(CorsHandler.create()
  .addOrigin("http://localhost:3000")
  .allowedMethod(HttpMethod.GET)
  .allowedMethod(HttpMethod.POST)
  .allowedMethod(HttpMethod.PUT)
  .allowedMethod(HttpMethod.DELETE)
  .allowedHeader("Content-Type"));
```

## Test Maintenance

### Keeping Tests Up to Date

1. Update tests when features change
   - Modify tests before changing code (TDD)
   - Update snapshots when UI changes intentionally

2. Remove obsolete tests
   - Delete tests for removed features
   - Archive tests for deprecated functionality

3. Refactor test code
   - Extract common test utilities
   - Use page object model for E2E tests
   - Keep tests DRY (Don't Repeat Yourself)

### Test Review Checklist

Before merging code, ensure:

- All tests pass locally
- New features have tests
- Bug fixes have regression tests
- Test coverage meets thresholds
- No flaky tests
- Test data is cleaned up
- CI/CD pipeline passes

## Performance Testing

### Load Testing (Future)

Tools: k6, Artillery, or JMeter

Example k6 script:

```javascript
// load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10, // 10 virtual users
  duration: '30s',
};

export default function () {
  const res = http.get('http://localhost:8080/api/v1/management/queues');

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });

  sleep(1);
}
```

Run load test:
```bash
k6 run load-test.js
```

## Accessibility Testing

### Manual Accessibility Checks

1. Keyboard Navigation
   - Tab through all interactive elements
   - Ensure focus is visible
   - Test keyboard shortcuts

2. Screen Reader
   - Test with NVDA (Windows) or VoiceOver (Mac)
   - Ensure all content is announced
   - Check ARIA labels

3. Color Contrast
   - Use browser DevTools to check contrast ratios
   - Ensure 4.5:1 for normal text, 3:1 for large text

### Automated Accessibility Testing

Tool: axe-core with Playwright

```typescript
// tests/e2e/accessibility.spec.ts
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('should not have accessibility violations', async ({ page }) => {
  await page.goto('http://localhost:3000');

  const accessibilityScanResults = await new AxeBuilder({ page }).analyze();

  expect(accessibilityScanResults.violations).toEqual([]);
});
```

## References

### Documentation

- Vitest Documentation: https://vitest.dev/
- Playwright Documentation: https://playwright.dev/
- React Testing Library: https://testing-library.com/react
- Testing Best Practices: https://kentcdodds.com/blog/common-mistakes-with-react-testing-library

### Related Docs

- QUICK_START.md: Getting started guide
- ARCHITECTURE.md: Architecture and design
- STATUS.md: Implementation status and production readiness
```

