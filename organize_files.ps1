#!/usr/bin/env pwsh

# Move test files to examples test directory
$testFiles = @(
    "recovered_SpringBootOutboxConfigurationExampleTest.java",
    "recovered_TransactionParticipationAdvancedExampleTest.java",
    "recovered_blob_28.java",  # SpringBootOutboxConfigurationExampleTest
    "recovered_blob_29.java",  # TransactionParticipationAdvancedExampleTest  
    "recovered_blob_30.java",  # TransactionParticipationAdvancedExampleTest (duplicate)
    "recovered_blob_32.java",  # AutomaticTransactionManagementExampleTest
    "recovered_blob_39.java",  # BasicReactiveOperationsExampleTest
    "recovered_blob_52.java",  # SpringBootOutboxExampleTest
    "recovered_blob_62.java",  # TransactionParticipationExampleTest
    "recovered_blob_64.java",  # ReactiveOutboxBasicExampleTest
    "recovered_blob_133.java", # SimpleSpringBootIntegrationTest
    "recovered_blob_166.java", # SimpleSpringBootTest
    "recovered_blob_167.java", # BasicTestExample
    "recovered_blob_168.java", # MinimalSpringBootTest
    "recovered_blob_170.java", # BasicCompilationTest
    "recovered_blob_92.java",  # PeeGeeQExampleTest
    "recovered_blob_93.java"   # PeeGeeQExampleTest
)

foreach ($file in $testFiles) {
    if (Test-Path $file) {
        # Get the class name from the file
        $content = Get-Content $file -Raw
        if ($content -match "class\s+(\w+)") {
            $className = $matches[1]
            $targetFile = "peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\$className.java"
            
            Write-Host "Moving $file to $targetFile"
            
            # Backup existing file if it exists
            if (Test-Path $targetFile) {
                Move-Item $targetFile "$targetFile.backup" -Force
            }
            
            Move-Item $file $targetFile -Force
        } else {
            Write-Host "Could not determine class name for $file"
        }
    }
}

# Move API files to appropriate modules
$apiFiles = @(
    @{ File = "recovered_blob_114.java"; Target = "peegeeq-db\src\main\java\dev\mars\peegeeq\db\provider\PgQueueFactory.java" },
    @{ File = "recovered_blob_115.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_122.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_128.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_132.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_135.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_144.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_158.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" },
    @{ File = "recovered_blob_162.java"; Target = "peegeeq-api\src\main\java\dev\mars\peegeeq\api\" }
)

foreach ($apiFile in $apiFiles) {
    $file = $apiFile.File
    if (Test-Path $file) {
        $content = Get-Content $file -Raw
        if ($content -match "public\s+(?:abstract\s+)?class\s+(\w+)") {
            $className = $matches[1]
            
            # Determine the correct subdirectory based on package
            $targetDir = $apiFile.Target
            if ($content -match "package\s+([^;]+)") {
                $package = $matches[1]
                $packagePath = $package -replace "dev\.mars\.peegeeq\.api\.?", "" -replace "\.", "\"
                if ($packagePath) {
                    $targetDir = $targetDir + $packagePath + "\"
                }
            }
            
            $targetFile = $targetDir + "$className.java"
            Write-Host "Moving $file to $targetFile"
            
            # Create directory if it doesn't exist
            $targetDirPath = Split-Path $targetFile -Parent
            if (-not (Test-Path $targetDirPath)) {
                New-Item -ItemType Directory -Force -Path $targetDirPath | Out-Null
            }
            
            # Backup existing file if it exists
            if (Test-Path $targetFile) {
                Move-Item $targetFile "$targetFile.backup" -Force
            }
            
            Move-Item $file $targetFile -Force
        }
    }
}

Write-Host "File organization complete!"
