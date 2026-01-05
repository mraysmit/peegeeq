# PeeGeeQ Migrations - Complete Guide

**Module**: `peegeeq-migrations`  
**Version**: 2.0.0  
**Date**: January 2026

This document is the **definitive guide** for the PeeGeeQ database migration module. It consolidates all previous documentation into a single reference.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Critical Setup](#2-critical-setup)
3. [Usage Reference (CLI/JAR)](#3-usage-reference-clijar)
4. [Deployment Patterns](#4-deployment-patterns)
5. [Testing & Verification](#5-testing--verification)
6. [Troubleshooting](#6-troubleshooting)
7. [Version Specific Notices](#7-version-specific-notices)
8. [Development Scripts Reference](#8-development-scripts-reference)
9. [Test Scripts Reference](#9-test-scripts-reference)

---

## 1. Overview

The `peegeeq-migrations` module contains Flyway database migration scripts that create and manage the complete PeeGeeQ database schema. It is **completely separate** from the application runtime and serves a specific purpose in your deployment workflow.

**Key Characteristics**:
- **Deployment-Only Module**: Never included in application runtime classpath
- **Flyway-Based**: Uses Flyway migration tool for version-controlled schema changes
- **Separate Deployment Step**: Runs independently, before application deployment
- **Version Tracked**: Maintains schema version history in `flyway_schema_history` table

---

## 2. Critical Setup

**Before using PeeGeeQ**, you **MUST** run database migrations to create required tables. This is the #1 issue reported by new users.

**Common errors if you skip this step:**
```
FATAL: dead_letter_queue table does not exist - schema not initialized properly
FATAL: bitemporal_event_log table does not exist - schema not initialized properly
FATAL: queue_messages table does not exist - schema not initialized properly
```

**Solution**: Follow the [Usage Reference](#3-usage-reference-clijar) below to run migrations.

---

## 3. Usage Reference (CLI/JAR)

This section covers the standalone migration runner JAR (`peegeeq-migrations.jar`).

### Building the JAR

```bash
# From project root
mvn clean package -pl peegeeq-migrations -DskipTests

# JAR location: peegeeq-migrations/target/peegeeq-migrations.jar (~15 MB shaded JAR)
```

### Configuration

All configuration is done via **environment variables**:

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `DB_JDBC_URL` | ✅ Yes | JDBC connection URL | `jdbc:postgresql://localhost:5432/peegeeq_dev` |
| `DB_USER` | ✅ Yes | Database username | `peegeeq_dev` |
| `DB_PASSWORD` | ✅ Yes | Database password | `peegeeq_dev` |
| `DB_SCHEMA` | ❌ No | Target database schema | `public` (default) or `myschema` |
| `DB_CLEAN_ON_START` | ❌ No | Clean database before migration (⚠️ dev only!) | `true` or `false` (default: `false`) |

### Commands

#### `migrate` (default)
Apply pending migrations to the database.

```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

#### `info`
Show migration status and history.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar info
```

#### `validate`
Validate that applied migrations match available migration files.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar validate
```

#### `repair`
Repair the Flyway metadata table. Use this to fix **checksum mismatches** (e.g., after V010 update) or failed migrations.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar repair
```

#### `clean`
Clean the database (⚠️ **DELETES ALL DATA** - dev only!).

```bash
export DB_CLEAN_ON_START=true
java -jar peegeeq-migrations/target/peegeeq-migrations.jar clean
```

---

## 4. Deployment Patterns

### Architecture Principles

1. **Separate migrations from application runtime**: Migrations run as a separate step before application deployment.
2. **Run migrations before deploying application**: Migrations complete successfully → Deploy application.
3. **Never run migrations from application code**: No Flyway in `@PostConstruct`.

### Pattern 1: CI/CD Pipeline (Recommended for Production)

**Best for**: Production, Staging, Test environments

**Implementation (Generic)**:
1. **Build Stage**: Build `peegeeq-migrations.jar`.
2. **Migrate Stage**: Run `java -jar peegeeq-migrations.jar migrate` against target DB.
3. **Deploy Stage**: Deploy application artifacts only if migration succeeds.

### Pattern 2: Docker Compose (Local Development)

**Best for**: Local development with Docker

```yaml
  migrations:
    build:
      context: .
      dockerfile: peegeeq-migrations/Dockerfile
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_JDBC_URL: jdbc:postgresql://postgres:5432/peegeeq_dev
      DB_USER: peegeeq_dev
      DB_PASSWORD: peegeeq_dev
      DB_CLEAN_ON_START: "true"  # Dev only!
    command: migrate
```

### Pattern 3: Dev Scripts (Recommended for Local Development)

**Best for**: Local development without Docker

```bash
# Linux/Mac
cd peegeeq-migrations/scripts
./dev-reset-db.sh

# Windows
cd peegeeq-migrations\scripts
dev-reset-db.bat
```

---

## 5. Testing & Verification

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
1. **JAR Commands**: `migrate`, `info`, `validate`, `repair`, `clean`.
2. **Schema Validation**: Checks existence of all tables, functions, and views.
3. **Idempotency**: Ensures running migrations multiple times is safe.

---

## 6. Troubleshooting

### Issue #1: Missing Tables Errors
**Cause**: You skipped database migrations.
**Solution**: Run `java -jar peegeeq-migrations.jar migrate`.

### Issue #2: Migration Checksum Mismatch
**Cause**: Migration file was modified after being applied (e.g., V010 update).
**Solution**:
```bash
# WARNING: Only do this if you are sure the change is safe!
java -jar peegeeq-migrations/target/peegeeq-migrations.jar repair
```

### Issue #3: Migration Failed
**Solution**:
1. Fix the SQL file or database state.
2. Run `repair` to fix metadata.
3. Run `migrate` again.

---

## 7. Version Specific Notices

### V010 Checksum Change (January 2026)

**Notice**: The migration script `V010__Create_Consumer_Group_Fanout_Tables.sql` was updated in January 2026 to fix a bug where schema-scoped objects were not being correctly detected when deploying to custom schemas (e.g., `peegeeq_configured`).

**Impact**:
- **New Deployments**: No impact. The fixed script will be applied normally.
- **Existing Deployments**: If you have already applied `V010` to a database, Flyway will report a **Checksum Mismatch** error because the file content has changed.

**Resolution for Existing Deployments**:
If you encounter a checksum mismatch for `V010`, you must run `repair` to update the checksum in the schema history table. The functional changes in the script are safe and only affect the `DO` blocks used for pre-flight checks.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar repair
```

---

## 8. Development Scripts Reference

Convenience scripts for running database migrations during local development are located in `peegeeq-migrations/scripts`.

### dev-reset-db.sh / dev-reset-db.bat

**Purpose**: Reset your local database by cleaning and re-running all migrations.

**What it does**:
1. Prompts for confirmation (destructive operation!)
2. Builds the migrations JAR
3. Cleans the database (drops all tables)
4. Runs all migrations from scratch

**Usage**:
```bash
# Use default database (peegeeq_dev)
./dev-reset-db.sh

# Use custom database name
./dev-reset-db.sh my_custom_db

# Override via environment variables
DB_NAME=test_db ./dev-reset-db.sh
```

**Environment Variables**:
- `DB_HOST` - Database host (default: `localhost`)
- `DB_PORT` - Database port (default: `5432`)
- `DB_NAME` - Database name (default: `peegeeq_dev`)
- `DB_USER` - Database user (default: `peegeeq_dev`)
- `DB_PASSWORD` - Database password (default: `peegeeq_dev`)
- `DB_SCHEMA` - Database schema (default: `public`)

### dev-migrate.sh / dev-migrate.bat

**Purpose**: Apply new migrations to an existing database without cleaning.

**What it does**:
1. Builds the migrations JAR
2. Runs pending migrations only

**Usage**:
```bash
# Use default database (peegeeq_dev)
./dev-migrate.sh

# Use custom database name
./dev-migrate.sh my_custom_db

# Override via environment variables
DB_NAME=test_db ./dev-migrate.sh
```

**Environment Variables**: Same as `dev-reset-db.sh`

### How They Work

Both scripts:
1. Navigate to the parent directory (`peegeeq-migrations/`)
2. Build the JAR: `mvn clean package -DskipTests`
3. Run the CLI: `java -jar target/peegeeq-migrations.jar migrate`

The scripts automatically handle:
- Building the JAR if it doesn't exist or is outdated
- Setting up environment variables for the CLI
- Providing sensible defaults for local development

---

## 9. Test Scripts Reference

Comprehensive automated test suite for the peegeeq-migrations module located in `peegeeq-migrations/scripts`.

### Overview

These scripts test all aspects of the migrations module:
- ✅ JAR command functionality (migrate, info, validate, baseline, repair, clean)
- ✅ Schema validation (tables, functions, views)
- ✅ Error handling (invalid credentials, missing safety flags)
- ✅ Idempotency (running migrations multiple times)
- ✅ Dev scripts functionality

### Prerequisites

1. **PostgreSQL running** on localhost:5432
2. **PostgreSQL superuser access** (for creating test databases)
3. **Java 21+** installed
4. **Maven** installed (unless using `--skip-build`)
5. **psql** command-line tool available

### Command Line Options

#### PowerShell (`test-migrations.ps1`)

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

#### Bash (`test-migrations.sh`)

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

### Options Explained

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

### Test Phases

1. **Prerequisites**: Check PostgreSQL connection, build JAR, create test database.
2. **JAR Command Tests**: Verify all CLI commands work.
3. **Schema Validation**: Verify tables and objects exist.
4. **Idempotency**: Verify migrations can run multiple times safely.
5. **Cleanup**: Drop test database (unless skipped).
