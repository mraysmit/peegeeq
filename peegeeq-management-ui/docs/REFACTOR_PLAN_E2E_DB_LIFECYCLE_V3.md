# Refactoring Plan: Inverting Test Database Lifecycle Ownership (V3)

> **NOTE:** The existing test process is very poorly designed. This refactoring aims to correct fundamental architectural flaws.
>
> **Why is it poorly designed?**
> 1.  **Circular Dependency**: The UI test runner (Playwright) is responsible for starting the database, but the Backend service requires that database to start. Playwright *also* waits for the Backend to be healthy. This creates a deadlock where you cannot start the backend without running the tests first, and the tests fail because the backend isn't running.
> 2.  **Inverted Responsibility**: The frontend test suite is provisioning infrastructure for the backend service. The backend should own its own dependencies, or they should be managed by a dedicated orchestration layer.
> 3.  **Fragile Workflow**: Developers are forced into a confusing "Run Tests (to start DB) -> Fail -> Start Backend -> Run Tests Again" loop.

## 1. Detailed Analysis of Current State
The user has correctly identified that the previous plan lacked depth regarding Playwright's interaction with TestContainers.

### What Playwright (`global-setup-testcontainers.ts`) Currently Does:
1.  **Container Orchestration**: It uses the Node.js `@testcontainers/postgresql` library to start a Docker container.
    *   **Image**: `postgres:15.13-alpine3.20`.
    *   **Reuse Strategy**: It calls `.withReuse()`. This relies on the TestContainers "Ryuk" sidecar (or label-based discovery) to find an existing container with the exact same configuration hash. If found, it connects to it; if not, it starts a new one. This is why the container persists across test runs.
2.  **User Provisioning**:
    *   It starts the container as the default `postgres` user.
    *   It *manually* executes a `psql` command inside the container: `CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';`.
    *   It verifies the user has `SUPERUSER` privileges.
3.  **Configuration Export**:
    *   It extracts the dynamic host port (e.g., `32768`) mapped to container port `5432`.
    *   It writes a JSON file `testcontainers-db.json` with the schema:
        ```json
        {
          "host": "localhost",
          "port": 12345,
          "database": "postgres",
          "username": "peegeeq",
          "password": "peegeeq",
          "jdbcUrl": "postgres://peegeeq:peegeeq@localhost:12345/postgres"
        }
        ```
4.  **State Tracking**: It writes a `.testcontainers-state.json` file to track the container ID for teardown (though `globalTeardown` currently leaves it running due to reuse).

### The Problem
The Backend (`start-backend-with-testcontainers.ps1`) *depends* on `testcontainers-db.json` to know where the DB is. But that file is only created when Playwright runs. This creates a circular dependency:
*   You can't start the Backend without the DB config.
*   You can't get the DB config without running Playwright.
*   Playwright fails if the Backend isn't running (health check).

## 2. Objective
Invert the control flow. The **Backend Script** becomes the "Infrastructure Owner". It will replicate the *exact* behavior of the Playwright setup, but in PowerShell/Docker CLI, removing the dependency on the Node.js TestContainers library.

## 3. Implementation Plan

### Phase 0: Safety Backup (Immediate Rollback Capability)
Before applying any changes, we will create physical backup copies of the files to be modified. This ensures that even if git state gets confused, we have the original files on disk.

**Action:**
1.  Copy `scripts/start-backend-with-testcontainers.ps1` to `scripts/start-backend-with-testcontainers.ps1.bak`
2.  Copy `src/tests/global-setup-testcontainers.ts` to `src/tests/global-setup-testcontainers.ts.bak`

### Phase 1: Enhance Backend Startup Script (`start-backend-with-testcontainers.ps1`)
**Goal**: Replicate the exact container provisioning logic currently in `global-setup-testcontainers.ts`.

