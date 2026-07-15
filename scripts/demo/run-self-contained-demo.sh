#!/bin/bash
# PeeGeeQ Self-Contained Demo Runner for Unix/Linux/macOS
# This script runs the self-contained demo that uses TestContainers

echo
echo "========================================"
echo " PeeGeeQ Self-Contained Demo"
echo "========================================"
echo
echo "This demo will:"
echo " - Start a PostgreSQL container automatically"
echo " - Demonstrate all PeeGeeQ production features"
echo " - Clean up when finished"
echo
echo "Requirements:"
echo " - Docker running"
echo " - Internet connection (to download PostgreSQL image)"
echo

# Check if Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is not running or not accessible"
    echo "Please start Docker and try again"
    exit 1
fi

echo "Docker is running - proceeding with demo..."
echo

# Run the demo
mvn compile exec:java \
    -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQSelfContainedDemo" \
    -pl peegeeq-examples

if [ $? -ne 0 ]; then
    echo
    echo "ERROR: Demo failed to run"
    echo "Check the output above for details"
    exit 1
fi

echo
echo "========================================"
echo " Demo completed successfully!"
echo "========================================"
