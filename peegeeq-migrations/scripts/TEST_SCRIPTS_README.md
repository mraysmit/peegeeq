# PeeGeeQ Migrations - Automated Test Scripts

Comprehensive automated test suite for the peegeeq-migrations module.

---

## Overview

These scripts test all aspects of the migrations module:
- ✅ JAR command functionality (migrate, info, validate, baseline, repair, clean)
- ✅ Schema validation (tables, functions, views)
- ✅ Error handling (invalid credentials, missing safety flags)
- ✅ Idempotency (running migrations multiple times)
- ✅ Dev scripts functionality

---

## Quick Start

### Windows (PowerShell)

```powershell
cd peegeeq-migrations\scripts
.\test-migrations.ps1
```

### Linux/Mac (Bash)

```bash
cd peegeeq-migrations/scripts
./test-migrations.sh
```

---

## Prerequisites

1. **PostgreSQL running** on localhost:5432
2. **PostgreSQL superuser access** (for creating test databases)
3. **Java 21+** installed
4. **Maven** installed (unless using `--skip-build`)
5. **psql** command-line tool available

---

## Command Line Options

### PowerShell

```powershell
.\test-migrations.ps1 `
    -DbHost "localhost" `
    -DbPort "5432" `
    -DbName "peegeeq_migrations_test" `
    -DbUser "peegeeq_dev" `
    -DbPassword "peegeeq_dev" `
    -SkipBuild `
    -SkipCleanup `
    -Verbose
```

### Bash

```bash
./test-migrations.sh \
    --db-host localhost \
    --db-port 5432 \
    --db-name peegeeq_migrations_test \
    --db-user peegeeq_dev \
    --db-password peegeeq_dev \
    --skip-build \
    --skip-cleanup \
    --verbose
```

---

## Options Explained

| Option | Description | Default |
|--------|-------------|---------|
| `--db-host` / `-DbHost` | PostgreSQL host | `localhost` |
| `--db-port` / `-DbPort` | PostgreSQL port | `5432` |
| `--db-name` / `-DbName` | Test database name | `peegeeq_migrations_test` |
| `--db-user` / `-DbUser` | Database user | `peegeeq_dev` |
| `--db-password` / `-DbPassword` | Database password | `peegeeq_dev` |
| `--skip-build` / `-SkipBuild` | Skip building JAR (use existing) | `false` |
| `--skip-cleanup` / `-SkipCleanup` | Keep test database after tests | `false` |
| `--verbose` / `-Verbose` | Show detailed output | `false` |

---

## Test Phases

### Phase 1: Prerequisites
- ✅ Check PostgreSQL connection
- ✅ Build migrations JAR (unless `--skip-build`)
- ✅ Create test database

### Phase 2: JAR Command Tests
- ✅ `info` (before migration)
- ✅ `migrate` (apply migrations)
- ✅ `info` (after migration)
- ✅ `validate` (validate checksums)
- ✅ `migrate` (idempotency test)

### Phase 3: Schema Validation
- ✅ Verify all expected tables exist
- ✅ Verify all expected functions exist
- ✅ Verify all expected views exist

### Phase 4: Error Handling Tests
- ✅ Invalid credentials (should fail)
- ✅ `clean` without safety flag (should fail)
- ✅ `clean` with safety flag (should succeed)
- ✅ `migrate` after clean
- ✅ `baseline` command
- ✅ `repair` command

### Phase 5: Dev Scripts Tests
- ✅ Test `dev-migrate.sh` / `dev-migrate.bat`

### Phase 6: Cleanup
- ✅ Drop test database (unless `--skip-cleanup`)

---

## Example Output

```
═══════════════════════════════════════════════════════════
  PeeGeeQ Migrations - Automated Test Suite
═══════════════════════════════════════════════════════════

ℹ️  Test Configuration:
ℹ️    Database Host: localhost
ℹ️    Database Port: 5432
ℹ️    Database Name: peegeeq_migrations_test
ℹ️    Database User: peegeeq_dev

═══════════════════════════════════════════════════════════
  PHASE 1: Prerequisites
═══════════════════════════════════════════════════════════

🧪 Testing PostgreSQL connection...
✅ PostgreSQL Connection - PASSED
🧪 Building migrations JAR...
✅ Build Migrations JAR - PASSED
🧪 Creating test database: peegeeq_migrations_test
✅ Create Test Database - PASSED

═══════════════════════════════════════════════════════════
  PHASE 2: JAR Command Tests
═══════════════════════════════════════════════════════════

🧪 Testing: JAR Command: info (before migration)
✅ JAR Command: info (before migration) - PASSED
🧪 Testing: JAR Command: migrate
✅ JAR Command: migrate - PASSED
...

═══════════════════════════════════════════════════════════
  TEST SUMMARY
═══════════════════════════════════════════════════════════

Total Tests: 18
Passed: 18
Failed: 0

🎉 ALL TESTS PASSED! 🎉
```

---

## Troubleshooting

### PostgreSQL not available

```
❌ PostgreSQL Connection - FAILED: Cannot connect to PostgreSQL at localhost:5432
```

**Solution**: Start PostgreSQL:
```bash
# Linux
sudo systemctl start postgresql

# Mac
brew services start postgresql

# Windows
net start postgresql-x64-15
```

### JAR not found

```
❌ Build Migrations JAR - FAILED: JAR not found after build
```

**Solution**: Build manually first:
```bash
mvn clean package -pl peegeeq-migrations -DskipTests
```

### Permission denied (Linux/Mac)

```
bash: ./test-migrations.sh: Permission denied
```

**Solution**: Make script executable:
```bash
chmod +x test-migrations.sh
```

---

## CI/CD Integration

### GitHub Actions

```yaml
- name: Test Migrations
  run: |
    cd peegeeq-migrations/scripts
    ./test-migrations.sh --verbose
```

### GitLab CI

```yaml
test-migrations:
  script:
    - cd peegeeq-migrations/scripts
    - ./test-migrations.sh --verbose
```

---

## See Also

- **[PEEGEEQ_MIGRATIONS_README.md](../PEEGEEQ_MIGRATIONS_README.md)** - Main migrations documentation
- **[PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](../PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** - JAR command reference
- **[PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md)** - Dev scripts guide

