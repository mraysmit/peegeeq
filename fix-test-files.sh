#!/bin/bash

# Script to fix malformed test files with clearSystemProperties insertions

echo "Fixing malformed test files..."

# Directory containing the test files
TEST_DIR="peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue"

# List of files to fix
FILES=(
    "SimpleNativeQueueTest.java"
    "EnhancedErrorHandlingDemoTest.java"
    "ConsumerGroupLoadBalancingDemoTest.java"
    "DistributedSystemResilienceDemoTest.java"
    "EnterpriseIntegrationDemoTest.java"
    "EventSourcingCQRSDemoTest.java"
    "MicroservicesCommunicationDemoTest.java"
    "NativeQueueFeatureTest.java"
    "SystemPropertiesConfigurationDemoTest.java"
)

for file in "${FILES[@]}"; do
    filepath="$TEST_DIR/$file"
    if [ -f "$filepath" ]; then
        echo "Processing $file..."
        
        # Create backup
        cp "$filepath" "$filepath.bak"
        
        # Fix patterns:
        # 1. Remove lines with just "clearSystemProperties();"
        # 2. Fix malformed string literals like: "text {    // Clear system properties\n    clearSystemProperties();\n}"
        # 3. Fix enum declarations like: "enum State { CLOSED, OPEN, HALF_OPEN     // Clear system properties\n     clearSystemProperties();\n }"
        
        # Use sed to remove the malformed insertions
        sed -i 's/    \/\/ Clear system properties//g' "$filepath"
        sed -i '/^    clearSystemProperties();$/d' "$filepath"
        sed -i '/^        clearSystemProperties();$/d' "$filepath"
        sed -i '/^            clearSystemProperties();$/d' "$filepath"
        sed -i '/^                clearSystemProperties();$/d' "$filepath"
        sed -i '/^                    clearSystemProperties();$/d' "$filepath"
        sed -i '/^     clearSystemProperties();$/d' "$filepath"
        sed -i 's/{    \/\/ Clear system properties/{/g' "$filepath"
        sed -i 's/     \/\/ Clear system properties//g' "$filepath"
        
        echo "  ✓ Fixed $file"
    else
        echo "  ✗ File not found: $filepath"
    fi
done

echo ""
echo "Done! Backup files created with .bak extension"
echo "To restore backups if needed: for f in $TEST_DIR/*.bak; do mv \"\$f\" \"\${f%.bak}\"; done"

