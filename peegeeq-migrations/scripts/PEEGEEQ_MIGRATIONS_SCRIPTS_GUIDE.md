# PeeGeeQ Migrations - Dev Scripts Guide

Convenience scripts for running database migrations during local development.

---

## Quick Start

```bash
# Navigate to this directory
cd peegeeq-migrations/scripts

# Reset database (clean + migrate)
./dev-reset-db.sh       # Linux/Mac
dev-reset-db.bat        # Windows

# Migrate only (no clean)
./dev-migrate.sh        # Linux/Mac
dev-migrate.bat         # Windows
```

---

## Scripts

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

---

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

---

## How They Work

Both scripts:
1. Navigate to the parent directory (`peegeeq-migrations/`)
2. Build the JAR: `mvn clean package -DskipTests`
3. Run the CLI: `java -jar target/peegeeq-migrations.jar migrate`

The scripts automatically handle:
- Building the JAR if it doesn't exist or is outdated
- Setting up environment variables for the CLI
- Providing sensible defaults for local development

---

## When to Use Which Script

### Use `dev-reset-db` when:
- Starting fresh on a new feature
- Your database schema is corrupted
- You want to test migrations from scratch
- You're switching between branches with different schemas

### Use `dev-migrate` when:
- You've added a new migration and want to apply it
- You want to update your database without losing data
- You're testing a new migration incrementally

---

## Examples

### Reset database with custom credentials
```bash
# Linux/Mac
DB_USER=postgres DB_PASSWORD=secret ./dev-reset-db.sh

# Windows
set DB_USER=postgres
set DB_PASSWORD=secret
dev-reset-db.bat
```

### Migrate to a different database
```bash
# Linux/Mac
./dev-migrate.sh staging_db

# Windows
dev-migrate.bat staging_db
```

### Use with Docker Compose
```bash
# Start PostgreSQL
docker-compose -f ../docker-compose.dev.yml up -d postgres

# Wait a few seconds for PostgreSQL to be ready
sleep 5

# Run migrations
./dev-migrate.sh
```

---

## Troubleshooting

### "Failed to build migrations JAR"
- Make sure you're in the `peegeeq-migrations/scripts` directory
- Check that Maven is installed: `mvn --version`


---

## ⚠️ Important Notes

**These scripts are for local development only!**

Do NOT use these scripts in production. For production deployments, use:
- Kubernetes Jobs
- CI/CD pipelines
- Direct JAR execution with proper credentials management

---

## See Also

- **[../PEEGEEQ_MIGRATIONS_README.md](../PEEGEEQ_MIGRATIONS_README.md)** - Overview and quick start
- **[../PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](../PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** - Complete JAR documentation
- **[../PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md](../PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md)** - Deployment patterns for all environments
- Check connection settings (host, port, database name)
- Verify PostgreSQL is running: `docker ps` or `pg_isready`

### "Migration failed" with authentication error
- Check database credentials (DB_USER, DB_PASSWORD)
- Verify the database exists: `psql -h localhost -U peegeeq_dev -l`
- Create the database if needed: `createdb -h localhost -U postgres peegeeq_dev`

### Script doesn't have execute permissions (Linux/Mac)
```bash
chmod +x dev-reset-db.sh dev-migrate.sh
```

