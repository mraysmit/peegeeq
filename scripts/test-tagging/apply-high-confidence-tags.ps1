# PowerShell Script to Automatically Apply HIGH Confidence Test Tags
# This script reads the tag suggestions CSV and applies tags to test files

param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$SuggestionsFile = (Join-Path $PSScriptRoot "test-tag-suggestions.csv"),
    [switch]$DryRun = $false,
    [switch]$Verbose = $false,
    [string[]]$TagsToApply = @("integration", "performance", "smoke")
)

Write-Host "=== PeeGeeQ High-Confidence Test Tag Application ===" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor Gray
Write-Host "Suggestions File: $SuggestionsFile" -ForegroundColor Gray
Write-Host "Dry Run Mode: $DryRun" -ForegroundColor $(if ($DryRun) { "Yellow" } else { "Green" })
Write-Host ""

if (-not (Test-Path $SuggestionsFile)) {
    Write-Host "ERROR: Suggestions file not found: $SuggestionsFile" -ForegroundColor Red
    Write-Host "Please run .\scripts\suggest-test-tags.ps1 first" -ForegroundColor Yellow
    exit 1
}

# Read suggestions
$suggestions = Import-Csv $SuggestionsFile | 
    Where-Object { 
        $_.Confidence -eq "HIGH" -and 
        $_.SuggestedTag -ne "ALREADY_TAGGED" -and
        $TagsToApply -contains $_.SuggestedTag
    }

Write-Host "Found $($suggestions.Count) HIGH confidence suggestions to apply" -ForegroundColor Green
Write-Host ""

# Group by tag for summary
$tagGroups = $suggestions | Group-Object -Property SuggestedTag
foreach ($group in $tagGroups | Sort-Object Name) {
    Write-Host "  @Tag(`"$($group.Name)`"): $($group.Count) files" -ForegroundColor White
}
Write-Host ""

if ($DryRun) {
    Write-Host "=== DRY RUN MODE - No files will be modified ===" -ForegroundColor Yellow
    Write-Host ""
}

$successCount = 0
$skipCount = 0
$errorCount = 0
$filesModified = @()

foreach ($suggestion in $suggestions) {
    $relativePath = $suggestion.File
    $filePath = Join-Path $ProjectRoot $relativePath
    $tagToAdd = $suggestion.SuggestedTag
    
    if (-not (Test-Path $filePath)) {
        Write-Host "  [SKIP] File not found: $relativePath" -ForegroundColor Yellow
        $skipCount++
        continue
    }
    
    try {
        $content = Get-Content $filePath -Raw
        
        # Check if tag already exists
        if ($content -match "@Tag\s*\(\s*`"$tagToAdd`"\s*\)") {
            if ($Verbose) {
                Write-Host "  [SKIP] Already has @Tag(`"$tagToAdd`"): $relativePath" -ForegroundColor Gray
            }
            $skipCount++
            continue
        }
        
        # Check if any @Tag exists
        if ($content -match "@Tag\s*\(") {
            if ($Verbose) {
                Write-Host "  [SKIP] Already has other @Tag: $relativePath" -ForegroundColor Gray
            }
            $skipCount++
            continue
        }
        
        # Ensure Tag import exists
        $hasTagImport = $content -match "import\s+org\.junit\.jupiter\.api\.Tag;"
        
        # Find the right place to insert @Tag annotation
        # Pattern: Look for class/interface declaration and insert @Tag before it
        $pattern = '(?m)^((?:public\s+|private\s+|protected\s+)?(?:abstract\s+)?(?:final\s+)?(?:class|interface|enum)\s+\w+)'
        
        if ($content -match $pattern) {
            $newContent = $content
            
            # Add Tag import if missing
            if (-not $hasTagImport) {
                # Find the last import statement
                if ($content -match '(?m)^import\s+.*?;[\r\n]+') {
                    $lastImport = $Matches[0]
                    $importToAdd = "import org.junit.jupiter.api.Tag;`r`n"
                    
                    # Insert after the last import
                    $importPattern = '(import\s+org\.junit\.jupiter\.api\.Test;[\r\n]+)'
                    if ($content -match $importPattern) {
                        # Insert after Test import if it exists
                        $newContent = $content -replace $importPattern, "`$1$importToAdd"
                    }
                    else {
                        # Otherwise, add at the end of imports
                        $newContent = $content -replace '(?m)(^import\s+.*?;[\r\n]+)(?!import)', "`$1$importToAdd"
                    }
                }
            }
            
            # Add @Tag annotation before class declaration
            $tagAnnotation = "@Tag(`"$tagToAdd`")`r`n"
            $newContent = $newContent -replace $pattern, "$tagAnnotation`$1"
            
            if ($DryRun) {
                Write-Host "  [PREVIEW] Would add @Tag(`"$tagToAdd`") to: $relativePath" -ForegroundColor Cyan
                $successCount++
            }
            else {
                # Create backup
                $backupPath = "$filePath.bak"
                Copy-Item $filePath $backupPath -Force
                
                # Write modified content
                Set-Content -Path $filePath -Value $newContent -NoNewline
                
                Write-Host "  [APPLIED] @Tag(`"$tagToAdd`") to: $relativePath" -ForegroundColor Green
                $filesModified += $relativePath
                $successCount++
                
                # Remove backup if successful
                Remove-Item $backupPath -Force
            }
        }
        else {
            Write-Host "  [ERROR] Could not find class declaration in: $relativePath" -ForegroundColor Red
            $errorCount++
        }
    }
    catch {
        Write-Host "  [ERROR] Failed to process $relativePath : $_" -ForegroundColor Red
        $errorCount++
    }
}

