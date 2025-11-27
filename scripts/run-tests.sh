#!/bin/bash

# PeeGeeQ Master Test Execution Script
# Centralized test runner for all modules and test categories

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Project configuration
PROJECT_NAME="PeeGeeQ"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# All available modules with test categorization
CATEGORIZED_MODULES=(
    "peegeeq-db"
    "peegeeq-native"
    "peegeeq-bitemporal"
    "peegeeq-outbox"
    "peegeeq-rest"
    "peegeeq-test-support"
    "peegeeq-service-manager"
    "peegeeq-performance-test-harness"
    "peegeeq-api"
    "peegeeq-examples"
    "peegeeq-examples-spring"
    "peegeeq-migrations"
    "peegeeq-management-ui"
)

# Valid test categories
VALID_CATEGORIES=("core" "integration" "performance" "smoke" "slow" "all")

# Function to print colored output
print_info() {
    printf "${BLUE}ℹ️  %s${NC}\n" "$*"
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

print_header() {
    echo -e "${CYAN}🎯 $1${NC}"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [CATEGORY] [MODULE1] [MODULE2] ..."
    echo ""
    echo "Parameters:"
    echo "  CATEGORY    Test category to run (required)"
    echo "  MODULES     Space-separated list of modules (optional, defaults to all)"
    echo ""
    echo "Test Categories:"
    echo "  core          Fast unit tests (~30 seconds per module)"
    echo "  integration   Tests with TestContainers (~5-10 minutes per module)"
    echo "  performance   Load and throughput tests (~10-15 minutes per module)"
    echo "  smoke         Ultra-fast basic verification (~30 seconds per module)"
    echo "  slow          Long-running comprehensive tests (~15+ minutes per module)"
    echo "  all           All tests except flaky (~20+ minutes per module)"
    echo ""
    echo "Available Modules:"
    for module in "${CATEGORIZED_MODULES[@]}"; do
        echo "  $module"
    done
    echo ""
    echo "Examples:"
    echo "  $0 core                                    # All modules, core tests"
    echo "  $0 integration                             # All modules, integration tests"
    echo "  $0 core peegeeq-outbox                     # Single module, core tests"
    echo "  $0 performance peegeeq-outbox peegeeq-rest # Multiple modules, performance tests"
    echo "  $0 smoke peegeeq-db peegeeq-api           # Multiple modules, smoke tests"
    echo "  $0 all                                     # All modules, all tests"
    echo ""
    echo "Performance Expectations:"
    echo "  core tests:        ~1 minute total (all modules)"
    echo "  smoke tests:       ~30 seconds total (all modules)"
    echo "  integration tests: ~10-15 minutes total (all modules)"
    echo "  performance tests: ~20-30 minutes total (all modules)"
    echo "  all tests:         ~45+ minutes total (all modules)"
    echo ""
    echo "💡 Tip: Use 'core' for daily development, 'integration' before commits"
}

# Function to validate category
validate_category() {
    local category=$1
    for valid in "${VALID_CATEGORIES[@]}"; do
        if [[ "$valid" == "$category" ]]; then
            return 0
        fi
    done
    return 1
}

# Function to validate modules
validate_modules() {
    local modules_input="$1"
    read -ra modules <<< "$modules_input"

    for module in "${modules[@]}"; do
        local found=false
        for valid in "${CATEGORIZED_MODULES[@]}"; do
            if [[ "$valid" == "$module" ]]; then
                found=true
                break
            fi
        done
        if [[ "$found" == false ]]; then
            print_error "Invalid module: $module"
            echo "Available modules: ${CATEGORIZED_MODULES[*]}"
            return 1
        fi
    done
    return 0
}

# Function to get Maven profile for category
get_maven_profile() {
    local category=$1
    case "$category" in
        "core") echo "core-tests" ;;
        "integration") echo "integration-tests" ;;
        "performance") echo "performance-tests" ;;
        "smoke") echo "smoke-tests" ;;
        "slow") echo "slow-tests" ;;
        "all") echo "all-tests" ;;
        *) echo "core-tests" ;;
    esac
}

