# PowerShell script to migrate all tests from TestSchemaInitializer to PeeGeeQTestSchemaInitializer

$files = @(
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\DistributedTracingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\JsonbConversionValidationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxBasicTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxCompletableFutureExceptionTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConfigurationIntegrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerCoreTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerCoverageTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerCrashRecoveryTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerEdgeCasesCoverageTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerErrorHandlingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerErrorPathsCoverageTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerFailureHandlingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerGroupIntegrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerGroupTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerNullHandlerTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerSurgicalCoverageTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxDeadLetterQueueTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxDirectExceptionHandlingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxEdgeCasesTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxErrorHandlingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxExceptionHandlingDemonstrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxIdempotencyKeyTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxMetricsTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxParallelProcessingTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxPerformanceTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxProducerAdditionalCoverageTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxProducerCoreTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxProducerIntegrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxProducerTransactionTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxResourceLeakDetectionTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxRetryConcurrencyTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxRetryLogicTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\StuckMessageRecoveryIntegrationTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\BasicReactiveOperationsExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\ConsumerGroupExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\EnhancedErrorHandlingExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\ErrorHandlingRollbackExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\IntegrationPatternsExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\MessagePriorityExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\RetryAndFailureHandlingExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\SystemPropertiesConfigurationExampleTest.java",
    "peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\TransactionParticipationAdvancedExampleTest.java"
)

$count = 0
foreach ($file in $files) {
    if (Test-Path $file) {
        Write-Host "Processing: $file"
        $content = Get-Content $file -Raw
        
        # Replace the method call
        $content = $content -replace 'TestSchemaInitializer\.initializeSchema\(postgres\)', 'PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL)'
        
        # Replace the import (if it exists)
        $content = $content -replace 'import dev\.mars\.peegeeq\.outbox\.TestSchemaInitializer;', 'import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;'
        $content = $content -replace 'import dev\.mars\.peegeeq\.outbox\.examples\.TestSchemaInitializer;', 'import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;'
        
        # Add imports if not present and if we're using PeeGeeQTestSchemaInitializer
        if ($content -match 'PeeGeeQTestSchemaInitializer') {
            # Add the main import if not present
            if ($content -notmatch 'import dev\.mars\.peegeeq\.test\.schema\.PeeGeeQTestSchemaInitializer;') {
                # Find the package declaration and add after it
                $content = $content -replace '(package [^;]+;)', "`$1`n`nimport dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;"
            }

            # Add SchemaComponent static import if not present
            if ($content -notmatch 'import static dev\.mars\.peegeeq\.test\.schema\.PeeGeeQTestSchemaInitializer\.SchemaComponent') {
                # Find the last import statement
                if ($content -match '(?s)(.*)(import [^;]+;)(.*)') {
                    $beforeLastImport = $matches[1] + $matches[2]
                    $afterLastImport = $matches[3]

                    # Add the static import after the last import
                    $content = $beforeLastImport + "`nimport static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;" + $afterLastImport
                }
            }
        }
        
        Set-Content -Path $file -Value $content -NoNewline
        $count++
        Write-Host "  ✓ Updated"
    } else {
        Write-Host "  ✗ File not found: $file"
    }
}

Write-Host "`n✅ Migration complete! Updated $count files."
Write-Host "`nNext steps:"
Write-Host "1. Delete peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\TestSchemaInitializer.java"
Write-Host "2. Delete peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\examples\TestSchemaInitializer.java"
Write-Host "3. Run tests to verify"

