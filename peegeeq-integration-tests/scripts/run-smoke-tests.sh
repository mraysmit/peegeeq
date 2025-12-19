#!/bin/bash
#
# PeeGeeQ End-to-End Smoke Tests Runner
#
# Usage:
#   ./run-smoke-tests.sh [options]
#
# Options:
#   --skip-java       Skip Java smoke tests
#   --skip-typescript Skip TypeScript smoke tests
#   --verbose         Enable verbose output
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PARENT_ROOT="$(dirname "$PROJECT_ROOT")"

SKIP_JAVA=false
SKIP_TYPESCRIPT=false
VERBOSE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-java)
            SKIP_JAVA=true
            shift
            ;;
        --skip-typescript)
            SKIP_TYPESCRIPT=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "========================================"
echo "PeeGeeQ End-to-End Smoke Tests"
echo "========================================"

# Step 1: Build the project
echo ""
echo "[1/4] Building project..."
cd "$PARENT_ROOT"
if [ "$VERBOSE" = true ]; then
    mvn clean install -DskipTests
else
    mvn clean install -DskipTests -q
fi
echo "Build successful"

# Step 2: Run Java smoke tests
if [ "$SKIP_JAVA" = false ]; then
    echo ""
    echo "[2/4] Running Java smoke tests..."
    cd "$PROJECT_ROOT"
    if [ "$VERBOSE" = true ]; then
        mvn test -Psmoke-tests
    else
        mvn test -Psmoke-tests -q
    fi
    echo "Java smoke tests passed"
else
    echo ""
    echo "[2/4] Skipping Java smoke tests"
fi

# Step 3: Install TypeScript dependencies
echo ""
echo "[3/4] Installing TypeScript dependencies..."
cd "$PROJECT_ROOT"
npm install
echo "Dependencies installed"

# Step 4: Run TypeScript smoke tests
if [ "$SKIP_TYPESCRIPT" = false ]; then
    echo ""
    echo "[4/4] Running TypeScript smoke tests..."
    cd "$PROJECT_ROOT"
    npm run test:smoke
    echo "TypeScript smoke tests passed"
else
    echo ""
    echo "[4/4] Skipping TypeScript smoke tests"
fi

echo ""
echo "========================================"
echo "All smoke tests completed successfully!"
echo "========================================"

