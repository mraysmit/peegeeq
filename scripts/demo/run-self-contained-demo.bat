@echo off
REM PeeGeeQ Self-Contained Demo Runner for Windows
REM This script runs the self-contained demo that uses TestContainers

echo.
echo ========================================
echo  PeeGeeQ Self-Contained Demo
echo ========================================
echo.
echo This demo will:
echo  - Start a PostgreSQL container automatically
echo  - Demonstrate all PeeGeeQ production features
echo  - Clean up when finished
echo.
echo Requirements:
echo  - Docker Desktop running
echo  - Internet connection (to download PostgreSQL image)
echo.

REM Check if Docker is running
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker is not running or not accessible
    echo Please start Docker Desktop and try again
    pause
    exit /b 1
)

echo Docker is running - proceeding with demo...
echo.

REM Run the demo
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.PeeGeeQSelfContainedDemo" -pl peegeeq-examples

if %errorlevel% neq 0 (
    echo.
    echo ERROR: Demo failed to run
    echo Check the output above for details
    pause
    exit /b 1
)

echo.
echo ========================================
echo  Demo completed successfully!
echo ========================================
pause
