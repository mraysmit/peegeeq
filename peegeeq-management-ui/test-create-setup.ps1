$body = @{
    setupId = "test-setup"
    databaseConfig = @{
        host = "localhost"
        port = 5432
        databaseName = "peegeeq"
        username = "peegeeq"
        password = "peegeeq"
        schema = "public"
    }
    queues = @(
        @{
            queueName = "test-queue-1"
            batchSize = 10
            pollingInterval = "PT5S"
        }
    )
    eventStores = @()
} | ConvertTo-Json -Depth 5

Write-Host "Request body:" -ForegroundColor Cyan
Write-Host $body

Write-Host "`nSending request..." -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/database-setup/create' `
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

