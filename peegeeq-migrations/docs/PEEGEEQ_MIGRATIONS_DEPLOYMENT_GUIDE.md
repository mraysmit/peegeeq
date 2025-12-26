# PeeGeeQ Migrations - Deployment Guide

This guide describes the recommended patterns for running database migrations in different environments.

---

## Architecture Principles

### ✅ DO

1. **Separate migrations from application runtime**
   - Migrations are in a separate module (`peegeeq-migrations`)
   - No Flyway dependency in core modules (`peegeeq-db`, `peegeeq-rest`, etc.)
   - Migrations run as a separate step before application deployment

2. **Run migrations before deploying application**
   - Migrations complete successfully → Deploy application
   - Migrations fail → Abort deployment

3. **Use appropriate tool for each environment**
   - **Production**: Standalone JAR in CI/CD pipeline
   - **CI/CD**: Standalone JAR or Maven plugin
   - **Local Development**: Dev scripts or Maven plugin

### ❌ DON'T

1. **Never run migrations from application code**
   - No Flyway in `@PostConstruct` or startup hooks
   - No automatic migration on application boot
   - Migrations are a deployment concern, not a runtime concern

2. **Never use `clean` in production**
   - `DB_CLEAN_ON_START=true` is for local development only
   - Production databases should never be cleaned automatically

3. **Never run migrations concurrently**
   - Ensure only one migration process runs at a time
   - Use locks or serialization in CI/CD pipelines

---

## Pattern 1: CI/CD Pipeline (Recommended for Production)

**Best for**: Production, Staging, Test environments

**Advantages**:
- Full control over deployment process
- Easy to add pre/post migration steps
- Can run validation before deploying
- Clear audit trail
- Explicit success/failure

**Implementation (GitLab CI)**:

```yaml
# .gitlab-ci.yml
stages:
  - build
  - migrate
  - deploy

build-migrations:
  stage: build
  script:
    - mvn clean package -pl peegeeq-migrations -DskipTests
  artifacts:
    paths:
      - peegeeq-migrations/target/peegeeq-migrations.jar

migrate-production:
  stage: migrate
  dependencies:
    - build-migrations
  script:
    - export DB_JDBC_URL=$PROD_DB_URL
    - export DB_USER=$PROD_DB_USER
    - export DB_PASSWORD=$PROD_DB_PASSWORD
    - java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
  only:
    - main

deploy-production:
  stage: deploy
  dependencies:
    - migrate-production
  script:
    - # Your deployment commands here
    - echo "Deploy application"
  only:
    - main
```

**Implementation (GitHub Actions)**:

```yaml
# .github/workflows/deploy.yml
name: Deploy to Production

on:
  push:
    branches: [main]

jobs:
  migrate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Build migrations JAR
        run: mvn clean package -pl peegeeq-migrations -DskipTests

      - name: Run migrations
        env:
          DB_JDBC_URL: ${{ secrets.PROD_DB_URL }}
          DB_USER: ${{ secrets.PROD_DB_USER }}
          DB_PASSWORD: ${{ secrets.PROD_DB_PASSWORD }}
        run: java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

  deploy:
    needs: migrate
    runs-on: ubuntu-latest
    steps:
      - name: Deploy application
        run: |
          # Your deployment commands here
          echo "Deploy application"
```

---

## Pattern 2: Docker Compose (Local Development)

**Best for**: Local development with Docker

**Advantages**:
- Complete environment in one command
- Automatic migrations on startup
- Easy to reset and restart

**Implementation**:

