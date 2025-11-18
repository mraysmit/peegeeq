# PeeGeeQ Migrations Quick Start

This guide gets you started with PeeGeeQ database migrations in 2 minutes.

## For Developers (Local Development)

### Option 1: Dev Scripts (Easiest)

```bash
# Linux/Mac - Reset database (clean + migrate)
cd peegeeq-migrations/scripts
./dev-reset-db.sh

# Windows - Reset database (clean + migrate)
cd peegeeq-migrations\scripts
dev-reset-db.bat
```

That's it! Your database is ready.

### Option 2: Standalone JAR

```bash
# Build migrations JAR
mvn clean package -pl peegeeq-migrations -DskipTests

# Set environment variables
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

# Run migrations
java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

### Option 3: Maven Plugin

```bash
cd peegeeq-migrations
mvn flyway:migrate -Plocal
```

## For DevOps (Production)

### Kubernetes Job (Recommended)

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

Deploy:
```bash
kubectl apply -f k8s/migration-job.yaml
kubectl wait --for=condition=complete job/peegeeq-migrations --timeout=300s
kubectl apply -f k8s/peegeeq-deployment.yaml
```

## For CI/CD

```yaml
# GitLab CI example
migrate-production:
  stage: migrate
  script:
    - mvn clean package -pl peegeeq-migrations -DskipTests
    - export DB_JDBC_URL=$PROD_DB_URL
    - export DB_USER=$PROD_DB_USER
    - export DB_PASSWORD=$PROD_DB_PASSWORD
    - java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

## Available Commands

| Command | Description | Usage |
|---------|-------------|-------|
| `migrate` | Apply pending migrations | `java -jar peegeeq-migrations.jar migrate` |
| `info` | Show migration status | `java -jar peegeeq-migrations.jar info` |
| `validate` | Validate migrations | `java -jar peegeeq-migrations.jar validate` |
| `baseline` | Baseline existing DB | `java -jar peegeeq-migrations.jar baseline` |
| `repair` | Repair metadata | `java -jar peegeeq-migrations.jar repair` |
| `clean` | Clean database (dev only!) | `java -jar peegeeq-migrations.jar clean` |

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_JDBC_URL` | ✅ | JDBC connection URL |
| `DB_USER` | ✅ | Database username |
| `DB_PASSWORD` | ✅ | Database password |
| `DB_CLEAN_ON_START` | ❌ | Clean before migrate (dev only!) |

## Common Tasks

### Reset Local Database

```bash
cd peegeeq-migrations/scripts
./dev-reset-db.sh
```

### Apply New Migration

```bash
cd peegeeq-migrations/scripts
./dev-migrate.sh
```

### Check Migration Status

```bash
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev
java -jar peegeeq-migrations/target/peegeeq-migrations.jar info
```

### Build Docker Image

```bash
docker build -t peegeeq-migrations:latest -f peegeeq-migrations/Dockerfile .
```

### Run with Docker

```bash
docker run --rm \
  -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq \
  -e DB_USER=peegeeq \
  -e DB_PASSWORD=secret \
  peegeeq-migrations:latest migrate
```

## Documentation

- **[README.md](peegeeq-migrations/README.md)** - Overview and quick reference
- **[CLI_USAGE.md](peegeeq-migrations/CLI_USAGE.md)** - Detailed CLI documentation
- **[MIGRATION_PATTERNS.md](peegeeq-migrations/MIGRATION_PATTERNS.md)** - Deployment patterns for different environments
- **[PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md](peegeeq-migrations/PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md)** - Complete guide with troubleshooting

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    PeeGeeQ Architecture                      │
└─────────────────────────────────────────────────────────────┘

peegeeq-migrations/          ← Separate module (NOT in runtime)
├── RunMigrations.java       ← Standalone CLI
├── V001__*.sql              ← Migration scripts
└── Dockerfile               ← Container image

peegeeq-db/                  ← Core module (NO Flyway dependency)
peegeeq-rest/                ← REST API (NO Flyway dependency)
peegeeq-native/              ← Native queue (NO Flyway dependency)

Deployment Flow:
1. Run migrations (separate step)
2. Deploy application (only if migrations succeed)
```

## Key Principles

✅ **DO**:
- Run migrations before deploying application
- Use dev scripts for local development
- Use Kubernetes Jobs for production
- Store credentials in Kubernetes Secrets

❌ **DON'T**:
- Run migrations from application code
- Use `DB_CLEAN_ON_START=true` in production
- Modify applied migrations (create new ones instead)
- Race multiple pods to migrate

## Need Help?

1. Check the [CLI_USAGE.md](peegeeq-migrations/CLI_USAGE.md) for detailed examples
2. Check the [MIGRATION_PATTERNS.md](peegeeq-migrations/MIGRATION_PATTERNS.md) for deployment patterns
3. Check the [PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md](peegeeq-migrations/PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md) for troubleshooting