# Function to get expected duration
get_expected_duration() {
    local category=$1
    local module_count=$2
    case "$category" in
        "core") echo "~$((module_count * 10)) seconds" ;;
        "smoke") echo "~$((module_count * 5)) seconds" ;;
        "integration") echo "~$((module_count * 2)) minutes" ;;
        "performance") echo "~$((module_count * 3)) minutes" ;;
        "slow") echo "~$((module_count * 5)) minutes" ;;
        "all") echo "~$((module_count * 8)) minutes" ;;
        *) echo "variable" ;;
    esac
}

# Function to run tests for specified modules
run_tests() {
    local category=$1
    local modules_input=$2
    local profile=$(get_maven_profile "$category")
    
    # Determine target modules
    local target_modules
    if [[ -z "$modules_input" ]]; then
        target_modules=("${CATEGORIZED_MODULES[@]}")
        print_info "Running $category tests for ALL modules"
    else
        target_modules=($modules_input)
        print_info "Running $category tests for modules: ${target_modules[*]}"
    fi
    
    local expected_duration=$(get_expected_duration "$category" ${#target_modules[@]})
    print_info "Expected duration: $expected_duration"
    print_info "Maven profile: $profile"
    
    # Build Maven command
    local maven_modules=""
    for module in "${target_modules[@]}"; do
        if [[ -z "$maven_modules" ]]; then
            maven_modules=":$module"
        else
            maven_modules="$maven_modules,:$module"
        fi
    done
    
    local maven_cmd="mvn test -P$profile"
    if [[ -n "$maven_modules" ]]; then
        maven_cmd="$maven_cmd -pl $maven_modules"
    fi
    
    print_header "Executing: $maven_cmd"
    
    # Execute tests with timing
    local start_time=$(date +%s)
    
    if eval "$maven_cmd"; then
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        
        print_success "Tests completed successfully in ${duration}s"
        
        # Performance feedback
        if [[ $duration -lt 60 ]]; then
            print_performance "Excellent performance! ⚡"
        elif [[ $duration -lt 300 ]]; then
            print_performance "Good performance! 🚀"
        elif [[ $duration -lt 600 ]]; then
            print_performance "Standard performance 📊"
        else
            print_warning "Long execution time - expected for comprehensive testing"
        fi
        
        # Module-specific summary
        print_info "Modules tested: ${#target_modules[@]}"
        print_info "Category: $category"
        print_info "Duration: ${duration}s"
        
    else
        print_error "Tests failed"
        print_warning "Check logs above for specific test failures"
        exit 1
    fi
}

# Main script logic
main() {
    # Store original argument count before shift
    local original_argc=$#
    local category=$1
    shift
    local modules_array=("$@")
    local modules="$*"


    # Show usage if no arguments or help requested
    if [[ $original_argc -eq 0 ]] || [[ "$category" == "help" ]] || [[ "$category" == "-h" ]] || [[ "$category" == "--help" ]]; then
        show_usage
        exit 0
    fi
    
    # Validate category
    if ! validate_category "$category"; then
        print_error "Invalid category: $category"
        echo "Valid categories: ${VALID_CATEGORIES[*]}"
        echo ""
        show_usage
        exit 1
    fi
    
    # Validate modules if provided
    if [[ -n "$modules" ]] && ! validate_modules "$modules"; then
        exit 1
    fi
    
    # Print header
    print_header "$PROJECT_NAME Test Execution"
    print_info "Category: $category"
    if [[ ${#modules_array[@]} -gt 0 ]]; then
        print_info "Modules: ${modules_array[*]}"
    else
        print_info "Modules: ALL (${#CATEGORIZED_MODULES[@]} modules)"
    fi
    
    # Run the tests
    if [[ ${#modules_array[@]} -eq 0 ]]; then
        run_tests "$category" ""
    else
        run_tests "$category" "${modules_array[*]}"
    fi
    
    print_success "$PROJECT_NAME test execution completed successfully!"
    print_info "💡 Tip: Use '$0 core' for daily development"
}

# Execute main function with all arguments
main "$@"
