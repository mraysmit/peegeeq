# PeeGeeQ Quick Start Guide

**Get up and running with PeeGeeQ in under 5 minutes.**

---

## Prerequisites

-   **Java 21+**
-   **Docker** (Running)
-   **Maven** 3.9+

---

## Option 1: The "Instant" Demo (Recommended)

The fastest way to see PeeGeeQ in action is the self-contained demo. This script compiles the project, starts a PostgreSQL container, and runs a demo application that showcases queues, pub/sub, and event sourcing.

**Run the command:**

```bash
# Linux / macOS
./scripts/run-self-contained-demo.sh

# Windows
./scripts/run-self-contained-demo.bat
```

**What happens:**
1.  Builds the project.
2.  Starts a Docker container for PostgreSQL.
3.  Creates a "Setup" (Database tenant).
4.  Sends and receives messages.
5.  Cleans up everything.

---

## Option 2: Running the Full Stack (Backend + UI)

If you want to explore the Management UI and interact with the API manually.

### 1. Start the Database
You can use your own Postgres instance or start one with Docker:
```bash
docker run -d --name peegeeq-postgres -p 5432:5432 -e POSTGRES_PASSWORD=password postgres:16
```

### 2. Start the Backend
Open a terminal in the project root:
```bash
# Configure DB connection via environment variables (optional if using defaults)
export PEEGEEQ_DATABASE_HOST=localhost
export PEEGEEQ_DATABASE_PORT=5432
export PEEGEEQ_DATABASE_USERNAME=postgres
export PEEGEEQ_DATABASE_PASSWORD=password

# Run the server
mvn compile exec:java -pl peegeeq-rest -Dexec.mainClass="dev.mars.peegeeq.rest.StartRestServer"
```
*Server will start on `http://localhost:8080`*

### 3. Start the Management UI
Open a new terminal in `peegeeq-management-ui`:
```bash
cd peegeeq-management-ui
npm install
npm run dev
```
*UI will start on `http://localhost:3000`*

---

## Next Steps

-   **Explore the API**: Visit `http://localhost:8080/api/v1/health`
-   **Read the Guide**: [PeeGeeQ Complete Guide](PEEGEEQ_COMPLETE_GUIDE.md)
-   **Run Tests**: See the [Testing Guide](PEEGEEQ_TESTING_GUIDE.md)
