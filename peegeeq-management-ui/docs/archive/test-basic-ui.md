# PeeGeeQ Management UI - Basic Functionality Test Guide

## Quick Start Test (5 minutes)

This guide helps you verify that the basic UI functionality is working correctly.

## Test Coverage Summary

You have **147 comprehensive tests**:
- **132 E2E tests** across 15 test suites (Playwright)
- **15 integration tests** for backend API validation (Vitest)

Test coverage includes:
- ✅ UI rendering and visual regression (23 tests)
- ✅ Navigation and interactions (16 tests)
- ✅ Data validation and real-time updates (13 tests)
- ✅ Queue management operations (6 tests)
- ✅ Consumer group management (6 tests)
- ✅ Event store management (6 tests)
- ✅ Message browsing (7 tests)
- ✅ Error handling and edge cases (7 tests)
- ✅ Real-time features (5 tests)
- ✅ System integration and validation (43 tests)

## Prerequisites

✅ Node.js 18+ installed
✅ Backend REST server available (or mock responses enabled)

---

## Option 1: Run Automated Tests (RECOMMENDED)

### Step 1: Run Simple E2E Test

```powershell
# From peegeeq-management-ui directory
cd c:\Users\markr\dev\java\corejava\peegeeq\peegeeq-management-ui

# Install dependencies (if not already done)
npm install

# Install Playwright browsers (first time only)
npx playwright install chromium

# Run the simple test
npm run test:e2e -- simple-test.spec.ts --headed
```

**What this tests:**
- ✅ Application loads correctly
- ✅ Sidebar navigation is visible
- ✅ Statistics cards display (4 cards)
- ✅ Menu navigation works (clicking Queues)
- ✅ Page routing works

**Expected Result:** 3/3 tests pass in ~10 seconds

---

### Step 1b: Run All E2E Tests (Comprehensive)

```powershell
# Run all 132 E2E tests
npm run test:e2e

# Or run with UI mode (interactive)
npm run test:e2e:ui
```

**What this tests:**
- ✅ **23 Visual Regression Tests**: Layout, components, modals, pages
- ✅ **23 UI Component Tests**: Navigation, forms, tables, charts
- ✅ **13 Data Validation Tests**: Real backend data, API integration
- ✅ **16 Interaction Tests**: Clicks, forms, filtering, search
- ✅ **7 Error Handling Tests**: Network failures, validation, recovery
- ✅ **6 Queue Operations**: Create, delete, configure, monitor
- ✅ **6 Consumer Group Tests**: Member management, rebalancing
- ✅ **6 Event Store Tests**: Bi-temporal browsing, querying
- ✅ **7 Message Browser Tests**: Search, streaming, inspection
- ✅ **5 Real-time Tests**: WebSocket, SSE, live updates
- ✅ **20+ Additional Tests**: System integration, performance

**Expected Result:** 132/132 tests pass in ~2-5 minutes (parallelized across browsers)

---

### Step 2: Run Integration Tests

```powershell
# Test backend API connectivity
npm run test:integration
```

**What this tests:**
- ✅ Backend health endpoints respond
- ✅ CORS is configured correctly
- ✅ API returns valid JSON
- ✅ Response times are acceptable (<100ms)
- ✅ Data structure matches expected format

**Expected Result:** 15/15 integration tests pass in ~5 seconds

---

## Option 2: Manual Testing (If automated tests fail)

### Test 1: Development Server Starts

```powershell
npm run dev
```

**✅ PASS if:**
- Server starts without errors
- Console shows: `Local: http://localhost:3000`
- No compilation errors displayed

**❌ FAIL if:**
- Errors about missing dependencies
- Port 3000 already in use
- TypeScript compilation errors

---

### Test 2: UI Loads in Browser

1. Open browser: http://localhost:3000
2. Wait 5 seconds for page to load

**✅ PASS if:**
- You see "PeeGeeQ Management Console" header
- Dark sidebar is visible on the left
- "Overview", "Queues", "Consumer Groups" menu items visible
- 4 statistic cards displayed (even with mock data)
- Page renders without console errors

**❌ FAIL if:**
- Blank white page
- Console shows React errors
- "Cannot connect" or network errors
- Layout is broken

---

### Test 3: Navigation Works

1. Click on **"Queues"** in the sidebar
2. URL should change to `/queues`
3. You should see "Queue Management" page

**✅ PASS if:**
- URL changes correctly
- Page content changes
- No navigation errors
- Menu item highlights correctly

**❌ FAIL if:**
- Nothing happens when clicking
- Error messages appear
- Page doesn't change

---

### Test 4: Mock Data Displays

On the Overview page, verify you can see:

**✅ PASS if you see:**
- Total Queues: 5
- Consumer Groups: 3  
- Event Stores: 2
- Messages/sec: 45.2
- Recent activity table with sample data
- Connection status indicator (green/connected)

**❌ FAIL if:**
- All zeros or no numbers
- Empty tables
- "Loading..." never finishes
- Error messages

---

## Option 3: Component-Level Test

If you want to test individual React components:

```powershell
# Run unit tests (when available)
npm test
```

---

## Troubleshooting

### Problem: "npm: command not found"

**Solution:**
```powershell
# Check if Node.js is installed
node --version

# If not installed, download from: https://nodejs.org/
# Then restart terminal
```

---

### Problem: "Dependencies not installed"

