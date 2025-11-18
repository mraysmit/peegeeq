#!/usr/bin/env bash
#
# PeeGeeQ Development Database Migration Script
#
# This script runs migrations WITHOUT cleaning the database.
# Use this for applying new migrations to an existing database.
#
# Usage:
#   cd peegeeq-migrations/scripts
#   ./dev-migrate.sh                    # Use default local database
#   ./dev-migrate.sh custom_db_name     # Use custom database name
#
# Environment Variables (optional):
#   DB_HOST      - Database host (default: localhost)
#   DB_PORT      - Database port (default: 5432)
#   DB_NAME      - Database name (default: peegeeq_dev)
#   DB_USER      - Database user (default: peegeeq_dev)
#   DB_PASSWORD  - Database password (default: peegeeq_dev)
#

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default configuration
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-peegeeq_dev}"
DB_USER="${DB_USER:-peegeeq_dev}"
DB_PASSWORD="${DB_PASSWORD:-peegeeq_dev}"

# Override DB_NAME if provided as argument
if [ $# -gt 0 ]; then
    DB_NAME="$1"
fi

# Construct JDBC URL
DB_JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║       PeeGeeQ Development Database Migration                   ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "Database: ${DB_NAME}"
echo "Host:     ${DB_HOST}:${DB_PORT}"
echo "User:     ${DB_USER}"
echo ""

echo -e "${BLUE}Step 1: Building migrations JAR...${NC}"
# Navigate to the peegeeq-migrations module root (parent of scripts/)
cd "$(dirname "$0")/.."
mvn clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Failed to build migrations JAR${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Migrations JAR built successfully${NC}"
echo ""

echo -e "${BLUE}Step 2: Running migrations...${NC}"
export DB_JDBC_URL
export DB_USER
export DB_PASSWORD

java -jar target/peegeeq-migrations.jar migrate

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Migration failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  ✓ Migration completed successfully!                           ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

