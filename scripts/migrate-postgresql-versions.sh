#!/bin/bash

# PostgreSQL Version Migration Script for PeeGeeQ Project
# This script migrates hardcoded PostgreSQL versions to use the centralized constant

set -e

echo "🔄 PostgreSQL Version Migration Script"
echo "======================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Current standardized version
STANDARD_VERSION="postgres:15.13-alpine3.20"
CONSTANT_IMPORT="import dev.mars.peegeeq.test.PostgreSQLTestConstants;"
CONSTANT_USAGE="PostgreSQLTestConstants.POSTGRES_IMAGE"

echo -e "${BLUE}📋 This script will:${NC}"
echo "   1. Find all hardcoded PostgreSQL versions"
echo "   2. Replace them with PostgreSQLTestConstants.POSTGRES_IMAGE"
echo "   3. Add necessary imports"
echo "   4. Report all changes made"
echo ""

# Function to find hardcoded PostgreSQL versions
find_hardcoded_versions() {
    echo -e "${YELLOW}🔍 Scanning for hardcoded PostgreSQL versions...${NC}"
    
    # Find all Java files with hardcoded PostgreSQL versions
    HARDCODED_FILES=$(find . -name "*.java" -type f -exec grep -l 'new PostgreSQLContainer<>("postgres:' {} \; | grep -v PostgreSQLTestConstants.java)
    
    if [ -z "$HARDCODED_FILES" ]; then
        echo -e "${GREEN}✅ No hardcoded PostgreSQL versions found!${NC}"
        return 0
    fi
    
    echo -e "${RED}❌ Found hardcoded PostgreSQL versions in:${NC}"
    for file in $HARDCODED_FILES; do
        echo "   📄 $file"
        grep -n 'new PostgreSQLContainer<>("postgres:' "$file" | sed 's/^/      /'
    done
    echo ""
    
    return 1
}

# Function to migrate a single file
migrate_file() {
    local file="$1"
    local backup_file="${file}.backup"
    
    echo -e "${BLUE}🔄 Migrating: $file${NC}"
    
    # Create backup
    cp "$file" "$backup_file"
    
    # Check if import already exists
    if ! grep -q "import dev.mars.peegeeq.test.PostgreSQLTestConstants;" "$file"; then
        # Find the last import line and add our import after it
        sed -i '/^import.*$/a\
import dev.mars.peegeeq.test.PostgreSQLTestConstants;' "$file"
        echo "   ✅ Added import statement"
    fi
    
    # Replace hardcoded versions with constant
    sed -i 's/new PostgreSQLContainer<>("postgres:[^"]*")/new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)/g' "$file"
    
    # Show what changed
    if ! diff -q "$backup_file" "$file" > /dev/null; then
        echo "   ✅ Replaced hardcoded versions with constant"
        echo -e "${GREEN}   📝 Changes made:${NC}"
        diff -u "$backup_file" "$file" | grep -E '^[+-]' | grep -v '^[+-]{3}' | sed 's/^/      /'
    else
        echo "   ℹ️  No changes needed"
    fi
    
    # Remove backup file
    rm "$backup_file"
    echo ""
}

# Function to verify migration
verify_migration() {
    echo -e "${YELLOW}🔍 Verifying migration...${NC}"
    
    # Check for any remaining hardcoded versions
    REMAINING=$(find . -name "*.java" -type f -exec grep -l 'new PostgreSQLContainer<>("postgres:' {} \; | grep -v PostgreSQLTestConstants.java)
    
    if [ -z "$REMAINING" ]; then
        echo -e "${GREEN}✅ Migration successful! No hardcoded versions remaining.${NC}"
        return 0
    else
        echo -e "${RED}❌ Migration incomplete. Remaining hardcoded versions in:${NC}"
        for file in $REMAINING; do
            echo "   📄 $file"
        done
        return 1
    fi
}

# Function to show usage statistics
show_statistics() {
    echo -e "${BLUE}📊 PostgreSQL Container Usage Statistics:${NC}"
    
    # Count total PostgreSQL container usages
    TOTAL_CONTAINERS=$(find . -name "*.java" -type f -exec grep -c 'new PostgreSQLContainer<>' {} \; | awk '{sum += $1} END {print sum}')
    
    # Count constant usages
    CONSTANT_USAGES=$(find . -name "*.java" -type f -exec grep -c 'PostgreSQLTestConstants.POSTGRES_IMAGE' {} \; | awk '{sum += $1} END {print sum}')
    
    # Count hardcoded usages
    HARDCODED_USAGES=$(find . -name "*.java" -type f -exec grep -c 'new PostgreSQLContainer<>("postgres:' {} \; | awk '{sum += $1} END {print sum}')
    
    echo "   📦 Total PostgreSQL containers: ${TOTAL_CONTAINERS:-0}"
    echo "   ✅ Using constant: ${CONSTANT_USAGES:-0}"
    echo "   ❌ Hardcoded versions: ${HARDCODED_USAGES:-0}"
    
    if [ "${HARDCODED_USAGES:-0}" -eq 0 ]; then
        echo -e "${GREEN}   🎯 100% compliance achieved!${NC}"
    else
        local compliance=$((CONSTANT_USAGES * 100 / TOTAL_CONTAINERS))
        echo -e "${YELLOW}   📈 Compliance: ${compliance}%${NC}"
    fi
    echo ""
}

# Main execution
main() {
    echo -e "${BLUE}🚀 Starting PostgreSQL version migration...${NC}"
    echo ""
    
    # Show initial statistics
    show_statistics
    
    # Find hardcoded versions
    if find_hardcoded_versions; then
        echo -e "${GREEN}🎉 All PostgreSQL versions are already using the centralized constant!${NC}"
        exit 0
    fi
    
    # Ask for confirmation
    echo -e "${YELLOW}⚠️  Do you want to proceed with the migration? (y/N)${NC}"
    read -r response
    if [[ ! "$response" =~ ^[Yy]$ ]]; then
        echo "Migration cancelled."
        exit 0
    fi
    
    echo ""
    echo -e "${BLUE}🔄 Starting migration...${NC}"
    echo ""
    
    # Migrate each file
    find . -name "*.java" -type f -exec grep -l 'new PostgreSQLContainer<>("postgres:' {} \; | grep -v PostgreSQLTestConstants.java | while read -r file; do
        migrate_file "$file"
    done
    
    # Verify migration
    if verify_migration; then
        echo ""
        echo -e "${GREEN}🎉 Migration completed successfully!${NC}"
        echo ""
        show_statistics
        
        echo -e "${BLUE}📋 Next steps:${NC}"
        echo "   1. Run 'mvn compile' to verify all changes compile correctly"
        echo "   2. Run 'mvn test' to ensure all tests still pass"
        echo "   3. Commit the changes to version control"
        echo ""
        echo -e "${GREEN}✅ Your project now uses a single PostgreSQL version: ${STANDARD_VERSION}${NC}"
    else
        echo ""
        echo -e "${RED}❌ Migration failed. Please review the remaining files manually.${NC}"
        exit 1
    fi
}

# Run main function
main "$@"
