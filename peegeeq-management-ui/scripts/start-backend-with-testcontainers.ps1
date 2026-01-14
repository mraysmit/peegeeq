# Start the PeeGeeQ backend with TestContainers database connection

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$DbConfigFile = Join-Path $ProjectRoot testcontainers-db.json
$ContainerName = "peegeeq-e2e-db"
$DbImage = "postgres:15.13-alpine3.20"

# --- Step 1: Ensure Database is Running ---

Write-Host "Checking database container '$ContainerName'..." -ForegroundColor Cyan

# Check if container exists (running or stopped)
$ContainerExists = docker ps -a -q -f name="^/${ContainerName}$"

if (-not $ContainerExists) {
    Write-Host "Starting new PostgreSQL container..." -ForegroundColor Green
    # Run detached, map random host port to 5432
    docker run -d --name $ContainerName -p 0:5432 -e POSTGRES_PASSWORD=postgres $DbImage
    
    Write-Host "Waiting for Database to be ready..."
    $MaxRetries = 30
    $RetryCount = 0
    $DbReady = $false

    while (-not $DbReady -and $RetryCount -lt $MaxRetries) {
        Start-Sleep -Seconds 1
        $RetryCount++
        
        # Check if Postgres is accepting connections
        try {
            docker exec $ContainerName pg_isready -U postgres -q
            if ($LASTEXITCODE -eq 0) {
                $DbReady = $true
            }
        } catch {
            # Ignore errors while waiting
        }
        Write-Host -NoNewline "."
    }
    Write-Host ""

    if (-not $DbReady) {
        Write-Error "Database failed to start after 30 seconds."
        exit 1
    }
    
    Write-Host "Creating 'peegeeq' user..."
    docker exec $ContainerName psql -U postgres -c "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    
    # Verify SUPERUSER privilege (Parity with global-setup-testcontainers.ts)
    $CheckResult = docker exec $ContainerName psql -U postgres -t -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'peegeeq';"
    if ($CheckResult.Trim() -ne 't') {
        Write-Error "Failed to verify peegeeq user SUPERUSER privilege."
        exit 1
    }
    
} else {
    # Check if it's actually running
    $IsRunning = docker ps -q -f name="^/${ContainerName}$"
    if (-not $IsRunning) {
        Write-Host "Starting existing container..." -ForegroundColor Yellow
        docker start $ContainerName
        Start-Sleep -Seconds 3
    } else {
        Write-Host "Container is already running." -ForegroundColor Yellow
    }
}

# --- Step 2: Get Connection Details & Write Config ---

# Get the dynamic port mapping (Format: 0.0.0.0:32768)
$PortMapping = docker port $ContainerName 5432
if (-not $PortMapping) {
    Write-Error "Could not determine port mapping for container $ContainerName"
    exit 1
}

# Extract just the port number
$DbPort = ($PortMapping -split ":")[-1]
$DbHost = "localhost"

Write-Host "Database is listening on $DbHost`:$DbPort" -ForegroundColor Green

$DbConfig = @{
    host = $DbHost
    port = [int]$DbPort
    database = "postgres"
    username = "peegeeq"
    password = "peegeeq"
    jdbcUrl = "postgres://peegeeq:peegeeq@$($DbHost):$($DbPort)/postgres"
}

# Write the config file so Playwright can find it later
$DbConfig | ConvertTo-Json -Depth 2 | Set-Content $DbConfigFile
Write-Host "Updated connection config: $DbConfigFile" -ForegroundColor Cyan

# --- Step 3: Start Backend ---

# Set environment variables for PeeGeeQ
$env:PEEGEEQ_DATABASE_HOST = $DbConfig.host
$env:PEEGEEQ_DATABASE_PORT = $DbConfig.port
$env:PEEGEEQ_DATABASE_NAME = $DbConfig.database
$env:PEEGEEQ_DATABASE_USERNAME = $DbConfig.username
$env:PEEGEEQ_DATABASE_PASSWORD = $DbConfig.password
$env:PEEGEEQ_DATABASE_SCHEMA = "public"

Write-Host ""
Write-Host "Starting PeeGeeQ REST API server..." -ForegroundColor Cyan
Write-Host ""

# Navigate to repository root (parent of peegeeq-management-ui) and start the server
$ManagementUiRoot = Split-Path -Parent $ScriptDir
$RepositoryRoot = Split-Path -Parent $ManagementUiRoot
Set-Location $RepositoryRoot

# Build the Maven command with system properties
$MavenArgs = @(
    "exec:java",
    "-pl", "peegeeq-rest",
    "-DPEEGEEQ_DATABASE_HOST=$($DbConfig.host)",
    "-DPEEGEEQ_DATABASE_PORT=$($DbConfig.port)",
    "-DPEEGEEQ_DATABASE_NAME=$($DbConfig.database)",
    "-DPEEGEEQ_DATABASE_USERNAME=$($DbConfig.username)",
    "-DPEEGEEQ_DATABASE_PASSWORD=$($DbConfig.password)",
    "-DPEEGEEQ_DATABASE_SCHEMA=public"
)

& mvn $MavenArgs

