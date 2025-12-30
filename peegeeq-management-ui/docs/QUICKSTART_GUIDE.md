# PeeGeeQ Management UI - Quickstart Guide

Get the PeeGeeQ Management UI up and running in 5 minutes.

## Prerequisites

- **Node.js**: Version 18+ 
- **npm**: Version 9+
- **Docker**: Required for database (TestContainers)
- **Java**: JDK 17+ (for backend)
- **Maven**: Version 3.8+ (for backend)

## Quick Start

### 1. Install Dependencies

```bash
cd peegeeq-management-ui
npm install
```

### 2. Start the Full System

**Terminal 1: Start Backend + Database**
```bash
cd scripts
./start-backend-with-testcontainers.ps1  # or .sh on Linux/Mac
```
This starts PostgreSQL container + Backend API on `http://localhost:8080`

**Verify backend is running:**
```bash
# Windows PowerShell
curl http://localhost:8080/health

# Linux/Mac
curl http://localhost:8080/health

# Should return: {"status":"UP"}
```

**Terminal 2: Start Development UI**
```bash
cd peegeeq-management-ui
npm run dev
```
UI available at `http://localhost:5173`

**Verify frontend is running:**
```
Open browser to http://localhost:5173
```

### 3. Run Tests

**E2E Tests** (requires backend running):
```bash
# Terminal 3
npm run test:e2e
```

**Unit Tests** (no backend required):
```bash
npm test
```

> **💡 Tip**: For detailed E2E testing workflows, see [E2E_TESTING.md](./E2E_TESTING.md)

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

**Option 3: vite.config.ts**
```typescript
export default defineConfig({
  server: {
    port: 3000
  }
})
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

# Linux/Mac
cd scripts
# Edit start-backend-with-testcontainers.sh and add port to Maven args:
# -Dexec.args="9090"
```

**Method 2: Modify the start script**

Edit `scripts/start-backend-with-testcontainers.ps1` (or `.sh`):
```powershell
# Add after the $MavenArgs array definition:
$MavenArgs = @(
    "exec:java",
    "-pl", "peegeeq-rest",
    "-Dexec.args=9090",  # Add this line with your custom port
    "-Dexec.systemProperties",
    # ... rest of args
)
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

## Manual Component Startup

If you prefer to start each component manually instead of using the convenience scripts:

### 1. Start PostgreSQL Database

**Using Docker:**
```bash
# Start PostgreSQL container
docker run -d \
  --name peegeeq-postgres \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=peegeeq \
  -e POSTGRES_PASSWORD=peegeeq \
  -p 5432:5432 \
  postgres:15.13-alpine3.20
```

**Windows PowerShell:**
```powershell
docker run -d `
  --name peegeeq-postgres `
  -e POSTGRES_DB=postgres `
  -e POSTGRES_USER=peegeeq `
  -e POSTGRES_PASSWORD=peegeeq `
  -p 5432:5432 `
  postgres:15.13-alpine3.20
```

**Verify PostgreSQL is running:**
```bash
# Check container status
docker ps | grep peegeeq-postgres

# Test database connection
# Windows PowerShell
docker exec peegeeq-postgres pg_isready -U peegeeq

# Linux/Mac
docker exec peegeeq-postgres pg_isready -U peegeeq

# Should return: /var/run/postgresql:5432 - accepting connections
```

### 2. Start Backend REST API

**From repository root:**
```bash
# Set database connection environment variables
# Windows PowerShell
$env:PEEGEEQ_DATABASE_HOST="localhost"
$env:PEEGEEQ_DATABASE_PORT="5432"
$env:PEEGEEQ_DATABASE_NAME="postgres"
$env:PEEGEEQ_DATABASE_USERNAME="peegeeq"
$env:PEEGEEQ_DATABASE_PASSWORD="peegeeq"
$env:PEEGEEQ_DATABASE_SCHEMA="public"

# Start backend (default port 8080)
mvn exec:java -pl peegeeq-rest

# Start backend on custom port (e.g., 9090)
mvn exec:java -pl peegeeq-rest -Dexec.args="9090"
```

**Linux/Mac:**
```bash
# Set database connection environment variables
export PEEGEEQ_DATABASE_HOST="localhost"
export PEEGEEQ_DATABASE_PORT="5432"
export PEEGEEQ_DATABASE_NAME="postgres"
export PEEGEEQ_DATABASE_USERNAME="peegeeq"
export PEEGEEQ_DATABASE_PASSWORD="peegeeq"
export PEEGEEQ_DATABASE_SCHEMA="public"

