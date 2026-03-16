#!/usr/bin/env pwsh
# PowerShell script to clean up test data after E2E tests

$API_BASE = "http://localhost:8080"

Write-Host "🧹 Cleaning up test data..." -ForegroundColor Cyan
Write-Host ""

# Check if backend is running
Write-Host "1️⃣  Checking backend health..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$API_BASE/health" -Method Get -TimeoutSec 5
    Write-Host "   Backend is healthy" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Backend is not running on port 8080" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "2️⃣  Purging test queues..." -ForegroundColor Yellow

# Purge Queue 1
try {
    Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/test-setup/test-queue-1/purge" `
        -Method Post | Out-Null
    Write-Host "   Purged queue: test-setup/test-queue-1" -ForegroundColor Green
} catch {
    Write-Host "   ⚠️  Could not purge test-setup/test-queue-1 (may not exist)" -ForegroundColor Yellow
}

# Purge Queue 2
try {
    Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/test-setup/test-queue-2/purge" `
        -Method Post | Out-Null
    Write-Host "   Purged queue: test-setup/test-queue-2" -ForegroundColor Green
} catch {
    Write-Host "   ⚠️  Could not purge test-setup/test-queue-2 (may not exist)" -ForegroundColor Yellow
}

# Purge Queue 3
try {
    Invoke-RestMethod -Uri "$API_BASE/api/v1/queues/demo-setup/demo-queue/purge" `
        -Method Post | Out-Null
    Write-Host "   Purged queue: demo-setup/demo-queue" -ForegroundColor Green
} catch {
    Write-Host "   ⚠️  Could not purge demo-setup/demo-queue (may not exist)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "3️⃣  Verifying cleanup..." -ForegroundColor Yellow

try {
    $overview = Invoke-RestMethod -Uri "$API_BASE/api/v1/management/overview" -Method Get
    Write-Host "   📊 System Overview:" -ForegroundColor Cyan
    Write-Host "      Total Queues: $($overview.totalQueues)" -ForegroundColor White
    Write-Host "      Total Messages: $($overview.totalMessages)" -ForegroundColor White
    Write-Host "      Total Consumer Groups: $($overview.totalConsumerGroups)" -ForegroundColor White
} catch {
    Write-Host "   ⚠️  Could not fetch overview data" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Cleanup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "📋 Note: Queues still exist but are empty. To fully remove queues," -ForegroundColor Gray
Write-Host "   restart the backend or use the delete queue API endpoints." -ForegroundColor Gray

