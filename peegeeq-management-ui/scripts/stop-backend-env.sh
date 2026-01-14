#!/bin/bash

# Stop the PeeGeeQ E2E test environment
# This stops the Docker container and removes the config file

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DB_CONFIG_FILE="$PROJECT_ROOT/testcontainers-db.json"
CONTAINER_NAME="peegeeq-e2e-db"

echo "Stopping E2E test environment..."

# Stop the container if it's running
if [ "$(docker ps -a -q -f name="^/${CONTAINER_NAME}$")" ]; then
    echo "Stopping container '$CONTAINER_NAME'..."
    docker stop $CONTAINER_NAME > /dev/null
    echo "✅ Container stopped"
else
    echo "Container '$CONTAINER_NAME' not found (already stopped or removed)"
fi

# Remove the config file
if [ -f "$DB_CONFIG_FILE" ]; then
    rm -f "$DB_CONFIG_FILE"
    echo "✅ Removed config file: $DB_CONFIG_FILE"
else
    echo "Config file not found (already removed)"
fi

echo ""
echo "Environment stopped."
echo "To completely remove the container, run: docker rm $CONTAINER_NAME"
