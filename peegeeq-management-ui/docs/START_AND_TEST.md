# Start Backend and Run E2E Tests

## Terminal 1: Start Backend + Database
```bash
cd scripts
./start-backend-with-testcontainers.ps1
```
**Leave this running**

## Terminal 2: Run E2E Tests (Headed Mode)
```bash
cd peegeeq-management-ui
npx playwright test --headed --workers=1
```

**Note:** `--workers=1` ensures test projects run sequentially to respect dependencies.
