#!/bin/bash
# Test runner script for PeeGeeQ migrations
# Runs all integration tests using Testcontainers

# Exit on any error
set -e

echo "🧪 Running PeeGeeQ Migration Tests"
echo "===================================="
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running"
    echo "   Testcontainers requires Docker to be running"
    echo "   Please start Docker and try again"
    exit 1
fi

echo "Docker is running"
echo ""

# Change to migrations directory
cd "$(dirname "$0")"

# Run tests
echo "📦 Running migration tests..."
echo ""

mvn clean test

# Check exit code
if [ $? -eq 0 ]; then
    echo ""
    echo "All tests passed!"
    echo ""
    echo "Tests verified:"
    echo "  ✓ All migrations execute successfully"
    echo "  ✓ All required tables exist"
    echo "  ✓ All required indexes exist"
    echo "  ✓ All required views exist"
    echo "  ✓ All required functions exist"
    echo "  ✓ All required triggers exist"
    echo "  ✓ Schema structure is valid"
    echo "  ✓ Migration conventions are followed"
else
    echo ""
    echo "❌ Tests failed"
    exit 1
fi