```yaml
# docker-compose.dev.yml
version: '3.8'

services:
  postgres:
    image: postgres:15.13-alpine3.20
    environment:
      POSTGRES_DB: peegeeq_dev
      POSTGRES_USER: peegeeq_dev
      POSTGRES_PASSWORD: peegeeq_dev
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U peegeeq_dev"]
      interval: 5s
      timeout: 5s
      retries: 5

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
    restart: "no"

  peegeeq-rest:
    build: .
    depends_on:
      migrations:
        condition: service_completed_successfully
    ports:
      - "8080:8080"
    environment:
      PEEGEEQ_DATABASE_HOST: postgres
      PEEGEEQ_DATABASE_PORT: 5432
      PEEGEEQ_DATABASE_NAME: peegeeq_dev
      PEEGEEQ_DATABASE_USERNAME: peegeeq_dev
      PEEGEEQ_DATABASE_PASSWORD: peegeeq_dev
```

**Usage**:

```bash
# Start everything
docker-compose -f docker-compose.dev.yml up

# Reset and restart
docker-compose -f docker-compose.dev.yml down -v
docker-compose -f docker-compose.dev.yml up
```

---

## Pattern 3: Dev Scripts (Recommended for Local Development)

**Best for**: Local development without Docker

**Advantages**:
- Simple one-command reset
- No Docker required
- Fast iteration

**Usage**:

```bash
# Linux/Mac
cd peegeeq-migrations/scripts
./dev-reset-db.sh

# Windows
cd peegeeq-migrations\scripts
dev-reset-db.bat
```

These scripts:
1. Build the migrations JAR
2. Run migrations (with optional clean)
3. Provide clear feedback

**See**: [scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md) for details.

---

## Pattern 4: Maven Plugin

**Best for**: Local development, CI/CD

**Advantages**:
- No JAR building required
- Maven profiles for different environments
- Familiar Maven workflow

**Usage**:

```bash
# Local development
cd peegeeq-migrations
mvn flyway:migrate -Plocal

# Check status
mvn flyway:info -Plocal

# Validate migrations
mvn flyway:validate -Plocal

# Production (with environment variables)
export DB_URL=jdbc:postgresql://prod-db:5432/peegeeq
export DB_USER=peegeeq_admin
export DB_PASSWORD=$SECURE_PASSWORD
mvn flyway:migrate -Pproduction
```


---

## Comparison Matrix

| Pattern | Production | Staging | CI/CD | Local Dev | Observability | Complexity |
|---------|------------|---------|-------|-----------|---------------|------------|
| **CI/CD Pipeline** | ✅ Best | ✅ Best | ✅ Best | ❌ N/A | ⭐⭐⭐⭐ | Medium |
| **Docker Compose** | ❌ No | ❌ No | ❌ No | ✅ Best | ⭐⭐⭐ | Low |
| **Dev Scripts** | ❌ No | ❌ No | ❌ No | ✅ Best | ⭐⭐⭐⭐ | Very Low |
| **Maven Plugin** | ⚠️ OK | ⚠️ OK | ✅ Good | ✅ Good | ⭐⭐⭐ | Low |

---

## Recommendations by Environment

### Production
1. **Primary**: CI/CD Pipeline with standalone JAR
2. **Alternative**: Maven plugin in CI/CD

**Why**: Best observability, explicit success/failure, easy to retry, clear audit trail

### Staging/Test
1. **Primary**: CI/CD Pipeline
2. **Alternative**: Maven plugin

**Why**: Same as production for consistency

### Local Development
1. **Primary**: Dev scripts (`./dev-reset-db.sh`)
2. **Alternative**: Docker Compose
3. **Alternative**: Maven plugin (`mvn flyway:migrate -Plocal`)

**Why**: Fastest iteration, no container overhead

### CI/CD
1. **Primary**: Standalone JAR in pipeline
2. **Alternative**: Maven plugin

**Why**: Simple, containerizable, explicit control

---

## Migration Workflow

