$body = @{
    setup = "test-setup"
    name = "test-queue-1"
    type = "native"
    batchSize = 10
    pollingInterval = "PT5S"
} | ConvertTo-Json

Write-Host "Request body:" -ForegroundColor Cyan
Write-Host $body

Write-Host "`nSending request..." -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/management/queues' `
        -Method Post `
        -ContentType 'application/json' `
        -Body $body
    
    Write-Host "`n✅ Success!" -ForegroundColor Green
    Write-Host ($response | ConvertTo-Json -Depth 5)
} catch {
    Write-Host "`n❌ Error!" -ForegroundColor Red
    Write-Host "Status Code:" $_.Exception.Response.StatusCode.value__
    Write-Host "Message:" $_.Exception.Message
    if ($_.ErrorDetails.Message) {
        Write-Host "Details:" $_.ErrorDetails.Message
    }
}

