# PeeGeeQ Database Migrations

This module contains **ONLY** database migration scripts for PeeGeeQ. It is **NOT** a dependency of any other module and is **NOT** included in the application runtime.

## 📖 Complete Documentation

**See [PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md](PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md) for complete installation, migration, troubleshooting, and deployment documentation.**

This README provides a quick reference only. The complete guide includes:
- ⚠️ Critical database setup warnings
- Quick start installation (3 steps)
- Common installation issues and solutions
- Verification steps
- CI/CD integration examples
- Production deployment best practices

## ⚠️ Important

- **This module is for deployment/setup ONLY**
- **Never run migrations from application code**
- **Migrations are a separate deployment step**

## Structure

```
peegeeq-migrations/
├── pom.xml                                    # Maven configuration + Shade plugin
├── README.md                                  # This file
├── src/main/java/
│   └── dev/mars/peegeeq/migrations/
│       └── RunMigrations.java                 # Standalone CLI runner
└── src/main/resources/db/migration/
    ├── V001__Create_Base_Tables.sql
    ├── V002__Add_New_Feature.sql              # Future migrations
    └── ...
```

## Quick Start (Development)

### Option 1: Dev Convenience Scripts (Recommended)

From the scripts folder:

```bash
cd peegeeq-migrations/scripts

# Linux/Mac - Reset database (clean + migrate)
./dev-reset-db.sh

# Windows - Reset database (clean + migrate)
dev-reset-db.bat

# Linux/Mac - Migrate only (no clean)
./dev-migrate.sh

# Windows - Migrate only (no clean)
dev-migrate.bat
```

These scripts automatically:
1. Build the migrations JAR
2. Run migrations against your local database
3. Use sensible defaults (peegeeq_dev database)

### Option 2: Standalone JAR

```bash
# Build the executable JAR
mvn clean package -pl peegeeq-migrations

# Run migrations
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev
java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

# Clean and migrate (dev only!)
export DB_CLEAN_ON_START=true
java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

# Show migration info
java -jar peegeeq-migrations/target/peegeeq-migrations.jar info
```

### Option 3: Maven Plugin (Traditional)

```bash
cd peegeeq-migrations

# Run migrations against local database
mvn flyway:migrate -Plocal

# Check migration status
mvn flyway:info -Plocal

# Validate migrations
mvn flyway:validate -Plocal
```

## Running Migrations

### Local Development

```bash
cd peegeeq-migrations

# Run migrations against local database
mvn flyway:migrate -Plocal

# Check migration status
mvn flyway:info -Plocal

# Validate migrations
mvn flyway:validate -Plocal
```

### Test Environment

```bash
mvn flyway:migrate -Ptest
```

### Production

**IMPORTANT**: Production migrations should be run from CI/CD pipeline with credentials from environment variables:

```bash
# Set environment variables
export DB_URL=jdbc:postgresql://prod-db:5432/peegeeq
export DB_USER=peegeeq_admin
export DB_PASSWORD=<secure-password>

# Run migrations
mvn flyway:migrate -Pproduction
```

Or pass credentials directly:

```bash
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://prod-db:5432/peegeeq \
  -Dflyway.user=peegeeq_admin \
  -Dflyway.password=<secure-password>
```

## Migration Naming Convention

Flyway uses versioned migrations with the following naming pattern:

```
V<VERSION>__<DESCRIPTION>.sql
```

Examples:
- `V001__Create_Base_Tables.sql`
- `V002__Add_Consumer_Groups.sql`
- `V003__Add_Bitemporal_Indexes.sql`

## Production Deployment

### Recommended Production Deployment Flow

**Pattern 1: Kubernetes Job (Recommended)**

```yaml
# k8s/migration-job.yaml
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

Deploy:
```bash
# Step 1: Run migrations as a Job
kubectl apply -f k8s/migration-job.yaml
kubectl wait --for=condition=complete job/peegeeq-migrations --timeout=300s

