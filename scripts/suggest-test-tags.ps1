# PowerShell Script to Analyze Tests and Suggest Appropriate Tags
# Based on test characteristics, this script recommends @Tag annotations

param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$CsvOutput = (Join-Path $PSScriptRoot "test-tag-suggestions.csv"),
    [switch]$Verbose,
    [switch]$OnlyUntagged
)

Write-Host "=== PeeGeeQ Test Tag Suggestion Analysis ===" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor Gray
Write-Host ""

# Classification criteria from TESTING-GUIDE.md:
# - CORE: Fast unit tests (<1s per test), no external dependencies, mocks only
# - SMOKE: Ultra-fast basic verification, critical paths only
# - INTEGRATION: Uses TestContainers, real PostgreSQL, database interactions
# - PERFORMANCE: Benchmarks, load tests, @Benchmark annotations
# - SLOW: Long-running tests (>15s), comprehensive scenarios

# Find all Java test files
$testFiles = Get-ChildItem -Path $ProjectRoot -Recurse -Include "*Test.java","*Tests.java","*TestCase.java" -File | 
    Where-Object { $_.FullName -match "\\src\\test\\java\\" }

Write-Host "Analyzing $($testFiles.Count) test files..." -ForegroundColor Green
Write-Host ""

$results = @()
$progressCounter = 0

