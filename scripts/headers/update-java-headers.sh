#!/bin/bash

# Java Header Update Script
# Updates comment headers in Java class files with author information.
#
# Usage:
#   ./update-java-headers.sh [--dry-run] [--verbose]
#
# Options:
#   --dry-run    Show what changes would be made without modifying files
#   --verbose    Enable verbose output showing detailed processing information

set -euo pipefail

# Configuration
AUTHOR_NAME="Mark Andrew Ray-Smith Cityline Ltd"
COPYRIGHT_YEAR=$(date +%Y)
PROJECT_NAME="PeeGeeQ"

# Parse command line arguments
DRY_RUN=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--dry-run] [--verbose]"
            echo ""
            echo "Updates comment headers in Java class files with author information."
            echo ""
            echo "Options:"
            echo "  --dry-run    Show what changes would be made without modifying files"
            echo "  --verbose    Enable verbose output"
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

# Function to determine the type of Java file (class, interface, enum, annotation)
get_java_file_type() {
    local content="$1"
    
    if echo "$content" | grep -q 'public[[:space:]]\+@interface[[:space:]]\+[[:alnum:]_]\+'; then
        echo "annotation"
    elif echo "$content" | grep -q 'public[[:space:]]\+interface[[:space:]]\+[[:alnum:]_]\+'; then
        echo "interface"
    elif echo "$content" | grep -q 'public[[:space:]]\+enum[[:space:]]\+[[:alnum:]_]\+'; then
        echo "enum"
    elif echo "$content" | grep -q 'public[[:space:]]\+class[[:space:]]\+[[:alnum:]_]\+'; then
        echo "class"
    elif echo "$content" | grep -q '[[:space:]]class[[:space:]]\+[[:alnum:]_]\+'; then
        echo "class"
    elif echo "$content" | grep -q '[[:space:]]interface[[:space:]]\+[[:alnum:]_]\+'; then
        echo "interface"
    elif echo "$content" | grep -q '[[:space:]]enum[[:space:]]\+[[:alnum:]_]\+'; then
        echo "enum"
    else
        echo "class"  # Default fallback
    fi
}

# Function to extract existing JavaDoc description
get_existing_description() {
    local content="$1"
    
    # Look for existing JavaDoc comment
    local description=$(echo "$content" | sed -n '/\/\*\*/,/\*\//p' | sed -n '2p' | sed 's/^[[:space:]]*\*[[:space:]]*//' | sed 's/[[:space:]]*$//')
    
    if [[ -n "$description" && "$description" != "*/" ]]; then
        echo "$description"
    fi
}

# Function to extract class/interface name from file
get_java_class_name() {
    local content="$1"
    
    # Try different patterns in order of specificity
    local class_name
    
    class_name=$(echo "$content" | grep -o 'public[[:space:]]\+@interface[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'public[[:space:]]\+interface[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'public[[:space:]]\+enum[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'public[[:space:]]\+class[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o '@interface[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'interface[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'enum[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    class_name=$(echo "$content" | grep -o 'class[[:space:]]\+[[:alnum:]_]\+' | awk '{print $NF}' | head -1)
    [[ -n "$class_name" ]] && { echo "$class_name"; return; }
    
    echo "Unknown"
}

# Function to generate the new header comment
generate_java_header() {
    local file_type="$1"
    local class_name="$2"
    local existing_description="$3"
    local file_path="$4"
    
    local relative_path="${file_path#$(pwd)/}"
    
    # Determine appropriate description based on file type
    local default_description
    case "$file_type" in
        interface) default_description="Interface defining contracts for $class_name functionality." ;;
        enum) default_description="Enumeration defining $class_name constants and values." ;;
        annotation) default_description="Annotation for $class_name metadata and configuration." ;;
        *) default_description="Implementation of $class_name functionality." ;;
    esac
    
    local description="${existing_description:-$default_description}"
    local current_date=$(date +%Y-%m-%d)
    
    cat << EOF
/**
 * $description
 * 
 * This $file_type is part of the $PROJECT_NAME message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author $AUTHOR_NAME
 * @since $current_date
 * @version 1.0
 */
EOF
}