**New Developer Workflow:**
Once this script is updated, the new workflow for running E2E tests will be:
1.  **Start Environment**: Run `.\scripts\start-backend-with-testcontainers.ps1`.
    *   This script will now handle *everything*: starting Docker, configuring the DB, and starting the Java backend.
    *   It will produce the `testcontainers-db.json` file required by the tests.
2.  **Run Tests**: Run `npm run test:e2e`.
    *   Playwright will detect the existing configuration and run against the environment you just started.

**Proposed Script Content:**
```powershell
# Start the PeeGeeQ backend with TestContainers database connection

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$DbConfigFile = Join-Path $ProjectRoot testcontainers-db.json
$ContainerName = "peegeeq-e2e-db"
$DbImage = "postgres:15.13-alpine3.20"

# --- Step 1: Ensure Database is Running ---

Write-Host "Checking database container '$ContainerName'..." -ForegroundColor Cyan

# Check if container exists (running or stopped)
$ContainerExists = docker ps -a -q -f name="^/${ContainerName}$"

if (-not $ContainerExists) {
    Write-Host "Starting new PostgreSQL container..." -ForegroundColor Green
    # Run detached, map random host port to 5432
    docker run -d --name $ContainerName -p 0:5432 -e POSTGRES_PASSWORD=postgres $DbImage
    
    Write-Host "Waiting for Database to be ready..."
    $MaxRetries = 30
    $RetryCount = 0
    $DbReady = $false

    while (-not $DbReady -and $RetryCount -lt $MaxRetries) {
        Start-Sleep -Seconds 1
        $RetryCount++
        
        # Check if Postgres is accepting connections
        try {
            docker exec $ContainerName pg_isready -U postgres -q
            if ($LASTEXITCODE -eq 0) {
                $DbReady = $true
            }
        } catch {
            # Ignore errors while waiting
        }
        Write-Host -NoNewline "."
    }
    Write-Host ""

    if (-not $DbReady) {
        Write-Error "Database failed to start after 30 seconds."
        exit 1
    }
    
    Write-Host "Creating 'peegeeq' user..."
    docker exec $ContainerName psql -U postgres -c "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    
    # Verify SUPERUSER privilege (Parity with global-setup-testcontainers.ts)
    $CheckResult = docker exec $ContainerName psql -U postgres -t -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'peegeeq';"
    if ($CheckResult.Trim() -ne 't') {
        Write-Error "Failed to verify peegeeq user SUPERUSER privilege."
    }
    
} else {
    # Check if it's actually running
    $IsRunning = docker ps -q -f name="^/${ContainerName}$"
    if (-not $IsRunning) {
        Write-Host "Starting existing container..." -ForegroundColor Yellow
        docker start $ContainerName
        Start-Sleep -Seconds 3
    } else {
        Write-Host "Container is already running." -ForegroundColor Yellow
    }
}

# --- Step 2: Get Connection Details & Write Config ---

# Get the dynamic port mapping (Format: 0.0.0.0:32768)
$PortMapping = docker port $ContainerName 5432
if (-not $PortMapping) {
    Write-Error "Could not determine port mapping for container $ContainerName"
}

# Extract just the port number
$DbPort = ($PortMapping -split ":")[-1]
$DbHost = "localhost"

Write-Host "Database is listening on $DbHost`:$DbPort" -ForegroundColor Green

$DbConfig = @{
    host = $DbHost
    port = [int]$DbPort
    database = "postgres"
    username = "peegeeq"
    password = "peegeeq"
    jdbcUrl = "postgres://peegeeq:peegeeq@$($DbHost):$($DbPort)/postgres"
}

# Write the config file so Playwright can find it later
$DbConfig | ConvertTo-Json -Depth 2 | Set-Content $DbConfigFile
Write-Host "Updated connection config: $DbConfigFile" -ForegroundColor Cyan

# --- Step 3: Start Backend ---

