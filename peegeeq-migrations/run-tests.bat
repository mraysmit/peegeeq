@echo off
REM Test runner script for PeeGeeQ migrations
REM Runs all integration tests using Testcontainers

echo.
echo Running PeeGeeQ Migration Tests
echo ====================================
echo.

REM Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    echo Error: Docker is not running
    echo    Testcontainers requires Docker to be running
    echo    Please start Docker and try again
    exit /b 1
)

echo Docker is running
echo.

REM Change to migrations directory
cd /d "%~dp0"

REM Run tests
echo Running migration tests...
echo.

call mvn clean test

REM Check exit code
if errorlevel 1 (
    echo.
    echo Tests failed
    exit /b 1
) else (
    echo.
    echo All tests passed!
    echo.
    echo Tests verified:
    echo   * All migrations execute successfully
    echo   * All required tables exist
    echo   * All required indexes exist
    echo   * All required views exist
    echo   * All required functions exist
    echo   * All required triggers exist
    echo   * Schema structure is valid
    echo   * Migration conventions are followed
)
