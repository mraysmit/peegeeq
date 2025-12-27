#!/usr/bin/env pwsh

Write-Host "Creating test setup for E2E tests..." -ForegroundColor Cyan
Write-Host ""

$API_BASE = "http://localhost:8080"

# Generate unique database name to avoid conflicts
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$dbName = "peegeeq_e2e_test_$timestamp"

Write-Host "Database name: $dbName" -ForegroundColor Yellow
Write-Host ""

# Create setup with new database
$setupBody = @{
    setupId = "e2e-test-setup"
    databaseConfig = @{
        host = "localhost"
        port = 5432
        databaseName = $dbName
        username = "peegeeq"
        password = "peegeeq"
        schema = "public"
        templateDatabase = "template0"
        encoding = "UTF8"
    }
    queues = @(
        @{
            queueName = "test-queue-1"
            batchSize = 10
            pollingInterval = "PT5S"
            maxRetries = 3
            visibilityTimeout = "PT30S"
        },
        @{
            queueName = "test-queue-2"
            batchSize = 20
            pollingInterval = "PT10S"
            maxRetries = 5
            visibilityTimeout = "PT60S"
        }
    )
    eventStores = @(
        @{
            eventStoreName = "test-events"
            tableName = "test_events"
            aggregateType = "Order"
        }
    )
} | ConvertTo-Json -Depth 5

Write-Host "Request body:" -ForegroundColor Cyan
Write-Host $setupBody
Write-Host ""

Write-Host "Creating setup..." -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri "$API_BASE/api/v1/database-setup/create" `
        -Method Post `
        -ContentType 'application/json' `
        -Body $setupBody `
        -TimeoutSec 120
    
    Write-Host ""
    Write-Host "✅ Setup created successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Setup ID: e2e-test-setup" -ForegroundColor Cyan
    Write-Host "Database: $dbName" -ForegroundColor Cyan
    Write-Host "Queues: test-queue-1, test-queue-2" -ForegroundColor Cyan
    Write-Host "Event Stores: test-events" -ForegroundColor Cyan
    Write-Host ""
    
    # Verify setup was created
    Write-Host "Verifying setup..." -ForegroundColor Yellow
    $setups = Invoke-RestMethod -Uri "$API_BASE/api/v1/setups" -Method Get
    Write-Host "Available setups:" -ForegroundColor Cyan
    Write-Host ($setups | ConvertTo-Json -Depth 3)
    Write-Host ""
    
    # Verify queues were created
    Write-Host "Verifying queues..." -ForegroundColor Yellow
    $queues = Invoke-RestMethod -Uri "$API_BASE/api/v1/management/queues" -Method Get
    Write-Host "Queues:" -ForegroundColor Cyan
    Write-Host ($queues | ConvertTo-Json -Depth 3)
    Write-Host ""
    
    Write-Host "✅ Setup verification complete!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "1. Open http://localhost:3000 to view the UI" -ForegroundColor White
    Write-Host "2. Navigate to Queues to see test-queue-1 and test-queue-2" -ForegroundColor White
    Write-Host "3. Click on a queue to publish test messages" -ForegroundColor White
    Write-Host "4. Run E2E tests: npm run test:e2e" -ForegroundColor White
    Write-Host ""
    Write-Host "To clean up: ./cleanup-test-setup.ps1" -ForegroundColor Yellow
    
} catch {
    Write-Host ""
    Write-Host "❌ Failed to create setup!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Status Code:" $_.Exception.Response.StatusCode.value__ -ForegroundColor Red
    Write-Host "Error:" $_.Exception.Message -ForegroundColor Red
    
    if ($_.ErrorDetails.Message) {
        Write-Host ""
        Write-Host "Details:" -ForegroundColor Yellow
        Write-Host $_.ErrorDetails.Message -ForegroundColor White
    }
    
    Write-Host ""
    Write-Host "Common issues:" -ForegroundColor Yellow
    Write-Host "1. PostgreSQL not running or not accessible" -ForegroundColor White
    Write-Host "2. Insufficient permissions to create databases" -ForegroundColor White
    Write-Host "3. Backend REST server not running on port 8080" -ForegroundColor White
    Write-Host "4. Database user 'peegeeq' doesn't have CREATEDB permission" -ForegroundColor White
    Write-Host ""
    Write-Host "To grant CREATEDB permission:" -ForegroundColor Cyan
    Write-Host "  ALTER USER peegeeq CREATEDB;" -ForegroundColor White
    
    exit 1
}

