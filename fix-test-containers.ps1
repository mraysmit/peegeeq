# PowerShell script to fix all test classes to use SharedTestContainers
# This script will:
# 1. Remove @Container annotations
# 2. Replace individual PostgreSQL containers with SharedTestContainers.getSharedPostgreSQLContainer()
# 3. Add @DynamicPropertySource methods
# 4. Add necessary imports

$testFiles = Get-ChildItem -Path "peegeeq-examples/src/test/java" -Recurse -Filter "*.java" | Where-Object { 
    (Get-Content $_.FullName -Raw) -match "@Container" 
}

Write-Host "Found $($testFiles.Count) test files to fix"

foreach ($file in $testFiles) {
    Write-Host "Processing: $($file.Name)"
    
    $content = Get-Content $file.FullName -Raw
    
    # Skip if already using SharedTestContainers
    if ($content -match "SharedTestContainers") {
        Write-Host "  Already using SharedTestContainers, skipping"
        continue
    }
    
    # Add SharedTestContainers import if not present
    if ($content -notmatch "import dev.mars.peegeeq.examples.shared.SharedTestContainers;") {
        $content = $content -replace "(import dev\.mars\.peegeeq\.test\.PostgreSQLTestConstants;)", "`$1`nimport dev.mars.peegeeq.examples.shared.SharedTestContainers;"
    }
    
    # Add DynamicPropertySource imports if not present
    if ($content -notmatch "import org.springframework.test.context.DynamicPropertyRegistry;") {
        $content = $content -replace "(import org\.junit\.jupiter\.api\.Test;)", "`$1`nimport org.springframework.test.context.DynamicPropertyRegistry;`nimport org.springframework.test.context.DynamicPropertySource;"
    }
    
    # Remove @Container annotation
    $content = $content -replace "\s*@Container\s*\n", "`n"
    
    # Replace PostgreSQL container creation
    $content = $content -replace "static PostgreSQLContainer<\?> postgres = PostgreSQLTestConstants\.createStandardContainer\(\);", "static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();`n`n    @DynamicPropertySource`n    static void configureProperties(DynamicPropertyRegistry registry) {`n        SharedTestContainers.configureSharedProperties(registry);`n    }"
    
    # Remove @Container import if no longer needed
    $content = $content -replace "import org\.testcontainers\.junit\.jupiter\.Container;\n", ""
    
    # Write back to file
    Set-Content -Path $file.FullName -Value $content -NoNewline
    
    Write-Host "  Fixed: $($file.Name)"
}

Write-Host "All test files have been processed!"
