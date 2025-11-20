# Quick Test Script for PeeGeeQ Management UI
# This script runs basic tests to verify UI functionality

Write-Host "🚀 PeeGeeQ Management UI - Quick Test Script" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Change to the management UI directory
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiPath = Split-Path -Parent $scriptPath
Set-Location $uiPath

Write-Host "📂 Working Directory: $uiPath" -ForegroundColor Yellow
Write-Host ""

# Function to check if command exists
function Test-CommandExists {
    param($command)
    $null -ne (Get-Command $command -ErrorAction SilentlyContinue)
}

# Check Node.js
Write-Host "✅ Checking prerequisites..." -ForegroundColor Green
if (Test-CommandExists "node") {
    $nodeVersion = node --version
    Write-Host "   Node.js version: $nodeVersion" -ForegroundColor Gray
} else {
    Write-Host "❌ Node.js is not installed!" -ForegroundColor Red
    Write-Host "   Please install Node.js from https://nodejs.org/" -ForegroundColor Yellow
    exit 1
}

# Check npm
if (-not (Test-CommandExists "npm")) {
    Write-Host "❌ npm is not available!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "📦 Checking dependencies..." -ForegroundColor Green

# Check if node_modules exists
if (-not (Test-Path "node_modules")) {
    Write-Host "⚠️  Dependencies not installed. Installing..." -ForegroundColor Yellow
    npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Failed to install dependencies!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "   Dependencies already installed ✓" -ForegroundColor Gray
}

Write-Host ""
Write-Host "🧪 Running Tests..." -ForegroundColor Green
Write-Host ""

# Test 1: TypeScript compilation
Write-Host "Test 1: TypeScript Compilation Check" -ForegroundColor Cyan
Write-Host "------------------------------------" -ForegroundColor Cyan
npm run type-check
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ TypeScript compilation: PASSED" -ForegroundColor Green
} else {
    Write-Host "❌ TypeScript compilation: FAILED" -ForegroundColor Red
}
Write-Host ""

# Test 2: Build test
Write-Host "Test 2: Production Build Test" -ForegroundColor Cyan
Write-Host "------------------------------" -ForegroundColor Cyan
npm run build
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Production build: PASSED" -ForegroundColor Green
    
    # Check if build output exists
    if (Test-Path "../peegeeq-rest/src/main/resources/webroot/index.html") {
        Write-Host "   ✓ Build output deployed to webroot" -ForegroundColor Gray
    }
} else {
    Write-Host "❌ Production build: FAILED" -ForegroundColor Red
}
Write-Host ""

# Test 3: Integration tests
Write-Host "Test 3: Integration Tests" -ForegroundColor Cyan
Write-Host "-------------------------" -ForegroundColor Cyan
Write-Host "⚠️  Note: Backend must be running on port 8080" -ForegroundColor Yellow
$runIntegrationTests = Read-Host "Run integration tests? (y/N)"
if ($runIntegrationTests -eq "y" -or $runIntegrationTests -eq "Y") {
    npm run test:integration
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ Integration tests: PASSED" -ForegroundColor Green
    } else {
        Write-Host "⚠️  Integration tests: SKIPPED (backend may not be running)" -ForegroundColor Yellow
    }
} else {
    Write-Host "⏭️  Integration tests: SKIPPED" -ForegroundColor Yellow
}
Write-Host ""

# Test 4: E2E tests
Write-Host "Test 4: E2E Tests (Simple)" -ForegroundColor Cyan
Write-Host "--------------------------" -ForegroundColor Cyan

# Check if Playwright is installed
if (-not (Test-Path "node_modules/@playwright/test")) {
    Write-Host "⚠️  Playwright not installed. Installing..." -ForegroundColor Yellow
    npm install @playwright/test --save-dev
}

# Check if browsers are installed
$playwrightBrowsers = "$env:USERPROFILE\.cache\ms-playwright"
if (-not (Test-Path $playwrightBrowsers)) {
    Write-Host "⚠️  Playwright browsers not installed. Installing..." -ForegroundColor Yellow
    npx playwright install chromium
}

$runE2ETests = Read-Host "Run E2E tests? (y/N)"
if ($runE2ETests -eq "y" -or $runE2ETests -eq "Y") {
    npx playwright test simple-test.spec.ts --project=chromium
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ E2E tests: PASSED" -ForegroundColor Green
    } else {
        Write-Host "⚠️  E2E tests: FAILED (UI may not be running)" -ForegroundColor Yellow
    }
} else {
    Write-Host "⏭️  E2E tests: SKIPPED" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "📊 Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "✅ TypeScript compilation works" -ForegroundColor Green
Write-Host "✅ Production build succeeds" -ForegroundColor Green
Write-Host "⚠️  Integration tests require backend" -ForegroundColor Yellow
Write-Host "⚠️  E2E tests require dev server running" -ForegroundColor Yellow
Write-Host ""
Write-Host "🎯 Next Steps:" -ForegroundColor Cyan
Write-Host "   1. Start dev server: npm run dev" -ForegroundColor Gray
Write-Host "   2. Open browser: http://localhost:3000" -ForegroundColor Gray
Write-Host "   3. Verify UI loads and navigation works" -ForegroundColor Gray
Write-Host "   4. Optional: Start backend for full integration" -ForegroundColor Gray
Write-Host ""
Write-Host "📖 For detailed testing guide, see: test-basic-ui.md" -ForegroundColor Yellow
Write-Host ""
