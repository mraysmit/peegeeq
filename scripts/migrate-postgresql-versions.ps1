# PostgreSQL Version Migration Script for PeeGeeQ Project (PowerShell)
# This script migrates hardcoded PostgreSQL versions to use the centralized constant

param(
    [switch]$DryRun = $false,
    [switch]$Force = $false
)

Write-Host "🔄 PostgreSQL Version Migration Script (PowerShell)" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host ""

# Configuration
$STANDARD_VERSION = "postgres:15.13-alpine3.20"
$CONSTANT_IMPORT = "import dev.mars.peegeeq.test.PostgreSQLTestConstants;"
$CONSTANT_USAGE = "PostgreSQLTestConstants.POSTGRES_IMAGE"

Write-Host "📋 This script will:" -ForegroundColor Blue
Write-Host "   1. Find all hardcoded PostgreSQL versions"
Write-Host "   2. Replace them with PostgreSQLTestConstants.POSTGRES_IMAGE"
Write-Host "   3. Add necessary imports"
Write-Host "   4. Report all changes made"
Write-Host ""

# Function to find hardcoded PostgreSQL versions
function Find-HardcodedVersions {
    Write-Host "🔍 Scanning for hardcoded PostgreSQL versions..." -ForegroundColor Yellow
    
    $hardcodedFiles = @()
    $javaFiles = Get-ChildItem -Path . -Recurse -Filter "*.java" | Where-Object { $_.Name -ne "PostgreSQLTestConstants.java" }
    
    foreach ($file in $javaFiles) {
        $content = Get-Content $file.FullName -Raw
        if ($content -match 'new PostgreSQLContainer<>\("postgres:') {
            $hardcodedFiles += $file
        }
    }
    
    if ($hardcodedFiles.Count -eq 0) {
        Write-Host "✅ No hardcoded PostgreSQL versions found!" -ForegroundColor Green
        return $false
    }
    
    Write-Host "❌ Found hardcoded PostgreSQL versions in:" -ForegroundColor Red
    foreach ($file in $hardcodedFiles) {
        Write-Host "   📄 $($file.FullName)" -ForegroundColor Red
        $lines = Get-Content $file.FullName
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'new PostgreSQLContainer<>\("postgres:') {
                Write-Host "      Line $($i + 1): $($lines[$i].Trim())" -ForegroundColor Gray
            }
        }
    }
    Write-Host ""
    
    return $hardcodedFiles
}

# Function to migrate a single file
function Migrate-File {
    param(
        [System.IO.FileInfo]$File
    )
    
    Write-Host "🔄 Migrating: $($File.FullName)" -ForegroundColor Blue
    
    # Read file content
    $content = Get-Content $File.FullName -Raw
    $originalContent = $content
    
    # Check if import already exists
    if ($content -notmatch [regex]::Escape($CONSTANT_IMPORT)) {
        # Find the last import line and add our import after it
        $lines = Get-Content $File.FullName
        $lastImportIndex = -1
        
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^import\s+') {
                $lastImportIndex = $i
            }
        }
        
        if ($lastImportIndex -ge 0) {
            $lines = $lines[0..$lastImportIndex] + $CONSTANT_IMPORT + $lines[($lastImportIndex + 1)..($lines.Count - 1)]
            $content = $lines -join "`r`n"
            Write-Host "   ✅ Added import statement" -ForegroundColor Green
        }
    }
    
    # Replace hardcoded versions with constant
    $pattern = 'new PostgreSQLContainer<>\("postgres:[^"]*"\)'
    $replacement = "new PostgreSQLContainer<>($CONSTANT_USAGE)"
    
    if ($content -match $pattern) {
        $content = $content -replace $pattern, $replacement
        Write-Host "   ✅ Replaced hardcoded versions with constant" -ForegroundColor Green
        
        # Show changes
        $originalLines = $originalContent -split "`r?`n"
        $newLines = $content -split "`r?`n"
        
        Write-Host "   📝 Changes made:" -ForegroundColor Green
        for ($i = 0; $i -lt [Math]::Max($originalLines.Count, $newLines.Count); $i++) {
            $oldLine = if ($i -lt $originalLines.Count) { $originalLines[$i] } else { "" }
            $newLine = if ($i -lt $newLines.Count) { $newLines[$i] } else { "" }
            
            if ($oldLine -ne $newLine) {
                if ($oldLine -match 'PostgreSQLContainer') {
                    Write-Host "      - $oldLine" -ForegroundColor Red
                    Write-Host "      + $newLine" -ForegroundColor Green
                }
            }
        }
    } else {
        Write-Host "   ℹ️  No PostgreSQL container changes needed" -ForegroundColor Gray
    }
    
    # Write the modified content back to file
    if (-not $DryRun) {
        Set-Content -Path $File.FullName -Value $content -NoNewline
    }
    
    Write-Host ""
}

