#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Updates comment headers in Java class files with author information.

.DESCRIPTION
    This script scans all Java files in the PeeGeeQ project and updates their comment headers
    to include proper copyright notices and author tags with "Mark Andrew Ray-Smith Cityline Ltd".

.PARAMETER DryRun
    If specified, shows what changes would be made without actually modifying files.

.PARAMETER Verbose
    Enables verbose output showing detailed processing information.

.EXAMPLE
    .\update-java-headers.ps1
    Updates all Java files with new headers.

.EXAMPLE
    .\update-java-headers.ps1 -DryRun
    Shows what changes would be made without modifying files.
#>

param(
    [switch]$DryRun,
    [switch]$Verbose
)

# Configuration
$AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
$COPYRIGHT_YEAR = (Get-Date).Year
$PROJECT_NAME = "PeeGeeQ"

# Function to determine the type of Java file (class, interface, enum, annotation)
function Get-JavaFileType {
    param([string]$Content)
    
    if ($Content -match '\bpublic\s+@interface\s+\w+') { return "annotation" }
    if ($Content -match '\bpublic\s+interface\s+\w+') { return "interface" }
    if ($Content -match '\bpublic\s+enum\s+\w+') { return "enum" }
    if ($Content -match '\bpublic\s+class\s+\w+') { return "class" }
    if ($Content -match '\bclass\s+\w+') { return "class" }
    if ($Content -match '\binterface\s+\w+') { return "interface" }
    if ($Content -match '\benum\s+\w+') { return "enum" }
    
    return "class" # Default fallback
}

# Function to extract existing JavaDoc description
function Get-ExistingDescription {
    param([string]$Content)
    
    # Look for existing JavaDoc comment
    if ($Content -match '/\*\*\s*\n\s*\*\s*([^\n]+)') {
        $description = $matches[1].Trim()
        # Clean up common patterns
        $description = $description -replace '^\*\s*', ''
        $description = $description -replace '\s*\*/$', ''
        return $description
    }
    
    return $null
}

# Function to extract class/interface name from file
function Get-JavaClassName {
    param([string]$Content)

    # Look for class/interface/enum/@interface declarations with better pattern matching
    if ($Content -match '\bpublic\s+@interface\s+(\w+)') { return $matches[1] }
    if ($Content -match '\bpublic\s+interface\s+(\w+)') { return $matches[1] }
    if ($Content -match '\bpublic\s+enum\s+(\w+)') { return $matches[1] }
    if ($Content -match '\bpublic\s+class\s+(\w+)') { return $matches[1] }
    if ($Content -match '\b@interface\s+(\w+)') { return $matches[1] }
    if ($Content -match '\binterface\s+(\w+)') { return $matches[1] }
    if ($Content -match '\benum\s+(\w+)') { return $matches[1] }
    if ($Content -match '\bclass\s+(\w+)') { return $matches[1] }

    return "Unknown"
}

