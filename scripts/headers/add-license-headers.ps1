#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Adds Apache License 2.0 headers to Java files in the PeeGeeQ project.

.DESCRIPTION
    This script adds proper Apache License 2.0 headers to all Java files,
    preserving existing JavaDoc comments and author information.

.PARAMETER DryRun
    If specified, shows what changes would be made without actually modifying files.

.PARAMETER Force
    If specified, updates files even if they already have license headers.

.EXAMPLE
    .\add-license-headers.ps1 -DryRun
    Shows what changes would be made without modifying files.

.EXAMPLE
    .\add-license-headers.ps1 -Force
    Updates all files, even those with existing license headers.
#>

param(
    [switch]$DryRun,
    [switch]$Force
)

# Configuration
$AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
$COPYRIGHT_YEAR = "2025"

# Apache License 2.0 header template
$LICENSE_HEADER = @"
/*
 * Copyright $COPYRIGHT_YEAR $AUTHOR_NAME
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
"@

# Function to check if file already has license header
function Test-HasLicenseHeader {
    param([string]$Content)
    
    return $Content -match "Licensed under the Apache License"
}

# Function to process a single Java file
function Add-LicenseHeader {
    param(
        [string]$FilePath,
        [switch]$DryRun,
        [switch]$Force
    )
    
    Write-Host "Processing: $FilePath" -ForegroundColor Cyan
    
    try {
        $content = Get-Content -Path $FilePath -Raw -Encoding UTF8
        
        # Check if file already has license header
        if ((Test-HasLicenseHeader -Content $content) -and -not $Force) {
            Write-Host "  Skipping - already has license header (use -Force to override)" -ForegroundColor Yellow
            return $false
        }
        
        # Split content into lines
        $lines = $content -split "`r?`n"
        
        # Find insertion point (after package declaration)
        $insertIndex = 0
        for ($i = 0; $i -lt $lines.Length; $i++) {
            $line = $lines[$i].Trim()
            if ($line -match '^package\s+') {
                $insertIndex = $i + 1
                break
            }
        }
        
        # Build new content
        $newLines = @()
        
        # Add lines up to insertion point
        if ($insertIndex -gt 0) {
            $newLines += $lines[0..($insertIndex-1)]
        }
        
        # Add empty line after package if needed
        if ($insertIndex -gt 0 -and $lines[$insertIndex-1].Trim() -ne "") {
            $newLines += ""
        }
        
        # Add license header
        $newLines += $LICENSE_HEADER -split "`r?`n"
        $newLines += ""
        
        # Add remaining lines (skip existing license header if present)
        $skipLicense = $false
        $licenseEndFound = $false
        
        for ($i = $insertIndex; $i -lt $lines.Length; $i++) {
            $line = $lines[$i]
            
            # Skip existing license header
            if ($line.Trim() -eq "/*" -and $lines[$i+1] -match "Copyright.*$AUTHOR_NAME") {
                $skipLicense = $true
                continue
            }
            
            if ($skipLicense) {
                if ($line.Trim() -eq "*/") {
                    $skipLicense = $false
                    $licenseEndFound = $true
                }
                continue
            }
            
            # Skip empty lines immediately after removed license
            if ($licenseEndFound -and $line.Trim() -eq "") {
                $licenseEndFound = $false
                continue
            }
            
            $newLines += $line
        }
        
        # Write the updated content
        if ($DryRun) {
            Write-Host "  Would add license header to: $FilePath" -ForegroundColor Green
        } else {
            $newContent = $newLines -join "`n"
            Set-Content -Path $FilePath -Value $newContent -Encoding UTF8 -NoNewline
            Write-Host "  Added license header to: $FilePath" -ForegroundColor Green
        }
        
        return $true
        
    } catch {
        Write-Error "Error processing $FilePath`: $_"
        return $false
    }
}

# Main execution
Write-Host "License Header Addition Script" -ForegroundColor Magenta
Write-Host "Apache License 2.0" -ForegroundColor Magenta
Write-Host "=============================" -ForegroundColor Magenta
Write-Host ""

if ($DryRun) {
    Write-Host "DRY RUN MODE - No files will be modified" -ForegroundColor Yellow
    Write-Host ""
}

if ($Force) {
    Write-Host "FORCE MODE - Will update files with existing license headers" -ForegroundColor Yellow
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
    $result = Add-LicenseHeader -FilePath $file.FullName -DryRun:$DryRun -Force:$Force
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
