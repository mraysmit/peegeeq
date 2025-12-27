#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Update visual regression test baselines for PeeGeeQ Management UI

.DESCRIPTION
    This script updates Playwright visual regression test baselines.
    Ensure both backend (port 8080) and frontend (port 3000) are running before executing.

.PARAMETER TestPattern
    Optional test name pattern to update specific tests only
    
.PARAMETER Browser
    Browser to update baselines for (chromium, firefox, webkit, or all)
    Default: chromium

.PARAMETER Force
    Skip confirmation prompts

.EXAMPLE
    .\update-baselines.ps1
    Update all visual baselines for Chromium

.EXAMPLE
    .\update-baselines.ps1 -TestPattern "Layout" -Browser "all"
    Update layout tests for all browsers

.EXAMPLE
    .\update-baselines.ps1 -Browser "firefox" -Force
    Update all baselines for Firefox without confirmation
#>

param(
    [string]$TestPattern = "",
    [ValidateSet("chromium", "firefox", "webkit", "all")]
    [string]$Browser = "chromium",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "PeeGeeQ Visual Baseline Update Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if servers are running
Write-Host "Checking if backend server is running..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080/health" -UseBasicParsing -TimeoutSec 3
    Write-Host "✓ Backend server is running on port 8080" -ForegroundColor Green
} catch {
    Write-Host "✗ Backend server is NOT running on port 8080" -ForegroundColor Red
    Write-Host "  Please start the backend server first:" -ForegroundColor Red
    Write-Host "  cd peegeeq-rest" -ForegroundColor Yellow
    Write-Host "  mvn clean compile exec:java" -ForegroundColor Yellow
    exit 1
}

Write-Host "Checking if frontend dev server is running..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:3000" -UseBasicParsing -TimeoutSec 3
    Write-Host "✓ Frontend dev server is running on port 3000" -ForegroundColor Green
} catch {
    Write-Host "✗ Frontend dev server is NOT running on port 3000" -ForegroundColor Red
    Write-Host "  Please start the frontend dev server first:" -ForegroundColor Red
    Write-Host "  cd peegeeq-management-ui" -ForegroundColor Yellow
    Write-Host "  npm run dev" -ForegroundColor Yellow
    exit 1
}

Write-Host ""

# Build command
$command = "npx playwright test visual-regression --update-snapshots"

if ($TestPattern -ne "") {
    $command += " --grep `"$TestPattern`""
    Write-Host "Test Pattern: $TestPattern" -ForegroundColor Cyan
}

if ($Browser -ne "all") {
    $command += " --project=$Browser"
    Write-Host "Browser: $Browser" -ForegroundColor Cyan
} else {
    Write-Host "Browser: All browsers" -ForegroundColor Cyan
}

Write-Host ""

# Confirm
if (-not $Force) {
    Write-Host "This will update visual regression test baselines." -ForegroundColor Yellow
    Write-Host "Command: $command" -ForegroundColor Gray
    Write-Host ""
    $confirmation = Read-Host "Continue? (y/n)"
    if ($confirmation -ne "y") {
        Write-Host "Cancelled." -ForegroundColor Red
        exit 0
    }
}

Write-Host ""
Write-Host "Updating baselines..." -ForegroundColor Cyan
Write-Host "----------------------------------------" -ForegroundColor Gray

# Execute command
Invoke-Expression $command

$exitCode = $LASTEXITCODE

Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray

if ($exitCode -eq 0) {
    Write-Host "✓ Baselines updated successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "1. Review updated snapshots in src/tests/e2e/visual-regression.spec.ts-snapshots/" -ForegroundColor White
    Write-Host "2. Run tests to verify: npx playwright test visual-regression" -ForegroundColor White
    Write-Host "3. Commit changes: git add . && git commit -m 'test: Update visual baselines'" -ForegroundColor White
} else {
    Write-Host "✗ Baseline update failed with exit code: $exitCode" -ForegroundColor Red
    Write-Host ""
    Write-Host "Check the output above for errors." -ForegroundColor Yellow
    exit $exitCode
}
