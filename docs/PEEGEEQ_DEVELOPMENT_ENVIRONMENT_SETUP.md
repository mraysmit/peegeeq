# PeeGeeQ Development Environment Setup Guide

This guide outlines the correct sequence for starting the dependent services required to run or test the PeeGeeQ Management UI.

## Architecture Dependency Chain

The system consists of three layers that must be started in order:

1.  **Database Layer** (PostgreSQL)
    *   *Dependency:* None
    *   *Provides:* Data persistence for the backend.
2.  **Backend Layer** (PeeGeeQ REST API)
    *   *Dependency:* Database Layer
    *   *Provides:* HTTP API for the UI.
3.  **Frontend Layer** (Management UI)
    *   *Dependency:* Backend Layer
    *   *Provides:* User Interface.

---

## Step 1: Start the Database Layer

The database runs in a Docker container defined in `docker-compose-local-dev.yml`.

**Command:**
Open a terminal at the project root and run:
```bash
docker-compose -f docker-compose-local-dev.yml up -d
```

**Verification:**
Ensure the container is running and healthy:
```bash
docker ps
# Look for "peegeeq-postgres-local" with status "Up (healthy)"
```

**Connection Details:**
*   **Port:** 5432
*   **Database:** `peegeeq_dev`
*   **User:** `peegeeq_dev`
*   **Password:** `peegeeq_dev`

---

## Step 2: Start the Backend Layer (REST API)

The REST API connects to the database and exposes endpoints on port 8080.

**Command:**
Open a **new** terminal window (do not reuse the previous one if you didn't use `-d`). Navigate to the project root:

```bash
cd peegeeq-rest
mvn compile exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.StartRestServer" -Dexec.args="8080"
```

**Verification:**
Wait for the log message:
`INFO d.m.peegeeq.rest.PeeGeeQRestServer - PeeGeeQ REST API server started on port 8080`

You can also check the health endpoint:
```bash
curl http://localhost:8080/health
# Should return {"status":"UP", ...}
```

---

## Step 3: Start the Frontend Layer (Management UI)

The UI connects to the REST API (proxied or direct).

### Option A: Run in Development Mode (Interactive)
Use this for active development.

**Command:**
Open a **third** terminal window.

```bash
cd peegeeq-management-ui
npm run dev
```

**Verification:**
Open your browser to `http://localhost:3000`. The UI should load and display data from the backend.

### Option B: Run E2E Tests
Use this to run the Playwright test suite.

**Command:**
Open a **third** terminal window.

```bash
cd peegeeq-management-ui
npm run test:e2e
```

*Note: The E2E tests require the Backend (Step 2) to be running.*

---

## Troubleshooting

### "Connection Refused" in UI
*   **Cause:** The REST API is not running.
*   **Fix:** Ensure Step 2 is completed and the server is listening on port 8080.

### "Connection Refused" in REST API Logs
*   **Cause:** The Database is not running.
*   **Fix:** Ensure Step 1 is completed and the Docker container is healthy.

### "Port 8080 already in use"
*   **Cause:** An old instance of the REST API is still running.
*   **Fix:** Find the process and kill it.
    *   **Windows (PowerShell):** `netstat -ano | findstr :8080` then `Stop-Process -Id <PID> -Force`
    *   **Linux/Mac:** `lsof -i :8080` then `kill -9 <PID>`
