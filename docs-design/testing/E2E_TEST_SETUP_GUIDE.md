# E2E Test Execution Guide (Multi-Terminal Setup)

To run the E2E tests for the PeeGeeQ Management UI, you **MUST** use separate terminal sessions. Running them in the same session will cause the backend to be killed when the test runner starts.

## Terminal 1: Backend REST API
This terminal runs the Java backend. It **MUST** stay running for the duration of the tests.

1. Navigate to the management UI directory:
   ```powershell
   cd peegeeq-management-ui
   ```
2. Start the backend in a separate window (recommended):
   ```powershell
   Start-Process powershell.exe -ArgumentList "-NoExit", "-File", "$PWD\scripts\start-backend-with-testcontainers.ps1"
   ```
3. Wait for the message: `PeeGeeQ REST API server started successfully`.
4. Verify health: `Invoke-RestMethod -Uri "http://127.0.0.1:8080/health"`

## Terminal 2: UI Test Runner
This terminal executes the actual Playwright tests.

1. Navigate to the management UI directory:
   ```powershell
   cd peegeeq-management-ui
   ```
2. Run the tests:
   ```powershell
   npm run test:e2e
   ```

## Troubleshooting
- **Backend fails to start**: Ensure Docker is running. The script will automatically start a PostgreSQL container and generate `testcontainers-db.json`.
- **Tests fail with "fetch failed"**: 
  - Ensure the backend in Terminal 1 is still running.
  - If using `localhost` fails, try `127.0.0.1` (the tests are configured to use `127.0.0.1` for health checks to avoid IPv6 resolution issues).
- **Port 3000 already in use**: The test runner will attempt to kill existing dev servers, but you may need to manually kill the process if it fails.
- **Database Setup**: The tests use a dependency chain. The first test `4-database-setup` will create the necessary database schema through the UI. Do not skip this test.

## Automation Tip
You can run the backend and tests in one go using this PowerShell snippet:
```powershell
# Start backend in background
Start-Process powershell.exe -ArgumentList "-NoExit", "-File", "$PWD\scripts\start-backend-with-testcontainers.ps1"

# Wait for backend to be ready
do { 
    Write-Host "Waiting for backend..."
    Start-Sleep -Seconds 5
    $ready = try { (Invoke-RestMethod -Uri "http://127.0.0.1:8080/health").status -eq "UP" } catch { $false }
} until ($ready)

# Run tests
npm run test:e2e
```
