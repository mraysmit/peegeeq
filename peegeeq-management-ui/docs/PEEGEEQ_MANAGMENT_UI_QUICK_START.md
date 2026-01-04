# PeeGeeQ Management UI - Quick Start Guide

Get the PeeGeeQ Management UI up and running in 5 minutes.

## Prerequisites

- **Node.js**: Version 18+ 
- **npm**: Version 9+
- **Docker**: Required for database (TestContainers)
- **Java**: JDK 17+ (for backend)
- **Maven**: Version 3.8+ (for backend)

## ⚠️ Critical Concept: Two Terminal Windows Required

This application requires **TWO separate terminal sessions** running simultaneously:

1. **Terminal 1 (Backend)**: Runs the backend server + database
   - This terminal becomes **blocked** - you cannot type commands here
   - It must stay open and running the entire time you're working
   - **Do NOT close this terminal or press Ctrl+C** unless you want to stop the backend

2. **Terminal 2 (Frontend/Tests/Commands)**: Everything else
   - Run the UI dev server here
   - Run tests here
   - Run curl commands here
   - This is your "working" terminal

**Why?** The backend server is a long-running process that occupies Terminal 1 completely. If you try to run other commands in Terminal 1 (like tests or curl), you'll need to stop the backend first (Ctrl+C), which will break everything.

**Visual Setup:**
```
┌─────────────────────────────────────┐  ┌─────────────────────────────────────┐
│ Terminal 1: Backend (KEEP RUNNING)  │  │ Terminal 2: Your Workspace          │
├─────────────────────────────────────┤  ├─────────────────────────────────────┤
│ $ cd peegeeq-management-ui/scripts  │  │ $ cd peegeeq-management-ui          │
│ $ ./start-backend...ps1             │  │ $ npm run test:e2e                  │
│                                     │  │                                     │
│ [Backend running...]                │  │ $ curl http://localhost:8080/health │
│ [PostgreSQL running...]             │  │                                     │
│                                     │  │ $ npm run dev                       │
│ ⚠️  DO NOT TYPE HERE!               │  │                                     │
│ ⚠️  DO NOT CLOSE!                   │  │ ✅ Use this terminal freely         │
└─────────────────────────────────────┘  └─────────────────────────────────────┘
```

## Quick Start

### 1. Install Dependencies

```bash
cd peegeeq-management-ui
npm install
```

### 2. Start the Full System

**Terminal 1: Start Backend + Database (KEEP THIS RUNNING)**
```bash
# From repository root
cd peegeeq-management-ui/scripts
./start-backend-with-testcontainers.ps1  # Windows
# OR
./start-backend-with-testcontainers.sh   # Linux/Mac
```
This starts PostgreSQL container (via TestContainers) + Backend API on `http://localhost:8080`

**Verify backend is running:**
```bash
# Windows PowerShell (open a NEW terminal - don't use Terminal 1!)
curl http://localhost:8080/health

# Linux/Mac (open a NEW terminal - don't use Terminal 1!)
curl http://localhost:8080/health

# Should return: {"status":"UP"}
```

**Terminal 2: Start Development UI (NEW TERMINAL - Don't close Terminal 1!)**
```bash
# From repository root
cd peegeeq-management-ui
npm run dev
```
UI available at `http://localhost:5173`

### 3. Run Tests

**E2E Tests** (requires backend running in Terminal 1):
```bash
# Terminal 2 OR Terminal 3 (DON'T use Terminal 1 - it's busy running the backend!)
cd peegeeq-management-ui
npm run test:e2e
```

**Unit Tests** (no backend required):
```bash
# Any terminal
cd peegeeq-management-ui
npm test
```

**Running Specific Test Files:**
```bash
# Run ONLY event store tests (standalone - creates own database setup)
npx playwright test src/tests/e2e/specs/event-store-management.spec.ts --headed --workers=1

# Run ONLY queue management tests
npx playwright test src/tests/e2e/specs/queue-management.spec.ts --headed --workers=1

# Run ONLY database setup tests
npx playwright test src/tests/e2e/specs/database-setup.spec.ts --headed --workers=1
```

## Customizing Ports

### Frontend Port (Default: 5173)

**Option 1: Command line**
```bash
npm run dev -- --port 3000
```

**Option 2: Environment variable**
```bash
# Windows PowerShell
$env:PORT=3000; npm run dev

# Linux/Mac
PORT=3000 npm run dev
```

### Backend Port (Default: 8080)

**Method 1: Pass port as argument to Maven**
```bash
# Windows PowerShell
cd scripts
# Edit start-backend-with-testcontainers.ps1 and add port to Maven args:
# -Dexec.args="9090"

# Or run Maven directly from repository root:
mvn exec:java -pl peegeeq-rest -Dexec.args="9090"
```

**Update Playwright baseURL:**
```typescript
// playwright.config.ts
export default defineConfig({
  use: {
    baseURL: 'http://localhost:3000',  // Match your frontend port
  }
})
```

**Update API URL in tests:**
```typescript
// src/tests/global-setup-testcontainers.ts
const API_BASE_URL = 'http://localhost:9090'  // Match your backend port
```

## Troubleshooting

### ⚠️ Critical: Backend Stops When Running Tests

**Problem**: I tried to run tests and the backend stopped working.

**Cause**: You tried to run commands in Terminal 1 (where the backend is running).

**Solution**: 
- Terminal 1 is **dedicated** to running the backend - it's **blocked** by the running process
- Use Terminal 2 or Terminal 3 for running tests, npm commands, curl, etc.
- If you run ANY command in Terminal 1, you must first stop the backend (Ctrl+C), which will break your tests

### Backend Won't Start
```bash
# Check Docker is running
docker ps
```

### E2E Tests Fail - Backend Not Running
```
Error: Cannot connect to PeeGeeQ backend at http://localhost:8080
```
**Solution**: Start the backend first (see "Start the Full System" section above)
