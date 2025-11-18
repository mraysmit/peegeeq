# PeeGeeQ Migrations CLI Usage Guide

This guide covers the standalone migration runner JAR (`RunMigrations.java`).

## Building the JAR

```bash
# From project root
mvn clean package -pl peegeeq-migrations -DskipTests

# JAR location
# peegeeq-migrations/target/peegeeq-migrations.jar
```

## Configuration

All configuration is done via environment variables:

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `DB_JDBC_URL` | ✅ Yes | JDBC connection URL | `jdbc:postgresql://localhost:5432/peegeeq_dev` |
| `DB_USER` | ✅ Yes | Database username | `peegeeq_dev` |
| `DB_PASSWORD` | ✅ Yes | Database password | `peegeeq_dev` |
| `DB_CLEAN_ON_START` | ❌ No | Clean database before migration (dev only!) | `true` or `false` (default: `false`) |
| `FLYWAY_BASELINE_VERSION` | ❌ No | Baseline version | `1` (default) |
| `FLYWAY_BASELINE_DESCRIPTION` | ❌ No | Baseline description | `Initial baseline` |

## Commands

### migrate (default)

Apply pending migrations to the database.

```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

Output:
```
╔════════════════════════════════════════════════════════════════╗
║           PeeGeeQ Database Migration Runner                    ║
╚════════════════════════════════════════════════════════════════╝

Command:  migrate
Database: jdbc:postgresql://localhost:5432/peegeeq_dev
User:     peegeeq_dev

✓ Migration completed successfully
  Migrations executed: 1
  Target version:      001

✓ Operation completed successfully
```

### info

Show migration status and history.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar info
```

Output:
```
Migration Status:
─────────────────────────────────────────────────────────────────
001        | Create_Base_Tables                       | Success
002        | Add_Consumer_Groups                      | Pending
```

### validate

Validate that applied migrations match available migration files.

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar validate
```

### baseline

Baseline an existing database (useful for adding Flyway to existing databases).

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar baseline
```

### repair

Repair the Flyway metadata table (useful after failed migrations).

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar repair
```

### clean

Clean the database (⚠️ **DELETES ALL DATA** - dev only!).

```bash
export DB_CLEAN_ON_START=true
java -jar peegeeq-migrations/target/peegeeq-migrations.jar clean
```

## Usage Examples

### Local Development - Clean and Migrate

```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev
export DB_CLEAN_ON_START=true

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

### Production - Migrate Only

```bash
export DB_JDBC_URL=jdbc:postgresql://prod-db:5432/peegeeq
export DB_USER=peegeeq_admin
export DB_PASSWORD=$SECURE_PASSWORD

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

### CI/CD Pipeline

```bash
#!/bin/bash
set -e

# Build migrations JAR
mvn clean package -pl peegeeq-migrations -DskipTests

# Run migrations
export DB_JDBC_URL=$PROD_DB_URL
export DB_USER=$PROD_DB_USER
export DB_PASSWORD=$PROD_DB_PASSWORD

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

# Deploy application only if migrations succeed
kubectl apply -f k8s/production/
```

### Docker

```bash
docker run --rm \
  -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq \
  -e DB_USER=peegeeq \
  -e DB_PASSWORD=secret \
  peegeeq-migrations:latest migrate
```

### Kubernetes Job

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: peegeeq-migrations
spec:
  template:
    spec:
      containers:
      - name: migrations
        image: peegeeq-migrations:latest
        command: ["java", "-jar", "/app/peegeeq-migrations.jar", "migrate"]
        env:
        - name: DB_JDBC_URL
          value: "jdbc:postgresql://postgres:5432/peegeeq"
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
      restartPolicy: Never
  backoffLimit: 3
```

## Error Handling

### Missing Environment Variables

```
❌ ERROR: Migration failed
   Required environment variable not set: DB_JDBC_URL
Please set: DB_JDBC_URL, DB_USER, DB_PASSWORD
```

**Solution**: Set all required environment variables.

### Connection Failed

```
❌ ERROR: Migration failed
   Connection to localhost:5432 refused
```

**Solution**: Ensure PostgreSQL is running and accessible.

### Migration Failed

```
❌ ERROR: Migration failed
   ERROR: syntax error at or near "CRATE"
```

**Solution**: Fix the SQL file, then run `repair` followed by `migrate`.

## Safety Features

1. **No clean by default**: `DB_CLEAN_ON_START` must be explicitly set to `true`
2. **Clean requires confirmation**: The `clean` command requires `DB_CLEAN_ON_START=true`
3. **Validation on migrate**: Checksums are validated before applying migrations
4. **Transaction safety**: Each migration runs in a transaction (PostgreSQL default)
5. **Baseline on migrate**: Existing databases are automatically baselined

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Error (missing config, connection failed, migration failed, etc.) |

## Comparison with Maven Plugin

| Feature | CLI JAR | Maven Plugin |
|---------|---------|--------------|
| **Containerization** | ✅ Easy (single JAR) | ❌ Requires Maven + POM |
| **Kubernetes** | ✅ Perfect for Jobs/InitContainers | ❌ Not suitable |
| **CI/CD** | ✅ Simple (just run JAR) | ✅ Works but heavier |
| **Local Dev** | ✅ Fast | ✅ Fast |
| **Configuration** | Environment variables | Maven profiles/properties |
| **Size** | ~15 MB (shaded JAR) | Requires full Maven installation |

## Best Practices

1. ✅ **Use CLI JAR for production** (Kubernetes, Docker, CI/CD)
2. ✅ **Use Maven plugin for local development** (if you prefer)
3. ✅ **Use dev scripts** (`dev-reset-db.sh`) for convenience
4. ✅ **Never set `DB_CLEAN_ON_START=true` in production**
5. ✅ **Run migrations before deploying application**
6. ✅ **Use Kubernetes Jobs** instead of InitContainers for better observability
7. ✅ **Store credentials in Kubernetes Secrets**, not in environment variables in YAML

