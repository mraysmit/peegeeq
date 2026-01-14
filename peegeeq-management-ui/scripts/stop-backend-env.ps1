# Stop the PeeGeeQ E2E test environment
# This stops the Docker container and removes the config file

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$DbConfigFile = Join-Path $ProjectRoot testcontainers-db.json
$ContainerName = "peegeeq-e2e-db"

Write-Host "Stopping E2E test environment..." -ForegroundColor Cyan

# Stop the container if it's running
$ContainerExists = docker ps -a -q -f name="^/${ContainerName}$"
if ($ContainerExists) {
    Write-Host "Stopping container '$ContainerName'..." -ForegroundColor Yellow
    docker stop $ContainerName | Out-Null
    Write-Host "✅ Container stopped" -ForegroundColor Green
} else {
    Write-Host "Container '$ContainerName' not found (already stopped or removed)" -ForegroundColor Yellow
}

# Remove the config file
if (Test-Path $DbConfigFile) {
    Remove-Item -Path $DbConfigFile -Force
    Write-Host "✅ Removed config file: $DbConfigFile" -ForegroundColor Green
} else {
    Write-Host "Config file not found (already removed)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Environment stopped." -ForegroundColor Green
Write-Host "To completely remove the container, run: docker rm $ContainerName" -ForegroundColor Cyan
