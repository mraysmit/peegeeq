# Script Usage Guide

This document provides comprehensive usage instructions for both PowerShell and Shell script versions of the Java header management tools.

## Available Scripts

### PowerShell Scripts (Windows)
- `update-java-headers.ps1` - Updates JavaDoc headers with author information
- `add-license-headers.ps1` - Adds Apache License 2.0 headers to Java files

### Shell Scripts (Linux/macOS/WSL)
- `update-java-headers.sh` - Updates JavaDoc headers with author information
- `add-license-headers.sh` - Adds Apache License 2.0 headers to Java files

## PowerShell Scripts Usage

### Prerequisites
- PowerShell 5.1 or later (Windows PowerShell or PowerShell Core)
- Execution policy allowing script execution

### Setting Execution Policy (if needed)
```powershell
# For current user only (recommended)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# Or for current session only
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process
```

### update-java-headers.ps1

**Purpose:** Updates JavaDoc comment headers with author information and project details.

**Syntax:**
```powershell
.\update-java-headers.ps1 [-DryRun] [-Verbose]
```

**Parameters:**
- `-DryRun` - Preview changes without modifying files
- `-Verbose` - Show detailed processing information

**Examples:**
```powershell
# Preview what would be changed
.\update-java-headers.ps1 -DryRun

# Update headers with verbose output
.\update-java-headers.ps1 -Verbose

# Update headers silently
.\update-java-headers.ps1

# Combine options
.\update-java-headers.ps1 -DryRun -Verbose
```

### add-license-headers.ps1

**Purpose:** Adds Apache License 2.0 headers to Java source files.

**Syntax:**
```powershell
.\add-license-headers.ps1 [-DryRun] [-Force]
```

**Parameters:**
- `-DryRun` - Preview changes without modifying files
- `-Force` - Update files even if they already have license headers

**Examples:**
```powershell
# Preview what would be changed
.\add-license-headers.ps1 -DryRun

# Add license headers
.\add-license-headers.ps1

# Force update existing headers
.\add-license-headers.ps1 -Force

# Preview force update
.\add-license-headers.ps1 -DryRun -Force
```

## Shell Scripts Usage

### Prerequisites
- Bash shell (Linux, macOS, WSL, Git Bash)
- Standard Unix utilities (find, grep, sed, head, tail)

### Making Scripts Executable
```bash
# Make scripts executable
chmod +x update-java-headers.sh add-license-headers.sh

# Verify permissions
ls -la *.sh
```

### update-java-headers.sh

**Purpose:** Updates JavaDoc comment headers with author information and project details.

**Syntax:**
```bash
./update-java-headers.sh [--dry-run] [--verbose] [--help]
```

**Options:**
- `--dry-run` - Preview changes without modifying files
- `--verbose` - Show detailed processing information
- `--help` - Display help message

**Examples:**
```bash
# Preview what would be changed
./update-java-headers.sh --dry-run

# Update headers with verbose output
./update-java-headers.sh --verbose

# Update headers silently
./update-java-headers.sh

# Combine options
./update-java-headers.sh --dry-run --verbose

# Show help
./update-java-headers.sh --help
```

### add-license-headers.sh

**Purpose:** Adds Apache License 2.0 headers to Java source files.

**Syntax:**
```bash
./add-license-headers.sh [--dry-run] [--force] [--help]
```

**Options:**
- `--dry-run` - Preview changes without modifying files
- `--force` - Update files even if they already have license headers
- `--help` - Display help message

**Examples:**
```bash
# Preview what would be changed
./add-license-headers.sh --dry-run

# Add license headers
./add-license-headers.sh

# Force update existing headers
./add-license-headers.sh --force

# Preview force update
./add-license-headers.sh --dry-run --force

# Show help
./add-license-headers.sh --help
```

## Cross-Platform Usage

### Windows
```powershell
# Use PowerShell scripts
.\update-java-headers.ps1 -DryRun
.\add-license-headers.ps1 -DryRun

# Or use shell scripts with WSL/Git Bash
bash ./update-java-headers.sh --dry-run
bash ./add-license-headers.sh --dry-run
```