# Function to generate the new header comment
function New-JavaHeader {
    param(
        [string]$FileType,
        [string]$ClassName,
        [string]$ExistingDescription,
        [string]$FilePath
    )

    $relativePath = $FilePath -replace [regex]::Escape((Get-Location).Path + "\"), ""

    # Determine appropriate description based on file type
    $defaultDescription = switch ($FileType) {
        "interface" { "Interface defining contracts for $ClassName functionality." }
        "enum" { "Enumeration defining $ClassName constants and values." }
        "annotation" { "Annotation for $ClassName metadata and configuration." }
        default { "Implementation of $ClassName functionality." }
    }

    $description = if ($ExistingDescription) { $ExistingDescription } else { $defaultDescription }

    $header = @"
/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * $description
 *
 * This $FileType is part of the $PROJECT_NAME message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author $AUTHOR_NAME
 * @since $(Get-Date -Format "yyyy-MM-dd")
 * @version 1.0
 */
"@

    return $header
}

# Function to process a single Java file
function Update-JavaFile {
    param(
        [string]$FilePath,
        [switch]$DryRun
    )
    
    if ($Verbose) {
        Write-Host "Processing: $FilePath" -ForegroundColor Cyan
    }
    
    try {
        $content = Get-Content -Path $FilePath -Raw -Encoding UTF8
        
        # Skip if file already has our author tag or license header
        if ($content -match "author.*$([regex]::Escape($AUTHOR_NAME))" -or
            $content -match "Licensed under the Apache License") {
            if ($Verbose) {
                Write-Host "  Skipping - already has author tag or license header" -ForegroundColor Yellow
            }
            return $false
        }
        
        # Determine file type and extract information
        $fileType = Get-JavaFileType -Content $content
        $className = Get-JavaClassName -Content $content
        $existingDescription = Get-ExistingDescription -Content $content
        
        # Generate new header
        $newHeader = New-JavaHeader -FileType $fileType -ClassName $className -ExistingDescription $existingDescription -FilePath $FilePath
        
        # Find insertion point (after package and imports, before class declaration)
        $lines = $content -split "`r?`n"
        $insertIndex = -1
        $inImports = $false
        
        for ($i = 0; $i -lt $lines.Length; $i++) {
            $line = $lines[$i].Trim()
            
            # Skip package declaration
            if ($line -match '^package\s+') {
                continue
            }
            
            # Track import section
            if ($line -match '^import\s+') {
                $inImports = $true
                continue
            }
            
            # If we were in imports and hit a non-import, non-empty line
            if ($inImports -and $line -ne "" -and -not ($line -match '^import\s+')) {
                $insertIndex = $i
                break
            }
            
            # If no imports, look for class/interface declaration
            if (-not $inImports -and $line -ne "" -and -not ($line -match '^package\s+') -and 
                ($line -match '\b(?:public\s+)?(?:class|interface|enum|@interface)\s+' -or $line -match '^/\*\*')) {
                $insertIndex = $i
                break
            }
        }
        
        if ($insertIndex -eq -1) {
            Write-Warning "Could not find insertion point in $FilePath"
            return $false
        }
        
        # Remove existing JavaDoc if present
        $startRemove = -1
        $endRemove = -1
        
        # Look for existing JavaDoc comment before the class declaration
        for ($i = $insertIndex; $i -lt $lines.Length; $i++) {
            $line = $lines[$i].Trim()
            if ($line -eq "/**") {
                $startRemove = $i
            }
            if ($startRemove -ne -1 -and $line -eq "*/") {
                $endRemove = $i
                break
            }
            if ($line -match '\b(?:public\s+)?(?:class|interface|enum|@interface)\s+') {
                break
            }
        }
        
        # Build new content
        $newLines = @()
        
        # Add lines up to insertion point
        $newLines += $lines[0..($insertIndex-1)]
        
        # Add empty line if needed
        if ($insertIndex -gt 0 -and $lines[$insertIndex-1].Trim() -ne "") {
            $newLines += ""
        }
        
        # Add new header
        $newLines += $newHeader -split "`r?`n"
        
        # Skip existing JavaDoc if found
        $skipTo = if ($endRemove -ne -1) { $endRemove + 1 } else { $insertIndex }
        
        # Add remaining lines
        if ($skipTo -lt $lines.Length) {
            $newLines += $lines[$skipTo..($lines.Length-1)]
        }
        
        # Write the updated content
        if ($DryRun) {
            Write-Host "  Would update: $FilePath" -ForegroundColor Green
            Write-Host "    File type: $fileType" -ForegroundColor Gray
            Write-Host "    Class name: $className" -ForegroundColor Gray
            if ($existingDescription) {
                Write-Host "    Existing description: $existingDescription" -ForegroundColor Gray
            }
        } else {
            $newContent = $newLines -join "`n"
            Set-Content -Path $FilePath -Value $newContent -Encoding UTF8 -NoNewline
            Write-Host "  Updated: $FilePath" -ForegroundColor Green
        }
        
        return $true
        
    } catch {
        Write-Error "Error processing $FilePath`: $_"
        return $false
    }
}

# Main execution
Write-Host "Java Header Update Script" -ForegroundColor Magenta
Write-Host "Author: $AUTHOR_NAME" -ForegroundColor Magenta
Write-Host "=========================" -ForegroundColor Magenta
Write-Host ""

if ($DryRun) {
    Write-Host "DRY RUN MODE - No files will be modified" -ForegroundColor Yellow
    Write-Host ""
}

# Find all Java files
Write-Host "Scanning for Java files..." -ForegroundColor Blue
$javaFiles = Get-ChildItem -Recurse -Filter "*.java" | Where-Object { $_.FullName -notmatch "\\target\\" }

Write-Host "Found $($javaFiles.Count) Java files" -ForegroundColor Blue
Write-Host ""

# Process each file
$updatedCount = 0
$skippedCount = 0

foreach ($file in $javaFiles) {
    $result = Update-JavaFile -FilePath $file.FullName -DryRun:$DryRun
    if ($result) {
        $updatedCount++
    } else {
        $skippedCount++
    }
}

# Summary
Write-Host ""
Write-Host "Summary:" -ForegroundColor Magenta
Write-Host "  Files processed: $($javaFiles.Count)" -ForegroundColor White
Write-Host "  Files updated: $updatedCount" -ForegroundColor Green
Write-Host "  Files skipped: $skippedCount" -ForegroundColor Yellow

if ($DryRun) {
    Write-Host ""
    Write-Host "Run without -DryRun to apply changes" -ForegroundColor Cyan
}
