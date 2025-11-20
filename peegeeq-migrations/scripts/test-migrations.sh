#!/bin/bash
# Automated Test Script for PeeGeeQ Migrations Module
# Tests all JAR commands, dev scripts, and validates schema creation

set -e

# Default configuration
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-peegeeq_migrations_test}"
DB_USER="${DB_USER:-peegeeq_dev}"
DB_PASSWORD="${DB_PASSWORD:-peegeeq_dev}"
SKIP_BUILD=false
SKIP_CLEANUP=false
VERBOSE=false

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
declare -a TEST_RESULTS

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# Helper functions
write_success() { echo -e "${GREEN}✅ $*${NC}"; }
write_failure() { echo -e "${RED}❌ $*${NC}"; }
write_info() { echo -e "${CYAN}ℹ️  $*${NC}"; }
write_test() { echo -e "${YELLOW}🧪 $*${NC}"; }
write_section() {
    echo ""
    echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
    echo -e "${MAGENTA}  $*${NC}"
    echo -e "${MAGENTA}═══════════════════════════════════════════════════════════${NC}"
    echo ""
}

# Record test result
record_test_result() {
    local test_name="$1"
    local passed="$2"
    local message="${3:-}"
    
    if [ "$passed" = "true" ]; then
        ((TESTS_PASSED++))
        write_success "$test_name - PASSED"
    else
        ((TESTS_FAILED++))
        write_failure "$test_name - FAILED: $message"
    fi
    
    TEST_RESULTS+=("$test_name|$passed|$message")
}

# Run command and capture output
run_test_command() {
    local command="$1"
    local expect_failure="${2:-false}"
    
    if [ "$VERBOSE" = "true" ]; then
        write_info "Running: $command"
    fi
    
    local output
    local exit_code
    
    if output=$(eval "$command" 2>&1); then
        exit_code=0
    else
        exit_code=$?
    fi
    
    if [ "$VERBOSE" = "true" ]; then
        echo -e "${NC}$output${NC}"
    fi
    
    if [ "$expect_failure" = "false" ] && [ $exit_code -ne 0 ]; then
        echo "FAILED|$output|$exit_code"
        return 1
    fi
    
    echo "SUCCESS|$output|$exit_code"
    return 0
}

# Check PostgreSQL connection
test_postgresql_connection() {
    write_test "Testing PostgreSQL connection..."
    
    if psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -c "SELECT 1;" >/dev/null 2>&1; then
        record_test_result "PostgreSQL Connection" "true"
        return 0
    else
        record_test_result "PostgreSQL Connection" "false" "Cannot connect to PostgreSQL at ${DB_HOST}:${DB_PORT}"
        return 1
    fi
}

# Create test database
initialize_test_database() {
    write_test "Creating test database: $DB_NAME"
    
    # Drop if exists
    psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -c "DROP DATABASE IF EXISTS $DB_NAME;" >/dev/null 2>&1 || true
    
    # Create database
    if psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -c "CREATE DATABASE $DB_NAME;" >/dev/null 2>&1; then
        record_test_result "Create Test Database" "true"
        return 0
    else
        record_test_result "Create Test Database" "false" "Failed to create database"
        return 1
    fi
}

# Set environment variables
set_migration_environment() {
    export DB_JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"
    export DB_USER="$DB_USER"
    export DB_PASSWORD="$DB_PASSWORD"
    
    write_info "Environment configured:"
    write_info "  DB_JDBC_URL: $DB_JDBC_URL"
    write_info "  DB_USER: $DB_USER"
}

# Build the JAR
build_migrations_jar() {
    write_test "Building migrations JAR..."
    
    local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    local project_root="$(cd "$script_dir/../.." && pwd)"
    
    cd "$project_root"
    
    if mvn clean package -pl peegeeq-migrations -DskipTests -q; then
        if [ -f "peegeeq-migrations/target/peegeeq-migrations.jar" ]; then
            record_test_result "Build Migrations JAR" "true"
            return 0
        else
            record_test_result "Build Migrations JAR" "false" "JAR not found after build"
            return 1
        fi
    else
        record_test_result "Build Migrations JAR" "false" "Maven build failed"
        return 1
    fi
}