### Linux/macOS
```bash
# Use shell scripts directly
./update-java-headers.sh --dry-run
./add-license-headers.sh --dry-run

# Or use PowerShell Core (if installed)
pwsh ./update-java-headers.ps1 -DryRun
pwsh ./add-license-headers.ps1 -DryRun
```

## Common Workflows

### Initial Setup (New Project)
```bash
# 1. Add license headers to all files
./add-license-headers.sh --dry-run    # Preview
./add-license-headers.sh              # Apply

# 2. Update JavaDoc headers
./update-java-headers.sh --dry-run    # Preview
./update-java-headers.sh              # Apply
```

### Adding New Files
```bash
# Add headers to new files only
./add-license-headers.sh --dry-run    # Preview (skips existing)
./add-license-headers.sh              # Apply to new files

./update-java-headers.sh --dry-run    # Preview (skips existing)
./update-java-headers.sh              # Apply to new files
```

### Updating Existing Headers
```bash
# Force update all headers (e.g., year change)
./add-license-headers.sh --force --dry-run    # Preview
./add-license-headers.sh --force              # Apply

./update-java-headers.sh --dry-run            # Preview
./update-java-headers.sh                      # Apply
```

## Output Examples

### Successful Run
```
License Header Addition Script
Apache License 2.0
=============================

Scanning for Java files...
Found 88 Java files

Processing: ./peegeeq-api/src/main/java/dev/mars/peegeeq/api/Message.java
  Added license header to: ./peegeeq-api/src/main/java/dev/mars/peegeeq/api/Message.java

Summary:
  Files processed: 88
  Files updated: 45
  Files skipped: 43
```

### Dry Run Output
```
DRY RUN MODE - No files will be modified

Processing: ./peegeeq-api/src/main/java/dev/mars/peegeeq/api/Message.java
  Would add license header to: ./peegeeq-api/src/main/java/dev/mars/peegeeq/api/Message.java

Run without --dry-run to apply changes
```

## Troubleshooting

### PowerShell Issues

**Execution Policy Error:**
```powershell
# Solution: Set execution policy
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

**Script Not Found:**
```powershell
# Solution: Use full path or ensure you're in the correct directory
Get-Location
.\update-java-headers.ps1
```

### Shell Script Issues

**Permission Denied:**
```bash
# Solution: Make script executable
chmod +x update-java-headers.sh add-license-headers.sh
```

**Command Not Found:**
```bash
# Solution: Use ./ prefix or add to PATH
./update-java-headers.sh --help
```

**Line Ending Issues (Windows):**
```bash
# Solution: Convert line endings
dos2unix update-java-headers.sh add-license-headers.sh
```

## Configuration

Both script versions use the same configuration constants:

```
AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
COPYRIGHT_YEAR = "2025" (or current year)
PROJECT_NAME = "PeeGeeQ"
```

To modify these values, edit the scripts directly at the top of each file.

## Integration with Build Systems

### Maven Integration
```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <id>check-headers</id>
            <phase>validate</phase>
            <goals>
                <goal>exec</goal>
            </goals>
            <configuration>
                <executable>bash</executable>
                <arguments>
                    <argument>./add-license-headers.sh</argument>
                    <argument>--dry-run</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### CI/CD Integration
```yaml
# GitHub Actions example
- name: Check License Headers
  run: |
    chmod +x add-license-headers.sh
    ./add-license-headers.sh --dry-run
    if [ $? -ne 0 ]; then
      echo "Missing license headers detected"
      exit 1
    fi
```

## Best Practices

1. **Always use dry-run first** to preview changes
2. **Backup your code** before running scripts (use version control)
3. **Test on a small subset** of files first
4. **Review changes** after running scripts
5. **Run scripts from project root** directory
6. **Use verbose mode** for troubleshooting
7. **Keep scripts updated** with project requirements

## Support

For issues with the scripts:
1. Check this documentation
2. Run with `--help` or `-Help` for usage information
3. Use `--dry-run` to preview changes safely
4. Check file permissions and execution policies
5. Verify you're in the correct directory
