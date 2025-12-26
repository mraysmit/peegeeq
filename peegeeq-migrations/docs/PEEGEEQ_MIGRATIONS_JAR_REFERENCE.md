# PeeGeeQ Migrations - Standalone JAR Reference

This guide covers the standalone migration runner JAR (`peegeeq-migrations.jar`) built from `RunMigrations.java`.

**Use this JAR for:**
- Production deployments (Docker containers, CI/CD pipelines)
- Manual migration execution
- Any environment where you need full control

**For local development convenience scripts**, see [scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md).

---

## Building the JAR

```bash
# From project root
mvn clean package -pl peegeeq-migrations -DskipTests

# JAR location
# peegeeq-migrations/target/peegeeq-migrations.jar (~15 MB shaded JAR)
```

The JAR includes all dependencies (Flyway, PostgreSQL driver) and can run standalone without Maven.

---

## Configuration

All configuration is done via **environment variables**:

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `DB_JDBC_URL` | ✅ Yes | JDBC connection URL | `jdbc:postgresql://localhost:5432/peegeeq_dev` |
| `DB_USER` | ✅ Yes | Database username | `peegeeq_dev` |
| `DB_PASSWORD` | ✅ Yes | Database password | `peegeeq_dev` |
| `DB_SCHEMA` | ❌ No | Target database schema | `public` (default) or `myschema` |
| `DB_CLEAN_ON_START` | ❌ No | Clean database before migration (⚠️ dev only!) | `true` or `false` (default: `false`) |
| `FLYWAY_BASELINE_VERSION` | ❌ No | Baseline version | `1` (default) |
| `FLYWAY_BASELINE_DESCRIPTION` | ❌ No | Baseline description | `Initial baseline` |

---

## Commands

### migrate (default)

Apply pending migrations to the database.

```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

**With custom schema:**
```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev
export DB_SCHEMA=myschema

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

**Output:**
```
╔════════════════════════════════════════════════════════════════╗
║           PeeGeeQ Database Migration Runner                    ║
╚════════════════════════════════════════════════════════════════╝

Command:  migrate
Database: jdbc:postgresql://localhost:5432/peegeeq_dev
User:     peegeeq_dev
Schema:   myschema

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

**Output:**
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

---

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
# (your deployment commands here)
```

### Docker Container

```bash
# Build Docker image
docker build -t peegeeq-migrations:latest peegeeq-migrations/

# Run migrations
docker run --rm \
  -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq \
  -e DB_USER=peegeeq \
  -e DB_PASSWORD=secret \
  peegeeq-migrations:latest migrate
```

---

## Error Handling

### Connection Failures

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

**Output**:
```
❌ ERROR: Migration failed
   Connection to localhost:5432 refused
```

**Solutions**:
- Ensure PostgreSQL is running
- Check connection settings (host, port, database name)
- Verify network connectivity

---

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

**Solutions**:
- Ensure PostgreSQL is running: `docker ps` or `pg_isready`
- Check connection settings (host, port, database name)
- Verify network connectivity
- Check firewall rules

### Migration Failed

```
❌ ERROR: Migration failed
   ERROR: syntax error at or near "CRATE"
```

**Solution**:
1. Fix the SQL file
2. Run `repair` to fix metadata: `java -jar peegeeq-migrations.jar repair`
3. Run `migrate` again: `java -jar peegeeq-migrations.jar migrate`

### Authentication Failed

```
❌ ERROR: Migration failed
   FATAL: password authentication failed for user "peegeeq"
```

**Solutions**:
- Verify database credentials
- Check that database user exists: `psql -h localhost -U postgres -c "\du"`
- Ensure user has proper permissions

---

## Safety Features

1. **No clean by default**: `DB_CLEAN_ON_START` must be explicitly set to `true`
2. **Clean requires confirmation**: The `clean` command requires `DB_CLEAN_ON_START=true`
3. **Validation on migrate**: Checksums are validated before applying migrations
4. **Transaction safety**: Each migration runs in a transaction (PostgreSQL default)
5. **Baseline on migrate**: Existing databases are automatically baselined
6. **Idempotent**: Running migrations multiple times is safe (already-applied migrations are skipped)

---

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Error (missing config, connection failed, migration failed, etc.) |

Use exit codes in CI/CD pipelines to halt deployment on migration failure:

```bash
java -jar peegeeq-migrations.jar migrate
if [ $? -ne 0 ]; then
  echo "Migration failed! Aborting deployment."
  exit 1
fi
```

---

## Comparison: JAR vs Maven Plugin vs Dev Scripts

| Feature | Standalone JAR | Maven Plugin | Dev Scripts |
|---------|---------------|--------------|-------------|
| **Containerization** | ✅ Easy (single JAR) | ❌ Requires Maven + POM | ❌ Not suitable |
| **CI/CD** | ✅ Simple (just run JAR) | ✅ Works but heavier | ❌ Not suitable |
| **Local Dev** | ✅ Fast | ✅ Fast | ✅ Fastest (automated) |
| **Production** | ✅ Recommended | ❌ Not recommended | ❌ Never use |
| **Configuration** | Environment variables | Maven profiles/properties | Defaults + env vars |
| **Size** | ~15 MB (shaded JAR) | Requires full Maven | Requires Maven |
| **All Commands** | ✅ Yes (migrate, info, validate, etc.) | ✅ Yes | ❌ Only migrate |

---

## Best Practices

### ✅ DO

1. **Use standalone JAR for production** (Docker, CI/CD)
2. **Run migrations before deploying application** (separate step in pipeline)
3. **Store credentials securely** (environment variables, secrets management)
4. **Run `validate` before `migrate`** in CI/CD pipelines
5. **Monitor migration execution** (check logs, exit codes)
6. **Test migrations in staging** before production
7. **Keep migrations idempotent** (safe to run multiple times)

### ❌ DON'T

1. **Never set `DB_CLEAN_ON_START=true` in production**
2. **Never run migrations from application code** (no Flyway in `@PostConstruct`)
3. **Never race multiple pods to migrate** (use Jobs, not Deployments)
4. **Never skip migration validation** in CI/CD
5. **Never hardcode credentials** in scripts or YAML
6. **Never run migrations manually in production** (use automation)

---

## Troubleshooting

### JAR not found

```
Error: Unable to access jarfile peegeeq-migrations/target/peegeeq-migrations.jar
```

**Solution**: Build the JAR first: `mvn clean package -pl peegeeq-migrations -DskipTests`

### Out of memory

```
java.lang.OutOfMemoryError: Java heap space
```

**Solution**: Increase heap size: `java -Xmx512m -jar peegeeq-migrations.jar migrate`

### PostgreSQL driver not found

```
No database found to handle jdbc:postgresql://...
```

**Solution**: This should never happen with the shaded JAR. Rebuild: `mvn clean package -pl peegeeq-migrations -DskipTests`

### Flyway metadata table locked

```
ERROR: could not obtain lock on row in relation "flyway_schema_history"
```

**Solution**: Another migration process is running. Wait for it to complete or check for stuck processes.

---

## See Also

- **[PEEGEEQ_MIGRATIONS_README.md](PEEGEEQ_MIGRATIONS_README.md)** - Overview and quick start
- **[PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md](PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md)** - Deployment patterns for all environments
- **[scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md)** - Dev convenience scripts