# Test JAR command
test_jar_command() {
    local command="$1"
    local test_name="$2"
    local expect_failure="${3:-false}"
    local expected_output="${4:-}"

    write_test "Testing: $test_name"

    local jar_path="peegeeq-migrations/target/peegeeq-migrations.jar"
    local result
    result=$(run_test_command "java -jar $jar_path $command" "$expect_failure")

    local status="${result%%|*}"
    local output="${result#*|}"
    output="${output%|*}"
    local exit_code="${result##*|}"

    if [ "$expect_failure" = "true" ]; then
        if [ "$exit_code" -ne 0 ]; then
            record_test_result "$test_name" "true"
            return 0
        else
            record_test_result "$test_name" "false" "Expected failure but command succeeded"
            return 1
        fi
    fi

    if [ "$status" != "SUCCESS" ]; then
        record_test_result "$test_name" "false" "$output"
        return 1
    fi

    if [ -n "$expected_output" ] && ! echo "$output" | grep -q "$expected_output"; then
        record_test_result "$test_name" "false" "Output did not contain expected text: $expected_output"
        return 1
    fi

    record_test_result "$test_name" "true"
    return 0
}

# Test schema validation
test_schema_validation() {
    write_test "Validating database schema..."

    local expected_tables=(
        "schema_version"
        "outbox"
        "outbox_consumer_groups"
        "queue_messages"
        "message_processing"
        "dead_letter_queue"
        "queue_metrics"
        "connection_pool_metrics"
        "bitemporal_event_log"
    )

    local expected_functions=(
        "notify_message_inserted"
        "update_message_processing_updated_at"
        "cleanup_completed_message_processing"
        "register_consumer_group_for_existing_messages"
        "create_consumer_group_entries_for_new_message"
        "cleanup_completed_outbox_messages"
        "notify_bitemporal_event"
        "cleanup_old_metrics"
        "get_events_as_of_time"
    )

    local expected_views=(
        "bitemporal_current_state"
        "bitemporal_latest_events"
    )

    local all_valid=true

    # Check tables
    for table in "${expected_tables[@]}"; do
        local query="SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = '$table');"
        local result
        result=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c "$query" 2>&1)

        if echo "$result" | grep -q "t"; then
            [ "$VERBOSE" = "true" ] && write_success "  Table exists: $table"
        else
            write_failure "  Table missing: $table"
            all_valid=false
        fi
    done

    # Check functions
    for func in "${expected_functions[@]}"; do
        local query="SELECT EXISTS (SELECT FROM pg_proc WHERE proname = '$func');"
        local result
        result=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c "$query" 2>&1)

        if echo "$result" | grep -q "t"; then
            [ "$VERBOSE" = "true" ] && write_success "  Function exists: $func"
        else
            write_failure "  Function missing: $func"
            all_valid=false
        fi
    done

    # Check views
    for view in "${expected_views[@]}"; do
        local query="SELECT EXISTS (SELECT FROM information_schema.views WHERE table_name = '$view');"
        local result
        result=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c "$query" 2>&1)

        if echo "$result" | grep -q "t"; then
            [ "$VERBOSE" = "true" ] && write_success "  View exists: $view"
        else
            write_failure "  View missing: $view"
            all_valid=false
        fi
    done

    if [ "$all_valid" = "true" ]; then
        record_test_result "Schema Validation" "true"
        return 0
    else
        record_test_result "Schema Validation" "false"
        return 1
    fi
}

# Test dev scripts
test_dev_scripts() {
    write_test "Testing dev scripts..."

    local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    cd "$script_dir"

    local result
    result=$(run_test_command "./dev-migrate.sh")

    local status="${result%%|*}"

    if [ "$status" = "SUCCESS" ]; then
        record_test_result "Dev Script: dev-migrate.sh" "true"
        return 0
    else
        record_test_result "Dev Script: dev-migrate.sh" "false" "Script failed"
        return 1
    fi
}

# Cleanup test database
remove_test_database() {
    if [ "$SKIP_CLEANUP" = "true" ]; then
        write_info "Skipping cleanup (--skip-cleanup flag set)"
        return
    fi

    write_test "Cleaning up test database..."
    psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -c "DROP DATABASE IF EXISTS $DB_NAME;" >/dev/null 2>&1 || true
    write_success "Test database dropped"
}