# Step 2: Deploy application (only after migrations succeed)
kubectl apply -f k8s/peegeeq-deployment.yaml
```

**Pattern 2: InitContainer**

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: peegeeq
spec:
  template:
    spec:
      initContainers:
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
      containers:
      - name: peegeeq
        image: peegeeq-rest:latest
        # ... rest of app config
```

**Pattern 3: CI/CD Pipeline**

```yaml
# Example GitLab CI/CD
deploy-production:
  stage: deploy
  script:
    # Build migrations JAR
    - mvn clean package -pl peegeeq-migrations -DskipTests

    # Run migrations via JAR
    - export DB_JDBC_URL=$PROD_DB_URL
    - export DB_USER=$PROD_DB_USER
    - export DB_PASSWORD=$PROD_DB_PASSWORD
    - java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

    # Deploy application (only if migrations succeed)
    - kubectl apply -f k8s/production/
```

**Pattern 4: Maven Plugin (Traditional)**

```bash
# Set environment variables
export DB_URL=jdbc:postgresql://prod-db:5432/peegeeq
export DB_USER=peegeeq_admin
export DB_PASSWORD=<secure-password>

# Run migrations
mvn flyway:migrate -Pproduction -pl peegeeq-migrations
```

## CLI Commands

The standalone JAR supports the following commands:

| Command | Description | Example |
|---------|-------------|---------|
| `migrate` | Apply pending migrations (default) | `java -jar peegeeq-migrations.jar migrate` |
| `info` | Show migration status and history | `java -jar peegeeq-migrations.jar info` |
| `validate` | Validate applied migrations | `java -jar peegeeq-migrations.jar validate` |
| `baseline` | Baseline an existing database | `java -jar peegeeq-migrations.jar baseline` |
| `repair` | Repair metadata table | `java -jar peegeeq-migrations.jar repair` |
| `clean` | Clean database (requires `DB_CLEAN_ON_START=true`) | `java -jar peegeeq-migrations.jar clean` |

## Maven Plugin Commands

| Command | Description |
|---------|-------------|
| `flyway:migrate` | Apply pending migrations |
| `flyway:info` | Show migration status |
| `flyway:validate` | Validate applied migrations against available ones |
| `flyway:baseline` | Baseline an existing database |
| `flyway:repair` | Repair metadata table after failed migration |

## Safety Features

- **`cleanDisabled=true`** - Prevents accidental database wipe
- **`validateOnMigrate=true`** - Validates checksums before migrating
- **`baselineOnMigrate=true`** - Allows migrating existing databases
- **`outOfOrder=false`** - Enforces sequential migration order

## Adding New Migrations

1. Create new SQL file with next version number:
   ```bash
   touch src/main/resources/db/migration/V002__Add_New_Feature.sql
   ```

2. Write your SQL:
   ```sql
   -- V002__Add_New_Feature.sql
   ALTER TABLE queue_messages ADD COLUMN new_field VARCHAR(255);
   CREATE INDEX idx_new_field ON queue_messages(new_field);
   ```

3. Test locally:
   ```bash
   mvn flyway:migrate -Plocal
   mvn flyway:info -Plocal
   ```

4. Commit and deploy through CI/CD

## Troubleshooting

### Migration Failed

If a migration fails, Flyway marks it as failed in the metadata table:

```bash
# Check status
mvn flyway:info -Plocal

# Fix the SQL file, then repair
mvn flyway:repair -Plocal

# Try again
mvn flyway:migrate -Plocal
```

### Baseline Existing Database

If you have an existing database without Flyway metadata:

```bash
mvn flyway:baseline -Plocal
```

## Best Practices

1. ✅ **Always test migrations locally first**
2. ✅ **Run migrations in a transaction** (Flyway default for PostgreSQL)
3. ✅ **Keep migrations small and focused**
4. ✅ **Never modify applied migrations** (create new ones instead)
5. ✅ **Use descriptive migration names**
6. ✅ **Run migrations before deploying application**
7. ❌ **Never run migrations from application code**
8. ❌ **Never use `flyway:clean` in production**

## Module Dependencies

**NONE** - This module has no dependencies on other PeeGeeQ modules and is not a dependency of any other module.

The application runtime does NOT include this module or its migration scripts.

