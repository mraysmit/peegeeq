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

# The REST server reads no PEEGEEQ_* environment variables or system properties. The UI
# creates setups with explicit connection coordinates from the connection file above.

Write-Host ""
Write-Host "Starting PeeGeeQ REST API server with TestContainers database..." -ForegroundColor Cyan
Write-Host ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
$ManagementUiRoot = Split-Path -Parent $ScriptDir
$RepositoryRoot = Split-Path -Parent $ManagementUiRoot
Set-Location $RepositoryRoot

# Build the Maven command
$MavenArgs = @(
    "exec:java",
    "-pl", "peegeeq-rest"
)

& mvn $MavenArgs

