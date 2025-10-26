#!/bin/bash

# Test execution helper script for peegeeq-api
# Provides convenient commands for running different API test categories

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_performance() {
    echo -e "${PURPLE}🚀 $1${NC}"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [CATEGORY]"
    echo ""
    echo "API Test Categories:"
    echo "  smoke         Run smoke tests (~1 second)"
    echo "  core          Run core tests (~2 seconds)"
    echo "  integration   Run integration tests (~1-3 minutes)"
    echo "  performance   Run performance tests (~2-5 minutes)"
    echo "  slow          Run slow tests (~5+ minutes)"
    echo "  all           Run all tests except flaky (~5 minutes)"
    echo "  help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 smoke         # Ultra-fast validation"
    echo "  $0 core          # Daily development"
    echo "  $0 integration   # Infrastructure testing"
    echo "  $0 performance   # API benchmarks"
    echo "  $0 all           # Comprehensive testing"
    echo ""
    echo "Direct Maven commands:"
    echo "  mvn test                           # Core tests (default)"
    echo "  mvn test -Psmoke-tests            # Smoke tests"
    echo "  mvn test -Pintegration-tests      # Integration tests"
    echo "  mvn test -Pperformance-tests      # Performance tests"
    echo "  mvn test -Pall-tests              # All tests"
    echo ""
    echo "Current Test Files:"
    echo "  SMOKE: MessageTest.java (placeholder test)"
    echo "  CORE:  MessageFilterDebugTest.java (filter logic)"
}

# Function to run tests with timing and performance info
run_tests() {
    local profile=$1
    local description=$2
    local expected_duration=$3
    
    print_info "Running $description..."
    print_info "Profile: $profile"
    print_info "Expected duration: $expected_duration"
    
    start_time=$(date +%s)
    
    if mvn -q test -P"$profile"; then
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_success "$description completed in ${duration}s"
        
        # Performance feedback
        if [[ $duration -lt 2 ]]; then
            print_performance "Excellent performance! ⚡"
        elif [[ $duration -lt 10 ]]; then
            print_performance "Good performance! 🚀"
        elif [[ $duration -lt 60 ]]; then
            print_performance "Standard performance 📊"
        else
            print_warning "Long execution time - consider optimization"
        fi
    else
        print_error "$description failed"
        exit 1
    fi
}

# Main script logic
case "${1:-core}" in
    "smoke")
        run_tests "smoke-tests" "Smoke tests (ultra-fast basic verification)" "~1 second"
        ;;
    "core")
        run_tests "core-tests" "Core tests (fast unit tests for daily development)" "~2 seconds"
        ;;
    "integration")
        run_tests "integration-tests" "Integration tests (TestContainers + real infrastructure)" "~1-3 minutes"
        ;;
    "performance")
        run_tests "performance-tests" "Performance tests (API benchmarks and throughput)" "~2-5 minutes"
        ;;
    "slow")
        run_tests "slow-tests" "Slow tests (comprehensive validation)" "~5+ minutes"
        ;;
    "all")
        run_tests "all-tests" "All tests (comprehensive testing)" "~5 minutes"
        ;;
    "help"|"-h"|"--help")
        show_usage
        ;;
    *)
        print_error "Unknown test category: $1"
        echo ""
        show_usage
        exit 1
        ;;
esac

print_success "API test execution completed successfully!"
print_info "💡 Tip: Use 'mvn test' for daily development (core tests)"