# Set environment variables for PeeGeeQ
$env:PEEGEEQ_DATABASE_HOST = $DbConfig.host
$env:PEEGEEQ_DATABASE_PORT = $DbConfig.port
$env:PEEGEEQ_DATABASE_NAME = $DbConfig.database
$env:PEEGEEQ_DATABASE_USERNAME = $DbConfig.username
$env:PEEGEEQ_DATABASE_PASSWORD = $DbConfig.password
$env:PEEGEEQ_DATABASE_SCHEMA = "public"

Write-Host ""
Write-Host "Starting PeeGeeQ REST API server..." -ForegroundColor Cyan
Write-Host ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
$ManagementUiRoot = Split-Path -Parent $ScriptDir
$RepositoryRoot = Split-Path -Parent $ManagementUiRoot
Set-Location $RepositoryRoot

# Build the Maven command with system properties
$MavenArgs = @(
    "exec:java",
    "-pl", "peegeeq-rest",
    "-DPEEGEEQ_DATABASE_HOST=$($DbConfig.host)",
    "-DPEEGEEQ_DATABASE_PORT=$($DbConfig.port)",
    "-DPEEGEEQ_DATABASE_NAME=$($DbConfig.database)",
    "-DPEEGEEQ_DATABASE_USERNAME=$($DbConfig.username)",
    "-DPEEGEEQ_DATABASE_PASSWORD=$($DbConfig.password)",
    "-DPEEGEEQ_DATABASE_SCHEMA=public"
)

& mvn $MavenArgs
```

### Phase 1.5: Enhance Backend Startup Script (Linux/Mac - `start-backend-with-testcontainers.sh`)
**Goal**: Replicate the PowerShell logic in Bash for Linux/macOS users.

**Proposed Script Content:**
```bash
#!/bin/bash
set -e

# Start the PeeGeeQ backend with TestContainers database connection

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DB_CONFIG_FILE="$PROJECT_ROOT/testcontainers-db.json"
CONTAINER_NAME="peegeeq-e2e-db"
DB_IMAGE="postgres:15.13-alpine3.20"

# --- Step 1: Ensure Database is Running ---

echo "Checking database container '$CONTAINER_NAME'..."

# Check if container exists (running or stopped)
if [ "$(docker ps -a -q -f name="^/${CONTAINER_NAME}$")" ]; then
    # Check if it's actually running
    if [ "$(docker ps -q -f name="^/${CONTAINER_NAME}$")" ]; then
        echo "Container is already running."
    else
        echo "Starting existing container..."
        docker start $CONTAINER_NAME
        sleep 3
    fi
