#!/usr/bin/env pwsh
# Automated Test Script for PeeGeeQ Migrations Module
# Tests all JAR commands, dev scripts, and validates schema creation

param(
    [string]$DbHost = "localhost",
    [string]$DbPort = "5432",
    [string]$DbName = "peegeeq_migrations_test",
    [string]$DbUser = "peegeeq_dev",
    [string]$DbPassword = "peegeeq_dev",
    [switch]$SkipBuild,
    [switch]$SkipCleanup,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"
$script:TestsPassed = 0
$script:TestsFailed = 0
$script:TestResults = @()

# Colors
function Write-Success { Write-Host "✅ $args" -ForegroundColor Green }
function Write-Failure { Write-Host "❌ $args" -ForegroundColor Red }
function Write-Info { Write-Host "ℹ️  $args" -ForegroundColor Cyan }
function Write-Test { Write-Host "🧪 $args" -ForegroundColor Yellow }
function Write-Section { 
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Magenta
    Write-Host "  $args" -ForegroundColor Magenta
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Magenta
    Write-Host ""
}

# Test result tracking
function Record-TestResult {
    param([string]$TestName, [bool]$Passed, [string]$Message = "")
    
    if ($Passed) {
        $script:TestsPassed++
        Write-Success "$TestName - PASSED"
    } else {
        $script:TestsFailed++
        Write-Failure "$TestName - FAILED: $Message"
    }
    
    $script:TestResults += [PSCustomObject]@{
        Test = $TestName
        Passed = $Passed
        Message = $Message
    }
}

# Run command and capture output
function Invoke-TestCommand {
    param(
        [string]$Command,
        [bool]$ExpectFailure = $false
    )

    if ($Verbose) {
        Write-Info "Running: $Command"
    }

    try {
        # Run command directly and capture all output with unlimited width
        $output = Invoke-Expression $Command 2>&1 | Out-String -Width 4096
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }

        if ($Verbose) {
            Write-Host $output -ForegroundColor Gray
            Write-Host "Exit Code: $exitCode" -ForegroundColor Gray
        }

        # Check if command succeeded based on exit code
        $commandSucceeded = ($exitCode -eq 0)

        if (-not $commandSucceeded -and -not $ExpectFailure) {
            return @{ Success = $false; Output = $output; ExitCode = $exitCode }
        }

        if ($commandSucceeded -and $ExpectFailure) {
            return @{ Success = $false; Output = $output; ExitCode = $exitCode }
        }

        return @{ Success = $true; Output = $output; ExitCode = $exitCode }
    } catch {
        if ($ExpectFailure) {
            return @{ Success = $true; Output = $_.Exception.Message; ExitCode = 1 }
        }
        return @{ Success = $false; Output = $_.Exception.Message; ExitCode = 1 }
    }
}

# Check if PostgreSQL is running
function Test-PostgreSQLConnection {
    Write-Test "Testing PostgreSQL connection..."

    # Try Docker first (most common for local dev)
    try {
        $dockerResult = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "SELECT 1;" 2>&1
        if ($LASTEXITCODE -eq 0) {
            $script:UseDocker = $true
            Write-Info "Using Docker container: peegeeq-postgres-local"
            Record-TestResult "PostgreSQL Connection" $true
            return $true
        }
    } catch {
        # Docker not available, try local psql
    }

    # Fall back to local psql
    try {
        $result = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d peegeeq_dev -c 'SELECT 1;'" 2>&1
        if ($LASTEXITCODE -eq 0) {
            $script:UseDocker = $false
            Write-Info "Using local PostgreSQL"
            Record-TestResult "PostgreSQL Connection" $true
            return $true
        }
    } catch {
        # psql not available
    }

    Record-TestResult "PostgreSQL Connection" $false "Cannot connect to PostgreSQL. Try: docker-compose -f docker-compose-local-dev.yml up -d"
    return $false
}

