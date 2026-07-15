#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Updates comment headers in Java class files with author information.

.DESCRIPTION
    Scans all Java files under the repository root and ensures each has:
      1. The Apache License 2.0 header immediately after the package declaration.
      2. A type-level JavaDoc comment with an @author tag, inserted directly
         before the type declaration (and before any annotations on it).

    An existing type-level JavaDoc is replaced, preserving its first
    descriptive paragraph. The generated JavaDoc deliberately carries no
    @since or @version tags: previous runs stamped @since with the script's
    run date, which misleads readers, and git history records versioning.

    Preserves each file's original line endings (CRLF/LF) and
    trailing-newline state. Excluded directories: target, .history,
    node_modules, .git.

.PARAMETER Path
    Optional file or directory to process. A single .java file is processed
    directly; a directory is scanned recursively (with the standard
    exclusions). Defaults to the repository root when omitted.

.PARAMETER DryRun
    Shows what changes would be made without actually modifying files.

.PARAMETER CopyrightYear
    Year used in the copyright line. Defaults to the current year.

.PARAMETER Verbose
    Enables verbose output showing detailed processing information.

.EXAMPLE
    .\update-java-headers.ps1 -DryRun
    Shows what changes would be made without modifying files.

.EXAMPLE
    .\update-java-headers.ps1 -Path peegeeq-db\src\main\java -DryRun
    Shows what changes would be made to files under one directory.
#>

param(
    [string]$Path,
    [switch]$DryRun,
    [string]$CopyrightYear = (Get-Date).Year.ToString(),
    [switch]$Verbose
)

# Configuration
$AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
$PROJECT_NAME = "PeeGeeQ"
$EXCLUDED_DIRS = '\\(target|\.history|node_modules|\.git)\\'

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

# Matches a type declaration line (annotations on preceding lines are handled separately)
$TYPE_DECL_REGEX = '^\s*(?:public\s+|protected\s+|private\s+|final\s+|abstract\s+|static\s+|sealed\s+|non-sealed\s+|strictfp\s+)*(?:class|interface|enum|record|@interface)\s+\w+'

# Function to detect the file's predominant line ending
function Get-LineEnding {
    param([string]$Content)

    if ($Content -match "`r`n") { return "`r`n" }
    return "`n"
}

# Function to determine the type and name of the primary Java type in the file
function Get-JavaTypeInfo {
    param([string]$Content)

    $patterns = @(
        @{ Regex = '\b(?:public\s+)?@interface\s+(\w+)';                                Type = "annotation" },
        @{ Regex = '\b(?:public\s+)?(?:sealed\s+|non-sealed\s+)?interface\s+(\w+)';     Type = "interface" },
        @{ Regex = '\b(?:public\s+)?enum\s+(\w+)';                                      Type = "enum" },
        @{ Regex = '\b(?:public\s+)?record\s+(\w+)';                                    Type = "record" },
        @{ Regex = '\b(?:public\s+)?(?:final\s+|abstract\s+|sealed\s+)*class\s+(\w+)';  Type = "class" }
    )

    foreach ($p in $patterns) {
        if ($Content -match $p.Regex) {
            return @{ Type = $p.Type; Name = $matches[1] }
        }
    }

    return $null
}

# Function to extract the first descriptive paragraph from an existing JavaDoc block
function Get-JavadocDescription {
    param([string[]]$JavadocLines)

    $desc = @()
    foreach ($line in $JavadocLines) {
        $t = $line.Trim()
        $t = $t -replace '^/\*\*\s*', ''
        $t = $t -replace '\s*\*/$', ''
        $t = $t -replace '^\*\s?', ''
        $t = $t.Trim()

        if ($t -match '^@\w+') { break }
        if ($t -eq '') {
            if ($desc.Count -gt 0) { break }
            continue
        }
        $desc += $t
    }

    if ($desc.Count -gt 0) { return ($desc -join ' ') }
    return $null
}

# Function to generate the type-level JavaDoc. Deliberately no @since/@version:
# run-date @since tags mislead readers, and git records version history.
function New-TypeJavadoc {
    param(
        [string]$FileType,
        [string]$ClassName,
        [string]$ExistingDescription
    )

    $defaultDescription = switch ($FileType) {
        "interface"  { "Interface defining contracts for $ClassName functionality." }
        "enum"       { "Enumeration defining $ClassName constants and values." }
        "annotation" { "Annotation for $ClassName metadata and configuration." }
        "record"     { "Immutable data carrier for $ClassName values." }
        default      { "Implementation of $ClassName functionality." }
    }

    $description = if ($ExistingDescription) { $ExistingDescription } else { $defaultDescription }

    return @"
/**
 * $description
 *
 * This $FileType is part of the $PROJECT_NAME message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author $AUTHOR_NAME
 */
"@
}

# Function to insert the license header after the package declaration (or at
# the top of the file when there is no package declaration). Returns the lines.
function Add-LicenseLines {
    param([string[]]$Lines)

    $headerLines = $LICENSE_HEADER -split "`r?`n"

    $pkgIndex = -1
    for ($i = 0; $i -lt $Lines.Length; $i++) {
        if ($Lines[$i].Trim() -match '^package\s+') {
            $pkgIndex = $i
            break
        }
    }

    $newLines = @()
    if ($pkgIndex -ge 0) {
        $newLines += $Lines[0..$pkgIndex]
        $newLines += ""
        $newLines += $headerLines

        $restStart = $pkgIndex + 1
        while ($restStart -lt $Lines.Length -and $Lines[$restStart].Trim() -eq "") {
            $restStart++
        }
        if ($restStart -lt $Lines.Length) {
            $newLines += ""
            $newLines += $Lines[$restStart..($Lines.Length - 1)]
        }
    } else {
        $newLines += $headerLines
        $newLines += ""
        $newLines += $Lines
    }

    return $newLines
}

