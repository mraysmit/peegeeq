#!/bin/bash

# W3C Trace Context Propagation - Comprehensive Test Script
# This script runs all tests for Priority 1 implementation

set -e  # Exit on error

echo "=========================================="
echo "W3C Trace Context Propagation Test Suite"
echo "=========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Function to run a test
run_test() {
    local test_name=$1
    local test_command=$2
    
    echo -e "${YELLOW}Running: $test_name${NC}"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if eval "$test_command"; then
        echo -e "${GREEN}✅ PASSED: $test_name${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}❌ FAILED: $test_name${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo ""
}

# Change to peegeeq-rest directory
cd "$(dirname "$0")/../peegeeq-rest" || exit 1

echo "Step 1: Clean and compile"
echo "-------------------------"
mvn clean compile test-compile
echo ""

echo "Step 2: Run Integration Tests"
echo "-----------------------------"

# Test 1: traceparent propagation
run_test "Test 1: traceparent propagation" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testTraceparentPropagation -q"

# Test 2: tracestate propagation
run_test "Test 2: tracestate propagation" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testTracestatePropagation -q"

# Test 3: baggage propagation
run_test "Test 3: baggage propagation" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBaggagePropagation -q"

# Test 4: All W3C headers together
run_test "Test 4: All W3C headers together" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testAllW3CHeadersPropagation -q"

# Test 5: Backward compatibility
run_test "Test 5: Backward compatibility (no W3C headers)" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithoutW3CHeaders -q"

# Test 6: Empty headers not propagated
run_test "Test 6: Empty W3C headers not propagated" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testEmptyW3CHeadersNotPropagated -q"

# Test 7: Special characters
run_test "Test 7: W3C headers with special characters" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testW3CHeadersWithSpecialCharacters -q"

# Test 8: Header override
run_test "Test 8: W3C headers override custom headers" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testW3CHeadersOverrideCustomHeaders -q"

# Test 9: Batch with W3C headers
run_test "Test 9: Batch messages with W3C headers" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBatchMessagesWithW3CHeaders -q"

# Test 10: Batch without W3C headers
run_test "Test 10: Batch messages without W3C headers" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testBatchMessagesWithoutW3CHeaders -q"

# Test 11: Priority and W3C headers
run_test "Test 11: Message with priority and W3C headers" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithPriorityAndW3CHeaders -q"

# Test 12: Custom and W3C headers
run_test "Test 12: Message with custom and W3C headers" \
    "mvn test -Dtest=W3CTraceContextPropagationTest#testMessageWithCustomAndW3CHeaders -q"

echo "Step 3: Run Unit Tests"
echo "---------------------"

# Unit tests (if Mockito is available)
if mvn dependency:tree | grep -q "mockito"; then
    run_test "Unit Test 1: Extract traceparent header" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldExtractTraceparentHeader -q"
    
    run_test "Unit Test 2: Extract all W3C headers" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldExtractAllW3CHeaders -q"
    
    run_test "Unit Test 3: Null headers not added" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldNotAddNullW3CHeaders -q"
    
    run_test "Unit Test 4: Empty headers not added" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldNotAddEmptyW3CHeaders -q"
    
    run_test "Unit Test 5: Preserve other headers" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldPreserveOtherHeadersWithW3CHeaders -q"
    
    run_test "Unit Test 6: Handle special characters" \
        "mvn test -Dtest=QueueHandlerW3CHeadersUnitTest#shouldHandleSpecialCharactersInW3CHeaders -q"
else
    echo -e "${YELLOW}⚠️  Mockito not found, skipping unit tests${NC}"
    echo ""
fi

echo "=========================================="
echo "Test Results Summary"
echo "=========================================="
echo ""
echo "Total Tests:  $TOTAL_TESTS"
echo -e "${GREEN}Passed:       $PASSED_TESTS${NC}"
if [ $FAILED_TESTS -gt 0 ]; then
    echo -e "${RED}Failed:       $FAILED_TESTS${NC}"
else
    echo "Failed:       $FAILED_TESTS"
fi
echo ""

# Calculate success rate
if [ $TOTAL_TESTS -gt 0 ]; then
    SUCCESS_RATE=$((PASSED_TESTS * 100 / TOTAL_TESTS))
    echo "Success Rate: $SUCCESS_RATE%"
    echo ""
fi

# Exit with appropriate code
if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}✅ All tests passed!${NC}"
    echo ""
    echo "Priority 1: W3C Trace Context Propagation is READY FOR PRODUCTION"
    exit 0
else
    echo -e "${RED}❌ Some tests failed${NC}"
    echo ""
    echo "Please review the failed tests above"
    exit 1
fi

