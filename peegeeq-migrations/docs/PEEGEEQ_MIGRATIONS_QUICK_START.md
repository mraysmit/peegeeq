# PeeGeeQ Migrations - Quick Start & Command Reference

**Module**: `peegeeq-migrations`  
**Version**: 2.0.0  
**Date**: January 2026

This guide provides a simple explanation of the available migration commands for both local development and production operations.

---

## 1. Local Development (Scripts)

For local development, use the convenience scripts in the `scripts/` folder. They handle building the JAR and setting up default environment variables.

### `dev-reset-db` (The "Nuke It" Button)
**Use when:** You want a fresh start or your database is in a bad state.
**What it does:**
1.  **DROPS** the entire schema (deletes all data).
2.  Re-creates all tables from scratch.
**Command:**
```bash
# Linux/Mac
./scripts/dev-reset-db.sh

# Windows
.\scripts\dev-reset-db.bat
```

### `dev-migrate` (The "Update" Button)
**Use when:** You pulled new code and want to apply the latest schema changes without losing your data.
**What it does:**
1.  Checks for new migration files.
2.  Applies only the pending migrations.
**Command:**
```bash
# Linux/Mac
./scripts/dev-migrate.sh

# Windows
.\scripts\dev-migrate.bat
```

---

## 2. Production / CI (JAR Commands)

In production or CI/CD pipelines, you run the `peegeeq-migrations.jar` directly.

### `migrate`
**Use when:** Deploying the application.
**What it does:** Migrates the database to the latest version. Safe to run on every deployment.
```bash
java -jar peegeeq-migrations.jar migrate
```

### `repair`
**Use when:** You see "Checksum Mismatch" errors (e.g., after a hotfix to an existing migration file).
**What it does:** Updates the `flyway_schema_history` table to match the checksums of your local files. It does **not** modify your actual database tables.
```bash
java -jar peegeeq-migrations.jar repair
```

### `info`
**Use when:** You want to see which migrations have been applied and which are pending.
**What it does:** Prints a table showing the status of all migrations.
```bash
java -jar peegeeq-migrations.jar info
```

### `validate`
**Use when:** You want to verify that the applied migrations match your local script files exactly.
**What it does:** Fails if there are any differences (checksum mismatches, missing files, etc.).
```bash
java -jar peegeeq-migrations.jar validate
```

---

## 3. Common Scenarios

| Scenario | Recommended Command |
| :--- | :--- |
| **"I just cloned the repo."** | `dev-reset-db` |
| **"I pulled latest changes from git."** | `dev-migrate` |
| **"My database is acting weird."** | `dev-reset-db` |
| **"I'm deploying to Production."** | `migrate` |
| **"Flyway says 'Checksum Mismatch'."** | `repair` |
