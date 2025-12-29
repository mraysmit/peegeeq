# Start the PeeGeeQ backend with TestContainers database connection

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$DbConfigFile = Join-Path $ProjectRoot testcontainers-db.json

# Check if the TestContainers config file exists
if (-not (Test-Path $DbConfigFile)) {
    Write-Host "TestContainers database config not found: $DbConfigFile" -ForegroundColor Red
    Write-Host "Please run the Playwright tests first to start the PostgreSQL container." -ForegroundColor Yellow
    exit 1
}

# Read connection details from JSON file
Write-Host "Reading TestContainers database connection details..." -ForegroundColor Cyan
$DbConfig = Get-Content $DbConfigFile | ConvertFrom-Json

Write-Host "TestContainers PostgreSQL connection:" -ForegroundColor Green
Write-Host "  Host: $($DbConfig.host)"
Write-Host "  Port: $($DbConfig.port)"
Write-Host "  Database: $($DbConfig.database)"
Write-Host "  Username: $($DbConfig.username)"

# Set environment variables for PeeGeeQ
$env:PEEGEEQ_DATABASE_HOST = $DbConfig.host
$env:PEEGEEQ_DATABASE_PORT = $DbConfig.port
$env:PEEGEEQ_DATABASE_NAME = $DbConfig.database
$env:PEEGEEQ_DATABASE_USERNAME = $DbConfig.username
$env:PEEGEEQ_DATABASE_PASSWORD = $DbConfig.password
$env:PEEGEEQ_DATABASE_SCHEMA = "public"

Write-Host ""
Write-Host "Starting PeeGeeQ REST API server with TestContainers database..." -ForegroundColor Cyan
Write-Host ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
$ManagementUiRoot = Split-Path -Parent $ScriptDir
$RepositoryRoot = Split-Path -Parent $ManagementUiRoot
Set-Location $RepositoryRoot

# Build the Maven command with system properties
$MavenArgs = @(
    "exec:java",
    "-pl", "peegeeq-rest",
    "-Dexec.systemProperties",
    "-DPEEGEEQ_DATABASE_HOST=$($DbConfig.host)",
    "-DPEEGEEQ_DATABASE_PORT=$($DbConfig.port)",
    "-DPEEGEEQ_DATABASE_NAME=$($DbConfig.database)",
    "-DPEEGEEQ_DATABASE_USERNAME=$($DbConfig.username)",
    "-DPEEGEEQ_DATABASE_PASSWORD=$($DbConfig.password)",
    "-DPEEGEEQ_DATABASE_SCHEMA=public"
)

& mvn $MavenArgs

