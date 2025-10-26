#!/bin/bash

# PeeGeeQ Test Execution Helper Script
# 
# This script provides convenient commands for running different test categories
# in the peegeeq-db module with appropriate timing and feedback.

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
    local expected_time=$3
    
    print_status "Running $description (expected: $expected_time)"
    start_time=$(date +%s)
    
    if mvn -q test -P$profile; then
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

# Main script logic
case "${1:-help}" in
    "core"|"c")
        run_tests "core-tests" "Core Tests" "< 30s"
        ;;
    "integration"|"i")
        run_tests "integration-tests" "Integration Tests" "1-3 min"
        ;;
    "performance"|"p")
        run_tests "performance-tests" "Performance Tests" "2-5 min"
        ;;
    "smoke"|"s")
        run_tests "smoke-tests" "Smoke Tests" "< 10s"
        ;;
    "all"|"a")
        run_tests "all-tests" "All Tests (except flaky)" "5-10 min"
        ;;
    "full"|"f")
        run_tests "full-tests" "Full Tests (including flaky)" "10+ min"
        ;;
    "dev"|"d")
        print_status "Running development workflow: Core → Integration"
        if run_tests "core-tests" "Core Tests" "< 30s"; then
            run_tests "integration-tests" "Integration Tests" "1-3 min"
        else
            print_error "Core tests failed, skipping integration tests"
            exit 1
        fi
        ;;
    "ci")
        print_status "Running CI workflow: Core → Integration → All"
        if run_tests "core-tests" "Core Tests" "< 30s"; then
            if run_tests "integration-tests" "Integration Tests" "1-3 min"; then
                run_tests "all-tests" "All Tests" "5-10 min"
            else
                print_error "Integration tests failed, skipping full test suite"
                exit 1
            fi
        else
            print_error "Core tests failed, skipping remaining tests"
            exit 1
        fi
        ;;
    "help"|"h"|*)
        echo "PeeGeeQ Test Execution Helper"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  core, c         Run core tests (< 30s) - Fast unit tests"
        echo "  integration, i  Run integration tests (1-3 min) - With real infrastructure"
        echo "  performance, p  Run performance tests (2-5 min) - Load & throughput"
        echo "  smoke, s        Run smoke tests (< 10s) - Ultra-fast verification"
        echo "  all, a          Run all tests except flaky (5-10 min)"
        echo "  full, f         Run all tests including flaky (10+ min)"
        echo ""
        echo "Workflows:"
        echo "  dev, d          Development workflow: core → integration"
        echo "  ci              CI workflow: core → integration → all"
        echo ""
        echo "Examples:"
        echo "  $0 core         # Quick feedback during development"
        echo "  $0 dev          # Full development validation"
        echo "  $0 ci           # Complete CI validation"
        echo ""
        echo "Direct Maven commands:"
        echo "  mvn test -Pcore-tests"
        echo "  mvn test -Pintegration-tests"
        echo "  mvn test -Pperformance-tests"
        echo "  mvn test -Psmoke-tests"
        echo "  mvn test -Pall-tests"
        echo "  mvn test -Pfull-tests"
        ;;
esac
