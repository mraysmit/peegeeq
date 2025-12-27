#!/usr/bin/env pwsh

Write-Host "Cleaning up E2E test setup..." -ForegroundColor Cyan
Write-Host ""

$API_BASE = "http://localhost:8080"
$SETUP_ID = "e2e-test-setup"

# Check if setup exists
Write-Host "Checking if setup exists..." -ForegroundColor Yellow

try {
    $setups = Invoke-RestMethod -Uri "$API_BASE/api/v1/setups" -Method Get
    
    if ($setups.setupIds -contains $SETUP_ID) {
        Write-Host "✓ Setup '$SETUP_ID' found" -ForegroundColor Green
        Write-Host ""
        
        # Delete the setup
        Write-Host "Deleting setup '$SETUP_ID'..." -ForegroundColor Yellow
        
        try {
            Invoke-RestMethod -Uri "$API_BASE/api/v1/database-setup/$SETUP_ID" `
                -Method Delete `
                -TimeoutSec 60
            
            Write-Host ""
            Write-Host "✅ Setup deleted successfully!" -ForegroundColor Green
            Write-Host ""
            Write-Host "The following were removed:" -ForegroundColor Cyan
            Write-Host "- Setup: $SETUP_ID" -ForegroundColor White
            Write-Host "- Database: peegeeq_e2e_test_*" -ForegroundColor White
            Write-Host "- All queues in the setup" -ForegroundColor White
            Write-Host "- All event stores in the setup" -ForegroundColor White
            Write-Host ""
            
        } catch {
            Write-Host ""
            Write-Host "❌ Failed to delete setup!" -ForegroundColor Red
            Write-Host "Error:" $_.Exception.Message -ForegroundColor Red
            
            if ($_.ErrorDetails.Message) {
                Write-Host "Details:" $_.ErrorDetails.Message -ForegroundColor White
            }
            
            exit 1
        }
        
    } else {
        Write-Host "ℹ Setup '$SETUP_ID' not found - nothing to clean up" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Available setups:" -ForegroundColor Cyan
        Write-Host ($setups | ConvertTo-Json -Depth 3)
    }
    
} catch {
    Write-Host ""
    Write-Host "❌ Failed to check setups!" -ForegroundColor Red
    Write-Host "Error:" $_.Exception.Message -ForegroundColor Red
    
    if ($_.ErrorDetails.Message) {
        Write-Host "Details:" $_.ErrorDetails.Message -ForegroundColor White
    }
    
    Write-Host ""
    Write-Host "Is the backend running on port 8080?" -ForegroundColor Yellow
    
    exit 1
}

Write-Host "Cleanup complete!" -ForegroundColor Green