# Function to process a single Java file
update_java_file() {
    local file_path="$1"
    
    if [[ "$VERBOSE" == true ]]; then
        print_color cyan "Processing: $file_path"
    fi
    
    if [[ ! -f "$file_path" ]]; then
        print_color red "Error: File not found: $file_path"
        return 1
    fi
    
    local content
    content=$(cat "$file_path")
    
    # Skip if file already has our author tag
    if echo "$content" | grep -q "author.*$AUTHOR_NAME"; then
        if [[ "$VERBOSE" == true ]]; then
            print_color yellow "  Skipping - already has author tag"
        fi
        return 1
    fi
    
    # Determine file type and extract information
    local file_type
    file_type=$(get_java_file_type "$content")
    
    local class_name
    class_name=$(get_java_class_name "$content")
    
    local existing_description
    existing_description=$(get_existing_description "$content")
    
    # Generate new header
    local new_header
    new_header=$(generate_java_header "$file_type" "$class_name" "$existing_description" "$file_path")
    
    # Create temporary file for processing
    local temp_file
    temp_file=$(mktemp)
    
    # Find insertion point and build new content
    local in_imports=false
    local insert_line=0
    local line_num=0
    
    while IFS= read -r line; do
        ((line_num++))
        local trimmed_line
        trimmed_line=$(echo "$line" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
        
        # Skip package declaration
        if [[ "$trimmed_line" =~ ^package[[:space:]] ]]; then
            continue
        fi
        
        # Track import section
        if [[ "$trimmed_line" =~ ^import[[:space:]] ]]; then
            in_imports=true
            continue
        fi
        
        # If we were in imports and hit a non-import, non-empty line
        if [[ "$in_imports" == true && -n "$trimmed_line" && ! "$trimmed_line" =~ ^import[[:space:]] ]]; then
            insert_line=$line_num
            break
        fi
        
        # If no imports, look for class/interface declaration
        if [[ "$in_imports" == false && -n "$trimmed_line" && ! "$trimmed_line" =~ ^package[[:space:]] ]]; then
            if [[ "$trimmed_line" =~ (public[[:space:]]+)?(class|interface|enum|@interface)[[:space:]] || "$trimmed_line" =~ ^/\*\* ]]; then
                insert_line=$line_num
                break
            fi
        fi
    done <<< "$content"
    
    if [[ $insert_line -eq 0 ]]; then
        print_color red "Could not find insertion point in $file_path"
        rm -f "$temp_file"
        return 1
    fi
    
    # Build new content
    {
        # Add lines up to insertion point
        head -n $((insert_line - 1)) "$file_path"
        
        # Add empty line if needed
        if [[ $insert_line -gt 1 ]]; then
            local prev_line
            prev_line=$(sed -n "$((insert_line - 1))p" "$file_path" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
            if [[ -n "$prev_line" ]]; then
                echo ""
            fi
        fi
        
        # Add new header
        echo "$new_header"
        
        # Skip existing JavaDoc and add remaining lines
        tail -n +$insert_line "$file_path"
    } > "$temp_file"
    
    # Apply changes or show what would be done
    if [[ "$DRY_RUN" == true ]]; then
        print_color green "  Would update: $file_path"
        print_color white "    File type: $file_type"
        print_color white "    Class name: $class_name"
        if [[ -n "$existing_description" ]]; then
            print_color white "    Existing description: $existing_description"
        fi
    else
        mv "$temp_file" "$file_path"
        print_color green "  Updated: $file_path"
    fi
    
    rm -f "$temp_file"
    return 0
}

# Main execution
print_color magenta "Java Header Update Script"
print_color magenta "Author: $AUTHOR_NAME"
print_color magenta "========================="
echo ""

if [[ "$DRY_RUN" == true ]]; then
    print_color yellow "DRY RUN MODE - No files will be modified"
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
    if update_java_file "$file"; then
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