Write-Host ""
Write-Host "=== SUMMARY ===" -ForegroundColor Cyan
Write-Host "Successfully processed: $successCount files" -ForegroundColor Green
Write-Host "Skipped (already tagged): $skipCount files" -ForegroundColor Yellow
Write-Host "Errors: $errorCount files" -ForegroundColor Red

if (-not $DryRun -and $successCount -gt 0) {
    Write-Host ""
    Write-Host "=== VERIFICATION ===" -ForegroundColor Magenta
    Write-Host "Run these commands to verify the changes:" -ForegroundColor White
    Write-Host ""
    Write-Host "# Verify tags were added correctly:" -ForegroundColor Gray
    Write-Host ".\scripts\analyze-test-tags.ps1" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "# Run the newly tagged integration tests:" -ForegroundColor Gray
    Write-Host ".\scripts\run-tests.sh integration" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "# Run the newly tagged performance tests:" -ForegroundColor Gray
    Write-Host ".\scripts\run-tests.sh performance" -ForegroundColor Cyan
    Write-Host ""
    
    # Export list of modified files
    $modifiedListPath = Join-Path $PSScriptRoot "modified-files.txt"
    $filesModified | Out-File $modifiedListPath
    Write-Host "Modified files list saved to: $modifiedListPath" -ForegroundColor Cyan
}
elseif ($DryRun) {
    Write-Host ""
    Write-Host "=== NEXT STEPS ===" -ForegroundColor Magenta
    Write-Host "To apply these changes, run:" -ForegroundColor White
    Write-Host "  .\scripts\apply-high-confidence-tags.ps1" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "To apply only specific tags:" -ForegroundColor White
    Write-Host "  .\scripts\apply-high-confidence-tags.ps1 -TagsToApply integration" -ForegroundColor Cyan
    Write-Host "  .\scripts\apply-high-confidence-tags.ps1 -TagsToApply performance" -ForegroundColor Cyan
}

return [PSCustomObject]@{
    SuccessCount = $successCount
    SkipCount = $skipCount
    ErrorCount = $errorCount
    FilesModified = $filesModified
    DryRun = $DryRun
}
