#!/bin/bash
set -e

# Start the PeeGeeQ backend with TestContainers database connection

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DB_CONFIG_FILE="$PROJECT_ROOT/testcontainers-db.json"
CONTAINER_NAME="peegeeq-e2e-db"
DB_IMAGE="postgres:15.13-alpine3.20"

# --- Step 1: Ensure Database is Running ---

echo "Checking database container '$CONTAINER_NAME'..."

# Check if container exists (running or stopped)
if [ "$(docker ps -a -q -f name="^/${CONTAINER_NAME}$")" ]; then
    # Check if it's actually running
    if [ "$(docker ps -q -f name="^/${CONTAINER_NAME}$")" ]; then
        echo "Container is already running."
    else
        echo "Starting existing container..."
        docker start $CONTAINER_NAME
        sleep 3
    fi
else
    echo "Starting new PostgreSQL container..."
    # Run detached, map random host port to 5432
    docker run -d --name $CONTAINER_NAME -p 0:5432 -e POSTGRES_PASSWORD=postgres $DB_IMAGE
    
    echo "Waiting for Database to be ready..."
    MAX_RETRIES=30
    RETRY_COUNT=0
    DB_READY=false

    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        sleep 1
        RETRY_COUNT=$((RETRY_COUNT+1))
        
        # Check if Postgres is accepting connections
        if docker exec $CONTAINER_NAME pg_isready -U postgres -q; then
            DB_READY=true
            break
        fi
        echo -n "."
    done
    echo ""

    if [ "$DB_READY" = false ]; then
        echo "Database failed to start after 30 seconds."
        exit 1
    fi
    
    echo "Creating 'peegeeq' user..."
    docker exec $CONTAINER_NAME psql -U postgres -c "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    
    # Verify SUPERUSER privilege
    CHECK_RESULT=$(docker exec $CONTAINER_NAME psql -U postgres -t -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'peegeeq';")
    if [[ $(echo "$CHECK_RESULT" | tr -d '[:space:]') != "t" ]]; then
        echo "Failed to verify peegeeq user SUPERUSER privilege."
        exit 1
    fi
fi

# --- Step 2: Get Connection Details & Write Config ---

# Get the dynamic port mapping (Format: 0.0.0.0:32768)
PORT_MAPPING=$(docker port $CONTAINER_NAME 5432)
if [ -z "$PORT_MAPPING" ]; then
    echo "Could not determine port mapping for container $CONTAINER_NAME"
    exit 1
fi

# Extract just the port number (assuming 0.0.0.0:PORT format)
DB_PORT=$(echo $PORT_MAPPING | awk -F: '{print $NF}')
DB_HOST="localhost"

echo "Database is listening on $DB_HOST:$DB_PORT"

# Create JSON config
cat > "$DB_CONFIG_FILE" <<EOF
{
  "host": "$DB_HOST",
  "port": $DB_PORT,
  "database": "postgres",
  "username": "peegeeq",
  "password": "peegeeq",
  "jdbcUrl": "postgres://peegeeq:peegeeq@$DB_HOST:$DB_PORT/postgres"
}
EOF

echo "Updated connection config: $DB_CONFIG_FILE"

# --- Step 3: Start Backend ---

# Set environment variables for PeeGeeQ
export PEEGEEQ_DATABASE_HOST=$DB_HOST
export PEEGEEQ_DATABASE_PORT=$DB_PORT
export PEEGEEQ_DATABASE_NAME="postgres"
export PEEGEEQ_DATABASE_USERNAME="peegeeq"
export PEEGEEQ_DATABASE_PASSWORD="peegeeq"
export PEEGEEQ_DATABASE_SCHEMA="public"

echo ""
echo "Starting PeeGeeQ REST API server..."
echo ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
MANAGEMENT_UI_ROOT="$(dirname "$SCRIPT_DIR")"
REPOSITORY_ROOT="$(dirname "$MANAGEMENT_UI_ROOT")"
cd "$REPOSITORY_ROOT"

# Build the Maven command with system properties
mvn exec:java \
    -pl peegeeq-rest \
    -DPEEGEEQ_DATABASE_HOST=$DB_HOST \
    -DPEEGEEQ_DATABASE_PORT=$DB_PORT \
    -DPEEGEEQ_DATABASE_NAME=postgres \
    -DPEEGEEQ_DATABASE_USERNAME=peegeeq \
    -DPEEGEEQ_DATABASE_PASSWORD=peegeeq \
    -DPEEGEEQ_DATABASE_SCHEMA=public

