# PeeGeeQ Management UI - Quick Start Guide

## Prerequisites

- Node.js 18+ installed
- PeeGeeQ REST server available

## Running the Management UI

### 1. Start the Backend API (Required)

The management UI requires the PeeGeeQ REST server to be running on port 8080.

**Option A: From peegeeq-rest module**
```bash
cd peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"
```

**Option B: If you have a startup script**
```bash
cd peegeeq-rest
./run-server.sh  # or run-server.bat on Windows
```

Verify the backend is running:
```bash
curl http://localhost:8080/health
```

### 2. Start the Frontend Development Server

In a **separate terminal**:

```bash
cd peegeeq-management-ui
npm install        # First time only
npm run dev
```

Access the UI at: **http://localhost:3000**

## Running Tests

### E2E Tests (Requires Backend)

The e2e tests require the backend to be running first.

```bash
# Terminal 1: Start backend
cd peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"

# Terminal 2: Run e2e tests
cd peegeeq-management-ui
npm run test:e2e
```

**Note:** E2E tests now only run on Chrome (178 tests) for faster execution. To enable Firefox and Safari, edit `playwright.config.ts`.

### Unit Tests (No Backend Required)

```bash
npm run test        # Run in watch mode
npm run test:run    # Run once
```

### Integration Tests

```bash
npm run test:integration
```

## Building for Production

```bash
npm run build
```

Built files are placed in `../peegeeq-rest/src/main/resources/webroot` and served by the REST server at:
**http://localhost:8080/ui/**

## Common Issues

### Tests Fail Immediately

**Error:** `Cannot connect to PeeGeeQ backend at http://localhost:8080`

**Solution:** Start the backend REST server first (see step 1 above)

### Port 3000 Already in Use

```bash
# Find and kill the process using port 3000
# Windows PowerShell:
Get-NetTCPConnection -LocalPort 3000 | Select-Object OwningProcess | Get-Process | Stop-Process

# Or use a different port:
npm run dev -- --port 3001
```

### CORS Errors in Browser

Make sure you're accessing the UI through the dev server (http://localhost:3000) not directly opening the HTML files.

## Quick Commands Reference

```bash
# Development
npm run dev                    # Start dev server
npm run build                  # Build for production
npm run preview               # Preview production build

# Testing
npm run test:e2e              # E2E tests (requires backend)
npm run test:e2e:ui           # E2E tests with UI
npm run test:e2e:debug        # Debug E2E tests
npm run test                   # Unit tests (watch mode)
npm run test:run              # Unit tests (single run)
npm run test:integration      # Integration tests

# Code Quality
npm run lint                   # Run ESLint
npm run type-check            # TypeScript type checking
```

## Architecture

```
┌─────────────────────────────────────────────┐
│  Browser: http://localhost:3000             │
│  ┌───────────────────────────────────────┐  │
│  │  React UI (Vite Dev Server)           │  │
│  │  - Ant Design Components              │  │
│  │  - Real-time Dashboard                │  │
│  │  - Queue Management                   │  │
│  └───────────────┬───────────────────────┘  │
└─────────────────┼───────────────────────────┘
                  │ HTTP/REST
                  ▼
┌─────────────────────────────────────────────┐
│  Backend: http://localhost:8080             │
│  ┌───────────────────────────────────────┐  │
│  │  PeeGeeQ REST Server                  │  │
│  │  - Management API                     │  │
│  │  - /api/v1/management/*               │  │
│  │  - /health endpoint                   │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

## Typical Development Workflow

1. Start backend REST server (port 8080)
2. Start frontend dev server (port 3000)
3. Make code changes - hot reload is enabled
4. Run unit tests during development
5. Run e2e tests before committing
6. Build for production

## Need Help?

- **Full Documentation:** See `docs/README.md`
- **Testing Guide:** See `docs/TESTING.md`
- **E2E Test Improvements:** See `docs/E2E_TESTING_IMPROVEMENTS.md`
