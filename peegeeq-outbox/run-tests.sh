#!/bin/bash

# PeeGeeQ Outbox Test Execution Helper Script
# Provides convenient commands for running different test categories

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
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

# Function to run tests with timing
run_tests() {
    local profile=$1
    local description=$2
    
    print_status "Running $description..."
    print_status "Profile: $profile"
    
    start_time=$(date +%s)
    
    if mvn test -P"$profile" -q; then
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_success "$description completed in ${duration}s"
    else
        print_error "$description failed"
        exit 1
    fi
}

# Function to show help
show_help() {
    echo "PeeGeeQ Outbox Test Runner"
    echo ""
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  core          Run core tests (default, ~30 seconds)"
    echo "  integration   Run integration tests (~3 minutes)"
    echo "  performance   Run performance tests (~5 minutes)"
    echo "  smoke         Run smoke tests (~10 seconds)"
    echo "  slow          Run slow tests (5+ minutes)"
    echo "  all           Run all tests except flaky (~8 minutes)"
    echo "  help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 core                    # Fast development feedback"
    echo "  $0 integration            # Pre-commit validation"
    echo "  $0 performance            # Performance benchmarks"
    echo "  $0 all                    # Comprehensive testing"
    echo ""
    echo "Direct Maven usage:"
    echo "  mvn test                           # Core tests (default)"
    echo "  mvn test -Pintegration-tests       # Integration tests"
    echo "  mvn test -Pperformance-tests       # Performance tests"
    echo "  mvn test -Pall-tests               # All tests"
}

# Main script logic
case "${1:-core}" in
    "core")
        run_tests "core-tests" "Core Tests (Fast Unit Tests)"
        ;;
    "integration")
        run_tests "integration-tests" "Integration Tests (TestContainers)"
        ;;
    "performance")
        run_tests "performance-tests" "Performance Tests (Benchmarks)"
        ;;
    "smoke")
        run_tests "smoke-tests" "Smoke Tests (Ultra-Fast)"
        ;;
    "slow")
        run_tests "slow-tests" "Slow Tests (Comprehensive)"
        ;;
    "all")
        run_tests "all-tests" "All Tests (Comprehensive)"
        ;;
    "help"|"-h"|"--help")
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