```
┌─────────────────────────────────────────────────────────────┐
│                    Development Workflow                      │
└─────────────────────────────────────────────────────────────┘

1. Developer creates new migration file
   └─> V002__Add_New_Feature.sql

2. Developer tests locally
   └─> cd peegeeq-migrations/scripts
   └─> ./dev-reset-db.sh
   └─> Run application
   └─> Verify feature works

3. Developer commits and pushes
   └─> git add src/main/resources/db/migration/V002__*.sql
   └─> git commit -m "Add new feature"
   └─> git push

4. CI/CD pipeline runs
   └─> Build migrations JAR
   └─> Run migrations on test environment
   └─> Run integration tests
   └─> Deploy to staging
   └─> Run migrations on staging
   └─> Deploy application to staging

5. Production deployment
   └─> Run migrations on production (CI/CD pipeline)
   └─> Wait for completion
   └─> Deploy application (only if migrations succeed)
```

---

## Safety Checklist

Before deploying to production:

- [ ] Migrations tested locally
- [ ] Migrations tested in staging
- [ ] Migrations are idempotent (can be run multiple times safely)
- [ ] Migrations are backwards compatible (old app version can still run)
- [ ] Rollback plan documented
- [ ] Database backup taken
- [ ] `DB_CLEAN_ON_START` is NOT set in production
- [ ] Credentials stored securely (secrets management)
- [ ] Migration timeout configured appropriately
- [ ] Monitoring/alerting configured for migration process

---

## Security Best Practices

### ✅ DO

1. **Store credentials securely**
   - Use environment variables from secrets management
   - Use CI/CD secret variables
   - Never hardcode in scripts or config files

2. **Use least-privilege database users**
   - Migration user: `CREATE`, `ALTER`, `DROP` permissions
   - Application user: `SELECT`, `INSERT`, `UPDATE`, `DELETE` only

3. **Rotate credentials regularly**
   - Use secret management tools (Vault, AWS Secrets Manager)
   - Never commit credentials to Git

4. **Use TLS for database connections**
   ```bash
   export DB_JDBC_URL=jdbc:postgresql://prod-db:5432/peegeeq?ssl=true&sslmode=require
   ```

5. **Audit migration execution**
   - Log all migration runs
   - Monitor for unauthorized changes

### ❌ DON'T

1. **Never hardcode credentials** in YAML or scripts
2. **Never use root/admin users** for migrations
3. **Never skip TLS** in production
4. **Never share credentials** between environments
5. **Never commit secrets** to version control

---

## Monitoring and Observability

### CI/CD Pipeline Monitoring

- Monitor pipeline execution logs
- Track migration success/failure rates
- Set up alerts for migration failures
- Review migration duration trends
- Check database connection health

### Metrics to Track

1. **Migration duration** - How long migrations take
2. **Migration success rate** - Percentage of successful migrations
3. **Migration failures** - Count and reasons for failures
4. **Database size growth** - Track schema changes impact
5. **Downtime** - Time between migration start and app deployment

### Alerting

Set up alerts for:
- Migration job failures
- Migration duration exceeds threshold
- Multiple migration retries
- Database connection failures during migration

---

## Troubleshooting

### Migration Process Stuck

**Symptoms**: Process doesn't complete, no progress

**Solutions**:
1. Check process logs in CI/CD pipeline
2. Check database locks: `SELECT * FROM pg_locks WHERE NOT granted;`
3. Check database connection: `pg_isready -h <host> -p 5432`
4. Kill stuck process and retry

### Migration Failed Mid-Way

**Symptoms**: Some migrations applied, some failed

**Solutions**:
1. Check Flyway metadata: `SELECT * FROM flyway_schema_history;`
2. Run repair: `java -jar peegeeq-migrations.jar repair`
3. Fix failed migration SQL
4. Re-run: `java -jar peegeeq-migrations.jar migrate`

### Rollback Needed

**Flyway doesn't support automatic rollback**. Manual process:

1. Create rollback migration: `V003__Rollback_Feature.sql`
2. Test rollback locally
3. Apply rollback migration
4. Deploy previous application version

---

## See Also

- **[PEEGEEQ_MIGRATIONS_README.md](PEEGEEQ_MIGRATIONS_README.md)** - Overview and quick start
- **[PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** - Standalone JAR documentation
- **[scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md)** - Dev scripts documentation