# Create test database
function Initialize-TestDatabase {
    Write-Test "Creating test database: $DbName"

    if ($script:UseDocker) {
        # Drop if exists
        $null = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "DROP DATABASE IF EXISTS $DbName;" 2>&1

        # Create database
        $createResult = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "CREATE DATABASE $DbName;" 2>&1

        if ($LASTEXITCODE -eq 0) {
            Record-TestResult "Create Test Database" $true
            return $true
        } else {
            Record-TestResult "Create Test Database" $false "Failed to create database"
            return $false
        }
    } else {
        # Drop if exists
        $null = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d peegeeq_dev -c 'DROP DATABASE IF EXISTS $DbName;'" 2>&1

        # Create database
        $result = Invoke-TestCommand "psql -h $DbHost -p $DbPort -U $DbUser -d peegeeq_dev -c 'CREATE DATABASE $DbName;'"

        if ($result.Success) {
            Record-TestResult "Create Test Database" $true
            return $true
        } else {
            Record-TestResult "Create Test Database" $false $result.Output
            return $false
        }
    }
}

# Set environment variables
function Set-MigrationEnvironment {
    $env:DB_JDBC_URL = "jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
    $env:DB_USER = $DbUser
    $env:DB_PASSWORD = $DbPassword
    
    Write-Info "Environment configured:"
    Write-Info "  DB_JDBC_URL: $env:DB_JDBC_URL"
    Write-Info "  DB_USER: $env:DB_USER"
}

# Build the JAR
function Build-MigrationsJar {
    Write-Test "Building migrations JAR..."
    
    Push-Location (Split-Path $PSScriptRoot -Parent)
    Push-Location ..
    
    try {
        $result = Invoke-TestCommand "mvn clean package -pl peegeeq-migrations -DskipTests -q"
        
        Pop-Location
        Pop-Location
        
        if ($result.Success -and (Test-Path "peegeeq-migrations/target/peegeeq-migrations.jar")) {
            Record-TestResult "Build Migrations JAR" $true
            return $true
        } else {
            Record-TestResult "Build Migrations JAR" $false "JAR not found after build"
            return $false
        }
    } catch {
        Pop-Location
        Pop-Location
        Record-TestResult "Build Migrations JAR" $false $_.Exception.Message
        return $false
    }
}

# Test JAR command
function Test-JarCommand {
    param(
        [string]$Command,
        [string]$TestName,
        [bool]$ExpectFailure = $false,
        [string]$ExpectedOutput = ""
    )

    Write-Test "Testing: $TestName"

    $jarPath = "peegeeq-migrations/target/peegeeq-migrations.jar"
    $result = Invoke-TestCommand "java -jar $jarPath $Command" -ExpectFailure $ExpectFailure

    if ($ExpectFailure) {
        if ($result.ExitCode -ne 0) {
            Record-TestResult $TestName $true
            return $true
        } else {
            Record-TestResult $TestName $false "Expected failure but command succeeded"
            return $false
        }
    }

    if (-not $result.Success) {
        Record-TestResult $TestName $false $result.Output
        return $false
    }

    if ($ExpectedOutput -and $result.Output -notmatch $ExpectedOutput) {
        Record-TestResult $TestName $false "Output did not contain expected text: $ExpectedOutput"
        return $false
    }

    Record-TestResult $TestName $true
    return $true
}