# Start backend (default port 8080)
mvn exec:java -pl peegeeq-rest

# Start backend on custom port (e.g., 9090)
mvn exec:java -pl peegeeq-rest -Dexec.args="9090"
```

**Verify backend is running:**
```bash
# Check health endpoint
curl http://localhost:8080/health

# Should return: {"status":"UP"}
```

### 3. Start Frontend Development Server

**From peegeeq-management-ui directory:**
```bash
# Default port (5173)
npm run dev

# Custom port
npm run dev -- --port 3000
```

**Verify frontend is running:**
```
Open browser to http://localhost:5173
```

### 4. Stop Components

**Stop frontend:**
```bash
# Press Ctrl+C in the terminal running npm
```

**Stop backend:**
```bash
# Press Ctrl+C in the terminal running Maven
```

**Stop and remove PostgreSQL:**
```bash
# Stop container
docker stop peegeeq-postgres

# Remove container
docker rm peegeeq-postgres
```

## Test Commands Reference

### Unit Tests (Vitest - Fast)

```bash
# Run all unit tests
npm test

# Watch mode (re-runs on changes)
npm test -- --watch

# With coverage report
npm run test:coverage

# Interactive UI
npm run test:ui
```

### E2E Tests (Playwright - Requires Backend)

**Basic Commands:**
```bash
# Run all E2E tests
npm run test:e2e

# Run specific test file
npx playwright test database-setup.spec.ts

# View test report
npx playwright show-report
```

**Debug Commands:**
```bash
# Watch tests execute in browser
npx playwright test --headed --workers=1

# Interactive debug mode (pause at each step)
npx playwright test --debug

# Update visual snapshots
npx playwright test --update-snapshots
```

> **Note**: Slow motion is configured in [playwright.config.ts](../playwright.config.ts) under `launchOptions.slowMo` (currently set to 1000ms). To adjust, edit the config file.

> **⚠️ Important**: Always run the **full test suite** for E2E tests. Individual test files may fail due to dependencies.

## Development Workflow

### Making Changes

1. **Edit Code**
   ```bash
   # Files in src/ directory
   ```

2. **Verify in Browser**
   ```bash
   # Check http://localhost:5173
   ```

3. **Run Tests**
   ```bash
   npm test              # Unit tests (fast)
   npm run test:e2e      # E2E tests (slow, requires backend)
   ```

4. **Debug Failures**
   ```bash
   # Watch tests in real browser with slow motion
   npx playwright test --headed --workers=1 --slowMo=500
   ```


## Troubleshooting

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

### Connection Errors
```bash
# Verify backend health
curl http://localhost:8080/health
```

### Port Already in Use
```bash
# Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

### Visual Test Failures
```bash
# Update snapshots (if changes are intentional)
npx playwright test --update-snapshots
```

### Vitest/Playwright Conflict (Fixed)
If you see `TypeError: Cannot redefine property: Symbol($$jest-matchers-object)`, ensure:
- `vitest.config.ts` has `globals: false`
- Vitest setup file is named `vitest.setup.ts` (not `setup.ts`)
- Setup file doesn't extend expect with jest-dom matchers globally

## Project Structure

```
peegeeq-management-ui/
├── src/
│   ├── pages/              # Page components
│   ├── components/         # Reusable UI components
│   ├── stores/             # Zustand state management
│   ├── services/           # API and WebSocket services
│   └── tests/
│       └── e2e/            # Playwright E2E tests
│           ├── page-objects/   # Page Object Model
│           └── specs/          # Test specifications
├── scripts/
│   └── start-backend-with-testcontainers.*  # Backend startup
├── playwright.config.ts    # Playwright configuration
└── vite.config.ts         # Vite configuration
```

## Testing Philosophy

This project uses **Playwright E2E tests** with a strict "no mocks" policy:

- **Unit Tests (Vitest)**: Fast component tests (~1 second total)
- **E2E Tests (Playwright)**: Full user workflows with real backend (~2-5 minutes)

**Why no mocks?**
- Tests verify actual system behavior
- Catches real integration bugs
- No mock maintenance overhead
- Tests serve as living documentation

## Learn More

- **[E2E_TESTING.md](./E2E_TESTING.md)** - Comprehensive E2E testing guide
- **[TESTING_SUMMARY.md](./TESTING_SUMMARY.md)** - Testing philosophy and status
- **[UI_TESTING_COMPLETE_GUIDE.md](./UI_TESTING_COMPLETE_GUIDE.md)** - Design-to-implementation workflow



