@echo off
REM Start PeeGeeQ Backend and Run E2E Tests
REM This script starts the backend server and waits for it to be ready before running tests

echo Starting PeeGeeQ REST Server...
start "PeeGeeQ Backend" cmd /k "cd /d %~dp0peegeeq-rest && mvn compile exec:java -Dexec.mainClass=dev.mars.peegeeq.rest.StartRestServer"

echo Waiting for backend to start (30 seconds)...
timeout /t 30 /nobreak

echo Running E2E Tests...
cd /d %~dp0peegeeq-management-ui
call npm run test:e2e

echo.
echo Tests complete. Backend server is still running in separate window.
echo Close the "PeeGeeQ Backend" window to stop the server.
pause
