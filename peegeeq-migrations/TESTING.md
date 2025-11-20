# Testing PeeGeeQ Migrations

This document describes how to test the peegeeq-migrations module.

---

## Automated Test Suite

We provide comprehensive automated test scripts that validate all migration functionality.

### Quick Start

**Windows:**
```powershell
cd peegeeq-migrations\scripts
.\test-migrations.ps1
```

**Linux/Mac:**
```bash
cd peegeeq-migrations/scripts
./test-migrations.sh
```

### What Gets Tested

The automated test suite validates:

1. **JAR Commands** - All Flyway commands work correctly
   - `migrate` - Apply migrations
   - `info` - Show migration status
   - `validate` - Validate checksums
   - `baseline` - Baseline existing database
   - `repair` - Repair metadata
   - `clean` - Clean database (with safety checks)

2. **Schema Validation** - All database objects are created
   - 9 tables (queue_messages, outbox, bitemporal_event_log, etc.)
   - 9 functions (notify_message_inserted, cleanup_old_metrics, etc.)
   - 2 views (bitemporal_current_state, bitemporal_latest_events)

3. **Error Handling** - Failures are handled gracefully
   - Invalid credentials
   - Missing safety flags
   - Connection failures

4. **Idempotency** - Running migrations multiple times is safe

5. **Dev Scripts** - Convenience scripts work correctly

### Test Results

The script provides clear pass/fail results:

```
═══════════════════════════════════════════════════════════
  TEST SUMMARY
═══════════════════════════════════════════════════════════

Total Tests: 18
Passed: 18
Failed: 0

🎉 ALL TESTS PASSED! 🎉
```

### Documentation

See **[scripts/TEST_SCRIPTS_README.md](scripts/TEST_SCRIPTS_README.md)** for:
- Detailed usage instructions
- Command-line options
- Troubleshooting guide
- CI/CD integration examples

---

## Manual Testing

If you prefer to test manually, follow these steps:

### 1. Build the JAR

```bash
mvn clean package -pl peegeeq-migrations -DskipTests
```

### 2. Set Environment Variables

```bash
# Windows PowerShell
$env:DB_JDBC_URL="jdbc:postgresql://localhost:5432/peegeeq_test"
$env:DB_USER="peegeeq_dev"
$env:DB_PASSWORD="peegeeq_dev"

# Linux/Mac
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_test
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev
```

### 3. Test Commands

```bash
cd peegeeq-migrations

# Show migration status (before)
java -jar target/peegeeq-migrations.jar info

# Apply migrations
java -jar target/peegeeq-migrations.jar migrate

# Show migration status (after)
java -jar target/peegeeq-migrations.jar info

# Validate migrations
java -jar target/peegeeq-migrations.jar validate
```

### 4. Verify Schema

```sql
-- Connect to database
psql -h localhost -U peegeeq_dev -d peegeeq_test

-- List tables
\dt

-- List functions
\df

-- List views
\dv

-- Check specific table
\d queue_messages
```

### 5. Test Dev Scripts

```bash
cd peegeeq-migrations/scripts

# Windows
.\dev-reset-db.bat

# Linux/Mac
./dev-reset-db.sh
```

---

## Integration Testing

To test migrations as part of your application integration tests:

### Using TestContainers

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    PostgreSQLTestConstants.POSTGRES_IMAGE
);

@BeforeAll
static void runMigrations() {
    // Set environment variables
    System.setProperty("DB_JDBC_URL", postgres.getJdbcUrl());
    System.setProperty("DB_USER", postgres.getUsername());
    System.setProperty("DB_PASSWORD", postgres.getPassword());
    
    // Run migrations
    RunMigrations.main(new String[]{"migrate"});
}
```

### Using Docker Compose

```yaml
# docker-compose.test.yml
services:
  postgres:
    image: postgres:15.13-alpine3.20
    environment:
      POSTGRES_DB: peegeeq_test
      POSTGRES_USER: peegeeq_dev
      POSTGRES_PASSWORD: peegeeq_dev
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U peegeeq_dev"]
      interval: 5s
      timeout: 5s
      retries: 5

  migrations:
    build:
      context: .
      dockerfile: peegeeq-migrations/Dockerfile
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_JDBC_URL: jdbc:postgresql://postgres:5432/peegeeq_test
      DB_USER: peegeeq_dev
      DB_PASSWORD: peegeeq_dev
    command: migrate
```

Run tests:
```bash
docker-compose -f docker-compose.test.yml up --abort-on-container-exit
```

---

## CI/CD Testing

### GitHub Actions

```yaml
name: Test Migrations

on: [push, pull_request]

jobs:
  test-migrations:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15.13-alpine3.20
        env:
          POSTGRES_DB: peegeeq_test
          POSTGRES_USER: peegeeq_dev
          POSTGRES_PASSWORD: peegeeq_dev
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
      
      - name: Run Migration Tests
        run: |
          cd peegeeq-migrations/scripts
          ./test-migrations.sh --verbose
```

---

## See Also

- **[PEEGEEQ_MIGRATIONS_README.md](PEEGEEQ_MIGRATIONS_README.md)** - Main documentation
- **[PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** - JAR command reference
- **[scripts/TEST_SCRIPTS_README.md](scripts/TEST_SCRIPTS_README.md)** - Automated test scripts guide

