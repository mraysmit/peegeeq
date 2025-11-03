#!/bin/bash

# Test execution helper script for peegeeq-test-support
# Provides convenient commands for running different test categories

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 <test-category>"
    echo ""
    echo "Available test categories:"
    echo "  core         - Fast unit tests for daily development (~10 seconds)"
    echo "  integration  - Tests with TestContainers and real infrastructure (~2-3 minutes)"
    echo "  performance  - Hardware profiling and performance tests (~5 minutes)"
    echo "  slow         - Long-running comprehensive tests (~15+ minutes)"
    echo "  smoke        - Ultra-fast basic verification (~5 seconds)"
    echo "  all          - All tests except flaky ones (~10 minutes)"
    echo ""
    echo "Examples:"
    echo "  $0 core         # Run core tests (default when running 'mvn test')"
    echo "  $0 integration  # Run integration tests"
    echo "  $0 performance  # Run performance tests"
    echo "  $0 all          # Run all tests"
}

# Function to run tests with timing
run_tests() {
    local profile=$1
    local description=$2
    
    print_info "Starting $description..."
    print_info "Profile: $profile"
    
    start_time=$(date +%s)
    
    if mvn -q test -P"$profile"; then
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_success "$description completed successfully in ${duration}s"
    else
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_error "$description failed after ${duration}s"
        exit 1
    fi
}

# Main script logic
case "${1:-}" in
    "core")
        run_tests "core-tests" "Core tests (fast unit tests)"
        ;;
    "integration")
        run_tests "integration-tests" "Integration tests (TestContainers + real infrastructure)"
        ;;
    "performance")
        run_tests "performance-tests" "Performance tests (hardware profiling + benchmarks)"
        ;;
    "slow")
        run_tests "slow-tests" "Slow tests (comprehensive long-running tests)"
        ;;
    "smoke")
        run_tests "smoke-tests" "Smoke tests (ultra-fast basic verification)"
        ;;
    "all")
        run_tests "all-tests" "All tests (comprehensive test suite)"
        ;;
    "help"|"-h"|"--help")
        show_usage
        ;;
    "")
        print_warning "No test category specified. Running core tests (default)."
        run_tests "core-tests" "Core tests (fast unit tests)"
        ;;
    *)
        print_error "Unknown test category: $1"
        echo ""
        show_usage
        exit 1
        ;;
esac

print_success "Test execution completed!"
