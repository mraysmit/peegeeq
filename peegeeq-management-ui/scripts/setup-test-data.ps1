#!/usr/bin/env pwsh
# PowerShell script to create test data for E2E tests

$API_BASE = "http://localhost:8080"

Write-Host "🔧 Setting up test data for E2E tests..." -ForegroundColor Cyan
Write-Host ""

# Check if backend is running
Write-Host "1️⃣  Checking backend health..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$API_BASE/health" -Method Get -TimeoutSec 5
    Write-Host "   ✅ Backend is healthy" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Backend is not running on port 8080" -ForegroundColor Red
    Write-Host "   Please start the backend first: cd peegeeq-rest && mvn exec:java" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "2️⃣  Creating database setups..." -ForegroundColor Yellow

# Database configuration (using default PeeGeeQ database)
$dbConfig = @{
    host = "localhost"
    port = 5432
    databaseName = "peegeeq"
    username = "peegeeq"
    password = "peegeeq"
    schema = "public"
}

# Create test-setup with queues
try {
    $testSetup = @{
        setupId = "test-setup"
        databaseConfig = $dbConfig
        queues = @(
            @{
                queueName = "test-queue-1"
                batchSize = 10
                pollingInterval = "PT5S"
            },
            @{
                queueName = "test-queue-2"
                batchSize = 20
                pollingInterval = "PT10S"
            }
        )
        eventStores = @()
    } | ConvertTo-Json -Depth 5

    Invoke-RestMethod -Uri "$API_BASE/api/v1/database-setup/create" `
        -Method Post `
        -ContentType "application/json" `
        -Body $testSetup | Out-Null
    Write-Host "   ✅ Created setup: test-setup with 2 queues" -ForegroundColor Green
} catch {
    Write-Host "   ⚠️  Setup test-setup may already exist" -ForegroundColor Yellow
}

# Create demo-setup with queue
try {
    $demoSetup = @{
        setupId = "demo-setup"
        databaseConfig = $dbConfig
        queues = @(
            @{
                queueName = "demo-queue"
                batchSize = 5
                pollingInterval = "PT3S"
            }
        )
        eventStores = @()
    } | ConvertTo-Json -Depth 5

    Invoke-RestMethod -Uri "$API_BASE/api/v1/database-setup/create" `
        -Method Post `
        -ContentType "application/json" `
        -Body $demoSetup | Out-Null
    Write-Host "   ✅ Created setup: demo-setup with 1 queue" -ForegroundColor Green
} catch {
    Write-Host "   ⚠️  Setup demo-setup may already exist" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "3️⃣  Sending test messages..." -ForegroundColor Yellow

# Send messages to Queue 1
for ($i = 1; $i -le 5; $i++) {
    try {
        $message = @{
            payload = "{`"type`": `"test`", `"data`": `"message $i`", `"timestamp`": `"$(Get-Date -Format 'o')`"}"
            headers = @{
                source = "test-script"
                messageId = "msg-$i"
            }
        } | ConvertTo-Json -Depth 3
        
        Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/test-setup/test-queue-1/messages" `
            -Method Post `
            -ContentType "application/json" `
            -Body $message | Out-Null
    } catch {
        Write-Host "   ⚠️  Failed to send message $i to test-queue-1" -ForegroundColor Yellow
    }
}
Write-Host "   ✅ Sent 5 messages to test-setup/test-queue-1" -ForegroundColor Green

# Send messages to Queue 2
for ($i = 1; $i -le 3; $i++) {
    try {
        $message = @{
            payload = "{`"type`": `"demo`", `"data`": `"sample $i`", `"timestamp`": `"$(Get-Date -Format 'o')`"}"
            headers = @{
                source = "demo-script"
                messageId = "demo-$i"
            }
        } | ConvertTo-Json -Depth 3
        
        Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/test-setup/test-queue-2/messages" `
            -Method Post `
            -ContentType "application/json" `
            -Body $message | Out-Null
    } catch {
        Write-Host "   ⚠️  Failed to send message $i to test-queue-2" -ForegroundColor Yellow
    }
}
Write-Host "   ✅ Sent 3 messages to test-setup/test-queue-2" -ForegroundColor Green

# Send messages to Queue 3
for ($i = 1; $i -le 2; $i++) {
    try {
        $message = @{
            payload = "{`"type`": `"demo`", `"data`": `"demo message $i`", `"timestamp`": `"$(Get-Date -Format 'o')`"}"
            headers = @{
                source = "demo-script"
                messageId = "demo-queue-$i"
            }
        } | ConvertTo-Json -Depth 3
        
        Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/demo-setup/demo-queue/messages" `
            -Method Post `
            -ContentType "application/json" `
            -Body $message | Out-Null
    } catch {
        Write-Host "   ⚠️  Failed to send message $i to demo-queue" -ForegroundColor Yellow
    }
}
Write-Host "   ✅ Sent 2 messages to demo-setup/demo-queue" -ForegroundColor Green

Write-Host ""
Write-Host "4️⃣  Verifying test data..." -ForegroundColor Yellow

try {
    $overview = Invoke-RestMethod -Uri "$API_BASE/api/v1/management/overview" -Method Get
    Write-Host "   📊 System Overview:" -ForegroundColor Cyan
    Write-Host "      Total Queues: $($overview.totalQueues)" -ForegroundColor White
    Write-Host "      Total Messages: $($overview.totalMessages)" -ForegroundColor White
    Write-Host "      Total Consumer Groups: $($overview.totalConsumerGroups)" -ForegroundColor White
    Write-Host "      Total Event Stores: $($overview.totalEventStores)" -ForegroundColor White
} catch {
    Write-Host "   ⚠️  Could not fetch overview data" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "✅ Test data setup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "📋 Next steps:" -ForegroundColor Cyan
Write-Host "   1. Verify data in UI: http://localhost:3000" -ForegroundColor White
Write-Host "   2. Run E2E tests: npm run test:e2e" -ForegroundColor White
Write-Host ""
Write-Host "🧹 To clean up test data, run: ./cleanup-test-data.ps1" -ForegroundColor Gray