# Print summary
show_test_summary() {
    write_section "TEST SUMMARY"

    local total=$((TESTS_PASSED + TESTS_FAILED))
    echo -e "${NC}Total Tests: $total${NC}"
    echo -e "${GREEN}Passed: $TESTS_PASSED${NC}"
    echo -e "${RED}Failed: $TESTS_FAILED${NC}"
    echo ""

    if [ $TESTS_FAILED -gt 0 ]; then
        echo -e "${RED}Failed Tests:${NC}"
        for result in "${TEST_RESULTS[@]}"; do
            IFS='|' read -r test_name passed message <<< "$result"
            if [ "$passed" = "false" ]; then
                echo -e "${RED}  ❌ $test_name: $message${NC}"
            fi
        done
        echo ""
    fi

    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "${GREEN}🎉 ALL TESTS PASSED! 🎉${NC}"
        exit 0
    else
        echo -e "${RED}💔 SOME TESTS FAILED 💔${NC}"
        exit 1
    fi
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-build)
                SKIP_BUILD=true
                shift
                ;;
            --skip-cleanup)
                SKIP_CLEANUP=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --db-host)
                DB_HOST="$2"
                shift 2
                ;;
            --db-port)
                DB_PORT="$2"
                shift 2
                ;;
            --db-name)
                DB_NAME="$2"
                shift 2
                ;;
            --db-user)
                DB_USER="$2"
                shift 2
                ;;
            --db-password)
                DB_PASSWORD="$2"
                shift 2
                ;;
            --help)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --skip-build       Skip building the JAR"
                echo "  --skip-cleanup     Skip cleaning up test database"
                echo "  --verbose          Enable verbose output"
                echo "  --db-host HOST     Database host (default: localhost)"
                echo "  --db-port PORT     Database port (default: 5432)"
                echo "  --db-name NAME     Database name (default: peegeeq_migrations_test)"
                echo "  --db-user USER     Database user (default: peegeeq_dev)"
                echo "  --db-password PWD  Database password (default: peegeeq_dev)"
                echo "  --help             Show this help message"
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done
}

# Main test execution
start_migration_tests() {
    write_section "PeeGeeQ Migrations - Automated Test Suite"

    write_info "Test Configuration:"
    write_info "  Database Host: $DB_HOST"
    write_info "  Database Port: $DB_PORT"
    write_info "  Database Name: $DB_NAME"
    write_info "  Database User: $DB_USER"
    write_info "  Skip Build: $SKIP_BUILD"
    write_info "  Skip Cleanup: $SKIP_CLEANUP"
    write_info "  Verbose: $VERBOSE"
    echo ""

    # Prerequisites
    write_section "PHASE 1: Prerequisites"

    if ! test_postgresql_connection; then
        write_failure "PostgreSQL is not available. Please start PostgreSQL and try again."
        show_test_summary
        return
    fi

    if [ "$SKIP_BUILD" = "false" ]; then
        if ! build_migrations_jar; then
            write_failure "Failed to build migrations JAR. Cannot continue."
            show_test_summary
            return
        fi
    else
        write_info "Skipping build (--skip-build flag set)"
        if [ ! -f "peegeeq-migrations/target/peegeeq-migrations.jar" ]; then
            write_failure "JAR not found and --skip-build specified. Build first or remove flag."
            show_test_summary
            return
        fi
    fi

    if ! initialize_test_database; then
        write_failure "Failed to create test database. Cannot continue."
        show_test_summary
        return
    fi

    set_migration_environment

    # Test JAR Commands
    write_section "PHASE 2: JAR Command Tests"

    test_jar_command "info" "JAR Command: info (before migration)"
    test_jar_command "migrate" "JAR Command: migrate" "false" "successfully"
    test_jar_command "info" "JAR Command: info (after migration)" "false" "Success"
    test_jar_command "validate" "JAR Command: validate"
    test_jar_command "migrate" "JAR Command: migrate (idempotency)"

    # Test Schema
    write_section "PHASE 3: Schema Validation"

    test_schema_validation

    # Test Error Handling
    write_section "PHASE 4: Error Handling Tests"

    # Test invalid credentials
    local original_user="$DB_USER"
    export DB_USER="invalid_user_12345"
    test_jar_command "info" "JAR Command: info (invalid credentials)" "true"
    export DB_USER="$original_user"

    # Test clean without safety flag
    unset DB_CLEAN_ON_START
    test_jar_command "clean" "JAR Command: clean (without safety flag)" "true"

    # Test clean with safety flag
    export DB_CLEAN_ON_START="true"
    test_jar_command "clean" "JAR Command: clean (with safety flag)"

    # Re-migrate after clean
    test_jar_command "migrate" "JAR Command: migrate (after clean)"

    # Test baseline
    test_jar_command "baseline" "JAR Command: baseline"

    # Test repair
    test_jar_command "repair" "JAR Command: repair"

    # Test Dev Scripts
    write_section "PHASE 5: Dev Scripts Tests"

    test_dev_scripts

    # Cleanup
    write_section "PHASE 6: Cleanup"

    remove_test_database

    # Summary
    show_test_summary
}

# Parse arguments and run tests
parse_args "$@"
start_migration_tests

