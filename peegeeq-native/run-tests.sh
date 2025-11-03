#!/bin/bash

# PeeGeeQ Native Test Runner
# Convenient script for running different test categories

set -e

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
    echo "PeeGeeQ Native Test Runner"
    echo ""
    echo "Usage: $0 [CATEGORY]"
    echo ""
    echo "Categories:"
    echo "  core         - Fast unit tests (~30 seconds) [DEFAULT]"
    echo "  integration  - Integration tests with PostgreSQL (~1-3 minutes)"
    echo "  performance  - Performance and load tests (~2-5 minutes)"
    echo "  smoke        - Ultra-fast smoke tests (~10 seconds)"
    echo "  slow         - Long-running tests (~5+ minutes)"
    echo "  all          - All tests except flaky (~5-10 minutes)"
    echo "  full         - Everything including flaky tests (~10+ minutes)"
    echo ""
    echo "Development Workflows:"
    echo "  dev          - Run core → integration (recommended for development)"
    echo "  ci           - Run core → integration → all (CI pipeline simulation)"
    echo ""
    echo "Examples:"
    echo "  $0                    # Run core tests (default)"
    echo "  $0 core              # Run core tests"
    echo "  $0 integration       # Run integration tests"
    echo "  $0 dev               # Development workflow"
    echo "  $0 ci                # CI workflow"
}

# Function to run tests with timing
run_tests() {
    local profile=$1
    local description=$2
    
    print_info "Running $description..."
    start_time=$(date +%s)
    
    if mvn -q test -P"$profile"; then
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_success "$description completed in ${duration}s"
        return 0
    else
        end_time=$(date +%s)
        duration=$((end_time - start_time))
        print_error "$description failed after ${duration}s"
        return 1
    fi
}

# Function to run development workflow
run_dev_workflow() {
    print_info "🚀 Starting development workflow..."
    
    if run_tests "core-tests" "Core tests"; then
        print_info "Core tests passed, running integration tests..."
        if run_tests "integration-tests" "Integration tests"; then
            print_success "🎉 Development workflow completed successfully!"
            print_info "Your changes look good! Ready to commit."
        else
            print_error "Integration tests failed. Please fix before committing."
            return 1
        fi
    else
        print_error "Core tests failed. Please fix basic functionality first."
        return 1
    fi
}

# Function to run CI workflow
run_ci_workflow() {
    print_info "🏗️  Starting CI workflow simulation..."
    
    if run_tests "core-tests" "Core tests"; then
        if run_tests "integration-tests" "Integration tests"; then
            if run_tests "all-tests" "All tests"; then
                print_success "🎉 CI workflow completed successfully!"
                print_info "Ready for production deployment!"
            else
                print_error "Comprehensive tests failed. Please investigate."
                return 1
            fi
        else
            print_error "Integration tests failed. Please fix before proceeding."
            return 1
        fi
    else
        print_error "Core tests failed. Please fix basic functionality first."
        return 1
    fi
}

# Main script logic
case "${1:-core}" in
    "core")
        run_tests "core-tests" "Core tests"
        ;;
    "integration")
        run_tests "integration-tests" "Integration tests"
        ;;
    "performance")
        run_tests "performance-tests" "Performance tests"
        ;;
    "smoke")
        run_tests "smoke-tests" "Smoke tests"
        ;;
    "slow")
        run_tests "slow-tests" "Slow tests"
        ;;
    "all")
        run_tests "all-tests" "All tests (except flaky)"
        ;;
    "full")
        run_tests "full-tests" "Full test suite (including flaky)"
        ;;
    "dev")
        run_dev_workflow
        ;;
    "ci")
        run_ci_workflow
        ;;
    "help"|"-h"|"--help")
        show_usage
        ;;
    *)
        print_error "Unknown category: $1"
        echo ""
        show_usage
        exit 1
        ;;
esac
