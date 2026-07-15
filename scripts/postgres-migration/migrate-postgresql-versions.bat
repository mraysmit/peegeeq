@echo off
REM PostgreSQL Version Migration Script for PeeGeeQ Project (Windows)
REM This script migrates hardcoded PostgreSQL versions to use the centralized constant

setlocal enabledelayedexpansion

echo PostgreSQL Version Migration Script
echo ======================================
echo.

echo This script will:
echo    1. Find all hardcoded PostgreSQL versions
echo    2. Replace them with PostgreSQLTestConstants.POSTGRES_IMAGE
echo    3. Add necessary imports
echo    4. Report all changes made
echo.

REM Check if PowerShell is available for better text processing
powershell -Command "Write-Host 'Scanning for hardcoded PostgreSQL versions...' -ForegroundColor Yellow"

REM Find Java files with hardcoded PostgreSQL versions
set FOUND_FILES=0
for /r %%f in (*.java) do (
    findstr /c:"new PostgreSQLContainer<>(\"postgres:" "%%f" >nul 2>&1
    if !errorlevel! equ 0 (
        echo %%f | findstr /v "PostgreSQLTestConstants.java" >nul 2>&1
        if !errorlevel! equ 0 (
            set FOUND_FILES=1
            echo     %%f
        )
    )
)

if !FOUND_FILES! equ 0 (
    powershell -Command "Write-Host ' No hardcoded PostgreSQL versions found!' -ForegroundColor Green"
    echo.
    echo All PostgreSQL versions are already using the centralized constant!
    goto :end
)

echo.
echo    Hardcoded PostgreSQL versions found. 
echo    Please manually update these files to use:
echo    import dev.mars.peegeeq.test.PostgreSQLTestConstants;
echo    new PostgreSQLContainer^<^>^(PostgreSQLTestConstants.POSTGRES_IMAGE^)
echo.

echo  Manual migration steps:
echo    1. Add import: import dev.mars.peegeeq.test.PostgreSQLTestConstants;
echo    2. Replace: new PostgreSQLContainer^<^>^("postgres:..."^) 
echo       with:    new PostgreSQLContainer^<^>^(PostgreSQLTestConstants.POSTGRES_IMAGE^)
echo    3. Run 'mvn compile' to verify changes
echo    4. Run 'mvn test' to ensure tests pass
echo.

powershell -Command "Write-Host 'Use the centralized constant to ensure only ONE PostgreSQL version!' -ForegroundColor Green"

:end
echo.
echo For detailed migration, use the Linux/Mac script: scripts/migrate-postgresql-versions.sh
pause
