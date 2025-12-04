# Start PeeGeeQ Backend and Run E2E Tests
# This script starts the backend server and waits for it to be ready before running tests

Write-Host "Starting PeeGeeQ REST Server..." -ForegroundColor Green
Start-Process pwsh -ArgumentList "-NoExit", "-Command", "cd '$PSScriptRoot\peegeeq-rest'; mvn compile exec:java '`-Dexec.mainClass=dev.mars.peegeeq.rest.StartRestServer'"

Write-Host "Waiting for backend to start (30 seconds)..." -ForegroundColor Yellow
Start-Sleep -Seconds 30

Write-Host "Running E2E Tests..." -ForegroundColor Green
Set-Location "$PSScriptRoot\peegeeq-management-ui"
npm run test:e2e

Write-Host ""
Write-Host "Tests complete. Backend server is still running in separate window." -ForegroundColor Cyan
Write-Host "Close the backend PowerShell window to stop the server." -ForegroundColor Cyan
