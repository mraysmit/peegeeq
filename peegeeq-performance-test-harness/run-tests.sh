#!/bin/bash

# Test execution helper script for peegeeq-performance-test-harness
# Provides convenient commands for running different performance test categories

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
    echo "Performance Test Categories:"
    echo "  smoke         Run smoke tests (~5 seconds)"
    echo "  performance   Run performance tests (~3-5 minutes)"
    echo "  slow          Run slow tests (~15+ minutes)"
    echo "  integration   Run integration tests (~1-3 minutes)"
    echo "  all           Run all tests except flaky (~20+ minutes)"
    echo "  help          Show this help message"
    echo ""
    echo "Legacy Performance Profiles:"
    echo "  legacy        Run legacy performance profile (~5 minutes)"
    echo "  load-test     Run load testing profile (~30 minutes)"
    echo "  stress-test   Run stress testing profile (~60 minutes)"
    echo ""
    echo "Examples:"
    echo "  $0 smoke         # Quick validation"
    echo "  $0 performance   # Standard benchmarks"
    echo "  $0 slow          # Comprehensive stress testing"
    echo "  $0 all           # Full test suite"
    echo ""
    echo "Direct Maven commands:"
    echo "  mvn test                           # Smoke tests (default)"
    echo "  mvn test -Pperformance-tests       # Performance tests"
    echo "  mvn test -Pslow-tests             # Slow tests"
    echo "  mvn test -Pall-tests              # All tests"
    echo ""
    echo "Performance Configuration:"
    echo "  System property 'peegeeq.performance.tests=true' is automatically set"
    echo "  Duration and thread counts are configured per profile"
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
        if [[ $duration -lt 10 ]]; then
            print_performance "Excellent performance! ⚡"
        elif [[ $duration -lt 60 ]]; then
            print_performance "Good performance! 🚀"
        elif [[ $duration -lt 300 ]]; then
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
case "${1:-smoke}" in
    "smoke")
        run_tests "smoke-tests" "Smoke tests (ultra-fast basic verification)" "~5 seconds"
        ;;
    "performance")
        run_tests "performance-tests" "Performance tests (standard benchmarks)" "~3-5 minutes"
        ;;
    "slow")
        run_tests "slow-tests" "Slow tests (comprehensive stress testing)" "~15+ minutes"
        ;;
    "integration")
        run_tests "integration-tests" "Integration tests (TestContainers + real infrastructure)" "~1-3 minutes"
        ;;
    "all")
        run_tests "all-tests" "All tests (comprehensive testing)" "~20+ minutes"
        ;;
    "legacy")
        run_tests "performance" "Legacy performance profile" "~5 minutes"
        ;;
    "load-test")
        run_tests "load-test" "Load testing profile (extended load)" "~30 minutes"
        ;;
    "stress-test")
        run_tests "stress-test" "Stress testing profile (maximum load)" "~60 minutes"
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

print_success "Performance test execution completed successfully!"
print_info "💡 Tip: Use 'mvn test -Pperformance-tests' for direct Maven execution"
