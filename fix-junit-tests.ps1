# PowerShell script to fix plain JUnit tests (non-Spring Boot) to use manual system property configuration
# This script will:
# 1. Remove @DynamicPropertySource methods from plain JUnit tests
# 2. Add manual system property configuration to @BeforeEach methods
# 3. Add clearSystemProperties method and call it in @AfterEach

$testFiles = Get-ChildItem -Path "peegeeq-examples/src/test/java" -Recurse -Filter "*.java" | Where-Object { 
    $content = Get-Content $_.FullName -Raw
    # Find plain JUnit tests (have @Testcontainers but NOT @SpringBootTest)
    ($content -match "@Testcontainers") -and ($content -notmatch "@SpringBootTest")
}

Write-Host "Found $($testFiles.Count) plain JUnit test files to fix"

foreach ($file in $testFiles) {
    Write-Host "Processing: $($file.Name)"
    
    $content = Get-Content $file.FullName -Raw
    
    # Skip if already manually configured (has clearSystemProperties method)
    if ($content -match "clearSystemProperties") {
        Write-Host "  Already manually configured, skipping"
        continue
    }
    
    # Remove @DynamicPropertySource method and imports
    $content = $content -replace "\s*@DynamicPropertySource\s*\n\s*static void configureProperties\(DynamicPropertyRegistry registry\) \{\s*\n\s*SharedTestContainers\.configureSharedProperties\(registry\);\s*\n\s*\}", ""
    $content = $content -replace "import org\.springframework\.test\.context\.DynamicPropertyRegistry;\n", ""
    $content = $content -replace "import org\.springframework\.test\.context\.DynamicPropertySource;\n", ""
    
    # Find @BeforeEach method and add system property configuration
    if ($content -match "@BeforeEach\s*\n\s*void\s+(\w+)\(\)\s*\{") {
        # Add system property configuration after the opening brace of @BeforeEach method
        $content = $content -replace "(@BeforeEach\s*\n\s*void\s+\w+\(\)\s*\{)", "`$1`n        // Configure system properties for TestContainers PostgreSQL connection`n        System.setProperty(`"peegeeq.database.host`", postgres.getHost());`n        System.setProperty(`"peegeeq.database.port`", String.valueOf(postgres.getFirstMappedPort()));`n        System.setProperty(`"peegeeq.database.name`", postgres.getDatabaseName());`n        System.setProperty(`"peegeeq.database.username`", postgres.getUsername());`n        System.setProperty(`"peegeeq.database.password`", postgres.getPassword());`n"
    }
    
    # Find @AfterEach method and add clearSystemProperties call
    if ($content -match "@AfterEach\s*\n\s*void\s+(\w+)\(\)\s*\{") {
        # Add clearSystemProperties call before the closing brace of @AfterEach method
        $content = $content -replace "(\s*)\}", "`$1    // Clear system properties`n`$1    clearSystemProperties();`n`$1}"
    }
    
    # Add clearSystemProperties method at the end of the class (before the last closing brace)
    $content = $content -replace "(\s*)(\}\s*)$", "`$1/**`n`$1 * Clear system properties after test completion`n`$1 */`n`$1private void clearSystemProperties() {`n`$1    System.clearProperty(`"peegeeq.database.host`");`n`$1    System.clearProperty(`"peegeeq.database.port`");`n`$1    System.clearProperty(`"peegeeq.database.name`");`n`$1    System.clearProperty(`"peegeeq.database.username`");`n`$1    System.clearProperty(`"peegeeq.database.password`");`n`$1}`n`n`$2"
    
    # Write back to file
    Set-Content -Path $file.FullName -Value $content -NoNewline
    
    Write-Host "  Fixed: $($file.Name)"
}

Write-Host "All plain JUnit test files have been processed!"