# Function to verify migration
function Verify-Migration {
    Write-Host "🔍 Verifying migration..." -ForegroundColor Yellow
    
    $remaining = Find-HardcodedVersions
    
    if (-not $remaining) {
        Write-Host "✅ Migration successful! No hardcoded versions remaining." -ForegroundColor Green
        return $true
    } else {
        Write-Host "❌ Migration incomplete. Remaining hardcoded versions found." -ForegroundColor Red
        return $false
    }
}

# Function to show usage statistics
function Show-Statistics {
    Write-Host "📊 PostgreSQL Container Usage Statistics:" -ForegroundColor Blue
    
    $javaFiles = Get-ChildItem -Path . -Recurse -Filter "*.java"
    $totalContainers = 0
    $constantUsages = 0
    $hardcodedUsages = 0
    
    foreach ($file in $javaFiles) {
        $content = Get-Content $file.FullName -Raw
        $totalContainers += ([regex]::Matches($content, 'new PostgreSQLContainer<>')).Count
        $constantUsages += ([regex]::Matches($content, [regex]::Escape($CONSTANT_USAGE))).Count
        $hardcodedUsages += ([regex]::Matches($content, 'new PostgreSQLContainer<>\("postgres:')).Count
    }
    
    Write-Host "   📦 Total PostgreSQL containers: $totalContainers"
    Write-Host "   ✅ Using constant: $constantUsages"
    Write-Host "   ❌ Hardcoded versions: $hardcodedUsages"
    
    if ($hardcodedUsages -eq 0) {
        Write-Host "   🎯 100% compliance achieved!" -ForegroundColor Green
    } else {
        $compliance = if ($totalContainers -gt 0) { [Math]::Round(($constantUsages * 100) / $totalContainers, 1) } else { 0 }
        Write-Host "   📈 Compliance: $compliance%" -ForegroundColor Yellow
    }
    Write-Host ""
}

# Main execution
function Main {
    Write-Host "🚀 Starting PostgreSQL version migration..." -ForegroundColor Blue
    Write-Host ""
    
    if ($DryRun) {
        Write-Host "🔍 DRY RUN MODE - No files will be modified" -ForegroundColor Yellow
        Write-Host ""
    }
    
    # Show initial statistics
    Show-Statistics
    
    # Find hardcoded versions
    $hardcodedFiles = Find-HardcodedVersions
    if (-not $hardcodedFiles) {
        Write-Host "🎉 All PostgreSQL versions are already using the centralized constant!" -ForegroundColor Green
        return
    }
    
    # Ask for confirmation unless forced
    if (-not $Force -and -not $DryRun) {
        $response = Read-Host "⚠️  Do you want to proceed with the migration? (y/N)"
        if ($response -notmatch '^[Yy]$') {
            Write-Host "Migration cancelled."
            return
        }
    }
    
    Write-Host ""
    Write-Host "🔄 Starting migration..." -ForegroundColor Blue
    Write-Host ""
    
    # Migrate each file
    foreach ($file in $hardcodedFiles) {
        Migrate-File -File $file
    }
    
    # Verify migration (only if not dry run)
    if (-not $DryRun) {
        if (Verify-Migration) {
            Write-Host ""
            Write-Host "🎉 Migration completed successfully!" -ForegroundColor Green
            Write-Host ""
            Show-Statistics
            
            Write-Host "📋 Next steps:" -ForegroundColor Blue
            Write-Host "   1. Run 'mvn compile' to verify all changes compile correctly"
            Write-Host "   2. Run 'mvn test' to ensure all tests still pass"
            Write-Host "   3. Commit the changes to version control"
            Write-Host ""
            Write-Host "✅ Your project now uses a single PostgreSQL version: $STANDARD_VERSION" -ForegroundColor Green
        } else {
            Write-Host ""
            Write-Host "❌ Migration failed. Please review the remaining files manually." -ForegroundColor Red
        }
    } else {
        Write-Host "🔍 DRY RUN COMPLETE - Review the changes above" -ForegroundColor Yellow
        Write-Host "   Run without -DryRun to apply changes" -ForegroundColor Yellow
    }
}

# Run main function
Main