# Test schema validation
function Test-SchemaValidation {
    Write-Test "Validating database schema..."

    $expectedTables = @(
        "schema_version",
        "outbox",
        "outbox_consumer_groups",
        "queue_messages",
        "message_processing",
        "dead_letter_queue",
        "queue_metrics",
        "connection_pool_metrics",
        "bitemporal_event_log"
    )

    $expectedFunctions = @(
        "notify_message_inserted",
        "update_message_processing_updated_at",
        "cleanup_completed_message_processing",
        "register_consumer_group_for_existing_messages",
        "create_consumer_group_entries_for_new_message",
        "cleanup_completed_outbox_messages",
        "notify_bitemporal_event",
        "cleanup_old_metrics",
        "get_events_as_of_time"
    )

    $expectedViews = @(
        "bitemporal_current_state",
        "bitemporal_latest_events"
    )

    $allValid = $true

    # Check tables
    foreach ($table in $expectedTables) {
        $query = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = '$table');"

        if ($script:UseDocker) {
            $result = docker exec peegeeq-postgres-local psql -U $DbUser -d $DbName -t -c "$query" 2>&1
        } else {
            $result = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -c `"$query`"" 2>&1
        }

        if ($result -match "t") {
            if ($Verbose) { Write-Success "  Table exists: $table" }
        } else {
            Write-Failure "  Table missing: $table"
            $allValid = $false
        }
    }

    # Check functions
    foreach ($func in $expectedFunctions) {
        $query = "SELECT EXISTS (SELECT FROM pg_proc WHERE proname = '$func');"

        if ($script:UseDocker) {
            $result = docker exec peegeeq-postgres-local psql -U $DbUser -d $DbName -t -c "$query" 2>&1
        } else {
            $result = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -c `"$query`"" 2>&1
        }

        if ($result -match "t") {
            if ($Verbose) { Write-Success "  Function exists: $func" }
        } else {
            Write-Failure "  Function missing: $func"
            $allValid = $false
        }
    }

    # Check views
    foreach ($view in $expectedViews) {
        $query = "SELECT EXISTS (SELECT FROM information_schema.views WHERE table_name = '$view');"

        if ($script:UseDocker) {
            $result = docker exec peegeeq-postgres-local psql -U $DbUser -d $DbName -t -c "$query" 2>&1
        } else {
            $result = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -c `"$query`"" 2>&1
        }

        if ($result -match "t") {
            if ($Verbose) { Write-Success "  View exists: $view" }
        } else {
            Write-Failure "  View missing: $view"
            $allValid = $false
        }
    }

    Record-TestResult "Schema Validation" $allValid
    return $allValid
}

# Test dev scripts
function Test-DevScripts {
    Write-Test "Testing dev scripts..."

    Push-Location $PSScriptRoot

    try {
        # Test dev-migrate.bat
        if ($IsWindows -or $env:OS -match "Windows") {
            $result = Invoke-TestCommand ".\dev-migrate.bat"
            Record-TestResult "Dev Script: dev-migrate.bat" $result.Success $result.Output
        } else {
            $result = Invoke-TestCommand "./dev-migrate.sh"
            Record-TestResult "Dev Script: dev-migrate.sh" $result.Success $result.Output
        }

        Pop-Location
        return $result.Success
    } catch {
        Pop-Location
        Record-TestResult "Dev Scripts" $false $_.Exception.Message
        return $false
    }
}

# Cleanup test database
function Remove-TestDatabase {
    if ($SkipCleanup) {
        Write-Info "Skipping cleanup (--SkipCleanup flag set)"
        return
    }

    Write-Test "Cleaning up test database..."

    if ($script:UseDocker) {
        $null = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "DROP DATABASE IF EXISTS $DbName;" 2>&1
    } else {
        $null = Invoke-Expression "psql -h $DbHost -p $DbPort -U $DbUser -d peegeeq_dev -c 'DROP DATABASE IF EXISTS $DbName;'" 2>&1
    }

    Write-Success "Test database dropped"
}

# Print summary
function Show-TestSummary {
    Write-Section "TEST SUMMARY"

    Write-Host "Total Tests: $($script:TestsPassed + $script:TestsFailed)" -ForegroundColor White
    Write-Host "Passed: $script:TestsPassed" -ForegroundColor Green
    Write-Host "Failed: $script:TestsFailed" -ForegroundColor Red
    Write-Host ""

    if ($script:TestsFailed -gt 0) {
        Write-Host "Failed Tests:" -ForegroundColor Red
        foreach ($result in $script:TestResults | Where-Object { -not $_.Passed }) {
            Write-Host "  ❌ $($result.Test): $($result.Message)" -ForegroundColor Red
        }
        Write-Host ""
    }

    if ($script:TestsFailed -eq 0) {
        Write-Host "🎉 ALL TESTS PASSED! 🎉" -ForegroundColor Green
        exit 0
    } else {
        Write-Host "💔 SOME TESTS FAILED 💔" -ForegroundColor Red
        exit 1
    }
}

