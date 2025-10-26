#!/bin/bash

# Test execution helper script for peegeeq-service-manager
# Provides convenient commands for running different test categories

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

# Function to show usage
show_usage() {
    echo "Usage: $0 [CATEGORY]"
    echo ""
    echo "Test Categories:"
    echo "  core          Run core tests (~5 seconds)"
    echo "  integration   Run integration tests (~1-2 minutes)"
    echo "  performance   Run performance tests (~3-5 minutes)"
    echo "  smoke         Run smoke tests (~10 seconds)"
    echo "  slow          Run slow tests (~15+ minutes)"
    echo "  all           Run all tests except flaky (~5-10 minutes)"
    echo "  help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 core          # Fast development feedback"
    echo "  $0 integration   # Pre-commit validation"
    echo "  $0 performance   # Performance benchmarks"
    echo "  $0 all           # Comprehensive testing"
    echo ""
    echo "Direct Maven commands:"
    echo "  mvn test                           # Core tests (default)"
    echo "  mvn test -Pintegration-tests       # Integration tests"
    echo "  mvn test -Pperformance-tests       # Performance tests"
    echo "  mvn test -Pall-tests              # All tests"
}

# Function to run tests with timing
run_tests() {
    local profile=$1
    local description=$2
    
    print_info "Running $description..."
    print_info "Profile: $profile"
    
    start_time=$(date +%s)
    
    if mvn -q test -P"$profile"; then
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_success "$description completed in ${duration}s"
    else
        print_error "$description failed"
        exit 1
    fi
}

# Main script logic
case "${1:-core}" in
    "core")
        run_tests "core-tests" "Core tests (fast unit tests)"
        ;;
    "integration")
        run_tests "integration-tests" "Integration tests (TestContainers + real infrastructure)"
        ;;
    "performance")
        run_tests "performance-tests" "Performance tests (load and throughput)"
        ;;
    "smoke")
        run_tests "smoke-tests" "Smoke tests (ultra-fast basic verification)"
        ;;
    "slow")
        run_tests "slow-tests" "Slow tests (comprehensive long-running tests)"
        ;;
    "all")
        run_tests "all-tests" "All tests (comprehensive testing)"
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

print_success "Test execution completed successfully!"