# Function to find the primary type declaration line, ignoring lines inside
# block comments. Returns -1 if no type declaration is found.
function Find-TypeDeclIndex {
    param([string[]]$Lines)

    $inBlockComment = $false
    for ($i = 0; $i -lt $Lines.Length; $i++) {
        $line = $Lines[$i]

        if ($inBlockComment) {
            if ($line -match '\*/') { $inBlockComment = $false }
            continue
        }
        if ($line -match '^\s*/\*' -and $line -notmatch '\*/') {
            $inBlockComment = $true
            continue
        }
        if ($line -match $TYPE_DECL_REGEX) {
            return $i
        }
    }

    return -1
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

        if ([string]::IsNullOrEmpty($content)) {
            if ($Verbose) {
                Write-Host "  Skipping - empty file" -ForegroundColor Yellow
            }
            return $false
        }

        $hasLicense = $content -match "Licensed under the Apache License"
        $hasAuthor = $content -match "@author\s+.*$([regex]::Escape($AUTHOR_NAME))"

        if ($hasLicense -and $hasAuthor) {
            if ($Verbose) {
                Write-Host "  Skipping - already has license header and author tag" -ForegroundColor Yellow
            }
            return $false
        }

        $eol = Get-LineEnding -Content $content
        $hadTrailingNewline = $content.EndsWith("`n")
        $lines = $content -split "`r?`n"

        # Step 1: license header after the package declaration
        if (-not $hasLicense) {
            $lines = Add-LicenseLines -Lines $lines
        }

        # Step 2: type-level JavaDoc with @author before the type declaration
        $typeInfo = $null
        $existingDescription = $null
        if (-not $hasAuthor) {
            $typeIndex = Find-TypeDeclIndex -Lines $lines
            if ($typeIndex -eq -1) {
                # e.g. package-info.java - license step may still have applied
                if ($Verbose) {
                    Write-Host "  No type declaration found - skipping JavaDoc step" -ForegroundColor Yellow
                }
                if ($hasLicense) { return $false }
            } else {
                $typeInfo = Get-JavaTypeInfo -Content ($lines -join "`n")
                if ($null -eq $typeInfo) {
                    Write-Warning "Could not determine type info in $FilePath"
                    return $false
                }

                # The JavaDoc goes before any annotations on the declaration
                $insertAt = $typeIndex
                $j = $typeIndex - 1
                while ($j -ge 0 -and ($lines[$j].Trim() -eq "" -or $lines[$j].Trim() -match '^@\w+')) {
                    if ($lines[$j].Trim() -match '^@\w+') { $insertAt = $j }
                    $j--
                }

                # Detect an existing type-level JavaDoc immediately above
                # (handles both '/**' on its own line and '/** text...' forms)
                $jdStart = -1
                $jdEnd = -1
                if ($j -ge 0 -and $lines[$j].Trim().EndsWith('*/')) {
                    for ($k = $j; $k -ge 0; $k--) {
                        $t = $lines[$k].TrimStart()
                        if ($t.StartsWith('/**')) { $jdStart = $k; $jdEnd = $j; break }
                        if ($t.StartsWith('/*')) { break }  # license block, not JavaDoc
                    }
                }

                if ($jdStart -ge 0) {
                    $existingDescription = Get-JavadocDescription -JavadocLines $lines[$jdStart..$jdEnd]
                }

                $javadocLines = (New-TypeJavadoc -FileType $typeInfo.Type -ClassName $typeInfo.Name -ExistingDescription $existingDescription) -split "`r?`n"

                # Rebuild: everything before the insertion point (minus the old
                # JavaDoc), one blank line, new JavaDoc, then the declaration
                $before = @()
                for ($i = 0; $i -lt $insertAt; $i++) {
                    if ($jdStart -ge 0 -and $i -ge $jdStart -and $i -le $jdEnd) { continue }
                    $before += $lines[$i]
                }
                while ($before.Count -gt 0 -and $before[-1].Trim() -eq "") {
                    $before = @($before | Select-Object -First ($before.Count - 1))
                }

                $newLines = @()
                if ($before.Count -gt 0) {
                    $newLines += $before
                    $newLines += ""
                }
                $newLines += $javadocLines
                $newLines += $lines[$insertAt..($lines.Length - 1)]
                $lines = $newLines
            }
        }

        # Preserve the file's original line endings and trailing-newline state
        $newContent = $lines -join $eol
        if ($hadTrailingNewline -and -not $newContent.EndsWith($eol)) {
            $newContent += $eol
        }

        if ($DryRun) {
            Write-Host "  Would update: $FilePath" -ForegroundColor Green
            if ($typeInfo) {
                Write-Host "    File type: $($typeInfo.Type)" -ForegroundColor Gray
                Write-Host "    Type name: $($typeInfo.Name)" -ForegroundColor Gray
            }
            if ($existingDescription) {
                Write-Host "    Existing description: $existingDescription" -ForegroundColor Gray
            }
        } else {
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