# Main test execution
function Start-MigrationTests {
    Write-Section "PeeGeeQ Migrations - Automated Test Suite"

    Write-Info "Test Configuration:"
    Write-Info "  Database Host: $DbHost"
    Write-Info "  Database Port: $DbPort"
    Write-Info "  Database Name: $DbName"
    Write-Info "  Database User: $DbUser"
    Write-Info "  Skip Build: $SkipBuild"
    Write-Info "  Skip Cleanup: $SkipCleanup"
    Write-Info "  Verbose: $Verbose"
    Write-Host ""

    # Prerequisites
    Write-Section "PHASE 1: Prerequisites"

    if (-not (Test-PostgreSQLConnection)) {
        Write-Failure "PostgreSQL is not available. Please start PostgreSQL and try again."
        Show-TestSummary
        return
    }

    if (-not $SkipBuild) {
        if (-not (Build-MigrationsJar)) {
            Write-Failure "Failed to build migrations JAR. Cannot continue."
            Show-TestSummary
            return
        }
    } else {
        Write-Info "Skipping build (--SkipBuild flag set)"
        if (-not (Test-Path "peegeeq-migrations/target/peegeeq-migrations.jar")) {
            Write-Failure "JAR not found and --SkipBuild specified. Build first or remove flag."
            Show-TestSummary
            return
        }
    }

    if (-not (Initialize-TestDatabase)) {
        Write-Failure "Failed to create test database. Cannot continue."
        Show-TestSummary
        return
    }

    Set-MigrationEnvironment

    # Test JAR Commands
    Write-Section "PHASE 2: JAR Command Tests"

    Test-JarCommand "info" "JAR Command: info (before migration)"
    Test-JarCommand "migrate" "JAR Command: migrate" -ExpectedOutput "successfully"
    Test-JarCommand "info" "JAR Command: info (after migration)" -ExpectedOutput "Success"
    Test-JarCommand "validate" "JAR Command: validate"
    Test-JarCommand "migrate" "JAR Command: migrate (idempotency)" -ExpectedOutput "Migrations executed: 0"

    # Test Schema
    Write-Section "PHASE 3: Schema Validation"

    Test-SchemaValidation

    # Test Error Handling
    Write-Section "PHASE 4: Error Handling Tests"

    # Test invalid credentials
    $originalUser = $env:DB_USER
    $env:DB_USER = "invalid_user_12345"
    Test-JarCommand "info" "JAR Command: info (invalid credentials)" -ExpectFailure $true
    $env:DB_USER = $originalUser

    # Test clean without safety flag
    Remove-Item Env:\DB_CLEAN_ON_START -ErrorAction SilentlyContinue
    Test-JarCommand "clean" "JAR Command: clean (without safety flag)" -ExpectFailure $true

    # Test clean with safety flag
    $env:DB_CLEAN_ON_START = "true"
    Test-JarCommand "clean" "JAR Command: clean (with safety flag)"

    # Re-migrate after clean
    Test-JarCommand "migrate" "JAR Command: migrate (after clean)"

    # Test baseline - requires a fresh database with no Flyway history
    # Drop and recreate the test database
    if ($script:UseDocker) {
        $null = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "DROP DATABASE IF EXISTS ${DbName}_baseline;" 2>&1
        $null = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "CREATE DATABASE ${DbName}_baseline;" 2>&1
    }

    # Temporarily change DB URL for baseline test
    $originalDbUrl = $env:DB_JDBC_URL
    $env:DB_JDBC_URL = "jdbc:postgresql://${DbHost}:${DbPort}/${DbName}_baseline"
    Test-JarCommand "baseline" "JAR Command: baseline"
    $env:DB_JDBC_URL = $originalDbUrl

    # Clean up baseline test database
    if ($script:UseDocker) {
        $null = docker exec peegeeq-postgres-local psql -U peegeeq_dev -d peegeeq_dev -c "DROP DATABASE IF EXISTS ${DbName}_baseline;" 2>&1
    }

    # Test repair
    Test-JarCommand "repair" "JAR Command: repair"

    # Test Dev Scripts
    Write-Section "PHASE 5: Dev Scripts Tests"

    Test-DevScripts

    # Cleanup
    Write-Section "PHASE 6: Cleanup"

    Remove-TestDatabase

    # Summary
    Show-TestSummary
}

# Run tests
Start-MigrationTests

