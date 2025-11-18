#!/usr/bin/env bash
#
# PeeGeeQ Development Database Reset Script
#
# This script provides a convenient way to reset your local development database
# by cleaning and re-running all migrations.
#
# Usage:
#   cd peegeeq-migrations/scripts
#   ./dev-reset-db.sh                    # Use default local database
#   ./dev-reset-db.sh custom_db_name     # Use custom database name
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
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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
echo -e "${BLUE}║       PeeGeeQ Development Database Reset                       ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}⚠️  WARNING: This will DELETE ALL DATA in the database!${NC}"
echo ""
echo "Database: ${DB_NAME}"
echo "Host:     ${DB_HOST}:${DB_PORT}"
echo "User:     ${DB_USER}"
echo ""
read -p "Are you sure you want to continue? (yes/no): " -r
echo ""

if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
    echo -e "${RED}❌ Aborted${NC}"
    exit 1
fi

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

echo -e "${BLUE}Step 2: Running migrations (clean + migrate)...${NC}"
export DB_JDBC_URL
export DB_USER
export DB_PASSWORD
export DB_CLEAN_ON_START=true

java -jar target/peegeeq-migrations.jar migrate

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Migration failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  ✓ Database reset completed successfully!                      ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "Your database is now ready for development."
echo ""
echo "Next steps:"
echo "  • Run your application: mvn exec:java -pl peegeeq-rest"
echo "  • Run tests: mvn test -Pintegration-tests"
echo ""

