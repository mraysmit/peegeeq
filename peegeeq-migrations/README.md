# PeeGeeQ Database Migrations

This module manages the database schema for the PeeGeeQ application using Flyway. It runs as a standalone process, separate from the application runtime.

> **📘 Documentation**:
> *   **[Quick Start & Command Reference](docs/PEEGEEQ_MIGRATIONS_QUICK_START.md)**: Simple explanation of all commands.
> *   **[Complete Guide](docs/PEEGEEQ_MIGRATIONS_COMPLETE_GUIDE.md)**: Deep dives, deployment patterns, and troubleshooting.

---

## 🚀 Quick Start (Local Development)

The easiest way to run migrations locally is using the provided convenience scripts.

### 1. Reset Database (Clean + Migrate)
**⚠️ WARNING: Deletes all data!**

```bash
# Linux/Mac
cd scripts
./dev-reset-db.sh

# Windows
cd scripts
.\dev-reset-db.bat
```

### 2. Migrate Only (Keep Data)
Applies only new migrations.

```bash
# Linux/Mac
./dev-migrate.sh

# Windows
.\dev-migrate.bat
```

---

## 🛠️ Manual Usage (CI/CD & Production)

For production or CI/CD, build the JAR and run it with environment variables.

### 1. Build the JAR
```bash
mvn clean package -pl peegeeq-migrations -DskipTests
# Output: peegeeq-migrations/target/peegeeq-migrations.jar
```

### 2. Run Migrations
```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

java -jar target/peegeeq-migrations.jar migrate
```

### Configuration Reference

| Variable | Required | Description | Default |
|----------|----------|-------------|---------|
| `DB_JDBC_URL` | ✅ Yes | JDBC connection URL | - |
| `DB_USER` | ✅ Yes | Database username | - |
| `DB_PASSWORD` | ✅ Yes | Database password | - |
| `DB_SCHEMA` | ❌ No | Target schema | `public` |
| `DB_CLEAN_ON_START` | ❌ No | **Wipe DB before running?** | `false` |

---

## ⚠️ Important Notices

### V010 Checksum Mismatch (Jan 2026)
If you see a "Checksum Mismatch" error for `V010`, it is because the migration file was updated to fix a bug.
**Fix:** Run the repair command:
```bash
java -jar target/peegeeq-migrations.jar repair
```
