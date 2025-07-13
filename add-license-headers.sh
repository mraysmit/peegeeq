#!/bin/bash

# License Header Addition Script
# Adds Apache License 2.0 headers to Java files in the PeeGeeQ project.
#
# Usage:
#   ./add-license-headers.sh [--dry-run] [--force]
#
# Options:
#   --dry-run    Show what changes would be made without modifying files
#   --force      Update files even if they already have license headers

set -euo pipefail

# Configuration
AUTHOR_NAME="Mark Andrew Ray-Smith Cityline Ltd"
COPYRIGHT_YEAR="2025"

# Apache License 2.0 header template
LICENSE_HEADER="/*
 * Copyright $COPYRIGHT_YEAR $AUTHOR_NAME
 *
 * Licensed under the Apache License, Version 2.0 (the \"License\");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an \"AS IS\" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */"

# Parse command line arguments
DRY_RUN=false
FORCE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --force)
            FORCE=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--dry-run] [--force]"
            echo ""
            echo "Adds Apache License 2.0 headers to Java files in the PeeGeeQ project."
            echo ""
            echo "Options:"
            echo "  --dry-run    Show what changes would be made without modifying files"
            echo "  --force      Update files even if they already have license headers"
            echo "  -h, --help   Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Color output functions
print_color() {
    local color=$1
    local message=$2
    case $color in
        red) echo -e "\033[31m$message\033[0m" ;;
        green) echo -e "\033[32m$message\033[0m" ;;
        yellow) echo -e "\033[33m$message\033[0m" ;;
        blue) echo -e "\033[34m$message\033[0m" ;;
        magenta) echo -e "\033[35m$message\033[0m" ;;
        cyan) echo -e "\033[36m$message\033[0m" ;;
        white) echo -e "\033[37m$message\033[0m" ;;
        *) echo "$message" ;;
    esac
}

# Function to check if file already has license header
has_license_header() {
    local content="$1"
    echo "$content" | grep -q "Licensed under the Apache License"
}

# Function to process a single Java file
add_license_header() {
    local file_path="$1"
    
    print_color cyan "Processing: $file_path"
    
    if [[ ! -f "$file_path" ]]; then
        print_color red "Error: File not found: $file_path"
        return 1
    fi
    
    local content
    content=$(cat "$file_path")
    
    # Check if file already has license header
    if has_license_header "$content" && [[ "$FORCE" != true ]]; then
        print_color yellow "  Skipping - already has license header (use --force to override)"
        return 1
    fi
    
    # Create temporary file for processing
    local temp_file
    temp_file=$(mktemp)
    
    # Find insertion point (after package declaration)
    local insert_line=1
    local line_num=0
    
    while IFS= read -r line; do
        ((line_num++))
        local trimmed_line
        trimmed_line=$(echo "$line" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
        
        if [[ "$trimmed_line" =~ ^package[[:space:]] ]]; then
            insert_line=$((line_num + 1))
            break
        fi
    done <<< "$content"
    
    # Build new content
    {
        # Add lines up to insertion point
        if [[ $insert_line -gt 1 ]]; then
            head -n $((insert_line - 1)) "$file_path"
        fi
        
        # Add empty line after package if needed
        if [[ $insert_line -gt 1 ]]; then
            local prev_line
            prev_line=$(sed -n "$((insert_line - 1))p" "$file_path" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
            if [[ -n "$prev_line" ]]; then
                echo ""
            fi
        fi
        
        # Add license header
        echo "$LICENSE_HEADER"
        echo ""
        
        # Add remaining lines (skip existing license header if present)
        local skip_license=false
        local license_end=false
        
        tail -n +$insert_line "$file_path" | while IFS= read -r line; do
            local trimmed_line
            trimmed_line=$(echo "$line" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
            
            # Skip existing license header
            if [[ "$trimmed_line" == "/*" ]]; then
                # Check if next few lines contain copyright
                local next_lines
                next_lines=$(tail -n +$((insert_line + 1)) "$file_path" | head -5)
                if echo "$next_lines" | grep -q "Copyright.*$AUTHOR_NAME"; then
                    skip_license=true
                    continue
                fi
            fi
            
            if [[ "$skip_license" == true ]]; then
                if [[ "$trimmed_line" == "*/" ]]; then
                    skip_license=false
                    license_end=true
                fi
                continue
            fi
            
            # Skip empty lines immediately after removed license
            if [[ "$license_end" == true && -z "$trimmed_line" ]]; then
                license_end=false
                continue
            fi
            
            echo "$line"
        done
    } > "$temp_file"
    
    # Apply changes or show what would be done
    if [[ "$DRY_RUN" == true ]]; then
        print_color green "  Would add license header to: $file_path"
    else
        mv "$temp_file" "$file_path"
        print_color green "  Added license header to: $file_path"
    fi
    
    rm -f "$temp_file"
    return 0
}

# Main execution
print_color magenta "License Header Addition Script"
print_color magenta "Apache License 2.0"
print_color magenta "============================="
echo ""

if [[ "$DRY_RUN" == true ]]; then
    print_color yellow "DRY RUN MODE - No files will be modified"
    echo ""
fi

if [[ "$FORCE" == true ]]; then
    print_color yellow "FORCE MODE - Will update files with existing license headers"
    echo ""
fi

# Find all Java files
print_color blue "Scanning for Java files..."
mapfile -t java_files < <(find . -name "*.java" -type f ! -path "*/target/*")

print_color blue "Found ${#java_files[@]} Java files"
echo ""

# Process each file
updated_count=0
skipped_count=0

for file in "${java_files[@]}"; do
    if add_license_header "$file"; then
        ((updated_count++))
    else
        ((skipped_count++))
    fi
done

# Summary
echo ""
print_color magenta "Summary:"
print_color white "  Files processed: ${#java_files[@]}"
print_color green "  Files updated: $updated_count"
print_color yellow "  Files skipped: $skipped_count"

if [[ "$DRY_RUN" == true ]]; then
    echo ""
    print_color cyan "Run without --dry-run to apply changes"
fi
