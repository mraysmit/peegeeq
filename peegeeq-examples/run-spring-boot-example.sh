#!/bin/bash

# PeeGeeQ Spring Boot Outbox Pattern Example Runner
# This script demonstrates how to run the complete Spring Boot integration example

set -e

echo "=========================================="
echo "PeeGeeQ Spring Boot Outbox Pattern Example"
echo "=========================================="
echo ""

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "❌ Java is not installed or not in PATH"
    echo "   Please install Java 17+ to run this example"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ Java 17+ is required, but found Java $JAVA_VERSION"
    echo "   Please upgrade to Java 17 or later"
    exit 1
fi

echo "✅ Java $JAVA_VERSION detected"

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is not installed or not in PATH"
    echo "   Please install Maven to build and run this example"
    exit 1
fi

echo "✅ Maven detected"

# Set default database configuration
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-peegeeq_example}
DB_USERNAME=${DB_USERNAME:-peegeeq_user}
DB_PASSWORD=${DB_PASSWORD:-peegeeq_password}

echo ""
echo "Database Configuration:"
echo "  Host: $DB_HOST"
echo "  Port: $DB_PORT"
echo "  Database: $DB_NAME"
echo "  Username: $DB_USERNAME"
echo "  Password: [HIDDEN]"
echo ""

# Check if PostgreSQL is accessible (optional check)
if command -v pg_isready &> /dev/null; then
    if pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" &> /dev/null; then
        echo "✅ PostgreSQL is accessible"
    else
        echo "⚠️  PostgreSQL connection check failed"
        echo "   Make sure PostgreSQL is running and accessible"
        echo "   You can continue, but the application may fail to start"
    fi
else
    echo "ℹ️  PostgreSQL client not found, skipping connection check"
fi

echo ""
echo "Building and running Spring Boot example..."
echo ""

# Export environment variables for Maven
export DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD

# Run the Spring Boot application
mvn spring-boot:run \
    -pl peegeeq-examples \
    -Dspring-boot.run.main-class="dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication" \
    -Dspring-boot.run.profiles=springboot \
    -Dspring-boot.run.jvmArguments="-Dpeegeeq.database.host=$DB_HOST -Dpeegeeq.database.port=$DB_PORT -Dpeegeeq.database.name=$DB_NAME -Dpeegeeq.database.username=$DB_USERNAME -Dpeegeeq.database.password=$DB_PASSWORD"

echo ""
echo "Spring Boot example finished."
