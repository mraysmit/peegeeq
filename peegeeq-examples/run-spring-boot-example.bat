@echo off
REM PeeGeeQ Spring Boot Outbox Pattern Example Runner (Windows)
REM This script demonstrates how to run the complete Spring Boot integration example

echo ==========================================
echo PeeGeeQ Spring Boot Outbox Pattern Example
echo ==========================================
echo.

REM Check if Java is available
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Java is not installed or not in PATH
    echo    Please install Java 17+ to run this example
    exit /b 1
)

echo ✅ Java detected

REM Check if Maven is available
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Maven is not installed or not in PATH
    echo    Please install Maven to build and run this example
    exit /b 1
)

echo ✅ Maven detected

REM Set default database configuration
if not defined DB_HOST set DB_HOST=localhost
if not defined DB_PORT set DB_PORT=5432
if not defined DB_NAME set DB_NAME=peegeeq_example
if not defined DB_USERNAME set DB_USERNAME=peegeeq_user
if not defined DB_PASSWORD set DB_PASSWORD=peegeeq_password

echo.
echo Database Configuration:
echo   Host: %DB_HOST%
echo   Port: %DB_PORT%
echo   Database: %DB_NAME%
echo   Username: %DB_USERNAME%
echo   Password: [HIDDEN]
echo.

echo Building and running Spring Boot example...
echo.

REM Run the Spring Boot application
mvn spring-boot:run ^
    -pl peegeeq-examples ^
    -Dspring-boot.run.main-class="dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication" ^
    -Dspring-boot.run.profiles=springboot ^
    -Dspring-boot.run.jvmArguments="-Dpeegeeq.database.host=%DB_HOST% -Dpeegeeq.database.port=%DB_PORT% -Dpeegeeq.database.name=%DB_NAME% -Dpeegeeq.database.username=%DB_USERNAME% -Dpeegeeq.database.password=%DB_PASSWORD%"

echo.
echo Spring Boot example finished.