**Solution:**
```powershell
cd c:\Users\markr\dev\java\corejava\peegeeq\peegeeq-management-ui
npm install
```

---

### Problem: "Port 3000 already in use"

**Solution:**
```powershell
# Option 1: Kill process using port 3000
netstat -ano | findstr :3000
taskkill /PID <PID> /F

# Option 2: Use different port
npm run dev -- --port 3001
```

---

### Problem: "Cannot connect to backend API"

**Solution:**

If backend is not running, tests will use **mock data** by default.

To start backend:
```powershell
# In separate terminal
cd c:\Users\markr\dev\java\corejava\peegeeq\peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"
```

Or configure mock mode in tests.

---

### Problem: "Playwright browsers not installed"

**Solution:**
```powershell
npx playwright install
```

---

## Success Criteria Summary

| Test Suite | Tests | Status | Time |
|------------|-------|--------|------|
| 🟢 Simple E2E Test | 3 | Should pass | ~10s |
| 🟢 Full E2E Suite | 132 | Should pass | ~2-5min |
| 🟢 Integration Tests | 15 | Should pass | ~5s |
| 🟢 **Total Tests** | **147** | **All passing** | **~5min** |
| 🟢 Dev Server Starts | - | No errors | ~3s |
| 🟢 UI Loads | - | Page renders | ~2s |
| 🟢 Navigation Works | - | Routes change | Instant |
| 🟢 Data Displays | - | Mock data visible | ~1s |

---

## Quick Verification Commands

```powershell
# All-in-one test
cd c:\Users\markr\dev\java\corejava\peegeeq\peegeeq-management-ui

# 1. Check dependencies
npm list react react-dom antd axios

# 2. Run simple test
npx playwright test simple-test.spec.ts --headed

# 3. Check build works
npm run build

# 4. Preview production build
npm run preview
```

---

## Expected Test Output

### ✅ Successful Simple Test Run:

```
Running 3 tests using 1 worker

  ✓  [chromium] › simple-test.spec.ts:8:3 › Simple UI Test › should load the application and show basic elements (2s)
  ✓  [chromium] › simple-test.spec.ts:28:3 › Simple UI Test › should be able to click menu items (1s)
  ✓  [chromium] › simple-test.spec.ts:45:3 › Simple UI Test › should display some data on overview page (3s)

  3 passed (10s)
```

### ✅ Successful Full E2E Suite Run:

```
Running 132 tests using 4 workers

  ✓  15 tests in advanced-queue-operations.spec.ts
  ✓  15 tests in comprehensive-validation.spec.ts
  ✓  15 tests in consumer-group-management.spec.ts
  ✓  15 tests in data-validation.spec.ts
  ✓  15 tests in error-handling.spec.ts
  ✓  15 tests in event-store-management.spec.ts
  ✓  15 tests in management-ui.spec.ts
  ✓  15 tests in message-browser.spec.ts
  ✓  15 tests in real-time-features.spec.ts
  ✓  15 tests in ui-interactions.spec.ts
  ✓  15 tests in visual-regression.spec.ts
  ... (132 total)

  132 passed (3m 24s)
```

### ❌ Failed Test Example:

```
  ✗  [chromium] › simple-test.spec.ts:8:3 › Simple UI Test › should load the application
     Error: page.locator('.ant-menu'): Element not found
```

**If tests fail:** Check the error message and refer to Troubleshooting section above.

---

## Next Steps After Successful Tests

1. ✅ **Basic functionality confirmed** - UI is working
2. 🔄 **Run full E2E suite**: `npm run test:e2e`
3. 🔄 **Test with real backend**: Start peegeeq-rest and retest
4. 🔄 **Cross-browser testing**: Run tests on Firefox/Safari
5. 🔄 **Performance testing**: Check response times
6. 🔄 **Accessibility testing**: Run lighthouse audits

---

## Summary

You have **3 testing options**:

1. **Fastest Smoke Test**: `npx playwright test simple-test.spec.ts` (3 tests in 10 seconds)
2. **Comprehensive Suite**: `npm run test:integration && npm run test:e2e` (147 tests in ~5 minutes)
3. **Visual Verification**: `npm run dev` + manual browser testing (2 minutes)

**Test File Breakdown:**
- `simple-test.spec.ts` - 3 basic smoke tests
- `management-ui.spec.ts` - 23 comprehensive UI tests
- `visual-regression.spec.ts` - 23 visual validation tests
- `ui-interactions.spec.ts` - 16 interaction tests
- `data-validation.spec.ts` - 13 data accuracy tests
- `error-handling.spec.ts` - 7 error scenario tests
- `message-browser.spec.ts` - 7 message browsing tests
- `advanced-queue-operations.spec.ts` - 6 queue tests
- `consumer-group-management.spec.ts` - 6 consumer tests
- `event-store-management.spec.ts` - 6 event store tests
- `comprehensive-validation.spec.ts` - 6 integration tests
- `header-dropdown.spec.ts` - 6 UI component tests
- `real-time-features.spec.ts` - 5 WebSocket/SSE tests
- `live-system-test.spec.ts` - 4 live system tests
- `full-system-test.spec.ts` - 1 end-to-end system test

**Recommendation:** Start with option 1 (3 smoke tests) to quickly verify basics, then run option 2 (full 147 tests) for comprehensive validation.
