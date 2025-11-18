# PeeGeeQ Migrations - Implementation Notes

## Overview

This module provides database migration infrastructure for PeeGeeQ using Flyway 10.10.0.

## What Was Implemented (2025-11-18)

### 1. Standalone Migration CLI

**File**: `src/main/java/dev/mars/peegeeq/migrations/RunMigrations.java`

A standalone Java CLI that can run Flyway migrations without Maven. Supports all Flyway commands:
- `migrate` - Apply pending migrations
- `info` - Show migration status
- `validate` - Validate migrations
- `baseline` - Baseline existing database
- `repair` - Repair metadata
- `clean` - Clean database (dev only, requires `DB_CLEAN_ON_START=true`)

Configuration via environment variables:
- `DB_JDBC_URL` (required)
- `DB_USER` (required)
- `DB_PASSWORD` (required)
- `DB_CLEAN_ON_START` (optional, default: false)

### 2. Executable JAR Build

**File**: `pom.xml`

Changed from `<packaging>pom</packaging>` to `<packaging>jar</packaging>` and added:
- Flyway Core dependency
- Flyway PostgreSQL database support
- Maven Shade Plugin to create fat JAR with all dependencies
- Main class: `dev.mars.peegeeq.migrations.RunMigrations`

Build command:
```bash
mvn clean package -DskipTests
```

Output: `target/peegeeq-migrations.jar` (~15 MB)

### 3. Dev Convenience Scripts

**Files**:
- `scripts/dev-reset-db.sh` (Linux/Mac) - Clean and migrate
- `scripts/dev-reset-db.bat` (Windows) - Clean and migrate
- `scripts/dev-migrate.sh` (Linux/Mac) - Migrate only
- `scripts/dev-migrate.bat` (Windows) - Migrate only

Features:
- Automatic JAR building
- Sensible defaults (peegeeq_dev database)
- Environment variable overrides
- Interactive confirmation for destructive operations
- Colored output (shell scripts)

Usage:
```bash
cd peegeeq-migrations/scripts
./dev-reset-db.sh              # Reset database
./dev-migrate.sh               # Migrate only
```

### 4. Docker Support

**Files**:
- `Dockerfile` - Multi-stage build for container image
- `docker-compose.dev.yml` - Complete dev environment

Build Docker image:
```bash
docker build -t peegeeq-migrations:latest -f peegeeq-migrations/Dockerfile .
```

Run migrations:
```bash
docker run --rm \
  -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq \
  -e DB_USER=peegeeq \
  -e DB_PASSWORD=secret \
  peegeeq-migrations:latest migrate
```

### 5. Comprehensive Documentation

**Files**:
- `QUICKSTART.md` - 2-minute quick start guide
- `CLI_USAGE.md` - Detailed CLI reference
- `MIGRATION_PATTERNS.md` - Deployment patterns for all environments
- `README.md` - Updated with CLI usage and deployment patterns

## Architecture Principles

### Separation of Concerns

✅ **Migrations are separate from application runtime**
- This module has Flyway dependencies
- Core modules (`peegeeq-db`, `peegeeq-rest`, `peegeeq-native`) have NO Flyway dependencies
- Migrations run as a separate step before deploying the application

✅ **Never run migrations from application code**
- No "DDL on startup" magic
- No race conditions between multiple pods
- Clear separation between schema lifecycle and application lifecycle

### Deployment Patterns

1. **Kubernetes Job** (Production) - Recommended
   - Run as a Job before deploying application
   - Proper error handling and exit codes
   - Credentials from Kubernetes Secrets

2. **Kubernetes InitContainer** (Production) - Alternative
   - Simpler but less observable
   - Runs before main container starts

3. **CI/CD Pipeline** (All Environments)
   - Run migrations as a separate pipeline step
   - Deploy application only if migrations succeed

4. **Docker Compose** (Local Development)
   - Complete stack with PostgreSQL + migrations
   - One command to start everything

5. **Dev Scripts** (Local Development)
   - Fastest iteration for developers
   - One command to reset database

## File Structure

```
peegeeq-migrations/
├── src/main/
│   ├── java/dev/mars/peegeeq/migrations/
│   │   └── RunMigrations.java          # Standalone CLI
│   └── resources/db/migration/
│       ├── V001__Create_Base_Tables.sql
│       └── V002__Create_Message_Processing_Table.sql
├── scripts/
│   ├── dev-reset-db.sh                  # Dev script (Linux/Mac)
│   ├── dev-reset-db.bat                 # Dev script (Windows)
│   ├── dev-migrate.sh                   # Dev script (Linux/Mac)
│   └── dev-migrate.bat                  # Dev script (Windows)
├── Dockerfile                           # Container image
├── docker-compose.dev.yml               # Dev environment
├── pom.xml                              # Maven build config
├── PEEGEEQ_MIGRATIONS_QUICKSTART.md     # Quick start guide
├── CLI_USAGE.md                         # CLI reference
├── PEEGEEQ_MIGRATIONS_MIGRATION_PATTERNS.md  # Deployment patterns
├── PEEGEEQ_MIGRATIONS_IMPLEMENTATION_NOTES.md  # Implementation notes
└── README.md                            # Overview
```

## Testing

### Build Test
```bash
mvn clean package -DskipTests
# ✓ JAR built: target/peegeeq-migrations.jar
```

### CLI Test
```bash
java -jar target/peegeeq-migrations.jar
# ✓ Shows error about missing environment variables (expected)
```

### Dev Script Test
```bash
cd scripts
./dev-migrate.bat
# ✓ Builds JAR and attempts to run migrations
# ✓ Error about database connection is expected if PostgreSQL not running
```

## Next Steps

1. **Test with real PostgreSQL**:
   ```bash
   docker-compose -f docker-compose.dev.yml up -d postgres
   cd scripts
   ./dev-migrate.sh
   ```

2. **Create Kubernetes manifests** for your environments

3. **Integrate into CI/CD pipeline**

4. **Update team documentation** with new migration workflow

## Key Benefits

✅ **Production-Ready**: Kubernetes Jobs, proper error handling, security
✅ **Developer-Friendly**: One-command database reset
✅ **CI/CD-Friendly**: Simple JAR-based deployment
✅ **Backwards Compatible**: Maven plugin still works
✅ **Well-Documented**: Comprehensive guides for all use cases