foreach ($file in $testFiles) {
    $progressCounter++
    if ($progressCounter % 50 -eq 0) {
        Write-Host "  Progress: $progressCounter / $($testFiles.Count)" -ForegroundColor Gray
    }
    
    $relativePath = $file.FullName.Replace($ProjectRoot, "").TrimStart('\')
    $content = Get-Content $file.FullName -Raw
    
    # Check for existing tags (both literal strings and TestCategories constants)
    $existingTags = @()
    $tagMatchesLiteral = [regex]::Matches($content, '@Tag\s*\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)')
    $tagMatchesConstant = [regex]::Matches($content, '@Tag\s*\(\s*TestCategories\.(\w+)\s*\)')
    
    foreach ($match in $tagMatchesLiteral) {
        $existingTags += $match.Groups[1].Value
    }
    foreach ($match in $tagMatchesConstant) {
        $existingTags += $match.Groups[1].Value.ToLower()
    }
    
    # Analyze test characteristics
    $characteristics = @{
        HasTestContainers = $content -match '@Testcontainers|@Container|PostgreSQLContainer|TestContainers'
        HasMockito = $content -match '@Mock|@InjectMocks|MockitoExtension|Mockito\.'
        HasAwaitility = $content -match 'Awaitility\.|await\(\)'
        HasSpring = $content -match '@SpringBootTest|@DataJpaTest|@WebMvcTest|ApplicationContext'
        HasBenchmark = $content -match '@Benchmark|@BenchmarkMode|JMH'
        FileName = $file.Name
        IsIntegrationTest = $file.Name -match 'IntegrationTest\.java$'
        IsPerformanceTest = $file.Name -match 'PerformanceTest\.java$|BenchmarkTest\.java$'
        IsSmokeTest = $file.Name -match 'Smoke.*Test\.java$'
        TestMethodCount = ([regex]::Matches($content, '@Test\s+(?:void|public\s+void)') | Measure-Object).Count
        HasDatabaseQueries = $content -match '(SELECT|INSERT|UPDATE|DELETE)\s+\w+\s+FROM'
        HasVertx = $content -match 'Vertx|io\.vertx'
        HasJDBC = $content -match 'DriverManager|Connection\.|PreparedStatement'
        FileSize = $file.Length
        HasDisplayName = $content -match '@DisplayName'
        IsInIntegrationTestsModule = $relativePath -match '^peegeeq-integration-tests\\'
        IsInApiModule = $relativePath -match '^peegeeq-api\\'
    }
    
    # Suggest tag based on characteristics
    $suggestedTag = ""
    $confidence = ""
    $reasoning = ""
    
    if ($existingTags.Count -gt 0) {
        $suggestedTag = "ALREADY_TAGGED"
        $confidence = "N/A"
        $reasoning = "Already has tags: $($existingTags -join ', ')"
    }
    # Module-based rules (highest priority)
    elseif ($characteristics.IsInIntegrationTestsModule) {
        $suggestedTag = "integration"
        $confidence = "HIGH"
        $reasoning = "In peegeeq-integration-tests module"
    }
    elseif ($characteristics.IsInApiModule -and $characteristics.IsSmokeTest) {
        $suggestedTag = "smoke"
        $confidence = "HIGH"
        $reasoning = "Smoke test in peegeeq-api module"
    }
    elseif ($characteristics.IsInApiModule) {
        $suggestedTag = "core"
        $confidence = "HIGH"
        $reasoning = "In peegeeq-api module (API definitions and utilities)"
    }
    # Performance tests
    elseif ($characteristics.HasBenchmark) {
        $suggestedTag = "performance"
        $confidence = "HIGH"
        $reasoning = "Contains benchmark annotations"
    }
    elseif ($characteristics.IsPerformanceTest) {
        $suggestedTag = "performance"
        $confidence = "HIGH"
        $reasoning = "Performance test naming pattern"
    }
    # Integration tests
    elseif ($characteristics.HasTestContainers) {
        $suggestedTag = "integration"
        $confidence = "HIGH"
        $reasoning = "Uses TestContainers for real database testing"
    }
    elseif ($characteristics.IsIntegrationTest) {
        $suggestedTag = "integration"
        $confidence = "MEDIUM"
        $reasoning = "Integration test naming pattern (verify if uses TestContainers)"
    }
    elseif ($characteristics.HasSpring -and -not $characteristics.HasMockito) {
        $suggestedTag = "integration"
        $confidence = "MEDIUM"
        $reasoning = "Spring Boot test without mocking - likely integration test"
    }
    elseif ($characteristics.HasAwaitility -or $characteristics.TestMethodCount -gt 20) {
        $suggestedTag = "slow"
        $confidence = "MEDIUM"
        $reasoning = "Uses async testing or has many test methods"
    }
    elseif ($characteristics.HasMockito -and $characteristics.TestMethodCount -le 10 -and $file.Length -lt 20KB) {
        $suggestedTag = "core"
        $confidence = "HIGH"
        $reasoning = "Fast unit test with mocks, small size"
    }
    elseif ($characteristics.HasMockito) {
        $suggestedTag = "core"
        $confidence = "MEDIUM"
        $reasoning = "Uses mocking framework - likely unit test"
    }
    elseif ($characteristics.TestMethodCount -eq 1 -and $file.Length -lt 5KB) {
        $suggestedTag = "smoke"
        $confidence = "MEDIUM"
        $reasoning = "Single test method, small file - basic verification"
    }
    elseif ($file.Length -lt 10KB -and $characteristics.TestMethodCount -le 5) {
        $suggestedTag = "core"
        $confidence = "MEDIUM"
        $reasoning = "Small file with few tests - likely fast unit test"
    }
    else {
        $suggestedTag = "core"
        $confidence = "LOW"
        $reasoning = "Default to core - manual review recommended"
    }
    
    # Skip already-tagged files if OnlyUntagged is set
    if ($OnlyUntagged -and $suggestedTag -eq "ALREADY_TAGGED") {
        continue
    }
    
    $result = [PSCustomObject]@{
        File = $relativePath
        Module = ($relativePath -split '\\')[0]
        ExistingTags = ($existingTags -join '; ')
        SuggestedTag = $suggestedTag
        Confidence = $confidence
        Reasoning = $reasoning
        TestContainers = $characteristics.HasTestContainers
        Mockito = $characteristics.HasMockito
        Spring = $characteristics.HasSpring
        TestMethods = $characteristics.TestMethodCount
        FileSizeKB = [math]::Round($file.Length / 1KB, 1)
    }
    
    $results += $result
    
    # Verbose output
    if ($Verbose) {
        # If OnlyUntagged is set, skip already-tagged files completely
        if ($suggestedTag -eq "ALREADY_TAGGED" -and $OnlyUntagged) {
            # Skip - don't output anything for already tagged files
        }
        elseif ($suggestedTag -ne "ALREADY_TAGGED") {
            # Output untagged files with suggestions
            $color = switch ($confidence) {
                "HIGH" { "Green" }
                "MEDIUM" { "Yellow" }
                "LOW" { "Red" }
                default { "White" }
            }
            Write-Host "  [$confidence] $relativePath" -ForegroundColor $color
            Write-Host "           Suggest: @Tag(`"$suggestedTag`") - $reasoning" -ForegroundColor Gray
        }
        else {
            # Output already tagged files only if NOT OnlyUntagged
            Write-Host "  [TAGGED] $relativePath" -ForegroundColor Gray
            Write-Host "           Has: $($existingTags -join ', ')" -ForegroundColor DarkGray
        }
    }
}

Write-Host ""
Write-Host "=== SUMMARY ===" -ForegroundColor Cyan
Write-Host ""

# Group suggestions by tag
$tagSummary = $results | Where-Object { $_.SuggestedTag -ne "ALREADY_TAGGED" } | Group-Object -Property SuggestedTag

foreach ($group in $tagSummary | Sort-Object Name) {
    $highConf = ($group.Group | Where-Object { $_.Confidence -eq "HIGH" }).Count
    $medConf = ($group.Group | Where-Object { $_.Confidence -eq "MEDIUM" }).Count
    $lowConf = ($group.Group | Where-Object { $_.Confidence -eq "LOW" }).Count
    
    Write-Host "Suggested @Tag(`"$($group.Name)`"): $($group.Count) files" -ForegroundColor White
    Write-Host "  High confidence: $highConf" -ForegroundColor Green
    Write-Host "  Medium confidence: $medConf" -ForegroundColor Yellow
    Write-Host "  Low confidence: $lowConf" -ForegroundColor Red
}

$alreadyTagged = ($results | Where-Object { $_.SuggestedTag -eq "ALREADY_TAGGED" }).Count
Write-Host ""
Write-Host "Already tagged: $alreadyTagged files" -ForegroundColor Cyan
Write-Host ""

# Export to CSV
$results | Sort-Object Module, File | Export-Csv -Path $CsvOutput -NoTypeInformation
Write-Host "Detailed suggestions exported to: $CsvOutput" -ForegroundColor Cyan

# Generate recommendations
Write-Host ""
Write-Host "=== RECOMMENDATIONS ===" -ForegroundColor Magenta
Write-Host ""

$highConfUntagged = ($results | Where-Object { $_.Confidence -eq "HIGH" }).Count
$medConfUntagged = ($results | Where-Object { $_.Confidence -eq "MEDIUM" }).Count
$lowConfUntagged = ($results | Where-Object { $_.Confidence -eq "LOW" }).Count

Write-Host "1. Start with HIGH confidence suggestions ($highConfUntagged files)" -ForegroundColor Green
Write-Host "   These are clear cases (TestContainers, Benchmarks, etc.)" -ForegroundColor Gray
Write-Host ""

Write-Host "2. Review MEDIUM confidence suggestions ($medConfUntagged files)" -ForegroundColor Yellow
Write-Host "   Verify these meet the criteria for the suggested tag" -ForegroundColor Gray
Write-Host ""

Write-Host "3. Manually review LOW confidence suggestions ($lowConfUntagged files)" -ForegroundColor Red
Write-Host "   These need careful analysis of test behavior" -ForegroundColor Gray
Write-Host ""

Write-Host "4. CRITICAL: Run each category separately to verify performance:" -ForegroundColor Cyan
Write-Host "   .\scripts\run-tests.sh core     # Should complete in <30 seconds" -ForegroundColor Gray
Write-Host "   .\scripts\run-tests.sh smoke    # Should complete in <20 seconds" -ForegroundColor Gray
Write-Host "   .\scripts\run-tests.sh integration  # 10-15 minutes expected" -ForegroundColor Gray
Write-Host ""

Write-Host "5. Use the CSV file to batch-apply tags by module" -ForegroundColor Cyan
Write-Host ""

# Module-level statistics
Write-Host "=== BY MODULE ===" -ForegroundColor Cyan
$moduleStats = $results | Where-Object { $_.SuggestedTag -ne "ALREADY_TAGGED" } | 
    Group-Object -Property Module | 
    Sort-Object Name

foreach ($mod in $moduleStats) {
    $untaggedCount = $mod.Count
    Write-Host "$($mod.Name): $untaggedCount untagged tests" -ForegroundColor White
    
    $modTags = $mod.Group | Group-Object -Property SuggestedTag
    foreach ($tag in $modTags | Sort-Object Name) {
        Write-Host "  - @Tag(`"$($tag.Name)`"): $($tag.Count) suggested" -ForegroundColor Gray
    }
}

return $results
