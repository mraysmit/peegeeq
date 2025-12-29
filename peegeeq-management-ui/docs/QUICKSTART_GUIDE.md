# PeeGeeQ Management UI - Quickstart Guide

This guide walks you through setting up and running the PeeGeeQ Management UI with its comprehensive test suite.

## Prerequisites

- **Node.js**: Version 18 or higher
- **npm**: Version 9 or higher
- **Docker**: Required for TestContainers (backend database)
- **Java**: JDK 17 or higher (for backend services)
- **Maven**: Version 3.8 or higher (for backend services)



## Quick Setup (5 Minutes)

### 1. Install Dependencies

```bash
cd peegeeq-management-ui
npm install
```

### 2. Start the Backend with TestContainers

**IMPORTANT**: E2E tests require the backend to be running first.

```bash
cd scripts
./start-backend-with-testcontainers.sh  # or .ps1 on Windows
```

This starts PostgreSQL + Backend on `http://localhost:8080`

### 3. Start the Development Server

In a new terminal:

```bash
npm run dev
```

The UI will be available at `http://localhost:5173`

### 4. Run the Tests

```bash
npm run test:e2e  # E2E tests (requires backend running)
npm test          # Unit tests
```

For detailed E2E testing workflow, see [E2E_TESTING.md](./E2E_TESTING.md)

## Test Suite Overview

The project uses **two complementary test systems** (industry best practice):

### 1. Vitest Unit Tests (Fast - runs in ~1 second)

**Purpose**: Test individual React components in isolation
**Command**: `npm test`
**Technology**: Vitest + React Testing Library + jsdom

**What it tests:**
- Component rendering and props
- User interactions (clicks, typing)
- Component state changes
- Conditional rendering logic

**Example files:**
- `src/components/common/__tests__/StatCard.test.tsx`
- `src/components/common/__tests__/FilterBar.test.tsx`
- `src/components/common/__tests__/ConfirmDialog.test.tsx`

### 2. Playwright E2E Tests (Slow - runs in ~2-5 minutes)

**Purpose**: Test complete user workflows in real browsers with backend integration
**Command**: `npm run test:e2e`
**Technology**: Playwright + Real Chromium/Firefox/WebKit browsers

**What it tests:**
- Complete user workflows (login → create queue → verify)
- Backend API integration
- Real-time WebSocket/SSE connections
- Cross-browser compatibility
- Visual regression (screenshot comparison)
- Database operations

**Key test files:**
- `management-ui.spec.ts` - Complete UI interaction tests
- `data-validation.spec.ts` - Backend integration and data accuracy
- `visual-regression.spec.ts` - UI appearance and snapshots
- `basic-queue-operations.spec.ts` - Queue CRUD operations
- `consumer-group-management.spec.ts` - Consumer group operations
- `event-store-management.spec.ts` - Event store operations
- `message-browser.spec.ts` - Message browsing and filtering
- `real-time-features.spec.ts` - WebSocket/SSE functionality
- Plus 12 more comprehensive test files

### Why Two Test Systems?

**Unit tests (Vitest):**
- ✅ Very fast (milliseconds per test)
- ✅ Run during development for instant feedback
- ✅ Test component logic in isolation
- ❌ Don't test backend integration
- ❌ Don't test real browser behavior

**E2E tests (Playwright):**
- ✅ Test complete user workflows
- ✅ Test real backend integration
- ✅ Test in real browsers
- ✅ Catch integration bugs
- ❌ Slower (seconds per test)
- ❌ More complex to debug

## Running Specific Test Suites

### Unit Tests (Vitest)

```bash
# Run all unit tests
npm test

# Run unit tests in watch mode (re-runs on file changes)
npm test -- --watch

# Run unit tests with coverage
npm run test:coverage

# Run unit tests with UI
npm run test:ui
```

### E2E Tests (Playwright)

```bash
# Run ALL Playwright E2E tests
npm run test:e2e

# Run specific test file
npm run test:e2e -- management-ui.spec.ts

# Run tests in headed mode (see browser)
npm run test:e2e -- --headed

# Run tests with slow motion (500ms delay between actions)
npm run test:e2e -- --headed --slow-mo=500

# Run tests with custom slow motion (1000ms = 1 second)
npm run test:e2e -- --headed --slow-mo=1000

# Run tests in debug mode (pauses on each action)
npm run test:e2e -- --debug

# Run specific test in headed mode with slow motion
npm run test:e2e -- management-ui.spec.ts --headed --slow-mo=500

# Run tests in a specific browser
npm run test:e2e -- --project=chromium --headed --slow-mo=500

# Update visual regression snapshots
npm run test:e2e -- visual-regression.spec.ts --update-snapshots

# Open Playwright UI mode (interactive test runner)
npm run test:e2e:ui

# View test report
npm run test:e2e:report
```

### Convenience Scripts

```bash
# Run specific E2E test suites
npm run test:e2e:interactions  # UI interaction tests
npm run test:e2e:data          # Data validation tests
npm run test:e2e:visual        # Visual regression tests

# Run in headed mode
npm run test:e2e:headed

# Run everything (unit + integration + E2E)
npm run test:all
```

## Project Structure

```
peegeeq-management-ui/
├── src/
│   ├── pages/              # Page components
│   ├── components/         # Reusable components
│   ├── stores/            # Zustand state management
│   ├── services/          # API and WebSocket services
│   └── tests/
│       └── e2e/           # Playwright E2E tests
├── scripts/
│   └── start-backend.ts   # Backend startup script
├── playwright.config.ts   # Playwright configuration
└── vite.config.ts        # Vite configuration
```

## Common Issues and Solutions

### Backend Won't Start

**Issue**: `Error: Could not start PostgreSQL container`

**Solution**: Ensure Docker is running:
```bash
docker ps
```

### Tests Fail with Connection Errors

**Issue**: `Error: connect ECONNREFUSED 127.0.0.1:8080`

**Solution**: Verify backend is running:
```bash
curl http://localhost:8080/api/v1/management/health
```

### Port Already in Use

**Issue**: `Error: Port 8080 is already in use`

**Solution**: Kill the existing process:
```bash
# Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

### Visual Regression Tests Fail

**Issue**: Snapshots don't match

**Solution**: Update snapshots if changes are intentional:
```bash
npm test -- visual-regression.spec.ts --update-snapshots
```

## Development Workflow

### 1. Make Changes
Edit files in `src/` directory

### 2. Verify in Browser
Check `http://localhost:5173` with dev server running

### 3. Run Tests
```bash
npm test
```

### 4. Debug Failing Tests

**Watch tests run in real-time:**
```bash
# Slow motion helps you see what's happening
npm test -- --headed --slow-mo=500
```

**Debug specific test:**
```bash
# Run one test file with slow motion
npm test -- management-ui.spec.ts --headed --slow-mo=1000

# Use debug mode to pause at each step
npm test -- management-ui.spec.ts --debug
```

**Useful slow-mo values:**
- `--slow-mo=100` - Slight delay, good for fast debugging
- `--slow-mo=500` - Medium delay, good for watching interactions
- `--slow-mo=1000` - 1 second delay, good for presentations/demos
- `--slow-mo=2000` - 2 second delay, very slow for detailed inspection

### 5. Fix Any Failures
- UI tests: Check element selectors and interactions
- Data tests: Verify API responses and data flow
- Visual tests: Update snapshots if UI changes are intentional

## Next Steps

- **E2E Testing**: See [E2E_TESTING.md](./E2E_TESTING.md) for detailed testing workflow
- **Contributing**: See main project README for contribution guidelines



