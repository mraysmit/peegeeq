# Simple PostgreSQL Version Migration Script for PeeGeeQ Project
# This script migrates hardcoded PostgreSQL versions to use the centralized constant

Write-Host "PostgreSQL Version Migration Script" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan

# Files that need to be updated based on the scan
$filesToUpdate = @(
    "peegeeq-bitemporal\src\test\java\dev\mars\peegeeq\bitemporal\PgBiTemporalEventStoreTest.java",
    "peegeeq-db\src\test\java\dev\mars\peegeeq\db\config\SystemPropertiesValidationTest.java",
    "peegeeq-db\src\test\java\dev\mars\peegeeq\db\connection\PgListenerConnectionTest.java",
    "peegeeq-examples\src\main\java\dev\mars\peegeeq\examples\ConsumerGroupExample.java",
    "peegeeq-examples\src\main\java\dev\mars\peegeeq\examples\MessagePriorityExample.java",
    "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\RestApiExampleTest.java",
    "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\SpringBootTransactionalRollbackTest.java",
    "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\TransactionalBiTemporalExampleTest.java",
    "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\TransactionalOutboxTest.java",
    "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\TransactionalRollbackDemoTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxCompletableFutureExceptionTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConfigurationIntegrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxDeadLetterQueueTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxDirectExceptionHandlingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxEdgeCasesTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxExceptionHandlingDemonstrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\ReactiveOutboxProducerTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxRetryLogicTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\RetryDebugTest.java"
)

$importToAdd = "import dev.mars.peegeeq.test.PostgreSQLTestConstants;"
$constantUsage = "PostgreSQLTestConstants.POSTGRES_IMAGE"

$updatedCount = 0
$errorCount = 0

foreach ($filePath in $filesToUpdate) {
    if (Test-Path $filePath) {
        Write-Host "Updating: $filePath" -ForegroundColor Yellow
        
        try {
            $content = Get-Content $filePath -Raw
            $lines = Get-Content $filePath
            
            # Check if import already exists
            if ($content -notmatch [regex]::Escape($importToAdd)) {
                # Find the last import line
                $lastImportIndex = -1
                for ($i = 0; $i -lt $lines.Count; $i++) {
                    if ($lines[$i] -match "^import\s+") {
                        $lastImportIndex = $i
                    }
                }
                
                if ($lastImportIndex -ge 0) {
                    # Insert the import after the last import
                    $newLines = @()
                    $newLines += $lines[0..$lastImportIndex]
                    $newLines += $importToAdd
                    $newLines += $lines[($lastImportIndex + 1)..($lines.Count - 1)]
                    $lines = $newLines
                    Write-Host "  Added import statement" -ForegroundColor Green
                }
            }
            
            # Replace hardcoded PostgreSQL versions
            $updated = $false
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match 'new PostgreSQLContainer<>\("postgres:[^"]*"\)') {
                    $oldLine = $lines[$i]
                    $lines[$i] = $lines[$i] -replace 'new PostgreSQLContainer<>\("postgres:[^"]*"\)', "new PostgreSQLContainer<>($constantUsage)"
                    Write-Host "  Replaced: $($oldLine.Trim())" -ForegroundColor Red
                    Write-Host "  With:     $($lines[$i].Trim())" -ForegroundColor Green
                    $updated = $true
                }
            }
            
            if ($updated) {
                # Write back to file
                Set-Content -Path $filePath -Value $lines
                $updatedCount++
                Write-Host "  File updated successfully" -ForegroundColor Green
            } else {
                Write-Host "  No changes needed" -ForegroundColor Gray
            }
            
        } catch {
            Write-Host "  Error updating file: $($_.Exception.Message)" -ForegroundColor Red
            $errorCount++
        }
        
        Write-Host ""
    } else {
        Write-Host "File not found: $filePath" -ForegroundColor Red
        $errorCount++
    }
}

Write-Host "Migration Summary:" -ForegroundColor Cyan
Write-Host "  Files updated: $updatedCount" -ForegroundColor Green
Write-Host "  Errors: $errorCount" -ForegroundColor Red

if ($errorCount -eq 0) {
    Write-Host ""
    Write-Host "Migration completed successfully!" -ForegroundColor Green
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "  1. Run 'mvn compile' to verify changes"
    Write-Host "  2. Run 'mvn test' to ensure tests pass"
    Write-Host "  3. All files now use PostgreSQLTestConstants.POSTGRES_IMAGE"
} else {
    Write-Host ""
    Write-Host "Migration completed with errors. Please review the failed files." -ForegroundColor Yellow
}
