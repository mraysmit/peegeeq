@echo off
REM PeeGeeQ Development Database Reset Script (Windows)
REM
REM This script provides a convenient way to reset your local development database
REM by cleaning and re-running all migrations.
REM
REM Usage:
REM   cd peegeeq-migrations\scripts
REM   dev-reset-db.bat                    # Use default local database
REM   dev-reset-db.bat custom_db_name     # Use custom database name
REM
REM Environment Variables (optional):
REM   DB_HOST      - Database host (default: localhost)
REM   DB_PORT      - Database port (default: 5432)
REM   DB_NAME      - Database name (default: peegeeq_dev)
REM   DB_USER      - Database user (default: peegeeq_dev)
REM   DB_PASSWORD  - Database password (default: peegeeq_dev)
REM   DB_SCHEMA    - Database schema (default: public)
REM

setlocal enabledelayedexpansion

REM Default configuration
if "%DB_HOST%"=="" set DB_HOST=localhost
if "%DB_PORT%"=="" set DB_PORT=5432
if "%DB_NAME%"=="" set DB_NAME=peegeeq_dev
if "%DB_USER%"=="" set DB_USER=peegeeq_dev
if "%DB_PASSWORD%"=="" set DB_PASSWORD=peegeeq_dev
if "%DB_SCHEMA%"=="" set DB_SCHEMA=public

REM Override DB_NAME if provided as argument
if not "%1"=="" set DB_NAME=%1

REM Construct JDBC URL
set DB_JDBC_URL=jdbc:postgresql://%DB_HOST%:%DB_PORT%/%DB_NAME%

echo ================================================================
echo        PeeGeeQ Development Database Reset
echo ================================================================
echo.
echo WARNING: This will DELETE ALL DATA in the database!
echo.
echo Database: %DB_NAME%
echo Host:     %DB_HOST%:%DB_PORT%
echo User:     %DB_USER%
echo Schema:   %DB_SCHEMA%
echo.
set /p CONFIRM="Are you sure you want to continue? (yes/no): "

if /i not "%CONFIRM%"=="yes" (
    echo.
    echo Aborted
    exit /b 1
)

echo.
echo Step 1: Building migrations JAR...
REM Navigate to the peegeeq-migrations module root (parent of scripts/)
cd /d "%~dp0\.."
call mvn clean package -DskipTests -q

if errorlevel 1 (
    echo.
    echo Failed to build migrations JAR
    exit /b 1
)

echo Done - Migrations JAR built successfully
echo.

echo Step 2: Running migrations (clean + migrate)...
set DB_CLEAN_ON_START=true

java -jar target\peegeeq-migrations.jar migrate

if errorlevel 1 (
    echo.
    echo Migration failed
    exit /b 1
)

echo.
echo ================================================================
echo   Database reset completed successfully!
echo ================================================================
echo.
echo Your database is now ready for development.
echo.
echo Next steps:
echo   * Run your application: mvn exec:java -pl peegeeq-rest
echo   * Run tests: mvn test -Pintegration-tests
echo.

endlocal

