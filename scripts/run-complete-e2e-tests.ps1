entire paageeq test suite - what are you talking about<#
# >#!/usr/bin/env pwsh
# Complete E2E Test Suite
# This script runs the COMPLETE test cycle including backend restart

param(
    [switch]$SkipUnitTests,
    [switch]$SkipBuild,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"

Write-Host "`n╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     PeeGeeQ Complete E2E Test Suite                        ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝`n" -ForegroundColor Cyan

$startTime = Get-Date
$failed = $false

# Step 1: Unit Tests
if (-not $SkipUnitTests) {
    Write-Host "📋 Step 1/6: Running Unit Tests..." -ForegroundColor Yellow
    Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

    mvn test -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Unit tests FAILED" -ForegroundColor Red
        $failed = $true
        exit 1
    }
    Write-Host "✅ Unit tests PASSED" -ForegroundColor Green
} else {
    Write-Host "⏭️  Skipping unit tests" -ForegroundColor Yellow
}

# Step 2: Build Artifacts
if (-not $SkipBuild) {
    Write-Host "`n📦 Step 2/6: Building Artifacts..." -ForegroundColor Yellow
    Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

    mvn clean install -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Build FAILED" -ForegroundColor Red
        $failed = $true
        exit 1
    }
    Write-Host "✅ Build completed successfully" -ForegroundColor Green
} else {
    Write-Host "⏭️  Skipping build" -ForegroundColor Yellow
}

# Step 3: Stop Old Backend
Write-Host "`n🛑 Step 3/6: Stopping Old Backend..." -ForegroundColor Yellow
Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

# Find and kill Java processes running peegeeq-runtime
$javaProcesses = Get-Process -Name java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like "*peegeeq*" -or $_.CommandLine -like "*peegeeq-runtime*" }

if ($javaProcesses) {
    foreach ($proc in $javaProcesses) {
        Write-Host "  Stopping process: $($proc.Id)" -ForegroundColor Gray
        Stop-Process -Id $proc.Id -Force
    }
    Start-Sleep -Seconds 3
    Write-Host "✅ Old backend stopped" -ForegroundColor Green
} else {
    Write-Host "ℹ️  No running backend found" -ForegroundColor Gray
}

# Step 4: Start New Backend
Write-Host "`n🚀 Step 4/6: Starting NEW Backend with NEW Artifacts..." -ForegroundColor Yellow
Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

# Start backend in background
$backendJob = Start-Job -ScriptBlock {
    Set-Location $using:PWD
    & ./start-backend-with-testcontainers.ps1
}

Write-Host "  Backend starting (Job ID: $($backendJob.Id))..." -ForegroundColor Gray
Write-Host "  Waiting for backend to be ready..." -ForegroundColor Gray

# Wait for backend health check
$maxWait = 60
$waited = 0
$backendReady = $false

while ($waited -lt $maxWait) {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/health" -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($response.status -eq "UP") {
            $backendReady = $true
            break
        }
    } catch {
        # Backend not ready yet
    }

    Start-Sleep -Seconds 2
    $waited += 2
    Write-Host "." -NoNewline -ForegroundColor Gray
}

Write-Host ""

if (-not $backendReady) {
    Write-Host "❌ Backend failed to start within $maxWait seconds" -ForegroundColor Red
    Stop-Job $backendJob
    Remove-Job $backendJob
    exit 1
}

Write-Host "✅ Backend is UP and healthy" -ForegroundColor Green

# Step 5: Run Integration Tests
Write-Host "`n🧪 Step 5/6: Running Integration Tests Against NEW Backend..." -ForegroundColor Yellow
Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

Set-Location peegeeq-integration-tests

if ($Verbose) {
    mvn test
} else {
    mvn test -q
}

$integrationTestResult = $LASTEXITCODE

Set-Location ..

if ($integrationTestResult -ne 0) {
    Write-Host "❌ Integration tests FAILED" -ForegroundColor Red
    $failed = $true
} else {
    Write-Host "✅ Integration tests PASSED" -ForegroundColor Green
}

# Step 6: Cleanup
Write-Host "`n🧹 Step 6/6: Cleanup..." -ForegroundColor Yellow
Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Gray

Write-Host "  Stopping backend job..." -ForegroundColor Gray
Stop-Job $backendJob
Remove-Job $backendJob

Write-Host "✅ Cleanup complete" -ForegroundColor Green

# Summary
$endTime = Get-Date
$duration = $endTime - $startTime

Write-Host "`n╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     E2E Test Suite Summary                                 ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝`n" -ForegroundColor Cyan

Write-Host "Total Time: $($duration.TotalSeconds) seconds" -ForegroundColor White

if ($failed) {
    Write-Host "`n❌ E2E TESTS FAILED" -ForegroundColor Red
    Write-Host "   Please review the errors above and fix before deploying.`n" -ForegroundColor Red
    exit 1
} else {
    Write-Host "`n✅ ALL E2E TESTS PASSED" -ForegroundColor Green
    Write-Host "   System is ready for deployment!`n" -ForegroundColor Green
    exit 0
}
