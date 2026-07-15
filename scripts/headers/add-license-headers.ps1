#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Adds Apache License 2.0 headers to Java files in the PeeGeeQ project.

.DESCRIPTION
    Inserts the Apache License 2.0 header immediately after the package
    declaration of every Java file under the repository root (or at the top
    of the file if there is no package declaration).

    Preserves each file's original line endings (CRLF/LF) and
    trailing-newline state, so diffs contain only the header change.

    Excluded directories: target, .history, node_modules, .git.

.PARAMETER Path
    Optional file or directory to process. A single .java file is processed
    directly; a directory is scanned recursively (with the standard
    exclusions). Defaults to the repository root when omitted.

.PARAMETER DryRun
    Shows what changes would be made without actually modifying files.

.PARAMETER Force
    Re-processes files that already have a license header: every existing
    Apache license block is removed (wherever it appears, before or after
    the package declaration, including accidental duplicates) and a single
    fresh header is inserted after the package declaration.

.PARAMETER CopyrightYear
    Year used in the copyright line. Defaults to 2025.

.PARAMETER Verbose
    Shows per-file processing and skip messages.

.EXAMPLE
    .\add-license-headers.ps1 -DryRun
    Shows what changes would be made without modifying files.

.EXAMPLE
    .\add-license-headers.ps1 -Force
    Normalizes all files to a single license header after the package declaration.

.EXAMPLE
    .\add-license-headers.ps1 -Path peegeeq-api\src\main\java\dev\mars\peegeeq\api\database\ConnectionProvider.java -Force
    Normalizes the license header of a single file.
#>

param(
    [string]$Path,
    [switch]$DryRun,
    [switch]$Force,
    [string]$CopyrightYear = "2025",
    [switch]$Verbose
)

# Configuration
$AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
$EXCLUDED_DIRS = '\\(target|\.history|node_modules|\.git)\\'

# Apache License 2.0 header template
$LICENSE_HEADER = @"
/*
 * Copyright $CopyrightYear $AUTHOR_NAME
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

# Function to detect the file's predominant line ending
function Get-LineEnding {
    param([string]$Content)

    if ($Content -match "`r`n") { return "`r`n" }
    return "`n"
}

# Function to check if file already has license header
function Test-HasLicenseHeader {
    param([string]$Content)

    return $Content -match "Licensed under the Apache License"
}

# Function to remove every existing Apache license block, wherever it appears.
# The lazy (?!\*/) guards keep the match inside a single comment block.
function Remove-ExistingLicense {
    param([string]$Content)

    return [regex]::Replace($Content,
        '(?s)[ \t]*/\*(?:(?!\*/).)*?Licensed under the Apache License(?:(?!\*/).)*?\*/[ \t]*(\r?\n)*',
        '')
}

# Function to process a single Java file
function Add-LicenseHeader {
    param(
        [string]$FilePath,
        [switch]$DryRun,
        [switch]$Force
    )

    if ($Verbose) {
        Write-Host "Processing: $FilePath" -ForegroundColor Cyan
    }

    try {
        $content = Get-Content -Path $FilePath -Raw -Encoding UTF8

        if ([string]::IsNullOrEmpty($content)) {
            if ($Verbose) {
                Write-Host "  Skipping - empty file" -ForegroundColor Yellow
            }
            return $false
        }

        # Check if file already has license header
        if ((Test-HasLicenseHeader -Content $content) -and -not $Force) {
            if ($Verbose) {
                Write-Host "  Skipping - already has license header (use -Force to override)" -ForegroundColor Yellow
            }
            return $false
        }

        $eol = Get-LineEnding -Content $content
        $hadTrailingNewline = $content.EndsWith("`n")

        # In Force mode, strip all existing license blocks first (this also
        # collapses accidental duplicates and headers placed before the package)
        if ($Force) {
            $content = Remove-ExistingLicense -Content $content
        }

        $lines = $content -split "`r?`n"
        $headerLines = $LICENSE_HEADER -split "`r?`n"

        # Find the package declaration
        $pkgIndex = -1
        for ($i = 0; $i -lt $lines.Length; $i++) {
            if ($lines[$i].Trim() -match '^package\s+') {
                $pkgIndex = $i
                break
            }
        }

        $newLines = @()

        if ($pkgIndex -ge 0) {
            # Insert the header after the package declaration
            $newLines += $lines[0..$pkgIndex]
            $newLines += ""
            $newLines += $headerLines

            # Remainder of the file, with leading blank lines collapsed to one
            $restStart = $pkgIndex + 1
            while ($restStart -lt $lines.Length -and $lines[$restStart].Trim() -eq "") {
                $restStart++
            }
            if ($restStart -lt $lines.Length) {
                $newLines += ""
                $newLines += $lines[$restStart..($lines.Length - 1)]
            }
        } else {
            # No package declaration: header goes at the very top
            $newLines += $headerLines
            $newLines += ""
            $newLines += $lines
        }

        # Preserve the file's original line endings and trailing-newline state
        $newContent = $newLines -join $eol
        if ($hadTrailingNewline -and -not $newContent.EndsWith($eol)) {
            $newContent += $eol
        }

        if ($DryRun) {
            Write-Host "  Would add license header to: $FilePath" -ForegroundColor Green
        } else {
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

# Resolve the target: an explicit file or directory, or the repository root
# (this script lives in <repo>/scripts/headers, so the root is two levels up)
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ($Path) {
    if (-not (Test-Path -Path $Path)) {
        Write-Error "Path not found: $Path"
        exit 1
    }
    $targetPath = (Resolve-Path -Path $Path).Path
} else {
    $targetPath = $repoRoot
}

if (Test-Path -Path $targetPath -PathType Leaf) {
    if ($targetPath -notmatch '\.java$') {
        Write-Error "Not a Java file: $targetPath"
        exit 1
    }
    Write-Host "Processing single file: $targetPath" -ForegroundColor Blue
    $javaFiles = @(Get-Item -Path $targetPath)
} else {
    Write-Host "Scanning for Java files under $targetPath ..." -ForegroundColor Blue
    $javaFiles = Get-ChildItem -Path $targetPath -Recurse -Filter "*.java" |
        Where-Object { $_.FullName -notmatch $EXCLUDED_DIRS }
}

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