else
    echo "Starting new PostgreSQL container..."
    # Run detached, map random host port to 5432
    docker run -d --name $CONTAINER_NAME -p 0:5432 -e POSTGRES_PASSWORD=postgres $DB_IMAGE
    
    echo "Waiting for Database to be ready..."
    MAX_RETRIES=30
    RETRY_COUNT=0
    DB_READY=false

    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        sleep 1
        RETRY_COUNT=$((RETRY_COUNT+1))
        
        # Check if Postgres is accepting connections
        if docker exec $CONTAINER_NAME pg_isready -U postgres -q; then
            DB_READY=true
            break
        fi
        echo -n "."
    done
    echo ""

    if [ "$DB_READY" = false ]; then
        echo "Database failed to start after 30 seconds."
        exit 1
    fi
    
    echo "Creating 'peegeeq' user..."
    docker exec $CONTAINER_NAME psql -U postgres -c "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    
    # Verify SUPERUSER privilege
    CHECK_RESULT=$(docker exec $CONTAINER_NAME psql -U postgres -t -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'peegeeq';")
    if [[ $(echo "$CHECK_RESULT" | tr -d '[:space:]') != "t" ]]; then
        echo "Failed to verify peegeeq user SUPERUSER privilege."
        exit 1
    fi
fi

# --- Step 2: Get Connection Details & Write Config ---

# Get the dynamic port mapping (Format: 0.0.0.0:32768)
PORT_MAPPING=$(docker port $CONTAINER_NAME 5432)
if [ -z "$PORT_MAPPING" ]; then
    echo "Could not determine port mapping for container $CONTAINER_NAME"
    exit 1
fi

# Extract just the port number (assuming 0.0.0.0:PORT format)
DB_PORT=$(echo $PORT_MAPPING | awk -F: '{print $NF}')
DB_HOST="localhost"

echo "Database is listening on $DB_HOST:$DB_PORT"

# Create JSON config
cat > "$DB_CONFIG_FILE" <<EOF
{
  "host": "$DB_HOST",
  "port": $DB_PORT,
  "database": "postgres",
  "username": "peegeeq",
  "password": "peegeeq",
  "jdbcUrl": "postgres://peegeeq:peegeeq@$DB_HOST:$DB_PORT/postgres"
}
EOF

echo "Updated connection config: $DB_CONFIG_FILE"

# --- Step 3: Start Backend ---

# Set environment variables for PeeGeeQ
export PEEGEEQ_DATABASE_HOST=$DB_HOST
export PEEGEEQ_DATABASE_PORT=$DB_PORT
export PEEGEEQ_DATABASE_NAME="postgres"
export PEEGEEQ_DATABASE_USERNAME="peegeeq"
export PEEGEEQ_DATABASE_PASSWORD="peegeeq"
export PEEGEEQ_DATABASE_SCHEMA="public"

echo ""
echo "Starting PeeGeeQ REST API server..."
echo ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
MANAGEMENT_UI_ROOT="$(dirname "$SCRIPT_DIR")"
REPOSITORY_ROOT="$(dirname "$MANAGEMENT_UI_ROOT")"
cd "$REPOSITORY_ROOT"

# Build the Maven command with system properties
mvn exec:java \
    -pl peegeeq-rest \
    -DPEEGEEQ_DATABASE_HOST=$DB_HOST \
    -DPEEGEEQ_DATABASE_PORT=$DB_PORT \
    -DPEEGEEQ_DATABASE_NAME=postgres \
    -DPEEGEEQ_DATABASE_USERNAME=peegeeq \
    -DPEEGEEQ_DATABASE_PASSWORD=peegeeq \
    -DPEEGEEQ_DATABASE_SCHEMA=public
```

### Phase 2: Refactor Playwright Global Setup (`global-setup-testcontainers.ts`)
**Goal**: Remove infrastructure management responsibilities but KEEP test data lifecycle management.

**Proposed Script Content:**
```typescript
import * as fs from 'fs'
import * as path from 'path'

/**
 * Global setup for Playwright tests.
 *
 * This setup:
 * 1. Reads connection details from testcontainers-db.json (created by start-backend-with-testcontainers.ps1)
 * 2. Checks if the backend is running and healthy
 * 3. Cleans up any existing database setups via the API (Test Data Lifecycle)
 *
 * Note: The database container is now managed by the backend startup script, not Playwright.
 */

async function globalSetup() {
  console.log('\n🔍 Checking test environment configuration...')

  const dbConfigPath = path.join(process.cwd(), 'testcontainers-db.json')
  
  if (!fs.existsSync(dbConfigPath)) {
    console.error('\n❌ Database configuration file not found:', dbConfigPath)
    console.error('   Please run the backend startup script first:')
    console.error('   ./scripts/start-backend-with-testcontainers.ps1 (Windows)')
    console.error('   ./scripts/start-backend-with-testcontainers.sh (Linux/Mac)')
    process.exit(1)
  }

  try {
    const dbConfig = JSON.parse(fs.readFileSync(dbConfigPath, 'utf8'))
    console.log('✅ Found database configuration')
    console.log(`   Host: ${dbConfig.host}`)
    console.log(`   Port: ${dbConfig.port}`)
    console.log(`   Database: ${dbConfig.database}`)

    // Check if backend is running
    console.log('\n🔍 Checking if PeeGeeQ backend is running...')
    const API_BASE_URL = 'http://127.0.0.1:8080'

    try {
      const response = await fetch(`${API_BASE_URL}/health`, {
        method: 'GET',
        signal: AbortSignal.timeout(5000),
      })

      if (!response.ok) {
        console.error(`\n❌ Backend health check failed with status: ${response.status}`)
        console.error('   Please start the PeeGeeQ REST server on port 8080 before running e2e tests.')
        process.exit(1)
      }

      console.log('✅ Backend is running and healthy')
    } catch (error) {
      console.error('\n❌ Cannot connect to PeeGeeQ backend at http://127.0.0.1:8080')
      console.error('   Error:', error instanceof Error ? error.message : String(error))
      console.error('\n   Please start the PeeGeeQ REST server before running e2e tests.')
      process.exit(1)
    }

    // --- TEST DATA LIFECYCLE MANAGEMENT ---
    // Clean up any existing database setups from previous test runs
    console.log('\n🧹 Cleaning up existing database setups...')
    try {
      const setupsResponse = await fetch(`${API_BASE_URL}/api/v1/setups`, {
        method: 'GET',
        signal: AbortSignal.timeout(5000),
      })

      if (setupsResponse.ok) {
        const setupsData = await setupsResponse.json()
        if (setupsData.setupIds && Array.isArray(setupsData.setupIds)) {
          for (const setupId of setupsData.setupIds) {
            try {
              await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, {
                method: 'DELETE',
                signal: AbortSignal.timeout(5000),
              })
              console.log(`   ✓ Deleted setup: ${setupId}`)
            } catch (error) {
              console.warn(`   ⚠️  Failed to delete setup ${setupId}:`, error instanceof Error ? error.message : String(error))
            }
          }
        }
        console.log('✅ Database cleanup complete')
      }
    } catch (error) {
      console.warn('⚠️  Error during database cleanup:', error instanceof Error ? error.message : String(error))
    }

    console.log('\n✅ Global setup complete')
  } catch (error) {
    console.error('\n❌ Failed to read database configuration')
    console.error('   Error:', error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}

/**
 * Global teardown
 */
async function globalTeardown() {
  // No cleanup needed as we don't manage the container anymore
  console.log('\n✅ Global teardown complete')
}

export default globalSetup
export { globalTeardown }
```

### Phase 3: Verification Strategy
1.  **Clean Slate**: `docker rm -f peegeeq-e2e-db` and delete `testcontainers-db.json`.
2.  **Step 1 (Backend)**: Run `.\scripts\start-backend-with-testcontainers.ps1`.
    *   *Verify*: Container `peegeeq-e2e-db` is running.
    *   *Verify*: `testcontainers-db.json` exists and has valid JSON.
    *   *Verify*: Backend starts and logs connection to the DB.
3.  **Step 2 (Frontend)**: Run `npm run test:e2e`.
    *   *Verify*: Playwright starts without attempting to spin up Docker.
    *   *Verify*: Tests pass.

### Phase 4: Cleanup Helper
Since the container is now persistent and not managed by Playwright's teardown, developers need a way to stop the environment.

**Action:**
Create a simple cleanup script `scripts/stop-backend-env.ps1` (and `.sh` equivalent) or document the command.

**Proposed `stop-backend-env.ps1`:**
```powershell
docker stop peegeeq-e2e-db
Remove-Item -Path "../testcontainers-db.json" -ErrorAction SilentlyContinue
Write-Host "Environment stopped and config removed."
```

## 4. Rollback Plan
If the refactor fails or causes issues, we can restore the original state immediately using the backups created in Phase 0.

**Steps:**
1.  **Restore Files**:
    *   `Copy-Item scripts/start-backend-with-testcontainers.ps1.bak scripts/start-backend-with-testcontainers.ps1 -Force`
    *   `Copy-Item src/tests/global-setup-testcontainers.ts.bak src/tests/global-setup-testcontainers.ts -Force`
2.  **Clean Infrastructure**:
    *   `docker rm -f peegeeq-e2e-db`
    *   `Remove-Item testcontainers-db.json`
3.  **Verify Restoration**:
    *   Run `npm run test:e2e` (This should revert to the old behavior of starting the container itself).
