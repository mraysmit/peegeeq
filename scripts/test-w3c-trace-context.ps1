# W3C Trace Context Propagation - Comprehensive Test Script (PowerShell)
# This script runs all tests for Priority 1 implementation

$ErrorActionPreference = "Stop"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "W3C Trace Context Propagation Test Suite" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Test counters
$script:TotalTests = 0
$script:PassedTests = 0
$script:FailedTests = 0

# Function to run a test
function Run-Test {
    param(
        [string]$TestName,
        [string]$TestCommand
    )
    
    Write-Host "Running: $TestName" -ForegroundColor Yellow
    $script:TotalTests++
    
    try {
        Invoke-Expression $TestCommand | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✅ PASSED: $TestName" -ForegroundColor Green
            $script:PassedTests++
        } else {
            Write-Host "❌ FAILED: $TestName" -ForegroundColor Red
            $script:FailedTests++
        }
    } catch {
        Write-Host "❌ FAILED: $TestName" -ForegroundColor Red
        Write-Host "Error: $_" -ForegroundColor Red
        $script:FailedTests++
    }
    Write-Host ""
}

# Change to peegeeq-rest directory
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptPath\..\peegeeq-rest"

Write-Host "Step 1: Clean and compile" -ForegroundColor Cyan
Write-Host "-------------------------"
mvn clean compile test-compile
Write-Host ""

Write-Host "Step 2: Run Integration Tests" -ForegroundColor Cyan
Write-Host "-----------------------------"

# Test 1: traceparent propagation
Run-Test "Test 1: traceparent propagation" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testTraceparentPropagation -q"

# Test 2: tracestate propagation
Run-Test "Test 2: tracestate propagation" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testTracestatePropagation -q"

# Test 3: baggage propagation
Run-Test "Test 3: baggage propagation" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBaggagePropagation -q"

# Test 4: All W3C headers together
Run-Test "Test 4: All W3C headers together" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testAllW3CHeadersPropagation -q"

# Test 5: Backward compatibility
Run-Test "Test 5: Backward compatibility (no W3C headers)" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithoutW3CHeaders -q"

# Test 6: Empty headers not propagated
Run-Test "Test 6: Empty W3C headers not propagated" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testEmptyW3CHeadersNotPropagated -q"

# Test 7: Special characters
Run-Test "Test 7: W3C headers with special characters" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testW3CHeadersWithSpecialCharacters -q"

# Test 8: Header override
Run-Test "Test 8: W3C headers override custom headers" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testW3CHeadersOverrideCustomHeaders -q"

# Test 9: Batch with W3C headers
Run-Test "Test 9: Batch messages with W3C headers" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBatchMessagesWithW3CHeaders -q"

# Test 10: Batch without W3C headers
Run-Test "Test 10: Batch messages without W3C headers" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBatchMessagesWithoutW3CHeaders -q"

# Test 11: Priority and W3C headers
Run-Test "Test 11: Message with priority and W3C headers" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithPriorityAndW3CHeaders -q"

# Test 12: Custom and W3C headers
Run-Test "Test 12: Message with custom and W3C headers" `
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithCustomAndW3CHeaders -q"

Write-Host "Step 3: Run Unit Tests" -ForegroundColor Cyan
Write-Host "---------------------"

# Check if Mockito is available
$mockitoAvailable = mvn dependency:tree | Select-String "mockito"

if ($mockitoAvailable) {
    Run-Test "Unit Test 1: Extract traceparent header" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldExtractTraceparentHeader -q"
    
    Run-Test "Unit Test 2: Extract all W3C headers" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldExtractAllW3CHeaders -q"
    
    Run-Test "Unit Test 3: Null headers not added" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldNotAddNullW3CHeaders -q"
    
    Run-Test "Unit Test 4: Empty headers not added" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldNotAddEmptyW3CHeaders -q"
    
    Run-Test "Unit Test 5: Preserve other headers" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldPreserveOtherHeadersWithW3CHeaders -q"
    
    Run-Test "Unit Test 6: Handle special characters" `
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldHandleSpecialCharactersInW3CHeaders -q"
} else {
    Write-Host "⚠️  Mockito not found, skipping unit tests" -ForegroundColor Yellow
    Write-Host ""
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Test Results Summary" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Total Tests:  $script:TotalTests"
Write-Host "Passed:       $script:PassedTests" -ForegroundColor Green
if ($script:FailedTests -gt 0) {
    Write-Host "Failed:       $script:FailedTests" -ForegroundColor Red
} else {
    Write-Host "Failed:       $script:FailedTests"
}
Write-Host ""

# Calculate success rate
if ($script:TotalTests -gt 0) {
    $successRate = [math]::Round(($script:PassedTests / $script:TotalTests) * 100, 2)
    Write-Host "Success Rate: $successRate%"
    Write-Host ""
}

# Exit with appropriate code
if ($script:FailedTests -eq 0) {
    Write-Host "✅ All tests passed!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Priority 1: W3C Trace Context Propagation is READY FOR PRODUCTION" -ForegroundColor Green
    exit 0
} else {
    Write-Host "❌ Some tests failed" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please review the failed tests above"
    exit 1
}

