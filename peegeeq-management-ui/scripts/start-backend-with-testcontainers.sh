#!/bin/bash

# Start the PeeGeeQ backend with TestContainers database connection
# This script reads the connection details from testcontainers-db.json
# and starts the backend with the correct environment variables

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANAGEMENT_UI_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DB_CONFIG_FILE="$MANAGEMENT_UI_ROOT/testcontainers-db.json"

# Check if the TestContainers config file exists
if [ ! -f "$DB_CONFIG_FILE" ]; then
    echo "❌ TestContainers database config not found: $DB_CONFIG_FILE"
    echo "   Please run the Playwright tests first to start the PostgreSQL container."
    echo "   The global setup will create this file automatically."
    exit 1
fi

# Read connection details from JSON file
echo "📖 Reading TestContainers database connection details..."
HOST=$(jq -r '.host' "$DB_CONFIG_FILE")
PORT=$(jq -r '.port' "$DB_CONFIG_FILE")
DATABASE=$(jq -r '.database' "$DB_CONFIG_FILE")
USERNAME=$(jq -r '.username' "$DB_CONFIG_FILE")
PASSWORD=$(jq -r '.password' "$DB_CONFIG_FILE")

echo "✅ TestContainers PostgreSQL connection:"
echo "   Host: $HOST"
echo "   Port: $PORT"
echo "   Database: $DATABASE"
echo "   Username: $USERNAME"

# Export environment variables for PeeGeeQ
export PEEGEEQ_DATABASE_HOST="$HOST"
export PEEGEEQ_DATABASE_PORT="$PORT"
export PEEGEEQ_DATABASE_NAME="$DATABASE"
export PEEGEEQ_DATABASE_USERNAME="$USERNAME"
export PEEGEEQ_DATABASE_PASSWORD="$PASSWORD"
export PEEGEEQ_DATABASE_SCHEMA="public"

echo ""
echo "🚀 Starting PeeGeeQ REST API server with TestContainers database..."
echo ""

# Navigate to repository root and start the server
REPOSITORY_ROOT="$(cd "$MANAGEMENT_UI_ROOT/.." && pwd)"
cd "$REPOSITORY_ROOT"
mvn exec:java -pl peegeeq-rest -Dexec.mainClass="dev.mars.peegeeq.rest.StartRestServer"

