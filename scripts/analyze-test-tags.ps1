# PowerShell Script to Analyze @Tag Annotations in Test Files
# This script scans all Java test files and reports on @Tag usage

param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [switch]$Verbose
)

Write-Host "=== PeegeeQ Test Tag Analysis ===" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor Gray
Write-Host ""

# Find all Java test files
$testFiles = Get-ChildItem -Path $ProjectRoot -Recurse -Include "*Test.java","*Tests.java","*TestCase.java" -File | 
    Where-Object { $_.FullName -match "\\src\\test\\java\\" }

if ($testFiles.Count -eq 0) {
    Write-Host "No test files found!" -ForegroundColor Red
    exit 1
}

Write-Host "Found $($testFiles.Count) test files to analyze..." -ForegroundColor Green
Write-Host ""

# Data structures to track results
$taggedFiles = @{}      # Dictionary: tag -> list of files
$untaggedFiles = @()    # List of files without tags
$fileTagMap = @{}       # Dictionary: file -> list of tags

# Process each test file
foreach ($file in $testFiles) {
    $relativePath = $file.FullName.Replace($ProjectRoot, "").TrimStart('\')
    $content = Get-Content $file.FullName -Raw
    
    # Find all @Tag annotations in the file (both class-level and method-level)
    # Matches: 
    #   @Tag("tagname") or @Tag(value = "tagname")
    #   @Tag(TestCategories.INTEGRATION) etc.
    $tagMatchesLiteral = [regex]::Matches($content, '@Tag\s*\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)')
    $tagMatchesConstant = [regex]::Matches($content, '@Tag\s*\(\s*TestCategories\.(\w+)\s*\)')
    
    # Combine and normalize to lowercase
    $tagMatches = @()
    foreach ($match in $tagMatchesLiteral) {
        $tagMatches += $match
    }
    foreach ($match in $tagMatchesConstant) {
        # Create a synthetic match with the tag value in lowercase
        $tagMatches += [PSCustomObject]@{
            Groups = @(
                $null,
                @{ Value = $match.Groups[1].Value.ToLower() }
            )
        }
    }
    
    if ($tagMatches.Count -gt 0) {
        $tags = @()
        foreach ($match in $tagMatches) {
            $tagValue = $match.Groups[1].Value
            $tags += $tagValue
            
            # Add to tag -> files mapping
            if (-not $taggedFiles.ContainsKey($tagValue)) {
                $taggedFiles[$tagValue] = @()
            }
            if ($taggedFiles[$tagValue] -notcontains $relativePath) {
                $taggedFiles[$tagValue] += $relativePath
            }
        }
        
        # Remove duplicates and store
        $uniqueTags = $tags | Select-Object -Unique
        $fileTagMap[$relativePath] = $uniqueTags
        
        if ($Verbose) {
            Write-Host "  [TAGGED] $relativePath" -ForegroundColor Green
            Write-Host "           Tags: $($uniqueTags -join ', ')" -ForegroundColor Gray
        }
    }
    else {
        $untaggedFiles += $relativePath
        if ($Verbose) {
            Write-Host "  [NO TAG] $relativePath" -ForegroundColor Yellow
        }
    }
}

Write-Host ""
Write-Host "=== SUMMARY ===" -ForegroundColor Cyan
Write-Host ""

# Display files by tag
if ($taggedFiles.Count -gt 0) {
    Write-Host "Files Grouped by Tag:" -ForegroundColor Green
    Write-Host "--------------------" -ForegroundColor Green
    
    $sortedTags = $taggedFiles.Keys | Sort-Object
    foreach ($tag in $sortedTags) {
        $count = $taggedFiles[$tag].Count
        Write-Host "  @Tag(`"$tag`"): $count file(s)" -ForegroundColor White
        
        if ($Verbose) {
            foreach ($file in $taggedFiles[$tag] | Sort-Object) {
                Write-Host "    - $file" -ForegroundColor Gray
            }
        }
    }
    Write-Host ""
}

# Display untagged files count
Write-Host "Files Without Tags:" -ForegroundColor Yellow
Write-Host "-------------------" -ForegroundColor Yellow
Write-Host "  No @Tag annotations: $($untaggedFiles.Count) file(s)" -ForegroundColor White

if ($Verbose -and $untaggedFiles.Count -gt 0) {
    foreach ($file in $untaggedFiles | Sort-Object) {
        Write-Host "    - $file" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "=== STATISTICS ===" -ForegroundColor Cyan
Write-Host "Total test files: $($testFiles.Count)" -ForegroundColor White
Write-Host "Tagged files: $($fileTagMap.Count)" -ForegroundColor Green
Write-Host "Untagged files: $($untaggedFiles.Count)" -ForegroundColor Yellow
Write-Host "Unique tags found: $($taggedFiles.Keys.Count)" -ForegroundColor Magenta

# Export detailed results to CSV (optional)
$exportPath = Join-Path $PSScriptRoot "test-tag-analysis.csv"
$csvData = @()

foreach ($file in $fileTagMap.Keys) {
    $csvData += [PSCustomObject]@{
        File = $file
        Tags = ($fileTagMap[$file] -join '; ')
        TagCount = $fileTagMap[$file].Count
    }
}

foreach ($file in $untaggedFiles) {
    $csvData += [PSCustomObject]@{
        File = $file
        Tags = ""
        TagCount = 0
    }
}

$csvData | Sort-Object File | Export-Csv -Path $exportPath -NoTypeInformation
Write-Host ""
Write-Host "Detailed results exported to: $exportPath" -ForegroundColor Cyan

# Return summary object for programmatic use
return [PSCustomObject]@{
    TotalFiles = $testFiles.Count
    TaggedFiles = $fileTagMap.Count
    UntaggedFiles = $untaggedFiles.Count
    UniqueTags = $taggedFiles.Keys.Count
    TagDistribution = $taggedFiles
}
